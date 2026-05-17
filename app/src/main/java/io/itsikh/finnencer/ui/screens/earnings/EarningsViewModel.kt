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
    private val earningsDao: EarningsDao,
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

    // ── Selection mode for bulk delete (#26) ────────────────────────────
    private val _selectedReportIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedReportIds: StateFlow<Set<Long>> = _selectedReportIds.asStateFlow()

    /** True whenever the user is in multi-select mode. We treat "selection
     *  empty" as "not in select mode" — entering is triggered by adding
     *  the first id via long-press. */
    val isSelecting: StateFlow<Boolean> = _selectedReportIds
        .mapState { it.isNotEmpty() }

    private fun <T, R> StateFlow<T>.mapState(transform: (T) -> R): StateFlow<R> =
        kotlinx.coroutines.flow.MutableStateFlow(transform(value)).also { out ->
            viewModelScope.launch {
                collect { out.value = transform(it) }
            }
        }.asStateFlow()

    fun toggleReportSelection(reportId: Long) {
        _selectedReportIds.value = _selectedReportIds.value.toMutableSet().also {
            if (!it.add(reportId)) it.remove(reportId)
        }
    }

    fun selectAllReports() {
        _selectedReportIds.value = recentReports.value.map { it.id }.toSet()
    }

    fun invertReportSelection() {
        val all = recentReports.value.map { it.id }.toSet()
        _selectedReportIds.value = all - _selectedReportIds.value
    }

    fun clearReportSelection() {
        _selectedReportIds.value = emptySet()
    }

    fun deleteSelectedReports() {
        val ids = _selectedReportIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            earningsDao.deleteReports(ids)
            _selectedReportIds.value = emptySet()
        }
    }

    fun deleteAllReports() {
        viewModelScope.launch {
            earningsDao.deleteAllReports()
            _selectedReportIds.value = emptySet()
        }
    }

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
