package io.itsikh.finnencer.core.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
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

    /** Fire-and-forget one-off sync. Useful for pull-to-refresh later. */
    fun runOnceNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    private companion object {
        const val UNIQUE_NAME = "finnencer-sync"
    }
}
