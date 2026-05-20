package io.itsikh.finnencer.core.net

import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Probes the specific AI hostnames the podcast pipeline needs. Returns
 * which ones are reachable so the worker can decide whether to proceed
 * or sit in WAITING_FOR_NETWORK until the user moves somewhere with a
 * usable internet (#43).
 *
 * The probe is intentionally minimal — DNS lookup with a hard timeout
 * per host. We don't open a TLS connection: that adds latency and
 * isn't needed to tell "Android says you're online" vs "your DNS
 * can't see Gemini". The actual API call's error message is more
 * informative than a synthetic HEAD anyway.
 *
 * Implementation note: `InetAddress.getAllByName` is a BLOCKING JNI
 * syscall that ignores coroutine cancellation, so wrapping it in
 * `withTimeoutOrNull` is ineffective when DNS is genuinely slow —
 * the timeout fires the cancel signal but the syscall keeps running
 * and the coroutine never returns (#52). We run the lookup on a
 * daemon thread and use a CountDownLatch with a wall-clock timeout
 * so the probe always returns within the budget regardless of what
 * the underlying DNS resolver is doing.
 */
@Singleton
class EndpointReachability @Inject constructor() {

    enum class Endpoint(val host: String, val displayName: String) {
        ANTHROPIC("api.anthropic.com", "Anthropic (script writer)"),
        GEMINI_AI_STUDIO("generativelanguage.googleapis.com", "Gemini (voice synthesis)"),
    }

    data class Report(
        val reachable: List<Endpoint>,
        val unreachable: List<Endpoint>,
    ) {
        val allReachable: Boolean get() = unreachable.isEmpty()
    }

    /**
     * Probe every endpoint in [needed] in parallel and return a per-host
     * verdict. Each lookup is bounded by [perHostTimeoutMs] so the
     * total wait is roughly the slowest single probe, not the sum.
     */
    suspend fun probe(
        needed: List<Endpoint>,
        perHostTimeoutMs: Long = 3_000L,
    ): Report = withContext(Dispatchers.IO) {
        coroutineScope {
            val results = needed.map { endpoint ->
                async {
                    endpoint to probeHostWithHardTimeout(endpoint.host, perHostTimeoutMs)
                }
            }.map { it.await() }
            val reachable = results.filter { it.second }.map { it.first }
            val unreachable = results.filter { !it.second }.map { it.first }
            if (unreachable.isNotEmpty()) {
                AppLogger.w(
                    TAG,
                    "endpoint probe: " +
                        "reachable=${reachable.map { it.host }} " +
                        "unreachable=${unreachable.map { it.host }}",
                )
            }
            Report(reachable = reachable, unreachable = unreachable)
        }
    }

    /**
     * Run `InetAddress.getAllByName(host)` on a daemon thread and return
     * its result within [timeoutMs]. If the DNS lookup doesn't finish
     * by then, we return false and let the thread continue in the
     * background (it'll die naturally when the OS-level DNS timeout
     * trips — typically within 30s). This is the layer that makes
     * the probe genuinely time-bounded; without it a slow DNS server
     * pins the worker in CONNECTIVITY_CHECK indefinitely (#52).
     */
    private fun probeHostWithHardTimeout(host: String, timeoutMs: Long): Boolean {
        val result = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val thread = Thread(
            {
                try {
                    result.set(InetAddress.getAllByName(host).isNotEmpty())
                } catch (_: Throwable) {
                    result.set(false)
                } finally {
                    done.countDown()
                }
            },
            "dns-probe-$host",
        ).apply { isDaemon = true }
        thread.start()
        val finished = try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            // Best-effort signal to the syscall thread that we don't
            // need the answer anymore. The native resolver may ignore
            // it, but the daemon flag means it can't keep the JVM alive.
            runCatching { thread.interrupt() }
            return false
        }
        return result.get()
    }

    private companion object { const val TAG = "EndpointReachability" }
}
