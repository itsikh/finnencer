package io.itsikh.finnencer.core.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.MainActivity
import io.itsikh.finnencer.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts user-facing notifications for AI jobs: completion + failure. Running
 * progress is tracked in-app via the Tasks screen, not via persistent
 * notifications, to avoid notification spam for fast jobs.
 *
 * Tapping a notification opens MainActivity routed to the Tasks screen.
 */
@Singleton
class AiJobNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun notifyCompleted(jobId: String, title: String, body: String) {
        post(jobId, title, body, icon = android.R.drawable.stat_sys_download_done)
    }

    fun notifyFailed(jobId: String, title: String, body: String) {
        post(jobId, "Failed: $title", body, icon = android.R.drawable.stat_notify_error)
    }

    private fun post(jobId: String, title: String, body: String, icon: Int) {
        NotificationChannels.ensureCreated(context)
        if (Build.VERSION.SDK_INT >= 33) {
            // POST_NOTIFICATIONS permission required at runtime.
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TASKS, true)
        }
        val pi = PendingIntent.getActivity(
            context,
            jobId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, NotificationChannels.AI_JOBS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(context).notify(jobId.hashCode(), notif)
    }

    companion object {
        const val EXTRA_OPEN_TASKS = "open_tasks"
    }
}
