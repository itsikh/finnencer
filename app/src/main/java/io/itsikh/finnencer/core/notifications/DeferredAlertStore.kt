package io.itsikh.finnencer.core.notifications

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deferredAlertsDataStore by preferencesDataStore(name = "deferred_alerts")

/**
 * Registry of alerts deferred by quiet hours — and ONLY by quiet hours.
 *
 * [AlertNotifier] records an (article, ticker) pair here the moment the
 * quiet-hours gate suppresses it, and removes it on any terminal verdict
 * (posted, or suppressed by threshold/mute/cap/cluster at delivery time).
 * Inferring "deferred" from "no notifications-log row" instead would
 * resurrect alerts that were deliberately dropped by cluster dedup, the
 * daily cap, or a mute once those windows lapse — stale duplicates.
 *
 * Entries are `articleId|ticker|deferredAtMillis` joined by commas
 * (article ids are 32-char hex, ticker symbols are A-Z — both separator
 * safe), capped at [MAX_ENTRIES] newest-first.
 */
@Singleton
class DeferredAlertStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Entry(
        val articleId: String,
        val tickerSymbol: String,
        val deferredAtMillis: Long,
    )

    suspend fun add(articleId: String, tickerSymbol: String, nowMillis: Long) {
        context.deferredAlertsDataStore.edit { prefs ->
            val current = parse(prefs[KEY] ?: "")
                .filterNot { it.articleId == articleId && it.tickerSymbol == tickerSymbol }
            val updated = (current + Entry(articleId, tickerSymbol, nowMillis))
                .sortedByDescending { it.deferredAtMillis }
                .take(MAX_ENTRIES)
            prefs[KEY] = serialize(updated)
        }
    }

    /** Entries younger than [maxAgeMillis]; expired ones are pruned in place. */
    suspend fun entries(maxAgeMillis: Long, nowMillis: Long): List<Entry> {
        val cutoff = nowMillis - maxAgeMillis
        val all = parse(context.deferredAlertsDataStore.data.first()[KEY] ?: "")
        val live = all.filter { it.deferredAtMillis >= cutoff }
        if (live.size != all.size) {
            context.deferredAlertsDataStore.edit { it[KEY] = serialize(live) }
        }
        return live
    }

    suspend fun remove(articleId: String, tickerSymbol: String) {
        context.deferredAlertsDataStore.edit { prefs ->
            val remaining = parse(prefs[KEY] ?: "")
                .filterNot { it.articleId == articleId && it.tickerSymbol == tickerSymbol }
            prefs[KEY] = serialize(remaining)
        }
    }

    private fun parse(raw: String): List<Entry> =
        raw.split(',').mapNotNull { token ->
            val parts = token.split('|')
            if (parts.size != 3) return@mapNotNull null
            val ts = parts[2].toLongOrNull() ?: return@mapNotNull null
            Entry(parts[0], parts[1], ts)
        }

    private fun serialize(entries: List<Entry>): String =
        entries.joinToString(",") { "${it.articleId}|${it.tickerSymbol}|${it.deferredAtMillis}" }

    private companion object {
        val KEY = stringPreferencesKey("entries")
        const val MAX_ENTRIES = 200
    }
}
