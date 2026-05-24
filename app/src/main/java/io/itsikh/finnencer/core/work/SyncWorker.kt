package io.itsikh.finnencer.core.work

import android.content.Context
import io.itsikh.finnencer.logging.AppLogger as Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.itsikh.finnencer.core.notifications.AlertNotifier
import io.itsikh.finnencer.data.ai.ImportanceScorer
import io.itsikh.finnencer.data.dao.ApiUsageDao
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.repo.ApiKey
import io.itsikh.finnencer.data.repo.ApiKeysRepository
import io.itsikh.finnencer.data.repo.RetentionPreferences
import io.itsikh.finnencer.data.sync.EarningsCalendarSync
import io.itsikh.finnencer.data.sync.EarningsNumericSync
import io.itsikh.finnencer.data.sync.NewsSyncEngine

/**
 * Periodic background worker that runs one news-sync cycle. Triggered by
 * [SyncScheduler]. Hilt-injected via [HiltWorker] + [AssistedInject].
 *
 * Importance scoring + notification fanout are NOT done here yet — they
 * land in Build A·9 and A·10. For now this only ingests articles into Room.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: NewsSyncEngine,
    private val earningsSync: EarningsCalendarSync,
    private val earningsNumericSync: EarningsNumericSync,
    private val scorer: ImportanceScorer,
    private val notifier: AlertNotifier,
    private val apiKeys: ApiKeysRepository,
    private val newsDao: NewsDao,
    private val apiUsageDao: ApiUsageDao,
    private val retentionPrefs: RetentionPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        runPipeline()
        Result.success()
    } catch (t: Throwable) {
        Log.e(TAG, "sync failed", t)
        Result.retry()
    }

    private suspend fun runPipeline() {
        // Stage 1 — ingest from all news providers.
        val ingestStats = engine.runOnce()
        Log.i(TAG, "ingest done: $ingestStats")

        // Stage 1b — earnings discovery. EDGAR seeds filing dates;
        // Finnhub fills in consensus + actual EPS / revenue so the
        // ReportGenerator doesn't have to write "Earnings data
        // unavailable" into every brief (#20).
        val earningsInserted = runCatching { earningsSync.runOnce() }
            .onFailure { Log.e(TAG, "EDGAR earnings sync failed", it) }
            .getOrDefault(0)
        Log.i(TAG, "EDGAR earnings: $earningsInserted new rows")
        if (apiKeys.isConfigured(ApiKey.FINNHUB)) {
            val numericUpdated = runCatching { earningsNumericSync.runOnce() }
                .onFailure { Log.e(TAG, "Finnhub earnings numeric sync failed", it) }
                .getOrDefault(0)
            Log.i(TAG, "Finnhub earnings numbers: $numericUpdated rows updated")
        }

        // Stage 2 — score newly-ingested articles with Claude Haiku. Skipped
        // entirely if the user hasn't pasted an Anthropic key yet.
        if (!apiKeys.isConfigured(ApiKey.ANTHROPIC)) {
            Log.i(TAG, "scoring skipped: ANTHROPIC key not configured")
            return
        }
        val scorerStats = scorer.scoreUnscored()
        Log.i(TAG, "scoring done: $scorerStats")

        // Stage 3 — fan notifications out for any newly-scored items that
        // pass per-ticker threshold + quiet hours + dedup gates.
        val fanout = notifier.fanout(scorerStats.newScores)
        Log.i(TAG, "fanout: $fanout")

        // Stage 4 — retention sweep. Trim cached news + API-usage rows
        // older than the user-configured retention windows so the DB
        // stays bounded under every-15-min syncs.
        runCatching { pruneStale() }
            .onFailure { Log.e(TAG, "retention prune failed (non-fatal)", it) }
    }

    private suspend fun pruneStale() {
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000

        val newsDays = retentionPrefs.getNewsRetentionDays()
        val newsCutoff = now - newsDays * day
        val newsPruned = newsDao.pruneOlderThan(newsCutoff)

        val usageDays = retentionPrefs.getApiUsageRetentionDays()
        val usageCutoff = now - usageDays * day
        val usagePruned = apiUsageDao.pruneOlderThan(usageCutoff)

        Log.i(TAG, "prune: news=$newsPruned (>${newsDays}d) api_usage=$usagePruned (>${usageDays}d)")
    }

    private companion object {
        const val TAG = "SyncWorker"
    }
}
