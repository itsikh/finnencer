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

private val Context.secFilingAlertDataStore by preferencesDataStore(name = "sec_filing_alerts")

/**
 * Settings + dedup state for the 8-K material event watcher.
 *
 * Accession numbers are guaranteed-unique per filing, so they make
 * the cleanest idempotency key. Capped at 500 entries to bound memory.
 */
@Singleton
class SecFilingAlertPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val enabled: Flow<Boolean> = context.secFilingAlertDataStore.data
        .map { it[KEY_ENABLED] ?: false }

    val notifiedAccessions: Flow<Set<String>> = context.secFilingAlertDataStore.data
        .map { prefs ->
            val csv = prefs[KEY_NOTIFIED_CSV].orEmpty()
            if (csv.isBlank()) emptySet()
            else csv.split('|').filter { it.isNotBlank() }.toSet()
        }

    suspend fun setEnabled(value: Boolean) {
        context.secFilingAlertDataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun markNotified(accessions: Collection<String>) {
        if (accessions.isEmpty()) return
        context.secFilingAlertDataStore.edit { prefs ->
            val current = prefs[KEY_NOTIFIED_CSV].orEmpty()
            val existing = current.split('|').filter { it.isNotBlank() }
            val combined = (existing + accessions).distinct().takeLast(500)
            prefs[KEY_NOTIFIED_CSV] = combined.joinToString("|")
        }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_NOTIFIED_CSV = stringPreferencesKey("notified_accessions_csv")
    }
}
