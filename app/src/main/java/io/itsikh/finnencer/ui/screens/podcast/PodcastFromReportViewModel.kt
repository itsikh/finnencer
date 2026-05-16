package io.itsikh.finnencer.ui.screens.podcast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.ai.PodcastGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PodcastGenState {
    object Starting : PodcastGenState
    data class Ready(val podcastId: Long) : PodcastGenState
    data class Failed(val message: String) : PodcastGenState
}

/**
 * Tiny launcher view-model used by the route `podcast/from-report/{id}`:
 * kicks off PodcastGenerator on init and emits the new podcast row id so
 * the nav graph can redirect to the player.
 */
@HiltViewModel
class PodcastFromReportViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val generator: PodcastGenerator,
) : ViewModel() {

    private val reportId: Long = savedState.get<String>("reportId")?.toLongOrNull()
        ?: error("podcast generation opened without reportId")

    private val _state = MutableStateFlow<PodcastGenState>(PodcastGenState.Starting)
    val state: StateFlow<PodcastGenState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { generator.generateFromReport(reportId) }
                .onSuccess { id -> _state.value = PodcastGenState.Ready(id) }
                .onFailure { t -> _state.value = PodcastGenState.Failed(t.message ?: "Failed") }
        }
    }
}
