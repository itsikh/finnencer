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

    companion object {
        const val TAG_AI_JOB = "ai-job"
    }
}
