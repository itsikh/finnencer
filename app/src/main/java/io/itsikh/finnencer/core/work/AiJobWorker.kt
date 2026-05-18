package io.itsikh.finnencer.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.itsikh.finnencer.core.notifications.AiJobNotifier
import io.itsikh.finnencer.data.ai.BundleSummarizer
import io.itsikh.finnencer.data.ai.ReportGenerator
import io.itsikh.finnencer.data.dao.AiJobDao
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.entity.AiJobResultKind
import io.itsikh.finnencer.data.entity.AiJobStatus
import io.itsikh.finnencer.data.entity.AiJobType
import io.itsikh.finnencer.data.entity.ReportTier
import kotlinx.coroutines.flow.first
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
    private val reportGenerator: ReportGenerator,
    private val earningsDao: EarningsDao,
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
                AiJobType.SUMMARY_AND_PODCAST_BATCH -> runSummaryAndPodcast(job.id, job.inputJson, job.title)
                AiJobType.EARNINGS_BRIEF_AND_PODCAST -> runEarningsBriefAndPodcast(job.id, job.inputJson, job.title)
                AiJobType.REPORT_EARNINGS -> runEarningsReport(job.id, job.inputJson, job.title)
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { t ->
                val friendly = io.itsikh.finnencer.data.ai.FriendlyError.describe(t)
                AppLogger.e(TAG, "ai job ${job.id} failed: $friendly", t)
                dao.markFailed(
                    job.id,
                    AiJobStatus.FAILED.name,
                    friendly,
                    System.currentTimeMillis(),
                )
                notifier.notifyFailed(job.id, job.title, friendly)
                Result.failure()
            }
        )
    }

    private suspend fun runSummary(jobId: String, tickerSymbol: String?, json: String, title: String) {
        val input = gson.fromJson(json, SummaryInput::class.java)
        val pages = BundleSummarizer.Pages.entries.firstOrNull { it.target == input.pagesTarget }
            ?: BundleSummarizer.Pages.TWO
        val result = bundle.summarizeText(input.articleIds, pages, input.customPrompt)
        dao.markCompleted(
            id = jobId,
            status = AiJobStatus.COMPLETED.name,
            resultKind = AiJobResultKind.INLINE_TEXT.name,
            resultRefId = null,
            resultText = result.text,
            resultModel = result.modelId,
            nowMs = System.currentTimeMillis(),
        )
        notifier.notifyCompleted(jobId, title, "Summary ready · open Tasks")
    }

    private suspend fun runPodcast(jobId: String, json: String) {
        val input = gson.fromJson(json, PodcastInput::class.java)
        val minutes = BundleSummarizer.PodcastMinutes.entries.firstOrNull { it.minutes == input.minutesValue }
            ?: BundleSummarizer.PodcastMinutes.FIVE
        val existingPodcastId = dao.get(jobId)?.resultRefId?.toLongOrNull()
        val podcastId = bundle.summarizeToPodcast(
            articleIds = input.articleIds,
            minutes = minutes,
            customPrompt = input.customPrompt,
            existingPodcastId = existingPodcastId,
            // Persist the row id the moment Bundle creates/reuses it so a
            // retry after a mid-generation process kill finds the same
            // row instead of inserting a duplicate (#39).
            onPodcastIdAssigned = { id -> dao.setResultRefId(jobId, id.toString()) },
        )
        dao.markCompleted(
            id = jobId,
            status = AiJobStatus.COMPLETED.name,
            resultKind = AiJobResultKind.PODCAST.name,
            resultRefId = podcastId.toString(),
            resultText = null,
            resultModel = null,
            nowMs = System.currentTimeMillis(),
        )
        notifier.notifyCompleted(jobId, "Podcast ready", "Open Tasks to listen")
    }

    /**
     * Cross-process JSON payload for the SUMMARY_BATCH job. Enums are
     * intentionally serialized as their primitive int field instead of as
     * the enum itself: R8 full-mode strips the field names on enums that
     * aren't covered by a -keep rule (BundleSummarizer$Pages lives in
     * data.ai, which isn't kept), which breaks Gson's name-based enum
     * codec and deserializes `pages` to null. Storing the int is robust
     * to any future R8 optimization pass.
     */
    data class SummaryInput(
        val articleIds: List<String>,
        val pagesTarget: Int,
        val customPrompt: String?,
    )

    /** See [SummaryInput] for why this uses `Int` rather than [BundleSummarizer.PodcastMinutes]. */
    data class PodcastInput(
        val articleIds: List<String>,
        val minutesValue: Int,
        val customPrompt: String?,
    )

    /** Combo payload — summary first, then podcast derived from the summary text. */
    data class SummaryAndPodcastInput(
        val articleIds: List<String>,
        val pagesTarget: Int,
        val minutesValue: Int,
        val customPrompt: String?,
    )

    /**
     * Per-stock earnings combo payload. The worker resolves an existing
     * BRIEF report for [earningsEventId] (or generates one if missing) and
     * then renders a podcast scripted from that report's markdown body.
     */
    data class EarningsBriefAndPodcastInput(
        val earningsEventId: Long,
        val minutesValue: Int,
        val customPrompt: String?,
    )

    /**
     * Standalone earnings-report job. Deep dive / Standard / Brief all
     * route through here so the work persists across navigation. Tier
     * is serialized by name; the worker reads it back via [ReportTier.valueOf].
     */
    data class EarningsReportInput(
        val earningsEventId: Long,
        val tierName: String,
    )

    private suspend fun runEarningsReport(jobId: String, json: String, title: String) {
        val input = gson.fromJson(json, EarningsReportInput::class.java)
        val tier = ReportTier.valueOf(input.tierName)
        // De-dupe: if a report at this tier already exists for the event
        // (another path produced it while this job was queued) reuse it
        // instead of burning tokens to regenerate.
        val event = earningsDao.getEvent(input.earningsEventId)
            ?: error("EarningsEvent ${input.earningsEventId} not found")
        val existing = earningsDao.observeReportsForTicker(event.tickerSymbol).first()
            .firstOrNull { it.earningsEventId == event.id && it.tier == tier.name }
        val reportId = existing?.id ?: reportGenerator.generate(event.id, tier)
        val report = earningsDao.getReport(reportId)
            ?: error("freshly generated report $reportId missing")
        dao.markCompleted(
            id = jobId,
            status = AiJobStatus.COMPLETED.name,
            resultKind = AiJobResultKind.EARNINGS_REPORT.name,
            resultRefId = reportId.toString(),
            resultText = null,
            resultModel = report.model,
            nowMs = System.currentTimeMillis(),
        )
        notifier.notifyCompleted(jobId, title, "${tier.name.lowercase()} report ready · open Tasks")
    }

    private suspend fun runEarningsBriefAndPodcast(jobId: String, json: String, title: String) {
        val input = gson.fromJson(json, EarningsBriefAndPodcastInput::class.java)
        val minutes = BundleSummarizer.PodcastMinutes.entries.firstOrNull { it.minutes == input.minutesValue }
            ?: BundleSummarizer.PodcastMinutes.TEN
        val event = earningsDao.getEvent(input.earningsEventId)
            ?: error("EarningsEvent ${input.earningsEventId} not found")
        // Look for an existing BRIEF report for this event; only generate a
        // new one if there isn't one already, so re-runs don't burn tokens.
        val existingBrief = earningsDao.observeReportsForTicker(event.tickerSymbol)
            .first()
            .firstOrNull { it.earningsEventId == event.id && it.tier == ReportTier.BRIEF.name }
        val reportId = existingBrief?.id ?: reportGenerator.generate(event.id, ReportTier.BRIEF)
        val report = earningsDao.getReport(reportId) ?: error("freshly generated report $reportId missing")
        val existingPodcastId = dao.get(jobId)?.resultRefId?.toLongOrNull()
        val podcastId = bundle.podcastFromEarningsReport(
            reportId = reportId,
            minutes = minutes,
            customPrompt = input.customPrompt,
            existingPodcastId = existingPodcastId,
            onPodcastIdAssigned = { id -> dao.setResultRefId(jobId, id.toString()) },
        )
        dao.markCompleted(
            id = jobId,
            status = AiJobStatus.COMPLETED.name,
            resultKind = AiJobResultKind.SUMMARY_AND_PODCAST.name,
            resultRefId = podcastId.toString(),
            resultText = report.contentMarkdown,
            resultModel = report.model,
            nowMs = System.currentTimeMillis(),
        )
        notifier.notifyCompleted(jobId, title, "Earnings podcast ready · open Tasks")
    }

    private suspend fun runSummaryAndPodcast(jobId: String, json: String, title: String) {
        val input = gson.fromJson(json, SummaryAndPodcastInput::class.java)
        val pages = BundleSummarizer.Pages.entries.firstOrNull { it.target == input.pagesTarget }
            ?: BundleSummarizer.Pages.FIVE
        val minutes = BundleSummarizer.PodcastMinutes.entries.firstOrNull { it.minutes == input.minutesValue }
            ?: BundleSummarizer.PodcastMinutes.TEN
        // 1. Summary first — its text becomes both the inline result AND the
        //    podcast-script source material so the audio narrative aligns
        //    with what the user sees in the Tasks card.
        val summary = bundle.summarizeText(input.articleIds, pages, input.customPrompt)
        // 2. Podcast from that summary. Renders + persists a Podcast row.
        val existingPodcastId = dao.get(jobId)?.resultRefId?.toLongOrNull()
        val podcastId = bundle.podcastFromSummary(
            articleIds = input.articleIds,
            summaryText = summary.text,
            minutes = minutes,
            customPrompt = input.customPrompt,
            existingPodcastId = existingPodcastId,
            onPodcastIdAssigned = { id -> dao.setResultRefId(jobId, id.toString()) },
        )
        dao.markCompleted(
            id = jobId,
            status = AiJobStatus.COMPLETED.name,
            resultKind = AiJobResultKind.SUMMARY_AND_PODCAST.name,
            resultRefId = podcastId.toString(),
            resultText = summary.text,
            resultModel = summary.modelId,
            nowMs = System.currentTimeMillis(),
        )
        notifier.notifyCompleted(jobId, title, "Summary + podcast ready · open Tasks")
    }

    companion object {
        const val KEY_JOB_ID = "ai_job_id"
        private const val TAG = "AiJobWorker"
    }
}
