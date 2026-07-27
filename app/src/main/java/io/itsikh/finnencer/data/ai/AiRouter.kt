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
        return runRanked(usage, ranked, system, userMessage, maxTokens, temperature, cacheSystem)
    }

    /** Direct model override (used when a feature needs a specific tier regardless of prefs). */
    suspend fun completeWith(
        model: AiModel,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double? = null,
        cacheSystem: Boolean = false,
    ): AiCompletion = runOneWithTransientRetry(AiModelOption.Builtin(model), system, userMessage, maxTokens, temperature, cacheSystem)

    private suspend fun runRanked(
        usage: AiUsage,
        ranked: List<AiModelOption>,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double?,
        cacheSystem: Boolean,
    ): AiCompletion {
        var lastError: Throwable? = null
        ranked.forEachIndexed { index, option ->
            try {
                return runOneWithTransientRetry(option, system, userMessage, maxTokens, temperature, cacheSystem)
            } catch (ce: CancellationException) {
                throw ce
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
        // Emergency cross-provider fallback: every ranked model failed AND
        // the failure class is "endpoint unreachable" (pure connectivity —
        // no HTTP status ever came back). Networks can block one provider
        // while the other works fine (#86 — api.anthropic.com was
        // unreachable for over an hour while Google's endpoints worked),
        // so try the OTHER provider once before failing a paid job. HTTP
        // errors (auth, quota, bad request) never trigger this — those
        // aren't fixed by switching providers... except quota, but the
        // ranked list is the user-intent place for that.
        val err = lastError
        if (err != null && isConnectivityFailure(err)) {
            crossProviderFallback(usage, ranked)?.let { fallback ->
                AppLogger.w(
                    TAG,
                    "[$usage] all ranked model(s) unreachable; emergency cross-provider fallback to ${fallback.id}",
                )
                try {
                    return runOneWithTransientRetry(
                        AiModelOption.Builtin(fallback), system, userMessage, maxTokens, temperature, cacheSystem,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "[$usage] cross-provider fallback ${fallback.id} also failed", t)
                    lastError = t
                }
            }
        }
        throw lastError ?: IllegalStateException("AiRouter: empty ranked list for $usage")
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
     * A configured model on a provider the ranked list did NOT already
     * try, tier-matched to the usage's default. Null when the other
     * provider's key isn't configured or every provider was already tried.
     */
    private fun crossProviderFallback(usage: AiUsage, ranked: List<AiModelOption>): AiModel? {
        val tried = ranked.mapTo(HashSet()) { it.provider }
        val wantLarge = usage.defaultModel.tier == AiTier.LARGE
        return when {
            AiProvider.GEMINI !in tried &&
                apiKeys.isConfigured(io.itsikh.finnencer.data.repo.ApiKey.GEMINI) ->
                if (wantLarge) AiModel.GEMINI_3_1_PRO else AiModel.GEMINI_3_6_FLASH

            AiProvider.ANTHROPIC !in tried &&
                apiKeys.isConfigured(io.itsikh.finnencer.data.repo.ApiKey.ANTHROPIC) ->
                if (wantLarge) AiModel.CLAUDE_OPUS_5 else AiModel.CLAUDE_SONNET_5

            else -> null
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
    ): AiCompletion {
        var lastErr: Throwable? = null
        for (attempt in 1..TRANSIENT_ATTEMPTS) {
            try {
                return runOne(option, system, userMessage, maxTokens, temperature, cacheSystem)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (!isTransient(t) || attempt == TRANSIENT_ATTEMPTS) throw t
                lastErr = t
                val backoffMs = attempt * TRANSIENT_BACKOFF_STEP_MS
                AppLogger.w(
                    TAG,
                    "${option.id} transient failure (${t.javaClass.simpleName}: ${t.message?.take(120)}); " +
                        "retry ${attempt + 1}/$TRANSIENT_ATTEMPTS after up to ${backoffMs / 1000}s",
                )
                // Wakes early the moment connectivity returns.
                networkAvailability.awaitNetwork(backoffMs)
            }
        }
        throw lastErr ?: IllegalStateException("unreachable")
    }

    /** Transient = worth retrying on the SAME model. The clients wrap
     *  HttpException in IOException for friendlier messages, so classify
     *  by the wrapped status when present: 408/429/5xx retry, other 4xx
     *  are permanent. A bare IOException (timeout, reset, DNS) retries. */
    private fun isTransient(t: Throwable): Boolean {
        val causes = generateSequence(t) { cur -> cur.cause.takeIf { it !== cur } }.toList()
        val http = causes.filterIsInstance<retrofit2.HttpException>().firstOrNull()
        if (http != null) {
            val code = http.code()
            return code == 408 || code == 429 || code >= 500
        }
        return causes.any { it is java.io.IOException }
    }

    private suspend fun runOne(
        option: AiModelOption,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double?,
        cacheSystem: Boolean,
    ): AiCompletion {
        val client: AiTextClient = when (option.provider) {
            AiProvider.ANTHROPIC -> anthropic
            AiProvider.GEMINI -> gemini
        }
        val result = client.complete(
            model = option.id,
            system = system,
            userMessage = userMessage,
            maxTokens = maxTokens,
            temperature = temperature,
            cacheSystem = cacheSystem,
        )
        return AiCompletion(text = result.text, stopReason = result.stopReason, modelUsed = option)
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
    }
}

data class AiCompletion(
    val text: String,
    val modelUsed: AiModelOption,
    /** Provider-normalized finish reason; `"max_tokens"` indicates the
     *  output is truncated and the caller may want a continuation pass. */
    val stopReason: String? = null,
)
