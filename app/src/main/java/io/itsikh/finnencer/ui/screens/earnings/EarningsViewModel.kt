package io.itsikh.finnencer.ui.screens.earnings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.ai.ReportGenerator
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.entity.EarningsEvent
import io.itsikh.finnencer.data.entity.EarningsReport
import io.itsikh.finnencer.data.entity.ReportTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TierPickerState(
    val event: EarningsEvent? = null,
    val generating: Boolean = false,
    val error: String? = null,
    val producedReportId: Long? = null,
)

@HiltViewModel
class EarningsViewModel @Inject constructor(
    earningsDao: EarningsDao,
    private val reportGenerator: ReportGenerator,
) : ViewModel() {

    val upcoming: StateFlow<List<EarningsEvent>> =
        earningsDao.observeBetween(
            fromMillis = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000,
            toMillis = System.currentTimeMillis() + 90L * 24 * 60 * 60 * 1000,
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentReports: StateFlow<List<EarningsReport>> =
        earningsDao.observeRecentReports(50)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _picker = MutableStateFlow(TierPickerState())
    val picker: StateFlow<TierPickerState> = _picker.asStateFlow()

    fun openTierPicker(event: EarningsEvent) {
        _picker.value = TierPickerState(event = event)
    }

    fun closeTierPicker() {
        _picker.value = TierPickerState()
    }

    fun generate(tier: ReportTier) {
        val event = _picker.value.event ?: return
        _picker.value = _picker.value.copy(generating = true, error = null)
        viewModelScope.launch {
            runCatching { reportGenerator.generate(event.id, tier) }
                .onSuccess { id ->
                    _picker.value = _picker.value.copy(generating = false, producedReportId = id)
                }
                .onFailure { t ->
                    _picker.value = _picker.value.copy(
                        generating = false,
                        error = t.message ?: "Failed to generate report",
                    )
                }
        }
    }
}
