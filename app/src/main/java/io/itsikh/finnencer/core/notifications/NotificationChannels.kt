package io.itsikh.finnencer.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat

object NotificationChannels {

    /** Material news that crossed the user's ticker threshold. */
    const val ALERTS = "finnencer.alerts"

    /** Less urgent informational pushes (e.g. weekly digest). Reserved for later. */
    const val DIGEST = "finnencer.digest"

    fun ensureCreated(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alerts = NotificationChannel(
            ALERTS,
            "Ticker alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Material news on tickers you watch"
            enableVibration(true)
            setShowBadge(true)
        }
        val digest = NotificationChannel(
            DIGEST,
            "Digest",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Daily / weekly market summaries"
            enableVibration(false)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(alerts)
        mgr.createNotificationChannel(digest)
    }

    fun areAlertsEnabled(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = mgr.getNotificationChannel(ALERTS) ?: return true
        return ch.importance != NotificationManager.IMPORTANCE_NONE
    }
}
