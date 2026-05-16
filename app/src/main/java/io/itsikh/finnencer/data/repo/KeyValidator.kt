package io.itsikh.finnencer.data.repo

import io.itsikh.finnencer.data.api.AnthropicMessage
import io.itsikh.finnencer.data.api.AnthropicRequest
import io.itsikh.finnencer.data.api.AnthropicService
import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.api.GeminiService
import io.itsikh.finnencer.data.api.SecEdgarService
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network-level validator for each API key. Each `validate*` does a single
 * cheap auth-required call against the provider's API and returns a typed
 * [KeyTestResult]. Used by the API Keys screen so the user can see in real
 * time whether the key they pasted actually works.
 *
 * Each call is bounded by Retrofit's default timeouts (10s connect + 10s
 * read on OkHttp). No retries — the user can re-tap "Test".
 */
@Singleton
class KeyValidator @Inject constructor(
    private val anthropic: AnthropicService,
    private val finnhub: FinnhubService,
    private val gemini: GeminiService,
    private val edgar: SecEdgarService,
    private val plainHttp: OkHttpClient, // unauthenticated client for GitHub /user
    private val keys: ApiKeysRepository,
) {

    suspend fun validate(key: ApiKey): KeyTestResult {
        if (!keys.isConfigured(key)) return KeyTestResult.NotConfigured
        return try {
            when (key) {
                ApiKey.ANTHROPIC -> validateAnthropic()
                ApiKey.FINNHUB -> validateFinnhub()
                ApiKey.GEMINI -> validateGemini()
                ApiKey.GITHUB_PAT -> validateGitHub()
                ApiKey.EDGAR_UA -> validateEdgar()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            mapHttp(key, e.code(), e)
        } catch (e: IOException) {
            AppLogger.w(TAG, "${key.name} validate network error: ${e.message}")
            KeyTestResult.Failed("Network error: ${e.message ?: "could not reach provider"}")
        } catch (e: Throwable) {
            AppLogger.e(TAG, "${key.name} validate unexpected ${e.javaClass.simpleName}", e)
            KeyTestResult.Failed("${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    private fun mapHttp(key: ApiKey, code: Int, e: HttpException): KeyTestResult {
        AppLogger.w(TAG, "${key.name} validate HTTP $code")
        return when (code) {
            401, 403 -> KeyTestResult.Failed(
                "Provider rejected the key (HTTP $code). Double-check you copied the full token."
            )
            429 -> KeyTestResult.Failed("Rate-limited (HTTP 429). Try again in a minute.")
            in 500..599 -> KeyTestResult.Failed("Provider server error (HTTP $code). Try again shortly.")
            else -> KeyTestResult.Failed("Unexpected HTTP $code")
        }
    }

    // ───────── per-provider tests ─────────

    private suspend fun validateAnthropic(): KeyTestResult {
        // One-token ping. Costs roughly $0.000004.
        val resp = anthropic.messages(
            AnthropicRequest(
                model = "claude-haiku-4-5-20251001",
                maxTokens = 1,
                messages = listOf(AnthropicMessage(role = "user", content = "ping")),
            )
        )
        return if (resp.id != null) KeyTestResult.Ok
        else KeyTestResult.Failed("Empty response from Anthropic")
    }

    private suspend fun validateFinnhub(): KeyTestResult {
        // /quote on AAPL is free-tier and always returns a current price.
        val q = finnhub.quote(symbol = "AAPL")
        return if (q.c != null) KeyTestResult.Ok
        else KeyTestResult.Failed("Finnhub returned no quote (key may be restricted to a different tier)")
    }

    private suspend fun validateGemini(): KeyTestResult {
        // Use the cheapest possible call: list models. Free.
        // Use a one-token TTS-less generateContent on flash, fall back to
        // a /models call if not available. Keep it simple here: a tiny text
        // request on gemini-2.5-flash.
        val req = io.itsikh.finnencer.data.api.GeminiGenerateRequest(
            contents = listOf(
                io.itsikh.finnencer.data.api.GeminiContent(
                    parts = listOf(io.itsikh.finnencer.data.api.GeminiPart(text = "ping"))
                )
            ),
        )
        val resp = gemini.generateContent(model = "gemini-2.5-flash", request = req)
        return if (resp.candidates.isNotEmpty()) KeyTestResult.Ok
        else KeyTestResult.Failed("Gemini returned no candidates")
    }

    private suspend fun validateGitHub(): KeyTestResult {
        val token = keys.get(ApiKey.GITHUB_PAT) ?: return KeyTestResult.NotConfigured
        val req = Request.Builder()
            .url("https://api.github.com/user")
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "finnencer/0.0.x")
            .build()
        plainHttp.newCall(req).execute().use { resp ->
            return when (resp.code) {
                200 -> KeyTestResult.Ok
                401 -> KeyTestResult.Failed("GitHub rejected the token (HTTP 401). Make sure it has 'repo' scope.")
                403 -> KeyTestResult.Failed("Token forbidden (HTTP 403). Check scopes + rate limit.")
                else -> KeyTestResult.Failed("GitHub returned HTTP ${resp.code}")
            }
        }
    }

    private suspend fun validateEdgar(): KeyTestResult {
        // EDGAR validates the User-Agent on every request. A 200 from the
        // submissions JSON for Apple proves our UA passes muster.
        val raw = edgar.submissions(cikZeroPadded10 = "0000320193")
        return if (raw.contains("\"cik\":") || raw.contains("\"cik\": ")) KeyTestResult.Ok
        else KeyTestResult.Failed("EDGAR returned an unexpected body (User-Agent likely rejected)")
    }

    private companion object {
        const val TAG = "KeyValidator"
    }
}
