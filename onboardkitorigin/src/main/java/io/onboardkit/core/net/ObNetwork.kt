package io.onboardkit.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * Whether the device can actually reach the internet, not merely whether a radio is up.
 *
 * The distinction is the whole point of the splash gate: a captive portal and a Wi-Fi with no
 * backhaul both report a transport, and letting either through spends the splash ad request on
 * a connection that cannot carry it. Only [NetworkCapabilities.NET_CAPABILITY_VALIDATED] says
 * the platform proved a route out.
 */
object ObNetwork {

    /** True when the current default network is validated for internet right now. */
    fun isValidated(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching {
            manager.getNetworkCapabilities(manager.activeNetwork).isValidated()
        }.getOrDefault(false)
    }

    /**
     * Emits the validated state, starting with the current one.
     *
     * The seed is not a convenience: with no default network at all the platform delivers no
     * callback whatsoever — not even `onUnavailable` — so an unseeded flow would hang on its
     * own emptiness in exactly the offline case this exists to serve.
     */
    fun validatedFlow(context: Context): Flow<Boolean> = callbackFlow {
        trySend(isValidated(context))
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                trySend(capabilities.isValidated())
            }

            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onUnavailable() {
                trySend(false)
            }
        }
        // A denied or throwing registration must fail closed: reporting "online" here would send
        // the splash straight into the dead ad request the gate exists to prevent.
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        if (!registered) trySend(false)
        awaitClose {
            if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    /** Suspends until the device is validated for internet. Never times out on its own. */
    suspend fun awaitValidated(context: Context) {
        validatedFlow(context).first { it }
    }
}

private fun NetworkCapabilities?.isValidated(): Boolean =
    this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
