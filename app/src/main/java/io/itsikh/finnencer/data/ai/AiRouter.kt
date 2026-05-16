package io.itsikh.finnencer.data.ai

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entrypoint for "complete some text" calls. Reads the user's
 * configured model for the given [AiUsage] and dispatches to the matching
 * provider client. Call sites never branch on provider.
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
        val option = prefs.get(usage)
        return run(option, system, userMessage, maxTokens, temperature)
    }

    /** Direct model override (used when a feature needs a specific tier regardless of prefs). */
    suspend fun completeWith(
        model: AiModel,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double? = null,
    ): AiCompletion = run(AiModelOption.Builtin(model), system, userMessage, maxTokens, temperature)

    private suspend fun run(
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
        val text = client.complete(
            model = option.id,
            system = system,
            userMessage = userMessage,
            maxTokens = maxTokens,
            temperature = temperature,
        )
        return AiCompletion(text = text, modelUsed = option)
    }

    /**
     * Helper for cases that want to extract a JSON block from the response
     * (scorer, etc.). Reuses [ClaudeClient.extractJson] regardless of which
     * provider answered.
     */
    fun extractJson(s: String): String? = anthropic.extractJson(s)
}

data class AiCompletion(val text: String, val modelUsed: AiModelOption)
