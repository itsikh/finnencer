package io.itsikh.finnencer.core.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
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
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val stats = engine.runOnce()
        Log.i(TAG, "sync done: $stats")
        Result.success()
    } catch (t: Throwable) {
        Log.e(TAG, "sync failed", t)
        // Soft-failure: retry, but bounded — WorkManager applies exponential
        // backoff capped at the next scheduled period anyway.
        Result.retry()
    }

    private companion object {
        const val TAG = "SyncWorker"
    }
}
