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

    /** Returns the zero-padded 10-digit CIK for [tickerSymbol], or null if unknown. */
    suspend fun resolve(tickerSymbol: String): String? {
        val map = ensureLoaded() ?: return null
        return map[tickerSymbol.uppercase()]
    }

    private suspend fun ensureLoaded(): Map<String, String>? {
        cache?.let { return it }
        mutex.withLock {
            cache?.let { return it }
            val raw = runCatching { service.tickerCikMap() }
                .onFailure { AppLogger.e(TAG, "ticker->CIK map fetch failed", it) }
                .getOrNull() ?: return null
            cache = parse(raw)
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

    private companion object { const val TAG = "EdgarCikLookup" }
}
