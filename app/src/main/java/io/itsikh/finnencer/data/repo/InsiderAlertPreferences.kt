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

private val Context.insiderAlertDataStore by preferencesDataStore(name = "insider_alerts")

/**
 * Settings + idempotency state for the insider-trading alerts worker.
 *
 * The notifiedTxKeys set keeps a fingerprint of every transaction
 * we've notified on so the periodic worker doesn't re-notify for the
 * same Form 4 line item. Each fingerprint is
 * `"symbol:filingDate:name:share:transactionCode"` — stable across
 * Finnhub's response normalization (the API doesn't expose a stable
 * id per line).
 */
@Singleton
class InsiderAlertPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val enabled: Flow<Boolean> = context.insiderAlertDataStore.data
        .map { it[KEY_ENABLED] ?: false }

    val notifiedTxKeys: Flow<Set<String>> = context.insiderAlertDataStore.data
        .map { prefs ->
            val csv = prefs[KEY_NOTIFIED_CSV].orEmpty()
            if (csv.isBlank()) emptySet()
            else csv.split('|').filter { it.isNotBlank() }.toSet()
        }

    suspend fun setEnabled(value: Boolean) {
        context.insiderAlertDataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun markNotified(keys: Collection<String>) {
        if (keys.isEmpty()) return
        context.insiderAlertDataStore.edit { prefs ->
            val current = prefs[KEY_NOTIFIED_CSV].orEmpty()
            val existing = current.split('|').filter { it.isNotBlank() }
            val combined = (existing + keys).distinct()
            // Cap at the most recent 500 — older fingerprints can no
            // longer match within the 30-day look-back window anyway.
            val capped = combined.takeLast(500)
            prefs[KEY_NOTIFIED_CSV] = capped.joinToString("|")
        }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_NOTIFIED_CSV = stringPreferencesKey("notified_tx_keys_csv")
    }
}
