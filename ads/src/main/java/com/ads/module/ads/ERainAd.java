package com.ads.module.ads;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.adjust.sdk.Adjust;
import com.adjust.sdk.AdjustConfig;
import com.adjust.sdk.LogLevel;
import com.ads.module.admob.Admob;
import com.ads.module.admob.AppOpenManager;
import com.ads.module.ads.wrapper.ApInterstitialAd;
import com.ads.module.ads.wrapper.ApInterstitialPriorityAd;
import com.ads.module.ads.wrapper.ApNativeAd;
import com.ads.module.config.ERainAdConfig;
import com.ads.module.event.AdjustInstallReferrer;
import com.ads.module.event.ERainAdjust;
import com.ads.module.event.MmpTracking;
import com.ads.module.funtion.AdCallback;
import com.ads.module.funtion.RewardCallback;
import com.ads.module.tracking.TrackingAdCallback;
import com.ads.module.util.AppUtil;
import com.ads.module.util.SharePreferenceUtils;
import com.facebook.FacebookSdk;
import com.facebook.shimmer.ShimmerFrameLayout;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdView;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd;

import io.trackkit.AdFormat;
import io.trackkit.PlacementRegistry;

public class ERainAd {
    public static final String TAG_ADJUST = "ERainAdjust";
    public static final String TAG = "JscAd";
    private static volatile ERainAd INSTANCE;
    private ERainAdConfig adConfig;

    public static synchronized ERainAd getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ERainAd();
        }
        return INSTANCE;
    }

    public ERainAdConfig getAdConfig() {
        return adConfig;
    }

    /**
     * Whether Adjust attributed this install to no campaign.
     *
     * <p>Defaults to {@code true} until attribution lands, so the {@code isForceOrganic} placements
     * stay hidden for a paid user's very first session rather than being shown to an organic one.
     * Returns {@code true} before {@link #init} too — reading it that early is a call-order mistake,
     * not a reason to crash the app.
     */
    public Boolean getOrganic() {
        return adConfig == null || SharePreferenceUtils.getIsOrganic(adConfig.getApplication());
    }

    /**
     * The single UA gate: when a placement is marked force-organic, it shows only to paid
     * (non-organic) installs; otherwise always. Placements are named by the caller (see
     * {@code com.ads.module.helper.AdGate#passesUaGate}) — the SDK deliberately has no
     * per-placement variants, so adding a placement never needs an SDK release.
     */
    public Boolean shouldDisplayForUa(boolean isForceOrganic) {
        return !isForceOrganic || !getOrganic();
    }

    public void setCountClickToShowAds(int countClickToShowAds) {
        Admob.getInstance().setNumToShowAds(countClickToShowAds);
    }

    public void setCountClickToShowAds(int countClickToShowAds, int currentClicked) {
        Admob.getInstance().setNumToShowAds(countClickToShowAds, currentClicked);
    }

    /**
     * Interstitial clicks allowed per ad unit per 24h before that unit stops loading and showing.
     * {@code 0} — the default — disables the cap.
     *
     * <p>Drive it from remote config so UA can turn it on, retune it, or switch it off without a
     * release; a cap baked into the binary can only be undone by shipping a new build.
     */
    public void setMaxClickAdsPerDay(int maxClickAdsPerDay) {
        Admob.getInstance().setMaxClickAdsPerDay(maxClickAdsPerDay);
    }

    /**
     * Minimum gap, in seconds, between two interstitial impressions. {@code 0} — the default —
     * disables the rule.
     *
     * <p>Read on every show, so remote config can retune it mid-session. This module owns the rule
     * because it owns the impression timestamp both it and any caller would have to read.
     */
    public void setIntervalInterstitialAd(int intervalSeconds) {
        if (adConfig != null) {
            adConfig.setIntervalInterstitialAd(intervalSeconds);
        }
    }

    public void init(Application context, ERainAdConfig adConfig) {
        if (adConfig == null) {
            throw new RuntimeException("Cant not set ERainAdConfig null");
        }
        this.adConfig = adConfig;
        AppUtil.VARIANT_DEV = adConfig.isVariantDev();
        // Arm the Adjust relay before any purchase can fire: :billingkit reports revenue through
        // the Trackkit seam and never touches this module's classes itself.
        MmpTracking.ensureInstalled();
        if (adConfig.isEnableAdjust()) {
            setupAdjust(adConfig.isVariantDev(), adConfig.getAdjustConfig());
        }

        Admob.getInstance().init(context, adConfig.getListDeviceTest());
        // Always attach the lifecycle hooks — the resume unit usually arrives later from remote
        // config. AppOpenManager skips requests until it has an id.
        AppOpenManager.getInstance().init(adConfig.getApplication(), adConfig.getIdAdResume());
        // The placeholder keeps FacebookSdk.sdkInitialize from crashing on a missing token, but
        // every Graph/App Events request made with it fails server-side — say so once, loudly.
        if (ERainAdConfig.DEFAULT_TOKEN_FACEBOOK_SDK.equals(adConfig.getFacebookClientToken())) {
            Log.e(TAG, "facebookClientToken is not set — Facebook SDK is running on the "
                    + "placeholder token and every Facebook request will fail. "
                    + "Set ERainAdConfig.facebookClientToken.");
        }
        FacebookSdk.setClientToken(adConfig.getFacebookClientToken());
        FacebookSdk.sdkInitialize(context);
    }

    /**
     * Brings up the Adjust SDK, or refuses to and says why.
     *
     * <p>Nothing downstream is armed until {@code Adjust.initSdk} has actually run against a valid
     * config: {@link ERainAdjust#markInitialized()} is the last statement, so a missing app token
     * leaves the integration off instead of firing every event at an uninitialised SDK.
     */
    private void setupAdjust(Boolean buildDebug, com.ads.module.config.AdjustConfig adjustConfig) {
        Application application = adConfig.getApplication();
        String adjustToken = adjustConfig.getAdjustToken();
        if (TextUtils.isEmpty(adjustToken)) {
            Log.e(TAG_ADJUST, "adjustConfig.enableAdjust is true but adjustToken is empty — "
                    + "Adjust stays off. Set the app token from the Adjust dashboard.");
            return;
        }

        String environment = buildDebug ? AdjustConfig.ENVIRONMENT_SANDBOX : AdjustConfig.ENVIRONMENT_PRODUCTION;
        AdjustConfig config = new AdjustConfig(application, adjustToken, environment);

        // VERBOSE prints the app token and the whole attribution payload on every session. That is
        // what you want in QA and a logcat leak in production, so it follows the build variant.
        config.setLogLevel(buildDebug ? LogLevel.VERBOSE : LogLevel.WARN);
        config.enablePreinstallTracking();
        config.enableSendingInBackground();
        // Adjust cannot forward anything to Meta without the app id; the field is optional because
        // not every partner runs Meta campaigns.
        if (!TextUtils.isEmpty(adjustConfig.getFbAppId())) {
            config.setFbAppId(adjustConfig.getFbAppId());
        }

        config.setOnAttributionChangedListener(adjustAttribution -> {
            boolean organic = "Organic".equals(adjustAttribution.trackerName) ||
                    (adjustAttribution.network != null && adjustAttribution.network.equalsIgnoreCase("organic"));
            SharePreferenceUtils.setIsOrganic(application, organic);
            Log.i(TAG_ADJUST, "attribution: network=" + adjustAttribution.network
                    + " campaign=" + adjustAttribution.campaign + " organic=" + organic);
        });
        // Failure callbacks in every build, success callbacks only in dev: a rejected token is
        // invisible otherwise — the client-side call succeeds and the event dies on Adjust's side.
        config.setOnEventTrackingFailedListener(failure ->
                Log.e(TAG_ADJUST, "event rejected: " + failure));
        config.setOnSessionTrackingFailedListener(failure ->
                Log.e(TAG_ADJUST, "session rejected: " + failure));
        if (buildDebug) {
            config.setOnEventTrackingSucceededListener(success ->
                    Log.d(TAG_ADJUST, "event ok: " + success));
            config.setOnSessionTrackingSucceededListener(success ->
                    Log.d(TAG_ADJUST, "session ok: " + success));
        }

        if (!config.isValid()) {
            Log.e(TAG_ADJUST, "AdjustConfig rejected (token/environment/context) — Adjust stays off");
            return;
        }
        // No ActivityLifecycleCallbacks relaying onResume/onPause: that was Adjust v4 boilerplate.
        // v5 registers its own lifecycle observer inside initSdk and tracks sessions itself.
        Adjust.initSdk(config);
        ERainAdjust.markInitialized();
        // Adjust owns the Play referrer fetch; mirror it into analytics once per install
        AdjustInstallReferrer.readOnce(application);
        Log.i(TAG_ADJUST, "Adjust initialised (" + environment + ")");
    }

    // -----------------------------------------------------------------------
    // Instrumentation
    //
    // Attached here, not by the host app: this is the layer that creates the ad object, the same
    // rule AdMob applies to setOnPaidEventListener. Callers keep passing their own AdCallback and
    // never see the decorator — like an OkHttp interceptor installed once at the composition root.
    // -----------------------------------------------------------------------

    /**
     * Wraps {@code callback} so the whole lifecycle of this ad unit reaches Trackkit.
     */
    private AdCallback instrument(String adUnitId, AdFormat format, AdCallback callback) {
        // Idempotent: a partner may still hand us a pre-wrapped callback via the deprecated
        // AdTracking.wrap, and nesting two decorators would double every event.
        if (callback instanceof TrackingAdCallback) {
            return callback;
        }
        return new TrackingAdCallback(PlacementRegistry.placementOf(adUnitId), format, adUnitId, callback);
    }

    /**
     * Tiered variant: the tiers are one placement shown once, so they resolve to whichever tier the
     * app registered and the rest are bound to it. Without this only the registered tier's revenue
     * could be attributed to a screen.
     */
    private AdCallback instrumentTiered(AdFormat format, AdCallback callback, String... adUnitIds) {
        if (callback instanceof TrackingAdCallback) {
            return callback;
        }
        String placement = "unknown";
        for (String id : adUnitIds) {
            String resolved = PlacementRegistry.placementOf(id, "");
            if (!resolved.isEmpty()) {
                placement = resolved;
                break;
            }
        }
        for (String id : adUnitIds) {
            PlacementRegistry.register(id, placement);
        }
        return new TrackingAdCallback(placement, format, adUnitIds[0], callback);
    }

    public void loadBanner(Activity mActivity, String id) {
        Admob.getInstance().loadBanner(mActivity, id, instrument(id, AdFormat.BANNER, null));
    }

    public void loadBanner(Activity mActivity, String id, AdCallback adCallback) {
        Admob.getInstance().loadBanner(mActivity, id, instrument(id, AdFormat.BANNER, adCallback));
    }

    public void loadCollapsibleBanner(Activity activity, String id, String gravity, AdCallback adCallback) {
        Admob.getInstance().loadCollapsibleBanner(activity, id, gravity,
                instrument(id, AdFormat.COLLAPSIBLE_BANNER, adCallback));
    }

    public void loadCollapsibleBannerFragment(Activity activity, String id, View rootView, String gravity, AdCallback adCallback) {
        Admob.getInstance().loadCollapsibleBannerFragment(activity, id, rootView, gravity,
                instrument(id, AdFormat.COLLAPSIBLE_BANNER, adCallback));
    }

    public void loadCollapsibleBannerSizeMedium(Activity activity, String id, String gravity, AdSize sizeBanner, AdCallback adCallback) {
        Admob.getInstance().loadCollapsibleBannerSizeMedium(activity, id, gravity, sizeBanner,
                instrument(id, AdFormat.COLLAPSIBLE_BANNER, adCallback));
    }

    public void loadBannerFragment(Activity mActivity, String id, View rootView) {
        Admob.getInstance().loadBannerFragment(mActivity, id, rootView, instrument(id, AdFormat.BANNER, null));
    }

    public void loadBannerFragment(Activity mActivity, String id, View rootView, AdCallback adCallback) {
        Admob.getInstance().loadBannerFragment(mActivity, id, rootView, instrument(id, AdFormat.BANNER, adCallback));
    }

    public void loadInlineBanner(Activity mActivity, String idBanner, String inlineStyle) {
        Admob.getInstance().loadInlineBanner(mActivity, idBanner, inlineStyle,
                instrument(idBanner, AdFormat.BANNER, null));
    }

    public void loadInlineBanner(Activity mActivity, String idBanner, String inlineStyle, AdCallback adCallback) {
        Admob.getInstance().loadInlineBanner(mActivity, idBanner, inlineStyle,
                instrument(idBanner, AdFormat.BANNER, adCallback));
    }

    public void loadBannerInlineFragment(Activity mActivity, String idBanner, View rootView, String inlineStyle) {
        Admob.getInstance().loadInlineBannerFragment(mActivity, idBanner, rootView, inlineStyle,
                instrument(idBanner, AdFormat.BANNER, null));
    }

    public void loadBannerInlineFragment(Activity mActivity, String idBanner, View rootView, String inlineStyle, AdCallback adCallback) {
        Admob.getInstance().loadInlineBannerFragment(mActivity, idBanner, rootView, inlineStyle,
                instrument(idBanner, AdFormat.BANNER, adCallback));
    }

    public void loadSplashInterstitialAds(Context context, String id, long timeOut, long timeDelay, AdCallback adListener) {
        Admob.getInstance().loadSplashInterstitialAds(context, id, timeOut, timeDelay, true,
                instrument(id, AdFormat.INTERSTITIAL, adListener));
    }

    public void onCheckShowSplashWhenFail(AppCompatActivity activity, AdCallback callback, int timeDelay) {
        Admob.getInstance().onCheckShowSplashWhenFail(activity, callback, timeDelay);
    }

    public ApInterstitialAd getInterstitialAds(Context context, String id, AdCallback adListener) {
        ApInterstitialAd apInterstitialAd = new ApInterstitialAd();
        Admob.getInstance().getInterstitialAds(context, id, instrument(id, AdFormat.INTERSTITIAL, new AdCallback() {
            @Override
            public void onInterstitialLoad(@Nullable InterstitialAd interstitialAd) {
                super.onInterstitialLoad(interstitialAd);
                apInterstitialAd.setInterstitialAd(interstitialAd);
                adListener.onApInterstitialLoad(apInterstitialAd);
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                Log.d(TAG, "Admob onAdFailedToLoad");
                adListener.onAdFailedToLoad(i);
            }

            @Override
            public void onAdFailedToShow(@Nullable AdError adError) {
                super.onAdFailedToShow(adError);
                Log.d(TAG, "Admob onAdFailedToShow");
                adListener.onAdFailedToShow(adError);
            }

        }));
        return apInterstitialAd;
    }

    public void forceShowInterstitial(@NonNull Context context, ApInterstitialAd mInterstitialAd,
                                      @NonNull final AdCallback callback, boolean shouldReloadAds) {
        if (System.currentTimeMillis() - SharePreferenceUtils.getLastImpressionInterstitialTime(context)
                < ERainAd.getInstance().adConfig.getIntervalInterstitialAd() * 1000L
        ) {
            callback.onNextAction();
            return;
        }
        if (mInterstitialAd == null || mInterstitialAd.isNotReady()) {
            callback.onNextAction();
            return;
        }
        // Captured while the ad is still held: the reload paths below used to dereference it after
        // it could already have been cleared.
        InterstitialAd shownAd = mInterstitialAd.getInterstitialAd();
        final String adUnitId = shownAd == null ? "" : shownAd.getAdUnitId();
        AdCallback adCallback = new AdCallback() {
            @Override
            public void onAdClosed() {
                super.onAdClosed();
                callback.onAdClosed();
                if (shouldReloadAds) {
                    Admob.getInstance().getInterstitialAds(context, adUnitId, instrument(adUnitId, AdFormat.INTERSTITIAL, new AdCallback() {
                        @Override
                        public void onInterstitialLoad(@Nullable InterstitialAd interstitialAd) {
                            super.onInterstitialLoad(interstitialAd);
                            mInterstitialAd.setInterstitialAd(interstitialAd);
                            callback.onInterstitialLoad(mInterstitialAd.getInterstitialAd());
                        }

                        @Override
                        public void onAdFailedToLoad(@Nullable LoadAdError i) {
                            super.onAdFailedToLoad(i);
                            mInterstitialAd.setInterstitialAd(null);
                            callback.onAdFailedToLoad(i);
                        }

                        @Override
                        public void onAdFailedToShow(@Nullable AdError adError) {
                            super.onAdFailedToShow(adError);
                            callback.onAdFailedToShow(adError);
                        }

                    }));
                } else {
                    mInterstitialAd.setInterstitialAd(null);
                }
            }

            @Override
            public void onNextAction() {
                super.onNextAction();
                callback.onNextAction();
            }

            @Override
            public void onAdFailedToShow(@Nullable AdError adError) {
                super.onAdFailedToShow(adError);
                callback.onAdFailedToShow(adError);
                if (shouldReloadAds) {
                    Admob.getInstance().getInterstitialAds(context, adUnitId, instrument(adUnitId, AdFormat.INTERSTITIAL, new AdCallback() {
                        @Override
                        public void onInterstitialLoad(@Nullable InterstitialAd interstitialAd) {
                            super.onInterstitialLoad(interstitialAd);
                            mInterstitialAd.setInterstitialAd(interstitialAd);
                            callback.onInterstitialLoad(mInterstitialAd.getInterstitialAd());
                        }

                        @Override
                        public void onAdFailedToLoad(@Nullable LoadAdError i) {
                            super.onAdFailedToLoad(i);
                            callback.onAdFailedToLoad(i);
                        }

                        @Override
                        public void onAdFailedToShow(@Nullable AdError adError) {
                            super.onAdFailedToShow(adError);
                            callback.onAdFailedToShow(adError);
                        }

                    }));
                } else {
                    mInterstitialAd.setInterstitialAd(null);
                }
            }

            @Override
            public void onAdClicked() {
                super.onAdClicked();
                callback.onAdClicked();
            }

            @Override
            public void onInterstitialShow() {
                super.onInterstitialShow();
                callback.onInterstitialShow();
            }
        };
        Admob.getInstance().forceShowInterstitial(context, shownAd,
                instrument(adUnitId, AdFormat.INTERSTITIAL, adCallback));
    }

    public void loadNativeAdResultCallback(final Activity activity, String id,
                                           int layoutCustomNative, AdCallback callback) {
        Admob.getInstance().loadNativeAd(((Context) activity), id, instrument(id, AdFormat.NATIVE, new AdCallback() {
            @Override
            public void onUnifiedNativeAdLoaded(@NonNull NativeAd unifiedNativeAd) {
                super.onUnifiedNativeAdLoaded(unifiedNativeAd);
                callback.onNativeAdLoaded(new ApNativeAd(layoutCustomNative, unifiedNativeAd));
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                callback.onAdFailedToLoad(i);
            }

            @Override
            public void onAdFailedToShow(@Nullable AdError adError) {
                super.onAdFailedToShow(adError);
                callback.onAdFailedToShow(adError);
            }

            @Override
            public void onAdClicked() {
                super.onAdClicked();
                callback.onAdClicked();
            }

            @Override
            public void onAdImpression() {
                super.onAdImpression();
                callback.onAdImpression();
            }
        }));
    }

    public void loadNativeAd(final Activity activity, String id,
                             int layoutCustomNative, FrameLayout adPlaceHolder, ShimmerFrameLayout
                                     containerShimmerLoading, AdCallback callback) {
        Admob.getInstance().loadNativeAd(((Context) activity), id, instrument(id, AdFormat.NATIVE, new AdCallback() {
            @Override
            public void onUnifiedNativeAdLoaded(@NonNull NativeAd unifiedNativeAd) {
                super.onUnifiedNativeAdLoaded(unifiedNativeAd);
                callback.onNativeAdLoaded(new ApNativeAd(layoutCustomNative, unifiedNativeAd));
                populateNativeAdView(activity, new ApNativeAd(layoutCustomNative, unifiedNativeAd), adPlaceHolder, containerShimmerLoading);
            }

            @Override
            public void onAdImpression() {
                super.onAdImpression();
                callback.onAdImpression();
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                callback.onAdFailedToLoad(i);
            }

            @Override
            public void onAdFailedToShow(@Nullable AdError adError) {
                super.onAdFailedToShow(adError);
                callback.onAdFailedToShow(adError);
            }

            @Override
            public void onAdClicked() {
                super.onAdClicked();
                callback.onAdClicked();
            }
        }));
    }

    public void populateNativeAdView(Activity activity, ApNativeAd apNativeAd, FrameLayout adPlaceHolder, ShimmerFrameLayout containerShimmerLoading) {
        if (apNativeAd.getAdmobNativeAd() == null && apNativeAd.getNativeView() == null) {
            containerShimmerLoading.setVisibility(View.GONE);
            return;
        }
        @SuppressLint("InflateParams") NativeAdView adView = (NativeAdView) LayoutInflater.from(activity).inflate(apNativeAd.getLayoutCustomNative(), null);
        containerShimmerLoading.stopShimmer();
        containerShimmerLoading.setVisibility(View.GONE);
        adPlaceHolder.setVisibility(View.VISIBLE);
        Admob.getInstance().populateUnifiedNativeAdView(apNativeAd.getAdmobNativeAd(), adView);
        adPlaceHolder.removeAllViews();
        adPlaceHolder.addView(adView);
    }

    public void initRewardAds(Context context, String id) {
        Admob.getInstance().initRewardAds(context, id, instrument(id, AdFormat.REWARDED, null));
    }

    public void initRewardAds(Context context, String id, AdCallback callback) {
        Admob.getInstance().initRewardAds(context, id, instrument(id, AdFormat.REWARDED, callback));
    }

    public void getRewardInterstitial(Context context, String id, AdCallback callback) {
        Admob.getInstance().getRewardInterstitial(context, id,
                instrument(id, AdFormat.REWARDED_INTERSTITIAL, callback));
    }

    public void showRewardInterstitial(Activity activity, RewardedInterstitialAd rewardedInterstitialAd, RewardCallback adCallback) {
        Admob.getInstance().showRewardInterstitial(activity, rewardedInterstitialAd, adCallback);
    }

    public void showRewardAds(Activity context, RewardCallback adCallback) {
        Admob.getInstance().showRewardAds(context, adCallback);
    }

    public void showRewardAds(Activity context, RewardedAd rewardedAd, RewardCallback adCallback) {
        Admob.getInstance().showRewardAds(context, rewardedAd, adCallback);
    }

    public void loadInterSplashPriority4SameTime(final Context context,
                                                 String idAdsHigh1,
                                                 String idAdsHigh2,
                                                 String idAdsHigh3,
                                                 String idAdsNormal,
                                                 long timeOut,
                                                 long timeDelay,
                                                 AdCallback adListener) {
        Admob.getInstance().loadInterSplashPriority4SameTime(context, idAdsHigh1, idAdsHigh2, idAdsHigh3, idAdsNormal, timeOut, timeDelay,
                instrumentTiered(AdFormat.INTERSTITIAL, adListener, idAdsHigh1, idAdsHigh2, idAdsHigh3, idAdsNormal));
    }

    public void onShowSplashPriority4(AppCompatActivity activity, AdCallback adListener) {
        Admob.getInstance().onShowSplashPriority4(activity, adListener);
    }

    public void onCheckShowSplashPriority4WhenFail(AppCompatActivity activity, AdCallback callback, int timeDelay) {
        Admob.getInstance().onCheckShowSplashPriority4WhenFail(activity, callback, timeDelay);
    }

    private boolean isFinishLoadNativeAdHigh1 = false;
    private boolean isFinishLoadNativeAdHigh2 = false;
    private boolean isFinishLoadNativeAdHigh3 = false;
    private boolean isFinishLoadNativeAdNormal = false;

    private ApNativeAd apNativeAdHigh2;
    private ApNativeAd apNativeAdHigh3;
    private ApNativeAd apNativeAdNormal;

    public void loadNative4SameTime(final Activity activity, String idAdHigh1, String idAdHigh2, String idAdHigh3, String idAdNormal, int layoutCustomNative, AdCallback adCallback) {
        isFinishLoadNativeAdHigh1 = false;
        isFinishLoadNativeAdHigh2 = false;
        isFinishLoadNativeAdHigh3 = false;

        apNativeAdHigh2 = null;
        apNativeAdHigh3 = null;
        apNativeAdNormal = null;

        loadNativeAdResultCallback(activity, idAdHigh1, layoutCustomNative, new AdCallback() {
            @Override
            public void onNativeAdLoaded(@NonNull ApNativeAd nativeAd) {
                super.onNativeAdLoaded(nativeAd);
                adCallback.onNativeAdLoaded(nativeAd);
            }

            @Override
            public void onAdClicked() {
                super.onAdClicked();
                adCallback.onAdClicked();
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                if (isFinishLoadNativeAdHigh2 && apNativeAdHigh2 != null) {
                    adCallback.onNativeAdLoaded(apNativeAdHigh2);
                } else if (isFinishLoadNativeAdHigh3 && apNativeAdHigh3 != null) {
                    adCallback.onNativeAdLoaded(apNativeAdHigh3);
                } else if (isFinishLoadNativeAdNormal && apNativeAdNormal != null) {
                    adCallback.onNativeAdLoaded(apNativeAdNormal);
                } else {
                    // waiting for ads loaded
                    isFinishLoadNativeAdHigh1 = true;
                }
            }
        });

        loadNativeAdResultCallback(activity, idAdHigh2, layoutCustomNative, new AdCallback() {
            @Override
            public void onNativeAdLoaded(@NonNull ApNativeAd nativeAd) {
                super.onNativeAdLoaded(nativeAd);
                if (isFinishLoadNativeAdHigh1) {
                    adCallback.onNativeAdLoaded(nativeAd);
                } else {
                    isFinishLoadNativeAdHigh2 = true;
                    apNativeAdHigh2 = nativeAd;
                }
            }

            @Override
            public void onAdClicked() {
                super.onAdClicked();
                adCallback.onAdClicked();
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                if (isFinishLoadNativeAdHigh1) {
                    if (isFinishLoadNativeAdHigh3 && apNativeAdHigh3 != null) {
                        adCallback.onNativeAdLoaded(apNativeAdHigh3);
                    } else if (isFinishLoadNativeAdNormal && apNativeAdNormal != null) {
                        adCallback.onNativeAdLoaded(apNativeAdNormal);
                    } else {
                        isFinishLoadNativeAdHigh2 = true;
                    }
                } else {
                    isFinishLoadNativeAdHigh2 = true;
                    apNativeAdHigh2 = null;
                }
            }
        });

        loadNativeAdResultCallback(activity, idAdHigh3, layoutCustomNative, new AdCallback() {
            @Override
            public void onNativeAdLoaded(@NonNull ApNativeAd nativeAd) {
                super.onNativeAdLoaded(nativeAd);
                if (isFinishLoadNativeAdHigh1 && isFinishLoadNativeAdHigh2) {
                    adCallback.onNativeAdLoaded(nativeAd);
                } else {
                    isFinishLoadNativeAdHigh3 = true;
                    apNativeAdHigh3 = nativeAd;
                }
            }

            @Override
            public void onAdClicked() {
                super.onAdClicked();
                adCallback.onAdClicked();
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                if (isFinishLoadNativeAdHigh1 && isFinishLoadNativeAdHigh2) {
                    if (isFinishLoadNativeAdNormal && apNativeAdNormal != null) {
                        adCallback.onNativeAdLoaded(apNativeAdNormal);
                    } else {
                        isFinishLoadNativeAdHigh3 = true;
                    }
                } else {
                    isFinishLoadNativeAdHigh3 = true;
                    apNativeAdHigh3 = null;
                }
            }
        });

        loadNativeAdResultCallback(activity, idAdNormal, layoutCustomNative, new AdCallback() {
            @Override
            public void onNativeAdLoaded(@NonNull ApNativeAd nativeAd) {
                super.onNativeAdLoaded(nativeAd);
                if (isFinishLoadNativeAdHigh1 && isFinishLoadNativeAdHigh2 && isFinishLoadNativeAdHigh3) {
                    adCallback.onNativeAdLoaded(nativeAd);
                } else {
                    isFinishLoadNativeAdNormal = true;
                    apNativeAdNormal = nativeAd;
                }
            }

            @Override
            public void onAdClicked() {
                super.onAdClicked();
                adCallback.onAdClicked();
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                if (isFinishLoadNativeAdHigh1 && isFinishLoadNativeAdHigh2 && isFinishLoadNativeAdHigh3) {
                    adCallback.onNativeAdLoaded(apNativeAdNormal);
                } else {
                    isFinishLoadNativeAdNormal = true;
                    apNativeAdNormal = null;
                }
            }
        });
    }

    public void loadPriorityInterstitialAds(Context context, ApInterstitialPriorityAd apInterstitialPriorityAd, AdCallback adCallback) {
        loadPriorityInterstitialAdsFromAdmob(context, apInterstitialPriorityAd, adCallback);
    }

    public void loadPriorityInterstitialAdsFromAdmob(Context context,
                                                     ApInterstitialPriorityAd apInterstitialPriorityAd,
                                                     AdCallback adCallback) {
        if (!apInterstitialPriorityAd.getHigh1PriorityId().isEmpty()
                && !apInterstitialPriorityAd.getHigh1PriorityInterstitialAd().isReady()
        ) {
            loadAdsInterHigh1Priority(context, apInterstitialPriorityAd, adCallback);
        }

        if (!apInterstitialPriorityAd.getHigh2PriorityId().isEmpty()
                && !apInterstitialPriorityAd.getHigh2PriorityInterstitialAd().isReady()
        ) {
            loadAdsInterHigh2Priority(context, apInterstitialPriorityAd, adCallback);
        }

        if (!apInterstitialPriorityAd.getHigh3PriorityId().isEmpty()
                && !apInterstitialPriorityAd.getHigh3PriorityInterstitialAd().isReady()
        ) {
            loadAdsInterHigh3Priority(context, apInterstitialPriorityAd, adCallback);
        }

        if (!apInterstitialPriorityAd.getNormalPriorityId().isEmpty()
                && !apInterstitialPriorityAd.getNormalPriorityInterstitialAd().isReady()
        ) {
            loadInterNormalPriority(context, apInterstitialPriorityAd, adCallback);
        }
    }

    private void loadAdsInterHigh1Priority(Context context, ApInterstitialPriorityAd apInterstitialPriorityAd, AdCallback adCallback) {
        String id = apInterstitialPriorityAd.getHigh1PriorityId();
        Admob.getInstance().getInterstitialAds(context, id, instrument(id, AdFormat.INTERSTITIAL, new AdCallback() {
            @Override
            public void onInterstitialLoad(@Nullable InterstitialAd interstitialAd) {
                super.onInterstitialLoad(interstitialAd);
                Log.d(TAG, "onInterstitialLoad idAdsNormalPriority");
                apInterstitialPriorityAd.getHigh1PriorityInterstitialAd().setInterstitialAd(interstitialAd);
                adCallback.onApInterstitialLoad(apInterstitialPriorityAd.getHigh1PriorityInterstitialAd());
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                Log.e(TAG, "onAdFailedToLoad: idAdsNormalPriority: " + i);
                adCallback.onAdFailedToLoad(i);
            }

            @Override
            public void onAdClicked() {
                super.onAdClicked();
                adCallback.onAdClicked();
            }

            @Override
            public void onAdImpression() {
                super.onAdImpression();
                adCallback.onAdImpression();
            }
        }));
    }

    private void loadAdsInterHigh2Priority(Context context, ApInterstitialPriorityAd apInterstitialPriorityAd, AdCallback adCallback) {
        String id = apInterstitialPriorityAd.getHigh2PriorityId();
        Admob.getInstance().getInterstitialAds(context, id, instrument(id, AdFormat.INTERSTITIAL, new AdCallback() {
            @Override
            public void onInterstitialLoad(@Nullable InterstitialAd interstitialAd) {
                super.onInterstitialLoad(interstitialAd);
                Log.d(TAG, "onInterstitialLoad idAdsNormalPriority");
                apInterstitialPriorityAd.getHigh2PriorityInterstitialAd().setInterstitialAd(interstitialAd);
                adCallback.onApInterstitialLoad(apInterstitialPriorityAd.getHigh2PriorityInterstitialAd());
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                Log.e(TAG, "onAdFailedToLoad: idAdsNormalPriority: " + i);
                adCallback.onAdFailedToLoad(i);
            }

            @Override
            public void onAdClicked() {
                super.onAdClicked();
                adCallback.onAdClicked();
            }

            @Override
            public void onAdImpression() {
                super.onAdImpression();
                adCallback.onAdImpression();
            }
        }));
    }

    private void loadAdsInterHigh3Priority(Context context, ApInterstitialPriorityAd apInterstitialPriorityAd, AdCallback adCallback) {
        String id = apInterstitialPriorityAd.getHigh3PriorityId();
        Admob.getInstance().getInterstitialAds(context, id, instrument(id, AdFormat.INTERSTITIAL, new AdCallback() {
            @Override
            public void onInterstitialLoad(@Nullable InterstitialAd interstitialAd) {
                super.onInterstitialLoad(interstitialAd);
                Log.d(TAG, "onInterstitialLoad idAdsNormalPriority");
                apInterstitialPriorityAd.getHigh3PriorityInterstitialAd().setInterstitialAd(interstitialAd);
                adCallback.onApInterstitialLoad(apInterstitialPriorityAd.getHigh3PriorityInterstitialAd());
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                Log.e(TAG, "onAdFailedToLoad: idAdsNormalPriority: " + i);
                adCallback.onAdFailedToLoad(i);
            }

            @Override
            public void onAdClicked() {
                super.onAdClicked();
                adCallback.onAdClicked();
            }

            @Override
            public void onAdImpression() {
                super.onAdImpression();
                adCallback.onAdImpression();
            }
        }));
    }

    private void loadInterNormalPriority(Context context, ApInterstitialPriorityAd apInterstitialPriorityAd, AdCallback adCallback) {
        String id = apInterstitialPriorityAd.getNormalPriorityId();
        Admob.getInstance().getInterstitialAds(context, id, instrument(id, AdFormat.INTERSTITIAL, new AdCallback() {
            @Override
            public void onInterstitialLoad(@Nullable InterstitialAd interstitialAd) {
                super.onInterstitialLoad(interstitialAd);
                Log.d(TAG, "onInterstitialLoad idAdsNormalPriority");
                apInterstitialPriorityAd.getNormalPriorityInterstitialAd().setInterstitialAd(interstitialAd);
                adCallback.onApInterstitialLoad(apInterstitialPriorityAd.getNormalPriorityInterstitialAd());
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                Log.e(TAG, "onAdFailedToLoad: idAdsNormalPriority: " + i);
                adCallback.onAdFailedToLoad(i);
            }

            @Override
            public void onAdClicked() {
                super.onAdClicked();
                adCallback.onAdClicked();
            }

            @Override
            public void onAdImpression() {
                super.onAdImpression();
                adCallback.onAdImpression();
            }
        }));
    }

    public void forceShowInterstitialPriority(Context context, ApInterstitialPriorityAd apInterstitialPriorityAd, AdCallback adCallback, boolean isReloadAds) {
        ApInterstitialAd interstitialAd;
        if (apInterstitialPriorityAd.getHigh1PriorityInterstitialAd() != null
                && apInterstitialPriorityAd.getHigh1PriorityInterstitialAd().isReady()
        ) {
            interstitialAd = apInterstitialPriorityAd.getHigh1PriorityInterstitialAd();
        } else if (apInterstitialPriorityAd.getHigh2PriorityInterstitialAd() != null
                && apInterstitialPriorityAd.getHigh2PriorityInterstitialAd().isReady()
        ) {
            interstitialAd = apInterstitialPriorityAd.getHigh2PriorityInterstitialAd();
        } else if (apInterstitialPriorityAd.getHigh3PriorityInterstitialAd() != null
                && apInterstitialPriorityAd.getHigh3PriorityInterstitialAd().isReady()
        ) {
            interstitialAd = apInterstitialPriorityAd.getHigh3PriorityInterstitialAd();
        } else if (apInterstitialPriorityAd.getNormalPriorityInterstitialAd() != null
                && apInterstitialPriorityAd.getNormalPriorityInterstitialAd().isReady()
        ) {
            interstitialAd = apInterstitialPriorityAd.getNormalPriorityInterstitialAd();
        } else {
            adCallback.onNextAction();
            if (isReloadAds) {
                loadPriorityInterstitialAds(context, apInterstitialPriorityAd, new AdCallback());
            }
            return;
        }
        forceShowInterstitial(context,
                interstitialAd,
                new AdCallback() {
                    @Override
                    public void onNextAction() {
                        super.onNextAction();
                        adCallback.onNextAction();
                    }

                    @Override
                    public void onAdClosed() {
                        super.onAdClosed();
                        interstitialAd.setInterstitialAd(null);
                        adCallback.onAdClosed();
                        if (isReloadAds) {
                            loadPriorityInterstitialAds(context, apInterstitialPriorityAd, new AdCallback());
                        }
                    }

                    @Override
                    public void onInterstitialShow() {
                        super.onInterstitialShow();
                        adCallback.onInterstitialShow();
                    }

                    @Override
                    public void onAdClicked() {
                        super.onAdClicked();
                        adCallback.onAdClicked();
                    }

                    @Override
                    public void onAdFailedToShow(@Nullable AdError adError) {
                        super.onAdFailedToShow(adError);
                        adCallback.onAdFailedToShow(adError);
                    }

                    @Override
                    public void onAdImpression() {
                        super.onAdImpression();
                        adCallback.onAdImpression();
                    }
                },
                false
        );
    }
}
