package io.itsikh.finnencer.core.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground Media3 service that owns the [ExoPlayer] backing podcast
 * playback. Hosts a [MediaSession] so the system can show lock-screen and
 * notification controls, and so Bluetooth / Android Auto can drive the
 * player without the app being in the foreground.
 */
@AndroidEntryPoint
class PodcastPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
