package io.itsikh.finnencer.data.api

import android.util.Base64
import com.google.gson.Gson
import io.itsikh.finnencer.data.repo.ApiKey
import io.itsikh.finnencer.data.repo.ApiKeysRepository
import io.itsikh.finnencer.logging.AppLogger as Log
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Mints + caches OAuth access tokens for Vertex AI calls.
 *
 * Vertex AI doesn't accept API keys for generateContent — every request
 * must carry an `Authorization: Bearer <access_token>` header. The
 * standard way to get one in a service-to-service flow is the JWT
 * Bearer grant:
 *   1. Sign a JWT with the service account's private key (RS256).
 *   2. POST it to the account's `token_uri` (oauth2.googleapis.com).
 *   3. Receive an access token good for ~1 hour.
 *
 * Tokens are cached in-memory until [REFRESH_MARGIN_MS] before their
 * stated expiry so concurrent callers don't all re-mint at the boundary.
 * A [Mutex] guards the refresh so only one in-flight token exchange
 * runs at a time even when 12 chunks fire near-simultaneously.
 *
 * No external JWT library is required — everything here uses standard
 * JCA primitives (KeyFactory, Signature) and OkHttp for the token
 * exchange. Keeping this dependency-free lets the app stay small.
 */
@Singleton
class VertexAuthManager @Inject constructor(
    private val repo: ApiKeysRepository,
    private val gson: Gson,
) {

    private val tokenHttpClient = OkHttpClient.Builder().build()
    private val mutex = Mutex()

    @Volatile
    private var cached: CachedToken? = null

    /**
     * Return a valid bearer token, refreshing if cache is empty or
     * within [REFRESH_MARGIN_MS] of expiry. Throws if the service
     * account JSON is missing / malformed.
     *
     * Runs the synchronous OkHttp + JCA work on [Dispatchers.IO] —
     * the suspend caller might be on the main thread (the OkHttp
     * interceptor uses runBlocking, the OAuth sign-in completion
     * dispatches from viewModelScope) and either path would hit
     * `NetworkOnMainThreadException` without the explicit IO context.
     */
    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val cur = cached
            if (cur != null && cur.expiresAtMs - now > REFRESH_MARGIN_MS) {
                return@withLock cur.token
            }
            val fresh = mintNewToken()
            cached = fresh
            fresh.token
        }
    }

    /** Force a refresh on the next call. Used by 401-aware retry paths. */
    fun invalidate() {
        cached = null
    }

    /**
     * Mint a fresh access token. Selection order, since either
     * credential alone is enough to authenticate:
     *  1. OAuth user refresh token (preferred — no JSON on disk, the
     *     in-app sign-in flow already proves device-level consent).
     *  2. Service-account JSON (fallback for unattended / CI-style use).
     *
     * If neither is configured, throw a config error that the UI maps
     * to "go to Settings → API keys and set one up".
     */
    private fun mintNewToken(): CachedToken {
        repo.get(ApiKey.VERTEX_OAUTH_REFRESH_TOKEN)?.takeIf { it.isNotBlank() }?.let { refreshToken ->
            return mintFromRefreshToken(refreshToken)
        }
        repo.get(ApiKey.VERTEX_SA_JSON)?.takeIf { it.isNotBlank() }?.let { saJsonRaw ->
            return mintFromServiceAccount(saJsonRaw)
        }
        throw VertexConfigError(
            "No Vertex credential configured. Either sign in with Google or paste a service-account JSON in Settings → API keys."
        )
    }

    private fun mintFromServiceAccount(saJsonRaw: String): CachedToken {
        val sa = parseServiceAccount(saJsonRaw)
        val nowSec = System.currentTimeMillis() / 1000
        val jwt = buildSignedJwt(sa, issuedAtSec = nowSec, expiresAtSec = nowSec + JWT_LIFETIME_SEC)
        val resp = exchangeJwt(sa.tokenUri!!, jwt)
        val expiresAtMs = System.currentTimeMillis() + resp.expiresIn * 1000L
        Log.i(TAG, "vertex: minted new access token via SA, expires in ${resp.expiresIn}s")
        return CachedToken(resp.accessToken, expiresAtMs)
    }

    private fun mintFromRefreshToken(refreshToken: String): CachedToken {
        val webClientId = repo.get(ApiKey.VERTEX_OAUTH_WEB_CLIENT_ID)?.takeIf { it.isNotBlank() }
            ?: throw VertexConfigError(
                "Vertex OAuth Web Client ID is missing. Set it in Settings → API keys before using Sign-in with Google."
            )
        val body = FormBody.Builder()
            .add("client_id", webClientId)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val req = Request.Builder().url(GOOGLE_TOKEN_URL).post(body).build()
        tokenHttpClient.newCall(req).execute().use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 400 invalid_grant is the canonical "user revoked /
                // refresh token expired" — surface a config-shaped error
                // so the UI can prompt re-sign-in.
                if (resp.code == 400 && responseBody.contains("invalid_grant")) {
                    throw VertexConfigError(
                        "Your Vertex Google sign-in expired or was revoked. Tap Sign in with Google again in Settings → API keys."
                    )
                }
                throw VertexConfigError("Vertex OAuth refresh failed: HTTP ${resp.code} ${shorten(responseBody)}")
            }
            val parsed = runCatching { gson.fromJson(responseBody, TokenResponse::class.java) }
                .getOrElse { throw VertexConfigError("Vertex OAuth refresh response wasn't JSON: ${shorten(responseBody)}") }
            if (parsed.accessToken.isBlank() || parsed.expiresIn <= 0) {
                throw VertexConfigError("Vertex OAuth refresh response missing access_token / expires_in: ${shorten(responseBody)}")
            }
            val expiresAtMs = System.currentTimeMillis() + parsed.expiresIn * 1000L
            Log.i(TAG, "vertex: minted new access token via OAuth refresh, expires in ${parsed.expiresIn}s")
            return CachedToken(parsed.accessToken, expiresAtMs)
        }
    }

    /**
     * Exchange a server auth code (returned by the in-app sign-in flow)
     * for a refresh token + access token. Run once per device — the
     * refresh token gets persisted and reused thereafter.
     *
     * No client secret is needed because Android OAuth clients are
     * "public" — the package name + SHA-1 fingerprint already proven
     * to Google by the device serve as the proof-of-app.
     */
    suspend fun exchangeServerAuthCode(serverAuthCode: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val webClientId = repo.get(ApiKey.VERTEX_OAUTH_WEB_CLIENT_ID)?.takeIf { it.isNotBlank() }
                ?: throw VertexConfigError(
                    "Vertex OAuth Web Client ID is missing. Save it in Settings → API keys before signing in."
                )
        val body = FormBody.Builder()
            .add("client_id", webClientId)
            .add("code", serverAuthCode)
            .add("grant_type", "authorization_code")
            // Native (Android) clients use this exact redirect URI for
            // the offline-access exchange — see Google's OAuth docs.
            .add("redirect_uri", "")
            .build()
        val req = Request.Builder().url(GOOGLE_TOKEN_URL).post(body).build()
        tokenHttpClient.newCall(req).execute().use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw VertexConfigError("Vertex OAuth code exchange failed: HTTP ${resp.code} ${shorten(responseBody)}")
            }
            val parsed = runCatching { gson.fromJson(responseBody, CodeExchangeResponse::class.java) }
                .getOrElse { throw VertexConfigError("Vertex OAuth code response wasn't JSON: ${shorten(responseBody)}") }
            val refresh = parsed.refreshToken
            if (refresh.isNullOrBlank()) {
                throw VertexConfigError(
                    "Sign-in succeeded but Google didn't return a refresh token. Make sure the in-app sign-in requests offline access."
                )
            }
            // Persist immediately and prime the cache with the fresh access token.
            repo.save(ApiKey.VERTEX_OAUTH_REFRESH_TOKEN, refresh)
            if (parsed.accessToken.isNotBlank() && parsed.expiresIn > 0) {
                cached = CachedToken(
                    parsed.accessToken,
                    System.currentTimeMillis() + parsed.expiresIn * 1000L,
                )
            }
            Log.i(TAG, "vertex: stored OAuth refresh token; access expires in ${parsed.expiresIn}s")
            refresh
            }
        }
    }

    private fun parseServiceAccount(json: String): ServiceAccount {
        val sa = runCatching { gson.fromJson(json, ServiceAccount::class.java) }
            .getOrElse { throw VertexConfigError("Vertex service-account JSON failed to parse: ${it.message}") }
        if (sa == null || sa.clientEmail.isNullOrBlank() || sa.privateKey.isNullOrBlank() || sa.tokenUri.isNullOrBlank()) {
            throw VertexConfigError("Vertex service-account JSON is missing required fields (client_email / private_key / token_uri).")
        }
        return sa
    }

    private fun buildSignedJwt(sa: ServiceAccount, issuedAtSec: Long, expiresAtSec: Long): String {
        val header = mapOf("alg" to "RS256", "typ" to "JWT").let { gson.toJson(it) }
        val claims = linkedMapOf(
            "iss" to sa.clientEmail,
            "scope" to SCOPE,
            "aud" to sa.tokenUri,
            "exp" to expiresAtSec,
            "iat" to issuedAtSec,
        ).let { gson.toJson(it) }
        val signingInput = base64Url(header.toByteArray()) + "." + base64Url(claims.toByteArray())
        val sig = signRs256(sa.privateKey!!, signingInput.toByteArray())
        return signingInput + "." + base64Url(sig)
    }

    private fun signRs256(pemPrivateKey: String, data: ByteArray): ByteArray {
        val pkcs8Bytes = pemToPkcs8(pemPrivateKey)
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8Bytes))
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(key)
        signer.update(data)
        return signer.sign()
    }

    private fun pemToPkcs8(pem: String): ByteArray {
        val cleaned = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        return Base64.decode(cleaned, Base64.DEFAULT)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun exchangeJwt(tokenUri: String, jwt: String): TokenResponse {
        val body = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", jwt)
            .build()
        val req = Request.Builder().url(tokenUri).post(body).build()
        tokenHttpClient.newCall(req).execute().use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw VertexConfigError("Vertex token exchange failed: HTTP ${resp.code} ${shorten(responseBody)}")
            }
            return runCatching { gson.fromJson(responseBody, TokenResponse::class.java) }
                .getOrElse { throw VertexConfigError("Vertex token response wasn't JSON: ${shorten(responseBody)}") }
                .also {
                    if (it.accessToken.isBlank() || it.expiresIn <= 0) {
                        throw VertexConfigError("Vertex token response missing access_token / expires_in: ${shorten(responseBody)}")
                    }
                }
        }
    }

    private fun shorten(s: String) = if (s.length > 200) s.take(200) + "…" else s

    private data class CachedToken(val token: String, val expiresAtMs: Long)

    /**
     * Subset of the service-account JSON shape — only the fields we
     * actually need to sign. Gson maps from snake_case via @SerializedName.
     */
    private data class ServiceAccount(
        @com.google.gson.annotations.SerializedName("client_email") val clientEmail: String? = null,
        @com.google.gson.annotations.SerializedName("private_key") val privateKey: String? = null,
        @com.google.gson.annotations.SerializedName("token_uri") val tokenUri: String? = null,
        @com.google.gson.annotations.SerializedName("project_id") val projectId: String? = null,
    )

    private data class TokenResponse(
        @com.google.gson.annotations.SerializedName("access_token") val accessToken: String = "",
        @com.google.gson.annotations.SerializedName("expires_in") val expiresIn: Int = 0,
        @com.google.gson.annotations.SerializedName("token_type") val tokenType: String = "Bearer",
    )

    private data class CodeExchangeResponse(
        @com.google.gson.annotations.SerializedName("access_token") val accessToken: String = "",
        @com.google.gson.annotations.SerializedName("expires_in") val expiresIn: Int = 0,
        @com.google.gson.annotations.SerializedName("refresh_token") val refreshToken: String? = null,
        @com.google.gson.annotations.SerializedName("token_type") val tokenType: String = "Bearer",
        @com.google.gson.annotations.SerializedName("scope") val scope: String? = null,
    )

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"
        private const val TAG = "VertexAuthManager"
        private const val JWT_LIFETIME_SEC = 3600L
        /** Refresh slightly before expiry to avoid a race with the
         *  upstream clock. 60s is the same margin google-auth-library
         *  defaults to. */
        private const val REFRESH_MARGIN_MS = 60_000L
        private const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
    }
}

/**
 * Thrown when Vertex auth fails for a reason the user can fix from
 * Settings (missing JSON, malformed key, project mismatch). Distinct
 * from network / HTTP errors so FriendlyError can route differently.
 */
class VertexConfigError(message: String) : RuntimeException(message)
