package io.itsikh.finnencer.data.sync

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.itsikh.finnencer.data.api.SecEdgarService
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.entity.EarningsStatus
import io.itsikh.finnencer.data.providers.EdgarCikLookup
import io.itsikh.finnencer.logging.AppLogger
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Earnings discovery driven entirely from SEC EDGAR — no Finnhub, no paid
 * sites. Each public company files an 8-K with item 2.02 ("Results of
 * Operations and Financial Condition") when they release earnings. The
 * filing date is the canonical earnings date; the linked primary document
 * is the press release itself.
 *
 * We walk the EDGAR submissions JSON for each watched CIK, filter 8-Ks
 * whose `items` field contains `2.02`, and write one [EarningsEvent] per
 * filing. Quarter / year is inferred from the filing date (calendar
 * quarter of the filing).
 *
 * Numbers (EPS / revenue / consensus) are NOT scraped here — the per-
 * ticker view shows the filing as the source of truth and the report
 * generator pulls the press-release HTML on demand.
 */
@Singleton
class EarningsCalendarSync @Inject constructor(
    private val edgar: SecEdgarService,
    private val cikLookup: EdgarCikLookup,
    private val tickerDao: TickerDao,
    private val earningsDao: EarningsDao,
    private val gson: Gson,
) {

    suspend fun runOnce(): Int {
        val tickers = tickerDao.getAll()
        if (tickers.isEmpty()) return 0

        var inserted = 0
        for (ticker in tickers) {
            val cik = ticker.cik ?: cikLookup.resolve(ticker.symbol) ?: continue
            if (ticker.cik == null) tickerDao.update(ticker.copy(cik = cik))

            val raw = runCatching { edgar.submissions(cik) }
                .onFailure { AppLogger.w(TAG, "EDGAR submissions fetch failed for ${ticker.symbol}: ${it.message}") }
                .getOrNull() ?: continue

            val parsed = runCatching { gson.fromJson(raw, JsonObject::class.java) }.getOrNull() ?: continue
            val recent = parsed["filings"]?.asJsonObject?.get("recent")?.asJsonObject ?: continue
            val forms = recent["form"]?.asJsonArray ?: continue
            val items = recent["items"]?.asJsonArray ?: continue
            val dates = recent["filingDate"]?.asJsonArray ?: continue
            val accNums = recent["accessionNumber"]?.asJsonArray ?: continue
            val primaryDocs = recent["primaryDocument"]?.asJsonArray ?: continue

            val n = forms.size()
            for (i in 0 until n) {
                val form = forms[i].asString
                if (form != "8-K") continue
                val itemsStr = items[i].asString
                if (!itemsStr.contains("2.02")) continue

                val dateStr = dates[i].asString // "YYYY-MM-DD"
                val filedAt = runCatching {
                    LocalDate.parse(dateStr).atStartOfDay(ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                }.getOrNull() ?: continue

                val accNoNoDash = accNums[i].asString.replace("-", "")
                val primary = primaryDocs[i].asString
                @Suppress("UNUSED_VARIABLE") // kept for future report-source attribution
                val pressUrl = "https://www.sec.gov/Archives/edgar/data/${cik.toLong()}/$accNoNoDash/$primary"

                val (fy, fq) = fiscalGuess(filedAt)

                val existing = earningsDao.findFiscal(ticker.symbol, fy, fq)
                if (existing == null) {
                    earningsDao.insertEvents(listOf(
                        EarningsEvent(
                            tickerSymbol = ticker.symbol,
                            fiscalYear = fy,
                            fiscalQuarter = fq,
                            scheduledAtMillis = filedAt,
                            actualReportedAtMillis = filedAt,
                            status = EarningsStatus.REPORTED.name,
                        )
                    ))
                    inserted++
                } else if (existing.actualReportedAtMillis == null) {
                    earningsDao.updateEvent(
                        existing.copy(
                            actualReportedAtMillis = filedAt,
                            status = EarningsStatus.REPORTED.name,
                        )
                    )
                }
            }
        }
        return inserted
    }

    /**
     * Map a filing date to (fiscalYear, fiscalQuarter) using the company's
     * **calendar** quarter at filing time. This is a heuristic — most US
     * filers report on calendar Q1/Q2/Q3/Q4 boundaries — and good enough
     * for sorting + display without a per-company fiscal-year shift table.
     */
    private fun fiscalGuess(filedAtMillis: Long): Pair<Int, Int> {
        val date = java.time.Instant.ofEpochMilli(filedAtMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val q = (date.monthValue - 1) / 3 + 1
        return date.year to q
    }

    private companion object { const val TAG = "EdgarEarningsSync" }
}
