package io.itsikh.finnencer.data.ai

import android.content.Context
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.discoveredModelsStore by preferencesDataStore(name = "discovered_models")

/**
 * Persists user-enabled custom model IDs discovered via Settings → AI →
 * Discover. Each entry is encoded as pipe-delimited `id|displayName|provider|tier`
 * to fit in a Set<String> preference without pulling in JSON.
 *
 * Today this only surfaces models a user enabled by hand — there's no
 * automatic enrollment — so the set stays small (single digits typical).
 */
@Singleton
class DiscoveredModels @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val ENABLED = stringSetPreferencesKey("enabled_v1")

    fun observe(): Flow<List<AiModelOption.Custom>> = context.discoveredModelsStore.data.map { p ->
        (p[ENABLED] ?: emptySet()).mapNotNull { decode(it) }
    }

    suspend fun snapshot(): List<AiModelOption.Custom> =
        (context.discoveredModelsStore.data.first()[ENABLED] ?: emptySet())
            .mapNotNull { decode(it) }

    suspend fun add(opt: AiModelOption.Custom) {
        context.discoveredModelsStore.edit { p ->
            val curr = (p[ENABLED] ?: emptySet()).toMutableSet()
            curr.removeAll { decode(it)?.id == opt.id }
            curr.add(encode(opt))
            p[ENABLED] = curr
        }
    }

    suspend fun remove(id: String) {
        context.discoveredModelsStore.edit { p ->
            val curr = (p[ENABLED] ?: emptySet()).toMutableSet()
            curr.removeAll { decode(it)?.id == id }
            p[ENABLED] = curr
        }
    }

    suspend fun byId(id: String): AiModelOption.Custom? =
        snapshot().firstOrNull { it.id == id }

    private fun encode(opt: AiModelOption.Custom): String =
        "${opt.id}|${opt.displayName}|${opt.provider.name}|${opt.tier.name}"

    private fun decode(raw: String): AiModelOption.Custom? {
        val parts = raw.split("|")
        if (parts.size != 4) return null
        val provider = runCatching { AiProvider.valueOf(parts[2]) }.getOrNull() ?: return null
        val tier = runCatching { AiTier.valueOf(parts[3]) }.getOrNull() ?: return null
        return AiModelOption.Custom(
            id = parts[0],
            displayName = parts[1],
            provider = provider,
            tier = tier,
        )
    }
}
