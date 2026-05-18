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
 */
object FriendlyError {

    /** [stage] is a short label like "script", "tts", "summary" so the user
     *  knows which step blew up when failures can come from multiple. */
    fun describe(t: Throwable, stage: String? = null): String {
        val prefix = stage?.let { "${it.capitalize()}: " } ?: ""
        val cause = unwrap(t)
        val message = cause.message.orEmpty()

        // Network: no DNS / no internet
        if (cause is UnknownHostException || message.contains("Unable to resolve host", ignoreCase = true)) {
            return "${prefix}No internet connection. Check your network and try again."
        }
        // Network: timeout
        if (cause is SocketTimeoutException || message.contains("timeout", ignoreCase = true)) {
            return "${prefix}The request timed out. Your network may be slow — try again in a moment."
        }
        // HTTP status codes (the message looks like "Anthropic HTTP 401: ..."
        // or "Gemini HTTP 429: ..." per ClaudeClient / GeminiTextClient).
        val httpStatus = Regex("HTTP (\\d{3})").find(message)?.groupValues?.get(1)?.toIntOrNull()
        if (httpStatus != null) {
            return prefix + when (httpStatus) {
                401, 403 -> "Your API key was rejected. Check it in Settings → API keys."
                404 -> "The model or endpoint wasn't found. The provider may have changed it — try a different model in Settings → AI."
                429 -> "Rate limit hit. Wait a minute, then try again — or pick a different model in Settings → AI."
                500, 502, 503, 504 -> "The AI provider is having trouble right now. Try again in a few minutes."
                else -> "The AI provider returned HTTP $httpStatus. Try again, or pick a different model."
            }
        }
        // I/O fallback
        if (cause is IOException) {
            return "${prefix}Network problem: ${shorten(message)}"
        }
        // Anthropic-style cancellation (job cancelled by WorkManager)
        if (message.contains("Job was cancelled", ignoreCase = true)) {
            return "${prefix}The task was cancelled before it could finish. Try again."
        }
        // Unknown — give the message but trim noise
        return "$prefix${shorten(message).ifBlank { "Generation failed (${cause.javaClass.simpleName})" }}"
    }

    private fun unwrap(t: Throwable): Throwable {
        var c: Throwable = t
        var hops = 0
        while (c.cause != null && c.cause !== c && hops < 6) {
            c = c.cause!!
            hops++
        }
        return c
    }

    private fun shorten(s: String): String {
        val trimmed = s.trim()
        return if (trimmed.length <= 180) trimmed else trimmed.take(180) + "…"
    }

    private fun String.capitalize(): String =
        if (isEmpty()) this else this[0].uppercaseChar() + substring(1)
}
