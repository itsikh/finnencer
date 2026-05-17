package io.itsikh.finnencer.core.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the lifecycle of the periodic [SyncWorker] registration.
 *
 * Android's WorkManager enforces a minimum periodic interval of 15 minutes.
 * Lower values silently clamp upward, so [SyncIntervalMinutes.FIFTEEN] is the
 * floor we expose to the user.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    enum class SyncIntervalMinutes(val minutes: Long) {
        FIFTEEN(15), THIRTY(30), HOURLY(60), EVERY_TWO_HOURS(120);

        companion object {
            val DEFAULT = FIFTEEN
        }
    }

    fun schedulePeriodic(
        interval: SyncIntervalMinutes = SyncIntervalMinutes.DEFAULT,
        wifiOnly: Boolean = false,
        replaceExisting: Boolean = true,
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval.minutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        val policy = if (replaceExisting) {
            ExistingPeriodicWorkPolicy.UPDATE
        } else {
            ExistingPeriodicWorkPolicy.KEEP
        }

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            policy,
            request,
        )
    }

    /**
     * Fire-and-forget one-off sync. Registered under [ONCE_NAME] (not the
     * periodic [UNIQUE_NAME]) so it doesn't clobber the periodic schedule,
     * AND so [isSyncRunning] can include it — without that registration
     * the run-once work was invisible to the UI progress indicator.
     */
    fun runOnceNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    /**
     * Reactive "is a sync currently in flight?" flag — true while EITHER
     * the periodic worker OR a user-triggered one-off is RUNNING / ENQUEUED.
     * Including ENQUEUED here means the progress bar shows up immediately
     * after the user taps refresh, rather than waiting for WorkManager to
     * transition the job to RUNNING (which can lag a beat or two).
     */
    val isSyncRunning: Flow<Boolean> = combine(
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_NAME),
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ONCE_NAME),
    ) { periodic, once ->
        // Periodic sits in ENQUEUED state between firings — don't treat
        // that as "running" or the bar would never turn off. The one-off
        // queue, however, only contains an entry when the user just
        // tapped refresh, so ENQUEUED there means "about to run" and is
        // worth surfacing immediately.
        val periodicRunning = periodic.any { it.state == WorkInfo.State.RUNNING }
        val onceActive = once.any {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        }
        periodicRunning || onceActive
    }

    private companion object {
        const val UNIQUE_NAME = "finnencer-sync"
        const val ONCE_NAME = "finnencer-sync-once"
    }
}
