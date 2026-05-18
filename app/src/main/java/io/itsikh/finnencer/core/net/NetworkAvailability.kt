package io.itsikh.finnencer.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.logging.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Thin wrapper around [ConnectivityManager] used by the AI pipeline to:
 *  - **Fast-fail** when the device has no usable network at job start
 *    (no point burning a 10-retry × 50s backoff on a plainly offline
 *    device, just tell the user "no internet" right away — #42).
 *  - **Wake retries** the moment connectivity returns, instead of
 *    sleeping the fixed backoff window blind.
 */
@Singleton
class NetworkAvailability @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * `true` if the system currently reports at least one network with
     * the INTERNET capability and validated transport. Snapshot only —
     * the network can disappear immediately after this returns.
     */
    fun isConnected(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Suspend until the device has a usable internet-capable network,
     * up to [maxWaitMs]. Returns `true` if network arrived (or was
     * already present), `false` on timeout.
     *
     * Used in place of `delay(backoffMs)` inside the AI retry helpers so
     * a transient drop resumes the instant connectivity is back instead
     * of waiting the full backoff window. The NetworkCallback is always
     * unregistered, even on cancellation.
     */
    suspend fun awaitNetwork(maxWaitMs: Long): Boolean {
        if (isConnected()) return true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false

        return withTimeoutOrNull(maxWaitMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                var registered = false
                lateinit var callback: ConnectivityManager.NetworkCallback
                fun unregisterOnce() {
                    if (registered) {
                        registered = false
                        runCatching { cm.unregisterNetworkCallback(callback) }
                    }
                }
                callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // Verify the network actually has internet — some
                        // captive-portal Wi-Fi reports onAvailable before
                        // it's truly usable.
                        val caps = cm.getNetworkCapabilities(network)
                        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                        if (internet && cont.isActive) {
                            unregisterOnce()
                            cont.resume(true)
                        }
                    }
                }
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                runCatching {
                    cm.registerNetworkCallback(request, callback)
                    registered = true
                }.onFailure {
                    AppLogger.w(TAG, "registerNetworkCallback failed: ${it.message}")
                    if (cont.isActive) cont.resume(false)
                }
                // Fires on coroutine cancellation (including the outer
                // withTimeoutOrNull timing out) — keeps the callback from
                // leaking.
                cont.invokeOnCancellation { unregisterOnce() }
            }
        } ?: false
    }

    private companion object { const val TAG = "NetworkAvailability" }
}
