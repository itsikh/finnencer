package io.itsikh.finnencer.data.repo

import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.io.IOException
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
    private val apiKeys: ApiKeysRepository,
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
        if (!apiKeys.isConfigured(ApiKey.FINNHUB)) {
            AppLogger.w(TAG, "search blocked: FINNHUB key not configured")
            throw IllegalStateException(
                "Finnhub API key not set. Open Settings → API Keys and paste your Finnhub key (free at finnhub.io)."
            )
        }
        val resp = try {
            finnhub.search(query.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val code = e.code()
            val hint = when (code) {
                401, 403 -> "Finnhub rejected the key — verify it in Settings → API Keys."
                429 -> "Finnhub rate-limited the request. Try again in a minute."
                in 500..599 -> "Finnhub server error ($code). Try again shortly."
                else -> "Finnhub returned HTTP $code."
            }
            AppLogger.e(TAG, "Finnhub /search HTTP $code for '$query'", e)
            throw IllegalStateException(hint, e)
        } catch (e: IOException) {
            AppLogger.e(TAG, "Network error reaching Finnhub /search for '$query'", e)
            throw IllegalStateException("Network error reaching Finnhub.", e)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Unexpected ${e.javaClass.simpleName} from Finnhub /search for '$query'", e)
            throw IllegalStateException(
                "Unexpected error: ${e.javaClass.simpleName}: ${e.message ?: "(no detail)"}",
                e,
            )
        }
        val q = query.trim().uppercase()
        val rows = resp.result
            .asSequence()
            .filter { it.symbol != null && it.description != null }
            // Skip preferred shares, warrants, and other suffixed symbols
            // (".PR", ".W", ":OTC" etc.); keep plain symbols only.
            .filter { sym -> sym.symbol!!.none { c -> c == '.' || c == ':' } }
            .map {
                TickerSearchResult(
                    symbol = it.symbol!!.uppercase(),
                    description = it.description ?: it.symbol,
                    displaySymbol = it.displaySymbol ?: it.symbol,
                    type = it.type ?: "Common Stock",
                )
            }
            .distinctBy { it.symbol }
            .toList()

        // Rank by relevance: exact ticker match first, then starts-with on
        // ticker, then starts-with on company name, then contains. Within
        // each bucket Finnhub's own order is preserved.
        return rows.sortedBy { r ->
            when {
                r.symbol == q -> 0
                r.symbol.startsWith(q) -> 1
                r.description.uppercase().startsWith(q) -> 2
                r.description.uppercase().contains(q) -> 3
                else -> 4
            }
        }.take(30)
    }

    private companion object {
        const val TAG = "WatchlistRepo"
    }
}

data class TickerSearchResult(
    val symbol: String,
    val description: String,
    val displaySymbol: String,
    val type: String,
)
