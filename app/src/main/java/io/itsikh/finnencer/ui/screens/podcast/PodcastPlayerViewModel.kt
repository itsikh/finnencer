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
import io.itsikh.finnencer.data.entity.QueueItemKind
import io.itsikh.finnencer.data.repo.EndOfPodcastAction
import io.itsikh.finnencer.data.repo.PodcastPreferences
import io.itsikh.finnencer.data.repo.QueueRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val queueRepo: QueueRepository,
    private val prefs: PodcastPreferences,
) : ViewModel() {

    private val podcastId: Long = savedState.get<String>("podcastId")?.toLongOrNull()
        ?: error("player opened without podcastId")

    val podcast: StateFlow<Podcast?> = podcastDao.observe(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _ui = MutableStateFlow(PlaybackUiState())
    val ui: StateFlow<PlaybackUiState> = _ui.asStateFlow()

    /**
     * Fires the next podcast's id when the current podcast ends and the
     * user has opted into auto-play-next via [PodcastPreferences]. The
     * screen observes this and asks the navigator to replace the route.
     */
    private val _navigateToNext = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val navigateToNext: SharedFlow<Long> = _navigateToNext.asSharedFlow()

    private var controller: MediaController? = null
    private var loaded = false
    /** Guards [handlePlaybackEnded] so STATE_ENDED-triggered work runs once
     *  per opened podcast, not once per state-change repaint. */
    private var endHandled = false
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
                if (playbackState == Player.STATE_ENDED) handlePlaybackEnded()
            }
        })
    }

    /**
     * On playback completion: mark this podcast's queue item (if any) as
     * done so the user's reading queue stays in sync, then act on the
     * user's end-of-podcast preference — STOP / CONTINUE / SHUFFLE.
     */
    private fun handlePlaybackEnded() {
        if (endHandled) return
        endHandled = true
        viewModelScope.launch {
            val refId = podcastId.toString()
            val current = queueRepo.observeByRef(QueueItemKind.PODCAST, refId).first()
            if (current != null && current.completedAtMillis == null) {
                queueRepo.markDone(current.id)
            }
            val incompletePodcasts = queueRepo.observeIncomplete().first()
                .filter { it.kind == QueueItemKind.PODCAST.name && it.refId != refId }
            val nextRefId: String? = when (prefs.endOfPodcastAction.first()) {
                EndOfPodcastAction.STOP -> null
                EndOfPodcastAction.CONTINUE -> incompletePodcasts.firstOrNull()?.refId
                EndOfPodcastAction.SHUFFLE -> incompletePodcasts.randomOrNull()?.refId
            }
            nextRefId?.toLongOrNull()?.let { _navigateToNext.tryEmit(it) }
        }
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
        // Auto-start playback when the player opens (#29). Opening the
        // player from the queue / library row previously left it paused
        // and the user had to tap play — friction the bug report flagged.
        c.play()
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
