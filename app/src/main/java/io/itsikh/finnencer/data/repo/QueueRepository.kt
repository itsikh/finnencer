package io.itsikh.finnencer.data.repo

import io.itsikh.finnencer.data.dao.QueueItemDao
import io.itsikh.finnencer.data.entity.QueueItem
import io.itsikh.finnencer.data.entity.QueueItemKind
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public API for the reading/listening queue. Every "+ Queue" affordance
 * in the app (article detail, tasks card, report viewer, podcast row /
 * player) goes through here so the schema details stay isolated from
 * the UI layer.
 */
@Singleton
class QueueRepository @Inject constructor(
    private val dao: QueueItemDao,
) {

    fun observeIncomplete(): Flow<List<QueueItem>> = dao.observeIncomplete()
    fun observeCompleted(): Flow<List<QueueItem>> = dao.observeCompleted()
    fun observeIncompleteCount(): Flow<Int> = dao.observeIncompleteCount()
    fun observeByRef(kind: QueueItemKind, refId: String): Flow<QueueItem?> =
        dao.observeByRef(kind.name, refId)

    /**
     * Idempotent add. If an entry for (kind, refId) already exists this
     * is a no-op — the toggle in the UI shows "✓ Queued" already and
     * doesn't try to insert a duplicate.
     */
    suspend fun add(
        kind: QueueItemKind,
        refId: String,
        title: String,
        subtitle: String? = null,
        tickerSymbol: String? = null,
    ): Long {
        dao.findByRef(kind.name, refId)?.let { return it.id }
        val sort = dao.maxIncompleteSortOrder() + SORT_STRIDE
        val now = System.currentTimeMillis()
        return dao.insert(
            QueueItem(
                kind = kind.name,
                refId = refId,
                title = title,
                subtitle = subtitle,
                tickerSymbol = tickerSymbol,
                sortOrder = sort,
                addedAtMillis = now,
                completedAtMillis = null,
            )
        )
    }

    /** Remove by (kind, refId) — used when the user taps "✓ Queued" to
     *  un-queue an item. Returns true if a row was removed. */
    suspend fun removeByRef(kind: QueueItemKind, refId: String): Boolean {
        val existing = dao.findByRef(kind.name, refId) ?: return false
        dao.delete(existing.id)
        return true
    }

    suspend fun toggle(
        kind: QueueItemKind,
        refId: String,
        title: String,
        subtitle: String? = null,
        tickerSymbol: String? = null,
    ): Boolean {
        return if (dao.findByRef(kind.name, refId) != null) {
            removeByRef(kind, refId)
            false
        } else {
            add(kind, refId, title, subtitle, tickerSymbol)
            true
        }
    }

    suspend fun markDone(id: Long) {
        val item = dao.get(id) ?: return
        if (item.completedAtMillis != null) return
        dao.update(item.copy(completedAtMillis = System.currentTimeMillis()))
    }

    suspend fun markUndone(id: Long) {
        val item = dao.get(id) ?: return
        val sort = dao.maxIncompleteSortOrder() + SORT_STRIDE
        dao.update(item.copy(completedAtMillis = null, sortOrder = sort))
    }

    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun deleteAll(ids: List<Long>) = dao.deleteAll(ids)
    suspend fun clearAllCompleted() = dao.deleteAllCompleted()

    /**
     * Persist a user-reordered list. We rewrite [sortOrder] in
     * [SORT_STRIDE] increments so a future single-row insert between
     * two existing rows has room without needing a re-pack.
     */
    suspend fun reorder(orderedItems: List<QueueItem>) {
        orderedItems.forEachIndexed { index, item ->
            val newSort = (index + 1L) * SORT_STRIDE
            if (item.sortOrder != newSort) {
                dao.update(item.copy(sortOrder = newSort))
            }
        }
    }

    private companion object {
        const val SORT_STRIDE = 1_000L
    }
}
