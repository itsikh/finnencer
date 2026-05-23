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
        purpose = "Multi-voice podcast generation (Gemini multi-speaker TTS).",
        signupUrl = "https://aistudio.google.com/",
    ),
    GEMINI_PROJECT_ID(
        // Sent as the `x-goog-user-project` header on every Generative
        // Language API call. Without it, quota is attributed to
        // whichever project the API key happens to belong to — which
        // on enterprise keys is often a default project with the
        // standard (tight) preview-TTS limits. Setting this explicitly
        // forces attribution to the project where the quota uplift
        // lives.
        alias = "key_gemini_project_id",
        displayName = "Gemini Quota Project (optional)",
        purpose = "Forces Gemini quota attribution to a specific GCP project. Useful when your enterprise quota uplift lives in a project different from the key's default.",
        signupUrl = "https://console.cloud.google.com/iam-admin/projects",
    ),
    VERTEX_SA_JSON(
        // Service-account JSON used to mint OAuth access tokens for
        // Vertex AI. Vertex doesn't accept API keys for generateContent,
        // and enterprise quota uplifts only ever apply to Vertex — so
        // this is the path that actually gets the elevated TTS limits.
        alias = "key_vertex_sa_json",
        displayName = "Vertex AI Service Account (JSON)",
        purpose = "Pasted service-account JSON for Vertex AI OAuth. Required to route TTS through Vertex.",
        signupUrl = "https://cloud.google.com/iam/docs/keys-create-delete",
    ),
    VERTEX_PROJECT_ID(
        alias = "key_vertex_project_id",
        displayName = "Vertex AI Project ID",
        purpose = "GCP project where Vertex AI generateContent runs (e.g. \"my-org-prod\").",
        signupUrl = "https://console.cloud.google.com/iam-admin/projects",
    ),
    VERTEX_REGION(
        alias = "key_vertex_region",
        displayName = "Vertex AI Region",
        purpose = "Vertex AI region (e.g. \"us-central1\", \"global\"). Default us-central1.",
        signupUrl = "https://cloud.google.com/vertex-ai/docs/general/locations",
    ),
    VERTEX_OAUTH_WEB_CLIENT_ID(
        // The "Web application" OAuth client ID from GCP Console.
        // Counter-intuitively needed for Android OAuth with
        // requestOfflineAccess — the server auth code we exchange for
        // a refresh token is bound to this client. Even though we never
        // run a web server, Google's flow requires it. Format:
        // <numeric-id>-<random>.apps.googleusercontent.com
        alias = "key_vertex_oauth_web_client_id",
        displayName = "Vertex OAuth Web Client ID",
        purpose = "Web application OAuth client ID from GCP Console. Required for the \"Sign in with Google\" path below — not used for the SA-JSON path.",
        signupUrl = "https://console.cloud.google.com/apis/credentials",
    ),
    VERTEX_OAUTH_WEB_CLIENT_SECRET(
        // Google rejects the code/refresh exchange with
        // `error=invalid_request: client_secret is missing` if this
        // isn't supplied — Web application OAuth clients are
        // confidential clients in Google's model, even when the actual
        // app holding the secret is a mobile app. (#59 root cause.)
        // The secret is downloadable from the same GCP Console page
        // the Web Client ID came from. Stored in SecureKeyManager
        // (encrypted on-device) alongside the other credentials.
        alias = "key_vertex_oauth_web_client_secret",
        displayName = "Vertex OAuth Web Client Secret",
        purpose = "Client secret for the Web application OAuth client. Required by Google to exchange the server auth code for a refresh token. Download from the same GCP credentials page as the Web Client ID.",
        signupUrl = "https://console.cloud.google.com/apis/credentials",
    ),
    VERTEX_OAUTH_REFRESH_TOKEN(
        // Long-lived refresh token written by the app AFTER the user
        // completes the in-app Sign in with Google flow. Lets the
        // background WorkManager mint fresh access tokens without
        // re-prompting the user. Excluded from the QR-bundle export to
        // prevent accidental cross-device copy (each device should
        // sign in independently — copying the refresh token bypasses
        // device-level consent and risks lingering grants).
        alias = "key_vertex_oauth_refresh_token",
        displayName = "Vertex OAuth refresh token (managed)",
        purpose = "Auto-managed. Filled when you tap \"Sign in with Google\" on the Vertex AI Service Account card. Don't paste this manually.",
        signupUrl = "https://myaccount.google.com/permissions",
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
                // Google issues several long-lived key formats: classic
                // "AIzaSy..." (39 chars) from aistudio.google.com, plus newer
                // "AQ..." style keys. Just check length and printable chars.
                if (value.length in 20..120 && value.all { it.isLetterOrDigit() || it in "._-" })
                    KeyTestResult.Ok
                else KeyTestResult.BadFormat("Doesn't look like a Google API key (expected 20-120 chars, letters/digits/.-_)")
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
            ApiKey.GEMINI_PROJECT_ID ->
                // GCP project IDs: 6-30 chars, lowercase letters / digits / hyphens, must start with a letter.
                if (Regex("^[a-z][a-z0-9-]{5,29}$").matches(value)) KeyTestResult.Ok
                else KeyTestResult.BadFormat("Expected a GCP project ID (6-30 chars, lowercase letters/digits/hyphens, start with a letter).")
            ApiKey.VERTEX_PROJECT_ID ->
                if (Regex("^[a-z][a-z0-9-]{5,29}$").matches(value)) KeyTestResult.Ok
                else KeyTestResult.BadFormat("Expected a GCP project ID (6-30 chars, lowercase letters/digits/hyphens, start with a letter).")
            ApiKey.VERTEX_REGION ->
                // Loose check — Google's region catalog changes regularly. Allow "global" or "<area>-<location><digit>".
                if (value == "global" || Regex("^[a-z]+-[a-z]+\\d+$").matches(value)) KeyTestResult.Ok
                else KeyTestResult.BadFormat("Expected a Vertex region like \"us-central1\", \"europe-west4\", or \"global\".")
            ApiKey.VERTEX_SA_JSON ->
                // Cheap structural check — full validation happens when VertexAuthManager parses it.
                if (value.contains("\"private_key\"") &&
                    value.contains("\"client_email\"") &&
                    value.contains("\"token_uri\"")
                ) KeyTestResult.Ok
                else KeyTestResult.BadFormat("Doesn't look like a service-account JSON. Expected fields \"private_key\", \"client_email\", \"token_uri\".")
            ApiKey.VERTEX_OAUTH_WEB_CLIENT_ID ->
                // Format: <project-number>-<random>.apps.googleusercontent.com
                if (value.endsWith(".apps.googleusercontent.com") && value.length in 40..120) KeyTestResult.Ok
                else KeyTestResult.BadFormat("Expected a Google OAuth client ID ending in .apps.googleusercontent.com.")
            ApiKey.VERTEX_OAUTH_WEB_CLIENT_SECRET ->
                // Google client secrets are short (~24-40 chars) and use a restricted alphabet.
                if (value.length in 18..80 && value.all { it.isLetterOrDigit() || it in "-_" }) KeyTestResult.Ok
                else KeyTestResult.BadFormat("Doesn't look like a Google OAuth client secret. Expected 18-80 chars of letters/digits/-/_.")
            ApiKey.VERTEX_OAUTH_REFRESH_TOKEN ->
                // Managed by the sign-in flow; basic length check only.
                if (value.length in 30..2000) KeyTestResult.Ok
                else KeyTestResult.BadFormat("Doesn't look like an OAuth refresh token. Use the in-app sign-in instead of pasting.")
        }
    }

    private fun snapshot(): Map<ApiKey, Boolean> =
        ApiKey.entries.associateWith { secureKeyManager.hasKey(it.alias) }
}
