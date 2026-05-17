package io.itsikh.finnencer.core.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import io.itsikh.finnencer.MainActivity

/**
 * Foreground Media3 service that owns the [ExoPlayer] backing podcast
 * playback. Hosts a [MediaSession] so the system can show lock-screen and
 * notification controls, and so Bluetooth / Android Auto can drive the
 * player without the app being in the foreground.
 *
 * The session is built with a `sessionActivity` PendingIntent so that
 * tapping the system playback notification (or the lock-screen card)
 * brings the app to the foreground. Without it, the notification looked
 * tappable but did nothing — see issue #19.
 */
@AndroidEntryPoint
class PodcastPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
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
