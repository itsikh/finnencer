package io.itsikh.finnencer.data.repo

import io.itsikh.finnencer.data.api.YahooChartMeta
import io.itsikh.finnencer.data.api.YahooQuoteService
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live last-trade snapshot for one ticker, with the deltas we need to
 * render the watchlist row. Change/percent are derived from the v8
 * chart endpoint's `regularMarketPrice` vs `chartPreviousClose`.
 *
 * The v8 chart endpoint doesn't expose explicit pre/post-market prices,
 * but it *does* update `regularMarketPrice` during extended hours when
 * a trade prints. That means PRE/POST tagging is no longer reliable —
 * we deliberately don't try to label the session here.
 */
data class TickerQuote(
    val symbol: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val asOfMillis: Long = System.currentTimeMillis(),
)

/**
 * Polls Yahoo Finance's public chart endpoint for the user's watched
 * tickers once a minute while the watchlist screen is foregrounded.
 *
 * Lifecycle: the screen calls [start] on resume and [stop] on pause —
 * the loop is idempotent in both directions. Polling stops entirely
 * when the screen isn't in front, so we don't burn cellular data on a
 * shade-only app or while the user is in another screen.
 *
 * The chart endpoint is single-symbol per request, so each tick fans
 * out one request per ticker in parallel using async.
 *
 * Quotes are cached only in memory ([latest] is the live snapshot).
 * Disk persistence would make stale numbers look live, which is the
 * opposite of what this surface is supposed to convey.
 */
@Singleton
class QuotePoller @Inject constructor(
    private val service: YahooQuoteService,
) {

    private val _latest = MutableStateFlow<Map<String, TickerQuote>>(emptyMap())
    val latest: StateFlow<Map<String, TickerQuote>> = _latest.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var currentTickers: List<String> = emptyList()

    /**
     * Begin polling for [tickers]. Calling [start] again with a
     * different list re-targets the loop without dropping the existing
     * cached quotes. Calling [start] with the same list is a no-op so
     * back-and-forth nav doesn't restart the timer.
     */
    fun start(tickers: List<String>) {
        val unique = tickers.map { it.uppercase() }.distinct()
        if (unique.isEmpty()) {
            stop()
            return
        }
        if (unique == currentTickers && pollJob?.isActive == true) return
        currentTickers = unique
        pollJob?.cancel()
        pollJob = scope.launch {
            // Fire one fetch immediately so the user sees a number,
            // then settle into the 60s cadence.
            while (true) {
                fetchOnce(unique)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Stop polling. Cached quotes remain on [latest] so the UI can
     *  keep showing the last-seen values until the next start. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * One tick: kick off a parallel chart fetch per symbol, merge the
     * results into [_latest]. Each symbol's request is independent;
     * one failure doesn't poison the others.
     */
    private suspend fun fetchOnce(tickers: List<String>) {
        val results: List<TickerQuote?> = coroutineScope {
            tickers.map { symbol ->
                async { fetchOneSymbol(symbol) }
            }.awaitAll()
        }
        val fresh = results.filterNotNull()
        if (fresh.isEmpty()) return
        val merged = _latest.value.toMutableMap()
        for (q in fresh) merged[q.symbol] = q
        _latest.value = merged
    }

    /**
     * Fetch one symbol's chart meta. Tries `query1` first; on any
     * failure (network, 401, parse) retries against `query2`. Returns
     * null if both attempts fail or the response doesn't carry a
     * usable price.
     */
    private suspend fun fetchOneSymbol(symbol: String): TickerQuote? {
        val meta = runCatching { service.chart(symbol).chart.result?.firstOrNull()?.meta }
            .getOrElse { primaryErr ->
                AppLogger.w(TAG, "query1 chart failed for $symbol (${primaryErr.message}); trying query2")
                runCatching {
                    service.chartAt(
                        "https://query2.finance.yahoo.com/v8/finance/chart/" +
                            "$symbol?interval=1d&range=1d&includePrePost=true",
                    ).chart.result?.firstOrNull()?.meta
                }.getOrElse { fallbackErr ->
                    AppLogger.w(TAG, "query2 chart also failed for $symbol: ${fallbackErr.message}")
                    null
                }
            }
        return meta?.let { toQuote(it) }
    }

    /**
     * Build a [TickerQuote] from Yahoo's chart meta. Percent change is
     * derived from `regularMarketPrice` against `chartPreviousClose`
     * (falling back to `previousClose` if the former is missing).
     * Returns null if Yahoo didn't include a usable current price.
     */
    private fun toQuote(meta: YahooChartMeta): TickerQuote? {
        val price = meta.regularMarketPrice ?: return null
        val prev = meta.chartPreviousClose ?: meta.previousClose
        val change = if (prev != null) price - prev else 0.0
        val pct = if (prev != null && prev != 0.0) (price - prev) / prev * 100.0 else 0.0
        return TickerQuote(
            symbol = meta.symbol.uppercase(),
            price = price,
            change = change,
            changePercent = pct,
        )
    }

    private companion object {
        const val TAG = "QuotePoller"
        const val POLL_INTERVAL_MS = 60_000L
    }
}
