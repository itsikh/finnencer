package io.itsikh.finnencer.data.sync

import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.entity.EarningsStatus
import io.itsikh.finnencer.logging.AppLogger
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills the numeric fields on [io.itsikh.finnencer.data.entity.EarningsEvent]
 * rows (consensus + actual EPS / revenue) from Finnhub's
 * `/calendar/earnings` endpoint. Runs alongside [EarningsCalendarSync],
 * which only seeds the filing dates from EDGAR.
 *
 * **Matching strategy — by date, not by fiscal tuple.** Earlier versions
 * matched `(symbol, fiscal_year, fiscal_quarter)`, which silently dropped
 * numbers for every company with an offset fiscal calendar: NVDA filed
 * Q4 FY2025 in Feb 2025, so EDGAR's calendar-quarter heuristic stored
 * it as `(2025, 1)` while Finnhub returned `(2025, 4)` — the lookup
 * never matched, the numbers never landed, and the resulting brief read
 * "Earnings data unavailable" (#24). We now match by date proximity
 * (±14 days) and ALSO adopt Finnhub's fiscal labels because they reflect
 * the company's actual fiscal calendar.
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
     * [EarningsEvent] (by date proximity within [matchWindowDays]). Rows
     * with no nearby EDGAR seed are inserted fresh so the user still sees
     * the print on the past-earnings card.
     *
     * @return count of EarningsEvent rows updated or inserted.
     */
    suspend fun runOnce(
        pastYears: Int = 2,
        futureMonths: Int = 3,
        matchWindowDays: Int = 14,
    ): Int {
        val tickers = tickerDao.getAll()
        if (tickers.isEmpty()) return 0

        val today = LocalDate.now()
        val from = today.minusYears(pastYears.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val to = today.plusMonths(futureMonths.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val windowMillis = TimeUnit.DAYS.toMillis(matchWindowDays.toLong())

        var touched = 0
        for (ticker in tickers) {
            val resp = runCatching { finnhub.earningsCalendar(from, to, ticker.symbol) }
                .onFailure { AppLogger.w(TAG, "earningsCalendar ${ticker.symbol} failed: ${it.message}") }
                .getOrNull() ?: continue

            // Pass 1: write all numbers immediately and collect the planned
            // (year, quarter) relabels. Splitting label changes out of the
            // numbers write lets us resolve swap chains in pass 2 — when
            // two MSFT-style filings each want a slot the other currently
            // occupies, a single-shot per-row update silently drops one of
            // them (#45).
            val plannedRelabels = mutableListOf<PlannedRelabel>()
            for (row in resp.earningsCalendar) {
                val dateStr = row.date ?: continue
                val dateMs = runCatching {
                    LocalDate.parse(dateStr).atStartOfDay(ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                }.getOrNull() ?: continue

                val existing = earningsDao.findNearestByDate(ticker.symbol, dateMs, windowMillis)
                if (existing != null) {
                    val (wroteNumbers, relabel) = applyNumbersAndPlanRelabel(existing, row, dateMs)
                    if (wroteNumbers) touched++
                    if (relabel != null) plannedRelabels += relabel
                } else {
                    if (insertFromFinnhub(ticker.symbol, row, dateMs)) touched++
                }
            }

            // Pass 2: iterative relabel. Each iteration applies every
            // relabel whose target slot is free (or held by self). Cycles
            // would deadlock, which is fine — we just log and stop; in
            // practice Finnhub's per-company quarters strictly increase
            // with date so the order resolves in O(n) passes.
            if (plannedRelabels.isNotEmpty()) {
                val pending = plannedRelabels.toMutableList()
                while (pending.isNotEmpty()) {
                    val applied = mutableListOf<PlannedRelabel>()
                    for (p in pending) {
                        val occupier = earningsDao.findFiscal(p.row.tickerSymbol, p.targetYear, p.targetQuarter)
                        if (occupier == null || occupier.id == p.row.id) {
                            earningsDao.updateEvent(
                                p.row.copy(
                                    fiscalYear = p.targetYear,
                                    fiscalQuarter = p.targetQuarter,
                                )
                            )
                            touched++
                            applied += p
                        }
                    }
                    if (applied.isEmpty()) {
                        AppLogger.w(
                            TAG,
                            "relabel deadlock on ${ticker.symbol}: ${pending.size} row(s) stuck",
                        )
                        break
                    }
                    pending.removeAll(applied)
                }
            }
        }
        if (touched > 0) AppLogger.i(TAG, "filled/inserted $touched earnings event(s) from Finnhub")
        return touched
    }

    private data class PlannedRelabel(val row: EarningsEvent, val targetYear: Int, val targetQuarter: Int)

    /**
     * Write whatever Finnhub provides — numbers + status — WITHOUT
     * touching the fiscal labels. Returns (numbersWritten, plannedRelabel).
     * Caller resolves all relabels together in a second pass so swap
     * chains don't drop rows (#45).
     */
    private suspend fun applyNumbersAndPlanRelabel(
        existing: EarningsEvent,
        row: io.itsikh.finnencer.data.api.FinnhubEarningsEvent,
        dateMs: Long,
    ): Pair<Boolean, PlannedRelabel?> {
        val hasActuals = row.epsActual != null || row.revenueActual != null
        val updated = existing.copy(
            consensusEps = row.epsEstimate ?: existing.consensusEps,
            consensusRevenue = row.revenueEstimate ?: existing.consensusRevenue,
            actualEps = row.epsActual ?: existing.actualEps,
            actualRevenue = row.revenueActual ?: existing.actualRevenue,
            actualReportedAtMillis = if (hasActuals) (existing.actualReportedAtMillis ?: dateMs) else existing.actualReportedAtMillis,
            status = if (hasActuals) EarningsStatus.REPORTED.name else existing.status,
        )
        val wroteNumbers = if (updated != existing) {
            earningsDao.updateEvent(updated)
            true
        } else false

        val newYear = row.year
        val newQuarter = row.quarter
        val relabel = if (
            newYear != null && newQuarter != null &&
            (newYear != updated.fiscalYear || newQuarter != updated.fiscalQuarter)
        ) {
            PlannedRelabel(row = updated, targetYear = newYear, targetQuarter = newQuarter)
        } else null

        return wroteNumbers to relabel
    }

    /**
     * No EDGAR row near this Finnhub date — insert one so the print
     * appears on the user's past-earnings card even when EDGAR's
     * submissions JSON didn't return a matching 8-K (small caps, missed
     * filings, etc.).
     */
    private suspend fun insertFromFinnhub(
        symbol: String,
        row: io.itsikh.finnencer.data.api.FinnhubEarningsEvent,
        dateMs: Long,
    ): Boolean {
        val year = row.year ?: return false
        val quarter = row.quarter ?: return false
        // Guard against duplicate inserts when both a fiscal-labelled row
        // and a date-proximity row would land on the same key.
        if (earningsDao.findFiscal(symbol, year, quarter) != null) return false
        val hasActuals = row.epsActual != null || row.revenueActual != null
        earningsDao.insertEvents(
            listOf(
                EarningsEvent(
                    tickerSymbol = symbol,
                    fiscalYear = year,
                    fiscalQuarter = quarter,
                    scheduledAtMillis = dateMs,
                    actualReportedAtMillis = if (hasActuals) dateMs else null,
                    consensusEps = row.epsEstimate,
                    consensusRevenue = row.revenueEstimate,
                    actualEps = row.epsActual,
                    actualRevenue = row.revenueActual,
                    status = if (hasActuals) EarningsStatus.REPORTED.name else EarningsStatus.SCHEDULED.name,
                )
            )
        )
        return true
    }

    private companion object { const val TAG = "FinnhubEarningsSync" }
}
