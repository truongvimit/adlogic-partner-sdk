package com.ads.module.helper

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ads.module.ads.ERainAd
import com.ads.module.billing.AppPurchase

/**
 * The single pre-request gate shared by every helper and manager: answers "may this
 * placement load" with the reason a dashboard can act on.
 *
 * Checks run in the established telemetry order — disabled config, purchased, offline,
 * UA gate — so existing dashboards keep reading the same reason for the same state.
 */
object AdGate {

    @JvmStatic
    @JvmOverloads
    fun skipReason(
        context: Context,
        enabled: Boolean,
        passesUaGate: Boolean = true,
        checkNetwork: Boolean = true,
    ): AdSkipReason? = when {
        !enabled -> AdSkipReason.DISABLED_CONFIG
        isPurchased(context) -> AdSkipReason.PURCHASED
        checkNetwork && !isNetworkAvailable(context) -> AdSkipReason.OFFLINE
        !passesUaGate -> AdSkipReason.UA_GATE
        else -> null
    }

    /** UA/organic gate; [bypass] mirrors the app-side "ignoreLimit" switch. */
    @JvmStatic
    @JvmOverloads
    fun passesUaGate(forceUaCheck: Boolean, bypass: Boolean = false): Boolean =
        bypass || ERainAd.getInstance().shouldDisplayForUa(forceUaCheck)

    @JvmStatic
    fun isPurchased(context: Context): Boolean =
        runCatching { AppPurchase.getInstance().isPurchased(context) }.getOrDefault(false)

    @JvmStatic
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
