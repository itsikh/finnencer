package io.itsikh.finnencer.core.work

import io.itsikh.finnencer.data.dao.AiJobDao
import io.itsikh.finnencer.data.entity.AiJobStage
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext as KCoroutineContext

/**
 * Pushes per-job stage / progress / detail updates to:
 *  - the [ai_jobs] Room row (so the Task Detail screen and the Tasks
 *    list can read the most recent state even after a process restart),
 *  - an in-memory [SharedFlow] for sub-second UI updates without a DB
 *    round-trip every time.
 *
 * Workers identify the job they're working on via a [JobIdContext]
 * coroutine-context element so internal helpers (BundleSummarizer,
 * GeminiTts) don't need to thread the jobId through every signature.
 */
@Singleton
class JobProgressReporter @Inject constructor(
    private val dao: AiJobDao,
) {

    data class Event(
        val jobId: String,
        val stage: AiJobStage,
        val progress: Int, // 0..100
        val detail: String?,
        val timestampMs: Long = System.currentTimeMillis(),
    )

    private val _events = MutableSharedFlow<Event>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events

    /**
     * Persist [stage] / [progress] / [detail] for the job currently in
     * coroutine context. No-op if there is no jobId on the context (e.g.
     * being called from outside a worker). Best-effort: a DB write
     * failure is logged but doesn't bubble — we never want progress
     * reporting to fail the actual work.
     */
    suspend fun update(
        stage: AiJobStage,
        progress: Int = 0,
        detail: String? = null,
    ) {
        val jobId = kotlin.coroutines.coroutineContext[JobIdContext]?.value ?: return
        val clamped = progress.coerceIn(0, 100)
        val event = Event(jobId = jobId, stage = stage, progress = clamped, detail = detail)
        _events.tryEmit(event)
        runCatching {
            dao.setStage(jobId, stage.name, clamped, detail)
        }.onFailure {
            AppLogger.w(TAG, "progress update failed for $jobId: ${it.message}")
        }
    }

    /** Same as [update] but for callers that already have the jobId
     *  in hand (e.g. the worker itself before entering the job context). */
    suspend fun updateExplicit(jobId: String, stage: AiJobStage, progress: Int = 0, detail: String? = null) {
        val clamped = progress.coerceIn(0, 100)
        val event = Event(jobId = jobId, stage = stage, progress = clamped, detail = detail)
        _events.tryEmit(event)
        runCatching {
            dao.setStage(jobId, stage.name, clamped, detail)
        }.onFailure {
            AppLogger.w(TAG, "progress update failed for $jobId: ${it.message}")
        }
    }

    private companion object { const val TAG = "JobProgressReporter" }
}

/**
 * Coroutine-context element carrying the AiJob id of the work currently
 * in progress. Set once at the top of [io.itsikh.finnencer.core.work.AiJobWorker.doWork]
 * via `withContext(JobIdContext(jobId)) { ... }`; read in
 * [JobProgressReporter.update].
 */
class JobIdContext(val value: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : KCoroutineContext.Key<JobIdContext>
}

/** Logger helper that prefixes the tag with the current job id (if any)
 *  so the Task Detail screen can filter the in-memory log buffer to
 *  just this job's lines (#43). */
suspend fun jobLog(tag: String, message: String, level: io.itsikh.finnencer.logging.LogLevel = io.itsikh.finnencer.logging.LogLevel.INFO) {
    val jobId = kotlin.coroutines.coroutineContext[JobIdContext]?.value
    val taggedTag = if (jobId != null) "$tag[${jobId.take(8)}]" else tag
    when (level) {
        io.itsikh.finnencer.logging.LogLevel.DEBUG -> AppLogger.d(taggedTag, message)
        io.itsikh.finnencer.logging.LogLevel.INFO -> AppLogger.i(taggedTag, message)
        io.itsikh.finnencer.logging.LogLevel.WARN -> AppLogger.w(taggedTag, message)
        io.itsikh.finnencer.logging.LogLevel.ERROR -> AppLogger.e(taggedTag, message)
        io.itsikh.finnencer.logging.LogLevel.NONE -> Unit
    }
}
