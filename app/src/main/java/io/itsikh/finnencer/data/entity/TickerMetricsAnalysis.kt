package io.itsikh.finnencer.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "ticker_metrics_analysis",
    primaryKeys = ["ticker", "as_of_date"],
)
data class TickerMetricsAnalysis(
    val ticker: String,
    @ColumnInfo(name = "as_of_date") val asOfDate: String,
    val analysis: String,
    val model: String,
    @ColumnInfo(name = "generated_at_millis") val generatedAtMillis: Long,
)
