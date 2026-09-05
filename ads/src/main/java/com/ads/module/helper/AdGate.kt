package com.ads.module.helper

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.ads.module.admob.AppOpenManager
import com.ads.module.consent.ConsentCenter
import com.ads.module.ads.ERainAd
import com.ads.module.helper.adnative.NativeAdPreload
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.helper.reward.RewardAdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * The single pre-request gate shared by every helper and manager: answers "may this
 * placement load" with the reason a dashboard can act on.
 *
 * Checks run in the established telemetry order — disabled config, purchased, offline,
 * UA gate — followed by consent authorization and its active form.
 */
object AdGate {
    private val bufferScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var entitlementObserver: Job? = null

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

    /** UA/organic gate; [bypass] mirrors the app-side "ignoreLimit" switch. */
    @JvmStatic
    @JvmOverloads
    fun passesUaGate(forceUaCheck: Boolean, bypass: Boolean = false): Boolean =
        bypass || ERainAd.getInstance().shouldDisplayForUa(forceUaCheck)

    /**
     * Reads the installed source and starts process-wide buffer invalidation on first use.
     * Only the application context is retained; installing a new source also updates the observer.
     */
    @JvmStatic
    fun isPurchased(context: Context): Boolean {
        observeEntitlement(context.applicationContext)
        return Entitlement.isPremium(context)
    }

    private fun observeEntitlement(appContext: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { observeEntitlement(appContext) }
            return
        }
        if (entitlementObserver?.isActive == true) return
        // Publish ownership before collecting the seed: releasing a buffer may reenter a gate.
        entitlementObserver = bufferScope.launch(start = CoroutineStart.LAZY) {
            Entitlement.observe(appContext).filter { it }.collect { releaseBufferedAds() }
        }
        entitlementObserver?.start()
    }

    /**
     * Clears the helper buffers for an initial true value and later false -> true changes.
     * Existing helpers already observe [Entitlement] automatically; this optional adapter is for
     * hosts that also supply a separate observable billing source.
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
            premium.filter { it }.collect { releaseBufferedAds() }
        }

    /**
     * Releases native preload, interstitial/reward manager buffers and cached app-open ads.
     * Call on the main thread. An ad already presenting keeps its own terminal callback.
     */
    @JvmStatic
    fun releaseBufferedAds() {
        NativeAdPreload.getInstance().invalidateBuffers()
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
