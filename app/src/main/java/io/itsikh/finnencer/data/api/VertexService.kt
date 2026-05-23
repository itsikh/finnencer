package io.itsikh.finnencer.data.api

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Vertex AI publisher-model endpoint for Gemini generateContent /
 * multi-speaker TTS. Vertex URLs embed BOTH the region in the hostname
 * AND project+region+model in the path:
 *
 *   https://{REGION}-aiplatform.googleapis.com/v1/projects/{PROJECT}/
 *     locations/{REGION}/publishers/google/models/{MODEL}:generateContent
 *
 * Special case: region "global" uses the unprefixed hostname
 *   https://aiplatform.googleapis.com/...
 *
 * Since neither hostname nor path is stable, we pass the full URL via
 * Retrofit's `@Url` annotation. Auth is a Bearer token from
 * [VertexAuthManager] attached by an interceptor.
 *
 * Request/response shapes are identical to Generative Language API for
 * Gemini models, so we reuse [GeminiGenerateRequest] /
 * [GeminiGenerateResponse].
 */
interface VertexService {

    @POST
    suspend fun generateContent(
        @Url url: String,
        @Body request: GeminiGenerateRequest,
    ): GeminiGenerateResponse

    companion object {
        /**
         * Build the full Vertex generateContent URL for the given
         * project / region / model. Pass to [generateContent] as the
         * @Url parameter.
         */
        fun buildGenerateContentUrl(
            projectId: String,
            region: String,
            model: String,
        ): String {
            val host = if (region == "global") {
                "https://aiplatform.googleapis.com"
            } else {
                "https://$region-aiplatform.googleapis.com"
            }
            return "$host/v1/projects/$projectId/locations/$region/publishers/google/models/$model:generateContent"
        }
    }
}
