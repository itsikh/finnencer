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

    /**
     * Resolve the model for [usage] and run the completion. Returns the raw
     * text response.
     */
    suspend fun complete(
        usage: AiUsage,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double? = null,
    ): AiCompletion {
        val model = prefs.get(usage)
        val client: AiTextClient = when (model.provider) {
            AiProvider.ANTHROPIC -> anthropic
            AiProvider.GEMINI -> gemini
        }
        val text = client.complete(
            model = model.id,
            system = system,
            userMessage = userMessage,
            maxTokens = maxTokens,
            temperature = temperature,
        )
        return AiCompletion(text = text, modelUsed = model)
    }

    /** Direct model override (used when a feature needs a specific tier regardless of prefs). */
    suspend fun completeWith(
        model: AiModel,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double? = null,
    ): AiCompletion {
        val client: AiTextClient = when (model.provider) {
            AiProvider.ANTHROPIC -> anthropic
            AiProvider.GEMINI -> gemini
        }
        val text = client.complete(
            model = model.id,
            system = system,
            userMessage = userMessage,
            maxTokens = maxTokens,
            temperature = temperature,
        )
        return AiCompletion(text = text, modelUsed = model)
    }

    /**
     * Helper for cases that want to extract a JSON block from the response
     * (scorer, etc.). Reuses [ClaudeClient.extractJson] regardless of which
     * provider answered.
     */
    fun extractJson(s: String): String? = anthropic.extractJson(s)
}

data class AiCompletion(val text: String, val modelUsed: AiModel)
