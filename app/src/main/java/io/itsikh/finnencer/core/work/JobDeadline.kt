package io.itsikh.finnencer.core.work

import android.os.SystemClock
import io.itsikh.finnencer.logging.AppLogger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext as KCoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Wall-clock budget for one AiJob, carried on the coroutine context the
 * same way [JobIdContext] carries the job id.
 *
 * ## Why this exists
 *
 * The podcast pipeline nests four retry layers, none of which knew about
 * the others:
 *
 * ```
 * BundleSummarizer.scriptCallWithRetry   N attempts
 *  └─ AiRouter.runRanked                 up to 6 models (ranked + rescue)
 *      └─ AiRouter transient retry       3 attempts per model
 *          └─ OkHttp call                up to its own timeout
 * ```
 *
 * Multiplied out that was up to 180 HTTP calls per script pass, times up
 * to four passes — and the TTS stage separately allows 10 attempts plus
 * six `Retry-After` waits per chunk, per chunk. Every individual policy
 * was defensible; the product of them was unbounded. Raising per-request
 * timeouts for slower, higher-quality models (Opus 5 / Fable 5) makes the
 * pathological path proportionally more expensive, so the ceiling has to
 * come first.
 *
 * A single budget checked by every retry loop bounds the whole tree
 * regardless of how deeply it nests, and it degrades gracefully: the
 * podcast pipeline persists its script as soon as it's written and caches
 * each rendered PCM chunk to disk, so a job stopped at the budget resumes
 * from where it left off rather than starting over.
 *
 * ## Clock choice
 *
 * [SystemClock.elapsedRealtime] is monotonic and keeps counting through
 * deep sleep. `System.currentTimeMillis()` would let an NTP correction
 * mid-job either extend the budget or kill the job early.
 */
class JobDeadline(
    /** Total wall-clock allowance for the job. */
    val budgetMs: Long,
    private val startedAtRealtimeMs: Long = SystemClock.elapsedRealtime(),
) : AbstractCoroutineContextElement(Key) {

    companion object Key : KCoroutineContext.Key<JobDeadline> {
        private const val TAG = "JobDeadline"

        /**
         * Podcast jobs run a script pass (plus continuations), an optional
         * validation pass, and a multi-chunk TTS render. Generous, because
         * hitting this cap should mean "something is genuinely wrong",
         * not "a long episode took a while".
         */
        const val PODCAST_BUDGET_MS = 45L * 60 * 1000

        /** Report jobs are a single LLM call plus source gathering. */
        const val REPORT_BUDGET_MS = 25L * 60 * 1000

        /** Summary batches are the cheapest of the three. */
        const val SUMMARY_BUDGET_MS = 20L * 60 * 1000

        /**
         * Article scoring inside SyncWorker. Deliberately well under the
         * OS's ~10-minute cap for non-foreground workers: unlike
         * AiJobWorker, SyncWorker doesn't promote itself, so exceeding it
         * means the system kills the run mid-stage. Leftover articles are
         * picked up by the next sync.
         */
        const val SCORING_BUDGET_MS = 7L * 60 * 1000

        /**
         * Never hand a single HTTP call the entire remaining budget — a
         * job that spends everything on one doomed request has nothing
         * left to fall back to another model with.
         */
        const val MAX_SINGLE_CALL_SHARE = 0.6
    }

    val elapsedMs: Long get() = SystemClock.elapsedRealtime() - startedAtRealtimeMs

    val remainingMs: Long get() = (budgetMs - elapsedMs).coerceAtLeast(0L)

    val isExhausted: Boolean get() = remainingMs <= 0L

    /**
     * Largest deadline a single HTTP call may claim right now: the lesser
     * of [preferredMs] and [MAX_SINGLE_CALL_SHARE] of what's left.
     * Returns 0 when the budget is spent — callers should treat that as
     * "stop", not "no limit".
     */
    fun callDeadlineMs(preferredMs: Long): Long {
        val share = (remainingMs * MAX_SINGLE_CALL_SHARE).toLong()
        return minOf(preferredMs, share).coerceAtLeast(0L)
    }

    fun describe(): String {
        val remainingSec = remainingMs / 1000
        return "${remainingSec / 60}m${remainingSec % 60}s left of ${budgetMs / 60_000}m"
    }
}

/**
 * Thrown when a job's wall-clock budget is spent. Deliberately NOT an
 * [java.io.IOException]: every retry loop in the codebase classifies
 * IOException as transient-and-worth-retrying, and this is the one
 * failure that must never be retried.
 */
class JobBudgetExceededException(
    val stage: String,
    val budgetMs: Long,
) : RuntimeException(
    "Job budget of ${budgetMs / 60_000} minutes exhausted during $stage",
)

/** The active budget, or null when running outside a budgeted job. */
suspend fun currentJobDeadline(): JobDeadline? = coroutineContext[JobDeadline]

/**
 * Remaining budget in millis, or null when there is no budget in context
 * (ad-hoc UI-triggered calls outside the worker). Null means "unbounded"
 * — callers should fall back to their own defaults rather than stopping.
 */
suspend fun remainingJobBudgetMs(): Long? = currentJobDeadline()?.remainingMs

/**
 * Throw [JobBudgetExceededException] if the budget is spent. Call this at
 * the top of each retry iteration — before sleeping and before issuing a
 * request — so a loop stops at the ceiling instead of one attempt past it.
 * No-op outside a budgeted job.
 */
suspend fun ensureJobBudget(stage: String, minRequiredMs: Long = 0L) {
    val deadline = currentJobDeadline() ?: return
    if (deadline.remainingMs <= minRequiredMs) {
        val need = if (minRequiredMs > 0) ", needed ${minRequiredMs / 1000}s" else ""
        AppLogger.w(
            "JobDeadline",
            "budget exhausted during $stage (${deadline.describe()}$need)",
        )
        throw JobBudgetExceededException(stage, deadline.budgetMs)
    }
}

/**
 * Non-throwing budget check, for loops that would rather stop cleanly
 * than unwind. Returns false outside a budgeted job (no budget = keep
 * going). Prefer this wherever a caller wraps its work in `runCatching`,
 * since a thrown [JobBudgetExceededException] would be swallowed there
 * and misreported as a failure of the work itself.
 */
suspend fun jobBudgetExhausted(): Boolean = currentJobDeadline()?.isExhausted == true

/**
 * Clamp a planned backoff so a retry can't sleep past the budget. Returns
 * the sleep to perform, or null if there isn't enough budget left to be
 * worth waiting — in which case the caller should give up now rather than
 * sleep and then discover it has no time to retry in.
 */
suspend fun backoffWithinBudget(plannedMs: Long, minUsefulRemainderMs: Long = 15_000L): Long? {
    val remaining = remainingJobBudgetMs() ?: return plannedMs
    if (remaining <= minUsefulRemainderMs) return null
    return minOf(plannedMs, remaining - minUsefulRemainderMs)
}

/**
 * Per-request HTTP deadline for a text-generation call, in seconds.
 *
 * These are non-streaming requests: nothing arrives on the socket until
 * the model has finished generating, so the read timeout has to exceed
 * *total* generation time rather than acting as an idle detector. Sizing
 * it from the output budget stops a 900-token move-explanation from being
 * handed the same twenty-minute rope as a 24k-token deep-dive report.
 *
 * The 20 tok/s divisor is a deliberately conservative floor — Opus 5 with
 * adaptive thinking on a long report runs well above that, so the deadline
 * has headroom without being open-ended. Clamped to [MIN_S, MAX_S], then
 * capped again by whatever the job budget can spare.
 */
suspend fun textCallDeadlineSeconds(maxTokens: Int): Long {
    val preferredS = (BASE_OVERHEAD_S + maxTokens / TOKENS_PER_SECOND_FLOOR)
        .coerceIn(MIN_CALL_DEADLINE_S, MAX_CALL_DEADLINE_S)
    // No budget in context means nobody is managing this call's lifetime,
    // which in practice means a user is sitting in front of it: reports
    // and summaries are generated straight from their ViewModels as well
    // as from the worker. Background work has earned a long deadline —
    // it's bounded by a job budget and nobody is waiting — but an
    // interactive tap should not be able to hang for twenty minutes.
    //
    // The interactive ceiling is still well above the old flat 300s,
    // because thinking-enabled reports legitimately need longer than the
    // pre-migration ones did; it just isn't open-ended.
    val deadline = currentJobDeadline()
        ?: return minOf(preferredS, INTERACTIVE_MAX_CALL_DEADLINE_S)
    val allowedMs = deadline.callDeadlineMs(preferredS * 1000)
    // Floor rather than zero: a request still needs a workable timeout.
    // Callers guard against issuing a call the budget can't fund by
    // passing MIN_CALL_DEADLINE_MS to ensureJobBudget first — without
    // that, a job with seconds left would start a two-minute call and
    // overshoot its own ceiling.
    return (allowedMs / 1000).coerceAtLeast(MIN_CALL_DEADLINE_S)
}

/** Minimum wall-clock a text call needs to be worth starting. */
val MIN_CALL_DEADLINE_MS: Long get() = MIN_CALL_DEADLINE_S * 1000

private const val BASE_OVERHEAD_S = 60L
private const val TOKENS_PER_SECOND_FLOOR = 20L
private const val MIN_CALL_DEADLINE_S = 120L
private const val MAX_CALL_DEADLINE_S = 1200L

/** Ceiling for calls made outside a budgeted job — i.e. with a user
 *  waiting on the result. See [textCallDeadlineSeconds]. */
private const val INTERACTIVE_MAX_CALL_DEADLINE_S = 600L
