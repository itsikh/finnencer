package io.itsikh.finnencer.ui.screens.feed

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.core.work.SyncScheduler
import io.itsikh.finnencer.data.ai.ReportGenerator
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.dao.ScoredArticleRow
import io.itsikh.finnencer.data.entity.ArticleCategory
import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.entity.ReportTier
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.data.repo.WatchlistRepository
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

@HiltViewModel
class TickerFeedViewModel @Inject constructor(
    savedState: SavedStateHandle,
    watchlist: WatchlistRepository,
    private val newsDao: NewsDao,
    private val earningsDao: EarningsDao,
    private val scheduler: SyncScheduler,
    private val reportGenerator: ReportGenerator,
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
        newsDao.observeTickerFeed(symbol, limit = 300),
        _filters,
    ) { list, filters ->
        list.asSequence()
            .filter { (it.score ?: 0) >= filters.minScore }
            .filter { filters.category == null || it.category == filters.category.name }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Last two PAST earnings for this ticker, most recent first. */
    val pastEarnings: StateFlow<List<EarningsEvent>> = earningsDao
        .observePastForTicker(symbol, System.currentTimeMillis(), limit = 2)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _picker = MutableStateFlow(FeedTierPickerState())
    val picker: StateFlow<FeedTierPickerState> = _picker.asStateFlow()

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
