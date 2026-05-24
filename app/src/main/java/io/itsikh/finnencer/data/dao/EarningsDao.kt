package io.itsikh.finnencer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.entity.EarningsReport
import kotlinx.coroutines.flow.Flow

@Dao
interface EarningsDao {

    // ───────── events ─────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvents(events: List<EarningsEvent>)

    @Update
    suspend fun updateEvent(event: EarningsEvent)

    @Query(
        """
        SELECT * FROM earnings_events
        WHERE scheduled_at_millis BETWEEN :fromMillis AND :toMillis
        ORDER BY scheduled_at_millis ASC
        """
    )
    fun observeBetween(fromMillis: Long, toMillis: Long): Flow<List<EarningsEvent>>

    @Query(
        """
        SELECT * FROM earnings_events
        WHERE ticker_symbol = :symbol
        ORDER BY scheduled_at_millis DESC
        """
    )
    fun observeForTicker(symbol: String): Flow<List<EarningsEvent>>

    @Query(
        """
        SELECT * FROM earnings_events
        WHERE ticker_symbol = :symbol AND fiscal_year = :year AND fiscal_quarter = :quarter
        LIMIT 1
        """
    )
    suspend fun findFiscal(symbol: String, year: Int, quarter: Int): EarningsEvent?

    /**
     * Find the EarningsEvent for [symbol] whose scheduled date is closest
     * to [aroundMillis] and falls within ±[windowMillis] of it. Lets the
     * numeric sync match an event without depending on (fiscal_year,
     * fiscal_quarter) — EDGAR's filing-date heuristic and Finnhub's
     * company-fiscal labels disagree for companies with offset fiscal
     * calendars (NVDA / AAPL / MSFT / ORCL / etc.).
     */
    @Query(
        """
        SELECT * FROM earnings_events
        WHERE ticker_symbol = :symbol
          AND ABS(scheduled_at_millis - :aroundMillis) <= :windowMillis
        ORDER BY ABS(scheduled_at_millis - :aroundMillis) ASC
        LIMIT 1
        """
    )
    suspend fun findNearestByDate(
        symbol: String,
        aroundMillis: Long,
        windowMillis: Long,
    ): EarningsEvent?

    @Query("SELECT * FROM earnings_events WHERE id = :id")
    suspend fun getEvent(id: Long): EarningsEvent?

    @Query(
        """
        SELECT * FROM earnings_events
        WHERE ticker_symbol = :symbol
        ORDER BY scheduled_at_millis ASC
        """
    )
    suspend fun getAllEventsForTicker(symbol: String): List<EarningsEvent>

    @Query("DELETE FROM earnings_events WHERE id IN (:ids)")
    suspend fun deleteEvents(ids: List<Long>)

    /** Past earnings events for a ticker, most-recent first. */
    @Query(
        """
        SELECT * FROM earnings_events
        WHERE ticker_symbol = :symbol
          AND scheduled_at_millis <= :nowMillis
        ORDER BY scheduled_at_millis DESC
        LIMIT :limit
        """
    )
    fun observePastForTicker(symbol: String, nowMillis: Long, limit: Int = 2): Flow<List<EarningsEvent>>

    /**
     * Next upcoming earnings event per symbol across the supplied
     * watchlist. Uses a correlated subquery so each ticker contributes
     * at most one row — its closest future event from [nowMillis].
     * Drives the "Earnings in Nd" pill on the watchlist.
     */
    @Query(
        """
        SELECT e.* FROM earnings_events e
        WHERE e.ticker_symbol IN (:symbols)
          AND e.scheduled_at_millis >= :nowMillis
          AND e.scheduled_at_millis = (
              SELECT MIN(e2.scheduled_at_millis)
              FROM earnings_events e2
              WHERE e2.ticker_symbol = e.ticker_symbol
                AND e2.scheduled_at_millis >= :nowMillis
          )
        """
    )
    fun observeNextEventForSymbols(symbols: List<String>, nowMillis: Long): Flow<List<EarningsEvent>>

    // ───────── reports ─────────

    @Insert
    suspend fun insertReport(report: EarningsReport): Long

    @Query("SELECT * FROM earnings_reports WHERE id = :id")
    suspend fun getReport(id: Long): EarningsReport?

    @Query("SELECT * FROM earnings_reports WHERE id = :id")
    fun observeReport(id: Long): Flow<EarningsReport?>

    @Query(
        """
        SELECT * FROM earnings_reports
        WHERE ticker_symbol = :symbol
        ORDER BY generated_at_millis DESC
        """
    )
    fun observeReportsForTicker(symbol: String): Flow<List<EarningsReport>>

    @Query("SELECT * FROM earnings_reports ORDER BY generated_at_millis DESC LIMIT :limit")
    fun observeRecentReports(limit: Int = 50): Flow<List<EarningsReport>>

    @Query("DELETE FROM earnings_reports WHERE id = :id")
    suspend fun deleteReport(id: Long)

    /** Bulk delete used by the multi-select flow in the global Earnings
     *  screen and by the per-ticker report-tag long-press path (#26). */
    @Query("DELETE FROM earnings_reports WHERE id IN (:ids)")
    suspend fun deleteReports(ids: List<Long>)

    /** Wipe every cached report. Wired behind an explicit "Delete all"
     *  confirm dialog in the Earnings screen header. */
    @Query("DELETE FROM earnings_reports")
    suspend fun deleteAllReports()
}
