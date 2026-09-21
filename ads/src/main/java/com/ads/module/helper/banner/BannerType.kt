package com.ads.module.helper.banner

import com.ads.module.util.AppConstant
import com.google.android.gms.ads.AdSize

/** Which banner family the placement uses; maps 1:1 onto the module's loader variants. */
sealed interface BannerType {

    /** Anchored adaptive banner. */
    data object Normal : BannerType

    /** Large anchored adaptive banner: up to 20% of screen height, 50–150dp, video-eligible. */
    data object LargeAnchored : BannerType

    /** Collapsible banner; [gravity] is [AppConstant.CollapsibleGravity]. */
    data class Collapsible(
        val gravity: String = AppConstant.CollapsibleGravity.BOTTOM,
    ) : BannerType

    /** Inline adaptive banner; [style] is [SMALL_STYLE] (capped at 50dp) or [LARGE_STYLE]. */
    data class Inline(
        val style: String = LARGE_STYLE,
    ) : BannerType {
        companion object {
            const val SMALL_STYLE: String = "BANNER_INLINE_SMALL_STYLE"
            const val LARGE_STYLE: String = "BANNER_INLINE_LARGE_STYLE"
        }
    }

    /** Inline adaptive banner whose height AdMob may grow up to [maxHeightDp] (at least 32). */
    data class InlineMaxHeight(val maxHeightDp: Int) : BannerType {
        init {
            require(maxHeightDp >= MIN_MAX_HEIGHT_DP) {
                "maxHeightDp must be >= $MIN_MAX_HEIGHT_DP"
            }
        }

        companion object {
            /** Smallest cap AdMob accepts for an inline adaptive request. */
            const val MIN_MAX_HEIGHT_DP: Int = 32
        }
    }

    /** Fixed-size banner from the standard AdMob size table. */
    data class Fixed(val size: FixedBannerSize = FixedBannerSize.BANNER) : BannerType
}

/** Standard AdMob fixed banner sizes; the last two are wider than phone screens. */
enum class FixedBannerSize(val adSize: AdSize) {
    /** 320x50. */
    BANNER(AdSize.BANNER),

    /** 320x100. */
    LARGE_BANNER(AdSize.LARGE_BANNER),

    /** 300x250. */
    MEDIUM_RECTANGLE(AdSize.MEDIUM_RECTANGLE),

    /** 468x60, tablets. */
    FULL_BANNER(AdSize.FULL_BANNER),

    /** 728x90, tablets. */
    LEADERBOARD(AdSize.LEADERBOARD),
}
