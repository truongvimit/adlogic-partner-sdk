package com.ads.module.ads.wrapper

import android.os.SystemClock
import com.ads.module.config.settings.AdBehavior
import com.google.android.gms.ads.nativead.NativeAd

class ApNativeAd(var layoutCustomNative: Int, admobNativeAd: NativeAd?) {
    private val loadedAtMs = SystemClock.elapsedRealtime()
    private var destroyed = false

    var admobNativeAd: NativeAd? = admobNativeAd
        private set

    val isReady: Boolean
        get() = admobNativeAd != null

    val isUsable: Boolean
        get() = !destroyed && isReady &&
            SystemClock.elapsedRealtime() - loadedAtMs < AdBehavior.number("native.cache.max_age_ms")

    /** Releases a consumed or expired ad exactly once. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        val ad = admobNativeAd
        admobNativeAd = null
        ad?.destroy()
    }
}
