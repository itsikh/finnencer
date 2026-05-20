package io.itsikh.finnencer.data.ai

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Translate the messy exceptions surfaced by Retrofit / OkHttp / Anthropic /
 * Gemini into a one-sentence, user-actionable error string suitable for
 * display next to a failed AI artefact (podcast, report, summary).
 *
 * The raw exception is still recorded by [AppLogger] for diagnostics; this
 * function exists so the UI doesn't dump an `UnknownHostException` stack
 * at the user (#38 — "please provide clearer errors").
 *
 * Matching walks the cause chain top-down and returns the first frame
 * that maps to a known category. Drilling straight to the root cause
 * before matching (the previous behavior) missed the `UnknownHostException`
 * wrapper on Android because the root frame is `GaiException` whose
 * message reads `android_getaddrinfo failed: EAI_NODATA (...)` — neither
 * the type nor the substring matched, so the raw native string leaked to
 * the UI (#41).
 */
object FriendlyError {

    /** [stage] is a short label like "script", "tts", "summary" so the user
     *  knows which step blew up when failures can come from multiple. */
    fun describe(t: Throwable, stage: String? = null): String {
        val prefix = stage?.let { "${it.capitalize()}: " } ?: ""
        // Walk top-down: the most specific mappable frame is usually the
        // outer wrapper (UnknownHostException, SocketTimeoutException,
        // our own IOException("Anthropic HTTP …")) — not the inner root.
        for (frame in causeChain(t)) {
            val match = matchFrame(frame)
            if (match != null) return prefix + match
        }
        // Final fallback — surface the root message but trim noise.
        val root = causeChain(t).lastOrNull() ?: t
        val msg = shorten(root.message.orEmpty())
        return "$prefix${msg.ifBlank { "Generation failed (${root.javaClass.simpleName})" }}"
    }

    private fun matchFrame(frame: Throwable): String? {
        val message = frame.message.orEmpty()

        // Network: no DNS / no internet — catch by type, by the JVM
        // wrapper's "Unable to resolve host" message, AND by the native
        // GaiException's EAI_NODATA payload (#41).
        if (frame is UnknownHostException ||
            message.contains("Unable to resolve host", ignoreCase = true) ||
            message.contains("EAI_NODATA", ignoreCase = true) ||
            message.contains("android_getaddrinfo failed", ignoreCase = true) ||
            message.contains("No address associated with hostname", ignoreCase = true)
        ) {
            return "No internet connection. Check your network and try again."
        }
        // Network: timeout
        if (frame is SocketTimeoutException || message.contains("timeout", ignoreCase = true)) {
            return "The request timed out. Your network may be slow — try again in a moment."
        }
        // HTTP status codes (message looks like "Anthropic HTTP 401: ..."
        // or "Gemini HTTP 429: ..." per ClaudeClient / GeminiTextClient).
        val httpStatus = Regex("HTTP (\\d{3})").find(message)?.groupValues?.get(1)?.toIntOrNull()
        if (httpStatus != null) {
            return when (httpStatus) {
                401, 403 -> "Your API key was rejected. Check it in Settings → API keys."
                404 -> "The model or endpoint wasn't found. The provider may have changed it — try a different model in Settings → AI."
                429 -> "Rate limit hit. Wait a minute, then try again — or pick a different model in Settings → AI."
                500, 502, 503, 504 -> "The AI provider is having trouble right now. Try again in a few minutes."
                else -> "The AI provider returned HTTP $httpStatus. Try again, or pick a different model."
            }
        }
        // Cancellation (WorkManager / scope teardown)
        if (message.contains("Job was cancelled", ignoreCase = true)) {
            return "The task was cancelled before it could finish. Try again."
        }
        // Gemini TTS returned a successful HTTP response but no audio.
        // After v0.0.71 this only surfaces if all 10 retries also came
        // back empty — almost always means the chunk hit a persistent
        // safety/recitation block rather than a transient glitch (#50).
        if (message.contains("Gemini returned no audio", ignoreCase = true)) {
            return "Gemini blocked one of the script chunks (likely a safety filter). Tap Read the script to inspect it, then retry — sometimes a fresh attempt sails through."
        }
        // I/O fallback — only after the more specific checks above, since
        // UnknownHostException and SocketTimeoutException both extend IOException.
        if (frame is IOException) {
            return "Network problem: ${shorten(message)}"
        }
        return null
    }

    private fun causeChain(t: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>(4)
        var c: Throwable? = t
        var hops = 0
        while (c != null && hops < 8) {
            out += c
            val next = c.cause
            if (next === c) break
            c = next
            hops++
        }
        return out
    }

    private fun shorten(s: String): String {
        val trimmed = s.trim()
        return if (trimmed.length <= 180) trimmed else trimmed.take(180) + "…"
    }

    private fun String.capitalize(): String =
        if (isEmpty()) this else this[0].uppercaseChar() + substring(1)
}
