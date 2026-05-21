package io.itsikh.finnencer.core.work

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.itsikh.finnencer.MainActivity
import io.itsikh.finnencer.core.net.EndpointReachability
import io.itsikh.finnencer.core.notifications.AiJobNotifier
import io.itsikh.finnencer.core.notifications.NotificationChannels
import io.itsikh.finnencer.data.ai.BundleSummarizer
import io.itsikh.finnencer.data.ai.ReportGenerator
import io.itsikh.finnencer.data.dao.AiJobDao
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.entity.AiJobResultKind
import io.itsikh.finnencer.data.entity.AiJobStage
import io.itsikh.finnencer.data.entity.AiJobStatus
import io.itsikh.finnencer.data.entity.AiJobType
import io.itsikh.finnencer.data.entity.ReportTier
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Single CoroutineWorker that handles every queued [AiJobType]. Reads the
 * row from Room, dispatches to the matching domain component, persists the
 * produced artifact, and writes the final status back. The user sees
 * progress / completion via the Tasks screen (Room flow) and a system
 * notification (this worker calls [AiJobNotifier] on success/failure).
 *
 * ## Pre-flight network gate (#43)
 *
 * Before any LLM call, the worker probes the hostnames the pipeline needs
 * (Anthropic + optionally Gemini) via DNS. If any are unreachable — even
 * when Android reports the device as "connected" — the worker enters a
 * polling loop, recheck every 60s. The Podcast row (if any) is flipped
 * to `WAITING_FOR_NETWORK` and a foreground-service notification keeps
 * the user informed and the worker alive past the 10-minute single-run
 * cap.
 *
 * ## Stage reporting
 *
 * Every phase calls [JobProgressReporter.update] (via the [JobIdContext]
 * coroutine-context element) so the Tasks list and Task Detail screen
 * can show "what's happening right now" in real time.
 */
@HiltWorker
class AiJobWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val dao: AiJobDao,
    private val bundle: BundleSummarizer,
    private val notifier: AiJobNotifier,
    private val gson: Gson,
    private val reportGenerator: ReportGenerator,
    private val earningsDao: EarningsDao,
    private val concurrencyGate: JobConcurrencyGate,
    private val endpointReachability: EndpointReachability,
    private val progressReporter: JobProgressReporter,
    private val geminiTts: io.itsikh.finnencer.data.ai.GeminiTts,
    private val podcastPrefs: io.itsikh.finnencer.data.repo.PodcastPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val job = dao.get(jobId) ?: return Result.failure()
        val type = AiJobType.valueOf(job.type)

        return withContext(JobIdContext(jobId)) {
            // Promote to foreground IMMEDIATELY. Without this the worker
            // is killed after ~10 minutes and the "wait for the user to
            // get back to wifi" loop can't outlive that. setForeground
            // also surfaces a notification so the user can see + cancel
            // the job from the shade.
            runCatching {
                setForeground(buildForegroundInfo(jobId, job.title, "Starting…"))
            }.onFailure {
                // Some OEMs reject setForeground for short workers — log
                // and continue. The 10-min cap will apply but normal
                // jobs complete in well under that.
                AppLogger.w(TAG, "setForeground rejected for $jobId: ${it.message}")
            }

            runCatching {
                // Phase 1: pre-flight + WAITING_FOR_NETWORK wait loop.
                // Run BEFORE acquiring the concurrency gate so a waiting
                // job doesn't hold a permit that other jobs could use.
                waitForReachableEndpoints(jobId, job.title, type)

                if (isStopped) return@runCatching Result.failure()

                // Surface gate-wait state to the UI. If another podcast
                // is currently holding the gate (concurrency limit = 1
                // by default), the gate.withPermit suspends here for
                // potentially many minutes — and the previous stage
                // detail ("Pre-flight checks passed") would otherwise
                // make it look like nothing was happening. Setting a
                // queued-behind detail makes the wait visible (#54).
                progressReporter.update(
                    AiJobStage.CONNECTIVITY_CHECK,
                    100,
                    "Ready — waiting for the ${gateKind(type).name.lowercase()} pipeline to be free",
                )

                // Phase 2: under the gate, run the actual pipeline.
                concurrencyGate.withPermit(gateKind(type), label = "${job.type} ${job.id}") {
                    dao.markRunning(job.id, AiJobStatus.RUNNING.name, System.currentTimeMillis())
                    AppLogger.i(TAG, "running ${job.type} ${job.id} (${job.title})")
                    progressReporter.update(AiJobStage.GENERATING_SCRIPT, 0, "Starting work")
                    runCatching {
                        when (type) {
                            AiJobType.SUMMARY_BATCH -> runSummary(job.id, job.tickerSymbol, job.inputJson, job.title)
                            AiJobType.PODCAST_BATCH -> runPodcast(job.id, job.inputJson)
                            AiJobType.SUMMARY_AND_PODCAST_BATCH -> runSummaryAndPodcast(job.id, job.inputJson, job.title)
                            AiJobType.EARNINGS_BRIEF_AND_PODCAST -> runEarningsBriefAndPodcast(job.id, job.inputJson, job.title)
                            AiJobType.REPORT_EARNINGS -> runEarningsReport(job.id, job.inputJson, job.title)
                        }
                    }.fold(
                        onSuccess = {
                            progressReporter.update(AiJobStage.DONE, 100, "Complete")
                            Result.success()
                        },
                        onFailure = { t ->
                            handleFailure(job.id, job.title, t)
                            Result.failure()
                        }
                    )
                }
            }.getOrElse { t ->
                handleFailure(job.id, job.title, t)
                Result.failure()
            }
        }
    }

    private suspend fun handleFailure(jobId: String, title: String, t: Throwable) {
        // Validator-FAIL is a "needs human review" exit, not a real
        // failure. The Podcast row was already flipped to PENDING_REVIEW
        // by the validator phase; here we mirror that on the AiJob so
        // the Tasks screen shows the amber chip + Proceed/Cancel
        // buttons. The user later resumes via AiJobsRepository.
        if (t is io.itsikh.finnencer.data.ai.BundleSummarizer.ValidationReviewRequiredException) {
            AppLogger.i(TAG, "ai job $jobId: validator flagged podcast ${t.podcastId} for review")
            progressReporter.updateExplicit(jobId, AiJobStage.VALIDATING_SCRIPT, 100, "Validator flagged — open this task to review and choose")
            dao.markFailed(jobId, AiJobStatus.PENDING_REVIEW.name, "Script flagged for human review", System.currentTimeMillis())
            return
        }
        val friendly = io.itsikh.finnencer.data.ai.FriendlyError.describe(t)
        AppLogger.e(TAG, "ai job $jobId failed: $friendly", t)
        progressReporter.updateExplicit(jobId, AiJobStage.FAILED, 0, friendly)
        dao.markFailed(jobId, AiJobStatus.FAILED.name, friendly, System.currentTimeMillis())
        notifier.notifyFailed(jobId, title, friendly)
    }

    /**
     * Two-phase pre-flight inside the CONNECTIVITY_CHECK stage:
     *
     *   1. DNS probe — verifies the user's network can resolve
     *      api.anthropic.com and (for podcast jobs)
     *      generativelanguage.googleapis.com. Bounded by
     *      EndpointReachability's hard-timeout daemon-thread fix
     *      from #52. We proceed even on failure because the actual
     *      API calls have their own retry/error paths.
     *
     *   2. TTS smoke probe — podcast jobs only. Sends one tiny
     *      multi-speaker request against the user's CURRENTLY-SELECTED
     *      Gemini TTS model with a model-tuned timeout (Pro: 60s,
     *      Flash: 30s). A failing smoke probe throws and the worker's
     *      outer runCatching turns the throw into a clean Failed job
     *      with a clear "switch models in Settings" message. Without
     *      this the user saw the connectivity stage say "All endpoints
     *      reachable" followed by 10+ minutes of silent TTS retries on
     *      chunk 0 (#54).
     */
    private suspend fun waitForReachableEndpoints(jobId: String, title: String, type: AiJobType) {
        val needed = endpointsFor(type)
        progressReporter.update(
            AiJobStage.CONNECTIVITY_CHECK,
            0,
            "Checking ${needed.joinToString(", ") { it.host }}",
        )

        val report = endpointReachability.probe(needed)
        if (report.allReachable) {
            AppLogger.i(TAG, "ai job $jobId: all ${needed.size} endpoint(s) reachable")
        } else {
            val unreachable = report.unreachable.joinToString(", ") { it.host }
            AppLogger.w(TAG, "ai job $jobId: DNS couldn't resolve $unreachable — proceeding optimistically; the API call is the source of truth")
        }

        if (type.producesPodcast()) {
            val model = podcastPrefs.ttsModel.first()
            val timeoutMs = ttsSmokeTimeoutMsFor(model)
            progressReporter.update(
                AiJobStage.CONNECTIVITY_CHECK,
                50,
                "Verifying ${model.displayName} is responsive (timeout ${timeoutMs / 1000}s)…",
            )
            val err = geminiTts.smokeTest(model = model.modelId, timeoutMs = timeoutMs)
            if (err != null) {
                AppLogger.w(TAG, "ai job $jobId: TTS smoke test for ${model.displayName} failed: ${err.message}")
                throw io.itsikh.finnencer.data.ai.TtsSmokeTestFailedException(
                    "${model.displayName} isn't responding within ${timeoutMs / 1000}s. " +
                        "Open Settings → Podcasts and switch to a different TTS model, or try again later. " +
                        "Underlying error: ${err.message}",
                    cause = err,
                )
            }
            AppLogger.i(TAG, "ai job $jobId: TTS smoke test for ${model.displayName} passed")
        }
        progressReporter.update(AiJobStage.CONNECTIVITY_CHECK, 100, "Pre-flight checks passed")
    }

    /**
     * Per-model smoke-test timeout. Pro models legitimately take longer
     * than Flash even on a trivial "Host: Ready." script. Tuned on the
     * conservative side so a healthy key never trips the timeout.
     */
    private fun ttsSmokeTimeoutMsFor(model: io.itsikh.finnencer.data.repo.TtsModel): Long = when (model) {
        io.itsikh.finnencer.data.repo.TtsModel.GEMINI_2_5_FLASH -> 30_000L
        io.itsikh.finnencer.data.repo.TtsModel.GEMINI_3_1_FLASH -> 30_000L
        io.itsikh.finnencer.data.repo.TtsModel.GEMINI_2_5_PRO -> 60_000L
    }

    private fun AiJobType.producesPodcast(): Boolean = when (this) {
        AiJobType.PODCAST_BATCH,
        AiJobType.SUMMARY_AND_PODCAST_BATCH,
        AiJobType.EARNINGS_BRIEF_AND_PODCAST -> true
        AiJobType.SUMMARY_BATCH,
        AiJobType.REPORT_EARNINGS -> false
    }

    private fun endpointsFor(type: AiJobType): List<EndpointReachability.Endpoint> = when (type) {
        // Summary and report jobs only use Anthropic.
        AiJobType.SUMMARY_BATCH,
        AiJobType.REPORT_EARNINGS -> listOf(EndpointReachability.Endpoint.ANTHROPIC)
        // Anything that renders audio also needs Gemini.
        AiJobType.PODCAST_BATCH,
        AiJobType.SUMMARY_AND_PODCAST_BATCH,
        AiJobType.EARNINGS_BRIEF_AND_PODCAST -> listOf(
            EndpointReachability.Endpoint.ANTHROPIC,
            EndpointReachability.Endpoint.GEMINI_AI_STUDIO,
        )
    }

    private fun gateKind(type: AiJobType): JobConcurrencyGate.Kind = when (type) {
        AiJobType.PODCAST_BATCH,
        AiJobType.SUMMARY_AND_PODCAST_BATCH,
        AiJobType.EARNINGS_BRIEF_AND_PODCAST -> JobConcurrencyGate.Kind.PODCAST
        AiJobType.SUMMARY_BATCH,
        AiJobType.REPORT_EARNINGS -> JobConcurrencyGate.Kind.SUMMARY
    }

    private fun buildForegroundInfo(jobId: String, title: String, statusText: String): ForegroundInfo {
        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            appContext,
            jobId.hashCode(),
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(appContext, NotificationChannels.AI_JOBS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPi)
            .build()
        val notifId = jobId.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notification)
        }
    }

    // ─── pipeline dispatch ────────────────────────────────────────────────

    private suspend fun runSummary(jobId: String, tickerSymbol: String?, json: String, title: String) {
        progressReporter.update(AiJobStage.GENERATING_SUMMARY, 0, "Calling Claude")
        val input = gson.fromJson(json, SummaryInput::class.java)
        val pages = BundleSummarizer.Pages.entries.firstOrNull { it.target == input.pagesTarget }
            ?: BundleSummarizer.Pages.TWO
        val result = bundle.summarizeText(input.articleIds, pages, input.customPrompt)
        progressReporter.update(AiJobStage.FINALIZING, 95, "Saving summary")
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
            onPodcastIdAssigned = { id -> dao.setResultRefId(jobId, id.toString()) },
        )
        progressReporter.update(AiJobStage.FINALIZING, 95, "Saving podcast")
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
        progressReporter.update(AiJobStage.GENERATING_REPORT, 0, "Resolving / generating earnings report")
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
        progressReporter.update(AiJobStage.FINALIZING, 95, "Saving report")
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
        progressReporter.update(AiJobStage.GENERATING_REPORT, 0, "Resolving BRIEF report")
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
        progressReporter.update(AiJobStage.FINALIZING, 95, "Saving podcast")
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
        progressReporter.update(AiJobStage.GENERATING_SUMMARY, 0, "Calling Claude")
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
        progressReporter.update(AiJobStage.FINALIZING, 95, "Saving artifacts")
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
