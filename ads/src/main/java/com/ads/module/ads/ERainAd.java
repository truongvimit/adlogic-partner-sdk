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
import com.ads.module.ads.wrapper.ApNativeAd;
import com.ads.module.R;
import com.ads.module.config.ERainAdConfig;
import com.ads.module.engine.BannerEngine;
import com.ads.module.engine.InterstitialEngine;
import com.ads.module.engine.NativeEngine;
import com.ads.module.event.AdjustInstallReferrer;
import com.ads.module.event.ERainAdjust;
import com.ads.module.event.MmpTracking;
import com.ads.module.funtion.AdCallback;
import com.ads.module.funtion.RewardCallback;
import com.ads.module.helper.banner.BannerType;
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
     * Shared AutoBuffer interval in seconds, counted from activation, dismissal or final load
     * failure. It gates both preload and show; {@code 0} disables the time interval.
     *
     * <p>Read by the AutoBuffer placement group for preload and show eligibility. Remote
     * changes reschedule its next check; splash and other non-group placements are exempt.
     */
    public void setIntervalInterstitialAd(int intervalSeconds) {
        if (adConfig != null) {
            adConfig.setIntervalInterstitialAd(intervalSeconds);
            com.ads.module.helper.interstitial.InterstitialAutoBuffer.onGateChanged();
        }
    }

    /**
     * Process-wide default for when an interstitial's {@code onNextAction} fires.
     *
     * <p>{@code false} — the default — fires it on dismissal: the next screen starts on an empty
     * stage. {@code true} fires it as the ad reaches the screen, so the next screen inflates and
     * binds underneath it and is already painted when the ad closes.
     *
     * <p>Set it once, from {@code Application.onCreate}: it changes what a callback <em>means</em>,
     * and a screen that toggles it leaves the process in whatever state it died in. A single
     * presentation that needs the other timing asks for it by name instead — see
     * {@code InterstitialAdManager.show(..., InterNextAction)}, which this is only the default for.
     */
    public void setOpenActivityAfterShowInterAds(boolean openActivityAfterShowInterAds) {
        Admob.getInstance().setOpenActivityAfterShowInterAds(openActivityAfterShowInterAds);
    }

    public boolean isOpenActivityAfterShowInterAds() {
        return Admob.getInstance().isOpenActivityAfterShowInterAds();
    }

    public void init(Application context, ERainAdConfig adConfig) {
        com.ads.module.config.settings.AdBehavior.initialize(context);
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
        // A partner may supply ClientToken only in the manifest. Never overwrite that real
        // value (possibly already loaded by FacebookInitProvider) with our legacy placeholder.
        String facebookToken = adConfig.getFacebookClientToken();
        if (!TextUtils.isEmpty(facebookToken)
                && !facebookToken.trim().isEmpty()
                && !ERainAdConfig.DEFAULT_TOKEN_FACEBOOK_SDK.equals(facebookToken)) {
            FacebookSdk.setClientToken(facebookToken);
        }
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

    public void loadBanner(Activity mActivity, String id, AdCallback adCallback) {
        BannerEngine.INSTANCE.load(mActivity, id, mActivity.findViewById(R.id.banner_container),
                mActivity.findViewById(R.id.shimmer_container_banner), BannerType.Normal.INSTANCE, adCallback);
    }

    public void loadCollapsibleBanner(Activity activity, String id, String gravity, AdCallback adCallback) {
        Admob.getInstance().loadCollapsibleBanner(activity, id, gravity,
                instrument(id, AdFormat.COLLAPSIBLE_BANNER, adCallback));
    }

    public void loadCollapsibleBannerFragment(Activity activity, String id, View rootView, String gravity, AdCallback adCallback) {
        Admob.getInstance().loadCollapsibleBannerFragment(activity, id, rootView, gravity,
                instrument(id, AdFormat.COLLAPSIBLE_BANNER, adCallback));
    }

    public void loadBannerFragment(Activity mActivity, String id, View rootView, AdCallback adCallback) {
        Admob.getInstance().loadBannerFragment(mActivity, id, rootView, instrument(id, AdFormat.BANNER, adCallback));
    }

    public void loadInlineBanner(Activity mActivity, String idBanner, String inlineStyle, AdCallback adCallback) {
        Admob.getInstance().loadInlineBanner(mActivity, idBanner, inlineStyle,
                instrument(idBanner, AdFormat.BANNER, adCallback));
    }

    public void loadBannerInlineFragment(Activity mActivity, String idBanner, View rootView, String inlineStyle, AdCallback adCallback) {
        Admob.getInstance().loadInlineBannerFragment(mActivity, idBanner, rootView, inlineStyle,
                instrument(idBanner, AdFormat.BANNER, adCallback));
    }

    public void loadInlineBanner(Activity mActivity, String idBanner, int maxHeightDp, AdCallback adCallback) {
        Admob.getInstance().loadInlineBanner(mActivity, idBanner, maxHeightDp,
                instrument(idBanner, AdFormat.BANNER, adCallback));
    }

    public void loadBannerInlineFragment(Activity mActivity, String idBanner, View rootView, int maxHeightDp, AdCallback adCallback) {
        Admob.getInstance().loadInlineBannerFragment(mActivity, idBanner, rootView, maxHeightDp,
                instrument(idBanner, AdFormat.BANNER, adCallback));
    }

    public void loadLargeAnchoredBanner(Activity mActivity, String id, AdCallback adCallback) {
        Admob.getInstance().loadLargeAnchoredBanner(mActivity, id, instrument(id, AdFormat.BANNER, adCallback));
    }

    public void loadLargeAnchoredBannerFragment(Activity mActivity, String id, View rootView, AdCallback adCallback) {
        Admob.getInstance().loadLargeAnchoredBannerFragment(mActivity, id, rootView,
                instrument(id, AdFormat.BANNER, adCallback));
    }

    public void loadFixedSizeBanner(Activity mActivity, String id, AdSize adSize, AdCallback adCallback) {
        Admob.getInstance().loadFixedSizeBanner(mActivity, id, adSize, instrument(id, AdFormat.BANNER, adCallback));
    }

    public void loadFixedSizeBannerFragment(Activity mActivity, String id, View rootView, AdSize adSize, AdCallback adCallback) {
        Admob.getInstance().loadFixedSizeBannerFragment(mActivity, id, rootView, adSize,
                instrument(id, AdFormat.BANNER, adCallback));
    }

    public ApInterstitialAd getInterstitialAds(Context context, String id, AdCallback adListener) {
        ApInterstitialAd apInterstitialAd = new ApInterstitialAd();
        InterstitialEngine.INSTANCE.load(context, id, new AdCallback() {
            @Override
            public void onApInterstitialLoad(@Nullable ApInterstitialAd loaded) {
                apInterstitialAd.setInterstitialAd(loaded == null ? null : loaded.getInterstitialAd());
                adListener.onApInterstitialLoad(apInterstitialAd);
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                Log.d(TAG, "Admob onAdFailedToLoad");
                adListener.onAdFailedToLoad(i);
            }

            @Override
            public void onAdFailedToShow(@Nullable AdError adError) {
                Log.d(TAG, "Admob onAdFailedToShow");
                adListener.onAdFailedToShow(adError);
            }
        });
        return apInterstitialAd;
    }

    public void forceShowInterstitial(@NonNull Context context, ApInterstitialAd mInterstitialAd,
                                      @NonNull final AdCallback callback, boolean shouldReloadAds,
                                      boolean openNextUnderAd) {
        InterstitialEngine.INSTANCE.show(context, mInterstitialAd, callback, openNextUnderAd);
    }

    public void loadNativeAdResultCallback(final Context activity, String id,
                                           int layoutCustomNative, AdCallback callback) {
        NativeEngine.INSTANCE.load(activity, id, layoutCustomNative, callback);
    }

    public void initRewardAds(Context context, String id, AdCallback callback) {
        Admob.getInstance().initRewardAds(context, id, instrument(id, AdFormat.REWARDED, callback));
    }

    public void showRewardAds(Activity context, RewardedAd rewardedAd, RewardCallback adCallback) {
        Admob.getInstance().showRewardAds(context, rewardedAd, adCallback);
    }

}
