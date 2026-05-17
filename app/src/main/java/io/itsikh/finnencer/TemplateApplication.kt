package io.itsikh.finnencer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import io.itsikh.finnencer.core.notifications.NotificationChannels
import io.itsikh.finnencer.core.work.SyncScheduler
import io.itsikh.finnencer.data.repo.WatchlistRestorer
import io.itsikh.finnencer.logging.AppLogger
import io.itsikh.finnencer.logging.GlobalExceptionHandler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point for the template app.
 *
 * Responsibilities:
 * - Annotated with [@HiltAndroidApp] to trigger Hilt's code generation and make DI
 *   available across the whole application.
 * - Installs [GlobalExceptionHandler] as the default uncaught exception handler so that
 *   any crash on any thread is captured to a file before the system default handler runs.
 *   This allows crash logs to be included in the next bug report the user submits.
 * - Creates the backup notification channel ([AppConfig.NOTIFICATION_CHANNEL_BACKUP]) used
 *   by [backup.BaseBackupManager] to post "Backup ready — tap to save" notifications.
 *
 * ## Adding initialization
 * Place one-time startup work (SDK inits, analytics, etc.) in [onCreate] after `super.onCreate()`.
 * Keep it lightweight — heavy work should be deferred to a background coroutine.
 *
 * ## Hilt
 * Because this class is annotated with [@HiltAndroidApp], it is the root of the Hilt
 * component hierarchy. All [@Singleton] scoped objects live as long as this Application.
 */
@HiltAndroidApp
class TemplateApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var watchlistRestorer: WatchlistRestorer

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(
            GlobalExceptionHandler(this, Thread.getDefaultUncaughtExceptionHandler())
        )
        createNotificationChannels()
        // Idempotent: WorkManager keeps the existing periodic request if one
        // already exists with the same unique name and interval.
        syncScheduler.schedulePeriodic()
        // Restore watchlist from the on-disk snapshot if the Room table
        // ever ends up empty (e.g. after a destructive migration).
        // Keeps the snapshot file fresh on every healthy start.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { watchlistRestorer.ensureRestored() }
                .onFailure { AppLogger.e("App", "Watchlist restore failed", it) }
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                AppConfig.NOTIFICATION_CHANNEL_BACKUP,
                "Backup",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for completed backups that are ready to save"
            }
        )
        // finnencer's own channels (alerts + digest).
        NotificationChannels.ensureCreated(this)
    }
}
