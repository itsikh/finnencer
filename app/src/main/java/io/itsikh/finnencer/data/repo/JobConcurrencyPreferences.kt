package io.itsikh.finnencer.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.core.work.JobConcurrencyGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.jobConcurrencyDataStore by preferencesDataStore(name = "job_concurrency_preferences")

/**
 * How many podcast-producing and summary-producing AI jobs may run in
 * parallel. Default 1 each (full serialization) — prevents fan-out into
 * Anthropic rate limits and per-app heap exhaustion when the user
 * queues a batch. Clamped to [JobConcurrencyGate.MIN_LIMIT] ..
 * [JobConcurrencyGate.MAX_LIMIT] (currently 1..10).
 */
@Singleton
class JobConcurrencyPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val podcastConcurrency: Flow<Int> = context.jobConcurrencyDataStore.data.map { p ->
        (p[KEY_PODCAST] ?: JobConcurrencyGate.DEFAULT_LIMIT)
            .coerceIn(JobConcurrencyGate.MIN_LIMIT, JobConcurrencyGate.MAX_LIMIT)
    }

    val summaryConcurrency: Flow<Int> = context.jobConcurrencyDataStore.data.map { p ->
        (p[KEY_SUMMARY] ?: JobConcurrencyGate.DEFAULT_LIMIT)
            .coerceIn(JobConcurrencyGate.MIN_LIMIT, JobConcurrencyGate.MAX_LIMIT)
    }

    suspend fun setPodcastConcurrency(n: Int) {
        val sanitized = n.coerceIn(JobConcurrencyGate.MIN_LIMIT, JobConcurrencyGate.MAX_LIMIT)
        context.jobConcurrencyDataStore.edit { it[KEY_PODCAST] = sanitized }
    }

    suspend fun setSummaryConcurrency(n: Int) {
        val sanitized = n.coerceIn(JobConcurrencyGate.MIN_LIMIT, JobConcurrencyGate.MAX_LIMIT)
        context.jobConcurrencyDataStore.edit { it[KEY_SUMMARY] = sanitized }
    }

    private companion object {
        val KEY_PODCAST = intPreferencesKey("podcast_concurrency")
        val KEY_SUMMARY = intPreferencesKey("summary_concurrency")
    }
}
