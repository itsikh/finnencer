package io.itsikh.finnencer.core.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.itsikh.finnencer.core.notifications.AlertNotifier
import io.itsikh.finnencer.data.ai.ImportanceScorer
import io.itsikh.finnencer.data.repo.ApiKey
import io.itsikh.finnencer.data.repo.ApiKeysRepository
import io.itsikh.finnencer.data.sync.EarningsCalendarSync
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
    private val scorer: ImportanceScorer,
    private val notifier: AlertNotifier,
    private val apiKeys: ApiKeysRepository,
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

        // Stage 1b — earnings calendar (cheap; same Finnhub key).
        if (apiKeys.isConfigured(ApiKey.FINNHUB)) {
            val earningsInserted = runCatching { earningsSync.runOnce() }
                .onFailure { Log.w(TAG, "earnings sync failed", it) }
                .getOrDefault(0)
            Log.i(TAG, "earnings calendar: $earningsInserted new rows")
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
    }

    private companion object {
        const val TAG = "SyncWorker"
    }
}
