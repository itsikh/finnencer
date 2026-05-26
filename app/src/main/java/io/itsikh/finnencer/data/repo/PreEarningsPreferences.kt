package io.itsikh.finnencer.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.preEarningsDataStore by preferencesDataStore(name = "pre_earnings_briefing")

/**
 * State for the pre-earnings auto-briefing feature.
 *
 * - [enabled]: gate the feature on/off.
 * - [briefedEventIdsCsv]: comma-separated list of EarningsEvent ids that
 *   we've already auto-spawned a brief for. Keeps the worker idempotent
 *   across re-runs (otherwise the every-6h schedule would queue a new
 *   brief on every tick within the 24h window).
 */
@Singleton
class PreEarningsPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val enabled: Flow<Boolean> = context.preEarningsDataStore.data
        .map { it[KEY_ENABLED] ?: false }

    val briefedEventIds: Flow<Set<Long>> = context.preEarningsDataStore.data
        .map { prefs ->
            val csv = prefs[KEY_BRIEFED_IDS_CSV].orEmpty()
            if (csv.isBlank()) emptySet()
            else csv.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
        }

    suspend fun setEnabled(value: Boolean) {
        context.preEarningsDataStore.edit { it[KEY_ENABLED] = value }
    }

    /** Mark [eventId] as briefed so the worker won't spawn another report
     *  for the same event on the next tick. */
    suspend fun markBriefed(eventId: Long) {
        context.preEarningsDataStore.edit { prefs ->
            val current = prefs[KEY_BRIEFED_IDS_CSV].orEmpty()
            val ids = current.split(',').mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
            ids.add(eventId)
            // Cap memory growth — keep only the most recent 200 ids.
            // After 200 cycles older entries fall off the list; by then
            // the corresponding event is months in the past and won't
            // re-trigger the 24h window anyway.
            val capped = ids.toList().takeLast(200)
            prefs[KEY_BRIEFED_IDS_CSV] = capped.joinToString(",")
        }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_BRIEFED_IDS_CSV = stringPreferencesKey("briefed_event_ids_csv")
    }
}
