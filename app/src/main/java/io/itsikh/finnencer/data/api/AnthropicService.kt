package io.itsikh.finnencer.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Anthropic Messages API.  Fleshed out in Build A·9 (importance scoring,
 * summaries, earnings reports). Stub here so the Retrofit graph compiles
 * after A·6.
 */
interface AnthropicService {

    @POST("v1/messages")
    suspend fun messages(@Body request: AnthropicRequest): AnthropicResponse
}

data class AnthropicRequest(
    val model: String,
    @SerializedName("max_tokens") val maxTokens: Int,
    val system: String? = null,
    val messages: List<AnthropicMessage>,
    val temperature: Double? = null,
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

data class AnthropicUsage(
    @SerializedName("input_tokens") val inputTokens: Int = 0,
    @SerializedName("output_tokens") val outputTokens: Int = 0,
)
