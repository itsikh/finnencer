package io.itsikh.finnencer.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.podcastPrefsDataStore by preferencesDataStore(name = "podcast_preferences")

/**
 * What to do when the current podcast finishes playing.
 *
 *  - [STOP]: stay on the current podcast screen, leave player idle.
 *  - [CONTINUE]: start the next incomplete podcast in the user's queue
 *    (preserves queue order).
 *  - [SHUFFLE]: pick a random remaining incomplete podcast — "Mix" in
 *    the bug report (#29).
 */
enum class EndOfPodcastAction { STOP, CONTINUE, SHUFFLE }

/**
 * Gemini multi-speaker TTS preview models the app can route podcast
 * synthesis through. All three are documented at
 * https://ai.google.dev/gemini-api/docs/speech-generation and differ
 * mostly in cost, latency, and audio quality. The user picks one in
 * Settings → Podcasts.
 */
enum class TtsModel(val modelId: String, val displayName: String, val description: String) {
    GEMINI_3_1_FLASH(
        modelId = "gemini-3.1-flash-tts-preview",
        displayName = "Gemini 3.1 Flash",
        description = "Default. Newest preview (April 2026), fastest on most keys.",
    ),
    GEMINI_2_5_FLASH(
        modelId = "gemini-2.5-flash-preview-tts",
        displayName = "Gemini 2.5 Flash",
        description = "Older preview. Try this if 3.1 Flash misbehaves on your key.",
    ),
    GEMINI_2_5_PRO(
        modelId = "gemini-2.5-pro-preview-tts",
        displayName = "Gemini 2.5 Pro",
        description = "Higher quality, slower, costs more per chunk.",
    ),
}

/**
 * User preferences for podcast playback behaviour.
 *
 * Stores [endOfPodcastAction] as a string ("STOP" / "CONTINUE" /
 * "SHUFFLE") so adding additional modes later doesn't need a migration.
 * Reads the legacy boolean [KEY_AUTOPLAY_NEXT] for backwards-compat with
 * users upgrading from 0.0.33 where the only choice was on/off.
 */
@Singleton
class PodcastPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val endOfPodcastAction: Flow<EndOfPodcastAction> =
        context.podcastPrefsDataStore.data.map { p ->
            val stored = p[KEY_END_ACTION]
            if (stored != null) {
                runCatching { EndOfPodcastAction.valueOf(stored) }.getOrDefault(EndOfPodcastAction.STOP)
            } else {
                // Legacy migration: 0.0.33 only had a boolean. Honor it
                // so users who already opted into autoplay don't lose
                // that choice on upgrade.
                if (p[KEY_AUTOPLAY_NEXT] == true) EndOfPodcastAction.CONTINUE
                else EndOfPodcastAction.STOP
            }
        }

    suspend fun setEndOfPodcastAction(value: EndOfPodcastAction) {
        context.podcastPrefsDataStore.edit {
            it[KEY_END_ACTION] = value.name
            // Drop the legacy boolean so we stop reading it next launch.
            it.remove(KEY_AUTOPLAY_NEXT)
        }
    }

    /**
     * Character budget the script generator targets per requested
     * minute of audio. Drives how long Claude's draft is — Gemini TTS
     * "Charon"/"Aoede" voices speak ~130–140 wpm, so 800 chars/min
     * lands roughly on the requested duration with a small safety
     * margin. Users who want longer-feeling podcasts can raise this;
     * users who consistently get podcasts that overshoot can lower it.
     *
     * Bounded to [CHARS_PER_MIN_MIN]..[CHARS_PER_MIN_MAX] when read
     * back to keep prompt budgets sane even if someone manually edits
     * the DataStore.
     */
    val charsPerMinute: Flow<Int> =
        context.podcastPrefsDataStore.data.map { p ->
            (p[KEY_CHARS_PER_MIN] ?: CHARS_PER_MIN_DEFAULT)
                .coerceIn(CHARS_PER_MIN_MIN, CHARS_PER_MIN_MAX)
        }

    suspend fun setCharsPerMinute(value: Int) {
        context.podcastPrefsDataStore.edit {
            it[KEY_CHARS_PER_MIN] = value.coerceIn(CHARS_PER_MIN_MIN, CHARS_PER_MIN_MAX)
        }
    }

    /**
     * Run the second-pass validator between the script writer and TTS.
     * Default OFF as of v0.0.69 (#49 — kept flagging "again and again"
     * even after the v0.0.68 prompt relaxation). The writer-stage
     * script is usually shippable as-is; turning on the validator adds
     * a second LLM round-trip + the risk of false-positive
     * PENDING_REVIEW. Users who want the safety net flip the toggle on
     * in Settings → AI.
     */
    val scriptValidationEnabled: Flow<Boolean> =
        context.podcastPrefsDataStore.data.map { p ->
            p[KEY_VALIDATION_ENABLED] ?: false
        }

    suspend fun setScriptValidationEnabled(value: Boolean) {
        context.podcastPrefsDataStore.edit {
            it[KEY_VALIDATION_ENABLED] = value
        }
    }

    /**
     * Maximum characters per Gemini TTS call. Smaller chunks → more
     * API calls but each one is faster and less likely to time out;
     * the per-chunk PCM cache means a failed chunk only loses one
     * slot of work instead of the whole tail. v0.0.69 dropped from
     * 4500 → 1500 to chase stability on long (20-30 min) podcasts
     * that were retrying repeatedly (#49 follow-up).
     *
     * Bounded to [TTS_CHUNK_MIN]..[TTS_CHUNK_MAX] on read so a
     * manually-edited DataStore can't push the value into ranges
     * that break Gemini's per-call budget.
     */
    val ttsChunkChars: Flow<Int> =
        context.podcastPrefsDataStore.data.map { p ->
            (p[KEY_TTS_CHUNK_CHARS] ?: TTS_CHUNK_DEFAULT)
                .coerceIn(TTS_CHUNK_MIN, TTS_CHUNK_MAX)
        }

    suspend fun setTtsChunkChars(value: Int) {
        context.podcastPrefsDataStore.edit {
            it[KEY_TTS_CHUNK_CHARS] = value.coerceIn(TTS_CHUNK_MIN, TTS_CHUNK_MAX)
        }
    }

    /**
     * Which Gemini TTS preview model to use for multi-speaker
     * synthesis. The official docs list three currently-supported
     * preview models with similar capabilities but different
     * latency/quality trade-offs. Default is the 2.5 Flash preview —
     * it's been stable longest and is the fastest on most keys.
     * Users who want the latest model or higher quality can switch
     * in Settings → Podcasts. Bounded to [TtsModel.entries] on read
     * so a manually-edited DataStore can't pick a model the API
     * would 4xx on.
     */
    val ttsModel: Flow<TtsModel> =
        context.podcastPrefsDataStore.data.map { p ->
            val stored = p[KEY_TTS_MODEL]
            TtsModel.entries.firstOrNull { it.modelId == stored } ?: TtsModel.GEMINI_3_1_FLASH
        }

    /**
     * Skip the pre-flight Gemini TTS smoke probe. Default OFF: the probe
     * catches dead keys / unresponsive models BEFORE Claude burns tokens
     * writing a 30-page script. Users on flaky / slow networks who want
     * to bypass the gate entirely (the in-pipeline retry loop is robust)
     * can flip this in Settings → Podcasts.
     */
    val skipTtsPreflight: Flow<Boolean> =
        context.podcastPrefsDataStore.data.map { p ->
            p[KEY_SKIP_TTS_PREFLIGHT] ?: false
        }

    suspend fun setSkipTtsPreflight(value: Boolean) {
        context.podcastPrefsDataStore.edit {
            it[KEY_SKIP_TTS_PREFLIGHT] = value
        }
    }

    suspend fun setTtsModel(value: TtsModel) {
        context.podcastPrefsDataStore.edit {
            it[KEY_TTS_MODEL] = value.modelId
        }
    }

    companion object {
        const val CHARS_PER_MIN_DEFAULT = 800
        const val CHARS_PER_MIN_MIN = 400
        const val CHARS_PER_MIN_MAX = 1600

        const val TTS_CHUNK_DEFAULT = 1500
        const val TTS_CHUNK_MIN = 500
        const val TTS_CHUNK_MAX = 6000

        private val KEY_AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next_in_queue")
        private val KEY_END_ACTION = stringPreferencesKey("end_of_podcast_action")
        private val KEY_CHARS_PER_MIN = intPreferencesKey("podcast_chars_per_minute")
        private val KEY_VALIDATION_ENABLED = booleanPreferencesKey("podcast_script_validation_enabled")
        private val KEY_TTS_CHUNK_CHARS = intPreferencesKey("podcast_tts_chunk_chars")
        private val KEY_TTS_MODEL = stringPreferencesKey("podcast_tts_model")
        private val KEY_SKIP_TTS_PREFLIGHT = booleanPreferencesKey("podcast_skip_tts_preflight")
    }
}
