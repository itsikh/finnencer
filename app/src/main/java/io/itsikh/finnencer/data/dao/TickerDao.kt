package io.itsikh.finnencer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.itsikh.finnencer.data.entity.Ticker
import kotlinx.coroutines.flow.Flow

@Dao
interface TickerDao {

    @Query("SELECT * FROM tickers ORDER BY watchlist_order ASC")
    fun observeAll(): Flow<List<Ticker>>

    @Query("SELECT * FROM tickers ORDER BY watchlist_order ASC")
    suspend fun getAll(): List<Ticker>

    @Query("SELECT symbol FROM tickers ORDER BY watchlist_order ASC")
    suspend fun getAllSymbols(): List<String>

    @Query("SELECT * FROM tickers WHERE symbol = :symbol")
    suspend fun get(symbol: String): Ticker?

    @Query("SELECT * FROM tickers WHERE symbol = :symbol")
    fun observe(symbol: String): Flow<Ticker?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ticker: Ticker)

    @Update
    suspend fun update(ticker: Ticker)

    @Query("DELETE FROM tickers WHERE symbol = :symbol")
    suspend fun delete(symbol: String)

    @Query("SELECT COALESCE(MAX(watchlist_order), -1) FROM tickers")
    suspend fun maxOrder(): Int

    @Query("UPDATE tickers SET watchlist_order = :order WHERE symbol = :symbol")
    suspend fun setOrder(symbol: String, order: Int)
}
