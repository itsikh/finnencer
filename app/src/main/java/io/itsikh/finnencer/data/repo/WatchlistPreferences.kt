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

private val Context.watchlistDataStore by preferencesDataStore(name = "watchlist_view")

/**
 * Persisted sort selection for the Stocks (watchlist) tab.
 *
 * The option is stored as its enum name; unknown names (e.g. an enum
 * value removed in a later release) fall back to the default rather
 * than crash on startup. Direction (asc/desc) is a separate boolean so
 * each option keeps its own preferred direction in memory across
 * relaunches.
 */
@Singleton
class WatchlistPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Stored sort option name. Defaults to "DEFAULT" (user's curated order). */
    val sortOptionName: Flow<String> = context.watchlistDataStore.data
        .map { it[KEY_SORT_OPTION] ?: "DEFAULT" }

    val sortDescending: Flow<Boolean> = context.watchlistDataStore.data
        .map { it[KEY_SORT_DESC] ?: false }

    suspend fun setSortOption(name: String) {
        context.watchlistDataStore.edit { it[KEY_SORT_OPTION] = name }
    }

    suspend fun setSortDescending(descending: Boolean) {
        context.watchlistDataStore.edit { it[KEY_SORT_DESC] = descending }
    }

    private companion object {
        val KEY_SORT_OPTION = stringPreferencesKey("sort_option")
        val KEY_SORT_DESC = booleanPreferencesKey("sort_descending")
    }
}
