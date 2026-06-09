package io.itsikh.finnencer.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ReportTier { BRIEF, STANDARD, DEEP }
enum class EarningsStatus { SCHEDULED, REPORTED, MISSED }

/**
 * Earnings event for a ticker (one row per fiscal quarter). Pre-populated by
 * polling Finnhub /calendar/earnings; updated to REPORTED when results
 * arrive.
 */
@Entity(
    tableName = "earnings_events",
    foreignKeys = [
        ForeignKey(
            entity = Ticker::class,
            parentColumns = ["symbol"],
            childColumns = ["ticker_symbol"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["ticker_symbol", "fiscal_year", "fiscal_quarter"], unique = true),
        Index("scheduled_at_millis"),
    ],
)
data class EarningsEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ticker_symbol") val tickerSymbol: String,
    @ColumnInfo(name = "fiscal_year") val fiscalYear: Int,
    @ColumnInfo(name = "fiscal_quarter") val fiscalQuarter: Int,
    @ColumnInfo(name = "scheduled_at_millis") val scheduledAtMillis: Long,
    @ColumnInfo(name = "actual_reported_at_millis") val actualReportedAtMillis: Long? = null,
    @ColumnInfo(name = "consensus_eps") val consensusEps: Double? = null,
    @ColumnInfo(name = "consensus_revenue") val consensusRevenue: Double? = null,
    @ColumnInfo(name = "actual_eps") val actualEps: Double? = null,
    @ColumnInfo(name = "actual_revenue") val actualRevenue: Double? = null,
    val status: String = EarningsStatus.SCHEDULED.name,
    /**
     * True once the fiscal label was set by the fiscal-aware source
     * (Finnhub). EDGAR seeds rows with a *calendar-quarter* guess
     * (`EarningsCalendarSync.fiscalGuess`) that's wrong for any company
     * whose fiscal year doesn't track the calendar — Marvell's May filing
     * is fiscal Q1 FY2027, not "Q2 2026". We keep the guess for
     * sorting/dedup but refuse to display it as fact (#70).
     */
    @ColumnInfo(name = "fiscal_confirmed") val fiscalConfirmed: Boolean = false,
)

/**
 * Display label for the fiscal period ("Q1 2027"), or null when the label
 * hasn't been confirmed by the fiscal-aware source yet — callers fall back
 * to just the ticker + date rather than show EDGAR's calendar-quarter
 * guess, which is wrong for offset-fiscal-year companies (#70).
 */
fun EarningsEvent.fiscalLabelOrNull(): String? =
    if (fiscalConfirmed) "Q$fiscalQuarter $fiscalYear" else null

/**
 * A generated earnings report. `contentMarkdown` is the LLM output ready for
 * rendering (and later for TTS dialogue conversion in Build B).
 *
 * `sourcesUsed` is a JSON-array string of source identifiers (filing URLs,
 * Finnhub IDs, etc.) for transparency.
 */
@Entity(
    tableName = "earnings_reports",
    foreignKeys = [
        ForeignKey(
            entity = Ticker::class,
            parentColumns = ["symbol"],
            childColumns = ["ticker_symbol"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = EarningsEvent::class,
            parentColumns = ["id"],
            childColumns = ["earnings_event_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("ticker_symbol"),
        Index("earnings_event_id"),
        Index("generated_at_millis"),
    ],
)
data class EarningsReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ticker_symbol") val tickerSymbol: String,
    @ColumnInfo(name = "earnings_event_id") val earningsEventId: Long? = null,
    val tier: String, // ReportTier.name
    val title: String,
    @ColumnInfo(name = "content_markdown") val contentMarkdown: String,
    val model: String,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int,
    @ColumnInfo(name = "sources_used_json") val sourcesUsedJson: String,
    @ColumnInfo(name = "generated_at_millis") val generatedAtMillis: Long,
)
