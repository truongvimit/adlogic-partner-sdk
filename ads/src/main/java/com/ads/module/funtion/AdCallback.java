package com.ads.module.funtion;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ads.module.ads.wrapper.ApInterstitialAd;
import com.ads.module.ads.wrapper.ApNativeAd;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.rewarded.RewardedAd;


public class AdCallback {

    public void onNextAction() {
    }

    public void onAdClosed() {
    }

    /**
     * The ad's destination took the screen. Mediation splits this fact: Meta's native adapter
     * reports only this and never a click, Pangle's reports only the click — so a caller that
     * must catch every departure has to listen to both.
     */
    public void onAdOpened() {
    }

    public void onAdFailedToLoad(@Nullable LoadAdError i) {
    }

    public void onAdFailedToShow(@Nullable AdError adError) {
    }

    public void onAdLoaded() {
    }

    public void onInterstitialLoad(@Nullable InterstitialAd interstitialAd) {

    }


    public void onApInterstitialLoad(@Nullable ApInterstitialAd apInterstitialAd) {

    }


    public void onAdClicked() {
    }

    public void onAdImpression() {
    }

    public void onRewardAdLoaded(RewardedAd rewardedAd) {
    }

    public void onUnifiedNativeAdLoaded(@NonNull NativeAd unifiedNativeAd) {

    }

    public void onNativeAdLoaded(@NonNull ApNativeAd nativeAd) {

    }

    public void onInterstitialShow() {

    }

    /** Vendor-confirmed display, distinct from the legacy pre-show navigation marker. */
    public void onInterstitialDisplayed() {
    }

    /** New content policy measures impressions from the vendor impression callback. */
    public boolean usesActualInterstitialImpression() {
        return false;
    }

    /** Rechecked immediately before dispatch, after the cosmetic preparation delay. */
    public boolean canShowInterstitial() {
        return true;
    }

}
