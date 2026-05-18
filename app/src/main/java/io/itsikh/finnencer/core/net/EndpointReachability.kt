package io.itsikh.finnencer.core.net

import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Probes the specific AI hostnames the podcast pipeline needs. Returns
 * which ones are reachable so the worker can decide whether to proceed
 * or sit in WAITING_FOR_NETWORK until the user moves somewhere with a
 * usable internet (#43).
 *
 * The probe is intentionally minimal — DNS lookup with a 3s timeout per
 * host. We don't open a TLS connection: that adds latency and isn't
 * needed to tell "Android says you're online" vs "your DNS can't see
 * Gemini". The actual API call's error message is more informative
 * than a synthetic HEAD anyway.
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
                    val ok = withTimeoutOrNull(perHostTimeoutMs) {
                        runCatching {
                            // Single lookup is enough to tell us if DNS
                            // can resolve the host at all. We don't
                            // care about the actual address.
                            InetAddress.getAllByName(endpoint.host).isNotEmpty()
                        }.getOrDefault(false)
                    } ?: false
                    endpoint to ok
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

    private companion object { const val TAG = "EndpointReachability" }
}
