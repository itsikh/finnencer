package io.itsikh.finnencer.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per outbound API call that costs money. Drives the in-app cost
 * meter screen so the user always knows what each provider is burning.
 *
 * Cost is stored in millicents (USD) — Long-precision, no float drift.
 */
@Entity(
    tableName = "api_usage",
    indices = [Index("provider"), Index("requested_at_millis")],
)
data class ApiUsage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String, // Anthropic, Finnhub, Gemini, etc.
    val endpoint: String,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "character_count") val characterCount: Int? = null,
    @ColumnInfo(name = "cost_millicents") val costMillicents: Long = 0,
    @ColumnInfo(name = "requested_at_millis") val requestedAtMillis: Long,
    @ColumnInfo(name = "ok") val ok: Boolean = true,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
)
