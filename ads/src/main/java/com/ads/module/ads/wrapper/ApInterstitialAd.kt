package com.ads.module.ads.wrapper

import com.google.android.gms.ads.interstitial.InterstitialAd

class ApInterstitialAd @JvmOverloads constructor(var interstitialAd: InterstitialAd? = null) {
    val isReady: Boolean
        get() = interstitialAd != null
}
