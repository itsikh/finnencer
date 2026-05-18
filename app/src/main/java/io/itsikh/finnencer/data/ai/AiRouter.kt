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
) {

    suspend fun complete(
        usage: AiUsage,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double? = null,
    ): AiCompletion {
        val ranked = prefs.getRanked(usage)
        return runRanked(usage, ranked, system, userMessage, maxTokens, temperature)
    }

    /** Direct model override (used when a feature needs a specific tier regardless of prefs). */
    suspend fun completeWith(
        model: AiModel,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double? = null,
    ): AiCompletion = runOne(AiModelOption.Builtin(model), system, userMessage, maxTokens, temperature)

    private suspend fun runRanked(
        usage: AiUsage,
        ranked: List<AiModelOption>,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double?,
    ): AiCompletion {
        var lastError: Throwable? = null
        ranked.forEachIndexed { index, option ->
            try {
                return runOne(option, system, userMessage, maxTokens, temperature)
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
        throw lastError ?: IllegalStateException("AiRouter: empty ranked list for $usage")
    }

    private suspend fun runOne(
        option: AiModelOption,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double?,
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
        )
        return AiCompletion(text = result.text, stopReason = result.stopReason, modelUsed = option)
    }

    /**
     * Helper for cases that want to extract a JSON block from the response
     * (scorer, etc.). Reuses [ClaudeClient.extractJson] regardless of which
     * provider answered.
     */
    fun extractJson(s: String): String? = anthropic.extractJson(s)

    private companion object { const val TAG = "AiRouter" }
}

data class AiCompletion(
    val text: String,
    val modelUsed: AiModelOption,
    /** Provider-normalized finish reason; `"max_tokens"` indicates the
     *  output is truncated and the caller may want a continuation pass. */
    val stopReason: String? = null,
)
