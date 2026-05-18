package io.itsikh.finnencer.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.data.dao.QueueItemDao
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.repairStateDataStore by preferencesDataStore(name = "data_repair_state")

/**
 * One-shot data repair for queue items mis-tagged by code prior to v0.0.42.
 *
 * Pre-v0.0.42, the Tasks-screen Queue pill hard-coded every result as
 * [io.itsikh.finnencer.data.entity.QueueItemKind.BATCH_SUMMARY] with
 * `ref_id = job.id` — including PODCAST and EARNINGS_REPORT results.
 * Tapping such a row routed to the Tasks page (BATCH_SUMMARY's
 * destination) instead of the player / report viewer. v0.0.42 fixed
 * the source for NEW rows; this repair fixes rows already in the user's
 * DB.
 *
 * Guarded by a DataStore boolean so it runs exactly once per install.
 * Idempotent: if the flag was somehow lost, a second run finds nothing
 * to update because the WHERE clause excludes rows already in the
 * correct shape.
 */
@Singleton
class QueueItemRepair @Inject constructor(
    @ApplicationContext private val context: Context,
    private val queueItemDao: QueueItemDao,
) {

    /**
     * Run the repair if it hasn't run yet. Safe to call on every app
     * start — the DataStore flag guards against repeated work. Any
     * failure is logged but does not throw — we never want a repair
     * issue to crash app startup.
     */
    suspend fun repairIfNeeded() {
        val alreadyRan = context.repairStateDataStore.data
            .map { it[KEY_QUEUE_REPAIR_V42] ?: false }
            .first()
        if (alreadyRan) return

        runCatching {
            val podcastFixes = queueItemDao.repairMisqueuedPodcasts()
            val reportFixes = queueItemDao.repairMisqueuedEarningsReports()
            AppLogger.i(
                TAG,
                "queue-item repair v42: " +
                    "podcasts=$podcastFixes, reports=$reportFixes",
            )
            // Mark complete only on success — if the UPDATEs threw,
            // we leave the flag false so the next launch retries.
            context.repairStateDataStore.edit {
                it[KEY_QUEUE_REPAIR_V42] = true
            }
        }.onFailure {
            AppLogger.w(TAG, "queue-item repair v42 failed: ${it.message}")
        }
    }

    private companion object {
        const val TAG = "QueueItemRepair"
        val KEY_QUEUE_REPAIR_V42 = booleanPreferencesKey("queue_repair_v42_done")
    }
}
