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
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.core.notifications.SignalNotifier
import io.itsikh.finnencer.data.api.SecEdgarService
import io.itsikh.finnencer.data.repo.SecFilingAlertPreferences
import io.itsikh.finnencer.data.repo.WatchlistRepository
import io.itsikh.finnencer.logging.AppLogger as Log
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches SEC EDGAR for new 8-K filings on the user's watchlist and
 * pushes a notification when one drops.
 *
 * Why 8-K specifically: it's the SEC's "material event" form — the
 * one companies have to file within 4 business days of anything
 * non-routine (officer departures, debt issuances, ratings actions,
 * merger agreements). The 10-K / 10-Q schedule is predictable and
 * already on the earnings calendar; 8-K is the unpredictable signal
 * that nobody else's mobile app surfaces.
 *
 * Item codes are translated to human language via [ITEM_DESCRIPTIONS]
 * so the push body reads as "[NVDA] 8-K: officer departure (5.02)"
 * rather than just the bare code.
 */
@HiltWorker
class SecFilingAlertWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val watchlist: WatchlistRepository,
    private val edgar: SecEdgarService,
    private val notifier: SignalNotifier,
    private val prefs: SecFilingAlertPreferences,
    private val gson: Gson,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        scan()
        Result.success()
    } catch (t: Throwable) {
        Log.e(TAG, "8-K scan failed", t)
        Result.success()
    }

    private suspend fun scan() {
        if (prefs.enabled.first() != true) return
        val tickers = watchlist.observeAll().first().filter { !it.cik.isNullOrBlank() }
        if (tickers.isEmpty()) return

        val cutoff = LocalDate.now().minusDays(LOOKBACK_DAYS)
        val already = prefs.notifiedAccessions.first().toMutableSet()
        val newlyNotified = mutableListOf<String>()

        for (t in tickers) {
            val cik = t.cik!!.padStart(10, '0')
            val filingsResult = runCatching {
                fetchEightKs(cik = cik, sinceDate = cutoff)
            }
            val filings = filingsResult.getOrNull()
            if (filings == null) {
                Log.w(TAG, "EDGAR fetch failed for ${t.symbol}: ${filingsResult.exceptionOrNull()?.message}")
                continue
            }
            for (f in filings) {
                if (f.accessionNumber in already) continue
                val itemDescriptions = describeItems(f.items)
                val body = buildString {
                    append("Filed ").append(f.filingDate)
                    if (itemDescriptions.isNotEmpty()) {
                        append(" — ")
                        append(itemDescriptions.joinToString(" · "))
                    }
                }
                notifier.post(
                    tag = "sec-${f.accessionNumber}",
                    title = "[${t.symbol}] new 8-K filing",
                    body = body,
                )
                newlyNotified.add(f.accessionNumber)
                already.add(f.accessionNumber)
            }
        }

        if (newlyNotified.isNotEmpty()) {
            prefs.markNotified(newlyNotified)
            Log.i(TAG, "notified ${newlyNotified.size} new 8-K filing(s)")
        }
    }

    private suspend fun fetchEightKs(cik: String, sinceDate: LocalDate): List<EightKFiling> {
        val body = edgar.submissions(cik)
        val parsed = gson.fromJson(body, SubmissionsJson::class.java)
            ?: return emptyList()
        val recent = parsed.filings?.recent ?: return emptyList()
        val forms = recent.form ?: return emptyList()
        val n = forms.size
        if (n == 0) return emptyList()
        val out = ArrayList<EightKFiling>(8)
        for (i in 0 until n) {
            val form = forms.getOrNull(i) ?: continue
            if (form != "8-K") continue
            val dateStr = recent.filingDate?.getOrNull(i) ?: continue
            val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: continue
            if (date.isBefore(sinceDate)) continue
            val acc = recent.accessionNumber?.getOrNull(i) ?: continue
            val items = recent.items?.getOrNull(i).orEmpty()
            out.add(
                EightKFiling(
                    accessionNumber = acc,
                    filingDate = dateStr,
                    items = items,
                    ageDays = ChronoUnit.DAYS.between(date, LocalDate.now()),
                ),
            )
        }
        return out
    }

    private fun describeItems(itemsCsv: String): List<String> {
        if (itemsCsv.isBlank()) return emptyList()
        return itemsCsv.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { code ->
                val desc = ITEM_DESCRIPTIONS[code]
                if (desc != null) "$desc ($code)" else code
            }
    }

    private data class EightKFiling(
        val accessionNumber: String,
        val filingDate: String,
        val items: String,
        val ageDays: Long,
    )

    // SEC submissions JSON projection — only the fields we read.
    private data class SubmissionsJson(val filings: FilingsObj?)
    private data class FilingsObj(val recent: RecentFilings?)
    private data class RecentFilings(
        val accessionNumber: List<String>?,
        val filingDate: List<String>?,
        val form: List<String>?,
        val items: List<String?>?,
    )

    private companion object {
        const val TAG = "SecFilings"
        const val LOOKBACK_DAYS = 2L

        /**
         * Human descriptions for the 8-K item codes that actually matter
         * to retail investors. Codes not in this table fall through to
         * just their raw number. See
         * https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany for
         * the canonical list; we cover the ones that commonly move
         * stocks.
         */
        val ITEM_DESCRIPTIONS = mapOf(
            "1.01" to "Material agreement entered",
            "1.02" to "Material agreement terminated",
            "1.03" to "Bankruptcy/receivership",
            "2.01" to "Acquisition or disposition",
            "2.02" to "Earnings released",
            "2.03" to "New material debt obligation",
            "2.04" to "Triggering event on debt",
            "2.05" to "Restructuring charges",
            "2.06" to "Material impairment",
            "3.01" to "Listing delisted",
            "3.02" to "Unregistered equity sales",
            "3.03" to "Material modification of rights",
            "4.01" to "Auditor change",
            "4.02" to "Prior financials no longer reliable",
            "5.01" to "Change in control",
            "5.02" to "Officer / director departure or appointment",
            "5.03" to "Articles / bylaws amended",
            "5.07" to "Shareholder vote results",
            "7.01" to "Reg FD disclosure",
            "8.01" to "Other events",
            "9.01" to "Financial statements and exhibits",
        )
    }
}

@Singleton
class SecFilingAlertScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SecFilingAlertPreferences,
) {
    suspend fun ensureScheduled() {
        if (prefs.enabled.first() != true) {
            cancel()
            return
        }
        val request = PeriodicWorkRequestBuilder<SecFilingAlertWorker>(2, TimeUnit.HOURS)
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
        const val UNIQUE_NAME = "finnencer-sec-filing-alert"
    }
}
