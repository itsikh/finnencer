package io.itsikh.finnencer.data.ai

import com.google.gson.Gson
import io.itsikh.finnencer.data.api.AnthropicMessage
import io.itsikh.finnencer.data.api.AnthropicRequest
import io.itsikh.finnencer.data.api.AnthropicResponse
import io.itsikh.finnencer.data.api.AnthropicService
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
    const val SONNET = "claude-sonnet-4-6"

    /** 1M-context model for DEEP earnings reports. */
    const val OPUS = "claude-opus-4-7"
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
    ): String {
        val entry = AiModel.byId(model)
        // Strip temperature for models that no longer accept it (Opus 4.x).
        // Unknown ids default to "supports it" so we don't silently change
        // behaviour for newly-discovered Gemini models.
        val effectiveTemperature = if (entry?.supportsTemperature == false) null else temperature
        val request = AnthropicRequest(
            model = model,
            maxTokens = maxTokens,
            system = system,
            messages = listOf(AnthropicMessage(role = "user", content = userMessage)),
            temperature = effectiveTemperature,
        )
        // The 1M-context variant of Opus 4.x requires the per-request
        // `anthropic-beta` header; without it the server returns HTTP 400.
        // Decide based on the catalog entry's declared context window so
        // future models inherit the right behavior automatically.
        val beta = entry
            ?.takeIf { it.provider == AiProvider.ANTHROPIC && it.maxContextTokens > 200_000 }
            ?.let { "context-1m-2025-08-07" }
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
        return response.content.firstOrNull { it.type == "text" }?.text
            ?: response.content.firstOrNull()?.text
            ?: error("Empty response from Anthropic for model $model")
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
        var depth = 0
        for (i in start until s.length) {
            val c = s[i]
            if (c == open) depth++
            else if (c == close) {
                depth--
                if (depth == 0) return s.substring(start, i + 1)
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
        val inputTokens = usage?.inputTokens ?: 0
        val outputTokens = usage?.outputTokens ?: 0
        apiUsageDao.insert(
            ApiUsage(
                provider = "Anthropic",
                endpoint = "v1/messages [$model]",
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costMillicents = estimateCostMillicents(model, inputTokens, outputTokens),
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
     */
    private fun estimateCostMillicents(model: String, inputTokens: Int, outputTokens: Int): Long {
        // Per million tokens: (input_usd, output_usd)
        val (inPerM, outPerM) = when {
            model.contains("haiku") -> 1.0 to 5.0
            model.contains("opus") -> 15.0 to 75.0
            else /* sonnet */ -> 3.0 to 15.0
        }
        val cents = (inputTokens / 1_000_000.0) * inPerM * 100 +
                (outputTokens / 1_000_000.0) * outPerM * 100
        val millicents = (cents * 1000).toLong()
        return millicents
    }
}
