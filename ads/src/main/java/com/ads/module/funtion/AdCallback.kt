package com.ads.module.funtion

import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd

open class AdCallback {

    open fun onNextAction() {}

    open fun onAdClosed() {}

    /**
     * The ad's destination took the screen. Mediation splits this fact: Meta's native adapter
     * reports only this and never a click, Pangle's reports only the click — so a caller that must
     * catch every departure has to listen to both.
     */
    open fun onAdOpened() {}

    open fun onAdFailedToLoad(i: LoadAdError?) {}

    open fun onAdFailedToShow(adError: AdError?) {}

    open fun onAdLoaded() {}

    open fun onApInterstitialLoad(apInterstitialAd: ApInterstitialAd?) {}

    open fun onAdClicked() {}

    open fun onAdImpression() {}

    open fun onRewardAdLoaded(rewardedAd: RewardedAd?) {}

    open fun onNativeAdLoaded(nativeAd: ApNativeAd) {}

    open fun onInterstitialShow() {}

    /** Vendor-confirmed display, distinct from the legacy pre-show navigation marker. */
    open fun onInterstitialDisplayed() {}

    /** New content policy measures impressions from the vendor impression callback. */
    open fun usesActualInterstitialImpression(): Boolean = false

    /** Rechecked immediately before dispatch, after the cosmetic preparation delay. */
    open fun canShowInterstitial(): Boolean = true
}
