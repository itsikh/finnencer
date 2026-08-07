package io.itsikh.finnencer.data.ai

import com.google.gson.Gson
import io.itsikh.finnencer.data.api.AnthropicCacheControl
import io.itsikh.finnencer.data.api.AnthropicMessage
import io.itsikh.finnencer.data.api.AnthropicOutputConfig
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
    /** Default model for scoring, summaries and BRIEF earnings reports. */
    const val SONNET = "claude-sonnet-5"

    /** Reports, podcast scripts. 1M context by default. */
    const val OPUS = "claude-opus-5"

    /** Most capable tier; used for podcast script validation. */
    const val FABLE = "claude-fable-5"
}

/**
 * The model's safety classifiers declined the request. Surfaces as an
 * HTTP 200 with `stop_reason: "refusal"`, so it isn't caught by any
 * status-code handling.
 *
 * Not an IOException by design — see the throw site in [ClaudeClient].
 */
class AiRefusalException(val model: String) : RuntimeException(
    "$model declined this request. Trying a different model may succeed.",
)

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
        effort: AiEffort?,
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
        // Thinking is ON for every model that supports configuring it.
        // Earlier builds sent {"type":"disabled"} to hold cost and
        // behaviour steady across the Opus 5 / Sonnet 5 migration, but
        // that trades away the reasoning these workloads most benefit
        // from — and on Opus 5 specifically, disabled thinking is a
        // documented source of `<thinking>` tags leaking into the visible
        // response, which in a Markdown report renders as raw XML.
        // Cost and latency are controlled with `effort` instead.
        val thinking: Map<String, String>? = when (entry?.thinking) {
            AiThinkingMode.ADAPTIVE -> mapOf("type" to "adaptive")
            // Fable 5 (ALWAYS_ON) and unknown ids (OMIT) send nothing.
            else -> null
        }
        // Gated on the model: pre-5 Anthropic models return 400 when
        // `output_config.effort` is present, and unknown ids default to
        // not supporting it.
        val outputConfig = effort
            ?.takeIf { entry?.supportsEffort == true }
            ?.let { AnthropicOutputConfig(effort = it.wire) }
        val request = AnthropicRequest(
            model = model,
            maxTokens = maxTokens,
            system = systemField,
            messages = listOf(AnthropicMessage(role = "user", content = userMessage)),
            temperature = effectiveTemperature,
            thinking = thinking,
            outputConfig = outputConfig,
        )
        // Opus 5 / Sonnet 5 serve the 1M context window by default — the
        // `context-1m-2025-08-07` beta header the Opus 4.7 era needed is
        // gone along with the 4.x catalog entries.
        val beta: String? = null
        // Non-streaming call: the socket stays silent until generation
        // finishes, so this request's read timeout is sized from its own
        // output budget rather than shared app-wide. Also clamped against
        // the remaining job budget, so a near-exhausted job doesn't hand
        // one doomed call everything it has left.
        val deadlineSeconds = io.itsikh.finnencer.core.work.textCallDeadlineSeconds(maxTokens)
        val started = System.currentTimeMillis()
        val response = try {
            service.messages(request, beta, deadlineSeconds.toString())
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
        // A safety-classifier decline arrives as a SUCCESSFUL HTTP 200
        // with an empty content array and stop_reason "refusal" — not as
        // an error status. Without this branch it surfaced as a bare
        // "Empty response from Anthropic", which is both misleading and
        // unactionable. Opus 5 and Fable 5 both ship elevated classifiers,
        // so this is reachable in normal operation.
        //
        // Deliberately NOT an IOException: AiRouter would classify that as
        // transient and retry the same model, which cannot help. As a
        // plain exception it falls straight through to the next ranked
        // model — the only move that might actually succeed.
        if (response.stopReason == "refusal") {
            throw AiRefusalException(model)
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
                costMillicents = ModelCost.anthropicCallMillicents(
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

}
