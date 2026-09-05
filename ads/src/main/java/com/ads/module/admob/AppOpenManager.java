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
import com.google.android.gms.ads.AdActivity;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.appopen.AppOpenAd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * App-open ads for foreground resume. Initialization can preload; lifecycle or an explicit
 * {@link #showResumeAdIfAvailable()} call decides when to present. Resume mode defaults to enabled,
 * and full-screen content notifications default to disabled until explicitly requested.
 * Raw cold-start app-open splash APIs are removed in 5.1.0; they do not alias resume presentation.
 */
public class AppOpenManager implements Application.ActivityLifecycleCallbacks, LifecycleObserver {
    private static final String TAG = "AppOpenManager";
    public static final String AD_UNIT_ID_TEST = "ca-app-pub-3940256099942544/3419835294";

    private static volatile AppOpenManager INSTANCE;
    private final AppResumeLoadOwner resumeLoadOwner;
    private AppOpenAd presentingResumeAd;
    private FullScreenContentCallback fullScreenContentCallback;

    private Activity currentActivity;

    private static boolean isShowingAd = false;

    /**
     * How long a full-screen ad may hold the suppression flags before they are assumed stuck.
     * Longer than any real interstitial, including a 30s rewarded video plus its end card.
     */
    private static final long INTERSTITIAL_SHOWING_TIMEOUT_MS = 90_000;

    private final Handler interstitialWatchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable interstitialWatchdogRunnable;

    private static final Handler showingAdWatchdogHandler = new Handler(Looper.getMainLooper());
    private static Runnable showingAdWatchdogRunnable;

    private volatile boolean isInitialized = false;// on  - off ad resume on app
    private boolean lifecycleHooksAttached = false;
    private boolean isAppResumeEnabled = true;
    private boolean isInterstitialShowing = false;
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
            if (currentActivity != null && Arrays.asList(
                    currentActivity.getResources().getStringArray(R.array.list_id_test)).contains(unitId)) {
                showTestIdAlert(currentActivity, unitId);
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
        else interstitialWatchdogHandler.post(action);
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

    public boolean isInterstitialShowing() {
        return isInterstitialShowing;
    }

    /**
     * Marks whether a full-screen ad (or its loading dialog) owns the screen.
     * <p>
     * Raising it arms a watchdog. The flag is set from several places — the loading dialog, the
     * GMA show callback — while only some of the failure paths lowered it again, so one dropped
     * callback used to suppress every resume ad for the rest of the process. The watchdog bounds
     * that to {@link #INTERSTITIAL_SHOWING_TIMEOUT_MS} instead of forever; the normal terminal
     * callbacks still lower it immediately and cancel the watchdog.
     */
    public void setInterstitialShowing(boolean interstitialShowing) {
        isInterstitialShowing = interstitialShowing;
        if (interstitialShowing) {
            armInterstitialWatchdog();
        } else {
            cancelInterstitialWatchdog();
        }
    }

    private void armInterstitialWatchdog() {
        cancelInterstitialWatchdog();
        interstitialWatchdogRunnable = () -> {
            if (!isInterstitialShowing) {
                return;
            }
            Log.w(TAG, "interstitial-showing flag stuck for "
                    + INTERSTITIAL_SHOWING_TIMEOUT_MS + "ms — clearing it. "
                    + "A show path raised it and never reported a terminal callback.");
            isInterstitialShowing = false;
            interstitialWatchdogRunnable = null;
        };
        interstitialWatchdogHandler.postDelayed(
                interstitialWatchdogRunnable, INTERSTITIAL_SHOWING_TIMEOUT_MS);
    }

    private void cancelInterstitialWatchdog() {
        if (interstitialWatchdogRunnable != null) {
            interstitialWatchdogHandler.removeCallbacks(interstitialWatchdogRunnable);
            interstitialWatchdogRunnable = null;
        }
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
     * True while an app-open ad owns the screen.
     */
    public boolean isShowingAd() {
        return isShowingAd;
    }

    /**
     * Marks whether an app-open ad owns the screen.
     * <p>
     * Static and process-wide, and it gates every later resume ad at
     * {@link #showResumeAdIfAvailable()}. It used to be cleared only by the dismiss and
     * fail-to-show callbacks, so a show that reported neither blocked resume ads permanently. Same
     * watchdog as the interstitial flag.
     */
    private static void setShowingAd(boolean showing) {
        isShowingAd = showing;
        if (showingAdWatchdogRunnable != null) {
            showingAdWatchdogHandler.removeCallbacks(showingAdWatchdogRunnable);
            showingAdWatchdogRunnable = null;
        }
        if (!showing) {
            return;
        }
        showingAdWatchdogRunnable = () -> {
            if (!isShowingAd) {
                return;
            }
            Log.w(TAG, "app-open showing flag stuck for " + INTERSTITIAL_SHOWING_TIMEOUT_MS
                    + "ms — clearing it. A show path raised it and never reported a terminal callback.");
            isShowingAd = false;
            showingAdWatchdogRunnable = null;
        };
        showingAdWatchdogHandler.postDelayed(showingAdWatchdogRunnable, INTERSTITIAL_SHOWING_TIMEOUT_MS);
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
     * Calls from a worker thread enqueue the attempt on the main thread.
     */
    public void showResumeAdIfAvailable() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnMain(this::showResumeAdIfAvailable);
            return;
        }
        if (!resumeLoadOwner.canShow()) return;
        if (currentActivity == null || AdGate.isPurchased(currentActivity)) {
            if (fullScreenContentCallback != null && enableScreenContentCallback) {
                fullScreenContentCallback.onAdDismissedFullScreenContent();
            }
            return;
        }
        if (!ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            if (fullScreenContentCallback != null && enableScreenContentCallback) {
                fullScreenContentCallback.onAdDismissedFullScreenContent();
            }
            return;
        }
        if (!isShowingAd && isResumeAdAvailable()) {
            showResumeAds();
        } else {
            fetchResumeAd();
        }
    }

    Dialog dialog = null;

    private void showResumeAds() {
        if (!resumeLoadOwner.canShow() || !resumeLoadOwner.isAdAvailable()
                || currentActivity == null || currentActivity.isFinishing() || currentActivity.isDestroyed()
                || isShowingAd) return;
        if (!ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;

        try {
            dismissDialogLoading();
            dialog = new ResumeLoadingDialog(currentActivity);
            dialog.show();
        } catch (Exception e) {
            dismissDialogLoading();
            if (fullScreenContentCallback != null && enableScreenContentCallback) {
                fullScreenContentCallback.onAdDismissedFullScreenContent();
            }
            return;
        }
        final AppOpenAd ad = resumeLoadOwner.takeForShow();
        if (ad == null) {
            dismissDialogLoading();
            return;
        }
        final Activity activity = currentActivity;
        final Dialog presentationDialog = dialog;
        final FullScreenContentCallback callback = fullScreenContentCallback;
        final boolean notifyContent = enableScreenContentCallback;
        presentingResumeAd = ad;
        // Reserve before calling the vendor, including the interval before its shown callback.
        setShowingAd(true);
        FullScreenContentCallback presentation = new FullScreenContentCallback() {
            private boolean terminal;
            private boolean shown;

            private boolean ownsPresentation() {
                return !terminal && presentingResumeAd == ad;
            }

            private boolean finishPresentation() {
                if (!ownsPresentation()) return false;
                terminal = true;
                presentingResumeAd = null;
                setShowingAd(false);
                try {
                    if (presentationDialog != null && presentationDialog.isShowing()) presentationDialog.dismiss();
                } catch (Exception ignored) {
                }
                if (dialog == presentationDialog) dialog = null;
                return true;
            }

            @Override
            public void onAdDismissedFullScreenContent() {
                runOnMain(() -> {
                    if (!finishPresentation()) return;
                    if (callback != null && notifyContent) callback.onAdDismissedFullScreenContent();
                    fetchResumeAd();
                });
            }

            @Override
            public void onAdFailedToShowFullScreenContent(AdError error) {
                runOnMain(() -> {
                    if (!finishPresentation()) return;
                    if (callback != null && notifyContent) callback.onAdFailedToShowFullScreenContent(error);
                    fetchResumeAd();
                });
            }

            @Override
            public void onAdShowedFullScreenContent() {
                runOnMain(() -> {
                    if (!ownsPresentation() || shown) return;
                    shown = true;
                    if (callback != null && notifyContent) callback.onAdShowedFullScreenContent();
                });
            }

            @Override
            public void onAdClicked() {
                runOnMain(() -> {
                    if (!ownsPresentation()) return;
                    ERainLogEventManager.logClickAdsEvent(activity, ad.getAdUnitId());
                    if (callback != null) callback.onAdClicked();
                });
            }

            @Override
            public void onAdImpression() {
                runOnMain(() -> {
                    if (ownsPresentation() && callback != null) callback.onAdImpression();
                });
            }
        };
        try {
            ad.setFullScreenContentCallback(presentation);
            ad.setImmersiveMode(true);
            ad.show(activity);
        } catch (RuntimeException error) {
            presentation.onAdFailedToShowFullScreenContent(new AdError(
                    0, error.getMessage() == null ? "App-open show failed" : error.getMessage(), TAG));
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void onResume() {
        if (!isAppResumeEnabled) {
            Log.d(TAG, "onResume: app resume is disabled");
            return;
        }

        if (isInterstitialShowing) {
            Log.d(TAG, "onResume: interstitial is showing");
            return;
        }

        if (disableAdResumeByClickAction) {
            Log.d(TAG, "onResume:ad resume disable ad by action");
            disableAdResumeByClickAction = false;
            return;
        }

        // Foreground can be reported before any activity has started, and the checks below all
        // dereference it.
        if (currentActivity == null) {
            Log.d(TAG, "onResume: no current activity");
            return;
        }

        // Through the same query the welcome-resume path uses. This loop compared class NAMES
        // exactly while isResumeSuppressedFor uses isInstance, so registering a base class
        // suppressed one path and not the other.
        if (isResumeSuppressedFor(currentActivity)) {
            Log.d(TAG, "onStart: activity is disabled");
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

    private void dismissDialogLoading() {
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }



}
