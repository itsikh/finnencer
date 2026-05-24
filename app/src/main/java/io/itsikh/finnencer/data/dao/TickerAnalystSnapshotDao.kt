package io.itsikh.finnencer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.itsikh.finnencer.data.entity.TickerAnalystSnapshot
import kotlinx.coroutines.flow.Flow

@Dao
interface TickerAnalystSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snap: TickerAnalystSnapshot)

    @Query("SELECT * FROM ticker_analyst_snapshot WHERE ticker = :ticker")
    suspend fun get(ticker: String): TickerAnalystSnapshot?

    @Query("SELECT * FROM ticker_analyst_snapshot WHERE ticker = :ticker")
    fun observe(ticker: String): Flow<TickerAnalystSnapshot?>

    @Query("SELECT * FROM ticker_analyst_snapshot WHERE ticker IN (:tickers)")
    suspend fun getMany(tickers: List<String>): List<TickerAnalystSnapshot>

    @Query("SELECT * FROM ticker_analyst_snapshot WHERE ticker IN (:tickers)")
    fun observeMany(tickers: List<String>): Flow<List<TickerAnalystSnapshot>>
}
