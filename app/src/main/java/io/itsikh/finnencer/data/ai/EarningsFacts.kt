package io.itsikh.finnencer.data.ai

import io.itsikh.finnencer.data.api.FinnhubRecommendation
import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.data.entity.TickerAnalystSnapshot
import io.itsikh.finnencer.data.entity.TickerMetrics
import io.itsikh.finnencer.data.entity.fiscalLabelOrNull
import io.itsikh.finnencer.data.providers.EarningsPressRelease
import io.itsikh.finnencer.data.providers.EdgarCikLookup
import io.itsikh.finnencer.data.providers.EdgarPressReleaseProvider
import io.itsikh.finnencer.data.providers.EdgarXbrlExtractor
import io.itsikh.finnencer.data.providers.PostEarningsReaction
import io.itsikh.finnencer.data.providers.PostEarningsReactionProvider
import io.itsikh.finnencer.data.providers.XbrlQuarter
import io.itsikh.finnencer.data.providers.XbrlSpanValue
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.repo.TickerMetricsRepo
import io.itsikh.finnencer.logging.AppLogger
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One ground-truth figure about the print.
 *
 * Every number that reaches a prompt goes through here, and every one of
 * them was either filed with the SEC or computed from filed numbers by
 * [EarningsFactsBuilder] — never by a language model. That's the whole
 * point of the type: the model's job is to explain and contextualize
 * these, not to derive them.
 */
data class EarningsFact(
    /** Human label, e.g. "Revenue" or "Operating margin". */
    val label: String,
    /** Rendered value, e.g. "$43.83B" or "62.4%". */
    val display: String,
    /** Optional trailing context, e.g. "+12.4% YoY · +3.1% QoQ". */
    val context: String? = null,
    /** Section heading this fact renders under. */
    val group: String,
    /**
     * Digit fragments that count as "the script cited this figure", used
     * by the approximate density diagnostic. Empty for non-numeric facts.
     */
    val matchTokens: List<String> = emptyList(),
) {
    /** One-line form used in the prompt body and the uncovered-facts list. */
    val line: String get() = if (context.isNullOrBlank()) "$label: $display" else "$label: $display ($context)"
}

/**
 * Signals used by [PodcastAutoSizer] to decide report depth and episode
 * length. Deliberately separate from the rendered facts: sizing looks at
 * how much substance exists, not at how it reads.
 */
data class EarningsContentSignals(
    /** Count of distinct financial metrics we actually resolved. */
    val metricCount: Int,
    /** How many comparison periods we have (year-ago, prior quarter, trend rows). */
    val comparativeQuarters: Int,
    val guidanceSentenceCount: Int,
    val pressReleaseChars: Int,
    /** Absolute EPS/revenue surprise vs consensus, in percent. */
    val surprisePctAbs: Double?,
    /** Absolute post-print price move, in percent. */
    val reactionPctAbs: Double?,
    val newsCount: Int,
    /** Price-target high/low spread as a % of the mean — Street disagreement. */
    val analystDispersionPct: Double?,
    val hasSegmentDetail: Boolean,
)

/**
 * The complete grounding block for one earnings print.
 *
 * [markdown] is what goes into prompts. The podcast pipeline additionally
 * filters [numericFacts] against the script written so far to build the
 * "uncovered facts" list re-sent on every continuation pass, so a late
 * segment stays anchored to real figures instead of drifting into
 * speculation.
 */
data class EarningsFactsSheet(
    val ticker: String,
    val companyName: String,
    val periodLabel: String,
    val facts: List<EarningsFact>,
    val markdown: String,
    val signals: EarningsContentSignals,
    val pressRelease: EarningsPressRelease?,
    val reaction: PostEarningsReaction?,
    /** SEC accession of the filing the actuals came from, for traceability. */
    val accession: String?,
) {
    /** Numeric facts only — the denominator of the density diagnostic. */
    val numericFacts: List<EarningsFact> get() = facts.filter { it.matchTokens.isNotEmpty() }

    /**
     * Approximate check of how many of our ground-truth figures actually
     * appear in [script]. Digit-form matching only, so a script that says
     * "about forty-four billion" without the digits under-counts — which
     * is why callers treat this as a diagnostic hint, never a gate.
     */
    fun citedFactCount(script: String): Int = numericFacts.count { fact ->
        fact.matchTokens.any { token ->
            Regex("(?<![\\d.])" + Regex.escape(token) + "(?![\\d])").containsMatchIn(script)
        }
    }
}

/**
 * Assembles [EarningsFactsSheet] from every source we can reach for free:
 * SEC XBRL company facts (authoritative actuals), the 8-K Exhibit 99.1
 * press release (guidance + segments), the Yahoo daily series (price
 * reaction), Finnhub basic financials (valuation), plus the consensus and
 * analyst-coverage rows the caller already has.
 *
 * All arithmetic — margins, YoY/QoQ deltas, FCF, surprise percentages —
 * happens here in Kotlin. Handing a model raw figures and asking it to
 * compute deltas is how reports end up with confidently wrong maths.
 */
@Singleton
class EarningsFactsBuilder @Inject constructor(
    private val xbrl: EdgarXbrlExtractor,
    private val cikLookup: EdgarCikLookup,
    private val tickerDao: TickerDao,
    private val pressReleases: EdgarPressReleaseProvider,
    private val reactions: PostEarningsReactionProvider,
    private val metricsRepo: TickerMetricsRepo,
) {

    /**
     * @param newsCount how many cached news items the caller will include
     *        (feeds Auto sizing only — the articles themselves stay with
     *        the caller's bundle)
     * @param analystSnapshot already-fetched price-target / recommendation
     *        row; [ReportGenerator] owns its refresh + 24h cache, so we
     *        take it as input rather than fetching it twice
     */
    suspend fun build(
        event: EarningsEvent,
        ticker: Ticker,
        newsCount: Int,
        analystSnapshot: TickerAnalystSnapshot?,
        recommendations: List<FinnhubRecommendation>,
    ): EarningsFactsSheet {
        val cik = resolveCik(ticker)
        val reportDate = Instant.ofEpochMilli(event.actualReportedAtMillis ?: event.scheduledAtMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

        // One companyfacts fetch serves the current quarter AND its
        // comparators — that's why we pull the list and match locally
        // instead of calling quarterNear(cik, …), which would refetch.
        val quarters = if (cik != null) {
            runCatching { xbrl.recentQuarters(cik, limit = 16) }
                .onFailure { AppLogger.w(TAG, "XBRL fetch failed for ${ticker.symbol}: ${it.message}") }
                .getOrDefault(emptyList())
        } else emptyList()

        val current = xbrl.quarterNear(quarters, reportDate, windowDays = 60)
        val yearAgo = current?.let { xbrl.yearAgoOf(quarters, it) }
        val priorQuarter = current?.let { xbrl.priorQuarterOf(quarters, it) }

        val pressRelease = if (cik != null) {
            pressReleases.forEarnings(cik, reportDate)
        } else null

        val reaction = reactions.reactionFor(ticker.symbol, reportDate)

        val metrics = runCatching { metricsRepo.load(ticker.symbol) }
            .onFailure { AppLogger.w(TAG, "basicFinancials failed for ${ticker.symbol}: ${it.message}") }
            .getOrNull()

        val facts = buildFacts(
            event = event,
            current = current,
            yearAgo = yearAgo,
            priorQuarter = priorQuarter,
            reaction = reaction,
            metrics = metrics,
            analystSnapshot = analystSnapshot,
            recommendations = recommendations,
        )

        // Trend rows across the recent same-span periods — the single
        // most useful thing for spotting margin direction, and impossible
        // for the model to reconstruct from one quarter.
        val trendRows = current?.let { cur ->
            quarters.filter { it.span == cur.span }
                .sortedByDescending { it.periodEnd }
                .take(TREND_QUARTERS)
        }.orEmpty()

        val surpriseAbs = listOfNotNull(
            surprisePct(event.actualEps ?: current?.epsDiluted, event.consensusEps),
            surprisePct(event.actualRevenue ?: current?.revenue, event.consensusRevenue),
        ).maxByOrNull { kotlin.math.abs(it) }?.let { kotlin.math.abs(it) }

        val dispersion = analystSnapshot?.let { snap ->
            val mean = snap.targetMean
            val hi = snap.targetHigh
            val lo = snap.targetLow
            if (mean != null && hi != null && lo != null && mean > 0) (hi - lo) / mean * 100.0 else null
        }

        val signals = EarningsContentSignals(
            metricCount = facts.count { it.matchTokens.isNotEmpty() },
            // maxOf, not a sum: the year-ago and prior quarters are also
            // rows in the trend table, so adding them double-counts the
            // same history and saturates the cap for any company with a
            // filing record at all.
            comparativeQuarters = maxOf(
                listOfNotNull(yearAgo, priorQuarter).size,
                (trendRows.size - 1).coerceAtLeast(0),
            ),
            guidanceSentenceCount = pressRelease?.guidanceSentences?.size ?: 0,
            pressReleaseChars = pressRelease?.text?.length ?: 0,
            surprisePctAbs = surpriseAbs,
            reactionPctAbs = reaction?.headlineMovePct?.let { kotlin.math.abs(it) },
            newsCount = newsCount,
            analystDispersionPct = dispersion,
            // Segment tables live in the release body; their presence is
            // what makes a segment-by-segment walk possible at all.
            hasSegmentDetail = pressRelease?.text?.let { SEGMENT_HINT_RE.containsMatchIn(it) } == true,
        )

        val periodLabel = event.fiscalLabelOrNull()
            ?: current?.let { "FY${it.fiscalYear} ${it.fiscalPeriod}" }
            ?: "the reported quarter"

        val markdown = renderMarkdown(
            ticker = ticker,
            periodLabel = periodLabel,
            facts = facts,
            current = current,
            trendRows = trendRows,
            pressRelease = pressRelease,
        )

        AppLogger.i(
            TAG,
            "facts sheet ${ticker.symbol} $periodLabel: ${signals.metricCount} metrics, " +
                "${signals.comparativeQuarters} comparatives, ${signals.guidanceSentenceCount} guidance, " +
                "release=${signals.pressReleaseChars}c, segments=${signals.hasSegmentDetail}, " +
                "surprise=${fmtPctOrDash(signals.surprisePctAbs)}, reaction=${fmtPctOrDash(signals.reactionPctAbs)}",
        )

        return EarningsFactsSheet(
            ticker = ticker.symbol,
            companyName = ticker.name,
            periodLabel = periodLabel,
            facts = facts,
            markdown = markdown,
            signals = signals,
            pressRelease = pressRelease,
            reaction = reaction,
            accession = current?.accn,
        )
    }

    /**
     * The Ticker row usually carries a CIK from the EDGAR sync. When it
     * doesn't (sync never ran, or failed while the User-Agent was
     * misconfigured) resolve on demand and persist, so later reports
     * don't repeat the lookup. Targeted column update — a full-row write
     * from this stale snapshot would clobber settings the user changed
     * meanwhile.
     */
    private suspend fun resolveCik(ticker: Ticker): String? {
        ticker.cik?.let { return it }
        val resolved = runCatching { cikLookup.resolve(ticker.symbol) }
            .onFailure { AppLogger.w(TAG, "on-demand CIK lookup failed for ${ticker.symbol}: ${it.message}") }
            .getOrNull()
        if (resolved != null) {
            tickerDao.updateCik(ticker.symbol, resolved)
            AppLogger.i(TAG, "resolved CIK $resolved for ${ticker.symbol} on-demand")
        } else {
            AppLogger.w(
                TAG,
                "${ticker.symbol} has no CIK; XBRL + press-release sections will be empty. " +
                    "Check API keys → EDGAR User-Agent.",
            )
        }
        return resolved
    }

    private fun buildFacts(
        event: EarningsEvent,
        current: XbrlQuarter?,
        yearAgo: XbrlQuarter?,
        priorQuarter: XbrlQuarter?,
        reaction: PostEarningsReaction?,
        metrics: TickerMetrics?,
        analystSnapshot: TickerAnalystSnapshot?,
        recommendations: List<FinnhubRecommendation>,
    ): List<EarningsFact> {
        val out = mutableListOf<EarningsFact>()

        // ───── Headline ─────
        money(
            out, G_HEADLINE, "Revenue", current?.revenue,
            context = deltaContext(current?.revenue, yearAgo?.revenue, priorQuarter?.revenue),
        )
        val consensusRev = event.consensusRevenue
        val actualRev = event.actualRevenue ?: current?.revenue
        surprisePct(actualRev, consensusRev)?.let { pct ->
            out += EarningsFact(
                label = "Revenue vs consensus",
                display = "${signed(pct)}% ${beatMiss(pct)}",
                context = "actual ${humanMoney(actualRev)} vs consensus ${humanMoney(consensusRev)}",
                group = G_HEADLINE,
                matchTokens = tokensForPct(pct),
            )
        }
        plain(
            out, G_HEADLINE, "EPS (diluted)", current?.epsDiluted?.let { "$" + fmt2(it) },
            context = deltaContext(current?.epsDiluted, yearAgo?.epsDiluted, priorQuarter?.epsDiluted),
            tokens = current?.epsDiluted?.let { tokensForRaw(it) }.orEmpty(),
        )
        val consensusEps = event.consensusEps
        val actualEps = event.actualEps ?: current?.epsDiluted
        surprisePct(actualEps, consensusEps)?.let { pct ->
            out += EarningsFact(
                label = "EPS vs consensus",
                display = "${signed(pct)}% ${beatMiss(pct)}",
                context = "actual $${fmt2(actualEps!!)} vs consensus $${fmt2(consensusEps!!)}",
                group = G_HEADLINE,
                matchTokens = tokensForPct(pct) + tokensForRaw(consensusEps),
            )
        }
        if (current == null) {
            out += EarningsFact(
                label = "SEC XBRL actuals",
                display = "not available for this period",
                context = "treat consensus figures as ESTIMATES; do not present them as results",
                group = G_HEADLINE,
            )
        }

        // ───── Profitability ─────
        money(
            out, G_PROFIT, "Gross profit", current?.grossProfitOrDerived,
            context = deltaContext(
                current?.grossProfitOrDerived,
                yearAgo?.grossProfitOrDerived,
                priorQuarter?.grossProfitOrDerived,
            ),
        )
        marginFact(out, "Gross margin", current?.grossMarginPct, yearAgo?.grossMarginPct, priorQuarter?.grossMarginPct)
        money(out, G_PROFIT, "Operating income", current?.operatingIncome, deltaContext(current?.operatingIncome, yearAgo?.operatingIncome, priorQuarter?.operatingIncome))
        marginFact(out, "Operating margin", current?.operatingMarginPct, yearAgo?.operatingMarginPct, priorQuarter?.operatingMarginPct)
        money(out, G_PROFIT, "Net income", current?.netIncome, deltaContext(current?.netIncome, yearAgo?.netIncome, priorQuarter?.netIncome))
        marginFact(out, "Net margin", current?.netMarginPct, yearAgo?.netMarginPct, priorQuarter?.netMarginPct)

        // ───── Cost structure ─────
        money(out, G_COSTS, "Cost of revenue", current?.costOfRevenue, deltaContext(current?.costOfRevenue, yearAgo?.costOfRevenue, null))
        current?.researchAndDevelopment?.let { rnd ->
            out += EarningsFact(
                label = "R&D expense",
                display = humanMoney(rnd),
                context = listOfNotNull(
                    current.rndPctOfRevenue?.let { "${fmt1(it)}% of revenue" },
                    pctDelta(rnd, yearAgo?.researchAndDevelopment)?.let { "${signed(it)}% YoY" },
                ).joinToString(" · ").ifBlank { null },
                group = G_COSTS,
                matchTokens = tokensForMoney(rnd),
            )
        }
        money(out, G_COSTS, "SG&A expense", current?.sellingGeneralAdmin, deltaContext(current?.sellingGeneralAdmin, yearAgo?.sellingGeneralAdmin, null))
        money(out, G_COSTS, "Total operating expenses", current?.operatingExpenses, deltaContext(current?.operatingExpenses, yearAgo?.operatingExpenses, null))

        // ───── Cash flow. Span-labelled: a 10-Q files these YTD, and a
        //       9-month figure passed off as quarterly is a real error. ─────
        spanMoney(out, "Operating cash flow", current?.operatingCashFlow)
        spanMoney(out, "Capital expenditure", current?.capex)
        current?.freeCashFlow?.let { fcf ->
            val marginCtx = current.revenue
                ?.takeIf { it > 0 && fcf.isStandaloneQuarter }
                ?.let { "${fmt1(fcf.value / it * 100)}% of revenue" }
            out += EarningsFact(
                label = "Free cash flow",
                display = humanMoney(fcf.value),
                context = listOfNotNull("${fcf.spanLabel} figure", marginCtx, "operating cash flow minus capex").joinToString(" · "),
                group = G_CASH,
                matchTokens = tokensForMoney(fcf.value),
            )
        }
        spanMoney(out, "Share buybacks", current?.buybacks)
        spanMoney(out, "Dividends paid", current?.dividendsPaid)

        // ───── Balance sheet ─────
        money(out, G_BALANCE, "Cash & equivalents", current?.cashAndEquivalents)
        money(out, G_BALANCE, "Long-term debt", current?.totalDebt)
        val netCash = current?.let { q ->
            val c = q.cashAndEquivalents
            val d = q.totalDebt
            if (c != null && d != null) c - d else null
        }
        money(out, G_BALANCE, "Net cash position", netCash)
        money(out, G_BALANCE, "Shareholders' equity", current?.stockholdersEquity)
        current?.dilutedShares?.let { shares ->
            out += EarningsFact(
                label = "Diluted shares outstanding",
                display = "${fmt2(shares / 1_000_000.0)}M",
                context = pctDelta(shares, yearAgo?.dilutedShares)?.let {
                    "${signed(it)}% YoY — ${if (it < 0) "net buyback shrinking the count" else "dilution"}"
                },
                group = G_BALANCE,
                matchTokens = tokensForRaw(shares / 1_000_000.0),
            )
        }

        // ───── Market reaction ─────
        reaction?.takeIf { it.hasAnyMove }?.let { r ->
            r.sessionOfPct?.let {
                out += EarningsFact(
                    label = "Price move, session of the print",
                    display = "${signed(it)}%",
                    context = "close ${fmt2Or(r.priorClose)} → ${fmt2Or(r.sessionOfClose)}",
                    group = G_MARKET,
                    matchTokens = tokensForPct(it),
                )
            }
            r.nextSessionPct?.let {
                out += EarningsFact(
                    label = "Price move, next session",
                    display = "${signed(it)}%",
                    context = "close ${fmt2Or(r.sessionOfClose)} → ${fmt2Or(r.nextSessionClose)}",
                    group = G_MARKET,
                    matchTokens = tokensForPct(it),
                )
            }
            r.driftSincePct?.let {
                out += EarningsFact(
                    label = "Drift since the reaction",
                    display = "${signed(it)}%",
                    context = "now ${fmt2Or(r.latestClose)} — the move ${if (it < -1) "faded" else if (it > 1) "extended" else "held"}",
                    group = G_MARKET,
                    matchTokens = tokensForPct(it),
                )
            }
            r.reactionVolume?.let { vol ->
                val avg = metrics?.avgVol3m?.takeIf { it > 0 }
                out += EarningsFact(
                    label = "Reaction-day volume",
                    display = "${fmt1(vol / 1_000_000.0)}M shares",
                    context = avg?.let { "${fmt1(vol / (it * 1_000_000.0))}× the 3-month average" },
                    group = G_MARKET,
                    matchTokens = tokensForRaw(vol / 1_000_000.0),
                )
            }
        }

        // ───── Valuation ─────
        metrics?.let { m ->
            money(out, G_VALUATION, "Market cap", m.marketCap?.let { it * 1_000_000.0 })
            plain(out, G_VALUATION, "P/E (TTM)", m.peTtm?.let { fmt1(it) }, tokens = m.peTtm?.let { tokensForRaw(it) }.orEmpty())
            plain(out, G_VALUATION, "Price / sales (TTM)", m.priceToSales?.let { fmt1(it) }, tokens = m.priceToSales?.let { tokensForRaw(it) }.orEmpty())
            plain(out, G_VALUATION, "Revenue growth (TTM YoY)", m.revGrowthYoy?.let { "${fmt1(it)}%" }, tokens = m.revGrowthYoy?.let { tokensForRaw(it) }.orEmpty())
            plain(out, G_VALUATION, "Beta", m.beta?.let { fmt2(it) }, tokens = emptyList())
            val hi = m.fiftyTwoWeekHigh
            val lo = m.fiftyTwoWeekLow
            val px = reaction?.latestClose
            if (hi != null && lo != null && hi > lo) {
                val pos = px?.let { (it - lo) / (hi - lo) * 100.0 }
                out += EarningsFact(
                    label = "52-week range",
                    display = "${fmt2(lo)} – ${fmt2(hi)}",
                    context = pos?.let { "currently ${fmt0(it)}% of the way up the range" },
                    group = G_VALUATION,
                    matchTokens = tokensForRaw(hi) + tokensForRaw(lo),
                )
            }
        }

        // ───── Street ─────
        analystSnapshot?.let { snap ->
            val mean = snap.targetMean
            if (mean != null) {
                val upside = reaction?.latestClose?.takeIf { it > 0 }?.let { (mean - it) / it * 100.0 }
                out += EarningsFact(
                    label = "Analyst price target",
                    display = "mean ${fmt2(mean)}",
                    context = listOfNotNull(
                        snap.targetLow?.let { lo -> snap.targetHigh?.let { hi -> "range ${fmt2(lo)}–${fmt2(hi)}" } },
                        upside?.let { "${signed(it)}% vs the current price" },
                        snap.lastUpdated?.let { "as of $it" },
                    ).joinToString(" · ").ifBlank { null },
                    group = G_STREET,
                    matchTokens = tokensForRaw(mean),
                )
            }
        }
        recommendations.firstOrNull()?.let { r ->
            // Finnhub leaves any bucket it has no data for as null.
            val strongBuy = r.strongBuy ?: 0
            val buy = r.buy ?: 0
            val hold = r.hold ?: 0
            val sell = r.sell ?: 0
            val strongSell = r.strongSell ?: 0
            val total = strongBuy + buy + hold + sell + strongSell
            if (total > 0) {
                out += EarningsFact(
                    label = "Recommendation split",
                    display = "${strongBuy + buy} buy · $hold hold · ${sell + strongSell} sell",
                    context = "$total analysts" + (r.period?.let { ", period $it" } ?: ""),
                    group = G_STREET,
                    matchTokens = emptyList(),
                )
            }
        }

        return out
    }

    // ───────────────────────── rendering ─────────────────────────

    private fun renderMarkdown(
        ticker: Ticker,
        periodLabel: String,
        facts: List<EarningsFact>,
        current: XbrlQuarter?,
        trendRows: List<XbrlQuarter>,
        pressRelease: EarningsPressRelease?,
    ): String = buildString {
        append("# GROUND-TRUTH FACTS — ").append(ticker.symbol).append(" (").append(ticker.name)
        append("), ").append(periodLabel).append('\n')
        append("Every figure below is filed with the SEC or computed from filed figures. ")
        append("These are the ONLY numbers you may state. Do not derive new percentages, ")
        append("deltas, or margins — if a comparison isn't listed here, it isn't available.\n")

        current?.let { q ->
            append("\nPeriod: ").append(q.periodStart).append(" to ").append(q.periodEnd)
            append(" (FY").append(q.fiscalYear).append(' ').append(q.fiscalPeriod)
            append(", ").append(q.form).append(", ")
            append(
                when (q.span) {
                    XbrlQuarter.Span.QUARTER -> "STANDALONE QUARTER"
                    XbrlQuarter.Span.ANNUAL -> "FULL FISCAL YEAR — XBRL did not tag a standalone Q4"
                    XbrlQuarter.Span.YTD -> "YTD CUMULATIVE"
                },
            )
            append(")\n")
            q.accn?.let { append("SEC accession: ").append(it).append('\n') }
        }

        facts.groupBy { it.group }
            .forEach { (group, groupFacts) ->
                append("\n## ").append(group).append('\n')
                groupFacts.forEach { append(" - ").append(it.line).append('\n') }
            }

        if (trendRows.size > 1) {
            append("\n## Trend across recent periods (oldest last)\n")
            append("| Period end | Revenue | Gross margin | Operating margin | Diluted EPS |\n")
            append("|---|---|---|---|---|\n")
            trendRows.forEach { q ->
                append("| ").append(q.periodEnd)
                append(" | ").append(q.revenue?.let { humanMoney(it) } ?: "—")
                append(" | ").append(q.grossMarginPct?.let { "${fmt1(it)}%" } ?: "—")
                append(" | ").append(q.operatingMarginPct?.let { "${fmt1(it)}%" } ?: "—")
                append(" | ").append(q.epsDiluted?.let { "$" + fmt2(it) } ?: "—")
                append(" |\n")
            }
        }

        if (pressRelease != null) {
            if (pressRelease.guidanceSentences.isNotEmpty()) {
                append("\n## Forward guidance — verbatim from the press release\n")
                append("Quote or paraphrase these; do not invent guidance beyond them.\n")
                pressRelease.guidanceSentences.forEach { append(" - \"").append(it).append("\"\n") }
            }
            append("\n## Earnings press release (8-K Exhibit 99.1")
            pressRelease.itemCodes?.let { append(", items ").append(it) }
            append(", filed ").append(pressRelease.filedDate).append(")\n")
            append("Source: ").append(pressRelease.sourceUrl).append('\n')
            append(
                "Segment revenue, business-unit detail and management commentary live in this text. " +
                    "Table cells are separated by \" | \". Use it for segment breakdowns and quotes; " +
                    "for any headline figure prefer the verified numbers above.",
            )
            if (pressRelease.truncated) append(" (Release was truncated at ${EdgarPressReleaseProvider.MAX_TEXT_CHARS} characters.)")
            append("\n\n").append(pressRelease.text).append('\n')
        } else {
            append("\n## Earnings press release\n")
            append("Not available — no Item 2.02 8-K exhibit could be retrieved. ")
            append("There is therefore NO guidance and NO segment data. Say so explicitly ")
            append("rather than speculating about either.\n")
        }
    }

    // ───────────────────────── fact helpers ─────────────────────────

    private fun money(
        out: MutableList<EarningsFact>,
        group: String,
        label: String,
        value: Double?,
        context: String? = null,
    ) {
        if (value == null) return
        out += EarningsFact(
            label = label,
            display = humanMoney(value),
            context = context,
            group = group,
            matchTokens = tokensForMoney(value),
        )
    }

    /** Cash-flow figures carry their reporting span, because a 10-Q files
     *  them year-to-date and the label is the difference between a
     *  correct statement and a wrong one. */
    private fun spanMoney(out: MutableList<EarningsFact>, label: String, value: XbrlSpanValue?) {
        if (value == null) return
        out += EarningsFact(
            label = label,
            display = humanMoney(value.value),
            context = "${value.spanLabel} figure (${value.periodStart} to ${value.periodEnd})",
            group = G_CASH,
            matchTokens = tokensForMoney(value.value),
        )
    }

    private fun plain(
        out: MutableList<EarningsFact>,
        group: String,
        label: String,
        display: String?,
        context: String? = null,
        tokens: List<String> = emptyList(),
    ) {
        if (display == null) return
        out += EarningsFact(label, display, context, group, tokens)
    }

    /** Margins get their YoY/QoQ change in basis points — the unit
     *  analysts actually use, and a change the model would otherwise
     *  compute (and round) itself. */
    private fun marginFact(
        out: MutableList<EarningsFact>,
        label: String,
        current: Double?,
        yearAgo: Double?,
        priorQuarter: Double?,
    ) {
        if (current == null) return
        // Whole basis points — the prompt tells the model to quote this
        // figure verbatim, and "+180 bps" is how an analyst says it.
        val ctx = listOfNotNull(
            yearAgo?.let { "${signedBps(current - it)} bps YoY" },
            priorQuarter?.let { "${signedBps(current - it)} bps QoQ" },
        ).joinToString(" · ").ifBlank { null }
        out += EarningsFact(
            label = label,
            display = "${fmt1(current)}%",
            context = ctx,
            group = G_PROFIT,
            matchTokens = tokensForRaw(current),
        )
    }

    /** "+12.4% YoY · +3.1% QoQ", omitting whichever comparator is absent. */
    private fun deltaContext(current: Double?, yearAgo: Double?, priorQuarter: Double?): String? {
        if (current == null) return null
        return listOfNotNull(
            pctDelta(current, yearAgo)?.let { "${signed(it)}% YoY" },
            pctDelta(current, priorQuarter)?.let { "${signed(it)}% QoQ" },
        ).joinToString(" · ").ifBlank { null }
    }

    /**
     * Percent change, or null when it would be meaningless. A negative or
     * zero base makes the percentage nonsense (going from -$1M to +$2M is
     * not "+300%"), and reporting it anyway is worse than omitting it.
     */
    private fun pctDelta(current: Double?, base: Double?): Double? {
        if (current == null || base == null || base <= 0.0) return null
        return (current - base) / base * 100.0
    }

    /** Surprise vs consensus. Consensus can legitimately be negative (a
     *  company expected to lose money), so this uses the absolute base. */
    private fun surprisePct(actual: Double?, consensus: Double?): Double? {
        if (actual == null || consensus == null || consensus == 0.0) return null
        return (actual - consensus) / kotlin.math.abs(consensus) * 100.0
    }

    private fun beatMiss(pct: Double): String = when {
        pct > 0.5 -> "BEAT"
        pct < -0.5 -> "MISS"
        else -> "in line"
    }

    // ───────────────────────── formatting ─────────────────────────

    /** "$39.33B" / "$456.7M". Handles negatives (losses, outflows). */
    private fun humanMoney(d: Double?): String {
        if (d == null) return "—"
        val abs = kotlin.math.abs(d)
        val sign = if (d < 0) "-" else ""
        return sign + when {
            abs >= 1_000_000_000_000.0 -> "$%.2fT".format(abs / 1_000_000_000_000.0)
            abs >= 1_000_000_000.0 -> "$%.2fB".format(abs / 1_000_000_000.0)
            abs >= 1_000_000.0 -> "$%.1fM".format(abs / 1_000_000.0)
            abs >= 1_000.0 -> "$%.0fK".format(abs / 1_000.0)
            else -> "$%.0f".format(abs)
        }
    }

    private fun fmt0(d: Double) = "%.0f".format(d)
    private fun fmt1(d: Double) = "%.1f".format(d)
    private fun fmt2(d: Double) = "%.2f".format(d)
    private fun fmt2Or(d: Double?) = if (d == null) "—" else "$%.2f".format(d)
    private fun signed(d: Double) = "%+.1f".format(d)
    /** Percentage-point difference rendered as whole basis points. */
    private fun signedBps(percentagePointDelta: Double) = "%+.0f".format(percentagePointDelta * 100)
    private fun fmtPctOrDash(d: Double?) = if (d == null) "—" else "%.1f%%".format(d)

    /**
     * Digit fragments that count as citing a money figure: the scaled
     * value at two and one decimal places ("43.83", "43.8") plus the
     * whole-unit form ("44"). Matching is word-bounded at the call site
     * so "44" can't be satisfied by "1944".
     */
    private fun tokensForMoney(d: Double): List<String> {
        val abs = kotlin.math.abs(d)
        // Must use the SAME scale buckets as humanMoney, or the tokens we
        // look for aren't the digits we printed — a "$45.0K" fact would be
        // hunted for as "45000".
        val scaled = when {
            abs >= 1_000_000_000_000.0 -> abs / 1_000_000_000_000.0
            abs >= 1_000_000_000.0 -> abs / 1_000_000_000.0
            abs >= 1_000_000.0 -> abs / 1_000_000.0
            abs >= 1_000.0 -> abs / 1_000.0
            else -> abs
        }
        return tokensForRaw(scaled)
    }

    private fun tokensForRaw(d: Double): List<String> {
        val abs = kotlin.math.abs(d)
        return listOf("%.2f".format(abs), "%.1f".format(abs), "%.0f".format(abs))
            .distinct()
            // A bare "0" or "1" matches almost any script; drop tokens too
            // short to be evidence of anything.
            .filter { it.length >= 2 }
    }

    private fun tokensForPct(pct: Double): List<String> = tokensForRaw(pct)

    private companion object {
        const val TAG = "EarningsFacts"
        const val TREND_QUARTERS = 6

        const val G_HEADLINE = "Headline results"
        const val G_PROFIT = "Profitability"
        const val G_COSTS = "Cost structure"
        const val G_CASH = "Cash flow"
        const val G_BALANCE = "Balance sheet"
        const val G_MARKET = "Market reaction"
        const val G_VALUATION = "Valuation"
        const val G_STREET = "Street positioning"

        /** Cheap presence test for segment reporting in a release body. */
        private val SEGMENT_HINT_RE = Regex(
            "\\b(segment|reportable segments|by segment|business unit|product revenue|" +
                "revenue by|geographic)\\b",
            RegexOption.IGNORE_CASE,
        )
    }
}
