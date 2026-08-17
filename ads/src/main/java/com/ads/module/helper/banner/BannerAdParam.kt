package com.ads.module.helper.banner

import com.ads.module.helper.IAdsParam

/** Command parameter for [BannerAdHelper.requestAds]. */
sealed interface BannerAdParam : IAdsParam {

    /** First load. */
    data object Request : BannerAdParam

    /** Re-load an already-active placement (resume / auto-reload path). */
    data object Reload : BannerAdParam
}
