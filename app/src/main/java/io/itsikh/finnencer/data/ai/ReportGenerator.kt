package io.itsikh.finnencer.data.ai

import io.itsikh.finnencer.logging.AppLogger as Log
import com.google.gson.Gson
import io.itsikh.finnencer.data.api.FinnhubRecommendation
import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.dao.TickerAnalystSnapshotDao
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.entity.EarningsReport
import io.itsikh.finnencer.data.entity.ReportTier
import io.itsikh.finnencer.data.entity.TickerAnalystSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates a BRIEF / STANDARD / DEEP earnings report for a given
 * EarningsEvent. Each tier assembles its own source bundle AND uses its own
 * prompt template — per the design doc, "be brief" alone gives shallow
 * coverage of everything; we want deep coverage of what matters.
 *
 * Source mix per tier (without Finnhub Pro transcripts in MVP):
 *  - BRIEF:    consensus + actual numbers + last 5 SEC filings + last 3
 *              high-score news items + price-target snapshot
 *  - STANDARD: + last 10 news items + recommendation-trends history
 *              + filing snippets
 *  - DEEP:     + full last-quarter PT history + full filings list (titles
 *              only — no body extraction yet) + bull/bear framing
 */
@Singleton
class ReportGenerator @Inject constructor(
    private val router: AiRouter,
    private val finnhub: FinnhubService,
    private val tickerDao: TickerDao,
    private val newsDao: NewsDao,
    private val earningsDao: EarningsDao,
    private val analystSnapshotDao: TickerAnalystSnapshotDao,
    private val promptPrefs: PromptPreferences,
    private val xbrl: io.itsikh.finnencer.data.providers.EdgarXbrlExtractor,
    private val cikLookup: io.itsikh.finnencer.data.providers.EdgarCikLookup,
    private val gson: Gson,
) {

    suspend fun generate(eventId: Long, tier: ReportTier): Long {
        val event = earningsDao.getEvent(eventId)
            ?: error("EarningsEvent $eventId not found")
        val ticker = tickerDao.get(event.tickerSymbol)
            ?: error("Ticker ${event.tickerSymbol} no longer in watchlist")

        // ───────── Source bundle ─────────
        val bundle = StringBuilder()
        bundle.append("# ${ticker.symbol} — ${ticker.name}\n")
        bundle.append("Fiscal Q${event.fiscalQuarter} ${event.fiscalYear}\n")
        bundle.append("Scheduled: ${event.scheduledAtMillis}\n")
        if (event.actualReportedAtMillis != null) {
            bundle.append("Reported: ${event.actualReportedAtMillis}\n")
        }
        bundle.append("\n## Consensus (from Finnhub)\n")
        bundle.append(" - EPS consensus: ${fmt(event.consensusEps)}\n")
        bundle.append(" - Revenue consensus: ${fmt(event.consensusRevenue)}\n")

        // ───── Actual results from SEC EDGAR XBRL ─────
        // This is the authoritative source: the company's own
        // mandatory-XBRL income statement, parsed by the SEC. Fetched
        // synchronously per report so the LLM always has real numbers
        // even when the periodic Finnhub numeric sync didn't (or
        // couldn't) backfill the EarningsEvent row.
        //
        // If the Ticker row doesn't have a CIK yet (EDGAR sync failed
        // earlier — e.g. while the User-Agent was still misconfigured —
        // and the cached failure expired before the sync re-ran), look
        // it up on-demand and persist so we don't refetch on every
        // future report.
        var cik = ticker.cik
        if (cik == null) {
            cik = runCatching { cikLookup.resolve(ticker.symbol) }
                .onFailure { Log.w(TAG, "on-demand CIK lookup failed for ${ticker.symbol}: ${it.message}") }
                .getOrNull()
            if (cik != null) {
                tickerDao.update(ticker.copy(cik = cik))
                Log.i(TAG, "resolved CIK $cik for ${ticker.symbol} on-demand")
            } else {
                Log.w(TAG, "${ticker.symbol} has no CIK; XBRL section will be empty. EDGAR sync hasn't resolved it — check API keys → EDGAR User-Agent.")
            }
        }
        val xbrlQuarter = if (cik != null) {
            val eventDate = java.time.Instant.ofEpochMilli(event.scheduledAtMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
            runCatching { xbrl.quarterNear(cik, eventDate, windowDays = 60) }
                .onFailure { Log.w(TAG, "XBRL fetch failed for ${ticker.symbol}: ${it.message}") }
                .getOrNull()
        } else null

        bundle.append("\n## Actual results (SEC EDGAR XBRL — authoritative)\n")
        if (xbrlQuarter == null) {
            bundle.append("(no XBRL filing found near this date; treat consensus block as ESTIMATE only)\n")
            // Fall back to Finnhub-derived actuals if available — better
            // than no numbers at all.
            if (event.actualEps != null || event.actualRevenue != null) {
                bundle.append(" - Fallback (Finnhub actuals): EPS=${fmt(event.actualEps)}, Revenue=${fmt(event.actualRevenue)}\n")
            }
        } else {
            val spanLabel = when (xbrlQuarter.span) {
                io.itsikh.finnencer.data.providers.XbrlQuarter.Span.QUARTER -> "STANDALONE QUARTER"
                io.itsikh.finnencer.data.providers.XbrlQuarter.Span.ANNUAL -> "FULL FISCAL YEAR (annual aggregate — XBRL did not tag a standalone Q4)"
                io.itsikh.finnencer.data.providers.XbrlQuarter.Span.YTD -> "YTD CUMULATIVE"
            }
            bundle.append(" - Period: ${xbrlQuarter.periodStart} to ${xbrlQuarter.periodEnd} (FY${xbrlQuarter.fiscalYear} ${xbrlQuarter.fiscalPeriod}, ${xbrlQuarter.form}) — $spanLabel\n")
            xbrlQuarter.revenue?.let { bundle.append(" - Revenue: \$${humanMoney(it)} (raw: $it)\n") }
            xbrlQuarter.grossProfit?.let { bundle.append(" - Gross profit: \$${humanMoney(it)} (raw: $it)\n") }
            xbrlQuarter.netIncome?.let { bundle.append(" - Net income: \$${humanMoney(it)} (raw: $it)\n") }
            xbrlQuarter.epsDiluted?.let { bundle.append(" - EPS diluted: \$$it\n") }
            xbrlQuarter.epsBasic?.let { bundle.append(" - EPS basic: \$$it\n") }
            xbrlQuarter.accn?.let { bundle.append(" - SEC accession: $it\n") }
            Log.i(TAG, "XBRL: ${ticker.symbol} ${xbrlQuarter.fiscalPeriod} FY${xbrlQuarter.fiscalYear} ${xbrlQuarter.span} rev=${xbrlQuarter.revenue} eps=${xbrlQuarter.epsDiluted}")
        }

        val newsLimit = when (tier) {
            ReportTier.BRIEF -> 5; ReportTier.STANDARD -> 12; ReportTier.DEEP -> 25
        }
        val recent = newsDao.recentForTicker(ticker.symbol, newsLimit)
        bundle.append("\n## Recent news titles\n")
        if (recent.isEmpty()) {
            bundle.append("(no recent news in local cache)\n")
        } else {
            recent.forEach { a ->
                val snippet = a.snippet?.take(160)?.replace("\n", " ")
                bundle.append(" - [${a.sourceName}] ${a.title}")
                if (!snippet.isNullOrBlank()) bundle.append(" — ").append(snippet)
                bundle.append('\n')
            }
        }

        // Analyst layer — read from the daily-cached snapshot so a
        // regenerate (or running 3 tiers back-to-back) doesn't burn 6
        // Finnhub calls on data that changes once a day at most.
        val snap = analystSnapshot(ticker.symbol)
        bundle.append("\n## Analyst price target\n")
        bundle.append(
            " - mean ${fmt(snap?.targetMean)} high ${fmt(snap?.targetHigh)} low ${fmt(snap?.targetLow)} " +
                "(as of ${snap?.lastUpdated ?: "?"})\n"
        )
        val recs = parseRecommendationTrends(snap?.recommendationTrendsJson)
        if (recs.isNotEmpty()) {
            bundle.append("\n## Recommendation trends (latest first)\n")
            val take = when (tier) { ReportTier.BRIEF -> 1; ReportTier.STANDARD -> 3; ReportTier.DEEP -> 6 }
            recs.take(take).forEach { r ->
                bundle.append(" - ${r.period}: strongBuy=${r.strongBuy} buy=${r.buy} hold=${r.hold} sell=${r.sell} strongSell=${r.strongSell}\n")
            }
        }

        // ───────── Prompt template ─────────
        val (usage, baseSystem, maxTokens) = when (tier) {
            ReportTier.BRIEF -> Triple(AiUsage.REPORT_BRIEF, BRIEF_PROMPT, 1500)
            ReportTier.STANDARD -> Triple(AiUsage.REPORT_STANDARD, STANDARD_PROMPT, 3500)
            ReportTier.DEEP -> Triple(AiUsage.REPORT_DEEP, DEEP_PROMPT, 6500)
        }
        val system = promptPrefs.applyExtras(
            base = baseSystem,
            extra = promptPrefs.get(usage),
        )

        Log.i(TAG, "generating ${tier.name} report for ${ticker.symbol}")
        val completion = router.complete(
            usage = usage,
            system = system,
            userMessage = bundle.toString(),
            maxTokens = maxTokens,
            temperature = 0.4,
            // PERSONA + per-tier prompt + user "extras" are large and
            // stable across every report at this tier. Cache the system
            // block so a same-day batch (e.g. earnings week) pays cache-
            // read rates on the shared prefix.
            cacheSystem = true,
        )
        val text = completion.text

        val title = "${ticker.symbol} · Q${event.fiscalQuarter} ${event.fiscalYear} · ${tier.name.lowercase().replaceFirstChar { it.uppercase() }}"
        val id = earningsDao.insertReport(
            EarningsReport(
                tickerSymbol = ticker.symbol,
                earningsEventId = event.id,
                tier = tier.name,
                title = title,
                contentMarkdown = text,
                model = completion.modelUsed.id,
                inputTokens = 0, // tracked in ApiUsage by the client
                outputTokens = 0,
                sourcesUsedJson = "[]",
                generatedAtMillis = System.currentTimeMillis(),
            )
        )
        return id
    }

    /**
     * Return a (cache_age <= 24h) analyst snapshot, refreshing from
     * Finnhub if missing or stale. Falls back to the stale cached row
     * if the refresh fails (a stale price target is still useful
     * grounding for an LLM report). Returns null only when there's
     * no cached row AND the API call fails — same shape as the
     * previous inline `runCatching` flow.
     */
    private suspend fun analystSnapshot(symbol: String): TickerAnalystSnapshot? {
        val now = System.currentTimeMillis()
        val cached = analystSnapshotDao.get(symbol)
        if (cached != null && (now - cached.fetchedAtMillis) < ANALYST_TTL_MS) {
            return cached
        }
        val pt = runCatching { finnhub.priceTarget(symbol) }
            .onFailure { Log.w(TAG, "priceTarget refresh failed for $symbol: ${it.message}") }
            .getOrNull()
        val recs = runCatching { finnhub.recommendationTrends(symbol) }
            .onFailure { Log.w(TAG, "recommendationTrends refresh failed for $symbol: ${it.message}") }
            .getOrNull().orEmpty()
        if (pt == null && recs.isEmpty()) {
            // Refresh failed end-to-end. Return whatever we had cached
            // (even stale) so the report still has something to ground
            // on; the next regenerate will try again.
            return cached
        }
        val snap = TickerAnalystSnapshot(
            ticker = symbol,
            fetchedAtMillis = now,
            targetHigh = pt?.targetHigh,
            targetLow = pt?.targetLow,
            targetMean = pt?.targetMean,
            targetMedian = pt?.targetMedian,
            lastUpdated = pt?.lastUpdated,
            recommendationTrendsJson = gson.toJson(recs),
        )
        analystSnapshotDao.upsert(snap)
        return snap
    }

    private fun parseRecommendationTrends(json: String?): List<FinnhubRecommendation> {
        if (json.isNullOrBlank() || json == "null") return emptyList()
        return runCatching {
            gson.fromJson(
                json,
                Array<FinnhubRecommendation>::class.java,
            )?.toList().orEmpty()
        }.getOrElse {
            Log.w(TAG, "recommendation_trends_json parse failed: ${it.message}")
            emptyList()
        }
    }

    private fun fmt(d: Double?): String = if (d == null) "—" else "%.4f".format(d).trimEnd('0').trimEnd('.')

    /** Format a large dollar amount as "39.33B" / "1.245B" / "456.7M" so
     *  the LLM gets a human-readable hint alongside the raw value. */
    private fun humanMoney(d: Double): String = when {
        d >= 1_000_000_000_000.0 -> "%.2fT".format(d / 1_000_000_000_000.0)
        d >= 1_000_000_000.0 -> "%.2fB".format(d / 1_000_000_000.0)
        d >= 1_000_000.0 -> "%.1fM".format(d / 1_000_000.0)
        else -> "%.0f".format(d)
    }

    private companion object {
        const val TAG = "ReportGenerator"
        /** How long the analyst snapshot is considered fresh. Daily
         *  refresh is plenty — Finnhub's price-target field updates at
         *  most a few times per week per ticker. */
        const val ANALYST_TTL_MS = 24L * 60 * 60 * 1000

        /**
         * Common persona/reader framing shared by all three earnings-report
         * tiers. The user (a high-tech professional + investor) wants
         * the AI to skip the "explain technology" warm-up and go
         * straight to how product/strategy choices translate to
         * financial outcomes.
         */
        private const val PERSONA = """
Act as an expert financial analyst and technology strategist.

The reader is a high-tech professional and investor with a strong
understanding of technology, software, and industry dynamics, so do NOT
water down technical concepts. The reader is analyzing this company to
make informed investment decisions, and wants to understand how
technological execution translates to financial success (or failure).

The source data block below contains the company name, fiscal period,
authoritative SEC/EDGAR XBRL numbers, consensus, recent news, and
analyst-coverage snapshots. Use it as the sole grounding for your
analysis — do not invent figures, and call out when a section's data
is sparse rather than speculating."""

        const val BRIEF_PROMPT = """$PERSONA

Write a TWO-PAGE executive brief in Markdown with EXACTLY these sections,
each kept tight (~3-4 sentences):

1. Executive Summary — headline numbers (Revenue, EPS, operating margins)
   vs Wall Street expectations (beat/miss), plus forward-looking guidance.
2. The Good (Bullish Signals) — what went well: revenue drivers,
   successful launches, margin expansions, AI/cloud monetization
   tailwinds. One paragraph.
3. The Bad (Bearish Signals) — what went wrong: missed targets,
   shrinking margins, declining segments, rising R&D-without-ROI,
   supply-chain or competitive-moat issues. One paragraph.
4. Tech & Strategy Quick Take — one paragraph on capital allocation
   to R&D / CapEx and whether management articulated a credible ROI
   path during the call.
5. Investor Takeaway — actionable thesis in two sentences PLUS a
   bullet list of the 2-3 critical KPIs to monitor next quarter.

Length budget: 400-600 words total. No fluff, no preamble, no
disclaimers — start directly with section 1."""

        const val STANDARD_PROMPT = """$PERSONA

Write a FIVE-PAGE comprehensive analysis in Markdown with EXACTLY these
sections. Use prose paragraphs except where a table is requested.

1. Executive Summary
   - Headline numbers (Revenue, EPS, Operating Margins) vs Wall Street
     consensus (beat/miss with the actual delta).
   - The company's forward-looking guidance for next quarter and full
     year, including any explicit raises or cuts vs prior guide.

2. The Good (Bullish Signals)
   - What went well: revenue growth drivers, successful product
     launches, margin expansions.
   - Strong technological tailwinds — successful AI monetization, cloud
     infrastructure growth, increasing market share in key tech
     verticals.

3. The Bad (Bearish Signals)
   - What went wrong: missed targets, shrinking margins, declining
     business segments.
   - Technological or execution headwinds — rising R&D costs without
     clear ROI, supply chain constraints, loss of competitive moat,
     cannibalization of existing products.

4. Tech & Strategy Deep Dive
   - Analyze capital allocation toward R&D and CapEx. Are they
     investing heavily in future tech, and does leadership clearly
     articulate the path to ROI on those investments?
   - Summarize management tone during the Q&A regarding the product
     roadmap. If the source data lacks transcript content, say so and
     reason from prepared-remarks signals + analyst-report language.

5. Investor Takeaway
   - Synthesize into an actionable thesis (constructive / cautious /
     bearish + one-sentence rationale).
   - As an investor, what are the 3-5 most critical KPIs to monitor
     over the next 2-3 quarters to see if the strategy is working?
     Bullet list with the KPI name AND why it matters in one line.

Length budget: 1000-1500 words. Numbers go inline as prose; a single
markdown table at the top of section 1 for Revenue/EPS/margins vs
consensus is welcome but optional. Cite source rows by the bracketed
[sourceName] tag when referencing news items."""

        const val DEEP_PROMPT = """$PERSONA

You are writing a TEN-PAGE deep-dive earnings analysis in Markdown.
The reader already knows the company — skip generic background and
get to specifics quickly. Use the structure below verbatim.

1. Executive Summary
   - Headline numbers (Revenue, EPS, Operating Margins, FCF) vs Wall
     Street consensus, with explicit delta percentages.
   - Forward-looking guidance for next quarter and full year, plus
     any cuts/raises vs prior guide and what management framed as
     the cause.
   - One-sentence bull/bear framing.

2. Numbers vs Consensus (Markdown table)
   - Revenue, EPS (GAAP + non-GAAP if available), gross margin,
     operating margin, FCF, key segment splits — actual / consensus
     / surprise / YoY delta.

3. The Good (Bullish Signals)
   - Revenue growth drivers segment by segment.
   - Successful product launches, design wins, customer expansions.
   - Margin expansion mechanics — where the operating leverage came
     from (mix, pricing, scale).
   - Tech tailwinds: AI monetization KPIs (tokens served, paid AI seats,
     ARR from AI products), cloud infra growth, share gains in
     strategic verticals.

4. The Bad (Bearish Signals)
   - Missed targets, shrinking margins, declining segments.
   - Tech/execution headwinds: rising R&D without articulated ROI,
     supply-chain constraints, eroding competitive moat, product
     cannibalization, technical debt that's slowing roadmap velocity.

5. Tech & Strategy Deep Dive
   - Capital allocation: dollar amounts and % of revenue going to R&D
     vs CapEx vs buybacks/dividends. Compare to prior quarters.
   - Path to ROI on the big bets — is leadership specific about the
     timeline and customer adoption metrics, or vague?
   - Management tone during Q&A (or prepared remarks, if the source
     data omits transcripts). Look for hedging language, deflection,
     or contradictions between segments. Cite specific quotes when
     available.
   - Competitive positioning: who is gaining/losing share in their
     core markets, and what's the technical reason (architecture
     advantage, distribution, ecosystem lock-in, talent density)?

6. Analyst Reaction
   - Synthesize the recommendation-trend data and price-target
     movement. Note magnitude and dispersion (is the Street tightly
     converged or split?).

7. Bull Case
   - 3-5 bullets, each one full sentence, with the linchpin
     assumption named so the reader knows what to falsify.

8. Bear Case
   - Same shape as Bull Case.

9. Risk Factors (5-7 bullets, severity inline as [LOW/MED/HIGH])

10. Comparables / Read-throughs
    - What the print implies for direct peers and adjacent tech names
      (suppliers, customers, competitors).

11. Investor Takeaway — KPIs to Monitor
    - Actionable thesis (one paragraph).
    - 5-8 specific KPIs to watch over the next 2-3 quarters, each
      with: KPI name · current value (if known) · threshold that
      would change the thesis · why it matters.

Length budget: 2500-4000 words. Be specific. Cite source rows from the
input by [sourceName] when referencing news items."""
    }
}
