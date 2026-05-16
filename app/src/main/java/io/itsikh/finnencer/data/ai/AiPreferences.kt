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

private val Context.aiPrefsDataStore by preferencesDataStore(name = "ai_preferences")

/**
 * User-configurable model selection for each [AiUsage]. DataStore-backed
 * (not encrypted — these are preferences, not secrets).
 *
 * Resolution order at request time:
 *  1. Saved override for the usage (if any)
 *  2. [AiUsage.defaultModel]
 */
@Singleton
class AiPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private fun keyFor(usage: AiUsage): Preferences.Key<String> =
        stringPreferencesKey("model_for_${usage.name}")

    fun observe(usage: AiUsage): Flow<AiModel> = context.aiPrefsDataStore.data.map { prefs ->
        AiModel.byId(prefs[keyFor(usage)]) ?: usage.defaultModel
    }

    suspend fun get(usage: AiUsage): AiModel {
        val prefs = context.aiPrefsDataStore.data.first()
        return AiModel.byId(prefs[keyFor(usage)]) ?: usage.defaultModel
    }

    suspend fun set(usage: AiUsage, model: AiModel) {
        context.aiPrefsDataStore.edit { prefs ->
            prefs[keyFor(usage)] = model.id
        }
    }

    suspend fun reset(usage: AiUsage) {
        context.aiPrefsDataStore.edit { prefs ->
            prefs.remove(keyFor(usage))
        }
    }
}
