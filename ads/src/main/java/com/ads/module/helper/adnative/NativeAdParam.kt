package com.ads.module.helper.adnative

import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.helper.IAdsParam

/** Command parameter for [NativeAdHelper.requestAds]. */
sealed interface NativeAdParam : IAdsParam {

    /** First load; consumes a preloaded ad when one is buffered. */
    data object Request : NativeAdParam

    /** Re-load an already-active placement (resume / auto-reload path). */
    data object Reload : NativeAdParam

    /** Inject an ad the caller already loaded. */
    data class Ready(val nativeAd: ApNativeAd) : NativeAdParam
}
