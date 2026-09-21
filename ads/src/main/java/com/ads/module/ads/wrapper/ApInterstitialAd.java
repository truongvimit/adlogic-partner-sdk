package com.ads.module.ads.wrapper;

import com.google.android.gms.ads.interstitial.InterstitialAd;

public class ApInterstitialAd {
    private InterstitialAd interstitialAd;

    public ApInterstitialAd() {
    }

    public ApInterstitialAd(InterstitialAd interstitialAd) {
        this.interstitialAd = interstitialAd;
    }

    public void setInterstitialAd(InterstitialAd interstitialAd) {
        this.interstitialAd = interstitialAd;
    }

    public boolean isReady() {
        return interstitialAd != null;
    }

    public InterstitialAd getInterstitialAd() {
        return interstitialAd;
    }
}
