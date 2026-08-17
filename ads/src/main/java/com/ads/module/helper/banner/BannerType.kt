package com.ads.module.helper.banner

import com.ads.module.admob.Admob
import com.ads.module.util.AppConstant

/** Which banner family the placement uses; maps 1:1 onto the module's loader variants. */
sealed interface BannerType {

    /** Anchored adaptive banner. */
    data object Normal : BannerType

    /** Collapsible banner; [gravity] is [AppConstant.CollapsibleGravity]. */
    data class Collapsible(
        val gravity: String = AppConstant.CollapsibleGravity.BOTTOM,
    ) : BannerType

    /** Inline adaptive banner; [style] is one of the module's `BANNER_INLINE_*_STYLE`. */
    data class Inline(
        val style: String = Admob.BANNER_INLINE_LARGE_STYLE,
    ) : BannerType
}
