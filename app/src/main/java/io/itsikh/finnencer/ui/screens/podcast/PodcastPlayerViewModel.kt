package io.itsikh.finnencer.ui.screens.podcast

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.core.playback.PodcastPlaybackService
import io.itsikh.finnencer.data.dao.PodcastDao
import io.itsikh.finnencer.data.entity.Podcast
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1.0f,
)

@HiltViewModel
class PodcastPlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    podcastDao: PodcastDao,
) : ViewModel() {

    private val podcastId: Long = savedState.get<String>("podcastId")?.toLongOrNull()
        ?: error("player opened without podcastId")

    val podcast: StateFlow<Podcast?> = podcastDao.observe(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _ui = MutableStateFlow(PlaybackUiState())
    val ui: StateFlow<PlaybackUiState> = _ui.asStateFlow()

    private var controller: MediaController? = null
    private var loaded = false
    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            val token = SessionToken(context, ComponentName(context, PodcastPlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener({
                controller = future.get()
                attachListener()
                attemptLoad()
            }, MoreExecutors.directExecutor())
        }
        // When the podcast becomes READY, ensure it's loaded.
        viewModelScope.launch {
            podcast.collect { attemptLoad() }
        }
    }

    private fun attachListener() {
        val c = controller ?: return
        c.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _ui.value = _ui.value.copy(isPlaying = isPlaying)
                if (isPlaying) startPolling() else stopPolling()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                _ui.value = _ui.value.copy(
                    positionMs = c.currentPosition,
                    durationMs = c.duration.coerceAtLeast(0),
                )
            }
        })
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                val c = controller ?: break
                _ui.value = _ui.value.copy(
                    positionMs = c.currentPosition.coerceAtLeast(0),
                    durationMs = c.duration.coerceAtLeast(0),
                )
                delay(500)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun attemptLoad() {
        val c = controller ?: return
        val p = podcast.value ?: return
        val path = p.filePath ?: return
        if (loaded) return
        if (!File(path).exists()) return
        val uri = Uri.fromFile(File(path))
        c.setMediaItem(MediaItem.fromUri(uri))
        c.prepare()
        loaded = true
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun skipBack(ms: Long = 30_000) {
        val c = controller ?: return
        c.seekTo((c.currentPosition - ms).coerceAtLeast(0))
    }

    fun skipForward(ms: Long = 30_000) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + ms).coerceAtMost(c.duration))
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        _ui.value = _ui.value.copy(speed = speed)
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
        controller?.release()
        controller = null
    }
}
