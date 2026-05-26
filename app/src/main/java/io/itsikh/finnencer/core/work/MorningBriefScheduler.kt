package io.itsikh.finnencer.core.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.data.repo.MorningBriefPreferences
import kotlinx.coroutines.flow.firstOrNull
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules and cancels the daily [MorningBriefWorker] run.
 *
 * Uses a chained OneTimeWorkRequest pattern (rather than
 * PeriodicWorkRequest) so we can land within ~minutes of the
 * user-configured clock time. PeriodicWork is good for
 * "approximately every N minutes" but bad for "every day at 8:30am" —
 * its flex period drifts, and there's no API to pin it to the wall
 * clock. The worker reschedules itself at the end of every run.
 */
@Singleton
class MorningBriefScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: MorningBriefPreferences,
) {

    /**
     * Schedule the next run based on current prefs.
     *
     * - When disabled, cancels any in-flight scheduling.
     * - When enabled, enqueues a OneTimeWorkRequest with the
     *   appropriate initial delay. UNIQUE+REPLACE so flipping the
     *   time picker doesn't double-schedule.
     */
    suspend fun rescheduleNext() {
        val cfg = readConfig()
        if (!cfg.enabled) {
            cancel()
            return
        }
        val delayMs = millisUntilNextRun(cfg)
        val request = OneTimeWorkRequestBuilder<MorningBriefWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    private suspend fun readConfig(): MorningBriefPreferences.Config {
        return MorningBriefPreferences.Config(
            enabled = prefs.enabled.firstOrNull() ?: false,
            hourLocal = prefs.hourLocal.firstOrNull() ?: 8,
            minuteLocal = prefs.minuteLocal.firstOrNull() ?: 30,
            weekdaysOnly = prefs.weekdaysOnly.firstOrNull() ?: true,
        )
    }

    companion object {
        const val UNIQUE_NAME = "finnencer-morning-brief"

        /**
         * Milliseconds until the next run. Same logic exposed so the
         * UI can show "Next brief in 14h 22m" alongside the toggle.
         */
        fun millisUntilNextRun(cfg: MorningBriefPreferences.Config): Long {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            val target = LocalTime.of(cfg.hourLocal, cfg.minuteLocal)
            var next = ZonedDateTime.of(LocalDateTime.of(now.toLocalDate(), target), zone)
            if (!next.isAfter(now)) {
                next = next.plusDays(1)
            }
            if (cfg.weekdaysOnly) {
                while (next.dayOfWeek == DayOfWeek.SATURDAY || next.dayOfWeek == DayOfWeek.SUNDAY) {
                    next = next.plusDays(1)
                }
            }
            return ChronoUnit.MILLIS.between(now, next).coerceAtLeast(0L)
        }
    }
}

