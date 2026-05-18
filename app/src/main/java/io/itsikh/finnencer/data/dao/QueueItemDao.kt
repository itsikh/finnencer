package io.itsikh.finnencer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.itsikh.finnencer.data.entity.QueueItem
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: QueueItem): Long

    @Update
    suspend fun update(item: QueueItem)

    @Query("SELECT * FROM queue_items WHERE id = :id")
    suspend fun get(id: Long): QueueItem?

    /** Lookup an existing entry by (kind, refId) so toggling "+ Queue" /
     *  "✓ Queued" can detect already-saved items without scanning. */
    @Query("SELECT * FROM queue_items WHERE kind = :kind AND ref_id = :refId LIMIT 1")
    suspend fun findByRef(kind: String, refId: String): QueueItem?

    @Query("SELECT * FROM queue_items WHERE kind = :kind AND ref_id = :refId LIMIT 1")
    fun observeByRef(kind: String, refId: String): Flow<QueueItem?>

    /** "To do" tab: unfinished items, user-ordered by sortOrder. */
    @Query("SELECT * FROM queue_items WHERE completed_at_millis IS NULL ORDER BY sort_order ASC")
    fun observeIncomplete(): Flow<List<QueueItem>>

    /** "Done" tab: completed items, most-recently-completed first. */
    @Query("SELECT * FROM queue_items WHERE completed_at_millis IS NOT NULL ORDER BY completed_at_millis DESC")
    fun observeCompleted(): Flow<List<QueueItem>>

    /** Used by the Watchlist top-bar badge — count only what's still
     *  open ("to do"); the badge would be useless if it included done
     *  items the user already finished. */
    @Query("SELECT COUNT(*) FROM queue_items WHERE completed_at_millis IS NULL")
    fun observeIncompleteCount(): Flow<Int>

    @Query("DELETE FROM queue_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM queue_items WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)

    /** Bulk-clear from the Done tab without touching the to-do list. */
    @Query("DELETE FROM queue_items WHERE completed_at_millis IS NOT NULL")
    suspend fun deleteAllCompleted()

    /** Highest current sort_order among incomplete items — new items
     *  go to the end of the to-do list by default. */
    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM queue_items WHERE completed_at_millis IS NULL")
    suspend fun maxIncompleteSortOrder(): Long

    // ───── One-shot data repair (v0.0.43) ─────────────────────────────
    //
    // v0.0.42 fixed the SOURCE of the bug at the Tasks-screen queue
    // pill, but rows queued before the fix carry the wrong kind
    // (BATCH_SUMMARY) and a stale ref_id (the AiJob id, not the
    // produced artifact id). Tapping such a row routed to the Tasks
    // page instead of the player. These two UPDATEs rewrite those
    // rows in place so the existing routing branch
    // (PODCAST.name → onOpenPodcast, EARNINGS_REPORT.name → onOpenReport)
    // does the right thing.
    //
    // Returns the row count affected by each UPDATE so the caller can
    // log the result. Idempotent: a row whose kind is already
    // PODCAST / EARNINGS_REPORT will not match the WHERE clause.

    @Query("""
        UPDATE queue_items
        SET kind = 'PODCAST',
            ref_id = (
                SELECT resultRefId FROM ai_jobs
                WHERE id = queue_items.ref_id
            )
        WHERE kind = 'BATCH_SUMMARY'
          AND EXISTS (
              SELECT 1 FROM ai_jobs
              WHERE id = queue_items.ref_id
                AND resultKind IN ('PODCAST', 'SUMMARY_AND_PODCAST')
                AND resultRefId IS NOT NULL
          )
    """)
    suspend fun repairMisqueuedPodcasts(): Int

    @Query("""
        UPDATE queue_items
        SET kind = 'EARNINGS_REPORT',
            ref_id = (
                SELECT resultRefId FROM ai_jobs
                WHERE id = queue_items.ref_id
            )
        WHERE kind = 'BATCH_SUMMARY'
          AND EXISTS (
              SELECT 1 FROM ai_jobs
              WHERE id = queue_items.ref_id
                AND resultKind = 'EARNINGS_REPORT'
                AND resultRefId IS NOT NULL
          )
    """)
    suspend fun repairMisqueuedEarningsReports(): Int
}
