package io.itsikh.finnencer.data.ai

/** Provider-agnostic text completion. Same signature for Claude + Gemini. */
interface AiTextClient {
    /**
     * @param model model ID (whatever the provider expects; the [AiRouter]
     *        passes the correct one based on [AiModel.provider]).
     * @param system optional system prompt; Gemini concatenates this into
     *        the user message since it has no separate system role.
     * @param userMessage the user content
     * @param maxTokens upper bound on tokens emitted
     * @param temperature 0.0 - 1.0
     */
    suspend fun complete(
        model: String,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double? = null,
    ): String
}
