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
) {

    suspend fun complete(
        model: String,
        system: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double? = null,
    ): String {
        val request = AnthropicRequest(
            model = model,
            maxTokens = maxTokens,
            system = system,
            messages = listOf(AnthropicMessage(role = "user", content = userMessage)),
            temperature = temperature,
        )
        val started = System.currentTimeMillis()
        val response = runCatching { service.messages(request) }
            .onSuccess { resp -> recordUsage(model, resp, started, ok = true, error = null) }
            .onFailure { t -> recordUsage(model, null, started, ok = false, error = t.message) }
            .getOrThrow()
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
        // Try fenced code first
        Regex("```(?:json)?\\s*(\\{.*?}|\\[.*?])\\s*```", RegexOption.DOT_MATCHES_ALL)
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
