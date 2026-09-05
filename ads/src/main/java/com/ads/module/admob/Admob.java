package com.ads.module.admob;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
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
import com.ads.module.helper.interstitial.InterstitialFrequency;
import com.ads.module.tracking.AdTracking;
import com.ads.module.util.SharePreferenceUtils;
import com.facebook.shimmer.ShimmerFrameLayout;
import com.ads.module.consent.ConsentCenter;
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
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.gms.ads.VideoOptions;
import com.google.android.gms.ads.initialization.AdapterStatus;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdOptions;
import com.google.android.gms.ads.nativead.NativeAdView;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import io.trackkit.AdFormat;
import io.trackkit.PlacementRegistry;

/**
 * Vendor ad adapters. Every retained interstitial, splash and rewarded show shares process-wide
 * fullscreen ownership with app-open resume, including cosmetic preparation. Interstitials keep
 * 800 ms preparation and 1500 ms UnderAd dialog cleanup. Splash captures its navigation mode per
 * call; busy or pre-invocation rejection does not consume its chosen ad. Vendor callbacks own the
 * active presentation until terminal, independently of cache loads and navigation completion.
 */
public class Admob {
    private static final String TAG = "ERainStudio";
    private static Admob instance;
    private int currentClicked = 0;
    private String nativeId;
    private int numShowAds = 3;

    /**
     * Interstitial clicks allowed per ad unit per 24h before the unit stops loading and showing.
     * {@code 0} — the default — means no cap.
     *
     * <p>Off by default on purpose: the cap shipped for a long time with its counter never
     * incremented, so "no cap" is the behaviour every existing partner build actually has. Turning
     * it on is an opt-in via {@link #setMaxClickAdsPerDay(int)}, typically driven by remote config.
     */
    private volatile int maxClickAds = 0;
    private Handler handlerTimeout;
    private Runnable rdTimeout;
    private boolean isTimeout;
    private boolean disableAdResumeWhenClickAds = false;
    private boolean isShowLoadingSplash = false;
    boolean isTimeDelay = false;
    /**
     * Process-wide default for when an interstitial's {@code onNextAction} fires: {@code false}
     * (the default) on dismissal, {@code true} as the ad goes to the screen so the caller can
     * start its next screen underneath it.
     * <p>
     * Only a <em>default</em> — the interstitial show path takes the value as a parameter, so one
     * presentation's choice can no longer be changed by another's while it is on screen. The
     * splash paths capture this default when the public show call begins, including priority fallback.
     */
    private volatile boolean openActivityAfterShowInterAds = false;
    private Context context;

    public static final String BANNER_INLINE_SMALL_STYLE = "BANNER_INLINE_SMALL_STYLE";
    public static final String BANNER_INLINE_LARGE_STYLE = "BANNER_INLINE_LARGE_STYLE";
        private final int MAX_SMALL_INLINE_BANNER_HEIGHT = 50;

    InterstitialAd mInterstitialSplash;


    /**
     * @param maxClickAds clicks per ad unit per 24h before interstitials from that unit stop being
     *                    loaded and shown. {@code 0} or negative disables the cap. Safe to call at
     *                    any time — remote config typically applies it once the fetch lands.
     */
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
        if (maxClickAds <= 0 || context == null || adUnitId == null || adUnitId.isEmpty()) {
            return;
        }
        AdmobHelper.increaseNumClickAdsPerDay(context, adUnitId);
    }

    /**
     * True when {@code adUnitId} has burned through its daily click allowance.
     */
    private boolean isClickCapReached(Context context, String adUnitId) {
        if (maxClickAds <= 0 || context == null || adUnitId == null || adUnitId.isEmpty()) {
            return false;
        }
        int clicks = AdmobHelper.getNumClickAdsPerDay(context, adUnitId);
        if (clicks < maxClickAds) {
            return false;
        }
        Log.w(TAG, "Interstitial suppressed: ad unit hit the daily click cap ("
                + clicks + "/" + maxClickAds + "). Resets 24h after the window opened.");
        return true;
    }

    public static Admob getInstance() {
        if (instance == null) {
            instance = new Admob();
            instance.isShowLoadingSplash = false;
        }
        return instance;
    }

    private Admob() {

    }

    public void setNumToShowAds(int numShowAds) {
        this.numShowAds = numShowAds;
    }

    public void setNumToShowAds(int numShowAds, int currentClicked) {
        this.numShowAds = numShowAds;
        this.currentClicked = currentClicked;
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

    public boolean isShowLoadingSplash() {
        return isShowLoadingSplash;
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
        return openActivityAfterShowInterAds;
    }

    @SuppressLint("VisibleForTests")
    public AdRequest getAdRequest() {
        AdRequest.Builder builder = new AdRequest.Builder();
        applyPersonalization(builder);
        return builder.build();
    }

    /**
     * Marks the request non-personalized when the user refused personalization.
     * <p>
     * The request still goes out — AdMob serves that user contextual (non-personalized) ads, and
     * declining to request at all would forfeit the fill for no compliance gain. Google also
     * enforces this server-side from the TC string; sending the extra states the same intent
     * explicitly rather than relying on that alone.
     */
    static void applyPersonalization(AdRequest.Builder builder) {
        if (ConsentCenter.canPersonalize()) {
            return;
        }
        Bundle extras = new Bundle();
        extras.putString("npa", "1");
        builder.addNetworkExtrasBundle(AdMobAdapter.class, extras);
    }

    public boolean interstitialSplashLoaded() {
        return mInterstitialSplash != null;
    }

    public InterstitialAd getInterstitialSplash() {
        return mInterstitialSplash;
    }

    /**
     * Loads the splash interstitial and hands control back to the caller when it is ready.
     *
     * @param timeOut   ms to wait for the ad; {@code <= 0} disables the timeout
     * @param timeDelay ms to hold the loaded ad before it is shown
     */
    public void loadSplashInterstitialAds(final Context context, String id, long timeOut, long timeDelay, AdCallback adListener) {
        isTimeDelay = false;
        isTimeout = false;

        if (AdGate.isPurchased(context)) {
            if (adListener != null) {
                adListener.onNextAction();
            }
            return;
        }
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                //check delay show ad splash
                if (mInterstitialSplash != null) {
                    onShowSplash((AppCompatActivity) context, adListener);
                    return;
                }
                isTimeDelay = true;
            }
        }, timeDelay);

        if (timeOut > 0) {
            handlerTimeout = new Handler();
            rdTimeout = new Runnable() {
                @Override
                public void run() {
                    isTimeout = true;
                    if (mInterstitialSplash != null) {
                        onShowSplash((AppCompatActivity) context, adListener);
                        return;
                    }
                    if (adListener != null) {
                        adListener.onNextAction();
                        isShowLoadingSplash = false;
                    }
                }
            };
            handlerTimeout.postDelayed(rdTimeout, timeOut);
        }


        isShowLoadingSplash = true;
        getInterstitialAds(context, id, new AdCallback() {
            @Override
            public void onInterstitialLoad(InterstitialAd interstitialAd) {
                super.onInterstitialLoad(interstitialAd);
                if (isTimeout)
                    return;
                if (interstitialAd != null) {
                    mInterstitialSplash = interstitialAd;
                    if (isTimeDelay) {
                        onShowSplash((AppCompatActivity) context, adListener);
                    }
                }
            }


            @Override
            public void onAdFailedToLoad(LoadAdError i) {
                super.onAdFailedToLoad(i);
                isShowLoadingSplash = false;
                if (isTimeout)
                    return;
                if (adListener != null) {
                    if (handlerTimeout != null && rdTimeout != null) {
                        handlerTimeout.removeCallbacks(rdTimeout);
                    }
                    adListener.onAdFailedToLoad(i);
                    adListener.onNextAction();
                }
            }

            @Override
            public void onAdFailedToShow(@Nullable AdError adError) {
                super.onAdFailedToShow(adError);
                if (adListener != null) {
                    adListener.onAdFailedToShow(adError);
                    adListener.onNextAction();
                }
            }
        });

    }

    /**
     * Loads the splash interstitial and hands control back to the caller when it is ready.
     *
     * @param timeOut           ms to wait for the ad; {@code <= 0} disables the timeout
     * @param timeDelay         ms to hold the loaded ad before it is shown
     * @param showSplashIfReady show the ad as soon as it loads, without a further call
     */
    public void loadSplashInterstitialAds(final Context context, String id, long timeOut, long timeDelay, boolean showSplashIfReady, AdCallback adListener) {
        isTimeDelay = false;
        isTimeout = false;

        if (AdGate.isPurchased(context)) {
            if (adListener != null) {
                adListener.onNextAction();
            }
            return;
        }
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mInterstitialSplash != null) {
                    if (showSplashIfReady)
                        onShowSplash((AppCompatActivity) context, adListener);
                    else
                        adListener.onAdSplashReady();
                    return;
                }
                isTimeDelay = true;
            }
        }, timeDelay);

        if (timeOut > 0) {
            handlerTimeout = new Handler();
            rdTimeout = new Runnable() {
                @Override
                public void run() {
                    isTimeout = true;
                    if (mInterstitialSplash != null) {
                        if (showSplashIfReady)
                            onShowSplash((AppCompatActivity) context, adListener);
                        else
                            adListener.onAdSplashReady();
                        return;
                    }
                    if (adListener != null) {
                        adListener.onNextAction();
                        isShowLoadingSplash = false;
                    }
                }
            };
            handlerTimeout.postDelayed(rdTimeout, timeOut);
        }

        isShowLoadingSplash = true;
        getInterstitialAds(context, id, new AdCallback() {
            @Override
            public void onInterstitialLoad(InterstitialAd interstitialAd) {
                super.onInterstitialLoad(interstitialAd);
                if (isTimeout)
                    return;
                if (interstitialAd != null) {
                    mInterstitialSplash = interstitialAd;
                    if (isTimeDelay) {
                        if (showSplashIfReady)
                            onShowSplash((AppCompatActivity) context, adListener);
                        else
                            adListener.onAdSplashReady();
                    }
                }
            }

            @Override
            public void onAdFailedToShow(@Nullable AdError adError) {
                super.onAdFailedToShow(adError);
                if (adListener != null) {
                    adListener.onAdFailedToShow(adError);
                    adListener.onNextAction();
                }
            }

            @Override
            public void onAdFailedToLoad(LoadAdError i) {
                super.onAdFailedToLoad(i);
                if (isTimeout)
                    return;
                if (adListener != null) {
                    adListener.onNextAction();
                    if (handlerTimeout != null && rdTimeout != null) {
                        handlerTimeout.removeCallbacks(rdTimeout);
                    }
                    adListener.onAdFailedToLoad(i);
                }
            }
        });

    }

    public void onShowSplash(AppCompatActivity activity, AdCallback adListener) {
        showBufferedSplash(activity, adListener, SplashSlot.STANDARD, mInterstitialSplash, openActivityAfterShowInterAds);
    }

    public void onShowSplash(AppCompatActivity activity, AdCallback adListener, InterstitialAd mInter) {
        // Do not replace a newer cached fill merely to present the explicit ad selected by caller.
        if (mInterstitialSplash == null) mInterstitialSplash = mInter;
        showBufferedSplash(activity, adListener, SplashSlot.STANDARD, mInter, openActivityAfterShowInterAds);
    }

    public void onCheckShowSplashWhenFail(AppCompatActivity activity, AdCallback callback, int timeDelay) {
        final boolean underAd = openActivityAfterShowInterAds;
        new Handler(activity.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (interstitialSplashLoaded() && !isShowLoadingSplash()) {
                    showBufferedSplash(activity, callback, SplashSlot.STANDARD, mInterstitialSplash, underAd);
                }
            }
        }, timeDelay);
    }

    /**
     * Requests an interstitial and returns it through {@code adCallback}.
     */
    public void getInterstitialAds(Context context, String id, AdCallback adCallback) {
        if (AdGate.isPurchased(context) || isClickCapReached(context, id)) {
            adCallback.onInterstitialLoad(null);
            return;
        }

        if (adCallback != null) adCallback.onAdRequestStarted(id);
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
     * Shows the interstitial after {@code timeDelay}, for the reopen-on-splash flow.
     * Navigation mode is captured now, before either the scheduling or preparation delay.
     */
    public void showInterstitialAdByTimes(final Context context, final InterstitialAd mInterstitialAd, final AdCallback callback, long timeDelay) {
        final boolean underAd = openActivityAfterShowInterAds;
        if (timeDelay > 0) {
            handlerTimeout = new Handler();
            rdTimeout = new Runnable() {
                @Override
                public void run() {
                    forceShowInterstitial(context, mInterstitialAd, callback, underAd);
                }
            };
            handlerTimeout.postDelayed(rdTimeout, timeDelay);
        } else {
            forceShowInterstitial(context, mInterstitialAd, callback, underAd);
        }
    }


    /**
     * Shows the interstitial once the click counter reaches the configured threshold, so an app
     * can gate ads on "every Nth action" rather than on every action.
     */
    public void showInterstitialAdByTimes(final Context context, InterstitialAd mInterstitialAd, final AdCallback callback) {
        showInterstitialAdByTimes(context, mInterstitialAd, callback, openActivityAfterShowInterAds);
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
        AdSkipReason policy = AdGate.skipReason(context, true, true, false);
        if (policy != null) {
            if (callback != null) callback.onAdShowRejected(policy);
            return;
        }
        if (mInterstitialAd == null) {
            if (callback != null) {
                callback.onAdShowRejected(AdSkipReason.NOT_READY);
            }
            return;
        }

        final InterstitialPreparation preparation = new InterstitialPreparation();


        if (!isClickCapReached(context, mInterstitialAd.getAdUnitId())) {
            showInterstitialAd(context, mInterstitialAd, callback, openNextUnderAd, preparation);
            return;
        }
        notifyShowRejected(callback, AdSkipReason.CLICK_CAP, preparation);
    }


    private void attachInterstitialPresentationCallback(Context context, InterstitialAd ad,
                                                         AdCallback callback, boolean underAd,
                                                         InterstitialPreparation preparation,
                                                         boolean recordInterval) {
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override public void onAdDismissedFullScreenContent() {
                if (!preparation.finishInvoked()) return;
                if (recordInterval) SharePreferenceUtils.setLastImpressionInterstitialTime(context);
                if (callback != null) {
                    if (!underAd) notifyPresentationCallback(callback::onNextAction);
                    notifyPresentationCallback(callback::onAdClosed);
                }
            }
            @Override public void onAdFailedToShowFullScreenContent(@NonNull AdError error) {
                if (preparation.finishInvoked()) notifyInterstitialFailure(callback, underAd, error);
            }
            @Override public void onAdShowedFullScreenContent() {
                if (!preparation.wasInvoked() || !preparation.lease.presented()) return;
                if (preparation.splashSlot == SplashSlot.HIGH1 || preparation.splashSlot == SplashSlot.HIGH2
                        || preparation.splashSlot == SplashSlot.HIGH3) {
                    // Existing priority splash return suppression; policy is consolidated in G12.
                    AppOpenManager.getInstance().disableAdResumeByClickAction();
                }
                if (callback != null) notifyPresentationCallback(callback::onAdPresented);
            }
            @Override public void onAdImpression() {
                if (preparation.wasInvoked() && callback != null) notifyPresentationCallback(callback::onAdImpression);
            }
            @Override public void onAdClicked() {
                if (!preparation.wasInvoked()) return;
                if (disableAdResumeWhenClickAds) AppOpenManager.getInstance().disableAdResumeByClickAction();
                notifyPresentationCallback(() -> ERainLogEventManager.logClickAdsEvent(context, ad.getAdUnitId()));
                if (callback != null) notifyPresentationCallback(callback::onAdClicked);
            }
        });
    }

    /**
     * Shows the interstitial now, ignoring the click counter.
     */
    public void forceShowInterstitial(Context context, InterstitialAd mInterstitialAd, final AdCallback callback) {
        forceShowInterstitial(context, mInterstitialAd, callback, openActivityAfterShowInterAds);
    }

    /**
     * Shows the interstitial now, ignoring the click counter, with the next-action timing chosen
     * for this one presentation instead of taken from
     * {@link #setOpenActivityAfterShowInterAds(boolean)}.
     */
    public void forceShowInterstitial(Context context, InterstitialAd mInterstitialAd, final AdCallback callback, boolean openNextUnderAd) {
        currentClicked = numShowAds;
        showInterstitialAdByTimes(context, mInterstitialAd, callback, openNextUnderAd);
    }

    /**
     * Shows the ad when the click counter has reached the threshold, otherwise runs the next action.
     */
    private void showInterstitialAd(Context context, InterstitialAd ad, AdCallback callback,
                                    boolean openNextUnderAd, InterstitialPreparation preparation) {
        if (FullscreenPresentationOwner.getInstance().isBusy()) {
            notifyShowRejected(callback, AdSkipReason.PRESENTATION_BUSY, preparation);
            return;
        }
        currentClicked++;
        if (currentClicked < numShowAds || ad == null) {
            notifyShowRejected(callback, ad == null ? AdSkipReason.NOT_READY
                    : AdSkipReason.CAPPED_BY_MODULE, preparation);
            return;
        }
        currentClicked = 0;
        prepareInterstitialPresentation(context, ad, callback, openNextUnderAd, preparation, true, true);
    }

    /** One preparation implementation for modern and retained splash interstitials. */
    private void prepareInterstitialPresentation(Context context, InterstitialAd ad, AdCallback callback,
                                                boolean underAd, InterstitialPreparation preparation,
                                                boolean checkCaps, boolean immersive) {
        AdSkipReason rejection = interstitialShowSkipReason(context, ad.getAdUnitId(), callback, checkCaps);
        if (rejection != null) {
            notifyShowRejected(callback, rejection, preparation);
            return;
        }
        if (!preparation.begin(() -> notifyShowRejected(callback, AdSkipReason.EXPIRED, preparation))) {
            notifyShowRejected(callback, AdSkipReason.PRESENTATION_BUSY, preparation);
            return;
        }
        if (preparation.splashSlot != null) {
            activeSplashPreparation = preparation;
            isShowLoadingSplash = true;
            cancelSplashTimeout(preparation.splashSlot);
        }
        try {
            attachInterstitialPresentationCallback(context, ad, callback, underAd, preparation, checkCaps);
            if (preparation.splashSlot != null) attachSplashPaidCallback(context, ad);
        } catch (RuntimeException error) {
            Log.w(TAG, "Interstitial callback setup failed", error);
            notifyShowRejected(callback, AdSkipReason.PREPARATION_FAILED, preparation);
            return;
        }
        if (preparation.ended.get()) return;
        // Retain the raw splash ready notification without treating it as a new request or show.
        if (preparation.splashSlot != null && callback != null) notifyPresentationCallback(callback::onAdLoaded);
        try {
            preparation.loadingDialog = new PrepareLoadingAdsDialog(context);
            preparation.loadingDialog.setCancelable(false);
            preparation.loadingDialog.show();
        } catch (RuntimeException error) {
            preparation.dismissDialog();
            Log.w(TAG, "Loading dialog unavailable; continuing interstitial presentation", error);
        }
        if (preparation.splashSlot == null && callback != null) {
            notifyPresentationCallback(callback::onInterstitialShow);
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (preparation.ended.get()) return;
            AdSkipReason delayed = interstitialShowSkipReason(context, ad.getAdUnitId(), callback, checkCaps);
            if (delayed != null) {
                notifyShowRejected(callback, delayed, preparation);
                return;
            }
            if (immersive) {
                try { ad.setImmersiveMode(true); }
                catch (RuntimeException error) { Log.w(TAG, "Immersive mode unavailable", error); }
            }
            // Cosmetic/vendor setup may call back synchronously and change the policy or host.
            delayed = interstitialShowSkipReason(context, ad.getAdUnitId(), callback, checkCaps);
            if (delayed != null) {
                notifyShowRejected(callback, delayed, preparation);
                return;
            }
            if (!preparation.lease.start()) {
                notifyShowRejected(callback, AdSkipReason.EXPIRED, preparation);
                return;
            }
            if (underAd && callback != null) {
                // Keep next and vendor.show on the same tick; navigation itself may pause the host.
                notifyPresentationCallback(callback::onNextAction);
                new Handler(Looper.getMainLooper()).postDelayed(preparation::dismissDialog, 1500);
            }
            try {
                preparation.invoked = true;
                if (preparation.splashSlot != null) {
                    consumeSplashAd(preparation.splashSlot, ad);
                    if (activeSplashPreparation == preparation) isShowLoadingSplash = false;
                }
                ad.show((Activity) context);
            } catch (RuntimeException error) {
                if (!preparation.finish()) return;
                notifyInterstitialFailure(callback, underAd,
                        new AdError(0, String.valueOf(error.getMessage()), TAG));
            }
        }, 800);
    }

    private static void notifyPresentationCallback(Runnable action) {
        try { action.run(); }
        catch (Exception error) { Log.w(TAG, "Interstitial callback failed", error); }
    }

    private static void notifyInterstitialFailure(AdCallback callback, boolean underAd, AdError error) {
        if (callback == null) return;
        notifyPresentationCallback(() -> callback.onAdFailedToShow(error));
        if (!underAd) notifyPresentationCallback(callback::onNextAction);
    }

    private AdSkipReason interstitialShowSkipReason(Context context, String adUnitId, AdCallback callback, boolean checkCaps) {
        if (!(context instanceof AppCompatActivity)) return AdSkipReason.INVALID_HOST;
        AppCompatActivity host = (AppCompatActivity) context;
        if (host.isFinishing() || host.isDestroyed()) return AdSkipReason.INVALID_HOST;
        if (!host.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED))
            return AdSkipReason.HOST_NOT_RESUMED;
        if (!ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED))
            return AdSkipReason.PROCESS_NOT_RESUMED;
        AdSkipReason policy = AdGate.skipReason(context, true, true, false);
        if (policy != null) return policy;
        if (checkCaps && !InterstitialFrequency.elapsed(context)) return AdSkipReason.INTERVAL;
        if (checkCaps && isClickCapReached(context, adUnitId)) return AdSkipReason.CLICK_CAP;
        try {
            return callback == null ? null : callback.getAdShowSkipReason();
        } catch (Exception error) {
            Log.w(TAG, "Interstitial admission check failed", error);
            return AdSkipReason.PREPARATION_FAILED;
        }
    }

    /** Declines before vendor invocation; the original wrapper remains reusable. */
    private void notifyShowRejected(AdCallback callback, AdSkipReason reason,
                                    InterstitialPreparation preparation) {
        if (preparation.finish() && callback != null) notifyPresentationCallback(() -> callback.onAdShowRejected(reason));
    }

    /** One call's UI and terminal state; no cleanup reaches another call's dialog. */
    private final class InterstitialPreparation {
        final AtomicBoolean ended = new AtomicBoolean(false);
        PrepareLoadingAdsDialog loadingDialog;
        boolean invoked;
        FullscreenPresentationOwner.Lease lease;
        SplashSlot splashSlot;

        boolean begin(Runnable onExpired) {
            lease = FullscreenPresentationOwner.getInstance().tryAcquire(AdFormat.INTERSTITIAL, onExpired);
            return lease != null;
        }

        boolean wasInvoked() { return invoked && !ended.get() && lease.isCurrent(); }

        boolean finishInvoked() { return invoked && finish(); }

        boolean finish() {
            if (!ended.compareAndSet(false, true)) return false;
            if (lease != null) lease.finish();
            dismissDialog();
            if (activeSplashPreparation == this) {
                activeSplashPreparation = null;
                isShowLoadingSplash = false;
            }
            return true;
        }

        void dismissDialog() {
            PrepareLoadingAdsDialog owned = loadingDialog;
            loadingDialog = null;
            if (owned != null) {
                try { owned.dismiss(); }
                catch (Exception error) { Log.w(TAG, "Preparation dialog cleanup failed", error); }
            }
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
     * Loads a medium-size collapsible banner into the activity's {@code banner_container}.
     *
     * @param gravity edge the banner collapses towards
     */
    public void loadCollapsibleBannerSizeMedium(final Activity mActivity, String id, String gravity, AdSize sizeBanner, final AdCallback callback) {
        final FrameLayout adContainer = mActivity.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = mActivity.findViewById(R.id.shimmer_container_banner);
        loadCollapsibleAutoSizeMedium(mActivity, id, gravity, sizeBanner, adContainer, containerShimmer, callback);
    }

    /**
     * Loads a banner into the {@code banner_container} of a fragment's {@code rootView}.
     */
    public void loadBannerFragment(final Activity mActivity, String id, final View rootView) {
        final FrameLayout adContainer = rootView.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = rootView.findViewById(R.id.shimmer_container_banner);
        loadBanner(mActivity, id, adContainer, containerShimmer, null, false, BANNER_INLINE_LARGE_STYLE);
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
    public void loadInlineBannerFragment(final Activity activity, String id, final View rootView, String inlineStyle) {
        final FrameLayout adContainer = rootView.findViewById(R.id.banner_container);
        final ShimmerFrameLayout containerShimmer = rootView.findViewById(R.id.shimmer_container_banner);
        loadBanner(activity, id, adContainer, containerShimmer, null, true, inlineStyle);
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
        if (AdGate.isPurchased(mActivity)) {
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
            containerShimmer.getLayoutParams().height = (int) (shimmerHeightDp * Resources.getSystem().getDisplayMetrics().density + 0.5f);
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
                    if (disableAdResumeWhenClickAds)
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

            if (callback != null) callback.onAdRequestStarted(id);
            adView.loadAd(getAdRequest());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadCollapsibleBanner(final Activity mActivity, String id, String gravity, final FrameLayout adContainer,
                                       final ShimmerFrameLayout containerShimmer, final AdCallback callback) {
        if (AdGate.isPurchased(mActivity)) {
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
            containerShimmer.getLayoutParams().height = (int) (adSize.getHeight() * Resources.getSystem().getDisplayMetrics().density + 0.5f);
            adView.setAdSize(adSize);
            adView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
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
                    if (disableAdResumeWhenClickAds)
                        AppOpenManager.getInstance().disableAdResumeByClickAction();
                    ERainLogEventManager.logClickAdsEvent(context, id);
                    if (callback != null) {
                        callback.onAdClicked();
                    }
                }

                @Override
                public void onAdImpression() {
                    super.onAdImpression();
                    if (callback != null) {
                        callback.onAdImpression();
                    }
                }
            });
            if (callback != null) callback.onAdRequestStarted(id);
            adView.loadAd(getAdRequestForCollapsibleBanner(gravity));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadCollapsibleAutoSizeMedium(final Activity mActivity, String id, String gravity, AdSize sizeBanner, final FrameLayout adContainer,
                                               final ShimmerFrameLayout containerShimmer, final AdCallback callback) {
        if (AdGate.isPurchased(mActivity)) {
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
            AdSize adSize = sizeBanner;
            containerShimmer.getLayoutParams().height = (int) (adSize.getHeight() * Resources.getSystem().getDisplayMetrics().density + 0.5f);
            adView.setAdSize(adSize);
            adView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
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
                    if (disableAdResumeWhenClickAds)
                        AppOpenManager.getInstance().disableAdResumeByClickAction();
                    ERainLogEventManager.logClickAdsEvent(context, id);
                    if (callback != null) {
                        callback.onAdClicked();
                    }
                }

                @Override
                public void onAdImpression() {
                    super.onAdImpression();
                    if (callback != null) {
                        callback.onAdImpression();
                    }
                }
            });
            if (callback != null) callback.onAdRequestStarted(id);
            adView.loadAd(getAdRequestForCollapsibleBanner(gravity));
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
        admobExtras.putString("collapsible_request_id", UUID.randomUUID().toString());
        // One bundle per adapter class — a second addNetworkExtrasBundle would replace this one,
        // so the personalization flag goes in here rather than through applyPersonalization.
        if (!ConsentCenter.canPersonalize()) {
            admobExtras.putString("npa", "1");
        }
        builder.addNetworkExtrasBundle(AdMobAdapter.class, admobExtras);
        return builder.build();
    }

    /**
     * Loads a native ad into the activity's {@code fl_adplaceholder}.
     */
    public void loadNative(final Activity mActivity, String id) {
        final FrameLayout frameLayout = mActivity.findViewById(R.id.fl_adplaceholder);
        final ShimmerFrameLayout containerShimmer = mActivity.findViewById(R.id.shimmer_container_native);
        loadNative(mActivity, containerShimmer, frameLayout, id, R.layout.custom_native_admob_free_size);
    }

    public void loadNativeFragment(final Activity mActivity, String id, View parent) {
        final FrameLayout frameLayout = parent.findViewById(R.id.fl_adplaceholder);
        final ShimmerFrameLayout containerShimmer = parent.findViewById(R.id.shimmer_container_native);
        loadNative(mActivity, containerShimmer, frameLayout, id, R.layout.custom_native_admob_free_size);
    }

    public void loadSmallNative(final Activity mActivity, String adUnitId) {
        final FrameLayout frameLayout = mActivity.findViewById(R.id.fl_adplaceholder);
        final ShimmerFrameLayout containerShimmer = mActivity.findViewById(R.id.shimmer_container_native);
        loadNative(mActivity, containerShimmer, frameLayout, adUnitId, R.layout.custom_native_admob_medium);
    }

    public void loadSmallNativeFragment(final Activity mActivity, String adUnitId, View parent) {
        final FrameLayout frameLayout = parent.findViewById(R.id.fl_adplaceholder);
        final ShimmerFrameLayout containerShimmer = parent.findViewById(R.id.shimmer_container_native);
        loadNative(mActivity, containerShimmer, frameLayout, adUnitId, R.layout.custom_native_admob_medium);
    }

    public void loadNativeAd(Context context, String id, final AdCallback callback) {
        if (AdGate.isPurchased(context)) {
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
                        if (disableAdResumeWhenClickAds)
                            AppOpenManager.getInstance().disableAdResumeByClickAction();
                        if (callback != null) {
                            callback.onAdClicked();
                        }
                        ERainLogEventManager.logClickAdsEvent(context, id);
                    }
                })
                .withNativeAdOptions(adOptions)
                .build();
        callback.onAdRequestStarted(id);
        adLoader.loadAd(getAdRequest());
    }

    public void loadNativeAds(Context context, String id, final AdCallback callback, int countAd) {
        if (AdGate.isPurchased(context)) {
            callback.onAdClosed();
            return;
        }
        VideoOptions videoOptions = new VideoOptions.Builder()
                .setStartMuted(true)
                .build();

        NativeAdOptions adOptions = new NativeAdOptions.Builder()
                .setVideoOptions(videoOptions)
                .build();
        AdLoader adLoader = new AdLoader.Builder(context, id)
                .forNativeAd(new NativeAd.OnNativeAdLoadedListener() {

                    @Override
                    public void onNativeAdLoaded(@NonNull NativeAd nativeAd) {
                        callback.onUnifiedNativeAdLoaded(nativeAd);
                        nativeAd.setOnPaidEventListener(adValue -> {
                            ERainLogEventManager.logPaidAdImpression(context,
                                    adValue,
                                    id,
                                    nativeAd.getResponseInfo().getMediationAdapterClassName(), AdType.NATIVE);
                            ERainLogEventManager.logPaidAdjustWithToken(adValue, id);
                        });
                    }
                })
                .withAdListener(new AdListener() {
                    @Override
                    public void onAdFailedToLoad(LoadAdError error) {
                        callback.onAdFailedToLoad(error);
                    }

                    @Override
                    public void onAdClicked() {
                        super.onAdClicked();
                        if (disableAdResumeWhenClickAds)
                            AppOpenManager.getInstance().disableAdResumeByClickAction();
                        if (callback != null) {
                            callback.onAdClicked();
                        }
                        ERainLogEventManager.logClickAdsEvent(context, id);
                    }
                })
                .withNativeAdOptions(adOptions)
                .build();
        adLoader.loadAds(getAdRequest(), countAd);
    }

    private void loadNative(final Context context, final ShimmerFrameLayout containerShimmer, final FrameLayout frameLayout, final String id, final int layout) {
        if (AdGate.isPurchased(context)) {
            containerShimmer.setVisibility(View.GONE);
            return;
        }
        frameLayout.removeAllViews();
        frameLayout.setVisibility(View.GONE);
        containerShimmer.setVisibility(View.VISIBLE);
        containerShimmer.startShimmer();

        VideoOptions videoOptions = new VideoOptions.Builder()
                .setStartMuted(true)
                .build();

        NativeAdOptions adOptions = new NativeAdOptions.Builder()
                .setVideoOptions(videoOptions)
                .build();


        AdLoader adLoader = new AdLoader.Builder(context, id)
                .forNativeAd(new NativeAd.OnNativeAdLoadedListener() {

                    @Override
                    public void onNativeAdLoaded(@NonNull NativeAd nativeAd) {
                        containerShimmer.stopShimmer();
                        containerShimmer.setVisibility(View.GONE);
                        frameLayout.setVisibility(View.VISIBLE);
                        @SuppressLint("InflateParams") NativeAdView adView = (NativeAdView) LayoutInflater.from(context)
                                .inflate(layout, null);
                        nativeAd.setOnPaidEventListener(adValue -> {
                            ERainLogEventManager.logPaidAdImpression(context,
                                    adValue,
                                    id,
                                    nativeAd.getResponseInfo().getMediationAdapterClassName(), AdType.NATIVE);
                            ERainLogEventManager.logPaidAdjustWithToken(adValue, id);
                        });
                        populateUnifiedNativeAdView(nativeAd, adView);
                        frameLayout.removeAllViews();
                        frameLayout.addView(adView);
                    }


                })
                .withAdListener(new AdListener() {
                    @Override
                    public void onAdFailedToLoad(LoadAdError error) {
                        containerShimmer.stopShimmer();
                        containerShimmer.setVisibility(View.GONE);
                        frameLayout.setVisibility(View.GONE);
                    }

                    @Override
                    public void onAdClicked() {
                        super.onAdClicked();
                        if (disableAdResumeWhenClickAds)
                            AppOpenManager.getInstance().disableAdResumeByClickAction();
                        ERainLogEventManager.logClickAdsEvent(context, id);
                    }
                })
                .withNativeAdOptions(adOptions)
                .build();

        adLoader.loadAd(getAdRequest());
    }

    private void loadNative(final Context context, final ShimmerFrameLayout containerShimmer, final FrameLayout frameLayout, final String id, final int layout, final AdCallback callback) {
        if (AdGate.isPurchased(context)) {
            containerShimmer.setVisibility(View.GONE);
            return;
        }
        frameLayout.removeAllViews();
        frameLayout.setVisibility(View.GONE);
        containerShimmer.setVisibility(View.VISIBLE);
        containerShimmer.startShimmer();

        VideoOptions videoOptions = new VideoOptions.Builder()
                .setStartMuted(true)
                .build();

        NativeAdOptions adOptions = new NativeAdOptions.Builder()
                .setVideoOptions(videoOptions)
                .build();


        AdLoader adLoader = new AdLoader.Builder(context, id)
                .forNativeAd(new NativeAd.OnNativeAdLoadedListener() {

                    @Override
                    public void onNativeAdLoaded(@NonNull NativeAd nativeAd) {
                        containerShimmer.stopShimmer();
                        containerShimmer.setVisibility(View.GONE);
                        frameLayout.setVisibility(View.VISIBLE);
                        @SuppressLint("InflateParams") NativeAdView adView = (NativeAdView) LayoutInflater.from(context)
                                .inflate(layout, null);
                        nativeAd.setOnPaidEventListener(adValue -> {
                            ERainLogEventManager.logPaidAdImpression(context,
                                    adValue,
                                    id,
                                    nativeAd.getResponseInfo().getMediationAdapterClassName(), AdType.NATIVE);
                            ERainLogEventManager.logPaidAdjustWithToken(adValue, id);
                        });
                        populateUnifiedNativeAdView(nativeAd, adView);
                        frameLayout.removeAllViews();
                        frameLayout.addView(adView);
                    }

                })
                .withAdListener(new AdListener() {
                    @Override
                    public void onAdFailedToLoad(LoadAdError error) {
                        containerShimmer.stopShimmer();
                        containerShimmer.setVisibility(View.GONE);
                        frameLayout.setVisibility(View.GONE);
                    }


                    @Override
                    public void onAdClicked() {
                        super.onAdClicked();
                        if (disableAdResumeWhenClickAds)
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

    public void loadNativeAdsFullScreen(Context context, String id, final AdCallback callback) {
        if (AdGate.isPurchased(context)) {
            return;
        }

        VideoOptions videoOptions =
                new VideoOptions.Builder().setStartMuted(false).build();
        NativeAdOptions adOptions =
                new NativeAdOptions.Builder()
                        .setMediaAspectRatio(MediaAspectRatio.PORTRAIT)
                        .setVideoOptions(videoOptions)
                        .build();
        AdLoader adLoader = new AdLoader.Builder(context, id)
                .forNativeAd(new NativeAd.OnNativeAdLoadedListener() {

                    @Override
                    public void onNativeAdLoaded(@NonNull NativeAd nativeAd) {
                        callback.onUnifiedNativeAdLoaded(nativeAd);
                        nativeAd.setOnPaidEventListener(adValue -> {
                            ERainLogEventManager.logPaidAdImpression(context,
                                    adValue,
                                    id,
                                    nativeAd.getResponseInfo().getMediationAdapterClassName(), AdType.NATIVE);

                            ERainLogEventManager.logPaidAdjustWithToken(adValue, id);
                        });
                    }
                })
                .withAdListener(new AdListener() {
                    @Override
                    public void onAdFailedToLoad(LoadAdError error) {
                        callback.onAdFailedToLoad(error);
                    }

                    @Override
                    public void onAdClicked() {
                        super.onAdClicked();
                        if (disableAdResumeWhenClickAds)
                            AppOpenManager.getInstance().disableAdResumeByClickAction();
                        if (callback != null) {
                            callback.onAdClicked();
                        }
                        ERainLogEventManager.logClickAdsEvent(context, id);
                    }
                })
                .withNativeAdOptions(adOptions)
                .build();
        adLoader.loadAds(getAdRequest(), 5);

    }

    public void loadNativeAdsFullScreen(final Context context, final ShimmerFrameLayout containerShimmer, final FrameLayout frameLayout, final String id, final int layout, final AdCallback callback) {
        if (AdGate.isPurchased(context)) {
            containerShimmer.setVisibility(View.GONE);
            return;
        }
        frameLayout.removeAllViews();
        frameLayout.setVisibility(View.GONE);
        containerShimmer.setVisibility(View.VISIBLE);
        containerShimmer.startShimmer();

        VideoOptions videoOptions = new VideoOptions.Builder()
                .setStartMuted(true)
                .build();

        NativeAdOptions adOptions = new NativeAdOptions.Builder()
                .setMediaAspectRatio(MediaAspectRatio.PORTRAIT)
                .setVideoOptions(videoOptions)
                .build();


        AdLoader adLoader = new AdLoader.Builder(context, id)
                .forNativeAd(nativeAd -> {
                    containerShimmer.stopShimmer();
                    containerShimmer.setVisibility(View.GONE);
                    frameLayout.setVisibility(View.VISIBLE);
                    @SuppressLint("InflateParams") NativeAdView adView = (NativeAdView) LayoutInflater.from(context)
                            .inflate(layout, null);
                    nativeAd.setOnPaidEventListener(adValue -> {

                        ERainLogEventManager.logPaidAdImpression(context,
                                adValue,
                                id,
                                nativeAd.getResponseInfo().getMediationAdapterClassName(), AdType.NATIVE);
                        ERainLogEventManager.logPaidAdjustWithToken(adValue, id);
                    });
                    populateUnifiedNativeAdView(nativeAd, adView);
                    frameLayout.removeAllViews();
                    frameLayout.addView(adView);
                })
                .withAdListener(new AdListener() {
                    @Override
                    public void onAdFailedToLoad(LoadAdError error) {
                        containerShimmer.stopShimmer();
                        containerShimmer.setVisibility(View.GONE);
                        frameLayout.setVisibility(View.GONE);
                    }


                    @Override
                    public void onAdClicked() {
                        super.onAdClicked();
                        if (disableAdResumeWhenClickAds)
                            AppOpenManager.getInstance().disableAdResumeByClickAction();
                        if (callback != null) {
                            callback.onAdClicked();
                        }
                        ERainLogEventManager.logClickAdsEvent(context, id);
                    }
                })
                .withNativeAdOptions(adOptions)
                .build();


        adLoader.loadAds(getAdRequest(), 5);

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


    private RewardedAd rewardedAd;
    // Identity belongs only to the raw member cache; every caller retains its own load callbacks.
    private Object rewardedLoadOwner;

    /**
     * Buffers a rewarded ad; premium users return without a request.
     */
    public void initRewardAds(Context context, String id) {
        if (AdGate.isPurchased(context)) {
            return;
        }
        final Object loadOwner = new Object();
        rewardedLoadOwner = loadOwner;
        this.nativeId = id;
        RewardedAd.load(context, id, getAdRequest(), new RewardedAdLoadCallback() {
            @Override
            public void onAdLoaded(@NonNull RewardedAd rewardedAd) {
                if (rewardedLoadOwner == loadOwner) {
                    rewardedLoadOwner = null;
                    Admob.this.rewardedAd = rewardedAd;
                }
                rewardedAd.setOnPaidEventListener(adValue -> {
                    ERainLogEventManager.logPaidAdImpression(context,
                            adValue,
                            rewardedAd.getAdUnitId(), rewardedAd.getResponseInfo().getMediationAdapterClassName()
                            , AdType.REWARDED);
                    ERainLogEventManager.logPaidAdjustWithToken(adValue, rewardedAd.getAdUnitId());
                });
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                if (rewardedLoadOwner == loadOwner) rewardedLoadOwner = null;
                super.onAdFailedToLoad(loadAdError);
            }
        });
    }

    /**
     * Buffers a rewarded ad; premium users return without a request.
     */
    public void initRewardAds(Context context, String id, AdCallback callback) {
        if (AdGate.isPurchased(context)) {
            return;
        }
        final Object loadOwner = new Object();
        rewardedLoadOwner = loadOwner;
        this.nativeId = id;
        callback.onAdRequestStarted(id);
        RewardedAd.load(context, id, getAdRequest(), new RewardedAdLoadCallback() {
            @Override
            public void onAdLoaded(@NonNull RewardedAd rewardedAd) {
                if (rewardedLoadOwner == loadOwner) {
                    rewardedLoadOwner = null;
                    Admob.this.rewardedAd = rewardedAd;
                }
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
                if (rewardedLoadOwner == loadOwner) {
                    rewardedLoadOwner = null;
                    Admob.this.rewardedAd = null;
                }
                callback.onAdFailedToLoad(loadAdError);
            }
        });
    }

    /**
     * Buffers a rewarded interstitial; premium users return without a request.
     */
    public void getRewardInterstitial(Context context, String id, AdCallback callback) {
        if (AdGate.isPurchased(context)) {
            // No helper wraps this format, so the skip is only visible if reported from here
            AdTracking.skipped(PlacementRegistry.placementOf(id), AdFormat.REWARDED_INTERSTITIAL,
                    AdSkipReason.PURCHASED.getKey());
            return;
        }
        this.nativeId = id;
        callback.onAdRequestStarted(id);
        RewardedInterstitialAd.load(context, id, getAdRequest(), new RewardedInterstitialAdLoadCallback() {
            @Override
            public void onAdLoaded(@NonNull RewardedInterstitialAd rewardedAd) {
                callback.onRewardAdLoaded(rewardedAd);
                rewardedAd.setOnPaidEventListener(adValue -> {
                    ERainLogEventManager.logPaidAdImpression(context,
                            adValue,
                            rewardedAd.getAdUnitId(),
                            rewardedAd.getResponseInfo().getMediationAdapterClassName()
                            , AdType.REWARDED);
                    ERainLogEventManager.logPaidAdjustWithToken(adValue, rewardedAd.getAdUnitId());
                });
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                callback.onAdFailedToLoad(loadAdError);
            }
        });
    }

    public RewardedAd getRewardedAd() {

        return rewardedAd;
    }

    /**
     * Shows the buffered rewarded ad and reports the outcome through {@code adCallback}.
     */
    public void showRewardAds(final Activity context, final RewardCallback adCallback) {
        if (AdGate.isPurchased(context)) {
            adCallback.onUserEarnedReward(null);
            return;
        }
        final RewardedAd ad = rewardedAd;
        if (ad == null) {
            if (FullscreenPresentationOwner.getInstance().isBusy()) {
                if (adCallback != null) adCallback.onAdShowRejected(AdSkipReason.PRESENTATION_BUSY);
                return;
            }
            initRewardAds(context, nativeId);
            adCallback.onRewardedAdFailedToShow(0);
            return;
        }
        RewardPresentation presentation = RewardPresentation.acquire(context, AdFormat.REWARDED,
                ad.getAdUnitId(), adCallback, disableAdResumeWhenClickAds,
                () -> { if (rewardedAd == ad) rewardedAd = null; }, () -> {});
        if (presentation == null) return;
        presentation.show(() -> ad.setFullScreenContentCallback(presentation),
                () -> ad.show(context, presentation));
    }

    /**
     * Shows a rewarded interstitial and reports the outcome through {@code adCallback}.
     */
    public void showRewardInterstitial(final Activity activity, RewardedInterstitialAd rewardedInterstitialAd, final RewardCallback adCallback) {
        if (AdGate.isPurchased(activity)) {
            String adUnitId = rewardedInterstitialAd == null ? nativeId : rewardedInterstitialAd.getAdUnitId();
            AdTracking.skipped(PlacementRegistry.placementOf(adUnitId), AdFormat.REWARDED_INTERSTITIAL,
                    AdSkipReason.PURCHASED.getKey());
            adCallback.onUserEarnedReward(null);
            return;
        }
        if (rewardedInterstitialAd == null) {
            if (FullscreenPresentationOwner.getInstance().isBusy()) {
                if (adCallback != null) adCallback.onAdShowRejected(AdSkipReason.PRESENTATION_BUSY);
                return;
            }
            initRewardAds(activity, nativeId);
            adCallback.onRewardedAdFailedToShow(0);
            return;
        }
        RewardPresentation presentation = RewardPresentation.acquire(activity, AdFormat.REWARDED_INTERSTITIAL,
                rewardedInterstitialAd.getAdUnitId(), adCallback, disableAdResumeWhenClickAds,
                () -> {}, () -> {});
        if (presentation == null) return;
        presentation.show(() -> rewardedInterstitialAd.setFullScreenContentCallback(presentation),
                () -> rewardedInterstitialAd.show(activity, presentation));
    }


    /**
     * Shows a buffered rewarded ad and reports the outcome through {@code adCallback}.
     */
    public void showRewardAds(final Activity context, RewardedAd rewardedAd, final RewardCallback adCallback) {
        if (AdGate.isPurchased(context)) {
            adCallback.onUserEarnedReward(null);
            return;
        }
        if (rewardedAd == null) {
            if (FullscreenPresentationOwner.getInstance().isBusy()) {
                if (adCallback != null) adCallback.onAdShowRejected(AdSkipReason.PRESENTATION_BUSY);
                return;
            }
            initRewardAds(context, nativeId);
            adCallback.onRewardedAdFailedToShow(0);
            return;
        }
        final String adUnitId = rewardedAd.getAdUnitId();
        RewardPresentation presentation = RewardPresentation.acquire(context, AdFormat.REWARDED,
                adUnitId, adCallback, disableAdResumeWhenClickAds,
                () -> { if (Admob.this.rewardedAd == rewardedAd) Admob.this.rewardedAd = null; },
                () -> initRewardAds(context, adUnitId));
        if (presentation == null) return;
        presentation.show(() -> rewardedAd.setFullScreenContentCallback(presentation),
                () -> rewardedAd.show(context, presentation));
    }


    @SuppressLint("HardwareIds")
    public String getDeviceId(Activity activity) {
        String android_id = Settings.Secure.getString(activity.getContentResolver(),
                Settings.Secure.ANDROID_ID);
        return md5(android_id).toUpperCase();
    }

    private String md5(final String s) {
        try {
            // Create MD5 Hash
            MessageDigest digest = MessageDigest
                    .getInstance("MD5");
            digest.update(s.getBytes());
            byte messageDigest[] = digest.digest();

            // Create Hex String
            StringBuffer hexString = new StringBuffer();
            for (int i = 0; i < messageDigest.length; i++) {
                String h = Integer.toHexString(0xFF & messageDigest[i]);
                while (h.length() < 2)
                    h = "0" + h;
                hexString.append(h);
            }
            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return "";
    }

    public final static int SPLASH_ADS = 0;
    public final static int RESUME_ADS = 1;
    private final static int BANNER_ADS = 2;
    private final static int INTERS_ADS = 3;
    private final static int REWARD_ADS = 4;
    private final static int NATIVE_ADS = 5;


    private boolean isInterHigh1Failed = false;
    private boolean isInterHigh2Loaded = false;
    private boolean isInterHigh3Loaded = false;
    private boolean isInterNormalLoaded = false;

    public void loadInterSplashPriority4SameTime(final Context context,
                                                 String idAdsHigh1,
                                                 String idAdsHigh2,
                                                 String idAdsHigh3,
                                                 String idAdsNormal,
                                                 long timeOut,
                                                 long timeDelay,
                                                 AdCallback adListener) {
        isInterHigh1Failed = false;
        isInterHigh2Loaded = false;
        isInterHigh3Loaded = false;
        isInterNormalLoaded = false;
        loadInterSplashHigh1(context, idAdsHigh1, timeOut, timeDelay, false, new AdCallback() {
            @Override
            public void onAdSplashReady() {
                super.onAdSplashReady();
                adListener.onAdSplashHigh1Ready();
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                adListener.onAdPriorityFailedToLoad(i);
            }

            @Override
            public void onNextAction() {
                super.onNextAction();
                if (isInterHigh2Loaded && mInterSplashHigh2 != null) {
                    adListener.onAdSplashHigh2Ready();
                } else if (isInterHigh3Loaded && mInterSplashHigh3 != null) {
                    adListener.onAdSplashHigh3Ready();
                } else if (isInterHigh3Loaded && isInterNormalLoaded) {
                    adListener.onAdSplashNormalReady();
                } else {
                    // waiting for ads loaded
                    isInterHigh1Failed = true;
                }
            }
        });

        loadInterSplashHigh2(context, idAdsHigh2, timeOut, timeDelay, new AdCallback() {
            @Override
            public void onAdSplashReady() {
                super.onAdSplashReady();
                if (isInterHigh1Failed) {
                    adListener.onAdSplashHigh2Ready();
                } else {
                    isInterHigh2Loaded = true;
                }
            }

            @Override
            public void onNextAction() {
                super.onNextAction();
                if (isInterHigh1Failed) {
                    if (isInterHigh3Loaded && mInterSplashHigh3 != null) {
                        adListener.onAdSplashHigh3Ready();
                    } else if (isInterNormalLoaded && mInterSplashNormal != null) {
                        adListener.onAdSplashNormalReady();
                    } else {
                        isInterHigh2Loaded = true;
                    }
                } else {
                    isInterHigh2Loaded = true;
                }
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                adListener.onAdPriorityFailedToLoad(i);
            }
        });

        loadInterSplashHigh3(context, idAdsHigh3, timeOut, timeDelay, new AdCallback() {
            @Override
            public void onAdSplashReady() {
                super.onAdSplashReady();
                if (isInterHigh1Failed && isInterHigh2Loaded) {
                    adListener.onAdSplashHigh3Ready();
                } else {
                    isInterHigh3Loaded = true;
                }
            }

            @Override
            public void onNextAction() {
                super.onNextAction();
                if (isInterHigh1Failed && isInterHigh2Loaded) {
                    if (isInterNormalLoaded && mInterSplashNormal != null) {
                        adListener.onAdSplashNormalReady();
                    } else {
                        isInterHigh3Loaded = true;
                    }
                } else {
                    isInterHigh3Loaded = true;
                }
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                adListener.onAdPriorityFailedToLoad(i);
            }
        });

        loadInterSplashNormal(context, idAdsNormal, timeOut, timeDelay, new AdCallback() {
            @Override
            public void onAdSplashReady() {
                super.onAdSplashReady();
                if (isInterHigh1Failed && isInterHigh2Loaded && isInterHigh3Loaded) {
                    adListener.onAdSplashNormalReady();
                } else {
                    isInterNormalLoaded = true;
                }
            }

            @Override
            public void onNextAction() {
                super.onNextAction();
                if (isInterHigh1Failed && isInterHigh2Loaded && isInterHigh3Loaded) {
                    adListener.onNextAction();
                } else {
                    isInterNormalLoaded = true;
                }
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                adListener.onAdPriorityFailedToLoad(i);
            }
        });
    }


    public void onShowSplashPriority4(AppCompatActivity activity, AdCallback adListener) {
        new SplashPriorityPresentation(activity, adListener, openActivityAfterShowInterAds).showFrom(0);
    }

    private enum SplashSlot { STANDARD, HIGH1, HIGH2, HIGH3, NORMAL }
    private InterstitialPreparation activeSplashPreparation;

    /** Splash keeps its existing pacing and 800/1500 ms timing, with captured cache/UI ownership. */
    private void showBufferedSplash(AppCompatActivity activity, AdCallback callback, SplashSlot slot,
                                    InterstitialAd ad, boolean underAd) {
        if (ad == null) {
            if (callback != null) notifyPresentationCallback(() -> callback.onAdShowRejected(AdSkipReason.NOT_READY));
            return;
        }
        InterstitialPreparation preparation = new InterstitialPreparation();
        preparation.splashSlot = slot;
        prepareInterstitialPresentation(activity, ad, callback, underAd, preparation, false,
                slot == SplashSlot.STANDARD);
    }

    private InterstitialAd splashAdAt(SplashSlot slot) {
        switch (slot) {
            case STANDARD: return mInterstitialSplash;
            case HIGH1: return mInterSplashHigh1;
            case HIGH2: return mInterSplashHigh2;
            case HIGH3: return mInterSplashHigh3;
            case NORMAL: return mInterSplashNormal;
            default: throw new AssertionError(slot);
        }
    }

    private void consumeSplashAd(SplashSlot slot, InterstitialAd ad) {
        switch (slot) {
            case STANDARD: if (mInterstitialSplash == ad) mInterstitialSplash = null; break;
            case HIGH1: if (mInterSplashHigh1 == ad) mInterSplashHigh1 = null; break;
            case HIGH2: if (mInterSplashHigh2 == ad) mInterSplashHigh2 = null; break;
            case HIGH3: if (mInterSplashHigh3 == ad) mInterSplashHigh3 = null; break;
            case NORMAL: if (mInterSplashNormal == ad) mInterSplashNormal = null; break;
        }
    }

    private void cancelSplashTimeout(SplashSlot slot) {
        Handler handler;
        Runnable timeout;
        switch (slot) {
            case STANDARD: handler = handlerTimeout; timeout = rdTimeout; break;
            case HIGH1: handler = handlerTimeoutHigh1; timeout = rdTimeoutHigh1; break;
            case HIGH2: handler = handlerTimeoutHigh2; timeout = rdTimeoutHigh2; break;
            case HIGH3: handler = handlerTimeoutHigh3; timeout = rdTimeoutHigh3; break;
            case NORMAL: handler = handlerTimeoutNormal; timeout = rdTimeoutNormal; break;
            default: throw new AssertionError(slot);
        }
        if (handler != null && timeout != null) handler.removeCallbacks(timeout);
    }

    private void attachSplashPaidCallback(Context host, InterstitialAd ad) {
        ad.setOnPaidEventListener(value -> {
            ERainLogEventManager.logPaidAdImpression(host, value, ad.getAdUnitId(),
                    ad.getResponseInfo().getMediationAdapterClassName(), AdType.INTERSTITIAL);
            ERainLogEventManager.logPaidAdjustWithToken(value, ad.getAdUnitId());
        });
    }

    /** Tier failure and navigation belong to one public call, never to a shared mutable flag. */
    private final class SplashPriorityPresentation {
        private final SplashSlot[] tiers = {SplashSlot.HIGH1, SplashSlot.HIGH2, SplashSlot.HIGH3, SplashSlot.NORMAL};
        private final AppCompatActivity activity;
        private final AdCallback callback;
        private final boolean underAd;
        private boolean ended;
        private boolean nextDelivered;
        private AdError lastFailure;

        SplashPriorityPresentation(AppCompatActivity activity, AdCallback callback, boolean underAd) {
            this.activity = activity;
            this.callback = callback;
            this.underAd = underAd;
        }

        private void nextOnce() {
            if (nextDelivered) return;
            nextDelivered = true;
            if (callback != null) notifyPresentationCallback(callback::onNextAction);
        }

        void showFrom(int start) {
            if (ended) return;
            int candidate = start;
            while (candidate < tiers.length && splashAdAt(tiers[candidate]) == null) candidate++;
            if (candidate == tiers.length) {
                ended = true;
                if (lastFailure == null && !nextDelivered && callback != null) {
                    nextDelivered = true;
                    notifyPresentationCallback(() -> callback.onAdShowRejected(AdSkipReason.NOT_READY));
                } else {
                    if (lastFailure != null && callback != null) {
                        notifyPresentationCallback(() -> callback.onAdFailedToShow(lastFailure));
                    }
                    nextOnce();
                }
                return;
            }
            final int index = candidate;
            final SplashSlot slot = tiers[index];
            showBufferedSplash(activity, new AdCallback() {
                private boolean failed;
                @Override public AdSkipReason getAdShowSkipReason() {
                    return callback == null ? null : callback.getAdShowSkipReason();
                }
                @Override public void onNextAction() { if (!failed && !ended) nextOnce(); }
                @Override public void onAdPresented() {
                    if (!ended && callback != null) notifyPresentationCallback(callback::onAdPresented);
                }
                @Override public void onAdImpression() {
                    if (!ended && callback != null) notifyPresentationCallback(callback::onAdImpression);
                }
                @Override public void onAdClicked() {
                    if (!ended && callback != null) notifyPresentationCallback(callback::onAdClicked);
                }
                @Override public void onAdClosed() {
                    if (ended) return;
                    ended = true;
                    if (callback != null) notifyPresentationCallback(callback::onAdClosed);
                }
                @Override public void onAdShowRejected(@NonNull AdSkipReason reason) {
                    if (ended) return;
                    ended = true;
                    if (callback == null) return;
                    if (!nextDelivered) {
                        nextDelivered = true;
                        notifyPresentationCallback(() -> callback.onAdShowRejected(reason));
                    } else if (lastFailure != null) {
                        // UnderAd already advanced on an actual failed tier. Complete with that
                        // vendor failure; default rejection must not navigate a second time.
                        notifyPresentationCallback(() -> callback.onAdFailedToShow(lastFailure));
                    }
                }
                @Override public void onAdFailedToShow(@Nullable AdError error) {
                    if (failed || ended) return;
                    failed = true;
                    lastFailure = error;
                    if (index < tiers.length - 1) {
                        if (callback != null) notifyPresentationCallback(() -> callback.onAdPriorityFailedToShow(error));
                        showFrom(index + 1);
                    } else {
                        ended = true;
                        if (callback != null) notifyPresentationCallback(() -> callback.onAdFailedToShow(error));
                        nextOnce();
                    }
                }
            }, slot, splashAdAt(slot), underAd);
        }
    }

    private InterstitialAd mInterSplashHigh1;
    private boolean isTimeDelayHigh1 = false;
    private boolean isTimeoutHigh1 = false;
    private Handler handlerTimeoutHigh1;
    private Runnable rdTimeoutHigh1;

    private void loadInterSplashHigh1(final Context context, String id, long timeOut, long timeDelay, boolean showSplashIfReady, AdCallback adListener) {
        isTimeDelayHigh1 = false;
        isTimeoutHigh1 = false;
        if (AdGate.isPurchased(context)) {
            if (adListener != null) {
                adListener.onNextAction();
            }
            return;
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (mInterSplashHigh1 != null) {
                if (showSplashIfReady) {
                    onShowSplashHigh1((AppCompatActivity) context, adListener);
                } else {
                    adListener.onAdSplashReady();
                }
                return;
            }
        }, timeDelay);

        if (timeOut > 0) {
            handlerTimeoutHigh1 = new Handler();
            rdTimeoutHigh1 = () -> {
                Log.e(TAG, "loadSplashInterstitialAdsPriority: on timeout");
                isTimeoutHigh1 = true;
                if (mInterSplashHigh1 != null) {
                    Log.i(TAG, "loadSplashInterstitialAdsPriority:show ad on timeout ");
                    if (showSplashIfReady)
                        onShowSplashHigh1((AppCompatActivity) context, adListener);
                    else
                        adListener.onAdSplashReady();
                    return;
                }
                if (adListener != null) {
                    adListener.onNextAction();
                    isShowLoadingSplash = false;
                }
            };
            handlerTimeoutHigh1.postDelayed(rdTimeoutHigh1, timeOut);
        }
        isShowLoadingSplash = true;
        getInterstitialAds(context, id, new AdCallback() {
            @Override
            public void onInterstitialLoad(@Nullable InterstitialAd interstitialAd) {
                super.onInterstitialLoad(interstitialAd);
                if (isTimeoutHigh1) {
                    return;
                }
                if (interstitialAd != null) {
                    mInterSplashHigh1 = interstitialAd;
                    mInterSplashHigh1.setOnPaidEventListener(new OnPaidEventListener() {
                        @Override
                        public void onPaidEvent(@NonNull AdValue adValue) {
                            ERainLogEventManager.logPaidAdImpression(context,
                                    adValue,
                                    mInterSplashHigh1.getAdUnitId(),
                                    mInterSplashHigh1.getResponseInfo()
                                            .getMediationAdapterClassName(), AdType.INTERSTITIAL);

                            ERainLogEventManager.logPaidAdjustWithToken(adValue, mInterSplashHigh1.getAdUnitId());
                        }
                    });

                    if (isTimeDelayHigh1) {
                        if (showSplashIfReady)
                            onShowSplashHigh1((AppCompatActivity) context, adListener);
                        else
                            adListener.onAdSplashReady();
                        Log.i(TAG, "loadSplashInterstitialAdsPriority:show ad on loaded ");
                    }
                }
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                if (isTimeoutHigh1) {
                    return;
                }
                if (adListener != null) {
                    adListener.onNextAction();
                    if (handlerTimeoutHigh1 != null && rdTimeoutHigh1 != null) {
                        handlerTimeoutHigh1.removeCallbacks(rdTimeoutHigh1);
                    }
                    if (i != null)
                        Log.e(TAG, "loadSplashInterstitialAdsPriority: load fail " + i.getMessage());
                    adListener.onAdFailedToLoad(i);
                }
            }
        });
    }

    private void onShowSplashHigh1(AppCompatActivity activity, AdCallback adListener) {
        showBufferedSplash(activity, adListener, SplashSlot.HIGH1, mInterSplashHigh1, openActivityAfterShowInterAds);
    }

    private InterstitialAd mInterSplashHigh2;
    private boolean isTimeDelayHigh2 = false;
    private boolean isTimeoutHigh2 = false;
    private Handler handlerTimeoutHigh2;
    private Runnable rdTimeoutHigh2;

    private void loadInterSplashHigh2(final Context context, String id, long timeOut, long timeDelay, AdCallback adListener) {
        isTimeDelayHigh2 = false;
        isTimeoutHigh2 = false;
        if (AdGate.isPurchased(context)) {
            if (adListener != null) {
                adListener.onNextAction();
            }
            return;
        }
        new Handler().postDelayed(() -> {
            //check delay show ad splash
            if (mInterSplashHigh2 != null) {
                Log.i(TAG, "mInterSplashHigh2:show ad on delay ");
                adListener.onAdSplashReady();
                return;
            }
            Log.i(TAG, "mInterSplashHigh2: delay validate");
            isTimeDelayHigh2 = true;
        }, timeDelay);
        if (timeOut > 0) {
            handlerTimeoutHigh2 = new Handler();
            rdTimeoutHigh2 = () -> {
                Log.e(TAG, "loadSplashInterstitialAdsMedium: on timeout");
                isTimeoutHigh2 = true;
                if (mInterSplashHigh2 != null) {
                    adListener.onAdSplashReady();
                    return;
                }
                if (adListener != null) {
                    adListener.onNextAction();
                    isShowLoadingSplash = false;
                }
            };
            handlerTimeoutHigh2.postDelayed(rdTimeoutHigh2, timeOut);
        }

        isShowLoadingSplash = true;
        getInterstitialAds(context, id, new AdCallback() {
            @Override
            public void onInterstitialLoad(@Nullable InterstitialAd interstitialAd) {
                super.onInterstitialLoad(interstitialAd);
                if (isTimeoutHigh2) {
                    return;
                }
                if (interstitialAd != null) {
                    mInterSplashHigh2 = interstitialAd;
                    mInterSplashHigh2.setOnPaidEventListener(new OnPaidEventListener() {
                        @Override
                        public void onPaidEvent(@NonNull AdValue adValue) {
                            ERainLogEventManager.logPaidAdImpression(context,
                                    adValue,
                                    mInterSplashHigh2.getAdUnitId(),
                                    mInterSplashHigh2.getResponseInfo()
                                            .getMediationAdapterClassName(), AdType.INTERSTITIAL);

                            ERainLogEventManager.logPaidAdjustWithToken(adValue, mInterSplashHigh2.getAdUnitId());
                        }
                    });

                    if (isTimeDelayHigh2) {
                        adListener.onAdSplashReady();
                        Log.i(TAG, "mInterSplashHigh2:show ad on loaded ");
                    }
                }
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                if (isTimeoutHigh2)
                    return;
                if (adListener != null) {
                    adListener.onNextAction();
                    if (handlerTimeoutHigh2 != null && rdTimeoutHigh2 != null) {
                        handlerTimeoutHigh2.removeCallbacks(rdTimeoutHigh2);
                    }
                    if (i != null)
                        Log.e(TAG, "loadSplashInterstitialAdsMedium: load fail " + i.getMessage());
                    adListener.onAdFailedToLoad(i);
                }
            }
        });
    }

    private void onShowSplashHigh2(AppCompatActivity activity, AdCallback adListener) {
        showBufferedSplash(activity, adListener, SplashSlot.HIGH2, mInterSplashHigh2, openActivityAfterShowInterAds);
    }

    private InterstitialAd mInterSplashHigh3;
    private boolean isTimeDelayHigh3 = false;
    private boolean isTimeoutHigh3 = false;
    private Handler handlerTimeoutHigh3;
    private Runnable rdTimeoutHigh3;

    private void loadInterSplashHigh3(final Context context, String id, long timeOut, long timeDelay, AdCallback adListener) {
        isTimeDelayHigh3 = false;
        isTimeoutHigh3 = false;
        if (AdGate.isPurchased(context)) {
            if (adListener != null) {
                adListener.onNextAction();
            }
            return;
        }
        new Handler().postDelayed(() -> {
            //check delay show ad splash
            if (mInterSplashHigh3 != null) {
                Log.i(TAG, "mInterSplashHigh3:show ad on delay ");
                adListener.onAdSplashReady();
                return;
            }
            Log.i(TAG, "mInterSplashHigh3: delay validate");
            isTimeDelayHigh3 = true;
        }, timeDelay);
        if (timeOut > 0) {
            handlerTimeoutHigh3 = new Handler();
            rdTimeoutHigh3 = () -> {
                Log.e(TAG, "loadSplashInterstitialAdsMedium: on timeout");
                isTimeoutHigh3 = true;
                if (mInterSplashHigh3 != null) {
                    adListener.onAdSplashReady();
                    return;
                }
                if (adListener != null) {
                    adListener.onNextAction();
                    isShowLoadingSplash = false;
                }
            };
            handlerTimeoutHigh3.postDelayed(rdTimeoutHigh3, timeOut);
        }

        isShowLoadingSplash = true;
        getInterstitialAds(context, id, new AdCallback() {
            @Override
            public void onInterstitialLoad(@Nullable InterstitialAd interstitialAd) {
                super.onInterstitialLoad(interstitialAd);
                if (isTimeoutHigh3) {
                    return;
                }
                if (interstitialAd != null) {
                    mInterSplashHigh3 = interstitialAd;
                    mInterSplashHigh3.setOnPaidEventListener(new OnPaidEventListener() {
                        @Override
                        public void onPaidEvent(@NonNull AdValue adValue) {
                            ERainLogEventManager.logPaidAdImpression(context,
                                    adValue,
                                    mInterSplashHigh3.getAdUnitId(),
                                    mInterSplashHigh3.getResponseInfo()
                                            .getMediationAdapterClassName(), AdType.INTERSTITIAL);

                            ERainLogEventManager.logPaidAdjustWithToken(adValue, mInterSplashHigh3.getAdUnitId());
                        }
                    });

                    if (isTimeDelayHigh3) {
                        adListener.onAdSplashReady();
                        Log.i(TAG, "mInterSplashHigh3:show ad on loaded ");
                    }
                }
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                if (isTimeoutHigh3)
                    return;
                if (adListener != null) {
                    adListener.onNextAction();
                    if (handlerTimeoutHigh3 != null && rdTimeoutHigh3 != null) {
                        handlerTimeoutHigh3.removeCallbacks(rdTimeoutHigh3);
                    }
                    if (i != null)
                        Log.e(TAG, "loadSplashInterstitialAdsMedium: load fail " + i.getMessage());
                    adListener.onAdFailedToLoad(i);
                }
            }
        });
    }

    private void onShowSplashHigh3(AppCompatActivity activity, AdCallback adListener) {
        showBufferedSplash(activity, adListener, SplashSlot.HIGH3, mInterSplashHigh3, openActivityAfterShowInterAds);
    }

    private InterstitialAd mInterSplashNormal;
    private boolean isTimeDelayNormal = false;
    private boolean isTimeoutNormal = false;
    private Handler handlerTimeoutNormal;
    private Runnable rdTimeoutNormal;

    private void loadInterSplashNormal(final Context context, String id, long timeOut, long timeDelay, AdCallback adListener) {
        isTimeDelayNormal = false;
        isTimeoutNormal = false;
        if (AdGate.isPurchased(context)) {
            if (adListener != null) {
                adListener.onNextAction();
            }
            return;
        }
        new Handler().postDelayed(() -> {
            //check delay show ad splash
            if (mInterSplashNormal != null) {
                Log.i(TAG, "loadInterSplashNormal:show ad on delay ");
                adListener.onAdSplashReady();
                return;
            }
            Log.i(TAG, "loadInterSplashNormal: delay validate");
            isTimeDelayNormal = true;
        }, timeDelay);
        if (timeOut > 0) {
            handlerTimeoutNormal = new Handler();
            rdTimeoutNormal = () -> {
                Log.e(TAG, "loadSplashInterstitialAdsMedium: on timeout");
                isTimeoutNormal = true;
                if (mInterSplashNormal != null) {
                    adListener.onAdSplashReady();
                    return;
                }
                if (adListener != null) {
                    adListener.onNextAction();
                    isShowLoadingSplash = false;
                }
            };
            handlerTimeoutNormal.postDelayed(rdTimeoutNormal, timeOut);
        }

        isShowLoadingSplash = true;
        getInterstitialAds(context, id, new AdCallback() {
            @Override
            public void onInterstitialLoad(@Nullable InterstitialAd interstitialAd) {
                super.onInterstitialLoad(interstitialAd);
                if (isTimeoutNormal) {
                    return;
                }
                if (interstitialAd != null) {
                    mInterSplashNormal = interstitialAd;
                    mInterSplashNormal.setOnPaidEventListener(new OnPaidEventListener() {
                        @Override
                        public void onPaidEvent(@NonNull AdValue adValue) {
                            ERainLogEventManager.logPaidAdImpression(context,
                                    adValue,
                                    mInterSplashNormal.getAdUnitId(),
                                    mInterSplashNormal.getResponseInfo()
                                            .getMediationAdapterClassName(), AdType.INTERSTITIAL);

                            ERainLogEventManager.logPaidAdjustWithToken(adValue, mInterSplashNormal.getAdUnitId());
                        }
                    });

                    if (isTimeDelayNormal) {
                        adListener.onAdSplashReady();
                        Log.i(TAG, "loadInterSplashNormal:show ad on loaded ");
                    }
                }
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError i) {
                super.onAdFailedToLoad(i);
                if (isTimeoutNormal)
                    return;
                if (adListener != null) {
                    adListener.onNextAction();
                    if (handlerTimeoutNormal != null && rdTimeoutNormal != null) {
                        handlerTimeoutNormal.removeCallbacks(rdTimeoutNormal);
                    }
                    if (i != null)
                        Log.e(TAG, "loadSplashInterstitialAdsMedium: load fail " + i.getMessage());
                    adListener.onAdFailedToLoad(i);
                }
            }
        });
    }

    public void onShowSplashNormal(AppCompatActivity activity, AdCallback adListener) {
        showBufferedSplash(activity, adListener, SplashSlot.NORMAL, mInterSplashNormal, openActivityAfterShowInterAds);
    }

    public void onCheckShowSplashPriority4WhenFail(AppCompatActivity activity, AdCallback callback, int timeDelay) {
        final boolean underAd = openActivityAfterShowInterAds;
        new Handler(activity.getMainLooper()).postDelayed(() -> {
            if (!isShowLoadingSplash() && (mInterSplashHigh1 != null || mInterSplashHigh2 != null || mInterSplashHigh3 != null || mInterSplashNormal != null)) {
                new SplashPriorityPresentation(activity, callback, underAd).showFrom(0);
            } else {
                callback.onNextAction();

            }
        }, timeDelay);
    }
}
