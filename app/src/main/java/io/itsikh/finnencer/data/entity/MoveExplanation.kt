package io.itsikh.finnencer.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "move_explanation",
    primaryKeys = ["ticker", "as_of_date"],
)
data class MoveExplanation(
    val ticker: String,
    @ColumnInfo(name = "as_of_date") val asOfDate: String,
    @ColumnInfo(name = "pct_change") val pctChange: Double,
    val explanation: String,
    val model: String,
    @ColumnInfo(name = "article_ids_csv") val articleIdsCsv: String,
    @ColumnInfo(name = "generated_at_millis") val generatedAtMillis: Long,
)
