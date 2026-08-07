package io.itsikh.finnencer.core.net

import io.itsikh.finnencer.logging.AppLogger
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Applies a per-request read timeout carried on a private header, then
 * strips the header before the request leaves the device.
 *
 * ## Why per-request
 *
 * Text-generation calls are non-streaming: the socket stays silent until
 * the model has finished generating the whole response, so `readTimeout`
 * is not an idle detector here — it has to exceed *total* generation
 * time. A single client-wide value therefore has to be sized for the
 * worst call in the app, which means a 900-token "why is this moving?"
 * explanation gets the same twenty-minute rope as a 24k-token deep-dive
 * report, and a genuinely wedged short call hangs for twenty minutes
 * before anyone notices.
 *
 * Callers set [HEADER] from
 * [io.itsikh.finnencer.core.work.textCallDeadlineSeconds], which sizes it
 * from the request's own `max_tokens` and clamps it against the remaining
 * job budget.
 *
 * ## Interaction with callTimeout
 *
 * OkHttp's `callTimeout` can only be set on the client, not adjusted per
 * call from an interceptor. The client value is therefore set to the
 * ceiling of what any single request may claim plus a margin, and this
 * interceptor's read timeout is what actually bounds each call.
 *
 * A request with no [HEADER] passes through untouched and keeps the
 * client defaults, so non-LLM traffic on a shared client is unaffected.
 */
class DeadlineInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val raw = request.header(HEADER)
            ?: return chain.proceed(request)

        val stripped = request.newBuilder().removeHeader(HEADER).build()
        val seconds = raw.toLongOrNull()
        if (seconds == null || seconds <= 0) {
            AppLogger.w(TAG, "ignoring unparseable $HEADER value '$raw'; using client defaults")
            return chain.proceed(stripped)
        }

        val clamped = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        return chain
            .withReadTimeout(clamped.toInt(), TimeUnit.SECONDS)
            .proceed(stripped)
    }

    companion object {
        /** Consumed and removed here — never sent to a provider. */
        const val HEADER = "X-Finnencer-Deadline-Seconds"

        /** Ceiling any single request may claim; the OkHttp client's
         *  callTimeout must exceed this plus connect/write overhead. */
        const val MAX_SECONDS = 1200L
        private const val MIN_SECONDS = 30L
        private const val TAG = "DeadlineInterceptor"
    }
}
