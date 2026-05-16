package io.itsikh.finnencer.data.providers

import com.google.gson.Gson
import io.itsikh.finnencer.data.api.SecEdgarService
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves ticker→CIK via SEC's daily-refreshed `company_tickers.json`.
 *
 * The blob is ~1MB. Fetched lazily on first use and cached in-process for the
 * lifetime of the app (next launch refetches — good enough for this use case
 * since the file changes at most once per day on the SEC side).
 */
@Singleton
class EdgarCikLookup @Inject constructor(
    private val service: SecEdgarService,
    private val gson: Gson,
) {

    private val mutex = Mutex()

    @Volatile
    private var cache: Map<String, String>? = null

    /** Timestamp of the most recent failed fetch; used to back off retries. */
    @Volatile
    private var lastFailureMillis: Long = 0L

    /** Returns the zero-padded 10-digit CIK for [tickerSymbol], or null if unknown. */
    suspend fun resolve(tickerSymbol: String): String? {
        val map = ensureLoaded() ?: return null
        return map[tickerSymbol.uppercase()]
    }

    private suspend fun ensureLoaded(): Map<String, String>? {
        cache?.let { return it }
        // Back off: if the last attempt failed less than [FAILURE_BACKOFF_MS]
        // ago, return null without spamming the network. SEC will hit a 403
        // and we used to retry continuously (every scoring batch + every
        // sync provider call for every ticker), producing dozens of log
        // errors per minute.
        val now = System.currentTimeMillis()
        if (lastFailureMillis != 0L && now - lastFailureMillis < FAILURE_BACKOFF_MS) {
            return null
        }
        mutex.withLock {
            cache?.let { return it }
            if (lastFailureMillis != 0L && System.currentTimeMillis() - lastFailureMillis < FAILURE_BACKOFF_MS) {
                return null
            }
            val raw = runCatching { service.tickerCikMap() }
                .onFailure {
                    lastFailureMillis = System.currentTimeMillis()
                    AppLogger.e(TAG, "ticker->CIK map fetch failed (cached for ${FAILURE_BACKOFF_MS / 60_000}m)", it)
                }
                .getOrNull() ?: return null
            cache = parse(raw)
            lastFailureMillis = 0L
            AppLogger.i(TAG, "ticker->CIK map loaded: ${cache?.size ?: 0} symbols")
        }
        return cache
    }

    private fun parse(raw: String): Map<String, String> {
        // Structure: {"0":{"cik_str":320193,"ticker":"AAPL","title":"Apple Inc."}, ...}
        val obj = runCatching {
            gson.fromJson(raw, Map::class.java) as? Map<*, *>
        }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, String>(obj.size)
        for ((_, v) in obj) {
            val row = v as? Map<*, *> ?: continue
            val ticker = (row["ticker"] as? String)?.uppercase() ?: continue
            // cik_str sometimes a Double from Gson; round to Long then zero-pad to 10
            val cikLong = when (val c = row["cik_str"]) {
                is Number -> c.toLong()
                is String -> c.toLongOrNull() ?: continue
                else -> continue
            }
            out[ticker] = cikLong.toString().padStart(10, '0')
        }
        return out
    }

    private companion object {
        const val TAG = "EdgarCikLookup"
        const val FAILURE_BACKOFF_MS = 15L * 60 * 1000 // 15 min
    }
}
