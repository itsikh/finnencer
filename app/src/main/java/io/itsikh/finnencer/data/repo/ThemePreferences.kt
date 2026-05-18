package io.itsikh.finnencer.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.ui.theme.ThemeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themePrefsDataStore by preferencesDataStore(name = "theme_preferences")

/**
 * Stores which color palette the user has picked. Persists across
 * reinstall-from-backup boundaries because it's a DataStore prefs
 * file and the prefs survive Room migrations.
 *
 * Stored as the [ThemeId.name] string so adding new themes later
 * doesn't need a migration. Unknown values silently fall back to
 * the [ThemeId.TERMINAL_PRO] default.
 */
@Singleton
class ThemePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val themeId: Flow<ThemeId> =
        context.themePrefsDataStore.data.map { p ->
            val stored = p[KEY_THEME_ID] ?: return@map DEFAULT
            runCatching { ThemeId.valueOf(stored) }.getOrDefault(DEFAULT)
        }

    suspend fun setThemeId(id: ThemeId) {
        context.themePrefsDataStore.edit { it[KEY_THEME_ID] = id.name }
    }

    companion object {
        val DEFAULT = ThemeId.TERMINAL_PRO

        private val KEY_THEME_ID = stringPreferencesKey("theme_id")
    }
}
