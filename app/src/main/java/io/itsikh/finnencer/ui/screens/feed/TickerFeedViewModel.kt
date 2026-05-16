package io.itsikh.finnencer.ui.screens.feed

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.core.work.SyncScheduler
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.dao.ScoredArticleRow
import io.itsikh.finnencer.data.entity.ArticleCategory
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.data.repo.WatchlistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class FeedFilters(
    val minScore: Int = 0,
    val category: ArticleCategory? = null,
)

data class TickerFeedState(
    val ticker: Ticker? = null,
    val filters: FeedFilters = FeedFilters(),
)

@HiltViewModel
class TickerFeedViewModel @Inject constructor(
    savedState: SavedStateHandle,
    watchlist: WatchlistRepository,
    private val newsDao: NewsDao,
    private val scheduler: SyncScheduler,
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

    fun setMinScore(min: Int) { _filters.value = _filters.value.copy(minScore = min.coerceIn(0, 10)) }
    fun setCategory(c: ArticleCategory?) { _filters.value = _filters.value.copy(category = c) }
    fun refresh() = scheduler.runOnceNow()
}
