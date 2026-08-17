package com.ads.module.helper.banner

/** Banner placement lifecycle, exposed by [BannerAdHelper.bannerAdState]. */
sealed interface AdBannerState {

    data object None : AdBannerState

    data object Loading : AdBannerState

    data object Loaded : AdBannerState

    /** Nothing filled and no earlier banner is on screen. */
    data object Fail : AdBannerState

    /** Helper stopped: purchased, config off, or offline with nothing to show. */
    data object Cancel : AdBannerState
}
