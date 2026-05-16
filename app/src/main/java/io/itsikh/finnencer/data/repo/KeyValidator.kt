package io.itsikh.finnencer.data.repo

import io.itsikh.finnencer.data.api.AnthropicMessage
import io.itsikh.finnencer.data.api.AnthropicRequest
import io.itsikh.finnencer.data.api.AnthropicService
import io.itsikh.finnencer.data.api.FinnhubService
import io.itsikh.finnencer.data.api.GeminiService
import io.itsikh.finnencer.data.api.SecEdgarService
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Every probe runs on [Dispatchers.IO] so we never block the caller's
 * dispatcher (notably Main, when invoked from a viewModelScope.launch).
 */
@Singleton
class KeyValidator @Inject constructor(
    private val anthropic: AnthropicService,
    private val finnhub: FinnhubService,
    private val gemini: GeminiService,
    private val edgar: SecEdgarService,
    private val plainHttp: OkHttpClient,
    private val keys: ApiKeysRepository,
) {

    suspend fun validate(key: ApiKey): KeyTestResult = withContext(Dispatchers.IO) {
        if (!keys.isConfigured(key)) return@withContext KeyTestResult.NotConfigured
        try {
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
        } catch (e: ClassCastException) {
            AppLogger.e(TAG, "${key.name} validate ClassCastException — provider response shape mismatch", e)
            KeyTestResult.Failed("Provider returned an unexpected response shape (possibly an error JSON instead of data). Re-check the key.")
        } catch (e: Throwable) {
            AppLogger.e(TAG, "${key.name} validate unexpected ${e.javaClass.simpleName}", e)
            KeyTestResult.Failed("${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    private fun mapHttp(key: ApiKey, code: Int, @Suppress("unused") e: HttpException): KeyTestResult {
        AppLogger.w(TAG, "${key.name} validate HTTP $code")
        return when (code) {
            400 -> KeyTestResult.Failed(
                "Provider rejected the request shape (HTTP 400). The key itself may still be valid — try saving and using the app feature."
            )
            401, 403 -> KeyTestResult.Failed(
                "Provider rejected the key (HTTP $code). Verify you copied the full token and that it has the right scope."
            )
            404 -> KeyTestResult.Failed("Endpoint not found (HTTP 404). API may have changed.")
            429 -> KeyTestResult.Failed("Rate-limited (HTTP 429). Try again in a minute.")
            in 500..599 -> KeyTestResult.Failed("Provider server error (HTTP $code). Try again shortly.")
            else -> KeyTestResult.Failed("Unexpected HTTP $code")
        }
    }

    // ───────── per-provider tests ─────────

    private suspend fun validateAnthropic(): KeyTestResult {
        val resp = anthropic.messages(
            AnthropicRequest(
                model = "claude-haiku-4-5-20251001",
                maxTokens = 1,
                messages = listOf(AnthropicMessage(role = "user", content = "ping")),
            )
        )
        AppLogger.i(TAG, "ANTHROPIC validate ok id=${resp.id}")
        return if (resp.id != null) KeyTestResult.Ok
        else KeyTestResult.Failed("Empty response from Anthropic")
    }

    private suspend fun validateFinnhub(): KeyTestResult {
        // /quote on AAPL is free-tier and always returns a current price for a
        // valid key. A bad key returns 401 (caught above).
        val q = finnhub.quote(symbol = "AAPL")
        AppLogger.i(TAG, "FINNHUB validate ok c=${q.c}")
        return if (q.c != null) KeyTestResult.Ok
        else KeyTestResult.Failed("Finnhub returned no quote. Key may be restricted to a different tier.")
    }

    private suspend fun validateGemini(): KeyTestResult {
        // /v1beta/models is auth-only, free, and avoids the body-shape
        // pitfalls of generateContent during validation.
        val resp = gemini.listModels()
        AppLogger.i(TAG, "GEMINI validate ok models=${resp.models.size}")
        return if (resp.models.isNotEmpty()) KeyTestResult.Ok
        else KeyTestResult.Failed("Gemini returned an empty model list (unexpected)")
    }

    private suspend fun validateGitHub(): KeyTestResult {
        val token = keys.get(ApiKey.GITHUB_PAT) ?: return KeyTestResult.NotConfigured
        // Synchronous .execute() must NOT run on Main — we're already inside
        // withContext(Dispatchers.IO) so this is safe.
        val req = Request.Builder()
            .url("https://api.github.com/user")
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "finnencer/0.0.x")
            .build()
        plainHttp.newCall(req).execute().use { resp ->
            AppLogger.i(TAG, "GITHUB_PAT validate http=${resp.code}")
            return when (resp.code) {
                200 -> KeyTestResult.Ok
                401 -> KeyTestResult.Failed("GitHub rejected the token (HTTP 401). Generate a new one with 'repo' scope.")
                403 -> KeyTestResult.Failed("Token forbidden (HTTP 403). Check scopes + rate limit.")
                else -> KeyTestResult.Failed("GitHub returned HTTP ${resp.code}")
            }
        }
    }

    private suspend fun validateEdgar(): KeyTestResult {
        val raw = edgar.submissions(cikZeroPadded10 = "0000320193")
        val ok = raw.contains("\"cik\":") || raw.contains("\"cik\": ")
        AppLogger.i(TAG, "EDGAR_UA validate body=${raw.length}B ok=$ok")
        return if (ok) KeyTestResult.Ok
        else KeyTestResult.Failed("EDGAR returned an unexpected body. SEC requires a real email in the User-Agent.")
    }

    private companion object {
        const val TAG = "KeyValidator"
    }
}
