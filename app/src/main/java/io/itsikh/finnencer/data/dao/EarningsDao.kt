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

    @Query("SELECT * FROM earnings_events WHERE id = :id")
    suspend fun getEvent(id: Long): EarningsEvent?

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
}
