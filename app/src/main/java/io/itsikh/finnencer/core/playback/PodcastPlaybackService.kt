package io.itsikh.finnencer.core.playback

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
 *
 * Adds a custom "Stop & exit" button to the notification's transport
 * row via [MediaSession.setCustomLayout]. Tapping it releases the
 * player, removes the foreground service / notification, and asks
 * Android to reclaim the process — equivalent to swiping the app off
 * recents.
 */
@AndroidEntryPoint
@UnstableApi
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
        val exitButton = CommandButton.Builder()
            .setDisplayName("Stop & exit")
            // CLOSE icon ships in Media3's bundled-icons enum starting
            // in 1.4; using ICON_UNDEFINED keeps the system from
            // drawing a placeholder when the value isn't supported on
            // older Media3 releases.
            .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
            .setSessionCommand(SessionCommand(ACTION_EXIT_APP, Bundle.EMPTY))
            .build()
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCustomLayout(listOf(exitButton))
            .setCallback(ExitCallback(exitButton))
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

    /**
     * Stops playback, removes the notification, and asks Android to
     * reclaim the process. Soft exit — we don't call
     * [Process.killProcess], which is an Android anti-pattern. The OS
     * tears down the activity stack and the service; a re-launch
     * cold-starts cleanly.
     */
    private fun performStopAndExit() {
        mediaSession?.run {
            player.stop()
            player.clearMediaItems()
        }
        // Drop the foreground notification before stopSelf so the user
        // doesn't see a flicker of "paused, tap to resume".
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Authorizes the [ACTION_EXIT_APP] custom command from any
     * controller (the system media notification, in practice) and
     * dispatches it to [performStopAndExit].
     */
    private inner class ExitCallback(
        private val exitButton: CommandButton,
    ) : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // Default connection commands + our custom one so the
            // controller is allowed to issue ACTION_EXIT_APP.
            val sessionCommands = MediaSession.ConnectionResult
                .DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_EXIT_APP, Bundle.EMPTY))
                .build()
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(listOf(exitButton))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_EXIT_APP) {
                performStopAndExit()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    private companion object {
        const val ACTION_EXIT_APP = "io.itsikh.finnencer.ACTION_EXIT_APP"
    }
}
