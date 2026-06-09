package io.itsikh.finnencer.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.morningBriefDataStore by preferencesDataStore(name = "morning_brief")

/**
 * Settings for the personalized daily morning brief — an AI-generated
 * podcast (~5–15 min, scaled to how much big news there is) that recaps
 * the user's watchlist overnight.
 *
 * Stored as raw prefs because we don't need history / migration; if a
 * later release changes the default time, existing users just keep
 * whatever they had configured.
 */
@Singleton
class MorningBriefPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Config(
        val enabled: Boolean,
        val hourLocal: Int,
        val minuteLocal: Int,
        val weekdaysOnly: Boolean,
    )

    val enabled: Flow<Boolean> = context.morningBriefDataStore.data
        .map { it[KEY_ENABLED] ?: false }

    val hourLocal: Flow<Int> = context.morningBriefDataStore.data
        .map { (it[KEY_HOUR] ?: DEFAULT_HOUR).coerceIn(0, 23) }

    val minuteLocal: Flow<Int> = context.morningBriefDataStore.data
        .map { (it[KEY_MINUTE] ?: DEFAULT_MINUTE).coerceIn(0, 59) }

    val weekdaysOnly: Flow<Boolean> = context.morningBriefDataStore.data
        .map { it[KEY_WEEKDAYS_ONLY] ?: true }

    val config: Flow<Config> = combine(enabled, hourLocal, minuteLocal, weekdaysOnly) { e, h, m, w ->
        Config(enabled = e, hourLocal = h, minuteLocal = m, weekdaysOnly = w)
    }

    suspend fun setEnabled(value: Boolean) {
        context.morningBriefDataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun setHour(hour: Int) {
        context.morningBriefDataStore.edit { it[KEY_HOUR] = hour.coerceIn(0, 23) }
    }

    suspend fun setMinute(minute: Int) {
        context.morningBriefDataStore.edit { it[KEY_MINUTE] = minute.coerceIn(0, 59) }
    }

    suspend fun setWeekdaysOnly(value: Boolean) {
        context.morningBriefDataStore.edit { it[KEY_WEEKDAYS_ONLY] = value }
    }

    private companion object {
        // Default to 05:00 local — the brief is built overnight so it's
        // ready before the user is awake (device-local time, so it follows
        // them when travelling).
        const val DEFAULT_HOUR = 5
        const val DEFAULT_MINUTE = 0

        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_HOUR = intPreferencesKey("hour_local")
        val KEY_MINUTE = intPreferencesKey("minute_local")
        val KEY_WEEKDAYS_ONLY = booleanPreferencesKey("weekdays_only")
    }
}
