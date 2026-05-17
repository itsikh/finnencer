package io.itsikh.finnencer.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Type of content the user has queued. Each kind maps to a specific
 * Room table by [QueueItem.refId]. The string `name` of this enum is
 * stored in [QueueItem.kind] so adding a new kind is additive — old
 * rows of removed kinds are silently skipped at read time.
 */
enum class QueueItemKind {
    /** refId = NewsArticle.id (String UUID) */
    ARTICLE,
    /** refId = SummaryVersion.id (Long .toString()) — the versioned article-summary row */
    ARTICLE_SUMMARY,
    /** refId = AiJob.id (String UUID) — batch summary's inline-text result */
    BATCH_SUMMARY,
    /** refId = EarningsReport.id (Long .toString()) */
    EARNINGS_REPORT,
    /** refId = Podcast.id (Long .toString()) */
    PODCAST,
}

/**
 * A user-curated reading / listening queue. Anywhere content lives in
 * the app — article detail, AI summaries, earnings reports, podcasts —
 * users can save into this list, see it in a dedicated Queue screen,
 * reorder, mark done, and remove. Tapping a row navigates to the
 * underlying content.
 *
 * Title / subtitle / tickerSymbol are intentionally denormalized so
 * the queue screen renders without joining across 5 entity tables.
 * If the underlying content is deleted, the queue row remains as a
 * "tombstone" until the user clears it — better than the tap silently
 * failing.
 */
@Entity(
    tableName = "queue_items",
    indices = [
        Index("kind"),
        Index("completed_at_millis"),
        Index("sort_order"),
    ],
)
data class QueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    @ColumnInfo(name = "ref_id") val refId: String,
    val title: String,
    val subtitle: String?,
    @ColumnInfo(name = "ticker_symbol") val tickerSymbol: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Long,
    @ColumnInfo(name = "added_at_millis") val addedAtMillis: Long,
    /** Non-null = item is in the "Done" tab; null = still in "To do". */
    @ColumnInfo(name = "completed_at_millis") val completedAtMillis: Long?,
)
