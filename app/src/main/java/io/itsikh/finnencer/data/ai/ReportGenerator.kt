package io.itsikh.finnencer.data.ai

import io.itsikh.finnencer.logging.AppLogger as Log
import com.google.gson.Gson
import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.entity.EarningsReport
import io.itsikh.finnencer.data.entity.ReportTier
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
    private val promptPrefs: PromptPreferences,
    private val xbrl: io.itsikh.finnencer.data.providers.EdgarXbrlExtractor,
    private val cikLookup: io.itsikh.finnencer.data.providers.EdgarCikLookup,
    @Suppress("unused") private val gson: Gson,
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

        // Analyst layer
        val pt = runCatching { finnhub.priceTarget(ticker.symbol) }.getOrNull()
        val recs = runCatching { finnhub.recommendationTrends(ticker.symbol) }.getOrNull().orEmpty()
        bundle.append("\n## Analyst price target\n")
        bundle.append(" - mean ${fmt(pt?.targetMean)} high ${fmt(pt?.targetHigh)} low ${fmt(pt?.targetLow)} (as of ${pt?.lastUpdated ?: "?"})\n")
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

        const val BRIEF_PROMPT = """
You are a senior equity analyst writing a TWO-PAGE executive brief for a holder of the named ticker.

Output a Markdown document with exactly these sections:
1. Headline (one-sentence summary of the print and its directional read)
2. Numbers (table: revenue, EPS, gross margin, guidance — with consensus deltas)
3. What matters (3 bullets, each one sentence)
4. Risks & next catalyst (one paragraph)

Length budget: 350-550 words total. Plain prose, no fluff, no preamble."""

        const val STANDARD_PROMPT = """
You are a senior equity analyst writing a FIVE-PAGE earnings report for a holder of the named ticker.

Output a Markdown document with these sections:
1. Executive summary (3-4 sentences)
2. Numbers vs consensus (markdown table)
3. Guidance commentary (one paragraph, explicit about beats/misses vs prior guide)
4. Segment / product detail (paragraphs as appropriate)
5. Analyst reaction (synthesize ratings + PT movement)
6. Risks (3-5 bullets)
7. Next catalyst (one paragraph)

Length budget: 1000-1500 words. Prose paragraphs, table for numbers."""

        const val DEEP_PROMPT = """
You are a senior equity analyst writing a TEN-PAGE deep-dive earnings report for a holder of
the named ticker. The reader is sophisticated; they already know the company.

Output a Markdown document with these sections:
1. Executive summary (4-5 sentences with explicit bull/bear framing)
2. Numbers vs consensus (markdown table)
3. Guidance and management tone (paragraphs)
4. Segment / product detail with quantitative depth where data permits
5. Analyst reaction (synthesize ratings + PT history — note magnitude and dispersion)
6. Bull case (3-5 bullets, each one full sentence, with the linchpin assumption named)
7. Bear case (same shape)
8. Risk factors (5-7 bullets with severity inline)
9. Comparables / read-throughs to peers (if applicable)
10. What to watch next quarter (3 specific data points)

Length budget: 2500-4000 words. Be specific. Cite source rows from the input by source name."""
    }
}
