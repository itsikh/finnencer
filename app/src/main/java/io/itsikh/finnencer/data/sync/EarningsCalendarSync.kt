package io.itsikh.finnencer.data.sync

import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.entity.EarningsStatus
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls the earnings calendar for watched tickers and upserts EarningsEvent
 * rows. Called from [io.itsikh.finnencer.core.work.SyncWorker] alongside the
 * news sync.
 *
 * Window: next 90 days forward + last 14 days back (so a missed reading
 * still surfaces in the screen even if the user opened the app a day late).
 */
@Singleton
class EarningsCalendarSync @Inject constructor(
    private val finnhub: FinnhubService,
    private val tickerDao: TickerDao,
    private val earningsDao: EarningsDao,
) {

    suspend fun runOnce(): Int {
        val tickers = tickerDao.getAll()
        if (tickers.isEmpty()) return 0
        val today = LocalDate.now(ZoneId.systemDefault())
        val fromIso = today.minusDays(14).toString()
        val toIso = today.plusDays(90).toString()

        var inserted = 0
        for (ticker in tickers) {
            val resp = runCatching {
                finnhub.earningsCalendar(fromIso = fromIso, toIso = toIso, symbol = ticker.symbol)
            }.getOrNull() ?: continue
            for (raw in resp.earningsCalendar) {
                val symbol = raw.symbol?.uppercase() ?: continue
                if (symbol != ticker.symbol) continue
                val year = raw.year ?: continue
                val quarter = raw.quarter ?: continue
                val dateMillis = raw.date?.let {
                    runCatching {
                        LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault())
                            .toInstant().toEpochMilli()
                    }.getOrNull()
                } ?: continue

                val existing = earningsDao.findFiscal(symbol, year, quarter)
                if (existing == null) {
                    earningsDao.insertEvents(listOf(
                        EarningsEvent(
                            tickerSymbol = symbol,
                            fiscalYear = year,
                            fiscalQuarter = quarter,
                            scheduledAtMillis = dateMillis,
                            consensusEps = raw.epsEstimate,
                            consensusRevenue = raw.revenueEstimate,
                            actualEps = raw.epsActual,
                            actualRevenue = raw.revenueActual,
                            status = if (raw.epsActual != null) EarningsStatus.REPORTED.name
                                else EarningsStatus.SCHEDULED.name,
                            actualReportedAtMillis = if (raw.epsActual != null) dateMillis else null,
                        )
                    ))
                    inserted++
                } else {
                    val merged = existing.copy(
                        scheduledAtMillis = dateMillis,
                        consensusEps = raw.epsEstimate ?: existing.consensusEps,
                        consensusRevenue = raw.revenueEstimate ?: existing.consensusRevenue,
                        actualEps = raw.epsActual ?: existing.actualEps,
                        actualRevenue = raw.revenueActual ?: existing.actualRevenue,
                        status = if (raw.epsActual != null) EarningsStatus.REPORTED.name else existing.status,
                        actualReportedAtMillis = if (raw.epsActual != null && existing.actualReportedAtMillis == null)
                            dateMillis else existing.actualReportedAtMillis,
                    )
                    if (merged != existing) earningsDao.updateEvent(merged)
                }
            }
        }
        return inserted
    }
}
