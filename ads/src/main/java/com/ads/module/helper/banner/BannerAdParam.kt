package com.ads.module.helper.banner

import com.ads.module.helper.IAdsParam

/** Command parameter for [BannerAdHelper.requestAds]. */
sealed interface BannerAdParam : IAdsParam {

    /** Explicit request using the configured type, including collapsible intent. */
    data object Request : BannerAdParam

    /** Resume/timer refresh. A collapsible placement requests an ordinary anchored banner. */
    data object Reload : BannerAdParam
}
