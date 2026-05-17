package io.itsikh.finnencer.ui.screens.feed

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.core.work.SyncScheduler
import io.itsikh.finnencer.data.ai.BundleSummarizer
import io.itsikh.finnencer.data.ai.ImportanceScorer
import io.itsikh.finnencer.data.ai.ReportGenerator
import io.itsikh.finnencer.data.entity.NewsArticle
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.dao.ScoredArticleRow
import io.itsikh.finnencer.data.entity.ArticleCategory
import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.entity.ReportTier
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.data.repo.AiJobsRepository
import io.itsikh.finnencer.data.repo.FeedPreferences
import io.itsikh.finnencer.data.repo.WatchlistRepository
import io.itsikh.finnencer.logging.AppLogger
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

@HiltViewModel
class TickerFeedViewModel @Inject constructor(
    savedState: SavedStateHandle,
    watchlist: WatchlistRepository,
    private val newsDao: NewsDao,
    private val earningsDao: EarningsDao,
    private val scheduler: SyncScheduler,
    private val reportGenerator: ReportGenerator,
    private val feedPrefs: FeedPreferences,
    private val bundleSummarizer: BundleSummarizer,
    private val scorer: ImportanceScorer,
    private val aiJobs: AiJobsRepository,
    private val earningsSync: io.itsikh.finnencer.data.sync.EarningsCalendarSync,
    private val earningsNumericSync: io.itsikh.finnencer.data.sync.EarningsNumericSync,
) : ViewModel() {

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
    }

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
     * [tier]. On success, [onProducedReport] is invoked with the new report
     * id so the caller can navigate into the ReportViewer.
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
            runCatching { reportGenerator.generate(eventId, tier) }
                .onSuccess { id ->
                    _earningsBusy.value = _earningsBusy.value - eventId
                    onProducedReport(id)
                }
                .onFailure { t ->
                    AppLogger.e(TAG, "earnings ${tier.name} for event=$eventId failed", t)
                    _earningsBusy.value = _earningsBusy.value - eventId
                    _earningsError.value = _earningsError.value + (eventId to (t.message ?: "Report failed"))
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

    fun generateReport(tier: ReportTier) {
        val event = _picker.value.event ?: return
        _picker.value = _picker.value.copy(generating = true, error = null)
        viewModelScope.launch {
            runCatching { reportGenerator.generate(event.id, tier) }
                .onSuccess { id ->
                    _picker.value = _picker.value.copy(generating = false, producedReportId = id)
                }
                .onFailure { t ->
                    AppLogger.e(TAG, "report generation failed for event ${event.id}/$tier", t)
                    _picker.value = _picker.value.copy(
                        generating = false,
                        error = t.message ?: "Report failed",
                    )
                }
        }
    }

    private companion object { const val TAG = "TickerFeedVM" }
}
