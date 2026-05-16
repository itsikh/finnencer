package io.itsikh.finnencer.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.logging.AppLogger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime accessor for this app's own signing-certificate SHA-1 and package
 * name. Needed because Google Cloud API keys (Gemini, Cloud TTS, etc.) can
 * be restricted in the GCP Console to a specific Android app — Google
 * verifies the request via two HTTP headers:
 *
 *  - `X-Android-Package` — must match the configured applicationId
 *  - `X-Android-Cert`    — must match the SHA-1 of the APK signer, as
 *                          uppercase hex WITHOUT colons
 *
 * Send these on every Gemini request so the user's key can stay restricted.
 *
 * Computed once at first access and cached.
 */
@Singleton
class AppSigningInfo @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val packageName: String = context.packageName

    /** Uppercase hex (no colons) — the format Google's API-key restriction expects. */
    val signingCertSha1Hex: String? by lazy { computeSha1Hex() }

    /** Colon-separated uppercase hex — the format users paste into GCP Console. */
    val signingCertSha1Pretty: String? by lazy {
        signingCertSha1Hex?.chunked(2)?.joinToString(":")
    }

    private fun computeSha1Hex(): String? {
        val bytes = firstSignatureBytes() ?: return null
        return MessageDigest.getInstance("SHA1").digest(bytes)
            .joinToString("") { b -> "%02X".format(b) }
    }

    private fun firstSignatureBytes(): ByteArray? {
        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            sigs?.firstOrNull()?.toByteArray()
        } catch (e: Exception) {
            AppLogger.w(TAG, "could not read signing certificate: ${e.message}")
            null
        }
    }

    private companion object { const val TAG = "AppSigningInfo" }
}
