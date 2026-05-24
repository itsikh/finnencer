package io.itsikh.finnencer.backup

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.AppConfig
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.entity.Ticker
import io.itsikh.finnencer.data.repo.ApiKey
import io.itsikh.finnencer.logging.AppLogger
import io.itsikh.finnencer.security.SecureKeyManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted backup of the only data the user can't reconstruct from
 * scratch:
 *  - Every configured [ApiKey] value (so the user doesn't have to
 *    re-paste 11 keys after a phone wipe).
 *  - The watchlist (tickers + per-ticker notification settings).
 *
 * News articles, AI summaries, earnings reports, podcasts, queue, cost
 * meter rows etc. are intentionally NOT backed up — they're either
 * rebuildable from a fresh sync or large/transient and not worth the
 * file size.
 *
 * The whole payload is encrypted with the user's password via
 * [BaseSettingsBackupManager] (PBKDF2WithHmacSHA256 100k iter +
 * AES-256-GCM). A backup file alone is restorable on any device that
 * has the password — that's the point: this exists so a lost or wiped
 * phone can be brought back to working state.
 *
 * The output filename uses [AppConfig.APP_NAME] so it's recognizable
 * in the user's file picker.
 */
@Singleton
class FinnencerBackupManager @Inject constructor(
    @ApplicationContext context: Context,
    private val secureKeyManager: SecureKeyManager,
    private val tickerDao: TickerDao,
) : BaseSettingsBackupManager(context, AppConfig.APP_NAME) {

    /** Count of records included in the most recent collect/restore.
     *  Surfaced in the Settings UI as "Last export OK · N items". */
    data class Counts(val keys: Int, val tickers: Int) {
        val total: Int get() = keys + tickers
    }

    /** Snapshot of what the last export wrote / last restore read. The
     *  ViewModel reads this immediately after the suspending call
     *  returns to compute the item count. Single-writer (the manager
     *  itself) so volatile is enough — no shared mutation race. */
    @Volatile var lastCounts: Counts = Counts(0, 0)
        private set

    override suspend fun collectSettingsData(): SettingsData {
        val keysObj = JsonObject()
        for (key in ApiKey.entries) {
            val v = secureKeyManager.getKey(key.alias)
            if (!v.isNullOrBlank()) {
                keysObj.addProperty(key.alias, v)
            }
        }

        val tickersArr = JsonArray()
        val tickers = tickerDao.getAll()
        for (t in tickers) {
            tickersArr.add(tickerToJson(t))
        }

        val data = JsonObject().apply {
            add("api_keys", keysObj)
            add("tickers", tickersArr)
        }

        lastCounts = Counts(keys = keysObj.size(), tickers = tickers.size)
        AppLogger.i(TAG, "collected backup: keys=${lastCounts.keys} tickers=${lastCounts.tickers}")
        return SettingsData(version = BACKUP_VERSION, data = data)
    }

    override suspend fun restoreSettingsData(data: JsonObject) {
        var keyCount = 0
        data.getAsJsonObject("api_keys")?.let { keys ->
            for ((alias, valueElement) in keys.entrySet()) {
                val value = valueElement?.takeIf { !it.isJsonNull }?.asString
                if (!value.isNullOrBlank()) {
                    secureKeyManager.saveKey(alias, value)
                    keyCount++
                }
            }
        }

        var tickerCount = 0
        data.getAsJsonArray("tickers")?.let { arr ->
            for (element in arr) {
                val obj = element?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val ticker = tickerFromJson(obj) ?: continue
                tickerDao.upsert(ticker)
                tickerCount++
            }
        }

        lastCounts = Counts(keys = keyCount, tickers = tickerCount)
        AppLogger.i(TAG, "restored backup: keys=$keyCount tickers=$tickerCount")
    }

    private fun tickerToJson(t: Ticker): JsonObject = JsonObject().apply {
        addProperty("symbol", t.symbol)
        addProperty("name", t.name)
        addProperty("exchange", t.exchange)
        t.sector?.let { addProperty("sector", it) }
        t.cik?.let { addProperty("cik", it) }
        t.logoUrl?.let { addProperty("logo_url", it) }
        addProperty("watchlist_order", t.watchlistOrder)
        addProperty("notification_threshold", t.notificationThreshold)
        addProperty("daily_notification_cap", t.dailyNotificationCap)
        t.mutedUntilMillis?.let { addProperty("muted_until_millis", it) }
        addProperty("quiet_hours_start_minute", t.quietHoursStartMinute)
        addProperty("quiet_hours_end_minute", t.quietHoursEndMinute)
        addProperty("added_at_millis", t.addedAtMillis)
    }

    private fun tickerFromJson(obj: JsonObject): Ticker? {
        val symbol = obj["symbol"]?.takeIf { !it.isJsonNull }?.asString ?: return null
        val name = obj["name"]?.takeIf { !it.isJsonNull }?.asString ?: return null
        val exchange = obj["exchange"]?.takeIf { !it.isJsonNull }?.asString ?: return null
        return Ticker(
            symbol = symbol,
            name = name,
            exchange = exchange,
            sector = obj["sector"]?.takeIf { !it.isJsonNull }?.asString,
            cik = obj["cik"]?.takeIf { !it.isJsonNull }?.asString,
            logoUrl = obj["logo_url"]?.takeIf { !it.isJsonNull }?.asString,
            watchlistOrder = obj["watchlist_order"]?.takeIf { !it.isJsonNull }?.asInt ?: 0,
            notificationThreshold = obj["notification_threshold"]?.takeIf { !it.isJsonNull }?.asInt ?: 8,
            dailyNotificationCap = obj["daily_notification_cap"]?.takeIf { !it.isJsonNull }?.asInt ?: 5,
            mutedUntilMillis = obj["muted_until_millis"]?.takeIf { !it.isJsonNull }?.asLong,
            quietHoursStartMinute = obj["quiet_hours_start_minute"]?.takeIf { !it.isJsonNull }?.asInt ?: (23 * 60),
            quietHoursEndMinute = obj["quiet_hours_end_minute"]?.takeIf { !it.isJsonNull }?.asInt ?: (6 * 60),
            addedAtMillis = obj["added_at_millis"]?.takeIf { !it.isJsonNull }?.asLong
                ?: System.currentTimeMillis(),
        )
    }

    private companion object {
        const val TAG = "FinnencerBackupManager"
        const val BACKUP_VERSION = 1
    }
}
