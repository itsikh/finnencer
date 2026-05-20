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

    companion object {
        const val CHARS_PER_MIN_DEFAULT = 800
        const val CHARS_PER_MIN_MIN = 400
        const val CHARS_PER_MIN_MAX = 1600

        private val KEY_AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next_in_queue")
        private val KEY_END_ACTION = stringPreferencesKey("end_of_podcast_action")
        private val KEY_CHARS_PER_MIN = intPreferencesKey("podcast_chars_per_minute")
        private val KEY_VALIDATION_ENABLED = booleanPreferencesKey("podcast_script_validation_enabled")
    }
}
