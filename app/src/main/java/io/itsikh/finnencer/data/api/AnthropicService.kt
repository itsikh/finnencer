package io.itsikh.finnencer.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Anthropic Messages API.  Fleshed out in Build A·9 (importance scoring,
 * summaries, earnings reports). Stub here so the Retrofit graph compiles
 * after A·6.
 */
interface AnthropicService {

    /**
     * @param beta optional value for the per-request `anthropic-beta`
     *        header. Required when invoking the 1M-context variant of
     *        Opus 4.x ("context-1m-2025-08-07"); a null value tells
     *        Retrofit to omit the header so standard 200K-context calls
     *        aren't affected.
     */
    @POST("v1/messages")
    suspend fun messages(
        @Body request: AnthropicRequest,
        @Header("anthropic-beta") beta: String? = null,
    ): AnthropicResponse
}

/**
 * Body of POST /v1/messages.
 *
 * [system] is `Any?` to support both shapes Anthropic accepts:
 *  - a plain string (cheapest to serialize, no caching)
 *  - a list of [AnthropicSystemBlock] entries, the last of which may
 *    carry `cache_control` to mark the prefix as cacheable
 *    (PROMPT_CACHE_EPHEMERAL — 5-minute TTL, billed at ~10% of input).
 *
 * Use the plain-string form when caching isn't wanted; use the block
 * form to opt into prompt caching for system prompts that are stable
 * across calls. Gson serializes whichever shape is passed transparently.
 */
data class AnthropicRequest(
    val model: String,
    @SerializedName("max_tokens") val maxTokens: Int,
    val system: Any? = null,
    val messages: List<AnthropicMessage>,
    val temperature: Double? = null,
)

/**
 * One block inside the structured `system` array. When [cacheControl]
 * is non-null, Anthropic caches the prefix up to and including this
 * block; subsequent requests reusing the exact same prefix read from
 * the cache at a fraction of the input-token cost.
 */
data class AnthropicSystemBlock(
    val type: String = "text",
    val text: String,
    @SerializedName("cache_control") val cacheControl: AnthropicCacheControl? = null,
)

/**
 * Marks a system / user content block as cacheable. Type is always
 * `"ephemeral"` for the 5-minute cache tier (the only tier the
 * Generative-Language SDK exposes today). Anthropic ignores
 * `cache_control` on prompts below the per-model minimum length, so
 * setting this on a too-short prompt is a harmless no-op.
 */
data class AnthropicCacheControl(
    val type: String = "ephemeral",
)

data class AnthropicMessage(
    val role: String, // "user" | "assistant"
    val content: String,
)

data class AnthropicResponse(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val model: String? = null,
    val content: List<AnthropicContentBlock> = emptyList(),
    @SerializedName("stop_reason") val stopReason: String? = null,
    val usage: AnthropicUsage? = null,
)

data class AnthropicContentBlock(
    val type: String? = null,
    val text: String? = null,
)

/**
 * Token-usage telemetry from a Messages response. The two cache fields
 * are only populated when prompt caching is in play:
 *  - [cacheCreationInputTokens]: tokens written into a fresh cache
 *    entry on this call (priced at ~1.25× input).
 *  - [cacheReadInputTokens]: tokens served from a previously-warm
 *    cache (priced at ~0.10× input).
 */
data class AnthropicUsage(
    @SerializedName("input_tokens") val inputTokens: Int = 0,
    @SerializedName("output_tokens") val outputTokens: Int = 0,
    @SerializedName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0,
    @SerializedName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0,
)
