package com.ads.module.helper.adnative

import android.app.Activity
import android.content.Context
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate

/** Optional preparation of the next unused ad; refresh timing belongs to the visible helper. */
class NativeAdPreloadClientOption @JvmOverloads constructor(
    val preloadAfterShow: Boolean = false,
    /** Retained for source compatibility. The shared placement store now holds one unused ad. */
    @Deprecated("One unused ad per placement; repeated requests never append batches")
    val preloadBuffer: Int = 1,
    /** Retained for source compatibility; every load/reload now uses the shared store. */
    @Deprecated("All native requests share the placement store")
    val preloadOnResume: Boolean = true,
)

/** Compatibility entry points. All loading and ownership live in [NativeAdManager]. */
class NativeAdPreload private constructor() {
    fun keyOf(config: NativeAdConfig): String = NativeAdManager.keyOf(config)

    @JvmOverloads
    fun preload(activity: Activity, config: NativeAdConfig, buffer: Int = 1): Boolean =
        preloadWithKey(keyOf(config), activity, config, buffer)

    /** true only if a request started; [buffer] no longer appends work to a loading/ready slot. */
    @JvmOverloads
    fun preloadWithKey(key: String, activity: Activity, config: NativeAdConfig, buffer: Int = 1): Boolean {
        require(buffer > 0) { "Buffer must be greater than 0" }
        return NativeAdManager.preload(activity, key, config, reportTelemetry = false)
    }

    /** Legacy covered/not-covered result, including an existing load or fresh unused fill. */
    fun preloadWithKeyIfEmpty(key: String, activity: Activity, config: NativeAdConfig): Boolean =
        isPreloadAvailable(key) || preloadWithKey(key, activity, config)

    fun canRequestLoad(context: Context): Boolean = AdGate.skipReason(context, enabled = true) == null
    fun getAdNative(key: String): ApNativeAd? = NativeAdManager.peek(key)
    fun pollAdNative(key: String): ApNativeAd? = NativeAdManager.poll(key)
    fun isPreloadAvailable(key: String): Boolean = NativeAdManager.isReady(key) || NativeAdManager.isLoading(key)
    fun isPreloadInProgress(key: String): Boolean = NativeAdManager.isLoading(key)
    fun getNativeAdBuffer(key: String): List<ApNativeAd> = listOfNotNull(getAdNative(key))
    fun awaitNext(key: String, onResult: (ApNativeAd?) -> Unit) { NativeAdManager.awaitNext(key, onResult) }
    fun registerAdCallback(key: String, adCallback: AdCallback) { NativeAdManager.register(key, adCallback) }
    fun unregisterAdCallback(key: String, adCallback: AdCallback) { NativeAdManager.unregister(key, adCallback) }
    fun release(key: String) { NativeAdManager.release(key) }
    fun releaseAll() { NativeAdManager.releaseAll() }

    companion object {
        private val instance = NativeAdPreload()
        @JvmStatic fun getInstance(): NativeAdPreload = instance
    }
}
