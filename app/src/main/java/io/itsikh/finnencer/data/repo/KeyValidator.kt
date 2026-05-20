package io.itsikh.finnencer.data.repo

import com.google.gson.Gson
import io.itsikh.finnencer.logging.AppLogger
import io.itsikh.finnencer.util.AppSigningInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network-level validator for each API key.
 *
 * Pattern lifted from a sister project that's been running these keys in
 * production: **never use Retrofit-typed DTOs for validation calls.** Raw
 * [OkHttpClient] + `Map<>` JSON bodies + manual response-body parsing. This
 * sidesteps R8/Gson reflection issues that crashed the typed validator in
 * release builds (FINNHUB ClassCastException, ANTHROPIC HTTP 400 from
 * mangled `max_tokens` → `maxTokens`).
 *
 * For Google services (Gemini), the key goes in the URL query string
 * (`?key=...`) rather than the `x-goog-api-key` header — AI Studio keys
 * sometimes have stricter permissions on the header-auth path (HTTP 403 on
 * `/v1beta/models`).
 *
 * Every call runs on [Dispatchers.IO].
 */
@Singleton
class KeyValidator @Inject constructor(
    private val okHttp: OkHttpClient,
    private val gson: Gson,
    private val keys: ApiKeysRepository,
    private val signingInfo: AppSigningInfo,
) {

    suspend fun validate(key: ApiKey): KeyTestResult = withContext(Dispatchers.IO) {
        val token = keys.get(key) ?: return@withContext KeyTestResult.NotConfigured
        try {
            when (key) {
                ApiKey.ANTHROPIC -> validateClaude(token)
                ApiKey.FINNHUB -> validateFinnhub(token)
                ApiKey.GEMINI -> validateGemini(token)
                ApiKey.GITHUB_PAT -> validateGitHub(token)
                ApiKey.EDGAR_UA -> validateEdgar(token)
            }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "${key.name} validate threw ${e.javaClass.simpleName}", e)
            KeyTestResult.Failed("Network error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun validateClaude(token: String): KeyTestResult {
        val body = mapOf(
            "model" to "claude-haiku-4-5-20251001",
            "max_tokens" to 1,
            "messages" to listOf(mapOf("role" to "user", "content" to "hi")),
        )
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", token)
            .addHeader("anthropic-version", "2023-06-01")
            .post(gson.toJson(body).toRequestBody(JSON))
            .build()
        okHttp.newCall(req).execute().use { resp ->
            return if (resp.isSuccessful) {
                AppLogger.i(TAG, "ANTHROPIC ok")
                KeyTestResult.Ok
            } else {
                val msg = parseAnthropicError(resp.body?.string())
                AppLogger.w(TAG, "ANTHROPIC ${resp.code}: $msg")
                KeyTestResult.Failed("Claude (HTTP ${resp.code}): $msg")
            }
        }
    }

    private fun validateFinnhub(token: String): KeyTestResult {
        // /quote on AAPL is free-tier and returns {c, d, dp, h, l, o, pc, t}.
        // We don't deserialize — just confirm the 200 + body contains a "c"
        // field. Raw string match avoids the R8/Gson reflection issue.
        val req = Request.Builder()
            .url("https://finnhub.io/api/v1/quote?symbol=AAPL&token=$token")
            .get()
            .build()
        okHttp.newCall(req).execute().use { resp ->
            return when {
                resp.isSuccessful -> {
                    val body = resp.body?.string().orEmpty()
                    if (body.contains("\"c\":") && !body.contains("\"c\":0,\"d\":null")) {
                        AppLogger.i(TAG, "FINNHUB ok")
                        KeyTestResult.Ok
                    } else {
                        AppLogger.w(TAG, "FINNHUB 200 but no quote: ${body.take(180)}")
                        KeyTestResult.Failed("Finnhub returned an empty quote. Key may be on a restricted tier.")
                    }
                }
                resp.code == 401 || resp.code == 403 -> {
                    AppLogger.w(TAG, "FINNHUB ${resp.code} — key rejected")
                    KeyTestResult.Failed("Finnhub rejected the key (HTTP ${resp.code}). Generate a new one at finnhub.io.")
                }
                resp.code == 429 -> KeyTestResult.Failed("Finnhub rate-limited. Try again in a minute.")
                else -> KeyTestResult.Failed("Finnhub HTTP ${resp.code}")
            }
        }
    }

    private fun validateGemini(token: String): KeyTestResult {
        // Two-stage probe. The Gemini key has to work BOTH for chat (any
        // generally-available model) AND for the specific multi-speaker
        // TTS model the podcast feature uses — the latter is a preview
        // model whose access is sometimes restricted independently of
        // the project-wide Gemini API enablement. Catching this at key-
        // save time saves the user 10+ minutes of script generation
        // before the first TTS chunk fails with finishReason=OTHER.

        // Stage 1: cheap chat probe.
        val chatResult = probeGeminiChat(token) ?: return KeyTestResult.Failed("Gemini probe failed")
        if (chatResult !is KeyTestResult.Ok) return chatResult

        // Stage 2: TTS probe with a tiny multi-speaker request.
        return probeGeminiTts(token)
    }

    private fun probeGeminiChat(token: String): KeyTestResult? {
        // Don't pre-reject by prefix — Google ships multiple long-lived key
        // formats (classic "AIzaSy..." and newer "AQ..." with different
        // length/charset). Let the actual API tell us if it's good.
        val body = mapOf(
            "contents" to listOf(
                mapOf("parts" to listOf(mapOf("text" to "hi"))),
            ),
            "generationConfig" to mapOf("maxOutputTokens" to 1),
        )
        val builder = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent")
            .addHeader("x-goog-api-key", token)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Android-Package", signingInfo.packageName)
        signingInfo.signingCertSha1Hex?.let { builder.addHeader("X-Android-Cert", it) }
        val req = builder
            .post(gson.toJson(body).toRequestBody(JSON))
            .build()
        okHttp.newCall(req).execute().use { resp ->
            return if (resp.isSuccessful) {
                AppLogger.i(TAG, "GEMINI chat ok")
                KeyTestResult.Ok
            } else {
                val msg = parseGoogleError(resp.body?.string())
                AppLogger.w(TAG, "GEMINI chat ${resp.code}: $msg (pkg=${signingInfo.packageName} sha1=${signingInfo.signingCertSha1Hex?.take(16)}…)")
                KeyTestResult.Failed("Gemini (HTTP ${resp.code}): $msg")
            }
        }
    }

    /**
     * Send a tiny multi-speaker TTS request — exactly the shape the
     * podcast pipeline uses, just shorter. If the API returns no
     * inlineData (e.g. with finishReason=OTHER, which is what a
     * deprecated/unenrolled preview model does instead of a clean
     * 4xx), surface that as a Failed result so the user knows their
     * key works for chat but won't render audio.
     *
     * Uses a 60-second per-call clone of the base OkHttp client
     * because TTS generation takes 10-15s typically, well over the
     * 10s default the base client uses for cheap REST calls.
     */
    private fun probeGeminiTts(token: String): KeyTestResult {
        val ttsClient = okHttp.newBuilder()
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val body = mapOf(
            "contents" to listOf(
                mapOf("parts" to listOf(mapOf("text" to "Host: Hi.\nAnalyst: Hi back."))),
            ),
            "generationConfig" to mapOf(
                "responseModalities" to listOf("AUDIO"),
                "speechConfig" to mapOf(
                    "multiSpeakerVoiceConfig" to mapOf(
                        "speakerVoiceConfigs" to listOf(
                            mapOf(
                                "speaker" to "Host",
                                "voiceConfig" to mapOf(
                                    "prebuiltVoiceConfig" to mapOf("voiceName" to "Charon"),
                                ),
                            ),
                            mapOf(
                                "speaker" to "Analyst",
                                "voiceConfig" to mapOf(
                                    "prebuiltVoiceConfig" to mapOf("voiceName" to "Aoede"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val builder = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${io.itsikh.finnencer.data.ai.GeminiTts.TTS_MODEL}:generateContent")
            .addHeader("x-goog-api-key", token)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Android-Package", signingInfo.packageName)
        signingInfo.signingCertSha1Hex?.let { builder.addHeader("X-Android-Cert", it) }
        val req = builder
            .post(gson.toJson(body).toRequestBody(JSON))
            .build()
        ttsClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val msg = parseGoogleError(resp.body?.string())
                AppLogger.w(TAG, "GEMINI tts ${resp.code}: $msg")
                return KeyTestResult.Failed("Gemini key works for chat but TTS rejected it (HTTP ${resp.code}): $msg. Enable preview-model access at aistudio.google.com or check the key's allowed services.")
            }
            // 200 — verify the response actually carried inline audio.
            // An empty audio + finishReason=OTHER is what a deprecated
            // preview model or unenrolled key returns; we'd rather tell
            // the user now than burn 10 minutes of script generation
            // before the first chunk fails the same way.
            val raw = resp.body?.string().orEmpty()
            val hasInlineAudio = raw.contains("\"inlineData\"") && raw.contains("\"data\":")
            return if (hasInlineAudio) {
                AppLogger.i(TAG, "GEMINI tts ok (${raw.length} bytes)")
                KeyTestResult.Ok
            } else {
                val finishReason = Regex("\"finishReason\"\\s*:\\s*\"([^\"]+)\"")
                    .find(raw)?.groupValues?.getOrNull(1) ?: "unknown"
                AppLogger.w(TAG, "GEMINI tts returned no audio (finishReason=$finishReason, body head: ${raw.take(200)})")
                KeyTestResult.Failed(
                    "Gemini chat works but TTS returned no audio (finishReason=$finishReason). Likely your key/project doesn't have access to the ${io.itsikh.finnencer.data.ai.GeminiTts.TTS_MODEL} preview model. Enable preview access at aistudio.google.com or try a different Gemini key."
                )
            }
        }
    }

    private fun validateGitHub(token: String): KeyTestResult {
        val req = Request.Builder()
            .url("https://api.github.com/user")
            .addHeader("Authorization", "token $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "finnencer")
            .get()
            .build()
        okHttp.newCall(req).execute().use { resp ->
            return when (resp.code) {
                200 -> KeyTestResult.Ok.also { AppLogger.i(TAG, "GITHUB_PAT ok") }
                401 -> KeyTestResult.Failed("GitHub rejected the token (HTTP 401). Generate a new one with 'repo' scope.")
                403 -> KeyTestResult.Failed("GitHub token forbidden (HTTP 403). Check scopes + rate limit.")
                else -> KeyTestResult.Failed("GitHub HTTP ${resp.code}")
            }
        }
    }

    private fun validateEdgar(userAgent: String): KeyTestResult {
        // Up-front syntactic check — EDGAR's policy requires an email in
        // the User-Agent so they can contact heavy callers. Reject obvious
        // tokens / hex strings here with a tailored message instead of
        // letting EDGAR 403 with a less-helpful one.
        if (!userAgent.contains("@") || !userAgent.contains(".")) {
            return KeyTestResult.BadFormat(
                "Looks like a token, not an email. SEC EDGAR doesn't issue API keys — paste your email " +
                    "here (e.g. \"finnencer your.name@example.com\"). The User-Agent value goes on every " +
                    "request so SEC can contact you about heavy usage."
            )
        }
        // EDGAR validates the User-Agent on every request; Apple's CIK is the
        // standard sentinel.
        //
        // NOTE: we intentionally do NOT add an Accept-Encoding header here.
        // OkHttp's BridgeInterceptor handles gzip transparently ONLY when it
        // sets Accept-Encoding itself; adding our own disables the
        // transparent decompression and the body comes back as raw gzip
        // bytes, which then fails the JSON-body check below even when the
        // User-Agent is perfectly valid.
        val req = Request.Builder()
            .url("https://data.sec.gov/submissions/CIK0000320193.json")
            .header("User-Agent", userAgent)
            .get()
            .build()
        okHttp.newCall(req).execute().use { resp ->
            return when {
                resp.isSuccessful -> {
                    val body = resp.body?.string().orEmpty()
                    if (body.contains("\"cik\":") || body.contains("\"cik\": ")) {
                        AppLogger.i(TAG, "EDGAR_UA ok")
                        KeyTestResult.Ok
                    } else {
                        // Useful diagnostic: include the first 160 chars of
                        // whatever EDGAR returned so a future failure tells
                        // us what the surprise looks like instead of just
                        // "unexpected body".
                        val preview = body.take(160).replace("\n", " ")
                        KeyTestResult.Failed("EDGAR returned an unexpected body — User-Agent likely rejected. Preview: $preview")
                    }
                }
                resp.code == 403 -> KeyTestResult.Failed(
                    "SEC EDGAR rejected the User-Agent (HTTP 403). It must include a real email so SEC can contact you."
                )
                else -> KeyTestResult.Failed("EDGAR HTTP ${resp.code}")
            }
        }
    }

    // ─── Error body parsing ──────────────────────────────────────────────────

    private fun parseGoogleError(body: String?): String {
        if (body.isNullOrBlank()) return "Unknown error"
        return try {
            @Suppress("UNCHECKED_CAST")
            val parsed = gson.fromJson(body, Map::class.java) as Map<String, Any>
            val error = parsed["error"] as? Map<*, *>
            val status = error?.get("status") as? String ?: ""
            val message = error?.get("message") as? String ?: body.take(180)
            if (status.isNotBlank()) "$status: $message" else message
        } catch (_: Exception) {
            body.take(180)
        }
    }

    private fun parseAnthropicError(body: String?): String {
        if (body.isNullOrBlank()) return "Unknown error"
        return try {
            @Suppress("UNCHECKED_CAST")
            val parsed = gson.fromJson(body, Map::class.java) as Map<String, Any>
            val error = parsed["error"] as? Map<*, *>
            (error?.get("message") as? String) ?: body.take(180)
        } catch (_: Exception) {
            body.take(180)
        }
    }

    private companion object {
        const val TAG = "KeyValidator"
        val JSON = "application/json".toMediaType()
    }
}
