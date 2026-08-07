package io.itsikh.finnencer.data.ai

import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entrypoint for "complete some text" calls. Reads the user's
 * configured ranked model list for the given [AiUsage] and walks it on
 * failure: tries primary, falls back to secondary on any non-cancellation
 * error, then tertiary. Returns the first successful response (and which
 * model produced it). If every slot fails, re-throws the LAST exception.
 */
@Singleton
class AiRouter @Inject constructor(
    private val prefs: AiPreferences,
    private val anthropic: ClaudeClient,
    private val gemini: GeminiTextClient,
    private val networkAvailability: io.itsikh.finnencer.core.net.NetworkAvailability,
    private val apiKeys: io.itsikh.finnencer.data.repo.ApiKeysRepository,
    private val geminiCatalog: GeminiModelCatalog,
) {

    suspend fun complete(
        usage: AiUsage,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double? = null,
        cacheSystem: Boolean = false,
    ): AiCompletion {
        val ranked = prefs.getRanked(usage)
        // Effort is a property of the WORKLOAD, not of the user's model
        // choice, so it's resolved here rather than passed in by callers
        // — every fallback model in the ranked walk gets the same hint.
        return runRanked(usage, ranked, system, userMessage, maxTokens, temperature, cacheSystem, usage.effort)
    }

    /** Direct model override (used when a feature needs a specific tier regardless of prefs). */
    suspend fun completeWith(
        model: AiModel,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double? = null,
        cacheSystem: Boolean = false,
        effort: AiEffort? = null,
    ): AiCompletion = runOneWithTransientRetry(
        AiModelOption.Builtin(model), system, userMessage, maxTokens, temperature, cacheSystem, effort,
    )

    private suspend fun runRanked(
        usage: AiUsage,
        ranked: List<AiModelOption>,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double?,
        cacheSystem: Boolean,
        effort: AiEffort?,
    ): AiCompletion {
        var lastError: Throwable? = null
        ranked.forEachIndexed { index, option ->
            // Stop walking the moment the job's wall-clock budget is
            // spent. Without this the ranked list and the rescue chain
            // below multiply against the per-model retries, and a job
            // whose provider is simply unreachable keeps a foreground
            // service alive for hours.
            io.itsikh.finnencer.core.work.ensureJobBudget("$usage model fallback")
            try {
                return runOneWithTransientRetry(option, system, userMessage, maxTokens, temperature, cacheSystem, effort)
            } catch (ce: CancellationException) {
                throw ce
            } catch (be: io.itsikh.finnencer.core.work.JobBudgetExceededException) {
                throw be
            } catch (t: Throwable) {
                lastError = t
                val isLast = index == ranked.lastIndex
                if (isLast) {
                    AppLogger.e(
                        TAG,
                        "[$usage] all ${ranked.size} model(s) failed; final=${option.id}",
                        t,
                    )
                } else {
                    val next = ranked[index + 1]
                    AppLogger.w(
                        TAG,
                        "[$usage] ${option.id} failed (${t.javaClass.simpleName}: ${t.message}); falling back to ${next.id}",
                    )
                }
            }
        }
        // Rescue chain — every ranked model failed, so before failing a
        // paid job try, in order:
        //  1. The usage's DEFAULT builtin (when the ranked list didn't
        //     include it): heals a broken user selection, e.g. a custom
        //     Gemini model that 400s every generateContent call because
        //     it only supports a different API (#87).
        //  2. Cross-provider fallback, ONLY for pure connectivity
        //     failures (no HTTP status ever came back): networks can
        //     block one provider while the other works (#86). HTTP
        //     errors don't trigger this — switching providers doesn't
        //     fix auth/quota/bad-request.
        val attempted = ranked.mapTo(HashSet()) { it.id }
        var rescues = 0
        while (rescues++ < MAX_RESCUES) {
            io.itsikh.finnencer.core.work.ensureJobBudget("$usage rescue models")
            val next = nextRescue(usage, attempted, lastError) ?: break
            attempted += next.id
            AppLogger.w(TAG, "[$usage] all prior model(s) failed; rescue attempt with ${next.id}")
            try {
                return runOneWithTransientRetry(
                    AiModelOption.Builtin(next), system, userMessage, maxTokens, temperature, cacheSystem, effort,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (be: io.itsikh.finnencer.core.work.JobBudgetExceededException) {
                throw be
            } catch (t: Throwable) {
                AppLogger.e(TAG, "[$usage] rescue model ${next.id} also failed", t)
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("AiRouter: empty ranked list for $usage")
    }

    /**
     * Next untried rescue model, re-evaluated after each failure:
     *  1. The usage's DEFAULT builtin — heals broken selections of any
     *     failure class (e.g. a custom model that 400s generateContent, #87).
     *  2. When the latest failure is pure connectivity (no HTTP status —
     *     the endpoint is unreachable, #86): a tier-matched builtin on
     *     whichever configured provider hasn't been tried, since networks
     *     can block one provider while the other works. Re-evaluating
     *     between attempts also covers the compound case (broken custom
     *     model AND the default's provider blocked).
     * HTTP-level failures never trigger step 2 — switching providers
     * doesn't fix auth, quota, or a bad request.
     */
    private fun nextRescue(usage: AiUsage, attempted: Set<String>, lastError: Throwable?): AiModel? {
        val def = usage.defaultModel
        if (def.id !in attempted && keyConfigured(def.provider)) return def
        if (lastError != null && isConnectivityFailure(lastError)) {
            val wantLarge = def.tier == AiTier.LARGE
            val candidates = listOf(
                if (wantLarge) AiModel.GEMINI_PRO_LATEST else AiModel.GEMINI_3_6_FLASH,
                if (wantLarge) AiModel.CLAUDE_OPUS_5 else AiModel.CLAUDE_SONNET_5,
            )
            return candidates.firstOrNull { it.id !in attempted && keyConfigured(it.provider) }
        }
        return null
    }

    private fun keyConfigured(provider: AiProvider): Boolean = when (provider) {
        AiProvider.ANTHROPIC -> apiKeys.isConfigured(io.itsikh.finnencer.data.repo.ApiKey.ANTHROPIC)
        AiProvider.GEMINI -> apiKeys.isConfigured(io.itsikh.finnencer.data.repo.ApiKey.GEMINI)
    }

    /** True when [t]'s cause chain is pure connectivity (connect/read
     *  timeout, DNS, reset) with NO HTTP response — the server was never
     *  reached, so a different provider may still be reachable. */
    private fun isConnectivityFailure(t: Throwable): Boolean {
        val causes = generateSequence(t) { cur -> cur.cause.takeIf { it !== cur } }.toList()
        if (causes.any { it is retrofit2.HttpException }) return false
        return causes.any {
            it is java.net.SocketTimeoutException ||
                it is java.net.ConnectException ||
                it is java.net.UnknownHostException ||
                it is java.io.IOException
        }
    }


    /**
     * [runOne] plus a small retry loop for TRANSIENT failures (socket
     * timeouts, connection drops, HTTP 408/429/5xx). One read-timeout on
     * a flaky network was killing a whole podcast/report job outright
     * (#85 — the user's network couldn't reach the provider's CDN for a
     * few minutes). Permanent errors (4xx) still surface immediately so
     * the ranked-fallback walk in [runRanked] can try the next model.
     */
    private suspend fun runOneWithTransientRetry(
        option: AiModelOption,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double?,
        cacheSystem: Boolean,
        effort: AiEffort?,
    ): AiCompletion {
        var lastErr: Throwable? = null
        for (attempt in 1..TRANSIENT_ATTEMPTS) {
            // Require enough budget for a minimum-length call, not merely
            // a non-zero remainder: the per-request deadline has a floor,
            // so a job with seconds left would otherwise start a
            // two-minute request and overshoot its own ceiling — paying
            // for a generation it has already decided not to wait for.
            io.itsikh.finnencer.core.work.ensureJobBudget(
                "${option.id} request",
                minRequiredMs = io.itsikh.finnencer.core.work.MIN_CALL_DEADLINE_MS,
            )
            // Recomputed per attempt, not hoisted: the deadline is
            // clamped against the REMAINING job budget, which shrinks as
            // attempts burn time. A hoisted value would overstate the
            // deadline on attempt 2+, so a call that really did exhaust
            // its (smaller) allowance would be misread as a transient
            // blip and retried again.
            val deadlineMs = io.itsikh.finnencer.core.work.textCallDeadlineSeconds(maxTokens) * 1000
            val startedAt = android.os.SystemClock.elapsedRealtime()
            try {
                return runOne(option, system, userMessage, maxTokens, temperature, cacheSystem, effort)
            } catch (ce: CancellationException) {
                throw ce
            } catch (be: io.itsikh.finnencer.core.work.JobBudgetExceededException) {
                throw be
            } catch (t: Throwable) {
                val elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt
                if (!isWorthRetryingSameModel(t, elapsedMs, deadlineMs) || attempt == TRANSIENT_ATTEMPTS) throw t
                lastErr = t
                val planned = attempt * TRANSIENT_BACKOFF_STEP_MS
                val backoffMs = io.itsikh.finnencer.core.work.backoffWithinBudget(planned)
                    ?: throw t // not enough budget left for another attempt to be useful
                AppLogger.w(
                    TAG,
                    "${option.id} transient failure after ${elapsedMs / 1000}s " +
                        "(${t.javaClass.simpleName}: ${t.message?.take(120)}); " +
                        "retry ${attempt + 1}/$TRANSIENT_ATTEMPTS after up to ${backoffMs / 1000}s",
                )
                // Wakes early the moment connectivity returns.
                networkAvailability.awaitNetwork(backoffMs)
            }
        }
        throw lastErr ?: IllegalStateException("unreachable")
    }

    /**
     * Whether [t] is worth another attempt against the SAME model.
     *
     * HTTP-level classification is unchanged: 408/429/5xx retry, other
     * 4xx are permanent (switching models doesn't fix auth or a bad
     * request).
     *
     * The new distinction is among network failures, which all arrive as
     * IOException and used to be retried indiscriminately:
     *
     *  - **Died early** (connect refused, DNS, reset — [elapsedMs] well
     *    short of the call's own deadline): genuinely transient, and
     *    cheap to retry. This is the case that matters on a flaky LAN,
     *    where the same call succeeds seconds later.
     *  - **Ran out of clock** ([elapsedMs] at or near [deadlineMs]): the
     *    request was accepted and the model was generating. Two more
     *    identical attempts re-bill the same generation and burn 2x the
     *    deadline to arrive at the same place. Fail through instead, so
     *    the ranked walk tries a *different* model — which is the only
     *    thing that plausibly helps.
     *
     * Timing is used rather than the exception message because OkHttp's
     * connect- and read-timeout SocketTimeoutExceptions aren't reliably
     * distinguishable by text across platforms.
     */
    private fun isWorthRetryingSameModel(t: Throwable, elapsedMs: Long, deadlineMs: Long): Boolean {
        val causes = generateSequence(t) { cur -> cur.cause.takeIf { it !== cur } }.toList()
        val http = causes.filterIsInstance<retrofit2.HttpException>().firstOrNull()
        if (http != null) {
            val code = http.code()
            return code == 408 || code == 429 || code >= 500
        }
        if (!causes.any { it is java.io.IOException }) return false
        if (elapsedMs >= deadlineMs * DEADLINE_EXHAUSTION_RATIO) {
            AppLogger.w(
                TAG,
                "call exhausted its ${deadlineMs / 1000}s deadline (${elapsedMs / 1000}s elapsed) — " +
                    "treating as permanent for this model; falling through to the next candidate",
            )
            return false
        }
        return true
    }

    private suspend fun runOne(
        option: AiModelOption,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double?,
        cacheSystem: Boolean,
        effort: AiEffort?,
    ): AiCompletion {
        val client: AiTextClient = when (option.provider) {
            AiProvider.ANTHROPIC -> anthropic
            AiProvider.GEMINI -> gemini
        }
        // Symbolic ids (e.g. "latest Gemini Pro") are resolved to a real
        // model here, at the last moment before the call, so a cache
        // refresh takes effect without restarting the job. `resolved`
        // carries the CONCRETE id onward: callers persist
        // completion.modelUsed.id to the podcast/report row, and
        // recording a sentinel there would leave no record of what
        // actually generated the artifact.
        val resolved = resolveSymbolic(option)
        val result = client.complete(
            model = resolved.id,
            system = system,
            userMessage = userMessage,
            maxTokens = maxTokens,
            temperature = temperature,
            cacheSystem = cacheSystem,
            effort = effort,
        )
        return AiCompletion(text = result.text, stopReason = result.stopReason, modelUsed = resolved)
    }

    /**
     * Turn a symbolic model option into a concrete one. Non-symbolic
     * options pass through untouched.
     *
     * Resolution never fails the call: [GeminiModelCatalog] falls back to
     * a pinned id when discovery is unavailable (no key, no network,
     * empty listing), so the wire always carries a real model id.
     */
    private suspend fun resolveSymbolic(option: AiModelOption): AiModelOption {
        val builtin = (option as? AiModelOption.Builtin)?.model ?: return option
        if (!builtin.isSymbolic) return option
        val concreteId = geminiCatalog.latestProId()
        return AiModelOption.Custom(
            id = concreteId,
            displayName = friendlyModelLabel(concreteId) ?: concreteId,
            provider = builtin.provider,
            tier = builtin.tier,
        )
    }

    /**
     * Helper for cases that want to extract a JSON block from the response
     * (scorer, etc.). Reuses [ClaudeClient.extractJson] regardless of which
     * provider answered.
     */
    fun extractJson(s: String): String? = anthropic.extractJson(s)

    private companion object {
        const val TAG = "AiRouter"
        /** 3 tries per model: initial + two retries at 8s/16s backoff.
         *  Kept small — the ranked-model fallback (and for podcasts the
         *  worker-level retry) sits above this. */
        const val TRANSIENT_ATTEMPTS = 3
        const val TRANSIENT_BACKOFF_STEP_MS = 8_000L
        /** A failure at or past this fraction of the call's own deadline
         *  is treated as deadline exhaustion rather than a transient
         *  blip. Slightly under 1.0 because OkHttp raises a moment before
         *  the nominal limit and the elapsed measurement excludes some
         *  request-building overhead. */
        const val DEADLINE_EXHAUSTION_RATIO = 0.9
        /** Rescue-model ceiling per call: default + at most both providers'
         *  tier-matched builtins. Bounds worst-case total attempts. */
        const val MAX_RESCUES = 3
    }
}

data class AiCompletion(
    val text: String,
    val modelUsed: AiModelOption,
    /** Provider-normalized finish reason; `"max_tokens"` indicates the
     *  output is truncated and the caller may want a continuation pass. */
    val stopReason: String? = null,
)
