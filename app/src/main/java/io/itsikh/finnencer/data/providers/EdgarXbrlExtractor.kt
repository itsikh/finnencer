package io.itsikh.finnencer.data.providers

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.itsikh.finnencer.data.api.SecEdgarService
import io.itsikh.finnencer.logging.AppLogger
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-quarter parsed financial facts pulled from EDGAR's XBRL company-
 * facts JSON. One [XbrlQuarter] represents a single fiscal quarter's
 * income-statement snapshot; values are reported by the company in their
 * own 10-Q / 10-K / 8-K (NUM-tagged), the SEC parses and exposes them.
 *
 * Some metrics may be null if the company didn't tag that line item that
 * quarter — Revenues vs RevenueFromContractWithCustomerExcludingAssessedTax
 * is the common one (older filings used the first, ASC-606-era used the
 * second). [revenue] picks whichever was reported.
 */
data class XbrlQuarter(
    val periodEnd: LocalDate,
    val periodStart: LocalDate,
    val fiscalYear: Int,
    val fiscalPeriod: String, // "Q1" / "Q2" / "Q3" / "Q4" / "FY"
    val form: String,         // "10-Q" / "10-K"
    val accn: String?,        // accession number for traceability
    val revenue: Double?,
    val epsBasic: Double?,
    val epsDiluted: Double?,
    val grossProfit: Double?,
    val netIncome: Double?,
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
}

/**
 * Hits EDGAR's XBRL companyfacts endpoint and extracts the most recent
 * standalone quarters with their key income-statement numbers. This is
 * the authoritative source for actual results — the SEC parses every
 * public US company's mandatory XBRL filing and exposes the data via
 * this free, no-auth, no-rate-limit-beyond-User-Agent endpoint.
 *
 * Used by [io.itsikh.finnencer.data.ai.ReportGenerator] to ground its
 * source bundle in real numbers instead of relying on third-party
 * aggregators whose data tier / coverage gaps caused the recurring
 * "Earnings data unavailable" bug (#23 / #24 / #25).
 */
@Singleton
class EdgarXbrlExtractor @Inject constructor(
    private val edgar: SecEdgarService,
) {

    // In-memory cache of (cik -> raw response JSON). The endpoint returns
    // ~1-2 MB per ticker; SEC asks for ≤ 10 req/sec global, so we cache
    // per-CIK for [CACHE_TTL_MILLIS] to avoid re-fetching during a single
    // session of generating multiple reports for the same ticker.
    private data class CacheEntry(val rootObj: JsonObject, val fetchedAt: Long)
    private val cache = mutableMapOf<String, CacheEntry>()

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
        val revenueByPeriod = pickConcept(
            gaap,
            // Order doesn't matter now (we union) — but list both because
            // some companies use only one of the two concepts.
            "Revenues",
            "RevenueFromContractWithCustomerExcludingAssessedTax",
            unit = "USD",
        )
        val grossByPeriod = pickConcept(gaap, "GrossProfit", unit = "USD")
        val netByPeriod = pickConcept(gaap, "NetIncomeLoss", unit = "USD")
        val epsBasicByPeriod = pickConcept(
            gaap,
            "EarningsPerShareBasic",
            unit = "USD/shares",
        )
        val epsDilutedByPeriod = pickConcept(
            gaap,
            "EarningsPerShareDiluted",
            unit = "USD/shares",
        )

        // Use revenue's period set as the spine — every reasonable income
        // statement has a top line, and we want to land on rows the
        // company actually filed. Sort by periodEnd descending and
        // dedupe by (start, end) — XBRL re-files the same period
        // multiple times under different `fy` values (comparative
        // columns in later 10-Ks), and we only want one entry per
        // physical period. Within a tie, prefer the entry whose `fy`
        // matches the period (the original primary filing).
        val periodKeys = revenueByPeriod.keys
            .sortedWith(
                compareByDescending<PeriodKey> { it.periodEnd }
                    .thenBy { kotlin.math.abs(it.fiscalYear - it.periodEnd.year) }
            )
            .distinctBy { it.periodStart to it.periodEnd }

        return periodKeys
            .asSequence()
            .map { key ->
                XbrlQuarter(
                    periodEnd = key.periodEnd,
                    periodStart = key.periodStart,
                    fiscalYear = key.fiscalYear,
                    fiscalPeriod = key.fiscalPeriod,
                    form = key.form,
                    accn = key.accn,
                    revenue = revenueByPeriod[key]?.value,
                    epsBasic = epsBasicByPeriod[key]?.value,
                    epsDiluted = epsDilutedByPeriod[key]?.value,
                    grossProfit = grossByPeriod[key]?.value,
                    netIncome = netByPeriod[key]?.value,
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
    ): XbrlQuarter? {
        val recent = recentQuarters(cik, limit = 16)
        return recent
            .map { it to ChronoUnit.DAYS.between(it.periodEnd, aroundDate) }
            .filter { kotlin.math.abs(it.second) <= windowDays }
            .minByOrNull { kotlin.math.abs(it.second) }
            ?.first
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
