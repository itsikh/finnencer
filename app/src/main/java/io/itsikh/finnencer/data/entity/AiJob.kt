package io.itsikh.finnencer.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One AI-driven task the user kicked off. Survives app exit thanks to
 * WorkManager + Room. Lifecycle:
 *   QUEUED → RUNNING → COMPLETED / FAILED / CANCELED
 *
 * `inputJson` is a small per-type payload the worker reads on startup
 * (article ids, page count, custom prompt, …). `resultRefId` and
 * `resultKind` point at the produced artifact — opening a finished task
 * deep-links to it.
 */
@Entity(
    tableName = "ai_jobs",
    indices = [Index("status"), Index("createdAtMillis")],
)
data class AiJob(
    @PrimaryKey val id: String,
    val type: String,                 // AiJobType.name
    val status: String,               // AiJobStatus.name
    val title: String,                // human-readable, shown in Tasks list
    val subtitle: String?,            // e.g. "3 articles · 5 pages" — optional
    val tickerSymbol: String?,        // when applicable, for grouping/badge
    val inputJson: String,            // worker payload
    val resultKind: String?,          // AiJobResultKind.name on success
    val resultRefId: String?,         // id of the produced row (long-as-string)
    val resultText: String?,          // inline result for jobs that don't write a row (batch summary)
    val errorMessage: String?,        // on failure
    val createdAtMillis: Long,
    val startedAtMillis: Long?,
    val completedAtMillis: Long?,
)

enum class AiJobType {
    SUMMARY_BATCH,    // many articles → one text summary version
    PODCAST_BATCH,    // many articles → one podcast row
    REPORT_EARNINGS,  // one EarningsEvent → one EarningsReport
}

enum class AiJobStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELED
}

enum class AiJobResultKind {
    /** Inline text only — read from AiJob.resultText. No separate row. */
    INLINE_TEXT,
    PODCAST,
    EARNINGS_REPORT,
}
