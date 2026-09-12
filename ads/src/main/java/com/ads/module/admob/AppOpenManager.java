package com.ads.module.admob;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.ads.module.R;
import com.ads.module.config.ERainAdConfig;
import com.ads.module.dialog.PrepareLoadingAdsDialog;
import com.ads.module.dialog.ResumeLoadingDialog;
import com.ads.module.event.ERainLogEventManager;
import com.ads.module.funtion.AdCallback;
import com.ads.module.funtion.AdType;
import com.ads.module.helper.AdGate;
import com.ads.module.tracking.AdTracking;

import io.trackkit.AdFormat;
import io.trackkit.PlacementRegistry;
import io.trackkit.Tracker;
import io.trackkit.TrackkitEvents;
import com.ads.module.helper.AdSkipReason;
import com.ads.module.consent.ConsentCenter;
import com.ads.module.config.AdRemoteConfig;
import com.ads.module.config.AdUnitConfig;
import com.google.android.gms.ads.AdActivity;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.appopen.AppOpenAd;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Objects;

public class AppOpenManager implements Application.ActivityLifecycleCallbacks, LifecycleObserver {
    private static final String TAG = "AppOpenManager";
    public static final String AD_UNIT_ID_TEST = "ca-app-pub-3940256099942544/3419835294";

    private static volatile AppOpenManager INSTANCE;
    private AppOpenAd appResumeAd = null;
    private AppOpenAd splashAd = null;
    private AppOpenAd.AppOpenAdLoadCallback loadCallback;

    // Resume fetch state only; raw splash requests retain their own callbacks and buffer.
    private static final long RESUME_FETCH_TIMEOUT_MS = 30_000L;
    private static final int RESUME_MAX_BACKGROUND_REQUESTS = 3;
    private static final long RESUME_BACKGROUND_RETRY_WINDOW_MS = 120_000L;
    private static final long RESUME_OFFLINE_RECHECK_MS = 5_000L;
    private long resumeBackgroundDeadlineMs;
    private int resumeBackgroundRequests;
    private boolean resumeBackground;
    private boolean resumeDispatchAllowed;
    private Runnable pendingBackgroundLoad;
    private static final long RESUME_LOADING_TIMEOUT_MS = 3_000L;
    private static final long RESUME_PRE_SHOW_DELAY_MS = 800L;
    private static final long[] RESUME_FAILURE_BACKOFF_MS = {5_000L, 30_000L, 120_000L};
    private final Handler resumeFetchHandler = new Handler(Looper.getMainLooper());
    private long resumeFetchGeneration;
    private boolean resumeFetchPending;
    private long resumeFetchDeadlineMs;
    private int resumeFailureStreak;
    private long resumeRetryAfterMs;
    private Runnable resumeFetchTimeout;

    private AppOpenAd.AppOpenAdLoadCallback loadCallbackHigh;
    private AppOpenAd.AppOpenAdLoadCallback loadCallbackMedium;
    private AppOpenAd.AppOpenAdLoadCallback loadCallbackAll;

    private AppOpenAd.AppOpenAdLoadCallback loadCallbackOpen;
    private FullScreenContentCallback fullScreenContentCallback;

    private String appResumeAdId;
    /** True once {@link #applyRemoteConfig()} owns the unit; it may then restore a cleared id. */
    private boolean resumeUnitFromConfig;
    private String splashAdId;

    private Activity currentActivity;
    private Activity resumedActivity;
    private long resumeHostGeneration;
    private Object activeResumeAttempt;
    private Runnable pendingResumeShow;
    private Runnable pendingResumeCancellation;

    private Application myApplication;

    private static boolean isShowingAd = false;
    private long appResumeLoadTime = 0;
    private long splashLoadTime = 0;
    private int splashTimeout = 0;

    private boolean isInitialized = false;// on  - off ad resume on app
    private boolean lifecycleHooksAttached = false;
    private boolean isAppResumeEnabled = true;
    private boolean isInterstitialShowing = false;
    private boolean enableScreenContentCallback = false; // default =  true when use splash & false after show splash
    private final AtomicReference<String> pendingResumeSkipReason = new AtomicReference<>();
    private volatile String currentReturnSkipReason;
    private long resumeReturnGeneration;
    private ResumeSkipPolicy resumeSkipPolicy;
    private final List<Class> disabledAppOpenList;
    private Class splashActivity;
    private boolean isTimeout = false;
    private AppOpenAd splashAdHigh = null;
    private AppOpenAd splashAdMedium = null;
    private AppOpenAd splashAdAll = null;

    private AppOpenAd splashAdOpen = null;
    private InterstitialAd splashAdInter = null;

    private int statusHigh = -1;
    private int statusMedium = -1;
    private int statusAll = -1;

    private int statusOpen = -1;
    private int statusInter = -1;

    private final int Type_Loading = 0;
    private final int Type_Load_Success = 1;
    private final int Type_Load_Fail = 2;
    private final int Type_Show_Success = 3;
    private final int Type_Show_Fail = 4;

    private boolean isAppOpenShowed = false;

    private Dialog dialogSplash = null;
    private CountDownTimer timerListenInter = null;
    private long currentTime = 0;
    private long timeRemaining = 0;

    private Handler timeoutHandler;

    public AppOpenAd getSplashAd() {
        return splashAd;
    }

    public void setSplashAd(AppOpenAd splashAd) {
        this.splashAd = splashAd;
    }

    /**
     * Constructor
     */
    private AppOpenManager() {
        disabledAppOpenList = new ArrayList<>();
    }

    public static synchronized AppOpenManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AppOpenManager();
        }
        return INSTANCE;
    }

    /**
     * Starts observing the process lifecycle so the app-open ad can show on resume.
     */
    public void init(Application application, String appOpenAdId) {
        setAppResumeAdId(appOpenAdId);
        // Register unconditionally, even with a blank id: the id usually only arrives later, from
        // remote config via setAppResumeAdId. Gating registration on it left the hooks unattached
        // for the whole process, so app-resume never fired. Requests stay gated in fetchAd.
        isInitialized = true;
        // Separate from isInitialized, which has a public setter partners toggle to switch
        // app-resume off: re-registering the callbacks would double every lifecycle event.
        if (lifecycleHooksAttached) {
            return;
        }
        lifecycleHooksAttached = true;
        this.myApplication = application;
        this.myApplication.registerActivityLifecycleCallbacks(this);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
    }

    public boolean isInitialized() {
        return isInitialized;
    }


    public void setInitialized(boolean initialized) {
        isInitialized = initialized;
        if (!initialized) {
            cancelBackgroundLoad();
            cancelResumeFetch(false);
        }
    }

    /**
     * Controls shown/dismissed/failed forwarding. Resume captures this flag when its attempt
     * starts; changes affect the next attempt. Click/impression forwarding remains independent.
     * Raw splash paths retain their existing live flag checks.
     */
    public void setEnableScreenContentCallback(boolean enableScreenContentCallback) {
        this.enableScreenContentCallback = enableScreenContentCallback;
    }

    public boolean isInterstitialShowing() {
        return isInterstitialShowing;
    }

    /**
     * Marks whether an interstitial/reward presentation or its preparation owns the screen.
     * Only its real completion may clear this bit: elapsed time cannot prove an ad has closed.
     */
    public void setInterstitialShowing(boolean interstitialShowing) {
        isInterstitialShowing = interstitialShowing;
    }

    /** Marks the next host return as coming from an ad click; does not enable app-open mode. */
    public void disableAdResumeByClickAction() {
        skipNextResume("returning_from_ad_click");
    }

    /** Compatibility setter. False clears both the pending skip and the current-return snapshot. */
    public void setDisableAdResumeByClickAction(boolean disabled) {
        if (disabled) {
            disableAdResumeByClickAction();
        } else {
            pendingResumeSkipReason.set(null);
            currentReturnSkipReason = null;
            resumeReturnGeneration++;
        }
    }

    /**
     * Suppresses the next host return, without changing OPEN/WELCOME/NONE mode.
     * A host return consumes the pending reason even while an interstitial or another gate blocks
     * resume. AdActivity is not a host return. An overlay return can be an Activity resume without
     * a process restart; no timer is needed. Repeated calls before a return keep the latest reason.
     */
    public void skipNextResume(@NonNull String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Resume skip reason must not be blank");
        }
        pendingResumeSkipReason.set(reason);
    }

    /**
     * Pure read: repeated queries do not spend the one-shot. Both process observers see the same
     * captured reason during this main-thread lifecycle dispatch. A posted clear ends that
     * snapshot so a later explicit show or overlay return in the same foreground is not blocked.
     */
    @Nullable
    public String getResumeReturnSkipReason() {
        String pending = pendingResumeSkipReason.get();
        return pending != null ? pending : currentReturnSkipReason;
    }

    /** Installs the optional host policy; null removes it. Call from Application setup on main. */
    public void setResumeSkipPolicy(@Nullable ResumeSkipPolicy policy) {
        resumeSkipPolicy = policy;
    }

    /**
     * Pure eligibility shared by app-open and a host's alternate welcome flow. It never consumes
     * a return or emits telemetry. Callers retain their entry mode, Activity exclusions, fullscreen
     * busy and placement/UA checks. App-open's durable switch and unit flag are deliberately absent.
     * An extension failure preserves the previous fail-open behavior; core consent still applies.
     */
    @Nullable
    public String resumeSkipReasonFor(@NonNull Activity activity) {
        if (activity instanceof AdActivity) return "ad_activity";
        String returnReason = getResumeReturnSkipReason();
        if (returnReason != null) return returnReason;
        com.ads.module.helper.AdSkipReason core = AdGate.skipReason(activity, true, true, false);
        if (core != null) return core.getKey();
        ResumeSkipPolicy policy = resumeSkipPolicy;
        if (policy == null) return null;
        try {
            return policy.skipReasonFor(activity);
        } catch (RuntimeException error) {
            Log.w(TAG, "Resume policy failed; keeping prior extension fallback", error);
            return null;
        }
    }

    @Nullable
    private String appOpenPolicySkipReasonFor(@NonNull Activity activity) {
        ResumeSkipPolicy policy = resumeSkipPolicy;
        if (policy == null) return null;
        try {
            return policy.appOpenSkipReasonFor(activity);
        } catch (RuntimeException error) {
            Log.w(TAG, "App-open policy failed; keeping prior extension fallback", error);
            return null;
        }
    }

    private void captureResumeReturn(@Nullable Activity activity) {
        if (activity == null || activity instanceof AdActivity) return;
        String reason = pendingResumeSkipReason.getAndSet(null);
        if (reason != null) {
            currentReturnSkipReason = reason;
            long generation = ++resumeReturnGeneration;
            // Both synchronous process observers (or Activity resume observers for an overlay)
            // can read the snapshot. It must not suppress the rest of the foreground session.
            resumeFetchHandler.post(() -> {
                if (resumeReturnGeneration == generation) currentReturnSkipReason = null;
            });
            Log.d(TAG, "resume skip consumed by host return: " + reason);
        }
    }

    private void reportResumePolicySkip(String reason) {
        AdTracking.skipped(resumePlacementFor(appResumeAdId), AdFormat.APP_OPEN, reason);
    }

    /** The placement key app-resume reads its own configuration under. */
    private static final String RESUME_PLACEMENT = "open_resume";

    /** The registry may not know a blank/late-config unit yet; keep the known placement stable. */
    private static String resumePlacementFor(String adUnitId) {
        return PlacementRegistry.placementOf(adUnitId, RESUME_PLACEMENT);
    }

    /**
     * Resume is the one format that reaches GMA without an {@link com.ads.module.funtion.AdCallback},
     * so {@code TrackingAdCallback} never sees it and these emit its funnel directly. Without them
     * a console gap between matched requests and impressions cannot be attributed to a stage.
     */
    private static void reportResumeRequest(String adUnitId) {
        AdTracking.request(resumePlacementFor(adUnitId), AdFormat.APP_OPEN, adUnitId);
    }

    private static void reportResumeLoaded(String adUnitId, long requestedAtMs) {
        Tracker.track(new TrackkitEvents.Ad.Loaded(resumePlacementFor(adUnitId), AdFormat.APP_OPEN,
                adUnitId, System.currentTimeMillis() - requestedAtMs));
    }

    private static void reportResumeLoadFailed(String adUnitId, Integer errorCode) {
        Tracker.track(new TrackkitEvents.Ad.LoadFailed(
                resumePlacementFor(adUnitId), AdFormat.APP_OPEN, adUnitId, errorCode));
    }

    private static void reportResumeShown(String adUnitId) {
        Tracker.track(new TrackkitEvents.Ad.Show(
                resumePlacementFor(adUnitId), AdFormat.APP_OPEN, adUnitId));
    }

    private static void reportResumeShowFailed(String adUnitId, Integer errorCode) {
        Tracker.track(new TrackkitEvents.Ad.ShowFailed(
                resumePlacementFor(adUnitId), AdFormat.APP_OPEN, adUnitId, errorCode));
    }

    /**
     * True while an app-open ad owns the screen.
     */
    public boolean isShowingAd() {
        return isShowingAd;
    }

    /** A dispatched ad stays busy until its actual terminal callback, without a time-based unlock. */
    private static void setShowingAd(boolean showing) {
        isShowingAd = showing;
    }

    /**
     * Suppresses the resume ad whenever {@code activityClass} is on top.
     */
    public void disableAppResumeWithActivity(Class activityClass) {
        Log.d(TAG, "disableAppResumeWithActivity: " + activityClass.getName());
        disabledAppOpenList.add(activityClass);
    }

    public void enableAppResumeWithActivity(Class activityClass) {
        Log.d(TAG, "enableAppResumeWithActivity: " + activityClass.getName());
        disabledAppOpenList.remove(activityClass);
    }

    /**
     * The activity currently on top, tracked from {@code onActivityStarted}.
     * <p>
     * Exposed because a host running its own resume flow needs the same activity this manager
     * sees: a tracker populated in {@code onActivityResumed} is still null when the process
     * ON_START fires, so the host's flow silently skipped the first foreground of every process.
     */
    public Activity getCurrentActivity() {
        return currentActivity;
    }

    /**
     * Whether a resume ad is suppressed while {@code activity} is on top.
     * <p>
     * The one place this is answered. A host that runs its own resume flow asks here rather than
     * keeping a second list: the two drifted, and a screen excluded from app-open ads still got a
     * welcome ad launched over it.
     */
    public boolean isResumeSuppressedFor(Activity activity) {
        if (activity == null) {
            return true;
        }
        for (Class activityClass : disabledAppOpenList) {
            if (activityClass.isInstance(activity)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Turns app-resume ads off for the rest of the process.
     * <p>
     * This is the durable entry-mode switch, owned by whoever decides that mode — in this template
     * the splash, from remote config. It is the wrong tool for "not on this one return": use
     * {@link #disableAdResumeByClickAction()} there, because {@link #enableAppResume()} has no
     * memory of what the mode was and would switch app-resume back on for a session that had it
     * off. That is what the splash-interstitial callbacks and the settings screen used to do.
     */
    public void disableAppResume() {
        isAppResumeEnabled = false;
        cancelBackgroundLoad();
        cancelResumeFetch(false);
    }

    /**
     * Enables resume ads. The next eligible background stay owns the first load; enabling
     * during splash or an Activity transition never warms the buffer.
     * @see #disableAppResume() — this is not an "undo my suppression".
     */
    public void enableAppResume() {
        isAppResumeEnabled = true;
    }

    public void setSplashActivity(Class splashActivity, String adId, int timeoutInMillis) {
        this.splashActivity = splashActivity;
        splashAdId = adId;
        this.splashTimeout = timeoutInMillis;
    }

    /**
     * Re-points the app-resume unit at what {@code open_resume} currently declares, including an
     * empty id when that placement is switched off.
     *
     * Two no-ops keep this from taking over a decision it was not given: until a resume unit
     * exists, because opting into app-resume stays the partner's own explicit call; and unless
     * {@code open_resume} actually carries an ad unit id, because an entry that only tunes
     * {@code app_resume_load_delay_ms} is not a statement about which unit to request.
     *
     * Once it has set the unit it keeps setting it, empty id included. Without that, switching
     * {@code isEnable} off would empty the id and then re-tripping the first no-op forever, so
     * switching it back on could never take effect in that process.
     */
    public void applyRemoteConfig() {
        // A remote refresh lands on whatever thread fetched it; setAppResumeAdId is main-only.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            resumeFetchHandler.post(this::applyRemoteConfig);
            return;
        }
        if (!resumeUnitFromConfig && (appResumeAdId == null || appResumeAdId.isEmpty())) return;
        AdUnitConfig unit = AdRemoteConfig.getInstance().getAds().get(RESUME_PLACEMENT);
        if (unit == null || unit.getWaterfallIds().isEmpty()) return;
        List<String> ids = AdGate.adUnitIds(RESUME_PLACEMENT);
        resumeUnitFromConfig = true;
        setAppResumeAdId(ids.isEmpty() ? "" : ids.get(0));
    }

    /** Changes the unit on main; pending results for the previous unit cannot fill this buffer. */
    public void setAppResumeAdId(String appResumeAdId) {
        if (Objects.equals(this.appResumeAdId, appResumeAdId)) return;
        cancelResumeFetch(true);
        appResumeAd = null;
        this.appResumeAdId = appResumeAdId;
    }

    /**
     * Registers the listener for future resume attempts. Each resume keeps its captured listener
     * through completion; replacing/removing it does not reroute an in-progress attempt.
     * RuntimeExceptions from that listener are logged without treating them as vendor failures.
     * Raw splash paths retain their existing live listener behavior.
     */
    public void setFullScreenContentCallback(FullScreenContentCallback callback) {
        this.fullScreenContentCallback = callback;
    }

    /** Clears registration for future resume attempts; an active attempt retains its listener. */
    public void removeFullScreenContentCallback() {
        this.fullScreenContentCallback = null;
    }

    /**
     * Drops every buffered app-open ad — call it when the user turns premium, or the ad loaded
     * before they paid is still shown to them.
     */
    public void releaseCachedAds() {
        cancelResumeFetch(true);
        appResumeAd = null;
        splashAd = null;
        splashAdHigh = null;
        splashAdMedium = null;
        splashAdAll = null;
        splashAdOpen = null;
        splashAdInter = null;
    }

    /**
     * Resume loads belong to a bounded background opportunity, including offline recovery and
     * failure backoff. Existing cache/in-flight work wins over new-request gates. Foreground
     * cancels scheduling, never a dispatched result; a timeout allows retry but a late fill can
     * still be cached until superseded or invalidated. No timer ever shows an ad.
     * Resume dispatch/configuration/cache mutations run on main. Raw splash keeps its own path.
     */
    public void fetchAd(final boolean isSplash) {
        if (!isSplash && Looper.myLooper() != Looper.getMainLooper()) {
            resumeFetchHandler.post(() -> fetchAd(false));
            return;
        }
        Log.d(TAG, "fetchAd: isSplash = " + isSplash);
        // Only the delayed background opportunity may dispatch. Public/legacy fetch calls
        // cannot create startup, foreground, post-show or unbounded background reloads.
        if (!isSplash && !resumeDispatchAllowed) return;
        // Existing work is independent of whether a new request could be sent right now.
        if (isAdAvailable(isSplash) || (!isSplash && resumeFetchPending)) return;
        if (!isSplash) {
            if (!canFetchResume(false)) return;
            if (!AdGate.isNetworkAvailable(myApplication)) {
                scheduleBackgroundLoad(RESUME_OFFLINE_RECHECK_MS);
                return;
            }
        }
        // GMA rejects a blank unit with "Cannot determine request type" on every call.
        String adUnitId = isSplash ? splashAdId : appResumeAdId;
        if (adUnitId == null || adUnitId.trim().isEmpty()) {
            Log.d(TAG, "fetchAd: no ad unit set yet (isSplash = " + isSplash + ")");
            return;
        }

        if (isSplash) {
            Context networkContext = currentActivity != null ? currentActivity : myApplication;
            if (networkContext != null && !AdGate.isNetworkAvailable(networkContext)) {
                Log.d(TAG, "fetchAd: raw splash gate=offline");
                return;
            }
        }

        if (!isSplash) {
            if (SystemClock.elapsedRealtime() < resumeRetryAfterMs) {
                Log.d(TAG, "fetchAd: resume backoff");
                scheduleBackgroundLoad(resumeRetryAfterMs - SystemClock.elapsedRealtime());
                return;
            }
        }
        final Application requestApplication = myApplication;
        final boolean personalized = ConsentCenter.canPersonalize();
        final long generation = isSplash ? 0 : resumeFetchGeneration + 1;
        // One terminal event per request, the same latch TrackingAdCallback keeps for every
        // other format: a superseded request must not report both an outcome and a retry's.
        final AtomicBoolean resumeTerminalReported = new AtomicBoolean(false);
        // A terminal is only meaningful for a request the funnel has actually seen; the gates
        // below can still refuse after this point, and those exits report nothing at all.
        final AtomicBoolean resumeRequestReported = new AtomicBoolean(false);
        final long resumeRequestedAtMs = (new Date()).getTime();
        final AppOpenAd.AppOpenAdLoadCallback requestCallback =
                new AppOpenAd.AppOpenAdLoadCallback() {

                    /**
                     * Called when an app open ad has loaded.
                     *
                     * @param ad the loaded app open ad.
                     */


                    @Override
                    public void onAdLoaded(AppOpenAd ad) {
                        Log.d(TAG, "onAppOpenAdLoaded: isSplash = " + isSplash);
                        if (!isSplash) {
                            if (!resumeTerminalReported.compareAndSet(false, true)) return;
                            reportResumeLoaded(adUnitId, resumeRequestedAtMs);
                            if (!canContinueResumeFetch(generation, adUnitId, personalized)) {
                                // A vendor fill is distinct from a result accepted into our cache.
                                AdTracking.skipped(resumePlacementFor(adUnitId), AdFormat.APP_OPEN,
                                        "fill_discarded");
                                return;
                            }
                            ad.setOnPaidEventListener(adValue -> {
                                ERainLogEventManager.logPaidAdImpression(requestApplication.getApplicationContext(),
                                        adValue,
                                        ad.getAdUnitId(),
                                        ad.getResponseInfo()
                                                .getMediationAdapterClassName(), AdType.APP_OPEN);
                            });
                            AppOpenManager.this.appResumeAd = ad;
                            // A delayed callback must not renew the ad's original lifetime.
                            AppOpenManager.this.appResumeLoadTime = resumeRequestedAtMs;
                            cancelResumeFetch(true);
                        } else {
                            AppOpenManager.this.splashAd = ad;

                            // Luan
                            AppOpenManager.this.setSplashAd(ad);

                            AppOpenManager.this.splashAd.setOnPaidEventListener(adValue -> {
                                ERainLogEventManager.logPaidAdImpression(myApplication.getApplicationContext(),
                                        adValue,
                                        ad.getAdUnitId(),
                                        ad.getResponseInfo()
                                                .getMediationAdapterClassName(), AdType.APP_OPEN);
                            });
                            AppOpenManager.this.splashLoadTime = (new Date()).getTime();
                        }


                    }


                    /**
                     * Called when an app open ad has failed to load.
                     *
                     * @param loadAdError the error.
                     */
                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        Log.d(TAG, "onAppOpenAdFailedToLoad: isSplash" + isSplash + " message " + loadAdError.getMessage());
                        if (!isSplash) {
                            if (!resumeTerminalReported.compareAndSet(false, true)) return;
                            reportResumeLoadFailed(adUnitId, loadAdError.getCode());
                        }
                        if (!isSplash && ownsResumeFetch(generation)) {
                            if (!canFetchResume(false)) {
                                if (ownsResumeFetch(generation)) cancelResumeFetch(false);
                            } else failResumeFetch(generation, "vendor_code=" + loadAdError.getCode());
                        }
                    }


                };
        if (isSplash) loadCallback = requestCallback;
        if (currentActivity != null) {
            if (AdGate.isPurchased(currentActivity)) {
                if (!isSplash && ownsResumeFetch(generation)) cancelResumeFetch(false);
                return;
            }
            if (Arrays.asList(currentActivity.getResources().getStringArray(R.array.list_id_test)).contains(adUnitId)) {
                try {
                    showTestIdAlert(currentActivity, isSplash, adUnitId);
                } catch (RuntimeException error) {
                    if (isSplash) throw error;
                    Log.w(TAG, "resume test-id notification unavailable", error);
                }
            }
        }
        try {
            AdRequest request = getAdRequest();
            if (!isSplash) {
                if (!Objects.equals(appResumeAdId, adUnitId) || !canFetchResume(false)
                        || ConsentCenter.canPersonalize() != personalized) return;
                if (!AdGate.isNetworkAvailable(myApplication)) {
                    scheduleBackgroundLoad(RESUME_OFFLINE_RECHECK_MS);
                    return;
                }
                // Ownership changes only when a replacement is actually about to dispatch.
                resumeFetchGeneration = generation;
                resumeFetchPending = true;
                resumeFetchDeadlineMs = SystemClock.elapsedRealtime() + RESUME_FETCH_TIMEOUT_MS;
                resumeFetchTimeout = () -> {
                    if (!ownsResumeFetch(generation)) return;
                    if (!canFetchResume(false)) {
                        if (ownsResumeFetch(generation)) cancelResumeFetch(false);
                    } else {
                        failResumeFetch(generation, "timeout", true);
                        AdTracking.skipped(resumePlacementFor(adUnitId), AdFormat.APP_OPEN, "load_timeout");
                    }
                };
                resumeFetchHandler.postDelayed(resumeFetchTimeout, RESUME_FETCH_TIMEOUT_MS);
                Log.d(TAG, "resume load dispatch generation=" + generation);
                // After every gate, so the funnel counts requests that actually reach GMA.
                resumeRequestReported.set(true);
                resumeBackgroundRequests++;
                reportResumeRequest(adUnitId);
            }
            AppOpenAd.load(requestApplication, adUnitId, request, requestCallback);
        } catch (RuntimeException error) {
            if (isSplash) throw error;
            if (!resumeRequestReported.get()) {
                // Preparation sent nothing. Keep any previous late result and the bounded
                // opportunity, without inventing a vendor request or failure in the funnel.
                Log.w(TAG, "resume request preparation failed", error);
                scheduleBackgroundLoad(RESUME_OFFLINE_RECHECK_MS);
                return;
            }
            // A reported request must always reach a terminal event, or the funnel keeps a
            // request that no outcome ever answers and every rate computed from it is wrong.
            if (resumeRequestReported.get() && resumeTerminalReported.compareAndSet(false, true)) {
                reportResumeLoadFailed(adUnitId, null);
            }
            failResumeFetch(generation, "dispatch_error=" + error.getClass().getSimpleName());
        }
    }

    private boolean canFetchResume(boolean checkNetwork) {
        if (myApplication == null || !isInitialized) return false;
        AdSkipReason reason = AdGate.skipReason(myApplication, isAppResumeEnabled,
                AdGate.placementPassesUaGate(RESUME_PLACEMENT), checkNetwork);
        if (reason != null) Log.d(TAG, "fetchAd: resume gate=" + reason.getKey());
        return reason == null;
    }

    private boolean ownsResumeFetch(long generation) {
        return resumeFetchPending && resumeFetchGeneration == generation;
    }

    private boolean canContinueResumeFetch(long generation, String adUnitId, boolean personalized) {
        // A timeout frees the loading slot, but only replacement/invalidation revokes a result.
        if (resumeFetchGeneration != generation || resumeFetchDeadlineMs == 0) return false;
        if (SystemClock.elapsedRealtime() - (resumeFetchDeadlineMs - RESUME_FETCH_TIMEOUT_MS)
                >= 4 * 60 * 60 * 1_000L) {
            cancelResumeFetch(false);
            return false;
        }
        if (!Objects.equals(appResumeAdId, adUnitId) || !canFetchResume(false)
                || ConsentCenter.canPersonalize() != personalized) {
            if (resumeFetchGeneration == generation) cancelResumeFetch(false);
            return false;
        }
        return resumeFetchGeneration == generation;
    }

    private void cancelResumeFetch(boolean resetBackoff) {
        resumeFetchGeneration++;
        resumeFetchPending = false;
        resumeFetchDeadlineMs = 0;
        if (resumeFetchTimeout != null) resumeFetchHandler.removeCallbacks(resumeFetchTimeout);
        resumeFetchTimeout = null;
        if (resetBackoff) {
            clearBackgroundLoadSchedule();
            resumeBackgroundDeadlineMs = 0;
            resumeFailureStreak = 0;
            resumeRetryAfterMs = 0;
        }
    }

    private void failResumeFetch(long generation, String reason) {
        failResumeFetch(generation, reason, false);
    }

    private void failResumeFetch(long generation, String reason, boolean retainLateResult) {
        if (!ownsResumeFetch(generation)) return;
        long delayMs = RESUME_FAILURE_BACKOFF_MS[
                Math.min(resumeFailureStreak, RESUME_FAILURE_BACKOFF_MS.length - 1)];
        resumeFailureStreak = Math.min(resumeFailureStreak + 1, RESUME_FAILURE_BACKOFF_MS.length);
        if (retainLateResult) {
            resumeFetchPending = false;
            if (resumeFetchTimeout != null) resumeFetchHandler.removeCallbacks(resumeFetchTimeout);
            resumeFetchTimeout = null;
        } else {
            cancelResumeFetch(false);
        }
        resumeRetryAfterMs = SystemClock.elapsedRealtime() + delayMs;
        Log.w(TAG, "resume load failed " + reason + "; retry after " + delayMs + "ms");
        scheduleBackgroundLoad(delayMs);
    }

    @SuppressLint("MissingPermission")
    private void showTestIdAlert(Context context, boolean isSplash, String id) {
        Notification notification = new NotificationCompat.Builder(context, "warning_ads")
                .setContentTitle("Found test ad id")
                .setContentText((isSplash ? "Splash Ads: " : "AppResume Ads: " + id))
                .setSmallIcon(R.drawable.ic_warning)
                .build();

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        notification.flags |= Notification.FLAG_AUTO_CANCEL;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("warning_ads",
                    "Warning Ads",
                    NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
        notificationManager.notify(isSplash ? Admob.SPLASH_ADS : Admob.RESUME_ADS, notification);
    }

    /**
     * Creates and returns ad request.
     */
    private AdRequest getAdRequest() {
        return new AdRequest.Builder().build();
    }

    private boolean wasLoadTimeLessThanNHoursAgo(long loadTime, long numHours) {
        long dateDifference = (new Date()).getTime() - loadTime;
        long numMilliSecondsPerHour = 3600000;
        return (dateDifference < (numMilliSecondsPerHour * numHours));
    }

    /**
     * Utility method that checks if ad exists and can be shown.
     */
    public boolean isAdAvailable(boolean isSplash) {
        long loadTime = isSplash ? splashLoadTime : appResumeLoadTime;
        boolean wasLoadTimeLessThanNHoursAgo = wasLoadTimeLessThanNHoursAgo(loadTime, 4);
        Log.d(TAG, "isAdAvailable: " + wasLoadTimeLessThanNHoursAgo);
        return (isSplash ? splashAd != null : appResumeAd != null)
                && wasLoadTimeLessThanNHoursAgo;
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
        // Activity start precedes process ON_START. Cancel here too so a quick return cannot
        // lose a race with the delayed background runnable on the main queue.
        cancelBackgroundLoad();
        currentActivity = activity;
        Log.d(TAG, "onActivityStarted: " + currentActivity);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        currentActivity = activity;
        resumedActivity = activity;
        Log.d(TAG, "onActivityResumed: " + currentActivity);
        captureResumeReturn(activity);

    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (dialog != null && dialog.getOwnerActivity() == activity) {
            dismissResumeDialog(dialog);
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        if (resumedActivity == activity) {
            resumedActivity = null;
            resumeHostGeneration++;
            if (pendingResumeCancellation != null) pendingResumeCancellation.run();
        }
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (resumedActivity == activity) {
            resumedActivity = null;
            resumeHostGeneration++;
        }
        // Only forget the activity we are actually tracking: destroys arrive after the next
        // activity has already started, and clearing unconditionally left the manager believing
        // no activity existed mid-session.
        if (currentActivity == activity) {
            currentActivity = null;
            Log.d(TAG, "onActivityDestroyed: null");
        }
    }

    public void showAdIfAvailable(final boolean isSplash) {
        if (currentActivity == null || AdGate.isPurchased(currentActivity)) {
            if (fullScreenContentCallback != null && enableScreenContentCallback) {
                fullScreenContentCallback.onAdDismissedFullScreenContent();
            }
            return;
        }

        if (!isSplash) {
            String reason = !isAppResumeEnabled ? "disabled_config" : resumeSkipReasonFor(currentActivity);
            if (reason == null) reason = appOpenPolicySkipReasonFor(currentActivity);
            if (reason != null) {
                Log.d(TAG, "showAdIfAvailable: resume policy blocked " + reason);
                reportResumePolicySkip(reason);
                if (fullScreenContentCallback != null && enableScreenContentCallback) {
                    fullScreenContentCallback.onAdDismissedFullScreenContent();
                }
                return;
            }
        }

        Log.d(TAG, "showAdIfAvailable: " + ProcessLifecycleOwner.get().getLifecycle().getCurrentState());
        Log.d(TAG, "showAd isSplash: " + isSplash);
        if (!ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            Log.d(TAG, "showAdIfAvailable: return");
            if (fullScreenContentCallback != null && enableScreenContentCallback) {
                fullScreenContentCallback.onAdDismissedFullScreenContent();
            }

            return;
        }

        if (!isShowingAd && isAdAvailable(isSplash)) {
            Log.d(TAG, "Will show ad isSplash:" + isSplash);
            if (isSplash) {
                showAdsWithLoading();
            } else {
                showResumeAds();
            }

        } else {
            Log.d(TAG, "Ad is not ready");
            if (isSplash && isShowingAd && isAdAvailable(true)) {
                showAdsWithLoading();
            }
        }
    }

    private void showAdsWithLoading() {
        if (ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            dialogSplash = null;
            try {
                dialogSplash = new PrepareLoadingAdsDialog(currentActivity);
                try {
                    dialogSplash.show();
                } catch (Exception e) {
                    if (fullScreenContentCallback != null && enableScreenContentCallback) {
                        fullScreenContentCallback.onAdDismissedFullScreenContent();
                    }
                    return;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            final Dialog finalDialog = dialogSplash;
            new Handler().postDelayed(() -> {
                if (splashAd != null) {
                    splashAd.setFullScreenContentCallback(
                            new FullScreenContentCallback() {
                                @Override
                                public void onAdDismissedFullScreenContent() {
                                    // Set the reference to null so isAdAvailable() returns false.
                                    appResumeAd = null;
                                    if (fullScreenContentCallback != null && enableScreenContentCallback) {
                                        fullScreenContentCallback.onAdDismissedFullScreenContent();
                                        enableScreenContentCallback = false;
                                    }
                                    setShowingAd(false);
                                    fetchAd(true);
                                }

                                @Override
                                public void onAdFailedToShowFullScreenContent(AdError adError) {
                                    if (fullScreenContentCallback != null && enableScreenContentCallback) {
                                        fullScreenContentCallback.onAdFailedToShowFullScreenContent(adError);
                                    }
                                }

                                @Override
                                public void onAdShowedFullScreenContent() {
                                    if (fullScreenContentCallback != null && enableScreenContentCallback) {
                                        fullScreenContentCallback.onAdShowedFullScreenContent();
                                    }
                                    setShowingAd(true);
                                    splashAd = null;
                                }


                                @Override
                                public void onAdClicked() {
                                    super.onAdClicked();
                                    if (currentActivity != null) {
                                        ERainLogEventManager.logClickAdsEvent(currentActivity, splashAdId);
                                        if (fullScreenContentCallback != null) {
                                            fullScreenContentCallback.onAdClicked();
                                        }
                                    }
                                }
                            });
                    splashAd.setImmersiveMode(true);
                    splashAd.show(currentActivity);
                }
            }, 800);
        }
    }

    Dialog dialog = null;

    /** Live admission for the resume path only; raw splash retains its existing flow. */
    private boolean canShowResumeOn(Activity host) {
        return isInitialized && isAppResumeEnabled && !isInterstitialShowing
                && host != null && host == currentActivity && host == resumedActivity
                && !host.isFinishing() && !host.isDestroyed() && !(host instanceof AdActivity)
                && ProcessLifecycleOwner.get().getLifecycle().getCurrentState()
                        .isAtLeast(Lifecycle.State.RESUMED)
                && !isResumeSuppressedFor(host)
                && resumeSkipReasonFor(host) == null && appOpenPolicySkipReasonFor(host) == null;
    }

    private void showResumeAds() {
        final Activity host = currentActivity;
        final AppOpenAd ad = appResumeAd;
        if (isShowingAd || activeResumeAttempt != null || ad == null
                || !isAdAvailable(false) || !canShowResumeOn(host)) return;

        final String unit = appResumeAdId;
        final long generation = resumeFetchGeneration;
        final long hostGeneration = resumeHostGeneration;
        final FullScreenContentCallback delegate = fullScreenContentCallback;
        final boolean forwardContent = enableScreenContentCallback;
        final Object attempt = new Object();
        activeResumeAttempt = attempt;
        setShowingAd(true);

        Dialog loading = null;
        try {
            loading = new ResumeLoadingDialog(host);
            loading.setOwnerActivity(host);
            dialog = loading;
            loading.show();
        } catch (RuntimeException error) {
            // The loading indicator is cosmetic; a usable vendor ad does not depend on it.
            Log.w(TAG, "Resume loading dialog unavailable", error);
        }
        final Dialog ownedDialog = loading;
        final FullScreenContentCallback callback = new FullScreenContentCallback() {
            private boolean shown;

            @Override
            public void onAdDismissedFullScreenContent() {
                if (!finishResumeAttempt(attempt, ownedDialog)) return;
                if (delegate != null && forwardContent) forwardResumeCallback(() -> delegate.onAdDismissedFullScreenContent());
            }

            @Override
            public void onAdFailedToShowFullScreenContent(AdError error) {
                if (!finishResumeAttempt(attempt, ownedDialog)) return;
                reportResumeShowFailed(unit, error == null ? null : error.getCode());
                if (delegate != null && forwardContent) forwardResumeCallback(() -> delegate.onAdFailedToShowFullScreenContent(error));
            }

            @Override
            public void onAdShowedFullScreenContent() {
                if (activeResumeAttempt != attempt || shown) return;
                shown = true;
                reportResumeShown(unit);
                // GMA can report shown before its content paints. Keep the loading backdrop
                // until the host stops, the ad ends, or the cosmetic timeout expires.
                // GMA may already have paused the host; only this attempt's identity matters now.
                if (delegate != null && forwardContent) forwardResumeCallback(() -> delegate.onAdShowedFullScreenContent());
            }

            @Override
            public void onAdClicked() {
                if (activeResumeAttempt != attempt) return;
                disableAdResumeByClickAction();
                ERainLogEventManager.logClickAdsEvent(host, unit);
                if (delegate != null) forwardResumeCallback(() -> delegate.onAdClicked());
            }

            @Override
            public void onAdImpression() {
                if (activeResumeAttempt == attempt && delegate != null) forwardResumeCallback(() -> delegate.onAdImpression());
            }
        };
        final Runnable cancelBeforeShow = () -> {
            if (finishResumeAttempt(attempt, ownedDialog) && delegate != null && forwardContent) {
                forwardResumeCallback(() -> delegate.onAdDismissedFullScreenContent());
            }
        };
        final Runnable dispatch = () -> {
            if (activeResumeAttempt != attempt) return;
            pendingResumeShow = null;
            pendingResumeCancellation = null;
            try {
                // Recheck after the loading interval; never spend a fill on a stale return.
                if (!canShowResumeOn(host) || hostGeneration != resumeHostGeneration
                        || appResumeAd != ad || !isAdAvailable(false) || generation != resumeFetchGeneration
                        || !Objects.equals(unit, appResumeAdId)) {
                    cancelBeforeShow.run();
                    return;
                }
                appResumeAd = null;
                ad.show(host);
            } catch (RuntimeException error) {
                callback.onAdFailedToShowFullScreenContent(
                        new AdError(0, "App-open show threw: " + error.getClass().getSimpleName(), "ERainStudio"));
            } finally {
                // GMA opens asynchronously. Bound only the cosmetic window, never ad ownership.
                if (ownedDialog != null && dialog == ownedDialog && ownedDialog.isShowing()) {
                    resumeFetchHandler.postDelayed(() -> dismissResumeDialog(ownedDialog), RESUME_LOADING_TIMEOUT_MS);
                }
            }
        };
        try {
            ad.setFullScreenContentCallback(callback);
            try {
                ad.setImmersiveMode(true);
            } catch (RuntimeException error) {
                Log.w(TAG, "Resume immersive mode unavailable", error);
            }
            // Dialog/vendor setup may synchronously change the host, policy, unit or cache.
            // Its own dialog takes focus, so host window focus alone is not a rejection here.
            if (activeResumeAttempt != attempt || !canShowResumeOn(host)
                    || hostGeneration != resumeHostGeneration
                    || appResumeAd != ad || !isAdAvailable(false) || generation != resumeFetchGeneration
                    || !Objects.equals(unit, appResumeAdId)) {
                cancelBeforeShow.run();
                return;
            }
            if (ownedDialog != null && ownedDialog.isShowing()) {
                ownedDialog.setOnCancelListener(ignored -> {
                    if (pendingResumeShow == dispatch) cancelBeforeShow.run();
                });
                pendingResumeShow = dispatch;
                pendingResumeCancellation = cancelBeforeShow;
                resumeFetchHandler.postDelayed(dispatch, RESUME_PRE_SHOW_DELAY_MS);
            } else {
                dispatch.run();
            }
        } catch (RuntimeException error) {
            callback.onAdFailedToShowFullScreenContent(
                    new AdError(0, "App-open show threw: " + error.getClass().getSimpleName(), "ERainStudio"));
        }
    }

    private void forwardResumeCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException error) {
            // Host code is not a vendor show failure and cannot release a visible ad.
            Log.w(TAG, "App-open host callback failed", error);
        }
    }

    private boolean finishResumeAttempt(Object attempt, Dialog ownedDialog) {
        if (activeResumeAttempt != attempt) return false;
        if (pendingResumeShow != null) resumeFetchHandler.removeCallbacks(pendingResumeShow);
        pendingResumeShow = null;
        pendingResumeCancellation = null;
        activeResumeAttempt = null;
        setShowingAd(false);
        dismissResumeDialog(ownedDialog);
        return true;
    }

    public void loadSplashOpenHighFloor(Class splashActivity, Activity activity, String idOpenHigh, String idOpenMedium, String idOpenAll, int timeOutOpen, AdCallback adListener) {
        isAppOpenShowed = false;

        statusHigh = Type_Loading;
        statusMedium = Type_Loading;
        statusAll = Type_Loading;

        if (AdGate.isPurchased(activity)) {
            if (adListener != null) {
                adListener.onNextAction();
            }
            return;
        }

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (adListener != null && !isAppOpenShowed) {
                    isAppOpenShowed = true;
                    adListener.onNextAction();
                }
            }
        }, timeOutOpen);

        AppOpenManager.getInstance().setSplashActivity(splashActivity, idOpenHigh, timeOutOpen);

        // load Open Splash High
        loadCallbackHigh =
                new AppOpenAd.AppOpenAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull AppOpenAd appOpenAd) {
                        Log.d(TAG, "loadCallbackHigh: onAdLoaded");
                        if (adListener != null) {
                            adListener.onAdLoadedHigh();
                        }

                        appOpenAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                            @Override
                            public void onAdClicked() {
                                super.onAdClicked();
                                disableAdResumeByClickAction();

                                if (adListener != null) {
                                    adListener.onAdClickedHigh();
                                }
                            }

                            @Override
                            public void onAdDismissedFullScreenContent() {
                                super.onAdDismissedFullScreenContent();
                                if (adListener != null) {
                                    adListener.onNextAction();
                                }
                            }

                            @Override
                            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                                super.onAdFailedToShowFullScreenContent(adError);
                                Log.e(TAG, "onAdFailedToShowFullScreenContent: High");

                                statusHigh = Type_Load_Fail;

                                if (splashAdHigh != null && statusMedium == Type_Load_Success && !isAppOpenShowed) {
                                    AppOpenManager.getInstance().setSplashActivity(splashActivity, idOpenMedium, timeOutOpen);

                                    if (splashAdMedium != null) {
                                        splashAdMedium.setImmersiveMode(true);
                                        splashAdMedium.show(activity);
                                    }
                                }
                                splashAdHigh = null;

                                if (adListener != null) {
                                    adListener.onAdFailedToShowHigh(adError);
                                }
                            }

                            @Override
                            public void onAdImpression() {
                                super.onAdImpression();
                                isAppOpenShowed = true;
                            }

                            @Override
                            public void onAdShowedFullScreenContent() {
                                super.onAdShowedFullScreenContent();
                            }
                        });

                        splashAdHigh = appOpenAd;
                        splashLoadTime = new Date().getTime();
                        appOpenAd.setOnPaidEventListener(adValue -> {
                            ERainLogEventManager.logPaidAdImpression(myApplication.getApplicationContext(),
                                    adValue,
                                    appOpenAd.getAdUnitId(),
                                    appOpenAd.getResponseInfo()
                                            .getMediationAdapterClassName(), AdType.APP_OPEN);

                            ERainLogEventManager.logPaidAdjustWithToken(adValue, appOpenAd.getAdUnitId());
                        });

                        if (!isAppOpenShowed) {
                            splashAdHigh.setImmersiveMode(true);
                            splashAdHigh.show(currentActivity);
                        }

                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        Log.d(TAG, "loadCallbackHigh: onAdFailedToLoad");
                        statusHigh = Type_Load_Fail;
                        if (splashAdHigh == null) {
                            if (statusMedium == Type_Load_Success && !isAppOpenShowed) {
                                AppOpenManager.getInstance().setSplashActivity(splashActivity, idOpenMedium, timeOutOpen);

                                if (splashAdMedium != null) {
                                    splashAdMedium.setImmersiveMode(true);
                                    splashAdMedium.show(activity);
                                }
                            }
                        }
                        if (splashAdMedium == null && splashAdAll == null && statusMedium == Type_Load_Fail && statusAll == Type_Load_Fail) {
                            if (adListener != null && !isAppOpenShowed) {
                                isAppOpenShowed = true;
                                adListener.onNextAction();
                            }
                        }
                    }

                };

        // load Open Splash Medium
        loadCallbackMedium =
                new AppOpenAd.AppOpenAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull AppOpenAd appOpenAd) {
                        Log.d(TAG, "loadCallbackMedium: onAdLoaded");
                        if (adListener != null) {
                            adListener.onAdLoaded();
                        }
                        statusMedium = Type_Load_Success;
                        splashAdMedium = appOpenAd;
                        if ((statusHigh == Type_Load_Fail || statusHigh == Type_Load_Success) && (statusAll == Type_Load_Fail || statusAll == Type_Load_Success || statusAll == Type_Loading) && !isAppOpenShowed) {
                            AppOpenManager.getInstance().setSplashActivity(splashActivity, idOpenMedium, timeOutOpen);

                            if (splashAdMedium != null) {
                                splashAdMedium.setImmersiveMode(true);
                                splashAdMedium.show(activity);
                            }
                        }

                        splashAdMedium.setFullScreenContentCallback(new FullScreenContentCallback() {
                            @Override
                            public void onAdClicked() {
                                super.onAdClicked();
                                disableAdResumeByClickAction();

                                if (adListener != null) {
                                    adListener.onAdClickedMedium();
                                }
                            }

                            @Override
                            public void onAdDismissedFullScreenContent() {
                                super.onAdDismissedFullScreenContent();
                                if (adListener != null) {
                                    adListener.onNextAction();
                                }
                            }

                            @Override
                            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                                super.onAdFailedToShowFullScreenContent(adError);
                                Log.e(TAG, "onAdFailedToShowFullScreenContent: Medium");

                                splashAdMedium = null;
                                statusMedium = Type_Load_Fail;

                                if (statusAll == Type_Load_Success && !isAppOpenShowed) {
                                    AppOpenManager.getInstance().setSplashActivity(splashActivity, idOpenAll, timeOutOpen);

                                    if (splashAdAll != null && !isAppOpenShowed) {
                                        splashAdAll.setImmersiveMode(true);
                                        splashAdAll.show(activity);
                                    }
                                }

                                if (adListener != null) {
                                    adListener.onAdFailedToShowMedium(adError);
                                }
                            }

                            @Override
                            public void onAdImpression() {
                                super.onAdImpression();
                                isAppOpenShowed = true;
                            }

                            @Override
                            public void onAdShowedFullScreenContent() {
                                super.onAdShowedFullScreenContent();
                            }
                        });
                        splashLoadTime = new Date().getTime();
                        appOpenAd.setOnPaidEventListener(adValue -> {
                            ERainLogEventManager.logPaidAdImpression(myApplication.getApplicationContext(),
                                    adValue,
                                    appOpenAd.getAdUnitId(),
                                    appOpenAd.getResponseInfo()
                                            .getMediationAdapterClassName(), AdType.APP_OPEN);
                            ERainLogEventManager.logPaidAdjustWithToken(adValue, appOpenAd.getAdUnitId());
                        });
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        Log.d(TAG, "loadCallbackMedium: onAdFailedToLoad");
                        splashAdMedium = null;
                        statusMedium = Type_Load_Fail;

                        if (splashAdHigh == null && splashAdAll == null && statusHigh == Type_Load_Fail && statusAll == Type_Load_Fail) {
                            if (adListener != null && !isAppOpenShowed) {
                                isAppOpenShowed = true;
                                adListener.onNextAction();
                            }
                        }
                    }

                };

        // load Open Splash All
        loadCallbackAll =
                new AppOpenAd.AppOpenAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull AppOpenAd appOpenAd) {
                        Log.d(TAG, "loadCallbackAll: onAdLoaded");
                        if (adListener != null) {
                            adListener.onAdLoadedAll();
                        }
                        splashAdAll = appOpenAd;
                        statusAll = Type_Load_Success;

                        if ((statusHigh == Type_Load_Fail || statusHigh == Type_Load_Success) && (statusMedium == Type_Load_Fail || statusMedium == Type_Load_Success) && !isAppOpenShowed) {
                            AppOpenManager.getInstance().setSplashActivity(splashActivity, idOpenAll, timeOutOpen);

                            if (splashAdAll != null) {
                                splashAdAll.setImmersiveMode(true);
                                splashAdAll.show(activity);
                            }
                        }

                        splashAdAll.setFullScreenContentCallback(new FullScreenContentCallback() {
                            @Override
                            public void onAdClicked() {
                                super.onAdClicked();
                                disableAdResumeByClickAction();

                                if (adListener != null) {
                                    adListener.onAdClickedAll();
                                }
                            }

                            @Override
                            public void onAdDismissedFullScreenContent() {
                                super.onAdDismissedFullScreenContent();
                                if (adListener != null) {
                                    adListener.onNextAction();
                                }
                            }

                            @Override
                            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                                super.onAdFailedToShowFullScreenContent(adError);
                                Log.e(TAG, "onAdFailedToShowFullScreenContent: All");

                                splashAdAll = null;
                                statusAll = Type_Load_Fail;

                                if (statusHigh == Type_Load_Fail && statusMedium == Type_Load_Fail) {
                                    if (adListener != null && !isAppOpenShowed) {
                                        adListener.onNextAction();
                                    }
                                }

                                if (adListener != null) {
                                    adListener.onAdFailedToShowAll(adError);
                                }
                            }

                            @Override
                            public void onAdImpression() {
                                super.onAdImpression();
                                isAppOpenShowed = true;
                            }

                            @Override
                            public void onAdShowedFullScreenContent() {
                                super.onAdShowedFullScreenContent();
                            }
                        });

                        splashLoadTime = new Date().getTime();
                        appOpenAd.setOnPaidEventListener(adValue -> {
                            ERainLogEventManager.logPaidAdImpression(myApplication.getApplicationContext(),
                                    adValue,
                                    appOpenAd.getAdUnitId(),
                                    appOpenAd.getResponseInfo()
                                            .getMediationAdapterClassName(), AdType.APP_OPEN);
                            ERainLogEventManager.logPaidAdjustWithToken(adValue, appOpenAd.getAdUnitId());
                        });
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        Log.d(TAG, "loadCallbackAll: onAdFailedToLoad");
                        splashAdAll = null;
                        statusAll = Type_Load_Fail;

                        if (splashAdHigh == null && splashAdMedium == null && statusHigh == Type_Load_Fail && statusMedium == Type_Load_Fail) {
                            if (adListener != null && !isAppOpenShowed) {
                                isAppOpenShowed = true;
                                adListener.onNextAction();
                            }
                        }

                    }

                };

        AdRequest request = getAdRequest();
        AdRequest request1 = getAdRequest();
        AdRequest request2 = getAdRequest();
        AppOpenAd.load(myApplication, idOpenHigh, request, loadCallbackHigh);
        AppOpenAd.load(myApplication, idOpenMedium, request1, loadCallbackMedium);
        AppOpenAd.load(myApplication, idOpenAll, request2, loadCallbackAll);
    }

    public void loadSplashOpenAndInter(Class splashActivity, AppCompatActivity activity, String idOpen, String idInter, int timeOutOpen, AdCallback adListener) {
        isAppOpenShowed = false;
        statusOpen = Type_Loading;
        statusInter = Type_Loading;

        if (AdGate.isPurchased(activity)) {
            if (adListener != null) {
                adListener.onNextAction();
            }
            return;
        }

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (adListener != null && !isAppOpenShowed && splashAdOpen == null && splashAdInter == null) {
                    isAppOpenShowed = true;
                    adListener.onNextAction();
                }
            }
        }, timeOutOpen);

        AppOpenManager.getInstance().setSplashActivity(splashActivity, idOpen, timeOutOpen);

        loadCallbackOpen =
                new AppOpenAd.AppOpenAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull AppOpenAd appOpenAd) {
                        Log.d(TAG, "loadCallbackOpen: onAdLoaded");
                        if (adListener != null) {
                            adListener.onAdLoadedHigh();
                        }

                        appOpenAd.setOnPaidEventListener(adValue -> {
                            ERainLogEventManager.logPaidAdImpression(myApplication.getApplicationContext(),
                                    adValue,
                                    appOpenAd.getAdUnitId(),
                                    appOpenAd.getResponseInfo()
                                            .getMediationAdapterClassName(), AdType.APP_OPEN);
                            ERainLogEventManager.logPaidAdjustWithToken(adValue, appOpenAd.getAdUnitId());
                        });

                        splashAdOpen = appOpenAd;
                        splashAdOpen.setFullScreenContentCallback(new FullScreenContentCallback() {
                            @Override
                            public void onAdClicked() {
                                super.onAdClicked();
                                disableAdResumeByClickAction();

                                if (adListener != null) {
                                    adListener.onAdClickedHigh();
                                }
                            }

                            @Override
                            public void onAdDismissedFullScreenContent() {
                                super.onAdDismissedFullScreenContent();
                                if (adListener != null) {
                                    adListener.onNextAction();
                                }
                            }

                            @Override
                            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                                super.onAdFailedToShowFullScreenContent(adError);
                                Log.e(TAG, "onAdFailedToShowFullScreenContent: Open");

                                statusOpen = Type_Load_Fail;
                                splashAdOpen = null;

                                long time = timeOutOpen - (System.currentTimeMillis() - currentTime);

                                if (timerListenInter == null) {
                                    timerListenInter = new CountDownTimer(time, 1000) {
                                        @Override
                                        public void onTick(long l) {
                                            if (statusInter == Type_Load_Success && !isAppOpenShowed) {
                                                isAppOpenShowed = true;
                                                Admob.getInstance().onShowSplash(activity, adListener, splashAdInter);
                                            } else if (statusInter == Type_Load_Fail && !isAppOpenShowed) {
                                                if (adListener != null) {
                                                    isAppOpenShowed = true;
                                                    adListener.onNextAction();
                                                }
                                            }
                                        }

                                        @Override
                                        public void onFinish() {
                                            if (!isAppOpenShowed) {
                                                if (adListener != null) {
                                                    isAppOpenShowed = true;
                                                    adListener.onNextAction();
                                                }
                                            }
                                        }
                                    }.start();
                                }
                            }

                            @Override
                            public void onAdImpression() {
                                super.onAdImpression();
                                isAppOpenShowed = true;
                            }

                            @Override
                            public void onAdShowedFullScreenContent() {
                                super.onAdShowedFullScreenContent();
                            }
                        });
                        splashLoadTime = new Date().getTime();
                        if (!isAppOpenShowed) {
                            splashAdOpen.show(currentActivity);
                        }
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        Log.d(TAG, "loadCallbackOpen: onAdFailedToLoad");
                        statusOpen = Type_Load_Fail;
                        splashAdOpen = null;

                        long time = timeOutOpen - (System.currentTimeMillis() - currentTime);

                        if (statusInter != Type_Loading) {
                            if (adListener != null && !isAppOpenShowed) {
                                isAppOpenShowed = true;
                                adListener.onNextAction();
                            }
                        } else {
                            timerListenInter = new CountDownTimer(time, 1000) {
                                @Override
                                public void onTick(long l) {
                                    if (statusInter == Type_Load_Success && !isAppOpenShowed) {
                                        isAppOpenShowed = true;
                                        Admob.getInstance().onShowSplash(activity, adListener, splashAdInter);
                                    } else if (statusInter == Type_Load_Fail && !isAppOpenShowed) {
                                        if (adListener != null) {
                                            isAppOpenShowed = true;
                                            adListener.onNextAction();
                                        }
                                    }
                                }

                                @Override
                                public void onFinish() {
                                    if (!isAppOpenShowed) {
                                        if (adListener != null) {
                                            isAppOpenShowed = true;
                                            adListener.onNextAction();
                                        }
                                    }
                                }
                            }.start();
                        }
                    }
                };

        InterstitialAd.load(activity, idInter, getAdRequest(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd interstitialAd) {
                        if (adListener != null)
                            adListener.onInterstitialLoad(interstitialAd);

                        statusInter = Type_Load_Success;

                        // Log paid Ads Interstitial
                        interstitialAd.setOnPaidEventListener(adValue -> {
                            ERainLogEventManager.logPaidAdImpression(activity,
                                    adValue,
                                    interstitialAd.getAdUnitId(),
                                    interstitialAd.getResponseInfo()
                                            .getMediationAdapterClassName(), AdType.INTERSTITIAL);
                            ERainLogEventManager.logPaidAdjustWithToken(adValue, interstitialAd.getAdUnitId());
                        });

                        splashAdInter = interstitialAd;
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        Log.i(TAG, loadAdError.getMessage());
                        statusInter = Type_Load_Fail;
                        splashAdInter = null;

                        if (statusOpen == Type_Load_Fail) {
                            if (adListener != null && !isAppOpenShowed) {
                                isAppOpenShowed = true;
                                adListener.onNextAction();
                            }
                        }
                    }

                });

        AppOpenAd.load(myApplication, idOpen, getAdRequest(), loadCallbackOpen);
        currentTime = System.currentTimeMillis();
    }

    public void loadAndShowSplashAds(final String aId) {
        loadAndShowSplashAds(aId, 0);
    }

    public void loadAndShowSplashAds(final String adId, long delay) {
        isTimeout = false;
        enableScreenContentCallback = true;
        // gated on the application: this is often called from onCreate, before currentActivity is set
        if (AdGate.isPurchased(myApplication)) {
            if (fullScreenContentCallback != null && enableScreenContentCallback) {
                (new Handler()).postDelayed(() -> {
                    fullScreenContentCallback.onAdDismissedFullScreenContent();
                }, delay);
            }
            return;
        }
        loadCallback =
                new AppOpenAd.AppOpenAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull AppOpenAd appOpenAd) {
                        Log.d(TAG, "onAppOpenAdLoaded: splash");

                        timeoutHandler.removeCallbacks(runnableTimeout);

                        if (isTimeout) {
                            Log.e(TAG, "onAppOpenAdLoaded: splash timeout");
                        } else {
                            AppOpenManager.this.splashAd = appOpenAd;
                            splashLoadTime = new Date().getTime();
                            appOpenAd.setOnPaidEventListener(adValue -> {
                                ERainLogEventManager.logPaidAdImpression(myApplication.getApplicationContext(),
                                        adValue,
                                        appOpenAd.getAdUnitId(),
                                        appOpenAd.getResponseInfo()
                                                .getMediationAdapterClassName(), AdType.APP_OPEN);
                            });

                            (new Handler()).postDelayed(() -> {
                                showAdIfAvailable(true);
                            }, delay);
                        }
                    }

                    /**
                     * Called when an app open ad has failed to load.
                     *
                     * @param loadAdError the error.
                     */
                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        Log.e(TAG, "onAppOpenAdFailedToLoad: splash " + loadAdError.getMessage());
                        if (isTimeout) {
                            Log.e(TAG, "onAdFailedToLoad: splash timeout");
                            return;
                        }
                        if (fullScreenContentCallback != null && enableScreenContentCallback) {
                            (new Handler()).postDelayed(() -> {
                                fullScreenContentCallback.onAdDismissedFullScreenContent();
                            }, delay);
                            enableScreenContentCallback = false;
                        }
                    }

                };
        AdRequest request = getAdRequest();
        AppOpenAd.load(myApplication, splashAdId, request, loadCallback);

        if (splashTimeout > 0) {
            timeoutHandler = new Handler();
            timeoutHandler.postDelayed(runnableTimeout, splashTimeout);
        }
    }

    Runnable runnableTimeout = new Runnable() {
        @Override
        public void run() {
            Log.e(TAG, "timeout load ad ");
            isTimeout = true;
            enableScreenContentCallback = false;
            if (fullScreenContentCallback != null) {
                fullScreenContentCallback.onAdDismissedFullScreenContent();
            }
        }
    };

    public void loadAdOpenSplash2id(Class splashActivity, Activity activity, String idOpenHigh, String idOpenAll, int timeOutOpen, AdCallback adListener) {
        if (AdGate.isPurchased(activity)) {
            if (adListener != null) {
                adListener.onNextAction();
            }
            return;
        }

        statusHigh = Type_Loading;
        statusAll = Type_Loading;
        isAppOpenShowed = false;

        Runnable actionTimeOut = () -> {
            Log.d("AppOpenSplash", "getAdSplash time out");
            adListener.onNextAction();
            setShowingAd(false);
        };
        Handler handleTimeOut = new Handler();
        handleTimeOut.postDelayed(actionTimeOut, timeOutOpen);
        AppOpenManager.getInstance().setSplashActivity(splashActivity, idOpenHigh, timeOutOpen);

        AppOpenAd.load(activity, idOpenHigh, getAdRequest(), new AppOpenAd.AppOpenAdLoadCallback() {
            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                super.onAdFailedToLoad(loadAdError);
                statusHigh = Type_Load_Fail;
                if (statusAll == Type_Load_Success && !isAppOpenShowed && splashAdAll != null) {
                    Log.d("AppOpenSplash", "onAdFailedToLoad: High");
                    AppOpenManager.getInstance().setSplashActivity(splashActivity, idOpenAll, timeOutOpen);
                    splashAdAll.setImmersiveMode(true);
                    splashAdAll.show(activity);
                }

                if (statusAll == Type_Load_Fail || statusAll == Type_Show_Fail) {
                    Log.d("AppOpenSplash", "onAdFailedToHigh: High");
                    if (adListener != null && !isAppOpenShowed) {
                        adListener.onNextAction();
                    }
                    handleTimeOut.removeCallbacks(actionTimeOut);
                }
            }

            @Override
            public void onAdLoaded(@NonNull AppOpenAd appOpenAd) {
                super.onAdLoaded(appOpenAd);
                handleTimeOut.removeCallbacks(actionTimeOut);
                if (adListener != null) {
                    adListener.onAdLoadedHigh();
                }

                appOpenAd.setOnPaidEventListener(adValue -> {
                    ERainLogEventManager.logPaidAdImpression(myApplication.getApplicationContext(),
                            adValue,
                            appOpenAd.getAdUnitId(),
                            appOpenAd.getResponseInfo()
                                    .getMediationAdapterClassName(), AdType.APP_OPEN);
                    ERainLogEventManager.logPaidAdjustWithToken(adValue, appOpenAd.getAdUnitId());
                });

                splashAdHigh = appOpenAd;
                statusHigh = Type_Load_Success;

                if (!isAppOpenShowed) {
                    splashAdHigh.setImmersiveMode(true);
                    splashAdHigh.show(activity);
                    Log.d("AppOpenSplash", "show High");
                }

                splashAdHigh.setFullScreenContentCallback(new FullScreenContentCallback() {
                    @Override
                    public void onAdClicked() {
                        super.onAdClicked();
                        disableAdResumeByClickAction();
                        if (adListener != null) {
                            adListener.onAdClickedHigh();
                        }
                    }

                    @Override
                    public void onAdDismissedFullScreenContent() {
                        super.onAdDismissedFullScreenContent();
                        if (adListener != null) {
                            adListener.onNextAction();
                            Log.d("AppOpenSplash", "onAdDismissedFullScreenContent: vao 1");
                        }
                    }

                    @Override
                    public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                        super.onAdFailedToShowFullScreenContent(adError);
                        if (statusAll == Type_Load_Success && splashAdAll != null && statusHigh != Type_Load_Success) {
                            AppOpenManager.getInstance().setSplashActivity(splashActivity, idOpenAll, timeOutOpen);
                            splashAdAll.setImmersiveMode(true);
                            splashAdAll.show(activity);
                            Log.d("AppOpenSplash", "onAdFailedToShowFullScreenContent show All");
                        }
                        timeRemaining = timeOutOpen - (System.currentTimeMillis() - currentTime);
                        statusHigh = Type_Show_Fail;
                    }

                    @Override
                    public void onAdImpression() {
                        super.onAdImpression();
                        isAppOpenShowed = true;
                        statusHigh = Type_Show_Success;
                    }

                    @Override
                    public void onAdShowedFullScreenContent() {
                        super.onAdShowedFullScreenContent();
                    }
                });
            }
        });

        AppOpenAd.load(activity, idOpenAll,

                getAdRequest(), new AppOpenAd.AppOpenAdLoadCallback() {
                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        super.onAdFailedToLoad(loadAdError);
                        statusAll = Type_Load_Fail;
                        if (statusHigh == Type_Load_Fail || statusHigh == Type_Show_Fail) {
                            Log.d("AppOpenSplash", "onAdFailedToLoad: All");
                            if (adListener != null && !isAppOpenShowed) {
                                adListener.onNextAction();
                            }
                            handleTimeOut.removeCallbacks(actionTimeOut);
                        }
                    }

                    @Override
                    public void onAdLoaded(@NonNull AppOpenAd appOpenAd) {
                        super.onAdLoaded(appOpenAd);
                        handleTimeOut.removeCallbacks(actionTimeOut);
                        if (adListener != null) {
                            adListener.onAdLoadedAll();
                        }

                        appOpenAd.setOnPaidEventListener(adValue -> {
                            ERainLogEventManager.logPaidAdImpression(myApplication.getApplicationContext(),
                                    adValue,
                                    appOpenAd.getAdUnitId(),
                                    appOpenAd.getResponseInfo()
                                            .getMediationAdapterClassName(), AdType.APP_OPEN);
                            ERainLogEventManager.logPaidAdjustWithToken(adValue, appOpenAd.getAdUnitId());
                        });

                        splashAdAll = appOpenAd;
                        statusAll = Type_Load_Success;

                        if (!isAppOpenShowed && (statusHigh == Type_Load_Fail || statusHigh == Type_Show_Fail)) {
                            AppOpenManager.getInstance().setSplashActivity(splashActivity, idOpenAll, timeOutOpen);
                            splashAdAll.setImmersiveMode(true);
                            splashAdAll.show(activity);
                            Log.d("AppOpenSplash", "show All");
                        }

                        splashAdAll.setFullScreenContentCallback(new FullScreenContentCallback() {
                            @Override
                            public void onAdClicked() {
                                super.onAdClicked();
                                disableAdResumeByClickAction();
                                if (adListener != null) {
                                    adListener.onAdClickedAll();
                                }
                            }

                            @Override
                            public void onAdDismissedFullScreenContent() {
                                super.onAdDismissedFullScreenContent();
                                if (adListener != null) {
                                    adListener.onNextAction();
                                    Log.d("AppOpenSplash", "onAdDismissedFullScreenContent: vao 2");
                                }
                            }

                            @Override
                            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                                super.onAdFailedToShowFullScreenContent(adError);
                                if (statusHigh == Type_Load_Fail) {
                                    if (timerListenInter == null) {
                                        timerListenInter = new CountDownTimer(timeRemaining, 1000) {
                                            @Override
                                            public void onTick(long l) {
                                                if (isAppOpenShowed) {
                                                    cancel();
                                                }
                                            }

                                            @Override
                                            public void onFinish() {
                                                if (adListener != null && !isAppOpenShowed) {
                                                    if (statusAll != Type_Load_Success && (statusHigh == Type_Load_Fail || statusHigh == Type_Show_Fail)) {
                                                        adListener.onNextAction();
                                                        Log.d("AppOpenSplash", "onAdFailedToShowFullScreenContentAll: vao 2");
                                                    }
                                                }
                                            }
                                        }.start();
                                    }
                                }
                                statusAll = Type_Show_Fail;
                            }

                            @Override
                            public void onAdImpression() {
                                super.onAdImpression();
                                isAppOpenShowed = true;
                                statusAll = Type_Load_Success;
                            }

                            @Override
                            public void onAdShowedFullScreenContent() {
                                super.onAdShowedFullScreenContent();
                            }
                        });
                    }
                });
    }

    public void onCheckShowAppOpenSplashWhenFail(AppCompatActivity activity, AdCallback callback, int timeDelay) {
        new Handler(activity.getMainLooper()).postDelayed(() -> {
            if (!isAppOpenShowed) {
                if (splashAdHigh != null && (statusHigh == Type_Load_Fail || statusHigh == Type_Show_Fail)) {
                    splashAd = splashAdHigh;
                    showAppOpenSplash(activity, callback);
                    Log.d("AppOpenSplash", "onCheckShowAppOpenSplashWhenFail: vao 1");
                } else if (splashAdAll != null && (statusAll == Type_Load_Fail || statusAll == Type_Show_Fail)) {
                    splashAd = splashAdAll;
                    showAppOpenSplash(activity, callback);
                    Log.d("AppOpenSplash", "onCheckShowAppOpenSplashWhenFail: vao 2");
                }
            }
        }, timeDelay);
    }

    public void showAppOpenSplash(Context context, AdCallback adCallback) {
        if (splashAd == null) {
            adCallback.onNextAction();
            Log.d("AppOpenSplash Failed", "splashAd null: vao 2");
            return;
        }
        new Handler().postDelayed(() -> {
            splashAd.setFullScreenContentCallback(
                    new FullScreenContentCallback() {
                        @Override
                        public void onAdDismissedFullScreenContent() {
                            adCallback.onNextAction();
                            isAppOpenShowed = false;
                            Log.d("AppOpenSplash Failed", "onAdDismissedFullScreenContent: vao 1");
                        }

                        @Override
                        public void onAdFailedToShowFullScreenContent(AdError adError) {
                            adCallback.onAdFailedToShow(adError);
                            isAppOpenShowed = false;
                        }

                        @Override
                        public void onAdShowedFullScreenContent() {
                            adCallback.onAdImpression();
                            isAppOpenShowed = true;
                        }


                        @Override
                        public void onAdClicked() {
                            super.onAdClicked();
                            ERainLogEventManager.logClickAdsEvent(context, splashAdId);
                            adCallback.onAdClicked();
                        }
                    });
            splashAd.setImmersiveMode(true);
            splashAd.show(currentActivity);
        }, 800);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void onResume() {
        cancelBackgroundLoad();
        captureResumeReturn(currentActivity);
        if (!isAppResumeEnabled) {
            Log.d(TAG, "onResume: app resume is disabled");
            return;
        }

        if (isInterstitialShowing) {
            Log.d(TAG, "onResume: interstitial is showing");
            return;
        }

        // Foreground can be reported before any activity has started, and the checks below all
        // dereference it.
        if (currentActivity == null) {
            Log.d(TAG, "onResume: no current activity");
            return;
        }

        if (currentActivity instanceof AdActivity) {
            Log.d(TAG, "onResume: vendor ad Activity is not a host return");
            return;
        }

        // Through the same query the welcome-resume path uses. This loop compared class NAMES
        // exactly while isResumeSuppressedFor uses isInstance, so registering a base class
        // suppressed one path and not the other.
        if (isResumeSuppressedFor(currentActivity)) {
            Log.d(TAG, "onStart: activity is disabled");
            return;
        }

        String sharedReason = resumeSkipReasonFor(currentActivity);
        if (sharedReason != null) {
            reportResumePolicySkip(sharedReason);
            return;
        }

        if (splashActivity != null && splashActivity.getName().equals(currentActivity.getClass().getName())) {
            String adId = splashAdId;
            if (adId == null) {
                Log.e(TAG, "splash ad id must not be null");
            }
            Log.d(TAG, "onStart: load and show splash ads");
            loadAndShowSplashAds(adId);
            return;
        }

        String appOpenReason = appOpenPolicySkipReasonFor(currentActivity);
        if (appOpenReason != null) {
            reportResumePolicySkip(appOpenReason);
            return;
        }

        if (!isInitialized) return;
        if (!isAdAvailable(false)) return;
        final Activity host = currentActivity;
        final AppOpenAd candidate = appResumeAd;
        final long hostGeneration = resumeHostGeneration;
        final long fetchGeneration = resumeFetchGeneration;
        // ON_START precedes Activity/process RESUMED. Only this already-eligible return is
        // deferred; a blocked one-shot above cannot become eligible after its snapshot clears.
        resumeFetchHandler.post(() -> {
            if (host == currentActivity && hostGeneration == resumeHostGeneration
                    && fetchGeneration == resumeFetchGeneration && appResumeAd == candidate) {
                showAdIfAvailable(false);
            }
        });
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void onStop() {
        Log.d(TAG, "onStop: app stop");
        currentReturnSkipReason = null;
        if (resumeBackground) return;
        resumeBackground = true;
        // The SDK's own fullscreen and partner-suppressed external actions are not a new
        // opportunity to buy a resume ad. Their return remains governed by the same policy.
        if (!isAppResumeEnabled || !isInitialized || isShowingAd || isInterstitialShowing
                || pendingResumeSkipReason.get() != null
                || currentActivity instanceof AdActivity) return;
        if (currentActivity != null && (isResumeSuppressedFor(currentActivity)
                || resumeSkipReasonFor(currentActivity) != null
                || appOpenPolicySkipReasonFor(currentActivity) != null)) return;
        long delayMs = AdRemoteConfig.normalizeAppResumeLoadDelayMs(
                AdRemoteConfig.getInstance().getAppResumeLoadDelayMs());
        resumeBackgroundRequests = 0;
        resumeBackgroundDeadlineMs = SystemClock.elapsedRealtime() + delayMs
                + RESUME_BACKGROUND_RETRY_WINDOW_MS;
        scheduleBackgroundLoad(delayMs);
    }

    private void scheduleBackgroundLoad(long delayMs) {
        clearBackgroundLoadSchedule();
        long nowMs = SystemClock.elapsedRealtime();
        // A callback carried from the previous stay may retry, but cannot shorten this stay's
        // captured initial delay. The window begins at that first eligible instant.
        long firstEligibleMs = resumeBackgroundDeadlineMs - RESUME_BACKGROUND_RETRY_WINDOW_MS;
        delayMs = Math.max(delayMs, firstEligibleMs - nowMs);
        if (!resumeBackground || !isAppResumeEnabled || !isInitialized
                || resumeBackgroundRequests >= RESUME_MAX_BACKGROUND_REQUESTS
                || nowMs >= resumeBackgroundDeadlineMs
                || delayMs >= resumeBackgroundDeadlineMs - nowMs) return;
        pendingBackgroundLoad = () -> {
            pendingBackgroundLoad = null;
            if (!resumeBackground || SystemClock.elapsedRealtime() >= resumeBackgroundDeadlineMs
                    || resumeBackgroundRequests >= RESUME_MAX_BACKGROUND_REQUESTS) return;
            resumeDispatchAllowed = true;
            try {
                fetchAd(false);
            } finally {
                resumeDispatchAllowed = false;
            }
        };
        resumeFetchHandler.postDelayed(pendingBackgroundLoad, Math.max(0L, delayMs));
    }

    private void clearBackgroundLoadSchedule() {
        if (pendingBackgroundLoad != null) resumeFetchHandler.removeCallbacks(pendingBackgroundLoad);
        pendingBackgroundLoad = null;
    }

    private void cancelBackgroundLoad() {
        resumeBackground = false;
        resumeBackgroundDeadlineMs = 0;
        clearBackgroundLoadSchedule();
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    public void onPause() {
        Log.d(TAG, "onPause");
    }

    private void dismissResumeDialog(Dialog ownedDialog) {
        if (dialog == ownedDialog) dialog = null;
        if (ownedDialog == null) return;
        try {
            if (ownedDialog.isShowing()) ownedDialog.dismiss();
        } catch (RuntimeException error) {
            Log.w(TAG, "Resume loading dialog could not be dismissed", error);
        }
    }


    public void loadOpenAppAdSplash(final Context context, String idResumeSplash, final long timeDelay, long timeOut, final boolean isShowAdIfReady, final AdCallback adCallback) {
        this.splashAdId = idResumeSplash;
        if (AdGate.isPurchased(context)) {
            if (adCallback != null) {
                adCallback.onNextAction();
            }
            return;
        }
        if (!this.isNetworkConnected(context)) {
            (new Handler()).postDelayed(new Runnable() {
                public void run() {
                    adCallback.onAdFailedToLoad((LoadAdError) null);
                    adCallback.onNextAction();
                }
            }, timeDelay);
        } else {
            final long currentTimeMillis = System.currentTimeMillis();
            final Runnable timeOutRunnable = () -> {
                Log.d("AppOpenManager", "getAdSplash time out");
                adCallback.onNextAction();
                setShowingAd(false);
            };
            final Handler handler = new Handler();
            handler.postDelayed(timeOutRunnable, timeOut);
            AdRequest adRequest = this.getAdRequest();
            String adUnitId = this.splashAdId;
            AppOpenAd.AppOpenAdLoadCallback appOpenAdLoadCallback = new AppOpenAd.AppOpenAdLoadCallback() {
                public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                    super.onAdFailedToLoad(loadAdError);
                    handler.removeCallbacks(timeOutRunnable);
                    adCallback.onAdFailedToLoad((LoadAdError) null);
                    adCallback.onNextAction();
                }

                public void onAdLoaded(@NonNull AppOpenAd appOpenAd) {
                    super.onAdLoaded(appOpenAd);
                    handler.removeCallbacks(timeOutRunnable);
                    AppOpenManager.this.splashAd = appOpenAd;
                    AppOpenManager.this.splashAd.setOnPaidEventListener((adValue) -> {
                    });
                    appOpenAd.setOnPaidEventListener((adValue) -> {
                        ERainLogEventManager.logPaidAdImpression(myApplication.getApplicationContext(),
                                adValue,
                                appOpenAd.getAdUnitId(),
                                appOpenAd.getResponseInfo()
                                        .getMediationAdapterClassName(), AdType.APP_OPEN);
                        ERainLogEventManager.logPaidAdjustWithToken(adValue, appOpenAd.getAdUnitId());
                    });
                    if (isShowAdIfReady) {
                        long elapsedTime = System.currentTimeMillis() - currentTimeMillis;
                        if (elapsedTime >= timeDelay) {
                            elapsedTime = 0L;
                        }

                        Handler handler1 = new Handler();
                        Context appOpenAdContext = context;
                        Runnable showAppOpenSplashRunnable = () -> {
                            AppOpenManager.this.showAppOpenSplash(appOpenAdContext, adCallback);
                        };
                        handler1.postDelayed(showAppOpenSplashRunnable, elapsedTime);
                    } else {
                        adCallback.onAdSplashReady();
                    }

                }
            };
            AppOpenAd.load(context, adUnitId, adRequest, appOpenAdLoadCallback);
        }

    }

    public void loadOpenAppAdSplashFloor(final Context context, final List<String> listIDResume, final boolean isShowAdIfReady, final AdCallback adCallback) {
        if (AdGate.isPurchased(context)) {
            if (adCallback != null) {
                adCallback.onNextAction();
            }
            return;
        }
        if (!this.isNetworkConnected(context)) {
            (new Handler()).postDelayed(new Runnable() {
                public void run() {
                    adCallback.onAdFailedToLoad((LoadAdError) null);
                    adCallback.onNextAction();
                }
            }, 3000L);
        } else {
            if (listIDResume == null) {
                adCallback.onAdFailedToLoad((LoadAdError) null);
                adCallback.onNextAction();
                return;
            }

            if (listIDResume.size() > 0) {
                Log.e("AppOpenManager", "load ID :" + (String) listIDResume.get(0));
            }

            if (listIDResume.size() < 1) {
                adCallback.onAdFailedToLoad((LoadAdError) null);
                adCallback.onNextAction();
                return;
            }

            AdRequest adRequest = this.getAdRequest();
            AppOpenAd.AppOpenAdLoadCallback appOpenAdLoadCallback = new AppOpenAd.AppOpenAdLoadCallback() {
                public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                    super.onAdFailedToLoad(loadAdError);
                    listIDResume.remove(0);
                    if (listIDResume.size() == 0) {
                        adCallback.onAdFailedToLoad((LoadAdError) null);
                        adCallback.onNextAction();
                    } else {
                        AppOpenManager.this.loadOpenAppAdSplashFloor(context, listIDResume, isShowAdIfReady, adCallback);
                    }

                }

                public void onAdLoaded(@NonNull AppOpenAd appOpenAd) {
                    super.onAdLoaded(appOpenAd);
                    AppOpenManager.this.splashAd = appOpenAd;
                    AppOpenManager.this.splashAd.setOnPaidEventListener((adValue) -> {
                        ERainLogEventManager.logPaidAdImpression(myApplication.getApplicationContext(),
                                adValue,
                                appOpenAd.getAdUnitId(),
                                appOpenAd.getResponseInfo()
                                        .getMediationAdapterClassName(), AdType.APP_OPEN);
                        ERainLogEventManager.logPaidAdjustWithToken(adValue, appOpenAd.getAdUnitId());
                    });
                    if (isShowAdIfReady) {
                        AppOpenManager.this.showAppOpenSplash(context, adCallback);
                    } else {
                        adCallback.onAdSplashReady();
                    }

                }
            };
            AppOpenAd.load(context, (String) listIDResume.get(0), adRequest, appOpenAdLoadCallback);
        }

    }

    public void onCheckShowSplashWhenFail(final AppCompatActivity activity, final AdCallback callback, int timeDelay) {
        (new Handler(activity.getMainLooper())).postDelayed(new Runnable() {
            public void run() {
                if (AppOpenManager.this.splashAd != null && !AppOpenManager.isShowingAd) {
                    Log.e("AppOpenManager", "show ad splash when show fail in background");
                    AppOpenManager.getInstance().showAppOpenSplash(activity, callback);
                }

            }
        }, (long) timeDelay);
    }

    private boolean isNetworkConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected();
    }
}
