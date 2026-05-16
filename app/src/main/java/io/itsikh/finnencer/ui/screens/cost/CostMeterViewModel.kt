package io.itsikh.finnencer.ui.screens.cost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.dao.ApiUsageDao
import io.itsikh.finnencer.data.dao.ProviderUsageRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class CostWindow(val millis: Long, val label: String) {
    TODAY(0, "Today"),
    LAST_7D(7L * 24 * 60 * 60 * 1000, "Last 7d"),
    LAST_30D(30L * 24 * 60 * 60 * 1000, "Last 30d"),
}

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CostMeterViewModel @Inject constructor(
    private val apiUsageDao: ApiUsageDao,
) : ViewModel() {

    private val _window = MutableStateFlow(CostWindow.LAST_7D)
    val window: StateFlow<CostWindow> = _window

    val rollup: StateFlow<List<ProviderUsageRow>> = _window
        .flatMapLatest { w ->
            val since = if (w == CostWindow.TODAY) startOfToday() else System.currentTimeMillis() - w.millis
            apiUsageDao.observeRollupSince(since)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setWindow(w: CostWindow) { _window.value = w }

    private fun startOfToday(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
