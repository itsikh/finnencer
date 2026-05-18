package io.itsikh.finnencer.core.work

import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-key, runtime-adjustable counting semaphore. The user controls how
 * many podcast and summary jobs may run concurrently via Settings →
 * Background jobs, defaulting to 1 each (full serialization) so a queue
 * of 10 podcasts no longer fans out and trips Anthropic rate limits or
 * the per-app heap (the spam/OOM patterns from #29/#39).
 *
 * Implementation: per-kind [AdjustableSemaphore]. Decreasing the limit
 * does NOT preempt jobs already holding permits — they finish, and any
 * surplus permits drain naturally as `release()` runs. Increasing the
 * limit immediately wakes that many waiters. Resize happens under a
 * shared mutex so concurrent acquire/release/setLimit calls don't race.
 */
@Singleton
class JobConcurrencyGate @Inject constructor() {

    enum class Kind { PODCAST, SUMMARY }

    private val podcasts = AdjustableSemaphore(initial = DEFAULT_LIMIT)
    private val summaries = AdjustableSemaphore(initial = DEFAULT_LIMIT)

    /** Runs [block] under the [kind] gate. Suspends until a permit is
     *  available, then releases on exit (even on exception/cancellation). */
    suspend fun <T> withPermit(kind: Kind, label: String, block: suspend () -> T): T {
        val sem = semaphore(kind)
        AppLogger.i(TAG, "gate ${kind.name} acquiring for $label (limit=${sem.limitSnapshot()})")
        sem.acquire()
        AppLogger.i(TAG, "gate ${kind.name} acquired for $label")
        return try {
            block()
        } finally {
            sem.release()
            AppLogger.i(TAG, "gate ${kind.name} released for $label")
        }
    }

    /** Update concurrency. Coerced to >= 1. Called by the prefs observer
     *  in [io.itsikh.finnencer.TemplateApplication]. */
    suspend fun setLimit(kind: Kind, newLimit: Int) {
        val sanitized = newLimit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        semaphore(kind).setLimit(sanitized)
        AppLogger.i(TAG, "gate ${kind.name} limit set to $sanitized")
    }

    private fun semaphore(kind: Kind) = when (kind) {
        Kind.PODCAST -> podcasts
        Kind.SUMMARY -> summaries
    }

    companion object {
        const val DEFAULT_LIMIT = 1
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 10
        private const val TAG = "JobConcurrencyGate"
    }
}

/**
 * Counting semaphore whose permit limit can change at runtime. Unlike
 * the kotlinx.coroutines [kotlinx.coroutines.sync.Semaphore], the
 * permit count is mutable post-construction.
 *
 *  - `acquire()`: take a permit, suspend if none available.
 *  - `release()`: hand the permit to a waiter if one is queued, else
 *    return it to the free pool.
 *  - `setLimit(n)`: if n grew, wake that many waiters; if n shrank, do
 *    nothing immediately — extra in-use permits drain through subsequent
 *    release() calls (which see `inUse > limit` and skip the auto-bump).
 */
class AdjustableSemaphore(initial: Int) {
    private val mutex = Mutex()
    private var limit: Int = initial.coerceAtLeast(1)
    private var inUse: Int = 0
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()

    suspend fun acquire() {
        val waiter = CompletableDeferred<Unit>()
        val mustWait = mutex.withLock {
            if (inUse < limit) {
                inUse++
                false
            } else {
                waiters.addLast(waiter)
                true
            }
        }
        if (mustWait) waiter.await()
    }

    suspend fun release() {
        mutex.withLock {
            // If a waiter is queued AND we're still within budget, hand
            // the permit off without bumping inUse down — net zero.
            // If we're over budget (limit was shrunk), drain the surplus
            // by decrementing inUse and leaving any waiters in the queue
            // until budget allows them in.
            val waiter = if (inUse <= limit) waiters.removeFirstOrNull() else null
            if (waiter != null) {
                waiter.complete(Unit)
            } else {
                inUse = (inUse - 1).coerceAtLeast(0)
            }
        }
    }

    suspend fun setLimit(newLimit: Int) {
        val sanitized = newLimit.coerceAtLeast(1)
        mutex.withLock {
            val grew = sanitized > limit
            limit = sanitized
            if (grew) {
                // Open up newly-available slots to queued waiters.
                while (inUse < limit && waiters.isNotEmpty()) {
                    val w = waiters.removeFirst()
                    inUse++
                    w.complete(Unit)
                }
            }
        }
    }

    /** For logging only — not consistent under contention. */
    fun limitSnapshot(): Int = limit
}
