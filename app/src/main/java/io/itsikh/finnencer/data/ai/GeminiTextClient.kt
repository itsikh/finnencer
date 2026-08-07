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
    private val catalog: GeminiModelCatalog,
) : AiTextClient {

    override suspend fun complete(
        model: String,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double?,
        // Caching is currently Anthropic-only; Gemini accepts the
        // parameter for interface symmetry and ignores it.
        cacheSystem: Boolean,
        // Likewise: generateContent has no reasoning-depth knob, so the
        // per-usage effort hint is accepted and ignored here.
        effort: AiEffort?,
    ): AiTextClient.TextResult {
        val merged = buildString {
            if (!system.isNullOrBlank()) {
                append("System instructions:\n")
                append(system.trim())
                append("\n\n---\n\n")
            }
            append(userMessage)
        }
        // Clamp to the model's own output ceiling when discovery told us
        // what it is. Budgets here are sized for Anthropic models — the
        // podcast validator asks for up to 24k — and Gemini rejects a
        // maxOutputTokens above the model's limit outright. Left
        // unclamped, a cross-provider fallback would 400 on every call
        // and quietly never work, which is the worst kind of fallback.
        // Unknown model = send the caller's budget unchanged rather than
        // guess a ceiling.
        val limit = catalog.outputLimitFor(model)
        val effectiveMaxTokens = limit?.let { minOf(maxTokens, it) } ?: maxTokens
        if (limit != null && effectiveMaxTokens < maxTokens) {
            AppLogger.i(TAG, "clamped maxOutputTokens $maxTokens -> $effectiveMaxTokens for $model")
        }
        val request = GeminiGenerateRequest(
            contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(text = merged)))),
            generationConfig = GeminiGenerationConfig(
                temperature = temperature,
                // Gemini's text generation honors maxOutputTokens here; not
                // related to the responseModalities (audio) used by TTS.
                maxOutputTokens = effectiveMaxTokens,
                responseModalities = null,
                speechConfig = null,
            ),
        )
        // Per-request read timeout sized from the output budget and the
        // remaining job budget — see ClaudeClient for the reasoning. TTS
        // shares this Retrofit client but passes no header, so it keeps
        // the client's TTS-tuned default.
        val deadlineSeconds = io.itsikh.finnencer.core.work.textCallDeadlineSeconds(maxTokens)
        val startedAt = System.currentTimeMillis()
        val resp = try {
            service.generateContent(model, request, deadlineSeconds.toString())
        } catch (he: retrofit2.HttpException) {
            // Strip Retrofit's loss-of-detail default message and
            // attach Google's actual error body excerpt — same
            // pattern as GeminiTts.dispatchGenerateContent (#61) so
            // 403/400/etc. from the text path show e.g.
            // "PERMISSION_DENIED: Generative Language API has not
            // been used in project X" instead of a bare
            // "HTTP 403 " (#63 was undebuggable for exactly this
            // reason).
            val body = runCatching { he.response()?.errorBody()?.string() }.getOrNull()
            val shortBody = body?.takeIf { it.isNotBlank() }
                ?.replace(Regex("\\s+"), " ")
                ?.take(600)
            recordUsage(model, 0, 0, startedAt, ok = false, error = he.message)
            throw java.io.IOException(
                "Gemini HTTP ${he.code()}" + (shortBody?.let { ": $it" } ?: ""),
                he,
            )
        } catch (t: Throwable) {
            recordUsage(model, 0, 0, startedAt, ok = false, error = t.message)
            throw t
        }
        val candidate = resp.candidates.firstOrNull()
        val text = candidate?.content?.parts?.mapNotNull { it.text }?.joinToString("")
            ?.trim().orEmpty()
        if (text.isBlank()) {
            // finishReason is the actual diagnostic here: SAFETY /
            // RECITATION / MAX_TOKENS all present as "empty content".
            // Record a failed usage row too — this throw happens after
            // the HTTP try/catch, so nothing else will.
            val reason = candidate?.finishReason ?: "no candidates"
            val message = "Gemini returned empty content (finishReason=$reason)"
            AppLogger.w(TAG, "Gemini ($model) returned empty text (finishReason=$reason)")
            recordUsage(model, merged.length / 4, 0, startedAt, ok = false, error = message)
            error(message)
        }
        // Prefer the API's own accounting; the chars/4 estimate is only a
        // fallback for responses that omit usageMetadata. The estimate was
        // previously the ONLY path and materially mis-stated the cost
        // meter — the ratio doesn't hold for current tokenizers.
        val usage = resp.usageMetadata
        val inputTokens = usage?.promptTokenCount?.takeIf { it > 0 } ?: (merged.length / 4)
        val outputTokens = usage?.candidatesTokenCount?.takeIf { it > 0 } ?: (text.length / 4)
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
        // Single shared price table — the pre-tap estimate and the
        // actuals meter can no longer drift apart.
        val (inPerM, outPerM) = ModelCost.pricePerMillion(model)
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
