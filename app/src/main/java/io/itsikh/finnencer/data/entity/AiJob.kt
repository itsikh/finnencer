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
    val resultModel: String?,         // AiModel.id (or discovered id) that actually answered, after fallback walk
    val errorMessage: String?,        // on failure
    val createdAtMillis: Long,
    val startedAtMillis: Long?,
    val completedAtMillis: Long?,
    /** Coarse phase the worker is currently in (AiJobStage.name). Updated
     *  as the pipeline progresses so the Tasks page + Task Detail screen
     *  can show what's happening in real time (#43). */
    val currentStage: String? = null,
    /** 0..100 — progress within [currentStage] (chars produced / target,
     *  chunks rendered / total, etc.). 0 if not applicable. */
    val stageProgress: Int = 0,
    /** Free-text "what's the worker doing right now" — e.g.
     *  "chunk 3 of 6 (2 reused from cache)" or "retry attempt 4/10,
     *  waiting 25s for network". Shown verbatim in the Tasks row + the
     *  Task Detail screen. */
    val stageDetail: String? = null,
)

enum class AiJobType {
    SUMMARY_BATCH,             // many articles → one text summary version
    PODCAST_BATCH,             // many articles → one podcast row
    SUMMARY_AND_PODCAST_BATCH, // many articles → summary AND a podcast derived from the summary
    REPORT_EARNINGS,           // one EarningsEvent → one EarningsReport
    EARNINGS_BRIEF_AND_PODCAST,// one EarningsEvent → BRIEF report + a podcast derived from it
}

enum class AiJobStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELED,
    /** Worker stopped cleanly because the podcast validator flagged the
     *  script. UI surfaces the validator notes + a preview of the script
     *  with "Proceed anyway" / "Cancel" buttons (#feedback-human-review-escape-hatch). */
    PENDING_REVIEW,
}

enum class AiJobResultKind {
    /** Inline text only — read from AiJob.resultText. No separate row. */
    INLINE_TEXT,
    PODCAST,
    /** Both: resultText holds the summary prose, resultRefId points at the Podcast row. */
    SUMMARY_AND_PODCAST,
    EARNINGS_REPORT,
}

/**
 * Coarse-grained phases of an AI job, surfaced in the Tasks page and
 * Task Detail screen so the user can see "what step is this on" in
 * real time instead of an opaque RUNNING for minutes (#43).
 *
 * Persisted on [AiJob.currentStage] as the name string.
 */
enum class AiJobStage(val displayName: String) {
    QUEUED("Queued"),
    CONNECTIVITY_CHECK("Checking connectivity"),
    WAITING_FOR_NETWORK("Waiting for usable network"),
    GENERATING_SUMMARY("Generating summary"),
    GENERATING_REPORT("Generating earnings report"),
    GENERATING_SCRIPT("Writing podcast script"),
    PERSISTING_SCRIPT("Saving podcast script"),
    VALIDATING_SCRIPT("Validating script"),
    SYNTHESIZING_AUDIO("Synthesizing audio"),
    FINALIZING("Finalizing"),
    DONE("Done"),
    FAILED("Failed"),
}
