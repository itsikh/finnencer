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
            "RevenueFromContractWithCustomerExcludingAssessedTax",
            "Revenues",
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
        // company actually filed.
        val periodKeys = revenueByPeriod.keys.sortedByDescending { it.periodEnd }

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
     * For [concepts] (tried in order), grab the parsed USD/USD-per-share
     * series and key it by [PeriodKey]. Later entries with identical
     * periods overwrite — XBRL re-files happen; we trust the latest one.
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
                val start = o.get("start")?.asString ?: continue
                val end = o.get("end")?.asString ?: continue
                val fy = o.get("fy")?.asInt ?: continue
                val fp = o.get("fp")?.asString ?: continue
                val form = o.get("form")?.asString ?: continue
                // 10-Q (quarter and YTD entries) + 10-K (fiscal year and
                // Q4 standalone, when companies bother to tag it). 8-K
                // earnings releases aren't XBRL-tagged for the income
                // statement, so they don't show up here.
                if (form != "10-Q" && form != "10-K") continue
                val value = o.get("val")?.asDouble ?: continue
                val key = PeriodKey(
                    periodStart = LocalDate.parse(start),
                    periodEnd = LocalDate.parse(end),
                    fiscalYear = fy,
                    fiscalPeriod = fp,
                    form = form,
                    accn = o.get("accn")?.asString,
                )
                out[key] = Reported(value)
            }
            // Stop at the first concept that returned anything — the
            // fallback list is "first preference, second preference".
            if (out.isNotEmpty()) return out
        }
        return out
    }

    private companion object {
        const val TAG = "XbrlExtractor"
        const val CACHE_TTL_MILLIS = 6L * 60L * 60L * 1000L // 6h
    }
}
