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
import io.itsikh.finnencer.core.notifications.SignalNotifier
import io.itsikh.finnencer.data.api.FinnhubInsiderTransaction
import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.repo.InsiderAlertPreferences
import io.itsikh.finnencer.data.repo.WatchlistRepository
import io.itsikh.finnencer.logging.AppLogger as Log
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Periodically polls Finnhub's `/stock/insider-transactions` for each
 * watched ticker and pushes a notification when a meaningful Code-P
 * (open-market purchase) lands.
 *
 * Signal logic — the literature is consistent that open-market buys
 * by insiders are the strongest insider signal (Lakonishok & Lee 2001,
 * Cohen et al.). We filter on:
 *  - transactionCode == "P" (open-market purchase)
 *  - share * transactionPrice >= [MIN_VALUE_USD] — filters out
 *    nominal symbolic purchases
 *  - filingDate within the last [LOOKBACK_DAYS] — anything older the
 *    user can find in OpenInsider; we focus on what's actionable now.
 *
 * Each notified transaction is fingerprinted via
 * [InsiderAlertPreferences.notifiedTxKeys] so the every-12h schedule
 * doesn't repeatedly fire for the same Form 4 line.
 */
@HiltWorker
class InsiderAlertWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val watchlist: WatchlistRepository,
    private val finnhub: FinnhubService,
    private val notifier: SignalNotifier,
    private val prefs: InsiderAlertPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        scan()
        Result.success()
    } catch (t: Throwable) {
        Log.e(TAG, "insider scan failed", t)
        Result.success()
    }

    private suspend fun scan() {
        if (prefs.enabled.first() != true) return
        val tickers = watchlist.observeAll().first()
        if (tickers.isEmpty()) return

        val to = LocalDate.now()
        val from = to.minusDays(LOOKBACK_DAYS)
        val already = prefs.notifiedTxKeys.first().toMutableSet()
        val newlyNotified = mutableListOf<String>()

        for (t in tickers) {
            val symbol = t.symbol
            val respResult = runCatching {
                finnhub.insiderTransactions(
                    symbol = symbol,
                    fromIso = from.toString(),
                    toIso = to.toString(),
                )
            }
            val resp = respResult.getOrNull()
            if (resp == null) {
                Log.w(TAG, "insider fetch failed for $symbol: ${respResult.exceptionOrNull()?.message}")
                continue
            }
            val buys = resp.data
                .filter { it.transactionCode?.uppercase() == "P" }
                .filter { isMaterial(it) }

            for (tx in buys) {
                val key = fingerprint(symbol, tx)
                if (key in already) continue
                val value = (tx.share ?: 0L) * (tx.transactionPrice ?: 0.0)
                val valueStr = formatDollars(value)
                val name = tx.name?.ifBlank { null } ?: "Insider"
                notifier.post(
                    tag = "insider-$key",
                    title = "[$symbol] Insider purchase",
                    body = "$name bought ${formatShares(tx.share)} shares ($valueStr) on the open market.",
                    deepLink = "finnencer://ticker/$symbol".takeIf { false }
                        ?: null, // ticker deep link not yet wired; placeholder
                )
                newlyNotified.add(key)
                already.add(key)
            }
        }

        if (newlyNotified.isNotEmpty()) {
            prefs.markNotified(newlyNotified)
            Log.i(TAG, "notified ${newlyNotified.size} insider buy(s)")
        }
    }

    private fun isMaterial(tx: FinnhubInsiderTransaction): Boolean {
        val shares = tx.share ?: 0L
        val price = tx.transactionPrice ?: 0.0
        val value = shares * price
        return value >= MIN_VALUE_USD
    }

    private fun fingerprint(symbol: String, tx: FinnhubInsiderTransaction): String =
        buildString {
            append(symbol)
            append(':')
            append(tx.filingDate.orEmpty())
            append(':')
            append(tx.transactionDate.orEmpty())
            append(':')
            append(tx.name.orEmpty().replace('|', '/'))
            append(':')
            append(tx.share ?: 0)
            append(':')
            append(tx.transactionCode.orEmpty())
        }

    private fun formatDollars(value: Double): String = when {
        value >= 1_000_000.0 -> String.format("$%.2fM", value / 1_000_000.0)
        value >= 1_000.0 -> String.format("$%.0fk", value / 1_000.0)
        else -> String.format("$%.0f", value)
    }

    private fun formatShares(s: Long?): String =
        s?.let { String.format("%,d", it) } ?: "?"

    private companion object {
        const val TAG = "InsiderAlert"
        const val LOOKBACK_DAYS = 7L
        const val MIN_VALUE_USD = 50_000.0
    }
}

@Singleton
class InsiderAlertScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: InsiderAlertPreferences,
) {
    suspend fun ensureScheduled() {
        if (prefs.enabled.first() != true) {
            cancel()
            return
        }
        val request = PeriodicWorkRequestBuilder<InsiderAlertWorker>(12, TimeUnit.HOURS)
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
        const val UNIQUE_NAME = "finnencer-insider-alert"
    }
}
