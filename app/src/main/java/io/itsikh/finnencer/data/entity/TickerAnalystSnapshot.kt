package io.itsikh.finnencer.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Daily-refreshed snapshot of analyst coverage for one ticker. Backs
 * both the watchlist row (price-target arrow vs current quote) and the
 * earnings-report pipeline (so re-running a report doesn't refetch
 * Finnhub each time).
 *
 * The recommendation-trends list is stored as a JSON string rather
 * than a child table — it's at most ~6 rows, the read path always
 * wants the whole array, and a child table would force a join just to
 * render the analyst-mix dot chart.
 *
 * Freshness: callers should treat the row as stale and refresh when
 * `now - fetchedAtMillis` exceeds the per-feature TTL (typically 24h
 * for reports, shorter for the watchlist pill).
 */
@Entity(tableName = "ticker_analyst_snapshot")
data class TickerAnalystSnapshot(
    @PrimaryKey val ticker: String,
    @ColumnInfo(name = "fetched_at_millis") val fetchedAtMillis: Long,
    @ColumnInfo(name = "target_high") val targetHigh: Double?,
    @ColumnInfo(name = "target_low") val targetLow: Double?,
    @ColumnInfo(name = "target_mean") val targetMean: Double?,
    @ColumnInfo(name = "target_median") val targetMedian: Double?,
    @ColumnInfo(name = "last_updated") val lastUpdated: String?,
    /**
     * JSON array of recommendation-trend rows from Finnhub. Schema:
     *   [{ "period": "YYYY-MM-DD", "buy": Int, "hold": Int, "sell": Int,
     *      "strongBuy": Int, "strongSell": Int }, ...]
     * Empty string when the API returned no trends (rather than null
     * so the column is non-nullable and easier to query on).
     */
    @ColumnInfo(name = "recommendation_trends_json") val recommendationTrendsJson: String,
)
