package com.ads.module.helper.adnative

import com.ads.module.ads.wrapper.ApNativeAd

/** Native placement lifecycle, exposed by [NativeAdHelper.nativeAdState]. */
sealed interface AdNativeState {

    data object None : AdNativeState

    data object Loading : AdNativeState

    data class Loaded(val nativeAd: ApNativeAd) : AdNativeState

    /** Nothing filled and no earlier ad is on screen. */
    data object Fail : AdNativeState

    /** Helper stopped: purchased, config off, or offline with nothing to show. */
    data object Cancel : AdNativeState
}
