package io.itsikh.finnencer.data.repo

import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.entity.Ticker
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Front door for watchlist operations: add / remove / reorder tickers, plus
 * Finnhub-backed symbol search.
 */
@Singleton
class WatchlistRepository @Inject constructor(
    private val tickerDao: TickerDao,
    private val finnhub: FinnhubService,
) {

    fun observeAll(): Flow<List<Ticker>> = tickerDao.observeAll()

    fun observe(symbol: String): Flow<Ticker?> = tickerDao.observe(symbol)

    suspend fun add(symbol: String, name: String, exchange: String, sector: String? = null) {
        val nextOrder = tickerDao.maxOrder() + 1
        tickerDao.upsert(
            Ticker(
                symbol = symbol.uppercase(),
                name = name,
                exchange = exchange,
                sector = sector,
                watchlistOrder = nextOrder,
                addedAtMillis = System.currentTimeMillis(),
            )
        )
    }

    suspend fun remove(symbol: String) = tickerDao.delete(symbol.uppercase())

    suspend fun update(ticker: Ticker) = tickerDao.update(ticker)

    suspend fun search(query: String): List<TickerSearchResult> {
        if (query.isBlank()) return emptyList()
        val resp = finnhub.search(query.trim())
        return resp.result
            .asSequence()
            .filter { it.symbol != null && it.description != null }
            // Only common stock on US exchanges; skip warrants, units, ADRs of micro caps
            .filter { it.type == null || it.type.equals("Common Stock", ignoreCase = true) }
            // Skip symbols with dots (.PR for preferred etc.) for the MVP add flow
            .filter { it.symbol!!.none { c -> c == '.' || c == '-' } || it.symbol.length <= 5 }
            .map {
                TickerSearchResult(
                    symbol = it.symbol!!.uppercase(),
                    description = it.description ?: it.symbol,
                    displaySymbol = it.displaySymbol ?: it.symbol,
                    type = it.type ?: "Common Stock",
                )
            }
            .distinctBy { it.symbol }
            .take(25)
            .toList()
    }
}

data class TickerSearchResult(
    val symbol: String,
    val description: String,
    val displaySymbol: String,
    val type: String,
)
