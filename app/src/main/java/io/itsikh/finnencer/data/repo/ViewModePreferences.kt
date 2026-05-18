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

private val Context.viewModesDataStore by preferencesDataStore(name = "view_modes")

/**
 * Per-screen list-view preference: flat (default) vs grouped-by-ticker.
 *
 * Tasks / Queue / Podcasts each can be toggled between the standard
 * chronological flat list and a "folder per ticker" view (#user-request).
 * Defaults are all false so the existing UX is preserved on upgrade.
 */
@Singleton
class ViewModePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val tasksGrouped: Flow<Boolean> = context.viewModesDataStore.data
        .map { it[KEY_TASKS] ?: false }

    val queueGrouped: Flow<Boolean> = context.viewModesDataStore.data
        .map { it[KEY_QUEUE] ?: false }

    val podcastsGrouped: Flow<Boolean> = context.viewModesDataStore.data
        .map { it[KEY_PODCASTS] ?: false }

    suspend fun setTasksGrouped(value: Boolean) {
        context.viewModesDataStore.edit { it[KEY_TASKS] = value }
    }

    suspend fun setQueueGrouped(value: Boolean) {
        context.viewModesDataStore.edit { it[KEY_QUEUE] = value }
    }

    suspend fun setPodcastsGrouped(value: Boolean) {
        context.viewModesDataStore.edit { it[KEY_PODCASTS] = value }
    }

    private companion object {
        val KEY_TASKS = booleanPreferencesKey("tasks_grouped")
        val KEY_QUEUE = booleanPreferencesKey("queue_grouped")
        val KEY_PODCASTS = booleanPreferencesKey("podcasts_grouped")
    }
}
