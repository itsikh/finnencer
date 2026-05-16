package io.itsikh.finnencer.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Audit row for every notification the engine decides to send. Used to enforce
 * the daily-cap-per-ticker policy and to power the in-app history view.
 */
@Entity(
    tableName = "notifications",
    foreignKeys = [
        ForeignKey(
            entity = NewsArticle::class,
            parentColumns = ["id"],
            childColumns = ["article_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Ticker::class,
            parentColumns = ["symbol"],
            childColumns = ["ticker_symbol"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("article_id"),
        Index("ticker_symbol"),
        Index("sent_at_millis"),
    ],
)
data class NotificationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "article_id") val articleId: String,
    @ColumnInfo(name = "ticker_symbol") val tickerSymbol: String,
    val score: Int,
    @ColumnInfo(name = "sent_at_millis") val sentAtMillis: Long,
    @ColumnInfo(name = "dismissed_at_millis") val dismissedAtMillis: Long? = null,
    @ColumnInfo(name = "tapped_at_millis") val tappedAtMillis: Long? = null,
)
