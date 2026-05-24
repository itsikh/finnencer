package io.itsikh.finnencer.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.retentionDataStore by preferencesDataStore(name = "retention_preferences")

/**
 * How long [data.dao.NewsDao] news rows and [data.dao.ApiUsageDao] cost
 * rows are kept before [core.work.SyncWorker] prunes them.
 *
 * Defaults: 60 days for news (long enough that the ticker feed still
 * has context after a week off; short enough that DB size stays modest
 * with 4 providers × 10 tickers × 15-min sync cadence). 180 days for
 * API usage (so the cost meter can show year-to-date roll-ups without
 * holding token-level rows forever).
 *
 * Articles and podcasts the user has acted on are NOT controlled here —
 * the underlying tables are the news cache only. Queue items, summaries,
 * earnings reports and podcasts persist independently.
 */
@Singleton
class RetentionPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val newsRetentionDays: Flow<Int> = context.retentionDataStore.data.map { p ->
        (p[KEY_NEWS_DAYS] ?: DEFAULT_NEWS_DAYS).coerceIn(MIN_DAYS, MAX_DAYS)
    }

    val apiUsageRetentionDays: Flow<Int> = context.retentionDataStore.data.map { p ->
        (p[KEY_USAGE_DAYS] ?: DEFAULT_USAGE_DAYS).coerceIn(MIN_DAYS, MAX_DAYS)
    }

    suspend fun getNewsRetentionDays(): Int =
        context.retentionDataStore.data.first()[KEY_NEWS_DAYS]?.coerceIn(MIN_DAYS, MAX_DAYS)
            ?: DEFAULT_NEWS_DAYS

    suspend fun getApiUsageRetentionDays(): Int =
        context.retentionDataStore.data.first()[KEY_USAGE_DAYS]?.coerceIn(MIN_DAYS, MAX_DAYS)
            ?: DEFAULT_USAGE_DAYS

    suspend fun setNewsRetentionDays(days: Int) {
        val sanitized = days.coerceIn(MIN_DAYS, MAX_DAYS)
        context.retentionDataStore.edit { it[KEY_NEWS_DAYS] = sanitized }
    }

    suspend fun setApiUsageRetentionDays(days: Int) {
        val sanitized = days.coerceIn(MIN_DAYS, MAX_DAYS)
        context.retentionDataStore.edit { it[KEY_USAGE_DAYS] = sanitized }
    }

    companion object {
        const val MIN_DAYS = 7
        const val MAX_DAYS = 730 // ~2 years
        const val DEFAULT_NEWS_DAYS = 60
        const val DEFAULT_USAGE_DAYS = 180

        /** Preset steps surfaced in Settings. */
        val NEWS_PRESETS = listOf(14, 30, 60, 90, 180, 365)
        val USAGE_PRESETS = listOf(30, 90, 180, 365, 730)

        private val KEY_NEWS_DAYS = intPreferencesKey("news_retention_days")
        private val KEY_USAGE_DAYS = intPreferencesKey("api_usage_retention_days")
    }
}
