package io.itsikh.finnencer.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Gemini multi-speaker TTS. Used in Build B for podcast generation. Stub
 * here so the Retrofit graph compiles after A·6.
 */
interface GeminiService {

    /**
     * Serves both text generation (GeminiTextClient) and multi-speaker
     * TTS (GeminiTts).
     *
     * @param deadlineSeconds read timeout for THIS call, consumed and
     *        stripped by
     *        [io.itsikh.finnencer.core.net.DeadlineInterceptor]. Text
     *        callers size it from `max_tokens` and the remaining job
     *        budget; TTS leaves it null and keeps the client's
     *        TTS-tuned default.
     */
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Body request: GeminiGenerateRequest,
        @retrofit2.http.Header(io.itsikh.finnencer.core.net.DeadlineInterceptor.HEADER)
        deadlineSeconds: String? = null,
    ): GeminiGenerateResponse

    /** Cheap auth-only call; used by KeyValidator to verify the API key
     *  and by GeminiModelCatalog to resolve the newest Pro model. */
    @retrofit2.http.GET("v1beta/models")
    suspend fun listModels(
        @retrofit2.http.Header(io.itsikh.finnencer.core.net.DeadlineInterceptor.HEADER)
        deadlineSeconds: String? = null,
    ): GeminiModelsResponse
}

data class GeminiModelsResponse(
    val models: List<GeminiModelInfo> = emptyList(),
)

data class GeminiModelInfo(
    val name: String? = null,
    val displayName: String? = null,
    val description: String? = null,
    val supportedGenerationMethods: List<String>? = null,
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null,
)

data class GeminiGenerateRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
)

data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>,
)

data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null,
)

data class GeminiInlineData(
    val mimeType: String,
    val data: String, // base64
)

data class GeminiGenerationConfig(
    val temperature: Double? = null,
    // Output cap for text generation. Null (the default) omits the field
    // entirely, which is what the TTS path wants — audio renders are not
    // token-capped.
    @SerializedName("maxOutputTokens") val maxOutputTokens: Int? = null,
    val responseModalities: List<String>? = null,
    val speechConfig: GeminiSpeechConfig? = null,
)

data class GeminiSpeechConfig(
    val multiSpeakerVoiceConfig: GeminiMultiSpeakerConfig? = null,
)

data class GeminiMultiSpeakerConfig(
    val speakerVoiceConfigs: List<GeminiSpeakerVoiceConfig>,
)

data class GeminiSpeakerVoiceConfig(
    val speaker: String,
    val voiceConfig: GeminiVoiceConfig,
)

data class GeminiVoiceConfig(
    val prebuiltVoiceConfig: GeminiPrebuiltVoice,
)

data class GeminiPrebuiltVoice(val voiceName: String)

data class GeminiGenerateResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    /** Authoritative token counts. Absent on some error/partial responses,
     *  so callers must keep a fallback path. */
    val usageMetadata: GeminiUsageMetadata? = null,
)

/**
 * Token accounting reported by generateContent. Replaces the previous
 * `text.length / 4` guess, which mis-stated the cost meter — that ratio
 * is wrong for the current tokenizers and ignored the system prompt
 * folded into the user message.
 */
data class GeminiUsageMetadata(
    @SerializedName("promptTokenCount") val promptTokenCount: Int = 0,
    @SerializedName("candidatesTokenCount") val candidatesTokenCount: Int = 0,
    @SerializedName("totalTokenCount") val totalTokenCount: Int = 0,
)

data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
)
