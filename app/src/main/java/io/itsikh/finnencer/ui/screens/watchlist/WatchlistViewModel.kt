package io.itsikh.finnencer.ui.screens.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.data.entity.TickerAnalystSnapshot
import io.itsikh.finnencer.data.repo.AiJobsRepository
import io.itsikh.finnencer.data.repo.AnalystSnapshotRepository
import io.itsikh.finnencer.data.repo.QueueRepository
import io.itsikh.finnencer.data.repo.TickerSearchResult
import io.itsikh.finnencer.data.repo.WatchlistRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddSheetState(
    val open: Boolean = false,
    val query: String = "",
    val loading: Boolean = false,
    val results: List<TickerSearchResult> = emptyList(),
    val error: String? = null,
)

data class TickerSettingsSheetState(
    val ticker: Ticker? = null,
    val draftThreshold: Int = 7,
    val draftDailyCap: Int = 5,
    val draftMuted: Boolean = false,
)

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val repo: WatchlistRepository,
    aiJobs: AiJobsRepository,
    queue: QueueRepository,
    private val quotePoller: io.itsikh.finnencer.data.repo.QuotePoller,
    private val analystSnapshots: AnalystSnapshotRepository,
    private val earningsDao: EarningsDao,
) : ViewModel() {

    val tickers: StateFlow<List<Ticker>> =
        repo.observeAll().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
        )

    /**
     * Next upcoming earnings event per watched symbol. Keyed by
     * ticker.symbol so the row card can do a single map lookup. Empty
     * until the user has at least one ticker AND that ticker has an
     * earnings_events row (synced by the every-15-min EarningsCalendarSync).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val nextEarningsBySymbol: StateFlow<Map<String, EarningsEvent>> =
        tickers
            .map { it.map(Ticker::symbol) }
            .distinctUntilChanged()
            .flatMapLatest { symbols ->
                if (symbols.isEmpty()) flowOf(emptyList())
                else earningsDao.observeNextEventForSymbols(symbols, System.currentTimeMillis())
            }
            .map { events -> events.associateBy { it.tickerSymbol } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Cached analyst snapshot per watched symbol. Backs the
     * "PT vs current quote" arrow on each card. Refreshed via
     * [refreshAnalystSnapshotsIfStale] when the user opens the screen.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val analystSnapshotsBySymbol: StateFlow<Map<String, TickerAnalystSnapshot>> =
        tickers
            .map { it.map(Ticker::symbol) }
            .distinctUntilChanged()
            .flatMapLatest { symbols ->
                if (symbols.isEmpty()) emptyFlow()
                else analystSnapshots.observe(symbols)
            }
            .map { snaps -> snaps.associateBy { it.ticker } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Live price + percent-change snapshot per watched ticker. Empty
     *  until the first [startQuotePolling] tick lands. */
    val quotes: StateFlow<Map<String, io.itsikh.finnencer.data.repo.TickerQuote>> = quotePoller.latest

    /** Begin polling Yahoo for the currently-watched tickers. Called by
     *  the screen on resume and re-called whenever the watched list
     *  changes. The poller dedupes back-to-back calls with the same
     *  ticker set, so re-invoking is cheap. Also opportunistically
     *  refreshes any stale or missing analyst snapshots so the
     *  watchlist's PT arrow shows real numbers on first open. */
    fun startQuotePolling() {
        val symbols = tickers.value.map { it.symbol }
        quotePoller.start(symbols)
        refreshAnalystSnapshotsIfStale(symbols)
    }

    private var snapshotRefreshJob: Job? = null

    private fun refreshAnalystSnapshotsIfStale(symbols: List<String>) {
        if (symbols.isEmpty()) return
        // Coalesce: if a refresh is already running for this ticker
        // set, don't queue another. Resume events fire frequently
        // (every screen visit), and the repo already idempotently
        // skips tickers inside the TTL — but cancelling avoids
        // burning a few hundred ms of work when nothing's stale.
        if (snapshotRefreshJob?.isActive == true) return
        snapshotRefreshJob = viewModelScope.launch {
            runCatching { analystSnapshots.refreshStale(symbols) }
        }
    }

    /** Pause polling. Called by the screen on pause. Cached quotes
     *  remain visible until the next resume produces fresh ones. */
    fun stopQuotePolling() {
        quotePoller.stop()
    }

    /** Number of queued + running AI jobs — drives the badge on the Tasks icon. */
    val activeJobCount: StateFlow<Int> = aiJobs.observeActiveCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Open ("to do") queue size — drives the badge on the Queue icon. */
    val queueCount: StateFlow<Int> = queue.observeIncompleteCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _addSheet = MutableStateFlow(AddSheetState())
    val addSheet: StateFlow<AddSheetState> = _addSheet.asStateFlow()

    private val _settingsSheet = MutableStateFlow(TickerSettingsSheetState())
    val settingsSheet: StateFlow<TickerSettingsSheetState> = _settingsSheet.asStateFlow()

    private val _query = MutableStateFlow("")
    private var searchJob: Job? = null

    init {
        // Debounced search loop. Anytime the user pauses typing for 300ms we
        // re-issue the Finnhub /search call.
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _query
                .debounce(300)
                .distinctUntilChanged()
                .collect { runSearch(it) }
        }
    }

    // ─── add-ticker sheet ───

    fun openAddSheet() { _addSheet.value = AddSheetState(open = true) }
    fun closeAddSheet() { _addSheet.value = AddSheetState() }

    fun onSearchQueryChanged(value: String) {
        _addSheet.value = _addSheet.value.copy(query = value)
        _query.value = value
    }

    private suspend fun runSearch(q: String) {
        if (q.isBlank() || q.length < 1) {
            _addSheet.value = _addSheet.value.copy(loading = false, results = emptyList(), error = null)
            return
        }
        _addSheet.value = _addSheet.value.copy(loading = true, error = null)
        runCatching { repo.search(q) }
            .onSuccess { results ->
                _addSheet.value = _addSheet.value.copy(loading = false, results = results)
            }
            .onFailure { t ->
                _addSheet.value = _addSheet.value.copy(
                    loading = false,
                    results = emptyList(),
                    error = t.message ?: "Search failed",
                )
            }
    }

    fun addTicker(result: TickerSearchResult) {
        viewModelScope.launch {
            repo.add(symbol = result.symbol, name = result.description, exchange = "US")
            closeAddSheet()
        }
    }

    // ─── per-ticker settings sheet ───

    fun openSettings(ticker: Ticker) {
        _settingsSheet.value = TickerSettingsSheetState(
            ticker = ticker,
            draftThreshold = ticker.notificationThreshold,
            draftDailyCap = ticker.dailyNotificationCap,
            draftMuted = ticker.mutedUntilMillis != null,
        )
    }

    fun closeSettings() { _settingsSheet.value = TickerSettingsSheetState() }

    fun setDraftThreshold(value: Int) {
        _settingsSheet.value = _settingsSheet.value.copy(draftThreshold = value.coerceIn(1, 10))
    }
    fun setDraftCap(value: Int) {
        _settingsSheet.value = _settingsSheet.value.copy(draftDailyCap = value.coerceIn(1, 50))
    }
    fun setDraftMuted(muted: Boolean) {
        _settingsSheet.value = _settingsSheet.value.copy(draftMuted = muted)
    }

    fun saveSettings() {
        val s = _settingsSheet.value
        val current = s.ticker ?: return
        viewModelScope.launch {
            repo.update(
                current.copy(
                    notificationThreshold = s.draftThreshold,
                    dailyNotificationCap = s.draftDailyCap,
                    mutedUntilMillis = if (s.draftMuted) Long.MAX_VALUE else null,
                )
            )
            closeSettings()
        }
    }

    fun removeTicker(symbol: String) {
        viewModelScope.launch { repo.remove(symbol) }
        closeSettings()
    }
}
