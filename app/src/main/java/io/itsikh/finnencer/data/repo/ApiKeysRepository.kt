package io.itsikh.finnencer.data.repo

import io.itsikh.finnencer.security.SecureKeyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identifier for each API key finnencer keeps in the encrypted store. The
 * `alias` matches the row name in [SecureKeyManager]; do not change after
 * shipping or existing users lose their stored values.
 */
enum class ApiKey(
    val alias: String,
    val displayName: String,
    val purpose: String,
    val signupUrl: String,
) {
    ANTHROPIC(
        alias = "key_anthropic",
        displayName = "Anthropic Claude",
        purpose = "Importance scoring, article summaries, earnings reports.",
        signupUrl = "https://console.anthropic.com/",
    ),
    FINNHUB(
        alias = "key_finnhub",
        displayName = "Finnhub",
        purpose = "News, earnings calendar, analyst ratings, ticker search.",
        signupUrl = "https://finnhub.io/",
    ),
    GEMINI(
        alias = "key_gemini",
        displayName = "Google Gemini",
        purpose = "Multi-voice podcast generation (Gemini 2.5 Flash TTS).",
        signupUrl = "https://aistudio.google.com/",
    ),
    GITHUB_PAT(
        // Must match the alias used by GitHubIssuesClient.KEY_GITHUB_TOKEN
        // and AppUpdateManager.KEY_GITHUB_TOKEN. Renaming this string
        // breaks the in-app bug reporter and the auto-updater.
        alias = "github_token",
        displayName = "GitHub Token",
        purpose = "In-app bug reports + app self-update.",
        signupUrl = "https://github.com/settings/tokens",
    ),
    EDGAR_UA(
        alias = "key_edgar_user_agent",
        displayName = "SEC EDGAR Identifier",
        purpose = "User-Agent SEC requires on every request. Format: \"App Name your-email@example.com\".",
        signupUrl = "https://www.sec.gov/developer",
    ),
}

/** Result of a quick syntactic / network probe of a stored key. */
sealed interface KeyTestResult {
    object NotConfigured : KeyTestResult
    object ChecksSyntax : KeyTestResult
    object Ok : KeyTestResult
    data class BadFormat(val message: String) : KeyTestResult
    data class Failed(val message: String) : KeyTestResult
}

/**
 * Front door to the 5 API keys finnencer needs. Backed by the template's
 * [SecureKeyManager]; exposes a [StateFlow] over a snapshot of configured/not
 * for each so the UI can light up status pills reactively.
 */
@Singleton
class ApiKeysRepository @Inject constructor(
    private val secureKeyManager: SecureKeyManager,
) {

    init {
        // One-time migration: earlier builds stored the GitHub PAT under
        // "key_github_pat". The template's bug-reporter + updater always
        // read from "github_token". Copy across so anything already saved
        // continues to work.
        migrateLegacyAlias(from = "key_github_pat", to = "github_token")
    }

    private fun migrateLegacyAlias(from: String, to: String) {
        if (secureKeyManager.hasKey(from) && !secureKeyManager.hasKey(to)) {
            secureKeyManager.getKey(from)?.let { v ->
                secureKeyManager.saveKey(to, v)
            }
        }
        if (secureKeyManager.hasKey(from)) {
            secureKeyManager.deleteKey(from)
        }
    }

    private val _configured = MutableStateFlow(snapshot())
    val configured: StateFlow<Map<ApiKey, Boolean>> = _configured.asStateFlow()

    fun get(key: ApiKey): String? = secureKeyManager.getKey(key.alias)?.takeIf { it.isNotBlank() }

    fun save(key: ApiKey, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            secureKeyManager.deleteKey(key.alias)
        } else {
            secureKeyManager.saveKey(key.alias, trimmed)
        }
        _configured.value = snapshot()
    }

    fun clear(key: ApiKey) {
        secureKeyManager.deleteKey(key.alias)
        _configured.value = snapshot()
    }

    fun isConfigured(key: ApiKey): Boolean = secureKeyManager.hasKey(key.alias)

    /** Cheap, offline syntactic check. Real network probes land in A·7. */
    fun checkSyntax(key: ApiKey): KeyTestResult {
        val value = get(key) ?: return KeyTestResult.NotConfigured
        return when (key) {
            ApiKey.ANTHROPIC ->
                if (value.startsWith("sk-ant-") && value.length >= 60) KeyTestResult.Ok
                else KeyTestResult.BadFormat("Expected sk-ant-... key from console.anthropic.com")
            ApiKey.FINNHUB ->
                if (value.length in 20..80 && value.all { it.isLetterOrDigit() })
                    KeyTestResult.Ok
                else KeyTestResult.BadFormat("Expected alphanumeric ~20-60 char Finnhub key")
            ApiKey.GEMINI ->
                if (value.startsWith("AIzaSy") && value.length == 39) KeyTestResult.Ok
                else KeyTestResult.BadFormat("Expected AIzaSy... (39 chars) Google AI Studio key")
            ApiKey.GITHUB_PAT ->
                if (value.startsWith("ghp_") || value.startsWith("github_pat_"))
                    KeyTestResult.Ok
                else KeyTestResult.BadFormat("Expected ghp_... or github_pat_... PAT")
            ApiKey.EDGAR_UA ->
                if ("@" in value && value.length >= 5)
                    KeyTestResult.Ok
                else KeyTestResult.BadFormat(
                    "SEC requires a real contact email in the User-Agent. " +
                            "Format: \"finnencer your-email@example.com\"."
                )
        }
    }

    private fun snapshot(): Map<ApiKey, Boolean> =
        ApiKey.entries.associateWith { secureKeyManager.hasKey(it.alias) }
}
