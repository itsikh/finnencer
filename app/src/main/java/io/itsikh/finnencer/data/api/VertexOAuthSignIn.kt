package io.itsikh.finnencer.data.api

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.logging.AppLogger as Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Drives the Google Identity Services [AuthorizationClient] sign-in
 * flow that gets the app a Vertex AI access token under the user's
 * own Google identity (Option B in #57 — no service-account JSON
 * needed on disk).
 *
 * Two-step shape because the Google API is two-step:
 *  1. [startSignIn] requests authorization. If the user has already
 *     granted the requested scopes on this device + this client, the
 *     server auth code is returned synchronously and [VertexAuthManager]
 *     exchanges it for a refresh token. If consent is needed, an
 *     [IntentSender] is returned so the caller can launch the consent
 *     dialog via ActivityResultLauncher.
 *  2. [completeSignIn] is called from the activity-result callback,
 *     parses the [AuthorizationResult], and finishes the exchange.
 *
 * Why AuthorizationClient and not Credential Manager: Credential
 * Manager handles identity sign-in (who is the user) — what we need is
 * an *authorization* with the `cloud-platform` scope, which is what
 * AuthorizationClient is built for.
 */
@Singleton
class VertexOAuthSignIn @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val authManager: VertexAuthManager,
) {

    sealed interface StartResult {
        /** Sign-in succeeded silently (consent already on record). */
        data class Success(val refreshToken: String) : StartResult

        /** User must approve the consent dialog. Launch this with an
         *  ActivityResultLauncher<IntentSenderRequest>. */
        data class NeedsConsent(val intentSender: IntentSender) : StartResult

        data class Error(val message: String) : StartResult
    }

    /**
     * Begin the Vertex Google sign-in. Caller must pass the Activity
     * the consent dialog will attach to; the call is suspending because
     * the immediate (consent-on-file) case still does a code → refresh
     * token exchange over the network.
     */
    suspend fun startSignIn(activity: Activity, webClientId: String): StartResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(VertexAuthManager.SCOPE)))
            // requestOfflineAccess gets us the server auth code, which
            // is what we trade for a refresh token. The web client ID
            // here is the audience: Google binds the auth code to this
            // client so only the matching exchange can use it. The
            // boolean forces an account chooser even when only one
            // account is signed in — better UX than silently picking
            // the wrong account when the user has multiple.
            .requestOfflineAccess(webClientId, true)
            .build()

        return try {
            val result = awaitAuthorize(activity, request)
            if (result.hasResolution()) {
                val sender = result.pendingIntent?.intentSender
                    ?: return StartResult.Error("Google Identity Services returned no consent intent")
                StartResult.NeedsConsent(sender)
            } else {
                finishExchange(result)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Vertex sign-in start failed: ${t.javaClass.simpleName}: ${t.message}")
            StartResult.Error("Sign-in failed to start: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * Called by the ActivityResultLauncher callback when the user has
     * gone through the consent dialog. [data] is the raw Intent the
     * launcher hands back; null indicates the user cancelled or the
     * system delivered no payload.
     */
    suspend fun completeSignIn(data: Intent?): StartResult {
        if (data == null) {
            return StartResult.Error("Sign-in was cancelled before completing.")
        }
        return try {
            val client = Identity.getAuthorizationClient(appContext)
            val result = client.getAuthorizationResultFromIntent(data)
            finishExchange(result)
        } catch (t: Throwable) {
            Log.w(TAG, "Vertex sign-in complete failed: ${t.javaClass.simpleName}: ${t.message}")
            StartResult.Error("Sign-in could not be completed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private suspend fun finishExchange(result: AuthorizationResult): StartResult {
        val serverAuthCode = result.serverAuthCode
        if (serverAuthCode.isNullOrBlank()) {
            return StartResult.Error(
                "Sign-in returned no server auth code. Verify the Web Client ID in Settings → API keys " +
                        "matches a Web application OAuth client in your GCP project."
            )
        }
        return try {
            val refresh = authManager.exchangeServerAuthCode(serverAuthCode)
            StartResult.Success(refresh)
        } catch (t: VertexConfigError) {
            StartResult.Error(t.message ?: "Token exchange failed")
        } catch (t: Throwable) {
            StartResult.Error("Token exchange failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private suspend fun awaitAuthorize(
        activity: Activity,
        request: AuthorizationRequest,
    ): AuthorizationResult = suspendCancellableCoroutine { cont ->
        val client = Identity.getAuthorizationClient(activity)
        client.authorize(request)
            .addOnSuccessListener { res -> if (cont.isActive) cont.resume(res) }
            .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    }

    private companion object {
        const val TAG = "VertexOAuthSignIn"
    }
}
