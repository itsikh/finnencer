package io.itsikh.finnencer.ui.screens.earnings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.ai.BundleSummarizer
import io.itsikh.finnencer.data.ai.ReportGenerator
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.entity.EarningsReport
import io.itsikh.finnencer.data.entity.ReportTier
import io.itsikh.finnencer.data.repo.AiJobsRepository
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the per-action busy + post-action navigation that the report
 * viewer exposes. The UI subscribes to this and surfaces a spinner on the
 * matching chip, then navigates to the newly-produced report when one
 * appears.
 */
data class ReportViewerActionState(
    val regenerating: Boolean = false,
    val upgradeBusy: Boolean = false,
    val podcastQueued: Boolean = false,
    val error: String? = null,
    /** Set after a regenerate / upgrade succeeds so the UI can navigate. */
    val producedReportId: Long? = null,
)

@HiltViewModel
class ReportViewerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val earningsDao: EarningsDao,
    private val reportGenerator: ReportGenerator,
    private val aiJobs: AiJobsRepository,
) : ViewModel() {

    private val reportId: Long = savedState.get<String>("reportId")?.toLongOrNull()
        ?: error("report viewer opened without reportId")

    val report: StateFlow<EarningsReport?> = earningsDao
        .observeReport(reportId)
        .map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Whether the currently-loaded report is "stale" — i.e. its cached
     * markdown contains the LLM's standard "data unavailable" phrasing,
     * but the underlying earnings event now has actual EPS or revenue
     * numbers. Happens when a BRIEF was generated before the periodic
     * Finnhub numeric sync had filled in the actuals; opening the same
     * report months later still shows the stale "pre-print" framing
     * even though the data is now available. Surfaced as a banner so
     * the user can one-tap regenerate.
     */
    val isStale: StateFlow<Boolean> = earningsDao.observeReport(reportId)
        .map { r ->
            if (r == null) return@map false
            val eventId = r.earningsEventId ?: return@map false
            if (!containsStaleLanguage(r.contentMarkdown)) return@map false
            val event = earningsDao.getEvent(eventId) ?: return@map false
            event.actualEps != null || event.actualRevenue != null
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private fun containsStaleLanguage(markdown: String): Boolean {
        val lower = markdown.lowercase()
        return STALE_PHRASES.any { it in lower }
    }

    private val _action = MutableStateFlow(ReportViewerActionState())
    val action: StateFlow<ReportViewerActionState> = _action.asStateFlow()

    fun clearError() {
        _action.value = _action.value.copy(error = null)
    }

    fun acknowledgeProduced() {
        _action.value = _action.value.copy(producedReportId = null)
    }

    /**
     * Re-run the same tier for the same earnings event. Useful when the
     * user changed an "extra instructions" prompt (Settings → AI →
     * Prompts) and wants the report re-rendered with the new guidance.
     */
    fun regenerate() {
        val current = report.value ?: return
        val eventId = current.earningsEventId ?: run {
            _action.value = _action.value.copy(error = "Report isn't linked to an earnings event.")
            return
        }
        if (_action.value.regenerating) return
        _action.value = ReportViewerActionState(regenerating = true)
        viewModelScope.launch {
            runCatching {
                val tier = ReportTier.valueOf(current.tier)
                reportGenerator.generate(eventId, tier)
            }
                .onSuccess { newId ->
                    _action.value = ReportViewerActionState(producedReportId = newId)
                }
                .onFailure { t ->
                    AppLogger.e(TAG, "regenerate failed for report ${current.id}", t)
                    _action.value = ReportViewerActionState(error = t.message ?: "Regenerate failed")
                }
        }
    }

    /**
     * Upgrade to the next-deeper tier (BRIEF → STANDARD → DEEP). Returns
     * silently when already at DEEP.
     */
    fun upgradeTier() {
        val current = report.value ?: return
        val eventId = current.earningsEventId ?: run {
            _action.value = _action.value.copy(error = "Report isn't linked to an earnings event.")
            return
        }
        val nextTier = when (ReportTier.valueOf(current.tier)) {
            ReportTier.BRIEF -> ReportTier.STANDARD
            ReportTier.STANDARD -> ReportTier.DEEP
            ReportTier.DEEP -> return
        }
        if (_action.value.upgradeBusy) return
        _action.value = ReportViewerActionState(upgradeBusy = true)
        viewModelScope.launch {
            runCatching { reportGenerator.generate(eventId, nextTier) }
                .onSuccess { newId ->
                    _action.value = ReportViewerActionState(producedReportId = newId)
                }
                .onFailure { t ->
                    AppLogger.e(TAG, "upgrade ${nextTier.name} for report ${current.id} failed", t)
                    _action.value = ReportViewerActionState(error = t.message ?: "Upgrade failed")
                }
        }
    }

    /** Enqueue the combo earnings-podcast job for this report's event. */
    fun makePodcast(minutes: BundleSummarizer.PodcastMinutes) {
        val current = report.value ?: return
        val eventId = current.earningsEventId ?: run {
            _action.value = _action.value.copy(error = "Report isn't linked to an earnings event.")
            return
        }
        if (_action.value.podcastQueued) return
        viewModelScope.launch {
            runCatching {
                aiJobs.enqueueEarningsBriefAndPodcast(
                    tickerSymbol = current.tickerSymbol,
                    earningsEventId = eventId,
                    eventLabel = current.title.substringAfter("·", current.title).trim(),
                    minutes = minutes,
                    customPrompt = null,
                )
            }
                .onSuccess {
                    _action.value = _action.value.copy(podcastQueued = true, error = null)
                }
                .onFailure { t ->
                    AppLogger.e(TAG, "podcast enqueue failed for report ${current.id}", t)
                    _action.value = _action.value.copy(error = t.message ?: "Podcast enqueue failed")
                }
        }
    }

    fun resetPodcastQueued() {
        _action.value = _action.value.copy(podcastQueued = false)
    }

    private companion object {
        const val TAG = "ReportViewerVM"

        /**
         * Phrases the LLM falls back to when the source bundle had null
         * actuals. Lowercased; matched case-insensitively against the
         * report body. Keep tight enough not to false-positive on a
         * normal report (e.g. avoid bare "unavailable" — common word).
         */
        private val STALE_PHRASES = listOf(
            "earnings data unavailable",
            "data unavailable at time of publication",
            "results have not yet been reported",
            "have not yet reported",
            "pre-report positioning",
            "pre-print setup",
            "this brief is a pre-print",
            "results have not yet been released",
            "not yet been reported",
        )
    }
}
