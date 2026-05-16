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

private val Context.feedPrefsDataStore by preferencesDataStore(name = "feed_preferences")

/**
 * User-tunable thresholds for what shows up in the news feed.
 *
 * `minScoreFloor` is the global default: any article whose AI score (or
 * manual override) is strictly less than this value is hidden entirely
 * from per-ticker feeds. Default = 5.
 */
@Singleton
class FeedPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val minScoreFloor: Flow<Int> = context.feedPrefsDataStore.data.map { p ->
        p[KEY_MIN_SCORE] ?: DEFAULT_MIN
    }

    suspend fun getMinScoreFloor(): Int =
        context.feedPrefsDataStore.data.first()[KEY_MIN_SCORE] ?: DEFAULT_MIN

    suspend fun setMinScoreFloor(value: Int) {
        context.feedPrefsDataStore.edit { it[KEY_MIN_SCORE] = value.coerceIn(0, 10) }
    }

    private companion object {
        val KEY_MIN_SCORE = intPreferencesKey("min_score_floor")
        const val DEFAULT_MIN = 5
    }
}
