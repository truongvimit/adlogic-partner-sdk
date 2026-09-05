package com.ads.module.admob;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.ads.module.R;
import com.ads.module.dialog.ResumeLoadingDialog;
import com.ads.module.event.ERainLogEventManager;
import com.ads.module.helper.AdGate;
import com.ads.module.helper.AdSkipReason;
import com.ads.module.tracking.AdTracking;
import com.google.android.gms.ads.AdActivity;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.appopen.AppOpenAd;

import io.trackkit.AdFormat;
import io.trackkit.Tracker;
import io.trackkit.TrackkitEvents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * App-open ads for foreground resume. Initialization can preload; lifecycle or an explicit
 * {@link #showResumeAdIfAvailable()} call decides when to present. Resume mode defaults to enabled,
 * and shown/dismissed/failure notifications default to disabled until explicitly requested.
 * Raw cold-start app-open splash APIs are removed in 5.1.0; they do not alias resume presentation.
 * A dispatched fullscreen ad holds process-wide ownership until a terminal callback. A missing
 * terminal callback is not recovered by elapsed time or host lifecycle in this contract.
 * Host callback exceptions are logged; they do not release a presentation or become vendor errors.
 * Canonical show/closed events require actual vendor callbacks and retain the accepted load's
 * attempt ID even when host notifications are disabled. Pre-show completion reports a skip.
 */
public class AppOpenManager implements Application.ActivityLifecycleCallbacks, LifecycleObserver {
    private static final String TAG = "AppOpenManager";
    public static final String AD_UNIT_ID_TEST = "ca-app-pub-3940256099942544/3419835294";

    private static volatile AppOpenManager INSTANCE;
    private final AppResumeLoadOwner resumeLoadOwner;
    private final FullscreenPresentationOwner presentationOwner = FullscreenPresentationOwner.getInstance();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private FullScreenContentCallback fullScreenContentCallback;

    private Activity currentActivity;

    private volatile boolean isInitialized = false;// on  - off ad resume on app
    private boolean lifecycleHooksAttached = false;
    private boolean isAppResumeEnabled = true;
    private boolean enableScreenContentCallback = false;
    private boolean disableAdResumeByClickAction = false;
    private final List<Class> disabledAppOpenList;
    /**
     * Constructor
     */
    private AppOpenManager() {
        this(AppOpenAd::load);
    }

    AppOpenManager(AppResumeAdLoader resumeAdLoader) {
        this.resumeLoadOwner = new AppResumeLoadOwner((context, unitId, request, callback) -> {
            try {
                if (currentActivity != null && Arrays.asList(
                        currentActivity.getResources().getStringArray(R.array.list_id_test)).contains(unitId)) {
                    showTestIdAlert(currentActivity, unitId);
                }
            } catch (Exception error) {
                Log.w(TAG, "Test-id notification unavailable", error);
            }
            resumeAdLoader.load(context, unitId, request, callback);
        });
        disabledAppOpenList = new ArrayList<>();
    }

    public static synchronized AppOpenManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AppOpenManager();
        }
        return INSTANCE;
    }

    /**
     * Initializes the resume unit and attaches activity, process and request-policy observers once.
     * A blank unit still attaches the observers; no request is sent until a unit is available.
     * Reinitializing with the same unit preserves a valid resume buffer or active request. A changed
     * unit invalidates the previous buffer and pending fill. The durable resume mode is preserved.
     * <p>
     * Initialization can preload through the consent, premium, network, cache and cooldown gates;
     * it never presents an ad. Later consent or entitlement changes use the same request gates.
     * Calls on the main thread apply immediately; calls from another thread enqueue the update.
     */
    public void init(Application application, String appOpenAdId) {
        runOnMain(() -> {
            disableAdResumeByClickAction = false;
            isInitialized = true;
            resumeLoadOwner.initialize(application, appOpenAdId);
            // Blank units still attach the hooks: remote config commonly supplies the unit later.
            if (lifecycleHooksAttached) return;
            lifecycleHooksAttached = true;
            application.registerActivityLifecycleCallbacks(this);
            ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
        });
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }

    public boolean isInitialized() {
        return isInitialized;
    }


    /**
     * Pauses or reactivates the resume owner without registering lifecycle observers again.
     * Pausing invalidates the buffer and pending fill; reactivation attempts a gated preload while
     * retaining the durable resume mode. Off-main calls enqueue the update on the main thread.
     */
    public void setInitialized(boolean initialized) {
        runOnMain(() -> {
            isInitialized = initialized;
            resumeLoadOwner.setInitialized(initialized);
        });
    }

    /** Enables shown/dismissed/failure notifications for subsequent presentations; default false. */
    public void setEnableScreenContentCallback(boolean enableScreenContentCallback) {
        this.enableScreenContentCallback = enableScreenContentCallback;
    }

    /** True for reserved/active interstitial or rewarded ads, or separate legacy suppression. */
    public boolean isInterstitialShowing() {
        return presentationOwner.isInterstitialBusy();
    }

    /**
     * Legacy suppression for an external integration; this does not own an SDK presentation.
     * Raising suppression arms its own 90-second timeout. Neither clearing it nor its timeout
     * can release an interstitial, rewarded or app-open presentation owned by the SDK.
     *
     * @deprecated SDK adapters acquire a captured presentation lease instead. This descriptor
     * remains only for integrations that need temporary resume suppression.
     */
    @Deprecated
    public void setInterstitialShowing(boolean interstitialShowing) {
        presentationOwner.setLegacyInterstitialSuppressed(interstitialShowing);
    }

    /**
     * Call disable ad resume when click a button, auto enable ad resume in next start
     */
    public void disableAdResumeByClickAction() {
        disableAdResumeByClickAction = true;
    }

    public void setDisableAdResumeByClickAction(boolean disableAdResumeByClickAction) {
        this.disableAdResumeByClickAction = disableAdResumeByClickAction;
    }

    /**
     * True while an app-open presentation is reserved or dispatched in this process.
     */
    public boolean isShowingAd() {
        return presentationOwner.isAppOpenBusy();
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
     * Whether a resume ad is suppressed while {@code activity} is on top. A null host or GMA
     * {@link AdActivity} is always suppressed. This query does not consume a return or request ads.
     * <p>
     * The one place this is answered. A host that runs its own resume flow asks here rather than
     * keeping a second list: the two drifted, and a screen excluded from app-open ads still got a
     * welcome ad launched over it.
     */
    public boolean isResumeSuppressedFor(Activity activity) {
        if (activity == null || activity instanceof AdActivity) {
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
     * Disables resume requests and shows until {@link #enableAppResume()} is called. Clears the
     * resume buffer and invalidates pending fills, so late vendor callbacks cannot restore it.
     * An ad already presenting completes through its existing callbacks.
     * <p>
     * The entry-mode owner controls this durable switch. To suppress one return, use
     * {@link #disableAdResumeByClickAction()}; enabling the durable switch does not restore an
     * earlier mode choice. Calls on the main thread apply immediately; off-main calls enqueue it.
     */
    public void disableAppResume() {
        runOnMain(() -> {
            isAppResumeEnabled = false;
            resumeLoadOwner.setEnabled(false);
        });
    }

    /**
     * Enables the durable resume mode and attempts a preload through the current consent, premium,
     * network, cache and cooldown gates. Repeated calls share any active request. This method does
     * not itself present an ad. Only the entry-mode owner should change this switch.
     * Calls on the main thread apply immediately; off-main calls enqueue the update.
     *
     * @see #disableAppResume()
     */
    public void enableAppResume() {
        runOnMain(() -> {
            isAppResumeEnabled = true;
            resumeLoadOwner.setEnabled(true);
        });
    }

    /**
     * Applies the resume unit after trimming surrounding whitespace. An unchanged unit is a no-op.
     * A changed unit clears the old buffer, invalidates pending fills and attempts a gated preload
     * under the existing resume mode. Null or blank clears the unit and prevents further requests.
     * Calls on the main thread apply immediately; off-main calls enqueue the update.
     */
    public void setAppResumeAdId(String appResumeAdId) {
        runOnMain(() -> {
            resumeLoadOwner.setUnitId(appResumeAdId);
        });
    }

    public void setFullScreenContentCallback(FullScreenContentCallback callback) {
        this.fullScreenContentCallback = callback;
    }

    public void removeFullScreenContentCallback() {
        this.fullScreenContentCallback = null;
    }

    /** Invalidates the resume buffer and pending fill; an active presentation keeps its owner. */
    public void releaseCachedAds() {
        runOnMain(resumeLoadOwner::invalidate);
    }

    /**
     * Attempts a resume preload through the current consent, premium, mode, network, freshness
     * and backoff gates. Repeated callers share the pending load; this never shows an ad.
     * Calls from a worker thread enqueue the request on the main thread.
     */
    public void fetchResumeAd() {
        resumeLoadOwner.request();
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
        notificationManager.notify(Admob.RESUME_ADS, notification);
    }

    /**
     * Whether the resume buffer is fresh and matches the current personalization choice.
     * This is not authorization to show: {@link #showResumeAdIfAvailable()} rechecks eligibility.
     * This query never starts a request or reserves a presentation.
     */
    public boolean isResumeAdAvailable() {
        return resumeLoadOwner.isAdAvailable();
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
        currentActivity = activity;
        Log.d(TAG, "onActivityStarted: " + currentActivity);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        currentActivity = activity;
        Log.d(TAG, "onActivityResumed: " + currentActivity);
        if (!(activity instanceof AdActivity)) {
            fetchResumeAd();
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        // Only forget the activity we are actually tracking: destroys arrive after the next
        // activity has already started, and clearing unconditionally left the manager believing
        // no activity existed mid-session.
        if (currentActivity == activity) {
            currentActivity = null;
            Log.d(TAG, "onActivityDestroyed: null");
        }
    }

    /**
     * Attempts to present a ready resume ad on the currently tracked host. Preserves the resume
     * mode and lifecycle gates; if no buffer is ready, requests a preload without a later auto-show.
     * Explicit calls respect the same activity exclusions as lifecycle resume; a vendor AdActivity
     * cannot host an app-open show. Preparation rechecks exclusions before consuming the fill.
     * Calls from a worker thread enqueue the attempt on the main thread.
     */
    public void showResumeAdIfAvailable() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnMain(this::showResumeAdIfAvailable);
            return;
        }
        ResumePresentation presentation = new ResumePresentation(
                currentActivity, fullScreenContentCallback, enableScreenContentCallback);
        AdSkipReason policyReason = resumeLoadOwner.showSkipReason();
        if (policyReason != null) {
            presentation.rejectBeforeShow(policyReason, false);
            return;
        }
        if (currentActivity == null || currentActivity instanceof AdActivity) {
            presentation.rejectBeforeShow(AdSkipReason.INVALID_HOST, true);
            return;
        }
        if (AdGate.isPurchased(currentActivity)) {
            presentation.rejectBeforeShow(AdSkipReason.PURCHASED, true);
            return;
        }
        if (!ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            presentation.rejectBeforeShow(AdSkipReason.PROCESS_NOT_RESUMED, true);
            return;
        }
        if (presentationOwner.isBusy()) {
            presentation.rejectBeforeShow(AdSkipReason.PRESENTATION_BUSY, false);
            fetchResumeAd();
            return;
        }
        if (isResumeSuppressedFor(currentActivity)) {
            presentation.rejectBeforeShow(AdSkipReason.SUPPRESSED_BY_FLOW, false);
            return;
        }
        if (!isResumeAdAvailable()) {
            presentation.rejectBeforeShow(AdSkipReason.NOT_READY, false);
            fetchResumeAd();
            return;
        }
        if (currentActivity.isFinishing() || currentActivity.isDestroyed()) {
            presentation.rejectBeforeShow(AdSkipReason.INVALID_HOST, false);
            return;
        }
        if (presentation.acquire()) presentation.show();
    }

    private void reportResumeSkipped(AdSkipReason reason) {
        AdTracking.skipped("app_resume", AdFormat.APP_OPEN, reason.getKey());
    }

    /** Captures the lease, vendor ad, host, dialog and callback for exactly one show attempt. */
    private final class ResumePresentation extends FullScreenContentCallback {
        private final Activity activity;
        private final FullScreenContentCallback callback;
        private final boolean notifyContent;
        private FullscreenPresentationOwner.Lease lease;
        private Dialog presentationDialog;
        private AppOpenAd ad;
        private AppResumeLoadOwner.LoadedAd selected;
        private String adUnitId;
        private boolean terminal;
        private boolean invoked;
        private boolean presented;

        ResumePresentation(Activity activity, FullScreenContentCallback callback, boolean notifyContent) {
            this.activity = activity;
            this.callback = callback;
            this.notifyContent = notifyContent;
        }

        boolean acquire() {
            lease = presentationOwner.tryAcquire(AdFormat.APP_OPEN,
                    () -> runOnMain(() -> rejectBeforeShow(AdSkipReason.EXPIRED, true)));
            if (lease == null) rejectBeforeShow(AdSkipReason.PRESENTATION_BUSY, false);
            return lease != null;
        }

        void show() {
            // Capture the selected fill before a cosmetic window can synchronously replace it.
            selected = resumeLoadOwner.peekForShow();
            if (selected == null) {
                AdSkipReason reason = resumeLoadOwner.showSkipReason();
                rejectBeforeShow(reason == null ? AdSkipReason.NOT_READY : reason, false);
                return;
            }
            ad = selected.getAd();
            adUnitId = ad.getAdUnitId();
            try {
                presentationDialog = new ResumeLoadingDialog(activity);
                presentationDialog.show();
            } catch (Exception error) {
                dismissDialog();
                Log.w(TAG, "App-open loading dialog unavailable", error);
            }
            if (terminal || !lease.isCurrent()) return;
            AdSkipReason hostReason = hostRejection();
            if (hostReason != null) {
                rejectBeforeShow(hostReason, true);
                return;
            }
            try {
                ad.setFullScreenContentCallback(this);
                try {
                    ad.setImmersiveMode(true);
                } catch (RuntimeException error) {
                    Log.w(TAG, "App-open immersive mode unavailable", error);
                }
                if (terminal) return;
                hostReason = hostRejection();
                if (hostReason != null) {
                    rejectBeforeShow(hostReason, true);
                    return;
                }
                if (resumeLoadOwner.takeForShow(selected, lease::start) == null) {
                    AdSkipReason reason = resumeLoadOwner.rejectionFor(selected);
                    rejectBeforeShow(reason == null ? AdSkipReason.EXPIRED : reason, true);
                    return;
                }
                invoked = true;
                ad.show(activity);
            } catch (RuntimeException error) {
                if (!invoked) {
                    Log.w(TAG, "App-open callback setup failed", error);
                    rejectBeforeShow(AdSkipReason.PREPARATION_FAILED, true);
                } else {
                    failPresentation(new AdError(0,
                            error.getMessage() == null ? "App-open show failed" : error.getMessage(), TAG), null);
                }
            }
        }

        private AdSkipReason hostRejection() {
            if (activity == null || currentActivity != activity || activity instanceof AdActivity
                    || activity.isFinishing() || activity.isDestroyed()) return AdSkipReason.INVALID_HOST;
            if (isResumeSuppressedFor(activity)) return AdSkipReason.SUPPRESSED_BY_FLOW;
            if (!ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                return AdSkipReason.PROCESS_NOT_RESUMED;
            }
            return null;
        }

        private boolean ownsPresentation() {
            return !terminal && invoked && lease.isCurrent();
        }

        private void dismissDialog() {
            try {
                if (presentationDialog != null && presentationDialog.isShowing()) presentationDialog.dismiss();
            } catch (Exception ignored) {
            }
        }

        private boolean finishPresentation() {
            if (terminal) return false;
            terminal = true;
            if (lease != null) lease.finish();
            dismissDialog();
            return true;
        }

        private void rejectBeforeShow(AdSkipReason reason, boolean notifyCompatibility) {
            if (!finishPresentation()) return;
            reportResumeSkipped(reason);
            if (notifyCompatibility && callback != null && notifyContent) {
                notifyHost(callback::onAdDismissedFullScreenContent);
            }
        }

        private void failPresentation(AdError error, Integer errorCode) {
            if (!finishPresentation()) return;
            Tracker.track(new TrackkitEvents.Ad.ShowFailed(selected.getContext().getPlacement(),
                    selected.getContext().getFormat(), adUnitId, errorCode, selected.getAttemptId()));
            if (callback != null && notifyContent) notifyHost(() -> callback.onAdFailedToShowFullScreenContent(error));
            fetchResumeAd();
        }

        private void notifyHost(Runnable notification) {
            try {
                notification.run();
            } catch (Exception error) {
                // A synchronous host callback can run inside vendor.show; it is not vendor failure.
                Log.e(TAG, "App-open host callback failed", error);
            }
        }

        @Override
        public void onAdDismissedFullScreenContent() {
            runOnMain(() -> {
                if (!invoked || !finishPresentation()) return;
                if (presented) {
                    Tracker.track(new TrackkitEvents.Ad.Closed(selected.getContext().getPlacement(),
                            selected.getContext().getFormat(), adUnitId, selected.getAttemptId()));
                }
                if (callback != null && notifyContent) notifyHost(callback::onAdDismissedFullScreenContent);
                fetchResumeAd();
            });
        }

        @Override
        public void onAdFailedToShowFullScreenContent(AdError error) {
            runOnMain(() -> {
                if (invoked) failPresentation(error, error.getCode());
            });
        }

        @Override
        public void onAdShowedFullScreenContent() {
            runOnMain(() -> {
                if (!ownsPresentation() || !lease.presented()) return;
                presented = true;
                Tracker.track(new TrackkitEvents.Ad.Show(selected.getContext().getPlacement(),
                        selected.getContext().getFormat(), adUnitId, selected.getAttemptId()));
                if (callback != null && notifyContent) notifyHost(callback::onAdShowedFullScreenContent);
            });
        }

        @Override
        public void onAdClicked() {
            runOnMain(() -> {
                if (!ownsPresentation()) return;
                ERainLogEventManager.logClickAdsEvent(activity, adUnitId, selected.getContext());
                if (callback != null) notifyHost(callback::onAdClicked);
            });
        }

        @Override
        public void onAdImpression() {
            runOnMain(() -> {
                if (ownsPresentation() && callback != null) notifyHost(callback::onAdImpression);
            });
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void onResume() {
        if (!isAppResumeEnabled) {
            Log.d(TAG, "onResume: app resume is disabled");
            return;
        }

        if (presentationOwner.isBusy()) {
            Log.d(TAG, "onResume: fullscreen presentation is busy");
            reportResumeSkipped(AdSkipReason.PRESENTATION_BUSY);
            return;
        }

        if (disableAdResumeByClickAction) {
            Log.d(TAG, "onResume:ad resume disable ad by action");
            disableAdResumeByClickAction = false;
            reportResumeSkipped(AdSkipReason.RETURNING_FROM_AD_CLICK);
            return;
        }

        // Foreground can be reported before any activity has started, and the checks below all
        // dereference it.
        if (currentActivity == null || currentActivity instanceof AdActivity) {
            Log.d(TAG, "onResume: no application host");
            reportResumeSkipped(AdSkipReason.INVALID_HOST);
            return;
        }

        // Through the same query the welcome-resume path uses. This loop compared class NAMES
        // exactly while isResumeSuppressedFor uses isInstance, so registering a base class
        // suppressed one path and not the other.
        if (isResumeSuppressedFor(currentActivity)) {
            Log.d(TAG, "onStart: activity is disabled");
            reportResumeSkipped(AdSkipReason.SUPPRESSED_BY_FLOW);
            return;
        }

        Log.d(TAG, "onStart: show resume ads :" + currentActivity.getClass().getName());
        showResumeAdIfAvailable();
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void onStop() {
        Log.d(TAG, "onStop: app stop");

    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    public void onPause() {
        Log.d(TAG, "onPause");
    }

}
