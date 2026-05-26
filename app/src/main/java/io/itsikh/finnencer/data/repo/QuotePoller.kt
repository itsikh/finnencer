package io.itsikh.finnencer.data.repo

import io.itsikh.finnencer.data.api.YahooChartResult
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
 * Live last-trade snapshot for one ticker.
 *
 * [price] / [change] / [changePercent] reflect the **regular session**
 * close vs. the previous trading day's close — `regularMarketPrice`
 * freezes at 4pm ET, so this is "today's regular-hours move".
 *
 * [extendedPrice] / [extendedChangePercent] / [extendedSession] are
 * populated when Yahoo's chart series shows a trade outside the
 * regular trading window. They're meant for a secondary "After
 * +0.45%" / "Pre −0.30%" sub-line on the watchlist card; the regular
 * `changePercent` is unaffected.
 */
data class TickerQuote(
    val symbol: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val asOfMillis: Long = System.currentTimeMillis(),
    val extendedPrice: Double? = null,
    val extendedChangePercent: Double? = null,
    val extendedSession: ExtendedSession? = null,
    /** 52-week high, or null if Yahoo didn't include it. */
    val fiftyTwoWeekHigh: Double? = null,
    /** 52-week low, or null if Yahoo didn't include it. */
    val fiftyTwoWeekLow: Double? = null,
    /** Today's volume / 3-month average. Null when either is missing.
     *  Used for the "Vol 2.4×" pill when significantly above average. */
    val volumeRatio: Double? = null,
    /** Intraday 15m closes (regular session only — extended bars are
     *  filtered out so the sparkline reads as "today's regular move").
     *  Empty when Yahoo returned no candles. */
    val intradayCloses: List<Double> = emptyList(),
)

/** Which extended-trading window an [TickerQuote.extendedPrice] came from. */
enum class ExtendedSession { PRE, POST }

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
     * One-shot fetch for a single symbol, independent of the polling
     * loop. Used by per-screen flows (e.g. the "Why is it moving?" card
     * in TickerFeedScreen) that need a fresh quote without subscribing
     * to the watchlist-scoped poller. Updates [latest] on success so a
     * later subscriber sees the most recent value.
     */
    suspend fun snapshot(symbol: String): TickerQuote? {
        val quote = fetchOneSymbol(symbol.uppercase()) ?: return null
        _latest.value = _latest.value + (quote.symbol to quote)
        return quote
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
        val result = runCatching { service.chart(symbol).chart.result?.firstOrNull() }
            .getOrElse { primaryErr ->
                AppLogger.w(TAG, "query1 chart failed for $symbol (${primaryErr.message}); trying query2")
                runCatching {
                    service.chartAt(
                        "https://query2.finance.yahoo.com/v8/finance/chart/" +
                            "$symbol?interval=15m&range=1d&includePrePost=true",
                    ).chart.result?.firstOrNull()
                }.getOrElse { fallbackErr ->
                    AppLogger.w(TAG, "query2 chart also failed for $symbol: ${fallbackErr.message}")
                    null
                }
            }
        return result?.let { toQuote(it) }
    }

    /**
     * Build a [TickerQuote] from Yahoo's chart payload. Regular-session
     * fields come from the meta block; extended-hours fields come from
     * [extractExtendedHours], which inspects the candle series.
     * Returns null if Yahoo didn't include a usable current price.
     */
    private fun toQuote(result: YahooChartResult): TickerQuote? {
        val meta = result.meta
        val price = meta.regularMarketPrice ?: return null
        val prev = meta.previousClose ?: meta.chartPreviousClose
        val change = if (prev != null) price - prev else 0.0
        val pct = if (prev != null && prev != 0.0) (price - prev) / prev * 100.0 else 0.0
        val extended = extractExtendedHours(result, regularPrice = price)
        // Volume ratio: prefer 3-month average; fall back to 10-day if
        // that's the only one Yahoo populated. Null when we can't
        // compute a meaningful ratio.
        val avgVol = meta.averageDailyVolume3Month ?: meta.averageDailyVolume10Day
        val volRatio = if (avgVol != null && avgVol > 0 && meta.regularMarketVolume != null) {
            meta.regularMarketVolume.toDouble() / avgVol.toDouble()
        } else null
        return TickerQuote(
            symbol = meta.symbol.uppercase(),
            price = price,
            change = change,
            changePercent = pct,
            extendedPrice = extended?.price,
            extendedChangePercent = extended?.percent,
            extendedSession = extended?.session,
            fiftyTwoWeekHigh = meta.fiftyTwoWeekHigh,
            fiftyTwoWeekLow = meta.fiftyTwoWeekLow,
            volumeRatio = volRatio,
            intradayCloses = regularSessionCloses(result),
        )
    }

    /**
     * Sub-sample of the candle close series that falls inside today's
     * regular trading window. Used as the data source for the
     * watchlist sparkline. Pre/post bars are dropped so the line reads
     * as today's regular-session move rather than a jagged
     * extended-hours overlay.
     */
    private fun regularSessionCloses(result: YahooChartResult): List<Double> {
        val regular = result.meta.currentTradingPeriod?.regular ?: return emptyList()
        val timestamps = result.timestamp ?: return emptyList()
        val closes = result.indicators?.quote?.firstOrNull()?.close ?: return emptyList()
        if (timestamps.size != closes.size) return emptyList()
        val out = ArrayList<Double>(timestamps.size)
        for (i in timestamps.indices) {
            val ts = timestamps[i]
            if (ts in regular.start..regular.end) {
                val c = closes[i] ?: continue
                out.add(c)
            }
        }
        return out
    }

    private data class ExtendedHours(
        val price: Double,
        val percent: Double,
        val session: ExtendedSession,
    )

    /**
     * Latest trade outside the regular session, if any.
     *
     * Strategy:
     *  1. **Cheap path** — if Yahoo populated `meta.postMarketPrice` or
     *     `meta.preMarketPrice` (rare on v8 chart, common on v7 quote),
     *     trust those directly.
     *  2. **Candle path** — walk the 15-minute candle series from the
     *     end backwards. The first non-null close whose timestamp falls
     *     OUTSIDE `currentTradingPeriod.regular` is the latest extended
     *     trade; classify it as POST if it's after `regular.end` or PRE
     *     if it's before `regular.start`.
     *
     * Percentage is computed vs. the regular-session price the caller
     * passed in — that's the meaningful baseline ("how far has the
     * stock moved since the 4pm close?").
     */
    private fun extractExtendedHours(
        result: YahooChartResult,
        regularPrice: Double,
    ): ExtendedHours? {
        val meta = result.meta
        val regular = meta.currentTradingPeriod?.regular

        // Cheap path: meta-level extended fields
        meta.postMarketPrice?.let { pp ->
            val regEnd = regular?.end ?: 0L
            val pt = meta.postMarketTime ?: 0L
            if (pt > regEnd) {
                val pct = meta.postMarketChangePercent
                    ?: ((pp - regularPrice) / regularPrice * 100.0)
                return ExtendedHours(pp, pct, ExtendedSession.POST)
            }
        }
        meta.preMarketPrice?.let { pp ->
            val regStart = regular?.start ?: Long.MAX_VALUE
            val pt = meta.preMarketTime ?: 0L
            if (pt in 1 until regStart) {
                val pct = meta.preMarketChangePercent
                    ?: ((pp - regularPrice) / regularPrice * 100.0)
                return ExtendedHours(pp, pct, ExtendedSession.PRE)
            }
        }

        // Candle fallback
        if (regular == null) return null
        val timestamps = result.timestamp ?: return null
        val closes = result.indicators?.quote?.firstOrNull()?.close ?: return null
        if (timestamps.size != closes.size) return null

        for (i in timestamps.indices.reversed()) {
            val close = closes[i] ?: continue
            val ts = timestamps[i]
            return when {
                ts > regular.end -> {
                    val pct = (close - regularPrice) / regularPrice * 100.0
                    ExtendedHours(close, pct, ExtendedSession.POST)
                }
                ts < regular.start -> {
                    val pct = (close - regularPrice) / regularPrice * 100.0
                    ExtendedHours(close, pct, ExtendedSession.PRE)
                }
                // Latest candle is in the regular window → no extended trade
                else -> null
            }
        }
        return null
    }

    private companion object {
        const val TAG = "QuotePoller"
        const val POLL_INTERVAL_MS = 60_000L
    }
}
