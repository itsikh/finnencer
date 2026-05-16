package io.itsikh.finnencer.data.api

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Gemini multi-speaker TTS. Used in Build B for podcast generation. Stub
 * here so the Retrofit graph compiles after A·6.
 */
interface GeminiService {

    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Body request: GeminiGenerateRequest,
    ): GeminiGenerateResponse
}

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
)

data class GeminiGenerationConfig(
    val temperature: Double? = null,
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
)

data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
)
