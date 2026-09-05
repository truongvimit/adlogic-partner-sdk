package com.ads.module.funtion;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ads.module.ads.wrapper.ApInterstitialAd;
import com.ads.module.ads.wrapper.ApNativeAd;
import com.ads.module.helper.AdSkipReason;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd;


public class AdCallback {

    /** The SDK is invoking a vendor load, after its request guards. Cache delivery does not fire it. */
    public void onAdRequestStarted(String adUnitId) {
    }

    /**
     * Whether this load's owner can currently accept a vendor fill. The default accepts it;
     * cache/helper owners override this to check their generation, authorization and lifecycle.
     * Load instrumentation checks immediately before its logical terminal. A false result records
     * failure while the original typed callback still runs its cleanup/navigation contract.
     */
    public boolean canAcceptLoadedAd() {
        return true;
    }

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

    /**
     * The SDK declined before invoking the vendor's show method, so the ad was not consumed.
     * The default continues the legacy navigation contract. This is not a vendor show failure.
     */
    public void onAdShowRejected(@NonNull AdSkipReason reason) {
        onNextAction();
    }

    /** The vendor confirmed fullscreen presentation; preparation/navigation is not presentation. */
    public void onAdPresented() {
    }

    /**
     * Optional owner check immediately before vendor invocation. Check the captured ad's validity,
     * not a cache entry already consumed for this presentation. Null permits the attempt;
     * the SDK still checks current consent and host lifecycle. Default: no owner restriction.
     * A thrown exception rejects with PREPARATION_FAILED before the ad is consumed.
     */
    @Nullable
    public AdSkipReason getAdShowSkipReason() {
        return null;
    }

    public void onAdFailedToShowHigh(@Nullable AdError adError) {
    }

    public void onAdFailedToShowMedium(@Nullable AdError adError) {
    }

    public void onAdFailedToShowAll(@Nullable AdError adError) {
    }

    public void onAdLoaded() {
    }

    public void onAdLoadedHigh() {
    }

    public void onAdLoadedAll() {
    }

    public void onAdSplashReady() {
    }

    public void onInterstitialLoad(@Nullable InterstitialAd interstitialAd) {

    }


    public void onApInterstitialLoad(@Nullable ApInterstitialAd apInterstitialAd) {

    }


    public void onAdClicked() {
    }

    public void onAdClickedHigh() {
    }

    public void onAdClickedMedium() {
    }

    public void onAdClickedAll() {
    }


    public void onAdImpression() {
    }

    public void onRewardAdLoaded(RewardedAd rewardedAd) {
    }

    public void onRewardAdLoaded(RewardedInterstitialAd rewardedAd) {
    }


    public void onUnifiedNativeAdLoaded(@NonNull NativeAd unifiedNativeAd) {

    }

    public void onNativeAdLoaded(@NonNull ApNativeAd nativeAd) {

    }

    /** Legacy preparation/commit marker. Use onAdPresented for proof of fullscreen presentation. */
    public void onInterstitialShow() {

    }

    public void onAdSplashHigh1Ready() {

    }

    public void onAdSplashHigh2Ready() {

    }

    public void onAdSplashHigh3Ready() {

    }

    public void onAdSplashNormalReady() {

    }

    public void onAdHighFailedToLoad() {

    }

    public void onAdPriorityFailedToLoad(@Nullable AdError adError) {

    }

    public void onAdPriorityFailedToShow(@Nullable AdError adError) {

    }
}
