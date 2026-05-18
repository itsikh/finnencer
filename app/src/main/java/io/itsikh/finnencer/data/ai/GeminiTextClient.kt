package io.itsikh.finnencer.data.ai

import io.itsikh.finnencer.data.api.GeminiContent
import io.itsikh.finnencer.data.api.GeminiGenerateRequest
import io.itsikh.finnencer.data.api.GeminiGenerationConfig
import io.itsikh.finnencer.data.api.GeminiPart
import io.itsikh.finnencer.data.api.GeminiService
import io.itsikh.finnencer.data.dao.ApiUsageDao
import io.itsikh.finnencer.data.entity.ApiUsage
import io.itsikh.finnencer.logging.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text-only completion via Gemini's generateContent endpoint. Used when the
 * [AiRouter] sees an [AiProvider.GEMINI] selection for any text usage.
 *
 * Gemini has no separate `system` role like Claude does, so the system prompt
 * is folded into the user message as a leading block.
 */
@Singleton
class GeminiTextClient @Inject constructor(
    private val service: GeminiService,
    private val apiUsageDao: ApiUsageDao,
) : AiTextClient {

    override suspend fun complete(
        model: String,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double?,
    ): AiTextClient.TextResult {
        val merged = buildString {
            if (!system.isNullOrBlank()) {
                append("System instructions:\n")
                append(system.trim())
                append("\n\n---\n\n")
            }
            append(userMessage)
        }
        val request = GeminiGenerateRequest(
            contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(text = merged)))),
            generationConfig = GeminiGenerationConfig(
                temperature = temperature,
                // Gemini's text generation honors maxOutputTokens here; not
                // related to the responseModalities (audio) used by TTS.
                responseModalities = null,
                speechConfig = null,
            ),
        )
        val startedAt = System.currentTimeMillis()
        val resp = runCatching { service.generateContent(model, request) }
            .onFailure { recordUsage(model, 0, 0, startedAt, ok = false, error = it.message) }
            .getOrThrow()
        val candidate = resp.candidates.firstOrNull()
        val text = candidate?.content?.parts?.mapNotNull { it.text }?.joinToString("")
            ?.trim().orEmpty()
        if (text.isBlank()) {
            AppLogger.w(TAG, "Gemini ($model) returned empty text")
            error("Gemini returned empty content")
        }
        val inputTokens = merged.length / 4
        val outputTokens = text.length / 4
        recordUsage(model, inputTokens, outputTokens, startedAt, ok = true, error = null)
        // Normalize Gemini's finish reason to the same vocabulary
        // Anthropic uses, so callers don't need to branch by provider.
        val normalizedStop = when (candidate?.finishReason?.uppercase()) {
            "MAX_TOKENS" -> "max_tokens"
            "STOP" -> "end_turn"
            null -> null
            else -> candidate.finishReason.lowercase()
        }
        return AiTextClient.TextResult(text = text, stopReason = normalizedStop)
    }

    private suspend fun recordUsage(
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        startedAt: Long,
        ok: Boolean,
        error: String?,
    ) {
        // Approximate Gemini text pricing (2026 rates): Flash $0.075/M in,
        // $0.30/M out; Pro $1.25/M in, $5.00/M out.
        val (inPerM, outPerM) = when {
            model.contains("pro") -> 1.25 to 5.00
            else -> 0.075 to 0.30
        }
        val cents = (inputTokens / 1_000_000.0) * inPerM * 100 +
                (outputTokens / 1_000_000.0) * outPerM * 100
        apiUsageDao.insert(
            ApiUsage(
                provider = "Gemini text",
                endpoint = "generateContent [$model]",
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costMillicents = (cents * 1000).toLong(),
                requestedAtMillis = startedAt,
                ok = ok,
                errorMessage = error,
            )
        )
    }

    private companion object { const val TAG = "GeminiTextClient" }
}
