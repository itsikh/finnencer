package io.itsikh.finnencer.ui.screens.feed

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.core.work.SyncScheduler
import io.itsikh.finnencer.data.ai.BundleSummarizer
import io.itsikh.finnencer.data.ai.ImportanceScorer
import io.itsikh.finnencer.data.ai.MoveExplainer
import io.itsikh.finnencer.data.entity.MoveExplanation
import io.itsikh.finnencer.data.entity.NewsArticle
import io.itsikh.finnencer.data.dao.AiJobDao
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.dao.ScoredArticleRow
import io.itsikh.finnencer.data.entity.AiJobStatus
import io.itsikh.finnencer.data.entity.ArticleCategory
import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.entity.ReportTier
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.data.repo.AiJobsRepository
import io.itsikh.finnencer.data.repo.FeedPreferences
import io.itsikh.finnencer.data.repo.WatchlistRepository
import io.itsikh.finnencer.logging.AppLogger
import io.itsikh.finnencer.logging.DebugSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedFilters(
    val minScore: Int = 0,
    val category: ArticleCategory? = null,
)

data class TickerFeedState(
    val ticker: Ticker? = null,
    val filters: FeedFilters = FeedFilters(),
)

/** Bottom-sheet state for picking a report tier for one earnings event. */
data class FeedTierPickerState(
    val event: EarningsEvent? = null,
    val generating: Boolean = false,
    val error: String? = null,
    val producedReportId: Long? = null,
)

/** Bottom-sheet state for the batch (multi-select) summarize-or-podcast flow. */
data class BatchActionState(
    val open: Boolean = false,
    val working: Boolean = false,
    val producedText: String? = null,
    val producedPodcastId: Long? = null,
    val error: String? = null,
)

/** State for the per-article action sheet (score override + re-score-with-note). */
data class ArticleActionState(
    val article: NewsArticle? = null,
    val aiScore: Int? = null,
    val currentOverride: Int? = null,
    val rescoring: Boolean = false,
    val error: String? = null,
)

/** "Why is it moving?" card state on the ticker feed. */
sealed class MoveUiState {
    object Idle : MoveUiState()
    object Loading : MoveUiState()
    data class Loaded(val row: MoveExplanation) : MoveUiState()
    data class NoNews(val pctChange: Double) : MoveUiState()
    data class Error(val message: String) : MoveUiState()
}

@HiltViewModel
class TickerFeedViewModel @Inject constructor(
    savedState: SavedStateHandle,
    watchlist: WatchlistRepository,
    private val newsDao: NewsDao,
    private val earningsDao: EarningsDao,
    private val aiJobDao: AiJobDao,
    private val scheduler: SyncScheduler,
    private val feedPrefs: FeedPreferences,
    private val bundleSummarizer: BundleSummarizer,
    private val scorer: ImportanceScorer,
    private val moveExplainer: MoveExplainer,
    private val aiJobs: AiJobsRepository,
    private val earningsSync: io.itsikh.finnencer.data.sync.EarningsCalendarSync,
    private val earningsNumericSync: io.itsikh.finnencer.data.sync.EarningsNumericSync,
    private val xbrl: io.itsikh.finnencer.data.providers.EdgarXbrlExtractor,
    private val tickerDao: io.itsikh.finnencer.data.dao.TickerDao,
    private val cikLookup: io.itsikh.finnencer.data.providers.EdgarCikLookup,
    debugSettings: DebugSettings,
) : ViewModel() {

    val showDiagnoseButtons: StateFlow<Boolean> = debugSettings.showDiagnoseButtons
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val symbol: String = savedState.get<String>("symbol")?.uppercase()
        ?: error("ticker feed opened without symbol")

    private val _filters = MutableStateFlow(FeedFilters())
    val state: StateFlow<TickerFeedState> = combine(
        watchlist.observe(symbol),
        _filters,
    ) { t, f -> TickerFeedState(ticker = t, filters = f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TickerFeedState())

    val rows: StateFlow<List<ScoredArticleRow>> = combine(
        newsDao.observeTickerFeed(symbol, limit = 500),
        _filters,
        feedPrefs.minScoreFloor,
        watchlist.observe(symbol),
    ) { list, filters, globalFloor, ticker ->
        // Per-ticker override takes precedence over the global floor.
        val effectiveFloor = (ticker?.notificationThreshold ?: globalFloor).coerceAtLeast(filters.minScore)
        list.asSequence()
            // Unscored articles (score == null) bubble to the bottom but
            // aren't blocked by the floor — they show as "?" so the user
            // sees scoring is still in flight.
            .filter { row ->
                val s = row.score
                s == null || s >= effectiveFloor
            }
            .filter { filters.category == null || it.category == filters.category.name }
            // Rank by score descending; null scores last. Within a score,
            // most-recent-first.
            .sortedWith(
                compareByDescending<ScoredArticleRow> { it.score ?: -1 }
                    .thenByDescending { it.published_at_millis }
            )
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Last two PAST earnings for this ticker, most recent first. */
    val pastEarnings: StateFlow<List<EarningsEvent>> = earningsDao
        .observePastForTicker(symbol, System.currentTimeMillis(), limit = 2)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All earnings reports for this ticker, latest first (keyed by event id in the UI layer). */
    val earningsReports: StateFlow<List<io.itsikh.finnencer.data.entity.EarningsReport>> = earningsDao
        .observeReportsForTicker(symbol)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-event in-flight state for the Highlights / Deep dive buttons. */
    private val _earningsBusy = MutableStateFlow<Map<Long, ReportTier>>(emptyMap())
    val earningsBusy: StateFlow<Map<Long, ReportTier>> = _earningsBusy.asStateFlow()

    /** Per-event most recent error to surface inline. */
    private val _earningsError = MutableStateFlow<Map<Long, String>>(emptyMap())
    val earningsError: StateFlow<Map<Long, String>> = _earningsError.asStateFlow()
    fun clearEarningsError(eventId: Long) {
        _earningsError.value = _earningsError.value - eventId
    }

    /** Delete a single cached AI report (used by long-press on a report
     *  tag in the per-ticker earnings card — #26). The underlying
     *  EarningsEvent row stays untouched. */
    fun deleteReport(reportId: Long) {
        viewModelScope.launch { earningsDao.deleteReport(reportId) }
    }

    private val _earningsSyncing = MutableStateFlow(false)
    val earningsSyncing: StateFlow<Boolean> = _earningsSyncing.asStateFlow()

    /** Last error from a user-triggered earnings sync. */
    private val _earningsSyncError = MutableStateFlow<String?>(null)
    val earningsSyncError: StateFlow<String?> = _earningsSyncError.asStateFlow()

    init {
        // Kick off a one-shot earnings sync the first time the screen
        // opens, in case the periodic SyncWorker hasn't run yet — this is
        // what makes the new earnings section appear without making the
        // user wait 15 minutes after adding a new ticker.
        viewModelScope.launch {
            val currentlyEmpty = earningsDao.observePastForTicker(symbol, System.currentTimeMillis(), limit = 1)
                .first()
                .isEmpty()
            if (currentlyEmpty) refreshEarningsNow()
        }
        // Surface any cached "Why is it moving?" explanation for today
        // without burning a Haiku call — the user explicitly taps to
        // generate a fresh one.
        viewModelScope.launch {
            moveExplainer.cached(symbol)?.let { _move.value = MoveUiState.Loaded(it) }
        }
    }

    private val _move = MutableStateFlow<MoveUiState>(MoveUiState.Idle)
    val move: StateFlow<MoveUiState> = _move.asStateFlow()

    fun explainMove(force: Boolean = false) {
        if (_move.value is MoveUiState.Loading) return
        _move.value = MoveUiState.Loading
        viewModelScope.launch {
            runCatching { moveExplainer.explain(symbol, force = force) }
                .onSuccess { outcome ->
                    _move.value = when (outcome) {
                        is MoveExplainer.Outcome.Ready -> MoveUiState.Loaded(outcome.row)
                        is MoveExplainer.Outcome.NoNews -> MoveUiState.NoNews(outcome.pctChange)
                    }
                }
                .onFailure { t ->
                    AppLogger.e(TAG, "move explainer failed for $symbol", t)
                    _move.value = MoveUiState.Error(t.message ?: t.javaClass.simpleName)
                }
        }
    }

    // ── XBRL diagnostic (issue #25 verification path) ───────────────────
    data class XbrlDiagnostic(
        val loading: Boolean = false,
        val ticker: String? = null,
        val cik: String? = null,
        val error: String? = null,
        val quarters: List<io.itsikh.finnencer.data.providers.XbrlQuarter> = emptyList(),
    )

    private val _xbrlDiag = MutableStateFlow(XbrlDiagnostic())
    val xbrlDiag: StateFlow<XbrlDiagnostic> = _xbrlDiag.asStateFlow()

    fun runXbrlDiagnose() {
        viewModelScope.launch {
            _xbrlDiag.value = XbrlDiagnostic(loading = true, ticker = symbol)
            val ticker = tickerDao.get(symbol)
            // On-demand CIK resolution — same as ReportGenerator does
            // before its XBRL fetch. If the Ticker row never got a CIK
            // (EDGAR sync failed during the User-Agent-misconfigured
            // era and the failure was cached), this is what unblocks
            // the XBRL flow.
            var cik = ticker?.cik
            if (cik == null) {
                cik = runCatching { cikLookup.resolve(symbol) }.getOrNull()
                if (cik != null && ticker != null) {
                    tickerDao.update(ticker.copy(cik = cik))
                    AppLogger.i(TAG, "resolved CIK $cik for $symbol on-demand (diagnose)")
                }
            }
            if (cik == null) {
                _xbrlDiag.value = XbrlDiagnostic(
                    ticker = symbol,
                    error = "Couldn't resolve a CIK for $symbol. The SEC EDGAR ticker→CIK table didn't include it, or the EDGAR User-Agent (Settings → API keys) is rejecting requests. Bug-report logs will have the exact reason.",
                )
                return@launch
            }
            runCatching { xbrl.recentQuarters(cik, limit = 4) }
                .onSuccess { qs ->
                    _xbrlDiag.value = XbrlDiagnostic(ticker = symbol, cik = cik, quarters = qs)
                }
                .onFailure { t ->
                    AppLogger.e(TAG, "XBRL diagnose failed", t)
                    _xbrlDiag.value = XbrlDiagnostic(
                        ticker = symbol,
                        cik = cik,
                        error = t.message ?: t.javaClass.simpleName,
                    )
                }
        }
    }

    fun closeXbrlDiag() { _xbrlDiag.value = XbrlDiagnostic() }

    fun refreshEarningsNow() {
        if (_earningsSyncing.value) return
        _earningsSyncing.value = true
        _earningsSyncError.value = null
        viewModelScope.launch {
            // EDGAR seeds the rows (dates + status); Finnhub fills the
            // numeric fields. Both must run for past-earnings cards to
            // show beat/miss percentages rather than "—".
            runCatching { earningsSync.runOnce() }
                .onFailure { t ->
                    AppLogger.w(TAG, "ad-hoc EDGAR earnings sync failed: ${t.message}")
                    _earningsSyncError.value = t.message ?: t.javaClass.simpleName
                }
            runCatching { earningsNumericSync.runOnce() }
                .onFailure { t ->
                    AppLogger.w(TAG, "ad-hoc Finnhub numeric sync failed: ${t.message}")
                    // Don't overwrite an existing EDGAR error; partial
                    // success (rows but no numbers) is more useful than
                    // total failure.
                    if (_earningsSyncError.value == null) {
                        _earningsSyncError.value = t.message ?: t.javaClass.simpleName
                    }
                }
            _earningsSyncing.value = false
        }
    }

    /**
     * Kick off (or re-open) an earnings report for [eventId] at the given
     * [tier]. The work runs as a persistent AI job (WorkManager + Room) so
     * navigating away or backgrounding the app no longer cancels it; the
     * user can track progress in the Tasks tab and the same callback fires
     * when the report lands as long as this ViewModel is still alive.
     */
    fun requestEarningsReport(eventId: Long, tier: ReportTier, onProducedReport: (Long) -> Unit) {
        // If a report at this tier already exists for the event, just open it.
        val existing = earningsReports.value
            .firstOrNull { it.earningsEventId == eventId && it.tier == tier.name }
        if (existing != null) {
            onProducedReport(existing.id)
            return
        }
        if (_earningsBusy.value[eventId] != null) return
        _earningsBusy.value = _earningsBusy.value + (eventId to tier)
        viewModelScope.launch {
            val event = earningsDao.getEvent(eventId)
            val tickerForJob = event?.tickerSymbol ?: symbol
            val label = if (event != null) "Q${event.fiscalQuarter} ${event.fiscalYear}" else tier.name
            val jobId = aiJobs.enqueueEarningsReport(
                tickerSymbol = tickerForJob,
                earningsEventId = eventId,
                eventLabel = label,
                tier = tier,
            )
            watchEarningsJob(eventId, tier, jobId, onProducedReport)
        }
    }

    private val watcherByEvent = mutableMapOf<Long, Job>()

    private fun watchEarningsJob(
        eventId: Long,
        tier: ReportTier,
        jobId: String,
        onProducedReport: (Long) -> Unit,
    ) {
        watcherByEvent[eventId]?.cancel()
        watcherByEvent[eventId] = viewModelScope.launch {
            aiJobDao.observe(jobId).collect { job ->
                if (job == null) return@collect
                when (AiJobStatus.valueOf(job.status)) {
                    AiJobStatus.COMPLETED -> {
                        _earningsBusy.value = _earningsBusy.value - eventId
                        job.resultRefId?.toLongOrNull()?.let(onProducedReport)
                        watcherByEvent.remove(eventId)
                    }
                    AiJobStatus.FAILED, AiJobStatus.CANCELED -> {
                        AppLogger.e(TAG, "earnings ${tier.name} for event=$eventId failed: ${job.errorMessage}")
                        _earningsBusy.value = _earningsBusy.value - eventId
                        _earningsError.value =
                            _earningsError.value + (eventId to (job.errorMessage ?: "Report failed"))
                        watcherByEvent.remove(eventId)
                    }
                    AiJobStatus.QUEUED, AiJobStatus.RUNNING, AiJobStatus.PENDING_REVIEW -> Unit
                }
            }
        }
    }

    /**
     * Enqueue the combo earnings job: BRIEF (auto-generated if missing) +
     * a podcast scripted from it. Tasks badge shows progress.
     */
    fun requestEarningsPodcast(
        eventId: Long,
        eventLabel: String,
        minutes: BundleSummarizer.PodcastMinutes,
        customPrompt: String?,
    ) {
        viewModelScope.launch {
            aiJobs.enqueueEarningsBriefAndPodcast(
                tickerSymbol = symbol,
                earningsEventId = eventId,
                eventLabel = eventLabel,
                minutes = minutes,
                customPrompt = customPrompt,
            )
        }
    }

    private val _picker = MutableStateFlow(FeedTierPickerState())
    val picker: StateFlow<FeedTierPickerState> = _picker.asStateFlow()

    // ── Multi-select + batch summary/podcast ───────────────────────────
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    /** Bottom-sheet visible state when the user taps "Summarize N". */
    private val _batchSheet = MutableStateFlow(BatchActionState())
    val batchSheet: StateFlow<BatchActionState> = _batchSheet.asStateFlow()

    fun toggleSelect(articleId: String) {
        _selection.value = _selection.value.toMutableSet().also {
            if (!it.add(articleId)) it.remove(articleId)
        }
    }
    fun clearSelection() { _selection.value = emptySet() }
    fun openBatchSheet() {
        if (_selection.value.isEmpty()) return
        _batchSheet.value = BatchActionState(open = true)
    }
    fun closeBatchSheet() { _batchSheet.value = BatchActionState() }

    fun summarizeBatch(pages: BundleSummarizer.Pages, customPrompt: String?) {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            aiJobs.enqueueBatchSummary(
                tickerSymbol = symbol,
                articleIds = ids,
                pages = pages,
                customPrompt = customPrompt,
            )
            // Close the sheet immediately — the user follows progress in
            // the Tasks tab (badge on the watchlist top bar). This is the
            // whole point of moving these calls to the background.
            _batchSheet.value = BatchActionState()
            _selection.value = emptySet()
        }
    }

    /**
     * Combo: enqueue ONE job that generates the summary first and then a
     * podcast derived from that summary's text. The podcast row lands in
     * the Podcasts tab; the Tasks card surfaces both the inline summary
     * text and an "Open podcast" affordance via the produced job row.
     */
    fun summarizeBatchWithPodcast(
        pages: BundleSummarizer.Pages,
        minutes: BundleSummarizer.PodcastMinutes,
        customPrompt: String?,
    ) {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            aiJobs.enqueueSummaryAndPodcast(
                tickerSymbol = symbol,
                articleIds = ids,
                pages = pages,
                minutes = minutes,
                customPrompt = customPrompt,
            )
            _batchSheet.value = BatchActionState()
            _selection.value = emptySet()
        }
    }

    // ── Per-article action sheet ────────────────────────────────────────
    private val _action = MutableStateFlow(ArticleActionState())
    val action: StateFlow<ArticleActionState> = _action.asStateFlow()

    fun openArticleAction(articleId: String) {
        viewModelScope.launch {
            val article = newsDao.getArticle(articleId) ?: return@launch
            val scores = newsDao.scoresFor(articleId)
            // Prefer the score row for THIS ticker; fall back to the first.
            val score = scores.firstOrNull { it.tickerSymbol == symbol } ?: scores.firstOrNull()
            _action.value = ArticleActionState(
                article = article,
                aiScore = score?.score,
                currentOverride = score?.userOverride,
            )
        }
    }

    fun closeArticleAction() { _action.value = ArticleActionState() }

    fun applyOverride(override: Int) {
        val a = _action.value.article ?: return
        viewModelScope.launch {
            val existing = newsDao.scoresFor(a.id).firstOrNull { it.tickerSymbol == symbol }
            if (existing == null) {
                // No AI row yet — insert a manual one with the same value
                // for both fields so existing queries don't go null.
                newsDao.insertScores(listOf(
                    io.itsikh.finnencer.data.entity.ArticleScore(
                        articleId = a.id,
                        tickerSymbol = symbol,
                        score = override,
                        category = io.itsikh.finnencer.data.entity.ArticleCategory.OTHER.name,
                        reason = "User-set score (no AI score yet)",
                        model = "user",
                        scoredAtMillis = System.currentTimeMillis(),
                        userOverride = override,
                    )
                ))
            } else {
                newsDao.setUserOverride(a.id, symbol, override)
            }
            _action.value = _action.value.copy(currentOverride = override)
        }
    }

    fun clearOverride() {
        val a = _action.value.article ?: return
        viewModelScope.launch {
            newsDao.setUserOverride(a.id, symbol, null)
            _action.value = _action.value.copy(currentOverride = null)
        }
    }

    fun rescoreWithNote(note: String) {
        val a = _action.value.article ?: return
        _action.value = _action.value.copy(rescoring = true, error = null)
        viewModelScope.launch {
            runCatching { scorer.rescoreSingle(a, note.ifBlank { null }) }
                .onSuccess { newRows ->
                    val newScore = newRows.firstOrNull { it.tickerSymbol == symbol }?.score
                        ?: newRows.firstOrNull()?.score
                    _action.value = _action.value.copy(rescoring = false, aiScore = newScore)
                }
                .onFailure { t ->
                    AppLogger.e(TAG, "rescore failed", t)
                    _action.value = _action.value.copy(
                        rescoring = false,
                        error = t.message ?: "Re-score failed",
                    )
                }
        }
    }

    fun summarizeBatchToPodcast(minutes: BundleSummarizer.PodcastMinutes, customPrompt: String?) {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            aiJobs.enqueueBatchPodcast(
                tickerSymbol = symbol,
                articleIds = ids,
                minutes = minutes,
                customPrompt = customPrompt,
            )
            _batchSheet.value = BatchActionState()
            _selection.value = emptySet()
        }
    }

    /** True while a sync worker is RUNNING — drives the top-of-screen
     *  progress bar so the user knows when the refresh button has effect. */
    val syncRunning: StateFlow<Boolean> = scheduler.isSyncRunning
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setMinScore(min: Int) { _filters.value = _filters.value.copy(minScore = min.coerceIn(0, 10)) }
    fun setCategory(c: ArticleCategory?) { _filters.value = _filters.value.copy(category = c) }
    fun refresh() = scheduler.runOnceNow()

    fun openPicker(event: EarningsEvent) { _picker.value = FeedTierPickerState(event = event) }
    fun closePicker() { _picker.value = FeedTierPickerState() }

    private var pickerJobWatcher: Job? = null

    fun generateReport(tier: ReportTier) {
        val event = _picker.value.event ?: return
        _picker.value = _picker.value.copy(generating = true, error = null)
        viewModelScope.launch {
            val jobId = aiJobs.enqueueEarningsReport(
                tickerSymbol = event.tickerSymbol,
                earningsEventId = event.id,
                eventLabel = "Q${event.fiscalQuarter} ${event.fiscalYear}",
                tier = tier,
            )
            pickerJobWatcher?.cancel()
            pickerJobWatcher = launch {
                aiJobDao.observe(jobId).collect { job ->
                    if (job == null) return@collect
                    when (AiJobStatus.valueOf(job.status)) {
                        AiJobStatus.COMPLETED -> {
                            _picker.value = _picker.value.copy(
                                generating = false,
                                producedReportId = job.resultRefId?.toLongOrNull(),
                            )
                        }
                        AiJobStatus.FAILED, AiJobStatus.CANCELED -> {
                            AppLogger.e(TAG, "report generation failed for event ${event.id}/$tier: ${job.errorMessage}")
                            _picker.value = _picker.value.copy(
                                generating = false,
                                error = job.errorMessage ?: "Report failed",
                            )
                        }
                        AiJobStatus.QUEUED, AiJobStatus.RUNNING, AiJobStatus.PENDING_REVIEW -> Unit
                    }
                }
            }
        }
    }

    private companion object { const val TAG = "TickerFeedVM" }
}
