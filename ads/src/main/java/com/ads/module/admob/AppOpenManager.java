package com.ads.module.admob;

import com.ads.module.config.settings.AdBehavior;
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
    private static final int RESUME_ADS = 1;

    private static volatile AppOpenManager INSTANCE;
    private AppOpenAd appResumeAd = null;
    private AppOpenAd.AppOpenAdLoadCallback loadCallback;

    // Resume fetch state only; raw splash requests retain their own callbacks and buffer.
    private long resumeBackgroundDeadlineMs;
    private long resumeBackgroundFirstEligibleMs;
    private int resumeBackgroundRequests;
    private boolean resumeBackground;
    private boolean resumeDispatchAllowed;
    private Runnable pendingBackgroundLoad;
    private java.util.List<Long> resumeFailureBackoffMs() {
        return AdBehavior.document.getSnapshot().longList("app_open.load.failure_backoff_ms", java.util.Collections.emptyList());
    }
    private final Handler resumeFetchHandler = new Handler(Looper.getMainLooper());
    private long resumeFetchGeneration;
    private boolean resumeFetchPending;
    private long resumeFetchDeadlineMs;
    private long resumeFetchStartedAtMs;
    private int resumeFailureStreak;
    private long resumeRetryAfterMs;
    private Runnable resumeFetchTimeout;

    private FullScreenContentCallback fullScreenContentCallback;

    private String appResumeAdId;
    /** True once {@link #applyRemoteConfig()} owns the unit; it may then restore a cleared id. */
    private boolean resumeUnitFromConfig;

    private Activity currentActivity;
    private Activity resumedActivity;
    private long resumeHostGeneration;
    private Object activeResumeAttempt;
    private Runnable pendingResumeShow;
    private Runnable pendingResumeCancellation;

    private Application myApplication;

    private static boolean isShowingAd = false;
    private long appResumeLoadTime = 0;

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
        if (AdBehavior.bool("diagnostics.ads_telemetry_enabled")) Tracker.track(new TrackkitEvents.Ad.Loaded(resumePlacementFor(adUnitId), AdFormat.APP_OPEN,
                adUnitId, System.currentTimeMillis() - requestedAtMs));
    }

    private static void reportResumeLoadFailed(String adUnitId, Integer errorCode) {
        if (AdBehavior.bool("diagnostics.ads_telemetry_enabled")) Tracker.track(new TrackkitEvents.Ad.LoadFailed(
                resumePlacementFor(adUnitId), AdFormat.APP_OPEN, adUnitId, errorCode));
    }

    private static void reportResumeShown(String adUnitId) {
        if (AdBehavior.bool("diagnostics.ads_telemetry_enabled")) Tracker.track(new TrackkitEvents.Ad.Show(
                resumePlacementFor(adUnitId), AdFormat.APP_OPEN, adUnitId));
    }

    private static void reportResumeShowFailed(String adUnitId, Integer errorCode) {
        if (AdBehavior.bool("diagnostics.ads_telemetry_enabled")) Tracker.track(new TrackkitEvents.Ad.ShowFailed(
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
    }

    /**
     * Resume loads belong to a bounded background opportunity, including offline recovery and
     * failure backoff. Existing cache/in-flight work wins over new-request gates. Foreground
     * cancels scheduling, never a dispatched result; a timeout allows retry but a late fill can
     * still be cached until superseded or invalidated. No timer ever shows an ad.
     * Resume dispatch/configuration/cache mutations run on main.
     */
    public void fetchAd() {
        if (AdGate.areRequestsHeld()) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            resumeFetchHandler.post(() -> fetchAd());
            return;
        }
        Log.d(TAG, "fetchAd");
        // Only the delayed background opportunity may dispatch. Public/legacy fetch calls
        // cannot create startup, foreground, post-show or unbounded background reloads.
        if (!resumeDispatchAllowed) return;
        // Existing work is independent of whether a new request could be sent right now.
        if (isAdAvailable() || resumeFetchPending) return;
        if (!canFetchResume(false)) return;
        if (!AdGate.isNetworkAvailable(myApplication)) {
            scheduleBackgroundLoad(AdBehavior.number("app_open.load.offline_recheck_ms"));
            return;
        }
        // GMA rejects a blank unit with "Cannot determine request type" on every call.
        String adUnitId = appResumeAdId;
        if (adUnitId == null || adUnitId.trim().isEmpty()) {
            Log.d(TAG, "fetchAd: no ad unit set yet");
            return;
        }

        if (SystemClock.elapsedRealtime() < resumeRetryAfterMs) {
            Log.d(TAG, "fetchAd: resume backoff");
            scheduleBackgroundLoad(resumeRetryAfterMs - SystemClock.elapsedRealtime());
            return;
        }
        final Application requestApplication = myApplication;
        final boolean personalized = ConsentCenter.canPersonalize();
        final long generation = resumeFetchGeneration + 1;
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
                        Log.d(TAG, "onAppOpenAdLoaded");
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
                    }


                    /**
                     * Called when an app open ad has failed to load.
                     *
                     * @param loadAdError the error.
                     */
                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        Log.d(TAG, "onAppOpenAdFailedToLoad: message " + loadAdError.getMessage());
                        if (!resumeTerminalReported.compareAndSet(false, true)) return;
                        reportResumeLoadFailed(adUnitId, loadAdError.getCode());
                        if (ownsResumeFetch(generation)) {
                            if (!canFetchResume(false)) {
                                if (ownsResumeFetch(generation)) cancelResumeFetch(false);
                            } else failResumeFetch(generation, "vendor_code=" + loadAdError.getCode());
                        }
                    }


                };
        if (currentActivity != null) {
            if (AdGate.areRequestsHeld() || !AdBehavior.bool("global.ads_enabled") || AdGate.isPurchased(currentActivity)) {
                if (ownsResumeFetch(generation)) cancelResumeFetch(false);
                return;
            }
            if (Arrays.asList(currentActivity.getResources().getStringArray(R.array.list_id_test)).contains(adUnitId)) {
                try {
                    showTestIdAlert(currentActivity, adUnitId);
                } catch (RuntimeException error) {
                    Log.w(TAG, "resume test-id notification unavailable", error);
                }
            }
        }
        try {
            AdRequest request = getAdRequest();
            if (!Objects.equals(appResumeAdId, adUnitId) || !canFetchResume(false)
                    || ConsentCenter.canPersonalize() != personalized) return;
            if (!AdGate.isNetworkAvailable(myApplication)) {
                scheduleBackgroundLoad(AdBehavior.number("app_open.load.offline_recheck_ms"));
                return;
            }
            // Ownership changes only when a replacement is actually about to dispatch.
            resumeFetchGeneration = generation;
            resumeFetchPending = true;
            resumeFetchStartedAtMs = SystemClock.elapsedRealtime();
            resumeFetchDeadlineMs = resumeFetchStartedAtMs + AdBehavior.number("app_open.load.timeout_ms");
            resumeFetchTimeout = () -> {
                if (!ownsResumeFetch(generation)) return;
                if (!canFetchResume(false)) {
                    if (ownsResumeFetch(generation)) cancelResumeFetch(false);
                } else {
                    failResumeFetch(generation, "timeout", true);
                    AdTracking.skipped(resumePlacementFor(adUnitId), AdFormat.APP_OPEN, "load_timeout");
                }
            };
            resumeFetchHandler.postDelayed(resumeFetchTimeout, AdBehavior.number("app_open.load.timeout_ms"));
            Log.d(TAG, "resume load dispatch generation=" + generation);
            // After every gate, so the funnel counts requests that actually reach GMA.
            resumeRequestReported.set(true);
            resumeBackgroundRequests++;
            reportResumeRequest(adUnitId);
            AppOpenAd.load(requestApplication, adUnitId, request, requestCallback);
        } catch (RuntimeException error) {
            if (!resumeRequestReported.get()) {
                // Preparation sent nothing. Keep any previous late result and the bounded
                // opportunity, without inventing a vendor request or failure in the funnel.
                Log.w(TAG, "resume request preparation failed", error);
                scheduleBackgroundLoad(AdBehavior.number("app_open.load.offline_recheck_ms"));
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
        if (SystemClock.elapsedRealtime() - resumeFetchStartedAtMs
                >= AdBehavior.number("app_open.cache.max_age_ms")) {
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
        java.util.List<Long> backoff = resumeFailureBackoffMs();
        long delayMs = backoff.get(Math.min(resumeFailureStreak, backoff.size() - 1));
        resumeFailureStreak = Math.min(resumeFailureStreak + 1, backoff.size());
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
    private void showTestIdAlert(Context context, String id) {
        Notification notification = new NotificationCompat.Builder(context, "warning_ads")
                .setContentTitle("Found test ad id")
                .setContentText("AppResume Ads: " + id)
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
        notificationManager.notify(RESUME_ADS, notification);
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
    public boolean isAdAvailable() {
        long loadTime = appResumeLoadTime;
        boolean wasLoadTimeLessThanNHoursAgo = (new Date()).getTime() - loadTime < AdBehavior.number("app_open.cache.max_age_ms");
        Log.d(TAG, "isAdAvailable: " + wasLoadTimeLessThanNHoursAgo);
        return appResumeAd != null
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

    public void showAdIfAvailable() {
        if (currentActivity == null || AdGate.isPurchased(currentActivity)) {
            if (fullScreenContentCallback != null && enableScreenContentCallback) {
                fullScreenContentCallback.onAdDismissedFullScreenContent();
            }
            return;
        }

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

        Log.d(TAG, "showAdIfAvailable: " + ProcessLifecycleOwner.get().getLifecycle().getCurrentState());
        Log.d(TAG, "showAd isSplash: false");
        if (!ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            Log.d(TAG, "showAdIfAvailable: return");
            if (fullScreenContentCallback != null && enableScreenContentCallback) {
                fullScreenContentCallback.onAdDismissedFullScreenContent();
            }

            return;
        }

        if (!isShowingAd && isAdAvailable()) {
            Log.d(TAG, "Will show ad isSplash:false");
            showResumeAds();

        } else {
            Log.d(TAG, "Ad is not ready");
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
                || !isAdAvailable() || !canShowResumeOn(host)) return;

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
                        || appResumeAd != ad || !isAdAvailable() || generation != resumeFetchGeneration
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
                    resumeFetchHandler.postDelayed(() -> dismissResumeDialog(ownedDialog), AdBehavior.number("app_open.presentation.loading_timeout_ms"));
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
                    || appResumeAd != ad || !isAdAvailable() || generation != resumeFetchGeneration
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
                resumeFetchHandler.postDelayed(dispatch, AdBehavior.number("app_open.presentation.pre_show_delay_ms"));
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


        String appOpenReason = appOpenPolicySkipReasonFor(currentActivity);
        if (appOpenReason != null) {
            reportResumePolicySkip(appOpenReason);
            return;
        }

        if (!isInitialized) return;
        if (!isAdAvailable()) return;
        final Activity host = currentActivity;
        final AppOpenAd candidate = appResumeAd;
        final long hostGeneration = resumeHostGeneration;
        final long fetchGeneration = resumeFetchGeneration;
        // ON_START precedes Activity/process RESUMED. Only this already-eligible return is
        // deferred; a blocked one-shot above cannot become eligible after its snapshot clears.
        resumeFetchHandler.post(() -> {
            if (host == currentActivity && hostGeneration == resumeHostGeneration
                    && fetchGeneration == resumeFetchGeneration && appResumeAd == candidate) {
        showAdIfAvailable();
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
        resumeBackgroundFirstEligibleMs = SystemClock.elapsedRealtime() + delayMs;
        resumeBackgroundDeadlineMs = resumeBackgroundFirstEligibleMs
                + AdBehavior.number("app_open.load.background_retry_window_ms");
        scheduleBackgroundLoad(delayMs);
    }

    private void scheduleBackgroundLoad(long delayMs) {
        clearBackgroundLoadSchedule();
        long nowMs = SystemClock.elapsedRealtime();
        // A callback carried from the previous stay may retry, but cannot shorten this stay's
        // captured initial delay. The window begins at that first eligible instant.
        delayMs = Math.max(delayMs, resumeBackgroundFirstEligibleMs - nowMs);
        if (!resumeBackground || !isAppResumeEnabled || !isInitialized
                || resumeBackgroundRequests >= AdBehavior.number("app_open.load.max_background_requests")
                || nowMs >= resumeBackgroundDeadlineMs
                || delayMs >= resumeBackgroundDeadlineMs - nowMs) return;
        pendingBackgroundLoad = () -> {
            pendingBackgroundLoad = null;
            if (!resumeBackground || SystemClock.elapsedRealtime() >= resumeBackgroundDeadlineMs
                    || resumeBackgroundRequests >= AdBehavior.number("app_open.load.max_background_requests")) return;
            resumeDispatchAllowed = true;
            try {
        fetchAd();
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


    private void dismissResumeDialog(Dialog ownedDialog) {
        if (dialog == ownedDialog) dialog = null;
        if (ownedDialog == null) return;
        try {
            if (ownedDialog.isShowing()) ownedDialog.dismiss();
        } catch (RuntimeException error) {
            Log.w(TAG, "Resume loading dialog could not be dismissed", error);
        }
    }


}
