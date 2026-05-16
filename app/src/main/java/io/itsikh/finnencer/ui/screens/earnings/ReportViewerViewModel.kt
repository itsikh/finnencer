package io.itsikh.finnencer.ui.screens.earnings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.entity.EarningsReport
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

@HiltViewModel
class ReportViewerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    earningsDao: EarningsDao,
) : ViewModel() {

    private val reportId: Long = savedState.get<String>("reportId")?.toLongOrNull()
        ?: error("report viewer opened without reportId")

    val report: StateFlow<EarningsReport?> = earningsDao
        .observeReport(reportId)
        .map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
