package io.itsikh.finnencer.data.ai

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.promptPrefsDataStore by preferencesDataStore(name = "ai_prompt_preferences")

/**
 * User-editable "extra instructions" appended to the built-in system
 * prompt for each [AiUsage]. The defaults live in code (the various
 * `SYSTEM_PROMPT` / `BRIEF_PROMPT` / `DEEP_PROMPT` constants); this
 * layer lets the user nudge them — e.g. "always keep the brief under
 * 250 words" or "for podcasts, keep each turn to under 30 seconds".
 *
 * The custom text is appended, not substituted, so the structural
 * sections in the default prompts (numbered headings, length budgets)
 * stay intact and the LLM still produces a parseable shape.
 */
@Singleton
class PromptPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private fun keyFor(usage: AiUsage): Preferences.Key<String> =
        stringPreferencesKey("extra_for_${usage.name}")

    fun observe(usage: AiUsage): Flow<String> =
        context.promptPrefsDataStore.data.map { it[keyFor(usage)].orEmpty() }

    suspend fun get(usage: AiUsage): String =
        context.promptPrefsDataStore.data.first()[keyFor(usage)].orEmpty()

    suspend fun set(usage: AiUsage, extra: String) {
        context.promptPrefsDataStore.edit { prefs ->
            val trimmed = extra.trim()
            if (trimmed.isEmpty()) prefs.remove(keyFor(usage)) else prefs[keyFor(usage)] = trimmed
        }
    }

    /**
     * Append [extra] (if non-blank) to [base] under a labelled section so
     * the LLM can distinguish the user's note from the structural prompt.
     * Convention reused across summarizer / scorer / report generator.
     */
    fun applyExtras(base: String, extra: String, perCallCustom: String? = null): String =
        buildString {
            append(base)
            if (extra.isNotBlank()) {
                append("\n\nUser preferences (apply faithfully):\n")
                append(extra.trim())
            }
            if (!perCallCustom.isNullOrBlank()) {
                append("\n\nAdditional instructions for this call:\n")
                append(perCallCustom.trim())
            }
        }
}
