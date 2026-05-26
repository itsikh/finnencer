package io.itsikh.finnencer.core.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.MainActivity
import io.itsikh.finnencer.R
import io.itsikh.finnencer.logging.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts simple title/body notifications for non-news signals — insider
 * trading flags and SEC filing watchers, and other "we noticed
 * something" surfaces that don't fit the article-fanout pipeline in
 * [AlertNotifier].
 *
 * Same channel as article alerts so the user can silence both with one
 * toggle. Tap routes to a deep link if provided, otherwise opens the
 * watchlist.
 */
@Singleton
class SignalNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Post a notification with [title] / [body]. The unique [tag] keys
     * the system notification id so re-posting under the same tag
     * updates the existing one (rather than stacking duplicates). The
     * optional [deepLink] is opened on tap; pass null to land on the
     * default activity.
     */
    fun post(tag: String, title: String, body: String, deepLink: String? = null) {
        if (!hasNotificationPermission()) {
            AppLogger.w(TAG, "no notifications permission — skipping $tag")
            return
        }
        NotificationChannels.ensureCreated(context)
        if (!NotificationChannels.areAlertsEnabled(context)) {
            AppLogger.w(TAG, "alerts channel disabled — skipping $tag")
            return
        }
        val tapIntent = if (deepLink != null) {
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(deepLink),
                context,
                MainActivity::class.java,
            )
        } else {
            Intent(context, MainActivity::class.java)
        }.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, NotificationChannels.ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(tag.hashCode(), notif)
        }.onFailure { AppLogger.e(TAG, "notify() threw for $tag", it) }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object { const val TAG = "SignalNotifier" }
}
