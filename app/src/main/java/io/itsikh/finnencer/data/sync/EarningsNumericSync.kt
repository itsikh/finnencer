package io.itsikh.finnencer.data.sync

import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.entity.EarningsStatus
import io.itsikh.finnencer.logging.AppLogger
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills the numeric fields on [io.itsikh.finnencer.data.entity.EarningsEvent]
 * rows (consensus + actual EPS / revenue) from Finnhub's
 * `/calendar/earnings` endpoint. Runs alongside [EarningsCalendarSync],
 * which only seeds the filing dates from EDGAR.
 *
 * Why a second sync: EDGAR knows when 8-Ks were filed but doesn't expose
 * parsed EPS / revenue. Finnhub keeps a structured estimates + actuals
 * series per ticker, free tier. Without this, every generated report
 * gets the "Earnings data unavailable for this print" placeholder
 * because the source bundle ships all-null numbers to the LLM (see #20).
 */
@Singleton
class EarningsNumericSync @Inject constructor(
    private val finnhub: FinnhubService,
    private val tickerDao: TickerDao,
    private val earningsDao: EarningsDao,
) {

    /**
     * Pull Finnhub earnings for every watched ticker over [pastYears] years
     * back through [futureMonths] months forward, then update any matching
     * [io.itsikh.finnencer.data.entity.EarningsEvent] rows by `(symbol,
     * fiscal_year, fiscal_quarter)`. Rows that exist only on Finnhub (no
     * matching EDGAR filing yet) are skipped — EarningsCalendarSync owns
     * row creation; this sync only fills numbers.
     *
     * @return count of EarningsEvent rows updated.
     */
    suspend fun runOnce(pastYears: Int = 2, futureMonths: Int = 3): Int {
        val tickers = tickerDao.getAll()
        if (tickers.isEmpty()) return 0

        val today = LocalDate.now()
        val from = today.minusYears(pastYears.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val to = today.plusMonths(futureMonths.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)

        var updated = 0
        for (ticker in tickers) {
            val resp = runCatching { finnhub.earningsCalendar(from, to, ticker.symbol) }
                .onFailure { AppLogger.w(TAG, "earningsCalendar ${ticker.symbol} failed: ${it.message}") }
                .getOrNull() ?: continue

            for (row in resp.earningsCalendar) {
                val year = row.year ?: continue
                val quarter = row.quarter ?: continue
                val existing = earningsDao.findFiscal(ticker.symbol, year, quarter) ?: continue

                // Only write when we actually have data to add. If
                // everything Finnhub returns matches what's already
                // stored, skip the write so we don't spam Room.
                val nothingNew = existing.consensusEps == row.epsEstimate &&
                    existing.consensusRevenue == row.revenueEstimate &&
                    existing.actualEps == row.epsActual &&
                    existing.actualRevenue == row.revenueActual
                if (nothingNew) continue

                val newStatus = if (row.epsActual != null || row.revenueActual != null) {
                    EarningsStatus.REPORTED.name
                } else {
                    existing.status
                }

                earningsDao.updateEvent(
                    existing.copy(
                        consensusEps = row.epsEstimate ?: existing.consensusEps,
                        consensusRevenue = row.revenueEstimate ?: existing.consensusRevenue,
                        actualEps = row.epsActual ?: existing.actualEps,
                        actualRevenue = row.revenueActual ?: existing.actualRevenue,
                        status = newStatus,
                    )
                )
                updated++
            }
        }
        if (updated > 0) AppLogger.i(TAG, "filled numbers on $updated earnings event(s)")
        return updated
    }

    private companion object { const val TAG = "FinnhubEarningsSync" }
}
