package com.ads.module.ads.wrapper;

import com.google.android.gms.ads.interstitial.InterstitialAd;

public class InterstitialAdWrapper extends AdWrapperBase {
    private InterstitialAd interstitialAd;

    public InterstitialAdWrapper(StatusAd status) {
        super(status);
    }

    public InterstitialAdWrapper() {
    }

    public InterstitialAdWrapper(InterstitialAd interstitialAd) {
        this.interstitialAd = interstitialAd;
        status = StatusAd.AD_LOADED;
    }


    public void setInterstitialAd(InterstitialAd interstitialAd) {
        this.interstitialAd = interstitialAd;
        status = StatusAd.AD_LOADED;
    }

    @Override
    public boolean isReady() {
        return interstitialAd != null;
    }


    public InterstitialAd getInterstitialAd() {
        return interstitialAd;
    }
}
