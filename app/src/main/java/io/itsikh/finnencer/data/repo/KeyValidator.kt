package io.itsikh.finnencer.data.repo

import com.google.gson.Gson
import io.itsikh.finnencer.logging.AppLogger
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
        if (!token.startsWith("AIza")) {
            return KeyTestResult.Failed(
                "Wrong format — Google API keys start with \"AIzaSy\". Generate one at aistudio.google.com."
            )
        }
        val body = mapOf(
            "contents" to listOf(
                mapOf("parts" to listOf(mapOf("text" to "hi"))),
            ),
            "generationConfig" to mapOf("maxOutputTokens" to 1),
        )
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$token")
            .post(gson.toJson(body).toRequestBody(JSON))
            .build()
        okHttp.newCall(req).execute().use { resp ->
            return if (resp.isSuccessful) {
                AppLogger.i(TAG, "GEMINI ok")
                KeyTestResult.Ok
            } else {
                val msg = parseGoogleError(resp.body?.string())
                AppLogger.w(TAG, "GEMINI ${resp.code}: $msg")
                KeyTestResult.Failed("Gemini (HTTP ${resp.code}): $msg")
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
        // EDGAR validates the User-Agent on every request; Apple's CIK is the
        // standard sentinel.
        val req = Request.Builder()
            .url("https://data.sec.gov/submissions/CIK0000320193.json")
            .addHeader("User-Agent", userAgent)
            .addHeader("Accept-Encoding", "gzip, deflate")
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
                        KeyTestResult.Failed("EDGAR returned an unexpected body — User-Agent likely rejected.")
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
