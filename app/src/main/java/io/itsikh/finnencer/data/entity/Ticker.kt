package io.itsikh.finnencer.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-watched stock symbol. `symbol` is the primary key (e.g. "NVDA").
 *
 * Notification policy per ticker:
 *  - threshold: minimum article importance score (1..10) to push
 *  - dailyCap: max notifications per local day before suppression
 *  - quietHoursStart/End: minutes-since-midnight in local TZ; outside this
 *    window notifications are deferred / suppressed
 *  - mutedUntilMillis: hard mute (no notifications regardless) until this
 *    instant; null = not muted
 */
@Entity(tableName = "tickers")
data class Ticker(
    @PrimaryKey
    val symbol: String,
    val name: String,
    val exchange: String,
    val sector: String? = null,
    @ColumnInfo(name = "cik") val cik: String? = null,
    @ColumnInfo(name = "logo_url") val logoUrl: String? = null,
    @ColumnInfo(name = "watchlist_order") val watchlistOrder: Int,
    @ColumnInfo(name = "notification_threshold") val notificationThreshold: Int = 8,
    @ColumnInfo(name = "daily_notification_cap") val dailyNotificationCap: Int = 5,
    @ColumnInfo(name = "muted_until_millis") val mutedUntilMillis: Long? = null,
    @ColumnInfo(name = "quiet_hours_start_minute") val quietHoursStartMinute: Int = 23 * 60,
    @ColumnInfo(name = "quiet_hours_end_minute") val quietHoursEndMinute: Int = 6 * 60,
    @ColumnInfo(name = "added_at_millis") val addedAtMillis: Long,
)
