package io.itsikh.finnencer.data.repo

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.core.work.AiJobWorker
import io.itsikh.finnencer.data.ai.BundleSummarizer
import io.itsikh.finnencer.data.dao.AiJobDao
import io.itsikh.finnencer.data.entity.AiJob
import io.itsikh.finnencer.data.entity.AiJobStatus
import io.itsikh.finnencer.data.entity.AiJobType
import io.itsikh.finnencer.data.entity.PodcastGenerationStatus
import io.itsikh.finnencer.data.entity.ReportTier
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Front door for the Tasks subsystem. Persists a queued [AiJob] row, then
 * fires a OneTime WorkManager request that the [AiJobWorker] consumes.
 *
 * Why two layers (Room + WM) instead of just WM data:
 *  - WM Data is bounded (~10KB) and not directly observable from the UI.
 *  - The user wants to scroll Tasks history weeks later, which means we
 *    keep the row independent of WM's job state machine. WM is purely the
 *    execution engine; Room is the source of truth.
 */
@Singleton
class AiJobsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AiJobDao,
    private val gson: Gson,
) {

    fun observeAll(): Flow<List<AiJob>> = dao.observeAll()
    fun observeActiveCount(): Flow<Int> = dao.observeActiveCount()

    suspend fun enqueueBatchSummary(
        tickerSymbol: String?,
        articleIds: List<String>,
        pages: BundleSummarizer.Pages,
        customPrompt: String?,
    ): String {
        val id = UUID.randomUUID().toString()
        val title = "Summary · ${articleIds.size} articles · ${pages.target}-pg"
        val subtitle = customPrompt?.takeIf { it.isNotBlank() }
        val input = AiJobWorker.SummaryInput(articleIds, pages.target, customPrompt)
        return insertAndEnqueue(
            id = id,
            type = AiJobType.SUMMARY_BATCH,
            title = title,
            subtitle = subtitle,
            tickerSymbol = tickerSymbol,
            inputJson = gson.toJson(input),
        )
    }

    suspend fun enqueueBatchPodcast(
        tickerSymbol: String?,
        articleIds: List<String>,
        minutes: BundleSummarizer.PodcastMinutes,
        customPrompt: String?,
    ): String {
        val id = UUID.randomUUID().toString()
        val title = "Podcast · ${articleIds.size} articles · ${minutes.minutes} min"
        val subtitle = customPrompt?.takeIf { it.isNotBlank() }
        val input = AiJobWorker.PodcastInput(articleIds, minutes.minutes, customPrompt)
        return insertAndEnqueue(
            id = id,
            type = AiJobType.PODCAST_BATCH,
            title = title,
            subtitle = subtitle,
            tickerSymbol = tickerSymbol,
            inputJson = gson.toJson(input),
        )
    }

    /**
     * Per-stock earnings combo: BRIEF earnings report (resolved or newly
     * generated) plus a podcast scripted from it. The worker decides which
     * model to use; the user just picks the podcast length.
     */
    /**
     * Standalone earnings report (BRIEF / STANDARD / DEEP) as a persistent
     * background job. Lets the user navigate away mid-generation without
     * losing the work — previously this ran in viewModelScope and was
     * canceled on back-press.
     */
    suspend fun enqueueEarningsReport(
        tickerSymbol: String,
        earningsEventId: Long,
        eventLabel: String,
        tier: ReportTier,
    ): String {
        val id = UUID.randomUUID().toString()
        val tierLabel = tier.name.lowercase().replaceFirstChar { it.uppercase() }
        val title = "$tickerSymbol earnings · $eventLabel · $tierLabel"
        val input = AiJobWorker.EarningsReportInput(
            earningsEventId = earningsEventId,
            tierName = tier.name,
        )
        return insertAndEnqueue(
            id = id,
            type = AiJobType.REPORT_EARNINGS,
            title = title,
            subtitle = null,
            tickerSymbol = tickerSymbol,
            inputJson = gson.toJson(input),
        )
    }

    suspend fun enqueueEarningsBriefAndPodcast(
        tickerSymbol: String,
        earningsEventId: Long,
        eventLabel: String,
        minutes: BundleSummarizer.PodcastMinutes,
        customPrompt: String?,
    ): String {
        val id = UUID.randomUUID().toString()
        val title = "$tickerSymbol earnings · $eventLabel · ${minutes.minutes}-min podcast"
        val subtitle = customPrompt?.takeIf { it.isNotBlank() }
        val input = AiJobWorker.EarningsBriefAndPodcastInput(
            earningsEventId = earningsEventId,
            minutesValue = minutes.minutes,
            customPrompt = customPrompt,
        )
        return insertAndEnqueue(
            id = id,
            type = AiJobType.EARNINGS_BRIEF_AND_PODCAST,
            title = title,
            subtitle = subtitle,
            tickerSymbol = tickerSymbol,
            inputJson = gson.toJson(input),
        )
    }

    suspend fun enqueueSummaryAndPodcast(
        tickerSymbol: String?,
        articleIds: List<String>,
        pages: BundleSummarizer.Pages,
        minutes: BundleSummarizer.PodcastMinutes,
        customPrompt: String?,
    ): String {
        val id = UUID.randomUUID().toString()
        val title = "Summary + Podcast · ${articleIds.size} articles · ${pages.target}-pg · ${minutes.minutes} min"
        val subtitle = customPrompt?.takeIf { it.isNotBlank() }
        val input = AiJobWorker.SummaryAndPodcastInput(
            articleIds = articleIds,
            pagesTarget = pages.target,
            minutesValue = minutes.minutes,
            customPrompt = customPrompt,
        )
        return insertAndEnqueue(
            id = id,
            type = AiJobType.SUMMARY_AND_PODCAST_BATCH,
            title = title,
            subtitle = subtitle,
            tickerSymbol = tickerSymbol,
            inputJson = gson.toJson(input),
        )
    }

    private suspend fun insertAndEnqueue(
        id: String,
        type: AiJobType,
        title: String,
        subtitle: String?,
        tickerSymbol: String?,
        inputJson: String,
    ): String {
        dao.insert(
            AiJob(
                id = id,
                type = type.name,
                status = AiJobStatus.QUEUED.name,
                title = title,
                subtitle = subtitle,
                tickerSymbol = tickerSymbol,
                inputJson = inputJson,
                resultKind = null,
                resultRefId = null,
                resultText = null,
                resultModel = null,
                errorMessage = null,
                createdAtMillis = System.currentTimeMillis(),
                startedAtMillis = null,
                completedAtMillis = null,
            )
        )
        val request = OneTimeWorkRequestBuilder<AiJobWorker>()
            .setInputData(workDataOf(AiJobWorker.KEY_JOB_ID to id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(TAG_AI_JOB)
            .addTag("ai-job:$id")
            .build()
        WorkManager.getInstance(context).enqueue(request)
        return id
    }

    suspend fun clearFinished() = dao.clearFinished()

    suspend fun delete(jobId: String) = dao.delete(jobId)

    /**
     * User read the validator-flagged script and decided to ship it
     * anyway. Flip the podcast row's `forceAcceptScript` so the next
     * worker run skips validation, reset the AiJob to QUEUED, and
     * re-enqueue. The worker picks up where it left off — script is
     * already persisted, so it goes straight to TTS.
     */
    suspend fun resumeFromValidationReview(jobId: String, podcastDao: io.itsikh.finnencer.data.dao.PodcastDao) {
        val existing = dao.get(jobId) ?: return
        if (existing.status != AiJobStatus.PENDING_REVIEW.name) return
        val podcastId = existing.resultRefId?.toLongOrNull()
        if (podcastId != null) {
            podcastDao.get(podcastId)?.let { row ->
                podcastDao.update(
                    row.copy(
                        forceAcceptScript = true,
                        status = PodcastGenerationStatus.PENDING.name,
                        generationError = null,
                    )
                )
            }
        }
        dao.markQueued(jobId)
        val request = OneTimeWorkRequestBuilder<AiJobWorker>()
            .setInputData(workDataOf(AiJobWorker.KEY_JOB_ID to existing.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(TAG_AI_JOB)
            .addTag("ai-job:${existing.id}")
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    /**
     * User read the validator-flagged script and decided to give up on
     * it. Mark both rows FAILED with the validator's reason so they
     * appear in the failed list and can be retried-fresh later.
     */
    suspend fun cancelFromValidationReview(jobId: String, podcastDao: io.itsikh.finnencer.data.dao.PodcastDao) {
        val existing = dao.get(jobId) ?: return
        val reason = existing.errorMessage?.takeIf { it.isNotBlank() }
            ?: "Cancelled by user after validator review"
        val podcastId = existing.resultRefId?.toLongOrNull()
        if (podcastId != null) {
            podcastDao.get(podcastId)?.let { row ->
                podcastDao.update(
                    row.copy(
                        status = PodcastGenerationStatus.FAILED.name,
                        generationError = reason,
                    )
                )
            }
        }
        dao.markFailed(
            jobId,
            AiJobStatus.FAILED.name,
            reason,
            System.currentTimeMillis(),
        )
    }

    /**
     * Re-run a previously-finished job (failed, completed, or canceled).
     * Reuses the same row id + inputJson so the user's history shows
     * "the same thing happened again" rather than a duplicate row.
     */
    suspend fun retry(jobId: String) {
        val existing = dao.get(jobId) ?: return
        dao.markQueued(jobId)
        val request = OneTimeWorkRequestBuilder<AiJobWorker>()
            .setInputData(workDataOf(AiJobWorker.KEY_JOB_ID to existing.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(TAG_AI_JOB)
            .addTag("ai-job:${existing.id}")
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    companion object {
        const val TAG_AI_JOB = "ai-job"
    }
}
