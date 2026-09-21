package com.ads.module.admob;

import com.ads.module.config.settings.AdBehavior;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RatingBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.ads.module.R;
import com.ads.module.dialog.PrepareLoadingAdsDialog;
import com.ads.module.event.ERainLogEventManager;
import com.ads.module.funtion.AdCallback;
import com.ads.module.funtion.AdType;
import com.ads.module.funtion.AdmobHelper;
import com.ads.module.funtion.RewardCallback;
import com.ads.module.helper.AdGate;
import com.ads.module.helper.AdSkipReason;
import com.ads.module.tracking.AdTracking;
import com.ads.module.tracking.TrackingAdCallback;
import com.ads.module.util.SharePreferenceUtils;
import com.facebook.shimmer.ShimmerFrameLayout;
import com.google.ads.mediation.admob.AdMobAdapter;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdLoader;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdValue;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MediaAspectRatio;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.OnPaidEventListener;
import com.google.android.gms.ads.OnUserEarnedRewardListener;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.gms.ads.VideoOptions;
import com.google.android.gms.ads.initialization.AdapterStatus;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdOptions;
import com.google.android.gms.ads.nativead.NativeAdView;
import com.google.android.gms.ads.rewarded.RewardItem;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.trackkit.AdFormat;
import io.trackkit.PlacementRegistry;

public class Admob {
    private static final String TAG = "ERainStudio";

    /** Internal pre-show lifecycle rejection in the ERainStudio error domain; not a GMA error. */
    public static final int ERROR_CODE_SHOW_IN_BACKGROUND = 9001;

    /** Only this module's rejection before vendor show permits a cached fill to be reused. */
    public static boolean isShowInBackgroundError(AdError error) {
        return error != null && error.getCode() == ERROR_CODE_SHOW_IN_BACKGROUND
                && TAG.equals(error.getDomain());
    }
    private static Admob instance;
    /**
     * Interstitial clicks allowed per ad unit per 24h before the unit stops loading and showing.
     * {@code 0} — the default — means no cap.
     *
     * <p>Off by default on purpose: the cap shipped for a long time with its counter never
     * incremented, so "no cap" is the behaviour every existing partner build actually has. Turning
     * it on is an opt-in via {@link #setMaxClickAdsPerDay(int)}, typically driven by remote config.
     */
    private volatile int maxClickAds = (int) AdBehavior.defaultNumber("interstitial.frequency.max_clicks_per_24h");
    private PrepareLoadingAdsDialog dialog;
    private boolean disableAdResumeWhenClickAds = AdBehavior.defaultBool("app_open.presentation.skip_after_ad_click");
    /**
     * Process-wide default for when an interstitial's {@code onNextAction} fires: {@code false}
     * (the default) on dismissal, {@code true} as the ad goes to the screen so the caller can
     * start its next screen underneath it.
     * <p>
     * Only a <em>default</em> — the interstitial show path takes the value as a parameter, so one
     * presentation's choice can no longer be changed by another's while it is on screen. The
     * splash paths, which have no per-show surface, read it directly.
     */
    private volatile boolean openActivityAfterShowInterAds = false;
    private Context context;

    public static final String BANNER_INLINE_SMALL_STYLE = "BANNER_INLINE_SMALL_STYLE";
    public static final String BANNER_INLINE_LARGE_STYLE = "BANNER_INLINE_LARGE_STYLE";
        private final int MAX_SMALL_INLINE_BANNER_HEIGHT = 50;


    /**
     * @param maxClickAds clicks per ad unit per 24h before interstitials from that unit stop being
     *                    loaded and shown. {@code 0} or negative disables the cap. Safe to call at
     *                    any time — remote config typically applies it once the fetch lands.
     */
    private int effectiveMaxClicks() { return (int) AdBehavior.number("interstitial.frequency.max_clicks_per_24h", maxClickAds); }

    public void setMaxClickAdsPerDay(int maxClickAds) {
        if (this.maxClickAds == maxClickAds) {
            return;
        }
        this.maxClickAds = maxClickAds;
        Log.i(TAG, maxClickAds > 0
                ? "Interstitial click cap enabled: " + maxClickAds + " clicks/ad unit/day"
                : "Interstitial click cap disabled");
    }

    /**
     * Records one ad click against the daily cap.
     *
     * <p>Called from {@code ERainLogEventManager.logClickAdsEvent}, the module's single click choke
     * point, so a newly added ad format cannot ship with its clicks uncounted. Counts are per ad
     * unit, so clicks on a native only ever gate that native's own unit.
     */
    public void recordAdClick(Context context, String adUnitId) {
        if (effectiveMaxClicks() <= 0 || context == null || adUnitId == null || adUnitId.isEmpty()) {
            return;
        }
        AdmobHelper.increaseNumClickAdsPerDay(context, adUnitId);
    }

    /**
     * True when {@code adUnitId} has burned through its daily click allowance.
     */
    private boolean isClickCapReached(Context context, String adUnitId) {
        if (effectiveMaxClicks() <= 0 || context == null || adUnitId == null || adUnitId.isEmpty()) {
            return false;
        }
        int clicks = AdmobHelper.getNumClickAdsPerDay(context, adUnitId);
        if (clicks < effectiveMaxClicks()) {
            return false;
        }
        Log.w(TAG, "Interstitial suppressed: ad unit hit the daily click cap ("
                + clicks + "/" + maxClickAds + "). Resets 24h after the window opened.");
        return true;
    }

    public static Admob getInstance() {
        if (instance == null) {
            instance = new Admob();
        }
        return instance;
    }

    private Admob() {

    }

    public void setDisableAdResumeWhenClickAds(boolean disableAdResumeWhenClickAds) {
        this.disableAdResumeWhenClickAds = disableAdResumeWhenClickAds;
    }

    public void init(Context context, List<String> testDeviceList) {
        initInternal(context);
        MobileAds.setRequestConfiguration(new RequestConfiguration.Builder().setTestDeviceIds(testDeviceList).build());
    }

    private void initInternal(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            String processName = Application.getProcessName();
            String packageName = context.getPackageName();
            if (!packageName.equals(processName)) {
                WebView.setDataDirectorySuffix(processName);
            }
        }
        MobileAds.initialize(context, initializationStatus -> {
            Map<String, AdapterStatus> statusMap = initializationStatus.getAdapterStatusMap();
            for (String adapterClass : statusMap.keySet()) {
                AdapterStatus status = statusMap.get(adapterClass);
                if (status != null) {
                    Log.d(TAG, String.format("Adapter name: %s, Description: %s, Latency: %d",
                            adapterClass, status.getDescription(), status.getLatency()));
                }
            }
        });
        // Application context, always: this field lives on a process-wide singleton, so storing an
        // Activity here — which the two-arg init() lets a partner pass — would leak it forever.
        this.context = context.getApplicationContext();
    }

    private String getProcessName(Context context) {
        if (context == null) return null;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo processInfo : manager.getRunningAppProcesses()) {
            if (processInfo.pid == android.os.Process.myPid()) {
                return processInfo.processName;
            }
        }
        return null;
    }

    public void setOpenActivityAfterShowInterAds(boolean openActivityAfterShowInterAds) {
        this.openActivityAfterShowInterAds = openActivityAfterShowInterAds;
    }

    public boolean isOpenActivityAfterShowInterAds() {
        return "UNDER_AD".equals(AdBehavior.text("interstitial.presentation.next_screen_timing",
                openActivityAfterShowInterAds ? "UNDER_AD" : "AFTER_AD"));
    }

    @SuppressLint("VisibleForTests")
    public AdRequest getAdRequest() {
        return new AdRequest.Builder().build();
    }

    /**
     * Requests an interstitial and returns it through {@code adCallback}.
     */
    public void getInterstitialAds(Context context, String id, AdCallback adCallback) {
        if (AdGate.areRequestsHeld() || !AdBehavior.bool("global.ads_enabled") || AdGate.isPurchased(context) || isClickCapReached(context, id)) {
            adCallback.onInterstitialLoad(null);
            return;
        }

        InterstitialAd.load(context, id, getAdRequest(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd interstitialAd) {
                        if (adCallback != null)
                            adCallback.onInterstitialLoad(interstitialAd);

                        interstitialAd.setOnPaidEventListener(adValue -> {
                            ERainLogEventManager.logPaidAdImpression(context,
                                    adValue,
                                    interstitialAd.getAdUnitId(),
                                    interstitialAd.getResponseInfo()
                                            .getMediationAdapterClassName(), AdType.INTERSTITIAL);
                            ERainLogEventManager.logPaidAdjustWithToken(adValue, interstitialAd.getAdUnitId());
                        });
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        Log.i(TAG, loadAdError.getMessage());
                        if (adCallback != null)
                            adCallback.onAdFailedToLoad(loadAdError);
                    }

                });

    }


    /**
     * The click-counter show, with the next-action timing fixed for this one presentation.
     *
     * @param openNextUnderAd {@code true} fires {@code onNextAction} as the ad goes to the screen,
     *                        so the caller's next screen starts underneath it; {@code false} fires
     *                        it on dismissal instead. Taken as a parameter, not read from
     *                        {@link #openActivityAfterShowInterAds}, because the field is read
     *                        800 ms after the show begins — long enough for another placement to
     *                        have changed what this presentation's callbacks mean.
     */
    private void showInterstitialAdByTimes(final Context context, InterstitialAd mInterstitialAd, final AdCallback callback, final boolean openNextUnderAd) {
        // No setupAdmobData() call: the 24h rollover now runs inside every counter read and write,
        // so it can no longer be skipped by the load-time gate that never called it.
        if (!AdBehavior.bool("global.ads_enabled") || AdGate.isPurchased(context)) {
            callback.onNextAction();
            return;
        }
        if (mInterstitialAd == null) {
            if (callback != null) {
                callback.onNextAction();
            }
            return;
        }

        mInterstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {

            @Override
            public void onAdDismissedFullScreenContent() {
                super.onAdDismissedFullScreenContent();
                AppOpenManager.getInstance().setInterstitialShowing(false);
                SharePreferenceUtils.setLastImpressionInterstitialTime(context);
                if (callback != null) {
                    if (!openNextUnderAd) {
                        callback.onNextAction();
                    }
                    callback.onAdClosed();
                }
                if (dialog != null) {
                    dialog.dismiss();
                }
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                super.onAdFailedToShowFullScreenContent(adError);
                // Before the null check, and for the same reason as notifyShowFailed: the show
                // path raised both, so a failure has to lower them whether or not anyone is
                // listening. Leaving the flag up suppressed every app-resume ad until the
                // AppOpenManager watchdog cleared it 90 s later.
                AppOpenManager.getInstance().setInterstitialShowing(false);
                if (dialog != null) {
                    dialog.dismiss();
                }
                if (callback != null) {
                    callback.onAdFailedToShow(adError);
                    if (!openNextUnderAd) {
                        callback.onNextAction();
                    }
                }
            }

            @Override
            public void onAdShowedFullScreenContent() {
                super.onAdShowedFullScreenContent();
                AppOpenManager.getInstance().setInterstitialShowing(true);
                if (callback != null) {
                    callback.onInterstitialDisplayed();
                    if (!callback.usesActualInterstitialImpression()) callback.onAdImpression();
                }
            }

            @Override
            public void onAdImpression() {
                if (callback != null && callback.usesActualInterstitialImpression()) {
                    callback.onAdImpression();
                }
            }

            @Override
            public void onAdClicked() {
                super.onAdClicked();
                if (AdBehavior.bool("app_open.presentation.skip_after_ad_click", disableAdResumeWhenClickAds))
                    AppOpenManager.getInstance().disableAdResumeByClickAction();
                if (callback != null) {
                    callback.onAdClicked();
                }
                ERainLogEventManager.logClickAdsEvent(context, mInterstitialAd.getAdUnitId());
            }
        });

        if (!isClickCapReached(context, mInterstitialAd.getAdUnitId())) {
            showInterstitialAd(context, mInterstitialAd, callback, openNextUnderAd);
            return;
        }
        if (callback != null) {
            callback.onNextAction();
        }
    }


    /**
     * Shows the interstitial now, ignoring the click counter, with the next-action timing chosen
     * for this one presentation instead of taken from
     * {@link #setOpenActivityAfterShowInterAds(boolean)}.
     */
    public void forceShowInterstitial(Context context, InterstitialAd mInterstitialAd, final AdCallback callback, boolean openNextUnderAd) {
        showInterstitialAdByTimes(context, mInterstitialAd, callback, openNextUnderAd);
    }

    /**
     * Shows the ad, or runs the next action when there is nothing to show.
     */
    private void showInterstitialAd(Context context, InterstitialAd mInterstitialAd, AdCallback callback, boolean openNextUnderAd) {
        if (mInterstitialAd == null) {
            if (dialog != null) {
                dialog.dismiss();
            }
            if (callback != null) {
                callback.onNextAction();
            }
            return;
        }

        // Every exit below reports something. This branch used to return in silence when the
        // process was not resumed, leaving the caller waiting on a callback that never came.
        if (!ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            notifyShowFailed(callback, ERROR_CODE_SHOW_IN_BACKGROUND, "Show fail: process is not resumed", openNextUnderAd);
            return;
        }

        // show() needs an Activity, and the delayed block reads its lifecycle. Reported in this
        // module's own error domain so the caller restores the fill it already took out of the
        // cache; a generic code 0 lost it for good.
        if (!(context instanceof AppCompatActivity)) {
            notifyShowFailed(callback, ERROR_CODE_SHOW_IN_BACKGROUND,
                    "Show fail: context is not an AppCompatActivity", openNextUnderAd);
            return;
        }

        // The loading dialog is cosmetic; failing to put it up must never cost an impression.
        try {
            if (dialog != null && dialog.isShowing())
                dialog.dismiss();
            dialog = new PrepareLoadingAdsDialog(context);
            dialog.setCancelable(false);
            if (AdBehavior.bool("interstitial.presentation.loading_enabled")) dialog.show();
            AppOpenManager.getInstance().setInterstitialShowing(true);
        } catch (Exception e) {
            dialog = null;
            Log.w(TAG, "showInterstitialAd: loading dialog unavailable, showing the ad anyway", e);
        }

        // Committed to showing. Call sites use this to tell the two meanings of onNextAction
        // apart, so it has to fire on every path that reaches show().
        if (callback != null) {
            callback.onInterstitialShow();
        }

        new Handler().postDelayed(() -> {
            if (((AppCompatActivity) context).getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                if (callback != null && !callback.canShowInterstitial()) {
                    if (dialog != null) dialog.dismiss();
                    notifyShowFailed(callback, 0, "Interstitial policy changed before dispatch", openNextUnderAd);
                    return;
                }
                if (openNextUnderAd && callback != null) {
                    // Same tick as show() below, deliberately: the next Activity has to be queued
                    // before the ad's, or it is stacked on top of it instead of underneath.
                    callback.onNextAction();
                    new Handler().postDelayed(() -> {
                        if (dialog != null && dialog.isShowing() && !((Activity) context).isDestroyed())
                            dialog.dismiss();
                    }, 1500);
                }
                mInterstitialAd.setImmersiveMode(true);
                mInterstitialAd.show((Activity) context);
            } else {
                if (dialog != null && dialog.isShowing() && !((Activity) context).isDestroyed())
                    dialog.dismiss();
                notifyShowFailed(callback, ERROR_CODE_SHOW_IN_BACKGROUND, "Show fail in background after show loading ad", openNextUnderAd);
            }
        }, AdBehavior.number("interstitial.presentation.pre_show_delay_ms"));
    }

    /**
     * Reports a presentation that never reached the screen.
     * <p>
     * onNextAction is still fired when the next screen was not opened under the ad, because that
     * is the signal legacy call sites advance their flow on.
     */
    private void notifyShowFailed(AdCallback callback, int code, String message, boolean openNextUnderAd) {
        // Before the null check on purpose: the flag is raised when the loading dialog goes up, so
        // a presentation that dies here must lower it whether or not anyone is listening. Leaving
        // it raised suppressed every app-resume ad for the rest of the process.
        AppOpenManager.getInstance().setInterstitialShowing(false);
        if (callback == null) {
            return;
        }
        Log.e(TAG, "showInterstitialAd: " + message);
        callback.onAdFailedToShow(new AdError(code, message, TAG));
        if (!openNextUnderAd) {
            callback.onNextAction();
        }
    }

    /**
     * Loads a banner into the activity's {@code banner_container}.
     */
    public void loadBanner(final Activity mActivity, String id) {
        final FrameLayout adContainer = mActivity.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = mActivity.findViewById(R.id.shimmer_container_banner);
        loadBanner(mActivity, id, adContainer, containerShimmer, null, false, BANNER_INLINE_LARGE_STYLE);
    }

    /**
     * Loads a banner into the activity's {@code banner_container}.
     */
    public void loadBanner(final Activity mActivity, String id, AdCallback callback) {
        final FrameLayout adContainer = mActivity.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = mActivity.findViewById(R.id.shimmer_container_banner);
        loadBanner(mActivity, id, adContainer, containerShimmer, callback, false, BANNER_INLINE_LARGE_STYLE);
    }


    /**
     * Loads an inline adaptive banner into the activity's {@code banner_container}.
     *
     * @param inlineStyle one of the {@code BANNER_INLINE_*} styles
     */
    public void loadInlineBanner(final Activity activity, String id, String inlineStyle) {
        final FrameLayout adContainer = activity.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = activity.findViewById(R.id.shimmer_container_banner);
        loadBanner(activity, id, adContainer, containerShimmer, null, true, inlineStyle);
    }

    /**
     * Loads an inline adaptive banner into the activity's {@code banner_container}.
     *
     * @param inlineStyle one of the {@code BANNER_INLINE_*} styles
     */
    public void loadInlineBanner(final Activity activity, String id, String inlineStyle, final AdCallback callback) {
        final FrameLayout adContainer = activity.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = activity.findViewById(R.id.shimmer_container_banner);
        loadBanner(activity, id, adContainer, containerShimmer, callback, true, inlineStyle);
    }

    /**
     * Loads a collapsible banner into the activity's {@code banner_container}.
     *
     * @param gravity edge the banner collapses towards
     */
    public void loadCollapsibleBanner(final Activity mActivity, String id, String gravity, final AdCallback callback) {
        final FrameLayout adContainer = mActivity.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = mActivity.findViewById(R.id.shimmer_container_banner);
        loadCollapsibleBanner(mActivity, id, gravity, adContainer, containerShimmer, callback);
    }

    /**
     * Loads a banner into the {@code banner_container} of a fragment's {@code rootView}.
     */
    public void loadBannerFragment(final Activity mActivity, String id, final View rootView, final AdCallback callback) {
        final FrameLayout adContainer = rootView.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = rootView.findViewById(R.id.shimmer_container_banner);
        loadBanner(mActivity, id, adContainer, containerShimmer, callback, false, BANNER_INLINE_LARGE_STYLE);
    }

    /**
     * Loads an inline adaptive banner into the {@code banner_container} of a fragment's {@code rootView}.
     *
     * @param inlineStyle one of the {@code BANNER_INLINE_*} styles
     */
    public void loadInlineBannerFragment(final Activity activity, String id, final View rootView, String inlineStyle, final AdCallback callback) {
        final FrameLayout adContainer = rootView.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = rootView.findViewById(R.id.shimmer_container_banner);
        loadBanner(activity, id, adContainer, containerShimmer, callback, true, inlineStyle);
    }

    /**
     * Loads a collapsible banner into the {@code banner_container} of a fragment's {@code rootView}.
     *
     * @param gravity edge the banner collapses towards
     */
    public void loadCollapsibleBannerFragment(final Activity mActivity, String id, final View rootView, String gravity, final AdCallback callback) {
        final FrameLayout adContainer = rootView.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = rootView.findViewById(R.id.shimmer_container_banner);
        loadCollapsibleBanner(mActivity, id, gravity, adContainer, containerShimmer, callback);
    }

    /**
     * Loads a large anchored adaptive banner (up to 20% of screen height, 50–150dp) into the
     * activity's {@code banner_container}.
     */
    @SuppressLint("VisibleForTests")
    public void loadLargeAnchoredBanner(final Activity mActivity, String id, final AdCallback callback) {
        final FrameLayout adContainer = mActivity.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = mActivity.findViewById(R.id.shimmer_container_banner);
        AdSize adSize = AdSize.getLargeAnchoredAdaptiveBannerAdSize(mActivity, getAdWidthDp(mActivity));
        loadBanner(mActivity, id, adContainer, containerShimmer, callback, adSize, adSize.getHeight());
    }

    /**
     * Loads a large anchored adaptive banner into the {@code banner_container} of a fragment's
     * {@code rootView}.
     */
    @SuppressLint("VisibleForTests")
    public void loadLargeAnchoredBannerFragment(final Activity mActivity, String id, final View rootView, final AdCallback callback) {
        final FrameLayout adContainer = rootView.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = rootView.findViewById(R.id.shimmer_container_banner);
        AdSize adSize = AdSize.getLargeAnchoredAdaptiveBannerAdSize(mActivity, getAdWidthDp(mActivity));
        loadBanner(mActivity, id, adContainer, containerShimmer, callback, adSize, adSize.getHeight());
    }

    /**
     * Loads an inline adaptive banner that may grow up to {@code maxHeightDp} (at least 32) into
     * the activity's {@code banner_container}.
     */
    @SuppressLint("VisibleForTests")
    public void loadInlineBanner(final Activity activity, String id, int maxHeightDp, final AdCallback callback) {
        final FrameLayout adContainer = activity.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = activity.findViewById(R.id.shimmer_container_banner);
        AdSize adSize = AdSize.getInlineAdaptiveBannerAdSize(getAdWidthDp(activity), maxHeightDp);
        // Inline sizes report height 0; reserve the cap so the shimmer keeps its slot
        loadBanner(activity, id, adContainer, containerShimmer, callback, adSize, maxHeightDp);
    }

    /**
     * Loads an inline adaptive banner that may grow up to {@code maxHeightDp} (at least 32) into
     * the {@code banner_container} of a fragment's {@code rootView}.
     */
    @SuppressLint("VisibleForTests")
    public void loadInlineBannerFragment(final Activity activity, String id, final View rootView, int maxHeightDp, final AdCallback callback) {
        final FrameLayout adContainer = rootView.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = rootView.findViewById(R.id.shimmer_container_banner);
        AdSize adSize = AdSize.getInlineAdaptiveBannerAdSize(getAdWidthDp(activity), maxHeightDp);
        // Inline sizes report height 0; reserve the cap so the shimmer keeps its slot
        loadBanner(activity, id, adContainer, containerShimmer, callback, adSize, maxHeightDp);
    }

    /**
     * Loads a fixed-size banner ({@link AdSize#BANNER}, {@link AdSize#LARGE_BANNER}, …) into the
     * activity's {@code banner_container}.
     */
    public void loadFixedSizeBanner(final Activity mActivity, String id, AdSize adSize, final AdCallback callback) {
        final FrameLayout adContainer = mActivity.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = mActivity.findViewById(R.id.shimmer_container_banner);
        loadBanner(mActivity, id, adContainer, containerShimmer, callback, adSize, adSize.getHeight());
    }

    /**
     * Loads a fixed-size banner into the {@code banner_container} of a fragment's {@code rootView}.
     */
    public void loadFixedSizeBannerFragment(final Activity mActivity, String id, final View rootView, AdSize adSize, final AdCallback callback) {
        final FrameLayout adContainer = rootView.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = rootView.findViewById(R.id.shimmer_container_banner);
        loadBanner(mActivity, id, adContainer, containerShimmer, callback, adSize, adSize.getHeight());
    }

    private void loadBanner(final Activity mActivity, String id,
                            final FrameLayout adContainer, final ShimmerFrameLayout containerShimmer,
                            final AdCallback callback, Boolean useInlineAdaptive, String inlineStyle) {
        AdSize adSize = getAdSize(mActivity, useInlineAdaptive, inlineStyle);
        // Inline sizes report height 0; the SMALL cap doubles as the reserved shimmer height
        int shimmerHeightDp = useInlineAdaptive && BANNER_INLINE_SMALL_STYLE.equalsIgnoreCase(inlineStyle)
                ? MAX_SMALL_INLINE_BANNER_HEIGHT
                : adSize.getHeight();
        loadBanner(mActivity, id, adContainer, containerShimmer, callback, adSize, shimmerHeightDp);
    }

    private void loadBanner(final Activity mActivity, String id,
                            final FrameLayout adContainer, final ShimmerFrameLayout containerShimmer,
                            final AdCallback callback, AdSize adSize, int shimmerHeightDp) {
        if (AdGate.areRequestsHeld() || !AdBehavior.bool("global.ads_enabled") || AdGate.isPurchased(mActivity)) {
            // Returning silently strands BannerAdHelper in Loading; end like a no-fill instead
            containerShimmer.stopShimmer();
            containerShimmer.setVisibility(View.GONE);
            adContainer.setVisibility(View.GONE);
            if (callback != null) {
                callback.onAdFailedToLoad(null);
            }
            return;
        }

        containerShimmer.setVisibility(View.VISIBLE);
        containerShimmer.startShimmer();
        try {
            AdView adView = new AdView(mActivity);
            adView.setAdUnitId(id);
            // Adaptive sizes span the window anyway; fixed sizes narrower than it must not hug the start edge
            adContainer.addView(adView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL));
            // Uncapped inline adaptive reports 0 until a creative arrives. Keep the
            // configured placeholder layout in that case instead of collapsing loading.
            if (shimmerHeightDp > 0) {
                ViewGroup.LayoutParams shimmerParams = containerShimmer.getLayoutParams();
                shimmerParams.height = (int) (shimmerHeightDp * containerShimmer.getResources().getDisplayMetrics().density + 0.5f);
                containerShimmer.setLayoutParams(shimmerParams);
            }
            adView.setAdSize(adSize);
            adView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            adView.setAdListener(new AdListener() {
                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                    containerShimmer.stopShimmer();
                    adContainer.setVisibility(View.GONE);
                    containerShimmer.setVisibility(View.GONE);

                    if (callback != null) {
                        callback.onAdFailedToLoad(loadAdError);
                    }
                }


                @Override
                public void onAdLoaded() {
                    Log.d(TAG, "Banner adapter class name: " + adView.getResponseInfo().getMediationAdapterClassName());
                    containerShimmer.stopShimmer();
                    containerShimmer.setVisibility(View.GONE);
                    adContainer.setVisibility(View.VISIBLE);
                    if (adView != null) {
                        adView.setOnPaidEventListener(adValue -> {
                            Log.d(TAG, "OnPaidEvent banner:" + adValue.getValueMicros());

                            ERainLogEventManager.logPaidAdImpression(context,
                                    adValue,
                                    adView.getAdUnitId(),
                                    adView.getResponseInfo()
                                            .getMediationAdapterClassName(), AdType.BANNER);
                            ERainLogEventManager.logPaidAdjustWithToken(adValue, adView.getAdUnitId());
                        });
                    }

                    if (callback != null) {
                        callback.onAdLoaded();
                    }
                }

                @Override
                public void onAdClicked() {
                    super.onAdClicked();
                    if (AdBehavior.bool("app_open.presentation.skip_after_ad_click", disableAdResumeWhenClickAds))
                        AppOpenManager.getInstance().disableAdResumeByClickAction();
                    if (callback != null) {
                        callback.onAdClicked();
                        Log.d(TAG, "onAdClicked");
                    }
                    ERainLogEventManager.logClickAdsEvent(context, id);
                }

                @Override
                public void onAdImpression() {
                    super.onAdImpression();
                    if (callback != null) {
                        callback.onAdImpression();
                    }
                }
            });

            adView.loadAd(getAdRequest());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadCollapsibleBanner(final Activity mActivity, String id, String gravity, final FrameLayout adContainer,
                                       final ShimmerFrameLayout containerShimmer, final AdCallback callback) {
        if (AdGate.areRequestsHeld() || !AdBehavior.bool("global.ads_enabled") || AdGate.isPurchased(mActivity)) {
            // Returning silently strands BannerAdHelper in Loading; end like a no-fill instead
            containerShimmer.stopShimmer();
            containerShimmer.setVisibility(View.GONE);
            adContainer.setVisibility(View.GONE);
            if (callback != null) {
                callback.onAdFailedToLoad(null);
            }
            return;
        }

        containerShimmer.setVisibility(View.VISIBLE);
        containerShimmer.startShimmer();
        try {
            AdView adView = new AdView(mActivity);
            adView.setAdUnitId(id);
            adContainer.addView(adView);
            AdSize adSize = getAdSize(mActivity, false, "");
            ViewGroup.LayoutParams shimmerParams = containerShimmer.getLayoutParams();
            shimmerParams.height = (int) (adSize.getHeight() * containerShimmer.getResources().getDisplayMetrics().density + 0.5f);
            containerShimmer.setLayoutParams(shimmerParams);
            adView.setAdSize(adSize);
            adView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            adView.loadAd(getAdRequestForCollapsibleBanner(gravity));
            adView.setAdListener(new AdListener() {

                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                    super.onAdFailedToLoad(loadAdError);
                    containerShimmer.stopShimmer();
                    adContainer.setVisibility(View.GONE);
                    containerShimmer.setVisibility(View.GONE);
                    if (callback != null) {
                        callback.onAdFailedToLoad(loadAdError);
                    }
                }

                @Override
                public void onAdLoaded() {
                    Log.d(TAG, "Banner adapter class name: " + adView.getResponseInfo().getMediationAdapterClassName());
                    containerShimmer.stopShimmer();
                    containerShimmer.setVisibility(View.GONE);
                    adContainer.setVisibility(View.VISIBLE);
                    adView.setOnPaidEventListener(adValue -> {
                        Log.d(TAG, "OnPaidEvent banner:" + adValue.getValueMicros());

                        ERainLogEventManager.logPaidAdImpression(context,
                                adValue,
                                adView.getAdUnitId(),
                                adView.getResponseInfo()
                                        .getMediationAdapterClassName(), AdType.BANNER);
                        ERainLogEventManager.logPaidAdjustWithToken(adValue, adView.getAdUnitId());
                    });
                    if (callback != null) {
                        callback.onAdLoaded();
                    }
                }

                @Override
                public void onAdClicked() {
                    super.onAdClicked();
                    if (AdBehavior.bool("app_open.presentation.skip_after_ad_click", disableAdResumeWhenClickAds))
                        AppOpenManager.getInstance().disableAdResumeByClickAction();
                    ERainLogEventManager.logClickAdsEvent(context, id);
                    if (callback != null) {
                        callback.onAdClicked();
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int getAdWidthDp(Activity mActivity) {
        // Width in dp of the window, not the display, so a multi-window ad is sized to fit.
        Display display = mActivity.getWindowManager().getDefaultDisplay();
        DisplayMetrics outMetrics = new DisplayMetrics();
        display.getMetrics(outMetrics);
        return (int) (outMetrics.widthPixels / outMetrics.density);
    }

    @SuppressLint("VisibleForTests")
    private AdSize getAdSize(Activity mActivity, Boolean useInlineAdaptive, String inlineStyle) {
        int adWidth = getAdWidthDp(mActivity);

        if (useInlineAdaptive) {
            if (BANNER_INLINE_LARGE_STYLE.equalsIgnoreCase(inlineStyle)) {
                return AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize(mActivity, adWidth);
            } else {
                return AdSize.getInlineAdaptiveBannerAdSize(adWidth, MAX_SMALL_INLINE_BANNER_HEIGHT);
            }
        }
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(mActivity, adWidth);

    }

    @SuppressLint("VisibleForTests")
    private AdRequest getAdRequestForCollapsibleBanner(String gravity) {
        AdRequest.Builder builder = new AdRequest.Builder();
        Bundle admobExtras = new Bundle();
        admobExtras.putString("collapsible", gravity);
        builder.addNetworkExtrasBundle(AdMobAdapter.class, admobExtras);
        return builder.build();
    }

    public void loadNativeAd(Context context, String id, final AdCallback callback) {
        if (AdGate.areRequestsHeld() || !AdBehavior.bool("global.ads_enabled") || AdGate.isPurchased(context)) {
            return;
        }
        VideoOptions videoOptions = new VideoOptions.Builder()
                .setStartMuted(true)
                .build();

        NativeAdOptions adOptions = new NativeAdOptions.Builder()
                .setVideoOptions(videoOptions)
                .build();
        AdLoader adLoader = new AdLoader.Builder(context, id)
                .forNativeAd(nativeAd -> {
                    callback.onUnifiedNativeAdLoaded(nativeAd);
                    nativeAd.setOnPaidEventListener(adValue -> {
                        ERainLogEventManager.logPaidAdImpression(context,
                                adValue,
                                id,
                                nativeAd.getResponseInfo().getMediationAdapterClassName(), AdType.NATIVE);
                        ERainLogEventManager.logPaidAdjustWithToken(adValue, id);
                    });
                })
                .withAdListener(new AdListener() {
                    @Override
                    public void onAdFailedToLoad(LoadAdError error) {
                        callback.onAdFailedToLoad(error);
                    }

                    @Override
                    public void onAdImpression() {
                        super.onAdImpression();
                        if (callback != null) {
                            callback.onAdImpression();
                        }
                    }

                    @Override
                    public void onAdOpened() {
                        super.onAdOpened();
                        if (callback != null) {
                            callback.onAdOpened();
                        }
                    }

                    @Override
                    public void onAdClicked() {
                        super.onAdClicked();
                        if (AdBehavior.bool("app_open.presentation.skip_after_ad_click", disableAdResumeWhenClickAds))
                            AppOpenManager.getInstance().disableAdResumeByClickAction();
                        if (callback != null) {
                            callback.onAdClicked();
                        }
                        ERainLogEventManager.logClickAdsEvent(context, id);
                    }
                })
                .withNativeAdOptions(adOptions)
                .build();
        adLoader.loadAd(getAdRequest());
    }

    public void populateUnifiedNativeAdView(NativeAd nativeAd, NativeAdView adView) {
        adView.setMediaView(adView.findViewById(R.id.ad_media));
        adView.setHeadlineView(adView.findViewById(R.id.ad_headline));
        adView.setBodyView(adView.findViewById(R.id.ad_body));
        adView.setCallToActionView(adView.findViewById(R.id.ad_call_to_action));
        adView.setIconView(adView.findViewById(R.id.ad_app_icon));
        adView.setPriceView(adView.findViewById(R.id.ad_price));
        adView.setStarRatingView(adView.findViewById(R.id.ad_stars));
        adView.setAdvertiserView(adView.findViewById(R.id.ad_advertiser));

        try {
            ((TextView) adView.getHeadlineView()).setText(nativeAd.getHeadline());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // These assets aren't guaranteed to be in every UnifiedNativeAd, so it's important to
        // check before trying to display them.
        try {
            if (nativeAd.getBody() == null) {
                adView.getBodyView().setVisibility(View.INVISIBLE);
            } else {
                adView.getBodyView().setVisibility(View.VISIBLE);
                ((TextView) adView.getBodyView()).setText(nativeAd.getBody());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            if (nativeAd.getCallToAction() == null) {
                Objects.requireNonNull(adView.getCallToActionView()).setVisibility(View.INVISIBLE);
            } else {
                Objects.requireNonNull(adView.getCallToActionView()).setVisibility(View.VISIBLE);
                ((TextView) adView.getCallToActionView()).setText(nativeAd.getCallToAction());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            if (nativeAd.getIcon() == null) {
                Objects.requireNonNull(adView.getIconView()).setVisibility(View.GONE);
            } else {
                ((ImageView) adView.getIconView()).setImageDrawable(
                        nativeAd.getIcon().getDrawable());
                adView.getIconView().setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            if (nativeAd.getPrice() == null) {
                Objects.requireNonNull(adView.getPriceView()).setVisibility(View.INVISIBLE);
            } else {
                Objects.requireNonNull(adView.getPriceView()).setVisibility(View.VISIBLE);
                ((TextView) adView.getPriceView()).setText(nativeAd.getPrice());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            if (nativeAd.getStarRating() == null) {
                Objects.requireNonNull(adView.getStarRatingView()).setVisibility(View.INVISIBLE);
            } else {
                ((RatingBar) Objects.requireNonNull(adView.getStarRatingView())).setRating(nativeAd.getStarRating().floatValue());
                adView.getStarRatingView().setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            if (nativeAd.getAdvertiser() == null) {
                adView.getAdvertiserView().setVisibility(View.INVISIBLE);
            } else {
                ((TextView) adView.getAdvertiserView()).setText(nativeAd.getAdvertiser());
                adView.getAdvertiserView().setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        adView.setNativeAd(nativeAd);

    }


    /**
     * Loads a rewarded ad and reports it through {@code callback}; premium users return without
     * a request. Nothing is cached here — the caller owns the fill.
     */
    public void initRewardAds(Context context, String id, AdCallback callback) {
        if (AdGate.areRequestsHeld() || !AdBehavior.bool("global.ads_enabled") || AdGate.isPurchased(context)) {
            return;
        }
        RewardedAd.load(context, id, getAdRequest(), new RewardedAdLoadCallback() {
            @Override
            public void onAdLoaded(@NonNull RewardedAd rewardedAd) {
                rewardedAd.setOnPaidEventListener(adValue -> {
                    ERainLogEventManager.logPaidAdImpression(context,
                            adValue,
                            rewardedAd.getAdUnitId(),
                            rewardedAd.getResponseInfo().getMediationAdapterClassName()
                            , AdType.REWARDED);
                    ERainLogEventManager.logPaidAdjustWithToken(adValue, rewardedAd.getAdUnitId());
                });
                callback.onRewardAdLoaded(rewardedAd);
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                callback.onAdFailedToLoad(loadAdError);
            }
        });
    }

    /** Shows only the supplied ad. Loading the next one is the caller's decision. */
    public void showRewardAds(final Activity context, RewardedAd rewardedAd,
                              final RewardCallback adCallback) {
        if (!AdBehavior.bool("global.ads_enabled") || AdGate.isPurchased(context)) {
            adCallback.onUserEarnedReward(null);
            return;
        }
        if (rewardedAd == null) {
            adCallback.onRewardedAdFailedToShow(0);
            return;
        } else {
            final AtomicBoolean settled = new AtomicBoolean(false);
            final String shownUnitId = rewardedAd.getAdUnitId();
            final TrackingAdCallback presentationTracking = new TrackingAdCallback(
                    PlacementRegistry.placementOf(shownUnitId), AdFormat.REWARDED, shownUnitId, null);
            rewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    if (!settled.compareAndSet(false, true)) return;
                    AppOpenManager.getInstance().setInterstitialShowing(false);
                    if (adCallback != null) adCallback.onRewardedAdClosed();
                }

                @Override
                public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                    if (!settled.compareAndSet(false, true)) return;
                    AppOpenManager.getInstance().setInterstitialShowing(false);
                    presentationTracking.onAdFailedToShow(adError);
                    if (adCallback != null)
                        adCallback.onRewardedAdFailedToShow(adError.getCode());
                }

                @Override
                public void onAdShowedFullScreenContent() {
                    if (settled.get()) return;

                    AppOpenManager.getInstance().setInterstitialShowing(true);
                    presentationTracking.onAdImpression();
                    if (adCallback != null) adCallback.onRewardedAdShown();
                }

                @Override
                public void onAdImpression() {
                    if (settled.get()) return;
                    if (adCallback != null) adCallback.onAdImpression();
                }

                public void onAdClicked() {
                    if (settled.get()) return;
                    if (AdBehavior.bool("app_open.presentation.skip_after_ad_click", disableAdResumeWhenClickAds))
                        AppOpenManager.getInstance().disableAdResumeByClickAction();
                    if (adCallback != null) {
                        adCallback.onAdClicked();
                    }
                    ERainLogEventManager.logClickAdsEvent(context, rewardedAd.getAdUnitId());
                }
            });
            rewardedAd.show(context, new OnUserEarnedRewardListener() {
                @Override
                public void onUserEarnedReward(@NonNull RewardItem rewardItem) {
                    if (adCallback != null) {
                        adCallback.onUserEarnedReward(rewardItem);

                    }
                }
            });
        }
    }


}
