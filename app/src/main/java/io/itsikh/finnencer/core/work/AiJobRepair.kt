package io.itsikh.finnencer.core.work

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.data.dao.AiJobDao
import io.itsikh.finnencer.data.entity.AiJobStatus
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Startup reconciliation for ai_jobs rows stranded in QUEUED/RUNNING with
 * no live WorkManager work behind them (#88 audit, F-1/F-2): a process
 * death between our Room write and WorkManager's own commit — or a
 * failure-handler that couldn't write — leaves a row the Tasks screen
 * shows as actively running FOREVER, with a ticking elapsed counter and
 * no Retry button (retry is gated on FAILED).
 *
 * For each active-looking row older than [MIN_AGE_MS] (the age gate
 * avoids racing a just-enqueued job), we ask WorkManager for the unique
 * work "ai-job:<id>": if nothing is alive, the row is flipped to FAILED
 * with a friendly "tap Retry" message — the existing retry path then
 * re-enqueues it cleanly, reusing the persisted script / chunk cache.
 */
@Singleton
class AiJobRepair @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AiJobDao,
) {

    suspend fun repairIfNeeded() {
        val now = System.currentTimeMillis()
        val active = dao.getByStatuses(listOf(AiJobStatus.QUEUED.name, AiJobStatus.RUNNING.name))
            .filter { now - it.createdAtMillis > MIN_AGE_MS }
        if (active.isEmpty()) return

        val wm = WorkManager.getInstance(context)
        var repaired = 0
        for (job in active) {
            val infos = runCatching {
                // ListenableFuture.get() blocks — keep it off main.
                withContext(Dispatchers.IO) { wm.getWorkInfosForUniqueWork("ai-job:${job.id}").get() }
            }.getOrNull() ?: continue
            val alive = infos.any { !it.state.isFinished }
            if (alive) continue
            AppLogger.w(TAG, "ai job ${job.id} (${job.status}, stage=${job.currentStage}) has no live WorkManager work — marking FAILED for manual retry")
            runCatching {
                dao.markFailed(
                    job.id,
                    AiJobStatus.FAILED.name,
                    "Interrupted by an app restart — tap Retry to resume.",
                    now,
                )
            }.onFailure { AppLogger.e(TAG, "repair write failed for ${job.id}", it) }
            repaired++
        }
        if (repaired > 0) AppLogger.i(TAG, "repaired $repaired orphaned ai_jobs row(s)")
    }

    private companion object {
        const val TAG = "AiJobRepair"
        /** Fresh enqueues insert the row BEFORE WorkManager registers the
         *  work — don't judge rows younger than this. */
        const val MIN_AGE_MS = 2 * 60 * 1000L
    }
}
