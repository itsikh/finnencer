package io.itsikh.finnencer.data.ai

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

private val Context.aiPrefsDataStore by preferencesDataStore(name = "ai_preferences")

/**
 * User-configurable model selection for each [AiUsage]. DataStore-backed
 * (not encrypted — these are preferences, not secrets).
 *
 * Resolution order at request time:
 *  1. Saved override id for the usage — matched against built-in [AiModel]
 *     entries, then against runtime-discovered models in [DiscoveredModels]
 *  2. [AiUsage.defaultModel] (always a built-in)
 *
 * Returns are wrapped in [AiModelOption] so callers don't branch on
 * builtin-vs-discovered.
 */
@Singleton
class AiPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val discovered: DiscoveredModels,
) {

    private fun keyFor(usage: AiUsage): Preferences.Key<String> =
        stringPreferencesKey("model_for_${usage.name}")

    fun observe(usage: AiUsage): Flow<AiModelOption> =
        combine(context.aiPrefsDataStore.data, discovered.observe()) { prefs, customs ->
            resolve(prefs[keyFor(usage)], customs, usage)
        }

    suspend fun get(usage: AiUsage): AiModelOption {
        val prefs = context.aiPrefsDataStore.data.first()
        val customs = discovered.snapshot()
        return resolve(prefs[keyFor(usage)], customs, usage)
    }

    suspend fun set(usage: AiUsage, model: AiModelOption) {
        context.aiPrefsDataStore.edit { prefs ->
            prefs[keyFor(usage)] = model.id
        }
    }

    suspend fun reset(usage: AiUsage) {
        context.aiPrefsDataStore.edit { prefs ->
            prefs.remove(keyFor(usage))
        }
    }

    private fun resolve(
        savedId: String?,
        customs: List<AiModelOption.Custom>,
        usage: AiUsage,
    ): AiModelOption {
        if (savedId == null) return AiModelOption.Builtin(usage.defaultModel)
        AiModel.byId(savedId)?.let { return AiModelOption.Builtin(it) }
        customs.firstOrNull { it.id == savedId }?.let { return it }
        return AiModelOption.Builtin(usage.defaultModel)
    }
}
