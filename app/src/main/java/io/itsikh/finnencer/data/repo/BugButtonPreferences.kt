package io.itsikh.finnencer.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.bugButtonDataStore by preferencesDataStore(name = "bug_button")

/**
 * Persisted on-screen position of the draggable [ui.components.FloatingBugButton].
 *
 * Stored as raw pixel offsets — the same unit the composable uses for
 * its `offset { IntOffset(...) }` modifier. Both keys must be present
 * to count as "set"; if either is missing we treat the position as
 * unset and the button falls back to its computed default.
 */
@Singleton
class BugButtonPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Emits `Pair(x, y)` in pixels, or null when the user has never moved the button. */
    val position: Flow<Pair<Float, Float>?> = context.bugButtonDataStore.data.map { prefs ->
        val x = prefs[KEY_X]
        val y = prefs[KEY_Y]
        if (x != null && y != null) x to y else null
    }

    suspend fun setPosition(x: Float, y: Float) {
        context.bugButtonDataStore.edit { prefs ->
            prefs[KEY_X] = x
            prefs[KEY_Y] = y
        }
    }

    private companion object {
        val KEY_X = floatPreferencesKey("offset_x_px")
        val KEY_Y = floatPreferencesKey("offset_y_px")
    }
}
