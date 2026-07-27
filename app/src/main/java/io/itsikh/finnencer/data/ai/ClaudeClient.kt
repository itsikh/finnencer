package io.itsikh.finnencer.data.ai

import com.google.gson.Gson
import io.itsikh.finnencer.data.api.AnthropicCacheControl
import io.itsikh.finnencer.data.api.AnthropicMessage
import io.itsikh.finnencer.data.api.AnthropicRequest
import io.itsikh.finnencer.data.api.AnthropicResponse
import io.itsikh.finnencer.data.api.AnthropicService
import io.itsikh.finnencer.data.api.AnthropicSystemBlock
import io.itsikh.finnencer.data.dao.ApiUsageDao
import io.itsikh.finnencer.data.entity.ApiUsage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Models finnencer uses. The IDs come from the Anthropic catalog.
 */
object ClaudeModels {
    /** Cheap, fast classifier model used by the importance scorer. */
    const val HAIKU = "claude-haiku-4-5-20251001"

    /** Default model for article summaries + BRIEF/STANDARD earnings reports. */
    const val SONNET = "claude-sonnet-5"

    /** 1M-context model for DEEP earnings reports. */
    const val OPUS = "claude-opus-5"
}

/**
 * Thin wrapper around [AnthropicService] that
 *  - posts a single user message with optional system prompt
 *  - extracts the first text block from the response
 *  - records token usage into [ApiUsageDao] so the cost meter (A·13) can
 *    show the user what each provider is costing them per day
 */
@Singleton
class ClaudeClient @Inject constructor(
    private val service: AnthropicService,
    private val apiUsageDao: ApiUsageDao,
    private val gson: Gson,
) : AiTextClient {

    override suspend fun complete(
        model: String,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double?,
        cacheSystem: Boolean,
    ): AiTextClient.TextResult {
        val entry = AiModel.byId(model)
        // Strip temperature for models that no longer accept it (Opus 4.x).
        // Unknown ids default to "supports it" so we don't silently change
        // behaviour for newly-discovered Gemini models.
        val effectiveTemperature = if (entry?.supportsTemperature == false) null else temperature
        // When the caller asks for caching AND there's an actual system
        // prompt, send the system as a single text block with
        // cache_control:ephemeral. Below the per-model cache threshold
        // Anthropic silently treats it as a non-cached block, so this
        // is safe to set even when we can't be sure the prompt is long
        // enough — there's no error, just no cache hit.
        val systemField: Any? = when {
            system.isNullOrBlank() -> null
            cacheSystem -> listOf(
                AnthropicSystemBlock(text = system, cacheControl = AnthropicCacheControl()),
            )
            else -> system
        }
        // Opus 5 / Sonnet 5 run adaptive thinking when the `thinking`
        // field is omitted, and max_tokens caps thinking + response text
        // together — our tightly-sized budgets would truncate mid-answer
        // and pay for thinking tokens. These are pure text-generation
        // calls (no tools), so disable it to keep behavior/cost parity.
        // Valid only at effort <= high; we never send effort (default
        // high), so this is always accepted.
        val thinking = if (model.startsWith("claude-opus-5") || model.startsWith("claude-sonnet-5")) {
            mapOf("type" to "disabled")
        } else null
        val request = AnthropicRequest(
            model = model,
            maxTokens = maxTokens,
            system = systemField,
            messages = listOf(AnthropicMessage(role = "user", content = userMessage)),
            temperature = effectiveTemperature,
            thinking = thinking,
        )
        // Opus 5 / Sonnet 5 serve the 1M context window by default — the
        // `context-1m-2025-08-07` beta header the Opus 4.7 era needed is
        // gone along with the 4.x catalog entries.
        val beta: String? = null
        val started = System.currentTimeMillis()
        val response = try {
            service.messages(request, beta)
                .also { recordUsage(model, it, started, ok = true, error = null) }
        } catch (e: retrofit2.HttpException) {
            // Retrofit's HttpException only carries "HTTP 4xx" in .message;
            // the actionable detail is in errorBody(). Read it eagerly so
            // logs/bug-reports show the provider's exact reason.
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            val detail = body?.take(500)?.replace("\n", " ") ?: "(no body)"
            recordUsage(model, null, started, ok = false, error = "HTTP ${e.code()}: $detail")
            throw java.io.IOException("Anthropic HTTP ${e.code()} on $model: $detail", e)
        } catch (t: Throwable) {
            recordUsage(model, null, started, ok = false, error = t.message)
            throw t
        }
        val text = response.content.firstOrNull { it.type == "text" }?.text
            ?: response.content.firstOrNull()?.text
            ?: error("Empty response from Anthropic for model $model")
        return AiTextClient.TextResult(text = text, stopReason = response.stopReason)
    }

    /**
     * Extracts the first JSON value enclosed in `{...}` or `[...]` from
     * [s] (Claude sometimes wraps JSON in prose / fenced code blocks despite
     * instructions). Returns null if none found.
     */
    fun extractJson(s: String): String? {
        // Try fenced code first. Android's ICU regex engine treats unescaped
        // `}` and `]` after a quantifier as a syntax error, so we escape both.
        Regex("```(?:json)?\\s*(\\{.*?\\}|\\[.*?\\])\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(s)?.let { return it.groupValues[1] }
        // Otherwise the first balanced { ... } or [ ... ]
        val firstObj = s.indexOf('{')
        val firstArr = s.indexOf('[')
        val start = when {
            firstObj == -1 -> firstArr
            firstArr == -1 -> firstObj
            else -> minOf(firstObj, firstArr)
        }
        if (start == -1) return null
        val open = s[start]
        val close = if (open == '{') '}' else ']'
        // String-aware scan: braces/brackets inside JSON string literals
        // (e.g. {"summary": "Q3 {beat}"}) must not affect the depth count,
        // so we track whether we're inside a string and skip escaped chars.
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                escaped -> escaped = false
                inString -> when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                c == '"' -> inString = true
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private suspend fun recordUsage(
        model: String,
        response: AnthropicResponse?,
        startedAtMillis: Long,
        ok: Boolean,
        error: String?,
    ) {
        val usage = response?.usage
        val freshInputTokens = usage?.inputTokens ?: 0
        val cacheCreate = usage?.cacheCreationInputTokens ?: 0
        val cacheRead = usage?.cacheReadInputTokens ?: 0
        val outputTokens = usage?.outputTokens ?: 0
        // Total input for the ApiUsage row sums fresh + cache I/O so
        // the user sees the real token count on the cost meter. The
        // per-bucket cost math lives below and is what actually drives
        // the millicent estimate.
        val totalInput = freshInputTokens + cacheCreate + cacheRead
        apiUsageDao.insert(
            ApiUsage(
                provider = "Anthropic",
                endpoint = "v1/messages [$model]",
                inputTokens = totalInput,
                outputTokens = outputTokens,
                costMillicents = estimateCostMillicents(
                    model = model,
                    freshInputTokens = freshInputTokens,
                    cacheCreationInputTokens = cacheCreate,
                    cacheReadInputTokens = cacheRead,
                    outputTokens = outputTokens,
                ),
                requestedAtMillis = startedAtMillis,
                ok = ok,
                errorMessage = error,
            )
        )
    }

    /**
     * Rough cost estimate in millicents (USD). Prices are best-effort
     * approximations of Anthropic's published rates at time of writing; if
     * they shift, the cost meter will drift but actual billing is unchanged.
     *
     * Prompt-caching pricing on the 5-minute (ephemeral) tier:
     *  - cache writes cost ~1.25× the base input rate
     *  - cache reads cost ~0.10× the base input rate
     * The fresh-input bucket is billed at the standard rate.
     */
    private fun estimateCostMillicents(
        model: String,
        freshInputTokens: Int,
        cacheCreationInputTokens: Int,
        cacheReadInputTokens: Int,
        outputTokens: Int,
    ): Long {
        // Per million tokens: (input_usd, output_usd)
        val (inPerM, outPerM) = when {
            model.contains("haiku") -> 1.0 to 5.0
            // Opus 4.6 through Opus 5 are all $5/$25 per MTok. The old
            // 15/75 figure was Opus-4.1-era pricing and overstated every
            // Opus call ~3x.
            model.contains("opus") -> 5.0 to 25.0
            else /* sonnet */ -> 3.0 to 15.0
        }
        val freshCost = (freshInputTokens / 1_000_000.0) * inPerM
        val createCost = (cacheCreationInputTokens / 1_000_000.0) * inPerM * 1.25
        val readCost = (cacheReadInputTokens / 1_000_000.0) * inPerM * 0.10
        val outputCost = (outputTokens / 1_000_000.0) * outPerM
        val cents = (freshCost + createCost + readCost + outputCost) * 100
        return (cents * 1000).toLong()
    }
}
