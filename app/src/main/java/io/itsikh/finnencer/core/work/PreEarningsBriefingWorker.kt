package io.itsikh.finnencer.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.entity.ReportTier
import io.itsikh.finnencer.data.repo.AiJobsRepository
import io.itsikh.finnencer.data.repo.PreEarningsPreferences
import io.itsikh.finnencer.data.repo.WatchlistRepository
import io.itsikh.finnencer.logging.AppLogger as Log
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Periodically scans the user's watchlist for earnings events that are
 * about to happen, and auto-spawns a BRIEF tier report for each one
 * so the user has a "what to watch" briefing ready ~24h before the
 * print.
 *
 * Why a brief tier specifically: it's the cheapest paid tier we have
 * and a pre-event read is meant to be a 60-second skim, not a deep
 * dive. The user can always upgrade the tier from the Earnings tab if
 * they want more depth.
 *
 * Idempotency: every event id we've spawned a brief for is recorded
 * in [PreEarningsPreferences.briefedEventIds]. Subsequent ticks
 * within the 24h window skip events already in that set. (The window
 * is 24h-30h so we only see each event once per cycle anyway — the
 * set is belt-and-suspenders.)
 */
@HiltWorker
class PreEarningsBriefingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val watchlist: WatchlistRepository,
    private val earningsDao: EarningsDao,
    private val aiJobs: AiJobsRepository,
    private val prefs: PreEarningsPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        run()
        Result.success()
    } catch (t: Throwable) {
        Log.e(TAG, "pre-earnings briefing pass failed", t)
        // The schedule will retry naturally on the next periodic tick.
        Result.success()
    }

    private suspend fun run() {
        if (prefs.enabled.first() != true) {
            return
        }
        val tickers = watchlist.observeAll().first()
        if (tickers.isEmpty()) return

        val now = System.currentTimeMillis()
        val windowStart = now + WINDOW_LEAD_MIN_MS
        val windowEnd = now + WINDOW_LEAD_MAX_MS
        val nextEvents = earningsDao.observeNextEventForSymbols(
            tickers.map { it.symbol },
            now,
        ).first()
        val already = prefs.briefedEventIds.first()

        val candidates = nextEvents.filter { ev ->
            ev.scheduledAtMillis in windowStart..windowEnd &&
                ev.id !in already
        }

        if (candidates.isEmpty()) return

        val labelFmt = DateTimeFormatter.ofPattern("MMM d")
        for (ev in candidates) {
            val label = Instant.ofEpochMilli(ev.scheduledAtMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(labelFmt)
            runCatching {
                aiJobs.enqueueEarningsReport(
                    tickerSymbol = ev.tickerSymbol,
                    earningsEventId = ev.id,
                    eventLabel = label,
                    tier = ReportTier.BRIEF,
                )
            }.onSuccess {
                prefs.markBriefed(ev.id)
                Log.i(TAG, "queued pre-earnings BRIEF for ${ev.tickerSymbol} ($label, event id ${ev.id})")
            }.onFailure { t ->
                Log.w(TAG, "failed to queue pre-earnings brief for ${ev.tickerSymbol}: ${t.message}")
            }
        }
    }

    private companion object {
        const val TAG = "PreEarnings"
        // Window: events scheduled 18h..30h from now. The lower bound
        // keeps us from re-queueing if a brief is already in flight
        // and is taking a long time to complete; the upper bound is
        // the "around a day before" target.
        const val WINDOW_LEAD_MIN_MS = 18L * 60 * 60 * 1000
        const val WINDOW_LEAD_MAX_MS = 30L * 60 * 60 * 1000
    }
}

/**
 * Scheduler companion for [PreEarningsBriefingWorker]. Six-hourly
 * periodic firing — fine-grained enough to catch any event in the
 * 18-30h window, infrequent enough to avoid spamming work for no
 * reason.
 */
@Singleton
class PreEarningsBriefingScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreEarningsPreferences,
) {
    suspend fun ensureScheduled() {
        if (prefs.enabled.first() != true) {
            cancel()
            return
        }
        val request = PeriodicWorkRequestBuilder<PreEarningsBriefingWorker>(
            6, TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    private companion object {
        const val UNIQUE_NAME = "finnencer-pre-earnings-briefing"
    }
}
