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
    private val podcastDao: PodcastDao,
    private val queueRepo: QueueRepository,
    private val prefs: PodcastPreferences,
    private val aiJobDao: io.itsikh.finnencer.data.dao.AiJobDao,
    private val aiJobsRepo: io.itsikh.finnencer.data.repo.AiJobsRepository,
) : ViewModel() {

    private val podcastId: Long = savedState.get<String>("podcastId")?.toLongOrNull()
        ?: error("player opened without podcastId")

    /**
     * Where this player was opened from — drives the implicit
     * "play through" behavior. When `"queue"`, the player advances to
     * the next podcast on completion regardless of the user's global
     * Stop/Continue/Mix preference (the explicit act of opening from a
     * queue signals "play through the queue"). Default "direct"
     * preserves the user's saved preference for library / notification
     * / direct-link entry points.
     */
    val launchSource: String = savedState.get<String>("from") ?: "direct"

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
     *  per playback-to-completion, not once per state-change repaint.
     *  Reset when the player leaves STATE_ENDED so a replayed episode
     *  can trigger end-of-podcast handling again (#84). */
    private var endHandled = false
    /** Set by [onCleared]; the async controller-connect callback checks it
     *  so a controller that finishes connecting AFTER the screen is gone
     *  is released instead of leaked (and never auto-plays) (#80). */
    @Volatile private var cleared = false
    private var pollJob: Job? = null

    /** True when the podcast row says READY but the WAV is gone from disk
     *  (deleted externally / restored backup without audio). The screen
     *  shows an explicit error instead of dead controls (#77). */
    private val _fileMissing = MutableStateFlow(false)
    val fileMissing: StateFlow<Boolean> = _fileMissing.asStateFlow()

    init {
        // Build the MediaController on the main looper and dispatch the
        // future's completion callback back to the main looper too — Media3
        // requires every controller call (setMediaItem / prepare / play)
        // to happen on the application main thread. Without forcing the
        // dispatcher, the listener can fire on the service-binder thread
        // and the play() call silently no-ops (#29).
        val mainExecutor = androidx.core.content.ContextCompat.getMainExecutor(context)
        viewModelScope.launch {
            val token = SessionToken(context, ComponentName(context, PodcastPlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener({
                val connected = future.get()
                if (cleared) {
                    // Screen was exited before the connect completed —
                    // releasing here (instead of assigning) prevents a
                    // leaked controller that keeps the service bound and
                    // could even start ghost playback (#80).
                    connected.release()
                    return@addListener
                }
                controller = connected
                io.itsikh.finnencer.logging.AppLogger.i(TAG, "MediaController connected (id=$podcastId)")
                attachListener()
                attemptLoad()
            }, mainExecutor)
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
                if (isPlaying) {
                    startPolling()
                } else {
                    stopPolling()
                    // Pause is the most reliable "user is leaving" signal —
                    // persist so reopening resumes here. Guarded by mediaId
                    // because this listener sits on the SHARED session
                    // player, which may be playing a different podcast.
                    if (c.currentMediaItem?.mediaId == podcastId.toString()) {
                        persistPosition(c.currentPosition.coerceAtLeast(0))
                    }
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                _ui.value = _ui.value.copy(
                    positionMs = c.currentPosition,
                    durationMs = c.duration.coerceAtLeast(0),
                )
                if (playbackState == Player.STATE_ENDED) {
                    // The listener sits on the SHARED session player —
                    // only react when it's OUR podcast that ended, or a
                    // background episode finishing would mark this one
                    // done and misfire auto-advance (#81).
                    if (c.currentMediaItem?.mediaId == podcastId.toString()) {
                        // Reset the saved position so a replay starts
                        // from the top.
                        persistPosition(0)
                        handlePlaybackEnded()
                    }
                } else {
                    // Left ENDED (replay/seek-back) — re-arm so finishing
                    // again triggers end-of-podcast handling again (#84).
                    endHandled = false
                }
            }
        })
    }

    /** Last position written to Room — dedups the periodic writes. */
    private var lastPersistedPosMs = -1L

    private fun persistPosition(positionMs: Long) {
        if (kotlin.math.abs(positionMs - lastPersistedPosMs) < 1_000) return
        lastPersistedPosMs = positionMs
        viewModelScope.launch {
            runCatching { podcastDao.updatePosition(podcastId, positionMs, System.currentTimeMillis()) }
        }
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
                // Skip queue items whose podcast row no longer exists —
                // auto-advancing to a deleted podcast left the player
                // stuck on "Loading…" forever (#78).
                .filter { item ->
                    item.refId?.toLongOrNull()?.let { podcastDao.get(it) != null } == true
                }
            // When the player was opened from the queue, the user is
            // implicitly playing through it — override a global STOP
            // setting to CONTINUE for this listening session. The
            // explicit Mix preference is still honored even from the
            // queue, since "Mix" is a stronger intent than the default.
            val savedAction = prefs.endOfPodcastAction.first()
            val effectiveAction = if (launchSource == "queue" && savedAction == EndOfPodcastAction.STOP) {
                EndOfPodcastAction.CONTINUE
            } else savedAction
            val nextRefId: String? = when (effectiveAction) {
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
            var ticks = 0
            while (true) {
                val c = controller ?: break
                _ui.value = _ui.value.copy(
                    positionMs = c.currentPosition.coerceAtLeast(0),
                    durationMs = c.duration.coerceAtLeast(0),
                )
                // Persist the listening position every ~5s so process
                // death / navigation loses at most a few seconds.
                if (++ticks % 10 == 0 && c.currentMediaItem?.mediaId == podcastId.toString()) {
                    persistPosition(c.currentPosition.coerceAtLeast(0))
                }
                delay(500)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun attemptLoad() {
        val c = controller
        val p = podcast.value
        if (c == null) {
            io.itsikh.finnencer.logging.AppLogger.i(TAG, "attemptLoad skip: controller=null (id=$podcastId)")
            return
        }
        if (p == null) {
            io.itsikh.finnencer.logging.AppLogger.i(TAG, "attemptLoad skip: podcast=null (id=$podcastId)")
            return
        }
        val path = p.filePath
        if (path == null) {
            io.itsikh.finnencer.logging.AppLogger.i(TAG, "attemptLoad skip: filePath=null status=${p.status} (id=$podcastId)")
            return
        }
        if (loaded) return
        // The session player is shared and may already be playing THIS
        // podcast in the background (user backed out and came back).
        // Adopt it instead of re-setting the media item, which restarted
        // playback from 0:00 on every reopen.
        if (c.currentMediaItem?.mediaId == podcastId.toString()) {
            loaded = true
            _ui.value = _ui.value.copy(
                isPlaying = c.isPlaying,
                positionMs = c.currentPosition.coerceAtLeast(0),
                durationMs = c.duration.coerceAtLeast(0),
                speed = c.playbackParameters.speed,
            )
            if (c.isPlaying) startPolling()
            io.itsikh.finnencer.logging.AppLogger.i(TAG, "player adopted live session (id=$podcastId pos=${c.currentPosition})")
            return
        }
        if (!File(path).exists()) {
            io.itsikh.finnencer.logging.AppLogger.w(TAG, "attemptLoad skip: file missing $path")
            // Surface it — a READY row with no WAV used to render fully
            // live controls that silently did nothing (#77).
            _fileMissing.value = true
            return
        }
        _fileMissing.value = false
        val uri = Uri.fromFile(File(path))
        c.setMediaItem(
            MediaItem.Builder()
                .setUri(uri)
                // mediaId is how we later recognize "this podcast is
                // already loaded" — keep it the row id.
                .setMediaId(podcastId.toString())
                .build()
        )
        c.prepare()
        // Resume where the user left off — unless they were effectively
        // done (within the last 5s), then start from the top.
        val resumeAt = p.playPositionMs
        val nearEnd = p.durationMs?.let { resumeAt >= it - 5_000 } ?: false
        if (resumeAt > 0 && !nearEnd) c.seekTo(resumeAt)
        // Auto-start playback when the player opens (#29). Opening the
        // player from the queue / library row previously left it paused
        // and the user had to tap play — friction the bug report flagged.
        c.play()
        loaded = true
        io.itsikh.finnencer.logging.AppLogger.i(TAG, "player loaded + play() called (id=$podcastId resumeAt=$resumeAt path=$path)")
    }

    private companion object { const val TAG = "PodcastPlayerVM" }

    /** Surfaced via [retryStatus] so the player screen can flash a toast
     *  if the retry couldn't find the originating AI job (e.g. user
     *  cleared it from the Tasks screen). */
    sealed interface RetryStatus {
        data object Idle : RetryStatus
        data object Retrying : RetryStatus
        data object Success : RetryStatus
        data class Error(val message: String) : RetryStatus
    }

    private val _retryStatus = MutableStateFlow<RetryStatus>(RetryStatus.Idle)
    val retryStatus: StateFlow<RetryStatus> = _retryStatus.asStateFlow()

    /**
     * Re-run whichever AI job produced this podcast row, reusing the
     * same Podcast row id so v0.0.40's per-chunk cache + persisted
     * script kick in (#43). If no AI job rows reference this podcast
     * (only possible for rows created before every podcast path went
     * through an enqueued job) surfaces an error so the user can navigate
     * back to the source screen and make the podcast again.
     */
    fun retry() {
        viewModelScope.launch {
            _retryStatus.value = RetryStatus.Retrying
            val job = aiJobDao.findByResultRefId(podcastId.toString())
            if (job == null) {
                _retryStatus.value = RetryStatus.Error(
                    "No AI job is associated with this podcast. Open the source screen (earnings report or ticker feed) and try again from there."
                )
                return@launch
            }
            runCatching { aiJobsRepo.retry(job.id) }
                .onSuccess { _retryStatus.value = RetryStatus.Success }
                .onFailure { _retryStatus.value = RetryStatus.Error(it.message ?: "Retry failed") }
        }
    }

    fun clearRetryStatus() { _retryStatus.value = RetryStatus.Idle }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            // Replaying a finished episode: in STATE_ENDED play() only
            // sets playWhenReady (often already true) and nothing moves —
            // seek to the start so the tap actually restarts playback.
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play()
        }
    }

    fun skipBack(ms: Long = 30_000) {
        val c = controller ?: return
        val target = (c.currentPosition - ms).coerceAtLeast(0)
        c.seekTo(target)
        // Reflect immediately — the poll loop is stopped while paused,
        // so without this the slider/label snap back until play resumes.
        _ui.value = _ui.value.copy(positionMs = target)
        persistSeek(c, target)
    }

    fun skipForward(ms: Long = 30_000) {
        val c = controller ?: return
        // duration is TIME_UNSET (a huge negative) until READY —
        // coercing against it seeked to the default position (0:00).
        val duration = c.duration
        val cap = if (duration == androidx.media3.common.C.TIME_UNSET) Long.MAX_VALUE else duration
        val target = (c.currentPosition + ms).coerceAtMost(cap)
        c.seekTo(target)
        _ui.value = _ui.value.copy(positionMs = target.coerceAtLeast(0))
        persistSeek(c, target.coerceAtLeast(0))
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        c.seekTo(positionMs)
        _ui.value = _ui.value.copy(positionMs = positionMs)
        persistSeek(c, positionMs)
    }

    /** Seeks made while PAUSED never hit the poll loop or a pause
     *  transition — persist them here or a pause→scrub→leave sequence
     *  resumes at the pre-scrub position. */
    private fun persistSeek(c: MediaController, positionMs: Long) {
        if (c.currentMediaItem?.mediaId == podcastId.toString()) {
            persistPosition(positionMs)
        }
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        _ui.value = _ui.value.copy(speed = speed)
    }

    override fun onCleared() {
        super.onCleared()
        cleared = true
        stopPolling()
        controller?.release()
        controller = null
    }
}
