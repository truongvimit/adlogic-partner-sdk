package com.ads.module.tracking;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ads.module.ads.wrapper.ApInterstitialAd;
import com.ads.module.ads.wrapper.ApNativeAd;
import com.ads.module.funtion.AdCallback;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd;

import java.util.concurrent.atomic.AtomicBoolean;

import io.trackkit.AdFormat;
import io.trackkit.Tracker;
import io.trackkit.TrackkitEvents;
import io.trackkit.PlacementRegistry;

/**
 * Decorator that emits the ad lifecycle into Trackkit and always forwards to the wrapped callback.
 *
 * <p>It also registers the ad unit against its placement and format, which is what lets the paid and
 * click bridges attribute an impression to a screen — AdMob's callbacks only know the ad unit.
 *
 * <p>The tiered splash flows call a per-tier variant of each callback ({@code onAdLoadedHigh},
 * {@code onAdClickedAll}, …), so loaded / shown / closed are emitted at most once per instance and
 * a tier race can not inflate the counts.
 *
 * <p>{@code ad_click} is deliberately not emitted here — {@code ERainLogEventManager.logClickAdsEvent}
 * owns it, from the vendor callback that every click path reaches.
 *
 * <p>Applied by {@code ERainAd} itself, never by the host app: the layer that creates the ad object
 * attaches the instrumentation, exactly as it attaches {@code OnPaidEventListener}.
 */
public class TrackingAdCallback extends AdCallback {

    private final String placement;
    private final AdFormat format;
    private final String adUnitId;
    private final AdCallback delegate;
    private final long requestedAtMs;

    private final AtomicBoolean loadedReported = new AtomicBoolean(false);
    private final AtomicBoolean loadFailedReported = new AtomicBoolean(false);
    private final AtomicBoolean shownReported = new AtomicBoolean(false);
    private final AtomicBoolean showFailedReported = new AtomicBoolean(false);
    private final AtomicBoolean closedReported = new AtomicBoolean(false);

    public TrackingAdCallback(String placement, AdFormat format, String adUnitId, @Nullable AdCallback delegate) {
        this.placement = placement == null || placement.isEmpty() ? "unknown" : placement;
        this.format = format == null ? AdFormat.UNKNOWN : format;
        this.adUnitId = adUnitId;
        this.delegate = delegate;
        this.requestedAtMs = System.currentTimeMillis();
        PlacementRegistry.register(adUnitId, this.placement);
        AdFormatRegistry.register(adUnitId, this.format);
    }

    // -----------------------------------------------------------------------
    // Load
    // -----------------------------------------------------------------------

    @Override
    public void onAdLoaded() {
        reportLoaded();
        if (delegate != null) delegate.onAdLoaded();
    }

    @Override
    public void onAdLoadedHigh() {
        reportLoaded();
        if (delegate != null) delegate.onAdLoadedHigh();
    }

    @Override
    public void onAdLoadedAll() {
        reportLoaded();
        if (delegate != null) delegate.onAdLoadedAll();
    }

    @Override
    public void onAdSplashReady() {
        reportLoaded();
        if (delegate != null) delegate.onAdSplashReady();
    }

    @Override
    public void onAdSplashHigh1Ready() {
        reportLoaded();
        if (delegate != null) delegate.onAdSplashHigh1Ready();
    }

    @Override
    public void onAdSplashHigh2Ready() {
        reportLoaded();
        if (delegate != null) delegate.onAdSplashHigh2Ready();
    }

    @Override
    public void onAdSplashHigh3Ready() {
        reportLoaded();
        if (delegate != null) delegate.onAdSplashHigh3Ready();
    }

    @Override
    public void onAdSplashNormalReady() {
        reportLoaded();
        if (delegate != null) delegate.onAdSplashNormalReady();
    }

    @Override
    public void onInterstitialLoad(@Nullable InterstitialAd interstitialAd) {
        reportLoaded();
        if (delegate != null) delegate.onInterstitialLoad(interstitialAd);
    }

    @Override
    public void onApInterstitialLoad(@Nullable ApInterstitialAd apInterstitialAd) {
        reportLoaded();
        if (delegate != null) delegate.onApInterstitialLoad(apInterstitialAd);
    }

    @Override
    public void onRewardAdLoaded(RewardedAd rewardedAd) {
        reportLoaded();
        if (delegate != null) delegate.onRewardAdLoaded(rewardedAd);
    }

    @Override
    public void onRewardAdLoaded(RewardedInterstitialAd rewardedAd) {
        reportLoaded();
        if (delegate != null) delegate.onRewardAdLoaded(rewardedAd);
    }

    @Override
    public void onUnifiedNativeAdLoaded(@NonNull NativeAd unifiedNativeAd) {
        reportLoaded();
        if (delegate != null) delegate.onUnifiedNativeAdLoaded(unifiedNativeAd);
    }

    @Override
    public void onNativeAdLoaded(@NonNull ApNativeAd nativeAd) {
        reportLoaded();
        if (delegate != null) delegate.onNativeAdLoaded(nativeAd);
    }

    @Override
    public void onAdFailedToLoad(@Nullable LoadAdError i) {
        reportLoadFailed(i == null ? null : i.getCode());
        if (delegate != null) delegate.onAdFailedToLoad(i);
    }

    @Override
    public void onAdHighFailedToLoad() {
        reportLoadFailed(null);
        if (delegate != null) delegate.onAdHighFailedToLoad();
    }

    @Override
    public void onAdPriorityFailedToLoad(@Nullable AdError adError) {
        reportLoadFailed(adError == null ? null : adError.getCode());
        if (delegate != null) delegate.onAdPriorityFailedToLoad(adError);
    }

    // -----------------------------------------------------------------------
    // Show
    // -----------------------------------------------------------------------

    @Override
    public void onAdImpression() {
        reportShown();
        if (delegate != null) delegate.onAdImpression();
    }

    @Override
    public void onInterstitialShow() {
        reportShown();
        if (delegate != null) delegate.onInterstitialShow();
    }

    @Override
    public void onAdFailedToShow(@Nullable AdError adError) {
        reportShowFailed(adError);
        if (delegate != null) delegate.onAdFailedToShow(adError);
    }

    @Override
    public void onAdFailedToShowHigh(@Nullable AdError adError) {
        reportShowFailed(adError);
        if (delegate != null) delegate.onAdFailedToShowHigh(adError);
    }

    @Override
    public void onAdFailedToShowMedium(@Nullable AdError adError) {
        reportShowFailed(adError);
        if (delegate != null) delegate.onAdFailedToShowMedium(adError);
    }

    @Override
    public void onAdFailedToShowAll(@Nullable AdError adError) {
        reportShowFailed(adError);
        if (delegate != null) delegate.onAdFailedToShowAll(adError);
    }

    @Override
    public void onAdPriorityFailedToShow(@Nullable AdError adError) {
        reportShowFailed(adError);
        if (delegate != null) delegate.onAdPriorityFailedToShow(adError);
    }

    // -----------------------------------------------------------------------
    // Click / close
    //
    // Clicks are forwarded only. logClickAdsEvent already emits ad_click next to every one of these
    // vendor callbacks, and it also covers the app-open paths that never see an AdCallback.
    // -----------------------------------------------------------------------

    @Override
    public void onAdClicked() {
        if (delegate != null) delegate.onAdClicked();
    }

    @Override
    public void onAdClickedHigh() {
        if (delegate != null) delegate.onAdClickedHigh();
    }

    @Override
    public void onAdClickedMedium() {
        if (delegate != null) delegate.onAdClickedMedium();
    }

    @Override
    public void onAdClickedAll() {
        if (delegate != null) delegate.onAdClickedAll();
    }

    @Override
    public void onAdClosed() {
        if (closedReported.compareAndSet(false, true)) {
            Tracker.track(new TrackkitEvents.Ad.Closed(placement, format, adUnitId));
        }
        if (delegate != null) delegate.onAdClosed();
    }

    @Override
    public void onAdOpened() {
        if (delegate != null) delegate.onAdOpened();
    }

    // -----------------------------------------------------------------------
    // Flow control — no lifecycle meaning, delegate only
    // -----------------------------------------------------------------------

    @Override
    public void onNextAction() {
        if (delegate != null) delegate.onNextAction();
    }

    // -----------------------------------------------------------------------
    // Emitters
    // -----------------------------------------------------------------------

    private void reportLoaded() {
        if (loadedReported.compareAndSet(false, true)) {
            Tracker.track(new TrackkitEvents.Ad.Loaded(
                    placement, format, adUnitId, System.currentTimeMillis() - requestedAtMs));
        }
    }

    private void reportLoadFailed(@Nullable Integer errorCode) {
        if (loadFailedReported.compareAndSet(false, true)) {
            Tracker.track(new TrackkitEvents.Ad.LoadFailed(placement, format, adUnitId, errorCode));
        }
    }

    private void reportShown() {
        if (shownReported.compareAndSet(false, true)) {
            Tracker.track(new TrackkitEvents.Ad.Show(placement, format, adUnitId));
        }
    }

    private void reportShowFailed(@Nullable AdError adError) {
        if (showFailedReported.compareAndSet(false, true)) {
            Tracker.track(new TrackkitEvents.Ad.ShowFailed(
                    placement, format, adUnitId, adError == null ? null : adError.getCode()));
        }
    }
}
