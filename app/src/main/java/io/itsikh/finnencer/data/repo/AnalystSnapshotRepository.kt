package io.itsikh.finnencer.data.repo

import com.google.gson.Gson
import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.dao.TickerAnalystSnapshotDao
import io.itsikh.finnencer.data.entity.TickerAnalystSnapshot
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-ticker analyst-coverage snapshot — price target, recommendation
 * mix — cached daily so the watchlist row's "PT vs current quote"
 * arrow can render without burning Finnhub calls on every list
 * refresh. The same table is consumed by [data.ai.ReportGenerator]
 * when building earnings reports, so a snapshot warmed for the
 * watchlist is also free for the next report.
 *
 * Freshness contract: callers should accept any cached row up to 24h
 * old. Use [refreshStale] to backfill missing or stale rows for a list
 * of tickers (typically the user's watchlist on first open of the day).
 */
@Singleton
class AnalystSnapshotRepository @Inject constructor(
    private val dao: TickerAnalystSnapshotDao,
    private val finnhub: FinnhubService,
    private val gson: Gson,
) {

    fun observe(tickers: List<String>): Flow<List<TickerAnalystSnapshot>> =
        dao.observeMany(tickers)

    /**
     * Refresh any cached row in [tickers] that's either missing or
     * older than [ttlMillis]. Best-effort — per-ticker failures are
     * logged and skipped so a single Finnhub 429 doesn't break the
     * whole batch. Idempotent: calling repeatedly within the TTL is a
     * no-op after the first hit.
     */
    suspend fun refreshStale(
        tickers: List<String>,
        ttlMillis: Long = DEFAULT_TTL_MS,
    ): Int {
        if (tickers.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val existing = dao.getMany(tickers).associateBy { it.ticker }
        val toFetch = tickers.filter { sym ->
            val cached = existing[sym]
            cached == null || (now - cached.fetchedAtMillis) >= ttlMillis
        }
        if (toFetch.isEmpty()) return 0

        var refreshed = 0
        for (symbol in toFetch) {
            val pt = runCatching { finnhub.priceTarget(symbol) }
                .onFailure { AppLogger.w(TAG, "priceTarget($symbol) failed: ${it.message}") }
                .getOrNull()
            val recs = runCatching { finnhub.recommendationTrends(symbol) }
                .onFailure { AppLogger.w(TAG, "recommendationTrends($symbol) failed: ${it.message}") }
                .getOrNull().orEmpty()
            if (pt == null && recs.isEmpty()) continue
            dao.upsert(
                TickerAnalystSnapshot(
                    ticker = symbol,
                    fetchedAtMillis = now,
                    targetHigh = pt?.targetHigh,
                    targetLow = pt?.targetLow,
                    targetMean = pt?.targetMean,
                    targetMedian = pt?.targetMedian,
                    lastUpdated = pt?.lastUpdated,
                    recommendationTrendsJson = gson.toJson(recs),
                )
            )
            refreshed++
        }
        AppLogger.i(TAG, "refreshStale: refreshed=$refreshed of ${toFetch.size} requested")
        return refreshed
    }

    private companion object {
        const val TAG = "AnalystSnapshotRepository"
        const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000
    }
}
