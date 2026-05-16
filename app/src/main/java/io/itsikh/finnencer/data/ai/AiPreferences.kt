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
 * Per [AiUsage] the user persists an ORDERED list of up to [MAX_RANK]
 * model IDs. Position 0 is the primary; positions 1..N are sequential
 * fallbacks the router tries on failure. Stored as a single comma-joined
 * string so a v1 single-id value still resolves cleanly.
 *
 * Resolution at request time produces a list of length ≥ 1; if nothing
 * was saved (or every saved id became unknown), the list is just
 * [AiUsage.defaultModel].
 */
@Singleton
class AiPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val discovered: DiscoveredModels,
) {

    private fun keyFor(usage: AiUsage): Preferences.Key<String> =
        stringPreferencesKey("model_for_${usage.name}")

    /** Ranked list flow: primary first, fallbacks after. Always non-empty. */
    fun observeRanked(usage: AiUsage): Flow<List<AiModelOption>> =
        combine(context.aiPrefsDataStore.data, discovered.observe()) { prefs, customs ->
            resolve(prefs[keyFor(usage)], customs, usage)
        }

    suspend fun getRanked(usage: AiUsage): List<AiModelOption> {
        val prefs = context.aiPrefsDataStore.data.first()
        val customs = discovered.snapshot()
        return resolve(prefs[keyFor(usage)], customs, usage)
    }

    /** Persists an ordered list (deduped, capped at [MAX_RANK]). Empty → reset to default. */
    suspend fun setRanked(usage: AiUsage, options: List<AiModelOption>) {
        val cleaned = options
            .distinctBy { it.id }
            .take(MAX_RANK)
        context.aiPrefsDataStore.edit { prefs ->
            if (cleaned.isEmpty()) {
                prefs.remove(keyFor(usage))
            } else {
                prefs[keyFor(usage)] = cleaned.joinToString(SEPARATOR) { it.id }
            }
        }
    }

    suspend fun reset(usage: AiUsage) {
        context.aiPrefsDataStore.edit { prefs -> prefs.remove(keyFor(usage)) }
    }

    private fun resolve(
        saved: String?,
        customs: List<AiModelOption.Custom>,
        usage: AiUsage,
    ): List<AiModelOption> {
        if (saved.isNullOrBlank()) return listOf(AiModelOption.Builtin(usage.defaultModel))
        val resolved = saved.split(SEPARATOR)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .mapNotNull { id ->
                AiModel.byId(id)?.let { AiModelOption.Builtin(it) }
                    ?: customs.firstOrNull { it.id == id }
            }
            .take(MAX_RANK)
            .toList()
        return resolved.ifEmpty { listOf(AiModelOption.Builtin(usage.defaultModel)) }
    }

    companion object {
        const val MAX_RANK = 3
        private const val SEPARATOR = ","
    }
}
