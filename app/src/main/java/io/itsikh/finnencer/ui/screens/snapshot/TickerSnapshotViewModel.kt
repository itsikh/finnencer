package io.itsikh.finnencer.ui.screens.snapshot

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.ai.SnapshotAnalyzer
import io.itsikh.finnencer.data.entity.TickerMetrics
import io.itsikh.finnencer.data.entity.TickerMetricsAnalysis
import io.itsikh.finnencer.data.repo.QuotePoller
import io.itsikh.finnencer.data.repo.TickerMetricsRepo
import io.itsikh.finnencer.data.repo.TickerQuote
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class MetricsUiState {
    object Loading : MetricsUiState()
    data class Loaded(val metrics: TickerMetrics) : MetricsUiState()
    data class Error(val message: String) : MetricsUiState()
}

sealed class AnalysisUiState {
    object Idle : AnalysisUiState()
    object Loading : AnalysisUiState()
    data class Loaded(val row: TickerMetricsAnalysis) : AnalysisUiState()
    data class Error(val message: String) : AnalysisUiState()
}

@HiltViewModel
class TickerSnapshotViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val metricsRepo: TickerMetricsRepo,
    private val quotePoller: QuotePoller,
    private val analyzer: SnapshotAnalyzer,
) : ViewModel() {

    val symbol: String = savedState.get<String>("symbol")?.uppercase()
        ?: error("snapshot screen opened without symbol")

    private val _metrics = MutableStateFlow<MetricsUiState>(MetricsUiState.Loading)
    val metrics: StateFlow<MetricsUiState> = _metrics.asStateFlow()

    private val _quote = MutableStateFlow<TickerQuote?>(null)
    val quote: StateFlow<TickerQuote?> = _quote.asStateFlow()

    private val _analysis = MutableStateFlow<AnalysisUiState>(AnalysisUiState.Idle)
    val analysis: StateFlow<AnalysisUiState> = _analysis.asStateFlow()

    private val _analysisSheetOpen = MutableStateFlow(false)
    val analysisSheetOpen: StateFlow<Boolean> = _analysisSheetOpen.asStateFlow()

    init {
        load(force = false)
        viewModelScope.launch {
            _quote.value = quotePoller.snapshot(symbol) ?: quotePoller.latest.value[symbol]
        }
        viewModelScope.launch {
            analyzer.cached(symbol)?.let { _analysis.value = AnalysisUiState.Loaded(it) }
        }
    }

    fun load(force: Boolean) {
        viewModelScope.launch {
            if (force) _metrics.value = MetricsUiState.Loading
            runCatching { metricsRepo.load(symbol, force = force) }
                .onSuccess { _metrics.value = MetricsUiState.Loaded(it) }
                .onFailure { t ->
                    AppLogger.e(TAG, "metrics fetch failed for $symbol", t)
                    _metrics.value = MetricsUiState.Error(t.message ?: t.javaClass.simpleName)
                }
        }
    }

    fun openAnalysisSheet() { _analysisSheetOpen.value = true }
    fun closeAnalysisSheet() { _analysisSheetOpen.value = false }

    fun analyze(force: Boolean = false) {
        val current = _metrics.value
        if (current !is MetricsUiState.Loaded) return
        if (_analysis.value is AnalysisUiState.Loading) return
        _analysis.value = AnalysisUiState.Loading
        viewModelScope.launch {
            runCatching { analyzer.analyze(symbol, current.metrics, _quote.value?.price, force = force) }
                .onSuccess { _analysis.value = AnalysisUiState.Loaded(it) }
                .onFailure { t ->
                    AppLogger.e(TAG, "snapshot analysis failed for $symbol", t)
                    _analysis.value = AnalysisUiState.Error(t.message ?: t.javaClass.simpleName)
                }
        }
    }

    private companion object { const val TAG = "SnapshotVM" }
}
