package io.itsikh.finnencer.data.providers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.itsikh.finnencer.data.api.SecEdgarService
import io.itsikh.finnencer.logging.AppLogger
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A value whose reporting period may be wider than the quarter it's
 * attached to. Cash-flow-statement concepts are the reason this exists:
 * a 10-Q reports operating cash flow **year-to-date**, not for the
 * standalone quarter, so a Q3 filing's OCF covers nine months. We keep
 * the number (it's genuinely useful) but carry [days] so the facts sheet
 * can label it honestly instead of passing a 9-month figure off as a
 * quarter.
 */
data class XbrlSpanValue(
    val value: Double,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
) {
    val days: Long get() = ChronoUnit.DAYS.between(periodStart, periodEnd)

    /** True when this covers roughly one quarter, i.e. it IS the
     *  standalone-quarter figure and needs no span caveat. */
    val isStandaloneQuarter: Boolean get() = days in 80..100

    /** Human label for the span: "quarter", "6-month YTD", "FY", … */
    val spanLabel: String
        get() = when {
            days in 80..100 -> "quarter"
            days in 170..195 -> "6-month YTD"
            days in 260..285 -> "9-month YTD"
            days in 350..380 -> "full year"
            else -> "${days}-day period"
        }
}

/**
 * Per-quarter parsed financial facts pulled from EDGAR's XBRL company-
 * facts JSON. One [XbrlQuarter] represents a single fiscal quarter's
 * financial snapshot; values are reported by the company in their own
 * 10-Q / 10-K (NUM-tagged), the SEC parses and exposes them.
 *
 * Any metric may be null if the company didn't tag that line item that
 * quarter. `Revenues` vs
 * `RevenueFromContractWithCustomerExcludingAssessedTax` is the common
 * one (older filings used the first, ASC-606-era used the second);
 * [revenue] picks whichever was reported. Beyond the income statement,
 * tagging practice varies a lot — plenty of filers never tag
 * `OperatingExpenses` as a single line, for instance.
 *
 * Income-statement and cash-flow figures are duration facts (they cover
 * a period); balance-sheet figures ([cashAndEquivalents], [totalDebt],
 * [stockholdersEquity]) are *instant* facts measured at [periodEnd].
 */
data class XbrlQuarter(
    val periodEnd: LocalDate,
    val periodStart: LocalDate,
    val fiscalYear: Int,
    val fiscalPeriod: String, // "Q1" / "Q2" / "Q3" / "Q4" / "FY"
    val form: String,         // "10-Q" / "10-K"
    val accn: String?,        // accession number for traceability
    // ───── Income statement ─────
    val revenue: Double?,
    val epsBasic: Double?,
    val epsDiluted: Double?,
    val grossProfit: Double?,
    val netIncome: Double?,
    val costOfRevenue: Double? = null,
    val operatingIncome: Double? = null,
    val researchAndDevelopment: Double? = null,
    val sellingGeneralAdmin: Double? = null,
    val operatingExpenses: Double? = null,
    // ───── Cash flow (usually YTD in a 10-Q — see XbrlSpanValue) ─────
    val operatingCashFlow: XbrlSpanValue? = null,
    val capex: XbrlSpanValue? = null,
    val buybacks: XbrlSpanValue? = null,
    val dividendsPaid: XbrlSpanValue? = null,
    // ───── Balance sheet (instant, as of periodEnd) ─────
    val cashAndEquivalents: Double? = null,
    val totalDebt: Double? = null,
    val stockholdersEquity: Double? = null,
    // ───── Share count ─────
    val dilutedShares: Double? = null,
) {
    /** Standalone quarter (~90 days, 10-Q), full fiscal year (~365 days,
     *  10-K), or neither (YTD aggregate — Q2-cumulative, Q3-cumulative,
     *  etc.). We KEEP both quarter and year rows because for Q4 prints
     *  most US filers only XBRL-tag the annual figures via the 10-K; the
     *  LLM can still write a meaningful Q4 brief from annual + prior
     *  three quarters of YTD progression. */
    enum class Span { QUARTER, ANNUAL, YTD }

    val span: Span
        get() {
            val days = ChronoUnit.DAYS.between(periodStart, periodEnd)
            return when {
                days in 80..100 -> Span.QUARTER
                days in 350..380 -> Span.ANNUAL
                else -> Span.YTD
            }
        }

    /**
     * Free cash flow. Only computed when operating cash flow and capex
     * cover the SAME period — subtracting a quarterly capex from a
     * 9-month OCF would produce a number that looks authoritative and
     * is wrong. Carries the span so the caller can label it.
     */
    val freeCashFlow: XbrlSpanValue?
        get() {
            val ocf = operatingCashFlow ?: return null
            val cx = capex ?: return null
            if (ocf.periodStart != cx.periodStart || ocf.periodEnd != cx.periodEnd) return null
            // Capex is filed as a positive outflow
            // (PaymentsToAcquirePropertyPlantAndEquipment), so FCF
            // subtracts it.
            return XbrlSpanValue(ocf.value - cx.value, ocf.periodStart, ocf.periodEnd)
        }

    /** Gross margin as a percentage, or null if either input is missing
     *  or revenue is non-positive (a zero-revenue quarter would divide
     *  to infinity). */
    val grossMarginPct: Double? get() = pctOfRevenue(grossProfit)
    val operatingMarginPct: Double? get() = pctOfRevenue(operatingIncome)
    val netMarginPct: Double? get() = pctOfRevenue(netIncome)
    val rndPctOfRevenue: Double? get() = pctOfRevenue(researchAndDevelopment)

    /**
     * Gross profit, falling back to revenue − cost of revenue when the
     * filer tagged the two components but not the subtotal. Common with
     * filers who present a single-step income statement.
     */
    val grossProfitOrDerived: Double?
        get() = grossProfit ?: run {
            val r = revenue ?: return null
            val c = costOfRevenue ?: return null
            r - c
        }

    private fun pctOfRevenue(numerator: Double?): Double? {
        val r = revenue ?: return null
        if (r <= 0.0) return null
        val n = numerator ?: return null
        return n / r * 100.0
    }
}

/**
 * Hits EDGAR's XBRL companyfacts endpoint and extracts the most recent
 * standalone quarters with their key financial numbers. This is the
 * authoritative source for actual results — the SEC parses every public
 * US company's mandatory XBRL filing and exposes the data via this free,
 * no-auth, no-rate-limit-beyond-User-Agent endpoint.
 *
 * Used by [io.itsikh.finnencer.data.ai.EarningsFactsBuilder] to ground
 * earnings reports and podcast scripts in real numbers instead of
 * relying on third-party aggregators whose data tier / coverage gaps
 * caused the recurring "Earnings data unavailable" bug (#23 / #24 / #25).
 */
@Singleton
class EdgarXbrlExtractor @Inject constructor(
    private val edgar: SecEdgarService,
) {

    // In-memory cache of (cik -> raw response JSON). The endpoint returns
    // ~1-2 MB per ticker; SEC asks for ≤ 10 req/sec global, so we cache
    // per-CIK for [CACHE_TTL_MILLIS] to avoid re-fetching during a single
    // session of generating multiple reports for the same ticker.
    // ConcurrentHashMap because this singleton is hit from concurrent
    // coroutines (report generation + syncs); a plain HashMap can corrupt
    // under parallel puts. Two coroutines may still fetch the same CIK
    // once each on a cold cache — harmless, last write wins.
    private data class CacheEntry(val rootObj: JsonObject, val fetchedAt: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

    /**
     * Fetch + parse the company's recent quarters.
     *
     * @param cik zero-padded 10-digit CIK (matches what
     *        [io.itsikh.finnencer.data.entity.Ticker.cik] stores).
     * @param limit max number of standalone quarters to return,
     *        most-recent first.
     */
    suspend fun recentQuarters(cik: String, limit: Int = 8): List<XbrlQuarter> {
        val root = loadCompanyFacts(cik) ?: return emptyList()
        val gaap = root.getAsJsonObject("facts")?.getAsJsonObject("us-gaap")
            ?: return emptyList()

        // Pull per-period values for each concept. Concept fallbacks are
        // intentional: older filings used Revenues, ASC-606-era filings
        // use RevenueFromContractWithCustomerExcludingAssessedTax.
        //
        // The revenue map keeps full PeriodKeys because it doubles as the
        // "spine" that decides which periods we emit at all. Every other
        // concept is joined by (periodStart, periodEnd) — see
        // [durationConcept] for why the looser key matters.
        val revenueKeyed = pickConcept(
            gaap,
            // Order doesn't matter now (we union) — but list both because
            // some companies use only one of the two concepts.
            "Revenues",
            "RevenueFromContractWithCustomerExcludingAssessedTax",
            unit = "USD",
        )

        // ───── Income statement ─────
        val revenue = revenueKeyed.byPeriod()
        val gross = durationConcept(gaap, "GrossProfit", unit = "USD")
        val net = durationConcept(gaap, "NetIncomeLoss", unit = "USD")
        val costOfRevenue = durationConcept(
            gaap,
            "CostOfRevenue",
            "CostOfGoodsAndServicesSold",
            unit = "USD",
        )
        val operatingIncome = durationConcept(gaap, "OperatingIncomeLoss", unit = "USD")
        val rnd = durationConcept(
            gaap,
            "ResearchAndDevelopmentExpense",
            "ResearchAndDevelopmentExpenseExcludingAcquiredInProcessCost",
            unit = "USD",
        )
        val sga = durationConcept(
            gaap,
            "SellingGeneralAndAdministrativeExpense",
            "GeneralAndAdministrativeExpense",
            unit = "USD",
        )
        val opex = durationConcept(
            gaap,
            "OperatingExpenses",
            "CostsAndExpenses",
            unit = "USD",
        )
        val epsBasic = durationConcept(gaap, "EarningsPerShareBasic", unit = "USD/shares")
        val epsDiluted = durationConcept(gaap, "EarningsPerShareDiluted", unit = "USD/shares")

        // ───── Cash flow ─────
        val ocf = durationConcept(
            gaap,
            "NetCashProvidedByUsedInOperatingActivities",
            "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations",
            unit = "USD",
        )
        val capex = durationConcept(
            gaap,
            "PaymentsToAcquirePropertyPlantAndEquipment",
            "PaymentsToAcquireProductiveAssets",
            unit = "USD",
        )
        val buybacks = durationConcept(
            gaap,
            "PaymentsForRepurchaseOfCommonStock",
            unit = "USD",
        )
        val dividends = durationConcept(
            gaap,
            "PaymentsOfDividendsCommonStock",
            "PaymentsOfDividends",
            unit = "USD",
        )

        // ───── Share count ─────
        val dilutedShares = durationConcept(
            gaap,
            "WeightedAverageNumberOfDilutedSharesOutstanding",
            unit = "shares",
        )

        // ───── Balance sheet (instant facts — keyed by date only) ─────
        val cash = instantConcept(
            gaap,
            "CashAndCashEquivalentsAtCarryingValue",
            "CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents",
            unit = "USD",
        )
        val debt = instantConcept(
            gaap,
            "LongTermDebtNoncurrent",
            "LongTermDebt",
            unit = "USD",
        )
        val equity = instantConcept(
            gaap,
            "StockholdersEquity",
            unit = "USD",
        )

        // Use revenue's period set as the spine — every reasonable income
        // statement has a top line, and we want to land on rows the
        // company actually filed. Sort by periodEnd descending and
        // dedupe by (start, end) — XBRL re-files the same period
        // multiple times under different `fy` values (comparative
        // columns in later 10-Ks), and we only want one entry per
        // physical period. Within a tie, prefer the entry whose `fy`
        // matches the period (the original primary filing).
        val periodKeys = revenueKeyed.keys
            .sortedWith(
                compareByDescending<PeriodKey> { it.periodEnd }
                    .thenBy { kotlin.math.abs(it.fiscalYear - it.periodEnd.year) }
            )
            .distinctBy { it.periodStart to it.periodEnd }

        return periodKeys
            .asSequence()
            .map { key ->
                val span = key.periodStart to key.periodEnd
                XbrlQuarter(
                    periodEnd = key.periodEnd,
                    periodStart = key.periodStart,
                    fiscalYear = key.fiscalYear,
                    fiscalPeriod = key.fiscalPeriod,
                    form = key.form,
                    accn = key.accn,
                    revenue = revenue[span],
                    epsBasic = epsBasic[span],
                    epsDiluted = epsDiluted[span],
                    grossProfit = gross[span],
                    netIncome = net[span],
                    costOfRevenue = costOfRevenue[span],
                    operatingIncome = operatingIncome[span],
                    researchAndDevelopment = rnd[span],
                    sellingGeneralAdmin = sga[span],
                    operatingExpenses = opex[span],
                    // Cash-flow statements are filed YTD in a 10-Q, so an
                    // exact (start, end) match usually only exists for Q1
                    // and the 10-K. Fall back to the narrowest span that
                    // ENDS on this period end — that's the most
                    // quarter-like figure the filer gave us, and
                    // XbrlSpanValue carries the label so nobody mistakes
                    // a 9-month number for a quarterly one.
                    operatingCashFlow = ocf.spanEndingAt(span),
                    capex = capex.spanEndingAt(span),
                    buybacks = buybacks.spanEndingAt(span),
                    dividendsPaid = dividends.spanEndingAt(span),
                    cashAndEquivalents = cash[key.periodEnd],
                    totalDebt = debt[key.periodEnd],
                    stockholdersEquity = equity[key.periodEnd],
                    dilutedShares = dilutedShares[span],
                )
            }
            .filter { it.span == XbrlQuarter.Span.QUARTER || it.span == XbrlQuarter.Span.ANNUAL }
            .take(limit)
            .toList()
    }

    /** Find the quarter whose period end is closest to [aroundDate],
     *  within ±[windowDays]. Returns null if nothing's close. */
    suspend fun quarterNear(
        cik: String,
        aroundDate: LocalDate,
        windowDays: Int = 60,
    ): XbrlQuarter? = quarterNear(recentQuarters(cik, limit = 16), aroundDate, windowDays)

    /**
     * Pure variant of [quarterNear] that works off an already-fetched
     * quarter list. Callers that need both the current quarter AND its
     * comparators (year-ago, prior quarter) use this so one
     * companyfacts fetch serves the whole facts sheet.
     */
    fun quarterNear(
        quarters: List<XbrlQuarter>,
        aroundDate: LocalDate,
        windowDays: Int = 60,
    ): XbrlQuarter? = quarters
        .map { it to ChronoUnit.DAYS.between(it.periodEnd, aroundDate) }
        .filter { kotlin.math.abs(it.second) <= windowDays }
        .minByOrNull { kotlin.math.abs(it.second) }
        ?.first

    /**
     * The same fiscal quarter one year before [current], for YoY math.
     * Matched on period LENGTH plus a period-end ~365 days earlier
     * (±25 days) rather than on the fiscal-period label, because
     * `fp` is unreliable on amended filings and 52/53-week fiscal
     * calendars shift the end date year to year.
     */
    fun yearAgoOf(quarters: List<XbrlQuarter>, current: XbrlQuarter): XbrlQuarter? =
        quarters
            .filter { it.span == current.span && it.periodEnd < current.periodEnd }
            .map { it to kotlin.math.abs(ChronoUnit.DAYS.between(it.periodEnd, current.periodEnd) - 365) }
            .filter { it.second <= 25 }
            .minByOrNull { it.second }
            ?.first

    /**
     * The immediately preceding period of the same span, for QoQ math.
     *
     * The gap is range-checked, not just ordered: most filers never
     * XBRL-tag a standalone Q4 (the 10-K carries annual figures only), so
     * "the latest earlier quarter" before a Q1 print is frequently Q3 —
     * two quarters back. Labelling that delta "QoQ" would be quietly
     * wrong, so a period that isn't actually adjacent yields null and the
     * QoQ context is simply omitted.
     */
    fun priorQuarterOf(quarters: List<XbrlQuarter>, current: XbrlQuarter): XbrlQuarter? {
        val expectedGap = when (current.span) {
            XbrlQuarter.Span.QUARTER -> 80L..110L
            XbrlQuarter.Span.ANNUAL -> 350L..380L
            XbrlQuarter.Span.YTD -> return null
        }
        return quarters
            .filter { it.span == current.span && it.periodEnd < current.periodEnd }
            .maxByOrNull { it.periodEnd }
            ?.takeIf { ChronoUnit.DAYS.between(it.periodEnd, current.periodEnd) in expectedGap }
    }

    private suspend fun loadCompanyFacts(cik: String): JsonObject? {
        val now = System.currentTimeMillis()
        cache[cik]?.let { entry ->
            if (now - entry.fetchedAt < CACHE_TTL_MILLIS) return entry.rootObj
        }
        val raw = runCatching { edgar.companyFacts(cik) }
            .onFailure { AppLogger.w(TAG, "companyFacts $cik failed: ${it.message}") }
            .getOrNull() ?: return null
        return runCatching {
            JsonParser.parseString(raw).asJsonObject.also {
                cache[cik] = CacheEntry(it, now)
            }
        }.onFailure { AppLogger.e(TAG, "companyFacts parse failed for $cik", it) }
            .getOrNull()
    }

    private data class PeriodKey(
        val periodStart: LocalDate,
        val periodEnd: LocalDate,
        val fiscalYear: Int,
        val fiscalPeriod: String,
        val form: String,
        val accn: String?,
    )

    private data class Reported(val value: Double)

    /** Collapse a PeriodKey-keyed map to (start, end) — the join key used
     *  for every non-spine concept. */
    private fun Map<PeriodKey, Reported>.byPeriod(): Map<Pair<LocalDate, LocalDate>, Double> {
        val out = LinkedHashMap<Pair<LocalDate, LocalDate>, Double>()
        for ((k, v) in this) out[k.periodStart to k.periodEnd] = v.value
        return out
    }

    /**
     * Duration-fact lookup keyed by (periodStart, periodEnd).
     *
     * Deliberately looser than the full [PeriodKey]: revenue for a given
     * period is often picked up from a later 10-K's comparative column
     * (a different accn/fy than the original 10-Q), and joining R&D or
     * operating income on the full key would silently miss in exactly
     * that case. The same physical period reported by any filing is the
     * same number — and where a restatement differs, later-filing-wins
     * (map insertion order follows EDGAR's chronological array) is the
     * answer we want anyway.
     */
    private fun durationConcept(
        gaap: JsonObject,
        vararg concepts: String,
        unit: String,
    ): Map<Pair<LocalDate, LocalDate>, Double> =
        pickConcept(gaap, *concepts, unit = unit).byPeriod()

    /**
     * Narrowest span that ends on the same date as [span]. Used for
     * cash-flow concepts, which a 10-Q reports year-to-date: for a Q3
     * period there is no 90-day entry, only a 9-month one. Preferring
     * the narrowest available keeps us as close to a quarterly figure as
     * the filer allows, and [XbrlSpanValue.spanLabel] tells the truth
     * about what we got.
     */
    private fun Map<Pair<LocalDate, LocalDate>, Double>.spanEndingAt(
        span: Pair<LocalDate, LocalDate>,
    ): XbrlSpanValue? {
        // Exact match first — that IS the standalone quarter.
        this[span]?.let { return XbrlSpanValue(it, span.first, span.second) }
        return entries
            .filter { it.key.second == span.second }
            .minByOrNull { ChronoUnit.DAYS.between(it.key.first, it.key.second) }
            ?.let { XbrlSpanValue(it.value, it.key.first, it.key.second) }
    }

    /**
     * Instant-fact lookup keyed by the measurement date. Balance-sheet
     * concepts (cash, debt, equity) carry only `end` — no `start` — so
     * [pickConcept]'s duration parser skips them entirely. Hence this
     * separate path.
     */
    private fun instantConcept(
        gaap: JsonObject,
        vararg concepts: String,
        unit: String,
    ): Map<LocalDate, Double> {
        val out = LinkedHashMap<LocalDate, Double>()
        for (concept in concepts) {
            val factObj = gaap.getAsJsonObject(concept) ?: continue
            val units = factObj.getAsJsonObject("units") ?: continue
            val arr = units.getAsJsonArray(unit) ?: continue
            for (el in arr) {
                runCatching {
                    val o = el.asJsonObject
                    // Instant facts have no `start`. If one does, it's a
                    // duration fact and belongs to durationConcept.
                    if (o.get("start")?.takeIf { !it.isJsonNull } != null) return@runCatching
                    val end = o.optString("end") ?: return@runCatching
                    val form = o.optString("form") ?: return@runCatching
                    if (form != "10-Q" && form != "10-K") return@runCatching
                    val value = o.optDouble("val") ?: return@runCatching
                    out[LocalDate.parse(end)] = value
                }.onFailure {
                    AppLogger.w(TAG, "skipping bad instant XBRL row in $concept: ${it.message}")
                }
            }
        }
        return out
    }

    /**
     * Collect entries across every concept in [concepts] and merge by
     * [PeriodKey]. Earlier versions stopped at the first concept that
     * returned anything — that silently dropped the latest data for
     * NVDA et al., which migrated from `Revenues` to
     * `RevenueFromContractWithCustomerExcludingAssessedTax` for the
     * ASC-606 transition (FY2018-FY2022) and then back to `Revenues`.
     * Single-concept lookup found only one half of the timeline. Now
     * we union them.
     */
    private fun pickConcept(
        gaap: JsonObject,
        vararg concepts: String,
        unit: String,
    ): Map<PeriodKey, Reported> {
        val out = LinkedHashMap<PeriodKey, Reported>()
        for (concept in concepts) {
            val factObj = gaap.getAsJsonObject(concept) ?: continue
            val units = factObj.getAsJsonObject("units") ?: continue
            val arr = units.getAsJsonArray(unit) ?: continue
            for (el in arr) {
                val o = el.asJsonObject
                // SEC's XBRL JSON occasionally emits explicit `null` for
                // optional fields (most commonly `fp` on amended or
                // restated filings). Gson surfaces those as JsonNull
                // elements, and calling `.asString` / `.asInt` on
                // JsonNull throws UnsupportedOperationException — that's
                // what surfaced as a bare "JsonNull" error in earlier
                // testing. Wrap each row in runCatching so one bad
                // entry can't poison the whole quarter list.
                runCatching {
                    val start = o.optString("start") ?: return@runCatching
                    val end = o.optString("end") ?: return@runCatching
                    val fy = o.optInt("fy") ?: return@runCatching
                    val fp = o.optString("fp") ?: return@runCatching
                    val form = o.optString("form") ?: return@runCatching
                    // 10-Q (quarter and YTD entries) + 10-K (fiscal year
                    // and Q4 standalone, when companies bother to tag
                    // it). 8-K earnings releases aren't XBRL-tagged for
                    // the income statement, so they don't show up here.
                    if (form != "10-Q" && form != "10-K") return@runCatching
                    val value = o.optDouble("val") ?: return@runCatching
                    val key = PeriodKey(
                        periodStart = LocalDate.parse(start),
                        periodEnd = LocalDate.parse(end),
                        fiscalYear = fy,
                        fiscalPeriod = fp,
                        form = form,
                        accn = o.optString("accn"),
                    )
                    out[key] = Reported(value)
                }.onFailure {
                    AppLogger.w(TAG, "skipping bad XBRL row in $concept: ${it.message}")
                }
            }
            // Continue across all concepts and union the results — see
            // class docstring above.
        }
        return out
    }

    /** JsonNull-safe accessors. `JsonObject.get` returns null for missing
     *  keys but a JsonNull element for present-but-null keys; the typed
     *  accessors (.asString, .asInt, .asDouble) throw on JsonNull. These
     *  helpers collapse both "missing" and "null" into Kotlin null. */
    private fun JsonObject.optString(key: String): String? =
        this.get(key)?.takeIf { !it.isJsonNull }?.asString
    private fun JsonObject.optInt(key: String): Int? =
        this.get(key)?.takeIf { !it.isJsonNull }?.asInt
    private fun JsonObject.optDouble(key: String): Double? =
        this.get(key)?.takeIf { !it.isJsonNull }?.asDouble

    private companion object {
        const val TAG = "XbrlExtractor"
        const val CACHE_TTL_MILLIS = 6L * 60L * 60L * 1000L // 6h
    }
}
