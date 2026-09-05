package com.ads.module.tracking;

import android.os.SystemClock;

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
 * <p>Direct loads start reporting at the vendor dispatch hook and share one request/terminal
 * owner. A callback instance represents one active load lifecycle; create a new callback for a
 * different load. Legacy splash callback reporting remains available explicitly for loaders that
 * do not expose a dispatch hook.
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
    private final AdLoadAttempt loadAttempt;
    private enum Mode { DIRECT, PRESENTATION, LEGACY_SPLASH }
    private Mode mode;
    private AdLoadContext presentationContext;
    private String presentationUnitId;
    private boolean requestStarted;
    private long requestStartedAtMs;
    private String requestUnitId;

    private final AtomicBoolean loadedReported = new AtomicBoolean(false);
    private final AtomicBoolean loadFailedReported = new AtomicBoolean(false);
    private final AtomicBoolean shownReported = new AtomicBoolean(false);
    private final AtomicBoolean showFailedReported = new AtomicBoolean(false);
    private final AtomicBoolean closedReported = new AtomicBoolean(false);

    public TrackingAdCallback(String placement, AdFormat format, String adUnitId, @Nullable AdCallback delegate) {
        this(placement, format, adUnitId, delegate, Mode.DIRECT);
    }

    /** Selects dispatch-backed reporting before adapting a direct ERain load's callback types. */
    public static TrackingAdCallback directLoad(String placement, AdFormat format, String adUnitId,
                                                @Nullable AdCallback delegate) {
        if (delegate instanceof TrackingAdCallback) {
            TrackingAdCallback existing = (TrackingAdCallback) delegate;
            if (existing.mode != Mode.PRESENTATION) existing.mode = Mode.DIRECT;
            return existing;
        }
        return new TrackingAdCallback(placement, format, adUnitId, delegate);
    }

    /**
     * Compatibility for raw splash loaders whose dispatch boundaries have not yet migrated.
     * Select the mode before starting the load; a callback instance represents one load lifecycle.
     * These paths retain their legacy splash callback reporting without manufacturing a request.
     */
    public static TrackingAdCallback legacySplash(String placement, AdFormat format, String adUnitId,
                                                  @Nullable AdCallback delegate) {
        TrackingAdCallback tracked = delegate instanceof TrackingAdCallback
                ? (TrackingAdCallback) delegate
                : new TrackingAdCallback(placement, format, adUnitId, delegate);
        if (tracked.mode != Mode.PRESENTATION) tracked.mode = Mode.LEGACY_SPLASH;
        return tracked;
    }

    /** The waterfall already owns request/load terminals; retain the winning ad's presentation events. */
    public static TrackingAdCallback presentationOnly(AdLoadContext context, String adUnitId,
                                                       @Nullable AdCallback delegate) {
        TrackingAdCallback tracked = delegate instanceof TrackingAdCallback
                ? (TrackingAdCallback) delegate
                : new TrackingAdCallback(context.getPlacement(), context.getFormat(), adUnitId,
                        delegate, Mode.PRESENTATION);
        tracked.mode = Mode.PRESENTATION;
        // A waterfall can select a later tier on the same callback. Presentation belongs to
        // that winning unit; the callback's own immutable load context is left untouched.
        tracked.presentationContext = context;
        tracked.presentationUnitId = adUnitId;
        PlacementRegistry.register(adUnitId, context.getPlacement());
        AdFormatRegistry.register(adUnitId, context.getFormat());
        return tracked;
    }

    private TrackingAdCallback(String placement, AdFormat format, String adUnitId,
                              @Nullable AdCallback delegate, Mode mode) {
        this.placement = placement == null || placement.isEmpty() ? "unknown" : placement;
        this.format = format == null ? AdFormat.UNKNOWN : format;
        this.adUnitId = adUnitId;
        this.delegate = delegate;
        this.requestedAtMs = System.currentTimeMillis();
        this.mode = mode;
        this.loadAttempt = new AdLoadAttempt(new AdLoadContext(this.placement, this.format));
        this.presentationContext = loadAttempt.getContext();
        this.presentationUnitId = adUnitId;
        PlacementRegistry.register(adUnitId, this.placement);
        AdFormatRegistry.register(adUnitId, this.format);
    }

    // -----------------------------------------------------------------------
    // Load
    // -----------------------------------------------------------------------

    @Override
    public boolean canAcceptLoadedAd() {
        return delegate == null || delegate.canAcceptLoadedAd();
    }

    @Override
    public void onAdRequestStarted(String adUnitId) {
        if (mode == Mode.DIRECT) {
            if (!requestStarted) {
                requestStarted = true;
                requestStartedAtMs = SystemClock.elapsedRealtime();
            }
            requestUnitId = adUnitId;
            presentationUnitId = adUnitId;
            loadAttempt.onRequestStarted(adUnitId);
        }
        if (delegate != null) delegate.onAdRequestStarted(adUnitId);
    }

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
        if (interstitialAd != null) reportLoaded();
        else reportLoadFailed(null);
        if (delegate != null) delegate.onInterstitialLoad(interstitialAd);
    }

    @Override
    public void onApInterstitialLoad(@Nullable ApInterstitialAd apInterstitialAd) {
        if (apInterstitialAd != null && apInterstitialAd.isReady()) reportLoaded();
        else reportLoadFailed(null);
        if (delegate != null) delegate.onApInterstitialLoad(apInterstitialAd);
    }

    @Override
    public void onRewardAdLoaded(RewardedAd rewardedAd) {
        if (rewardedAd != null) reportLoaded();
        else reportLoadFailed(null);
        if (delegate != null) delegate.onRewardAdLoaded(rewardedAd);
    }

    @Override
    public void onRewardAdLoaded(RewardedInterstitialAd rewardedAd) {
        if (rewardedAd != null) reportLoaded();
        else reportLoadFailed(null);
        if (delegate != null) delegate.onRewardAdLoaded(rewardedAd);
    }

    @Override
    public void onUnifiedNativeAdLoaded(@NonNull NativeAd unifiedNativeAd) {
        if (unifiedNativeAd != null) reportLoaded();
        else reportLoadFailed(null);
        if (delegate != null) delegate.onUnifiedNativeAdLoaded(unifiedNativeAd);
    }

    @Override
    public void onNativeAdLoaded(@NonNull ApNativeAd nativeAd) {
        if (nativeAd != null && (nativeAd.getAdmobNativeAd() != null || nativeAd.getNativeView() != null)) {
            reportLoaded();
        } else reportLoadFailed(null);
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
            Tracker.track(new TrackkitEvents.Ad.Closed(presentationContext.getPlacement(),
                    presentationContext.getFormat(), presentationUnitId));
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
        if (mode == Mode.PRESENTATION) return;
        if (mode == Mode.DIRECT) {
            reportTier("loaded", null);
            if (canAcceptLoadedAd()) loadAttempt.onLoaded(requestUnitId);
            else loadAttempt.onFailed(null);
            return;
        }
        if (loadedReported.compareAndSet(false, true)) {
            Tracker.track(new TrackkitEvents.Ad.Loaded(
                    placement, format, adUnitId, System.currentTimeMillis() - requestedAtMs));
        }
    }

    private void reportLoadFailed(@Nullable Integer errorCode) {
        if (mode == Mode.PRESENTATION) return;
        if (mode == Mode.DIRECT) {
            reportTier("load_failed", errorCode);
            loadAttempt.onFailed(errorCode);
            return;
        }
        if (loadFailedReported.compareAndSet(false, true)) {
            Tracker.track(new TrackkitEvents.Ad.LoadFailed(placement, format, adUnitId, errorCode));
        }
    }

    private void reportTier(String outcome, @Nullable Integer errorCode) {
        if (requestStarted) {
            loadAttempt.onTierResult(1, requestUnitId, outcome, errorCode,
                    Math.max(0L, SystemClock.elapsedRealtime() - requestStartedAtMs));
        }
    }

    private void reportShown() {
        if (shownReported.compareAndSet(false, true)) {
            Tracker.track(new TrackkitEvents.Ad.Show(presentationContext.getPlacement(),
                    presentationContext.getFormat(), presentationUnitId));
        }
    }

    private void reportShowFailed(@Nullable AdError adError) {
        if (showFailedReported.compareAndSet(false, true)) {
            Tracker.track(new TrackkitEvents.Ad.ShowFailed(
                    presentationContext.getPlacement(), presentationContext.getFormat(),
                    presentationUnitId, adError == null ? null : adError.getCode()));
        }
    }
}
