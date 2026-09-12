package com.ads.module.helper

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ads.module.admob.AppOpenManager
import com.ads.module.config.AdRemoteConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.ads.ERainAd
import com.ads.module.helper.adnative.NativeAdPreload
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.helper.reward.RewardAdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

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
        !ConsentCenter.canRequestAds() -> AdSkipReason.CONSENT_NOT_GRANTED
        ConsentCenter.isFormShowing() -> AdSkipReason.CONSENT_FORM_SHOWING
        else -> null
    }

    /**
     * The same answer as [skipReason], with the placement's own configuration filled in from
     * `ad_config.json`: its on/off switch and `enable_ua_check`.
     *
     * A placement the payload does not declare is [AdSkipReason.DISABLED_CONFIG] — an ad the app
     * never configured must not be requested just because a call site names it.
     */
    @JvmStatic
    @JvmOverloads
    fun placementSkipReason(
        context: Context,
        placement: String,
        checkNetwork: Boolean = true,
    ): AdSkipReason? = skipReason(
        context,
        enabled = placementEnabled(placement),
        passesUaGate = placementPassesUaGate(placement),
        checkNetwork = checkNetwork,
    )

    /** The waterfall configured for [placement], highest floor first; empty when it is off. */
    @JvmStatic
    fun adUnitIds(placement: String): List<String> =
        AdRemoteConfig.getInstance().tiersFor(placement)

    /** True when [placement] is switched on and has at least one usable ad unit id. */
    @JvmStatic
    fun placementEnabled(placement: String): Boolean = adUnitIds(placement).isNotEmpty()

    /** The UA/organic gate resolved from the placement's own `enable_ua_check`. */
    @JvmStatic
    fun placementPassesUaGate(placement: String): Boolean =
        passesUaGate(AdRemoteConfig.getInstance().ads[placement]?.enableUaCheck == true)

    /** UA/organic gate; [bypass] mirrors the app-side "ignoreLimit" switch. */
    @JvmStatic
    @JvmOverloads
    fun passesUaGate(forceUaCheck: Boolean, bypass: Boolean = false): Boolean =
        bypass || ERainAd.getInstance().shouldDisplayForUa(forceUaCheck)

    @JvmStatic
    fun isPurchased(context: Context): Boolean = Entitlement.isPremium(context)

    /**
     * Drops every preloaded ad when [premium] flips false -> true; a purchase that lands
     * mid-session would otherwise leave a bought user watching what was already buffered.
     *
     * @param premium the app's premium state, e.g. `Billing.isPremium`
     * @param context defaults to the main dispatcher because the release paths destroy GMA ad
     *                objects, which is a main-thread-only API; override it from tests
     * @return the collecting job — cancel it to uninstall the observer
     */
    @JvmStatic
    @JvmOverloads
    fun installPremiumObserver(
        scope: CoroutineScope,
        premium: StateFlow<Boolean>,
        context: CoroutineContext = Dispatchers.Main.immediate,
    ): Job =
        scope.launch(context) {
            premium.drop(1).filter { it }.collect { releaseBufferedAds() }
        }

    /** Releases every ad buffer the module owns; safe to call at any time. */
    @JvmStatic
    fun releaseBufferedAds() {
        NativeAdPreload.getInstance().releaseAll()
        InterstitialAdManager.releaseAll()
        RewardAdManager.releaseAll()
        AppOpenManager.getInstance().releaseCachedAds()
    }

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
