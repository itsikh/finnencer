package io.itsikh.finnencer.ui.screens.reader

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.readerDataStore by preferencesDataStore(name = "reader_preferences")

/**
 * Persists Reader Mode preferences (font size + theme) across app sessions
 * so the user doesn't reconfigure every time they open a summary.
 */
@Singleton
class ReaderPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val fontStepKey = intPreferencesKey("font_step")
    private val themeKey = stringPreferencesKey("theme")

    val fontStep: Flow<Int> = context.readerDataStore.data.map { it[fontStepKey] ?: DEFAULT_FONT_STEP }
    val theme: Flow<ReaderTheme> = context.readerDataStore.data.map {
        runCatching { ReaderTheme.valueOf(it[themeKey] ?: ReaderTheme.DARK.name) }
            .getOrElse { ReaderTheme.DARK }
    }

    suspend fun setFontStep(step: Int) {
        context.readerDataStore.edit { it[fontStepKey] = step.coerceIn(0, FONT_STEPS.lastIndex) }
    }
    suspend fun setTheme(t: ReaderTheme) {
        context.readerDataStore.edit { it[themeKey] = t.name }
    }

    companion object {
        /** Body font sizes in sp — chosen to land at 60–75 chars per line on a 360dp phone. */
        val FONT_STEPS = listOf(16, 18, 20, 22, 24, 26)
        const val DEFAULT_FONT_STEP = 2 // 20sp
    }
}

enum class ReaderTheme { DARK, SEPIA, LIGHT }
