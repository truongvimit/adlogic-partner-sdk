package com.ads.module.billing

import com.ads.module.util.AppUtil

/**
 * Process-wide switches for the billing engine.
 *
 * Dev mode simulates purchases without Play. It is fail-closed: off unless the app calls
 * [setDevMode], or — when `:ads` is on the classpath — `ERainAdConfig.variantDev` says otherwise,
 * so a partner running both modules keeps the exact behaviour of the pre-split SDK.
 */
object BillingKit {

    @Volatile
    private var devMode: Boolean? = null

    @Volatile
    private var adsOnClasspath = true

    /** Explicit dev-mode switch; wins over the `:ads` config flag once called. */
    @JvmStatic
    fun setDevMode(enabled: Boolean) {
        devMode = enabled
    }

    @JvmStatic
    fun isDevMode(): Boolean = devMode ?: adsVariantDev() ?: false

    // Read-through instead of a push from ERainAd.init: :ads must stay free of billing
    // references, and reading keeps the pre-init window behaving exactly as before the split.
    private fun adsVariantDev(): Boolean? {
        if (!adsOnClasspath) return null
        return try {
            AdsDevFlagHook.variantDev()
        } catch (_: NoClassDefFoundError) {
            adsOnClasspath = false
            null
        } catch (_: ExceptionInInitializerError) {
            adsOnClasspath = false
            null
        }
    }
}

// Separate object so the AppUtil reference resolves only inside the guarded call above.
private object AdsDevFlagHook {

    fun variantDev(): Boolean? = AppUtil.VARIANT_DEV
}
