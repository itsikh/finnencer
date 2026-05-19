package io.itsikh.finnencer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.itsikh.finnencer.data.entity.TickerMetrics
import io.itsikh.finnencer.data.entity.TickerMetricsAnalysis
import kotlinx.coroutines.flow.Flow

@Dao
interface TickerMetricsDao {

    @Query("SELECT * FROM ticker_metrics WHERE ticker = :ticker")
    suspend fun get(ticker: String): TickerMetrics?

    @Query("SELECT * FROM ticker_metrics WHERE ticker = :ticker")
    fun observe(ticker: String): Flow<TickerMetrics?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TickerMetrics)

    @Query("SELECT * FROM ticker_metrics_analysis WHERE ticker = :ticker AND as_of_date = :date")
    suspend fun getAnalysis(ticker: String, date: String): TickerMetricsAnalysis?

    @Query("SELECT * FROM ticker_metrics_analysis WHERE ticker = :ticker AND as_of_date = :date")
    fun observeAnalysis(ticker: String, date: String): Flow<TickerMetricsAnalysis?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnalysis(row: TickerMetricsAnalysis)
}
