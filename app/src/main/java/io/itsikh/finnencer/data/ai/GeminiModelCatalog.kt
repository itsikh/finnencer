package io.itsikh.finnencer.data.ai

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.data.api.GeminiModelInfo
import io.itsikh.finnencer.data.api.GeminiService
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.geminiCatalogStore by preferencesDataStore(name = "gemini_catalog")

/**
 * Resolves the symbolic [AiModel.GEMINI_PRO_LATEST] to a concrete Gemini
 * Pro model id, discovered from Google's ListModels endpoint.
 *
 * ## Why
 *
 * A pinned id goes stale. `claude-haiku-4-5-20251001` sat hardcoded in
 * two places until it was retired from the catalog, and a pinned
 * `gemini-3.1-pro` would age the same way — silently, since an older Pro
 * keeps answering, just less well. Pinning matters most exactly where
 * this is used: the podcast validator's cross-provider fallback, which
 * only runs when the primary already failed and so gets little day-to-day
 * scrutiny.
 *
 * ## Behaviour
 *
 * Result is cached in DataStore for [TTL_MS]; a miss triggers one
 * ListModels call. Every failure path falls back to
 * [AiModel.GEMINI_3_1_PRO]'s id, so callers always receive something
 * sendable — the sentinel id itself is never put on the wire.
 */
@Singleton
class GeminiModelCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: GeminiService,
) {

    private val refreshLock = Mutex()

    /**
     * Concrete id for the newest usable Gemini Pro. Never returns the
     * sentinel id.
     */
    suspend fun latestProId(): String = resolve().id

    /**
     * Output-token ceiling for [modelId], when known from discovery.
     *
     * Null means "no information" — callers should send their own budget
     * unchanged rather than guessing a limit. Only the discovered Pro is
     * tracked; that's the model this class chose on the caller's behalf,
     * so it's the one whose limits the caller had no chance to check.
     */
    suspend fun outputLimitFor(modelId: String): Int? {
        val cached = readCache() ?: return null
        return if (cached.id == modelId) cached.outputLimit else null
    }

    private suspend fun resolve(): Resolved {
        readCache()?.takeIf { it.isFresh() }?.let { return it }

        // Single-flight: several usages can resolve concurrently (the
        // validator and a rescue attempt, say) and there's no reason to
        // spend more than one ListModels call on it.
        return refreshLock.withLock {
            readCache()?.takeIf { it.isFresh() }?.let { return@withLock it }

            val discovered = try {
                discoverLatestPro()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // MUST propagate. runCatching here would swallow it and
                // report "discovery failed", leaving a cancelled job
                // (user hit Cancel on the podcast) running on to use a
                // fallback model nobody is waiting for.
                throw ce
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Gemini ListModels failed while resolving latest Pro: ${t.message}")
                null
            }

            if (discovered == null) {
                // Cache the FALLBACK too, with a shorter TTL, so a
                // persistently failing key doesn't re-issue ListModels on
                // every single call.
                val stamp = System.currentTimeMillis() - (TTL_MS - FAILURE_RETRY_MS)
                write(FALLBACK_ID, outputLimit = null, checkedAt = stamp)
                return@withLock Resolved(FALLBACK_ID, null, stamp)
            }
            AppLogger.i(
                TAG,
                "latest Gemini Pro resolved to ${discovered.id}" +
                    (discovered.outputLimit?.let { " (output limit $it)" } ?: ""),
            )
            val now = System.currentTimeMillis()
            write(discovered.id, discovered.outputLimit, now)
            Resolved(discovered.id, discovered.outputLimit, now)
        }
    }

    private suspend fun discoverLatestPro(): Resolved? {
        val models = service.listModels(deadlineSeconds = LIST_DEADLINE_S.toString()).models
        val pro = models
            .filter { isUsableTextModel(it) }
            .mapNotNull { info ->
                val id = (info.name ?: return@mapNotNull null).removePrefix("models/")
                if ("pro" !in id.lowercase()) return@mapNotNull null
                id to info.outputTokenLimit
            }
        if (pro.isEmpty()) return null
        // Prefer the newest STABLE release. A preview only wins when
        // there is no stable Pro at all — this id backs a fallback that
        // has to work unattended, and preview endpoints get pulled.
        val stable = pro.filter { !isPrerelease(it.first) }
        val winner = (stable.ifEmpty { pro }).maxWithOrNull(compareBy(VERSION_ORDER) { it.first })
            ?: return null
        return Resolved(winner.first, winner.second, System.currentTimeMillis())
    }

    private data class Resolved(val id: String, val outputLimit: Int?, val checkedAt: Long) {
        /**
         * A clock moved BACKWARDS (NTP correction, user edit) yields a
         * negative age, which a naive `age < TTL` read would treat as
         * fresh forever. Anything not inside [0, TTL) is stale.
         */
        fun isFresh(): Boolean {
            val age = System.currentTimeMillis() - checkedAt
            return age in 0 until TTL_MS
        }
    }

    /** Null when nothing is cached, or when the store can't be read —
     *  a DataStore IO failure should degrade to "look it up", never
     *  fail the caller's completion. */
    private suspend fun readCache(): Resolved? = runCatching {
        val prefs = context.geminiCatalogStore.data.first()
        val id = prefs[KEY_ID] ?: return@runCatching null
        Resolved(
            id = id,
            outputLimit = prefs[KEY_OUTPUT_LIMIT]?.takeIf { it > 0 },
            checkedAt = prefs[KEY_CHECKED_AT] ?: 0L,
        )
    }.onFailure { AppLogger.w(TAG, "gemini catalog cache read failed: ${it.message}") }
        .getOrNull()

    private suspend fun write(id: String, outputLimit: Int?, checkedAt: Long) {
        runCatching {
            context.geminiCatalogStore.edit { p ->
                p[KEY_ID] = id
                p[KEY_CHECKED_AT] = checkedAt
                if (outputLimit != null && outputLimit > 0) p[KEY_OUTPUT_LIMIT] = outputLimit
                else p.remove(KEY_OUTPUT_LIMIT)
            }
        }.onFailure { AppLogger.w(TAG, "gemini catalog cache write failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "GeminiModelCatalog"

        /** Model catalogs move on the order of months; a day is plenty. */
        private const val TTL_MS = 24L * 60 * 60 * 1000

        /** After a failed lookup, retry this soon rather than in a day. */
        private const val FAILURE_RETRY_MS = 30L * 60 * 1000

        /** ListModels is a small call — don't let it inherit the long
         *  text-generation read timeout and sit on a job's budget. */
        private const val LIST_DEADLINE_S = 30

        /** Used whenever discovery can't answer. Must be a real, sendable
         *  id — never the [AiModel.GEMINI_PRO_LATEST] sentinel. */
        val FALLBACK_ID: String get() = AiModel.GEMINI_3_1_PRO.id

        private val KEY_ID = stringPreferencesKey("latest_pro_id")
        private val KEY_CHECKED_AT = longPreferencesKey("latest_pro_checked_at")
        private val KEY_OUTPUT_LIMIT = intPreferencesKey("latest_pro_output_limit")

        /**
         * Models that advertise `generateContent` AND actually honour it.
         *
         * Shared with the Settings → Discover list so the two can't drift:
         * the `deep-research` and "Interactions API" exclusions exist
         * because Google's metadata advertises `generateContent` on
         * families that reject it at runtime, and a model picked from a
         * list without those filters 400s every call (#87).
         */
        fun isUsableTextModel(info: GeminiModelInfo): Boolean {
            val name = info.name ?: return false
            if (!(info.supportedGenerationMethods ?: emptyList()).contains("generateContent")) return false
            if (name.contains("-tts", ignoreCase = true)) return false
            if (name.contains("embedding", ignoreCase = true)) return false
            if (name.contains("deep-research", ignoreCase = true)) return false
            if ((info.description ?: "").contains("Interactions API", ignoreCase = true)) return false
            return true
        }

        private fun isPrerelease(id: String): Boolean {
            val lower = id.lowercase()
            return "preview" in lower || "-exp" in lower || "experimental" in lower
        }

        /**
         * Orders Gemini ids by embedded version, newest last (so
         * `maxWith` yields the newest). `gemini-3.6-pro` beats
         * `gemini-3.1-pro` beats `gemini-2.5-pro`.
         *
         * Version digits are compared numerically, not lexically — a
         * string sort would put `gemini-3.10-pro` before `gemini-3.6-pro`.
         * Ids with no parseable version sort lowest so a surprise naming
         * scheme can never outrank a known-good release.
         */
        private val VERSION_ORDER: Comparator<String> = compareBy(
            { versionOf(it).getOrElse(0) { -1 } },
            { versionOf(it).getOrElse(1) { -1 } },
            // Tie-break on the shorter id: `gemini-3.1-pro` over a
            // longer dated or suffixed variant of the same version.
            { -it.length },
        )

        private fun versionOf(id: String): List<Int> =
            Regex("(\\d+)(?:\\.(\\d+))?").find(id.removePrefix("gemini-"))
                ?.groupValues
                ?.drop(1)
                ?.map { it.toIntOrNull() ?: 0 }
                ?: emptyList()
    }
}
