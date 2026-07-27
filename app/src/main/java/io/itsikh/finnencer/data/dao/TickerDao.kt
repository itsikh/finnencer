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

    // IGNORE, never REPLACE: SQLite REPLACE is DELETE+INSERT, which fires
    // ON DELETE CASCADE on article_ticker_xref, earnings_events,
    // earnings_reports and notifications — re-inserting an existing symbol
    // would silently wipe all of the ticker's children. Returns -1 when the
    // symbol already exists; callers that mean "update" must use [update].
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(ticker: Ticker): Long

    @Update
    suspend fun update(ticker: Ticker)

    @Query("UPDATE tickers SET cik = :cik WHERE symbol = :symbol")
    suspend fun updateCik(symbol: String, cik: String)

    @Query("DELETE FROM tickers WHERE symbol = :symbol")
    suspend fun delete(symbol: String)

    @Query("SELECT COALESCE(MAX(watchlist_order), -1) FROM tickers")
    suspend fun maxOrder(): Int

    @Query("UPDATE tickers SET watchlist_order = :order WHERE symbol = :symbol")
    suspend fun setOrder(symbol: String, order: Int)
}
