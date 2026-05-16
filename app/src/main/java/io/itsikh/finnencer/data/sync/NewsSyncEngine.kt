package io.itsikh.finnencer.data.sync

import io.itsikh.finnencer.logging.AppLogger as Log
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.entity.ArticleTickerXref
import io.itsikh.finnencer.data.entity.NewsArticle
import io.itsikh.finnencer.data.providers.EdgarCikLookup
import io.itsikh.finnencer.data.providers.NewsProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates one sync cycle: walk every watched ticker, ask every provider
 * for recent items, deduplicate, persist. Pure data — no scheduling, no UI.
 *
 * Always idempotent: the same (provider, sourceArticleId) pair produces the
 * same stable id, and Room's IGNORE conflict strategy drops duplicates
 * silently. Re-running [runOnce] back-to-back is a no-op other than network
 * cost.
 */
@Singleton
class NewsSyncEngine @Inject constructor(
    private val tickerDao: TickerDao,
    private val newsDao: NewsDao,
    private val providers: Set<@JvmSuppressWildcards NewsProvider>,
    private val cikLookup: EdgarCikLookup,
) {

    data class CycleStats(
        val tickersProcessed: Int,
        val articlesInserted: Int,
        val providerErrors: Int,
        val durationMs: Long,
    )

    /**
     * @param sinceMillis ignore articles older than this. Default: 36h.
     */
    suspend fun runOnce(sinceMillis: Long = System.currentTimeMillis() - DEFAULT_WINDOW): CycleStats {
        val start = System.currentTimeMillis()
        val tickers = tickerDao.getAll()
        if (tickers.isEmpty()) {
            return CycleStats(0, 0, 0, System.currentTimeMillis() - start)
        }

        var inserted = 0
        var errors = 0

        for (ticker in tickers) {
            // Best-effort backfill of CIK on a ticker that doesn't have one
            // yet — cheap and unblocks the EDGAR provider.
            val cik = ticker.cik ?: runCatching { cikLookup.resolve(ticker.symbol) }
                .onSuccess { c ->
                    if (c != null) tickerDao.update(ticker.copy(cik = c))
                }
                .getOrNull()

            val perTicker = ArrayList<NewsArticle>(64)
            for (provider in providers) {
                val fetched = runCatching {
                    provider.fetchForTicker(
                        tickerSymbol = ticker.symbol,
                        companyName = ticker.name,
                        cik = cik,
                        sinceMillis = sinceMillis,
                    )
                }.onFailure {
                    errors++
                    Log.w(TAG, "provider ${provider.key} failed for ${ticker.symbol}: ${it.message}")
                }.getOrDefault(emptyList())
                perTicker.addAll(fetched)
            }

            if (perTicker.isEmpty()) continue

            // Dedup within the cycle: same id from multiple providers is
            // possible (e.g. when source URLs collide).
            val unique = perTicker.distinctBy { it.id }

            // Drop articles already in the DB so we know how many are net new
            val existing = newsDao.existingIds(unique.map { it.id }).toHashSet()
            val fresh = unique.filterNot { existing.contains(it.id) }
            if (fresh.isEmpty()) continue

            newsDao.insertArticles(fresh)
            newsDao.linkTickers(
                fresh.map { ArticleTickerXref(articleId = it.id, tickerSymbol = ticker.symbol) }
            )
            inserted += fresh.size
        }

        return CycleStats(
            tickersProcessed = tickers.size,
            articlesInserted = inserted,
            providerErrors = errors,
            durationMs = System.currentTimeMillis() - start,
        )
    }

    private companion object {
        const val TAG = "NewsSyncEngine"
        const val DEFAULT_WINDOW = 36L * 60 * 60 * 1000
    }
}
