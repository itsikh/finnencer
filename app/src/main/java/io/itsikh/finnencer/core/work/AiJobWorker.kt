package io.itsikh.finnencer.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.itsikh.finnencer.core.notifications.AiJobNotifier
import io.itsikh.finnencer.data.ai.BundleSummarizer
import io.itsikh.finnencer.data.dao.AiJobDao
import io.itsikh.finnencer.data.entity.AiJobResultKind
import io.itsikh.finnencer.data.entity.AiJobStatus
import io.itsikh.finnencer.data.entity.AiJobType
import io.itsikh.finnencer.logging.AppLogger

/**
 * Single CoroutineWorker that handles every queued [AiJobType]. Reads
 * the row from Room, dispatches to the matching domain component, persists
 * the produced artifact, and writes the final status back. The user sees
 * progress / completion via the Tasks screen (Room flow) and a system
 * notification (this worker calls [AiJobNotifier] on success/failure).
 *
 * IDs come in as a "jobId" input data string. The worker does not get the
 * payload from input data — it reads `AiJob.inputJson` from Room so that
 * a process restart can resume cleanly without losing arguments.
 */
@HiltWorker
class AiJobWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dao: AiJobDao,
    private val bundle: BundleSummarizer,
    private val notifier: AiJobNotifier,
    private val gson: Gson,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val job = dao.get(jobId) ?: return Result.failure()

        dao.markRunning(job.id, AiJobStatus.RUNNING.name, System.currentTimeMillis())
        AppLogger.i(TAG, "running ${job.type} ${job.id} (${job.title})")

        return runCatching {
            when (AiJobType.valueOf(job.type)) {
                AiJobType.SUMMARY_BATCH -> runSummary(job.id, job.tickerSymbol, job.inputJson, job.title)
                AiJobType.PODCAST_BATCH -> runPodcast(job.id, job.inputJson)
                AiJobType.REPORT_EARNINGS -> {
                    // Not yet routed through this worker — reports still run
                    // synchronously from the tier picker sheet. Mark as
                    // failed so the row doesn't get stuck.
                    error("REPORT_EARNINGS not handled here yet")
                }
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { t ->
                AppLogger.e(TAG, "ai job ${job.id} failed", t)
                dao.markFailed(
                    job.id,
                    AiJobStatus.FAILED.name,
                    t.message ?: t.javaClass.simpleName,
                    System.currentTimeMillis(),
                )
                notifier.notifyFailed(job.id, job.title, t.message ?: "AI job failed")
                Result.failure()
            }
        )
    }

    private suspend fun runSummary(jobId: String, tickerSymbol: String?, json: String, title: String) {
        val input = gson.fromJson<SummaryInput>(json, SummaryInput.typeToken)
        val text = bundle.summarizeText(input.articleIds, input.pages, input.customPrompt)
        dao.markCompleted(
            id = jobId,
            status = AiJobStatus.COMPLETED.name,
            resultKind = AiJobResultKind.INLINE_TEXT.name,
            resultRefId = null,
            resultText = text,
            nowMs = System.currentTimeMillis(),
        )
        notifier.notifyCompleted(jobId, title, "Summary ready · open Tasks")
    }

    private suspend fun runPodcast(jobId: String, json: String) {
        val input = gson.fromJson(json, PodcastInput::class.java)
        val podcastId = bundle.summarizeToPodcast(
            articleIds = input.articleIds,
            minutes = input.minutes,
            customPrompt = input.customPrompt,
        )
        dao.markCompleted(
            id = jobId,
            status = AiJobStatus.COMPLETED.name,
            resultKind = AiJobResultKind.PODCAST.name,
            resultRefId = podcastId.toString(),
            resultText = null,
            nowMs = System.currentTimeMillis(),
        )
        notifier.notifyCompleted(jobId, "Podcast ready", "Open Tasks to listen")
    }

    data class SummaryInput(
        val articleIds: List<String>,
        val pages: BundleSummarizer.Pages,
        val customPrompt: String?,
    ) {
        companion object {
            val typeToken = object : TypeToken<SummaryInput>() {}.type
        }
    }

    data class PodcastInput(
        val articleIds: List<String>,
        val minutes: BundleSummarizer.PodcastMinutes,
        val customPrompt: String?,
    )

    companion object {
        const val KEY_JOB_ID = "ai_job_id"
        private const val TAG = "AiJobWorker"
    }
}
