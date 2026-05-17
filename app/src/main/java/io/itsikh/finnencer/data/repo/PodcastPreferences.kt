package io.itsikh.finnencer.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.podcastPrefsDataStore by preferencesDataStore(name = "podcast_preferences")

/**
 * User preferences for podcast playback behaviour.
 *
 * `autoPlayNextInQueue`: when a podcast finishes, automatically navigate
 * to the next incomplete podcast in the user's queue and start it.
 * Defaults to `false` so behaviour stays predictable until the user
 * explicitly opts in.
 */
@Singleton
class PodcastPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val autoPlayNextInQueue: Flow<Boolean> = context.podcastPrefsDataStore.data.map { p ->
        p[KEY_AUTOPLAY_NEXT] ?: false
    }

    suspend fun setAutoPlayNextInQueue(value: Boolean) {
        context.podcastPrefsDataStore.edit { it[KEY_AUTOPLAY_NEXT] = value }
    }

    private companion object {
        val KEY_AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next_in_queue")
    }
}
