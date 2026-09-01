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
import android.util.Log;

import androidx.annotation.NonNull;
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

public class AppOpenManager implements Application.ActivityLifecycleCallbacks, LifecycleObserver {
    private static final String TAG = "AppOpenManager";
    public static final String AD_UNIT_ID_TEST = "ca-app-pub-3940256099942544/3419835294";

    private static volatile AppOpenManager INSTANCE;
    private AppOpenAd appResumeAd = null;
    private AppOpenAd splashAd = null;
    private AppOpenAd.AppOpenAdLoadCallback loadCallback;

    private AppOpenAd.AppOpenAdLoadCallback loadCallbackHigh;
    private AppOpenAd.AppOpenAdLoadCallback loadCallbackMedium;
    private AppOpenAd.AppOpenAdLoadCallback loadCallbackAll;

    private AppOpenAd.AppOpenAdLoadCallback loadCallbackOpen;
    private FullScreenContentCallback fullScreenContentCallback;

    private String appResumeAdId;
    private String splashAdId;

    private Activity currentActivity;

    private Application myApplication;

    private static boolean isShowingAd = false;
    private long appResumeLoadTime = 0;
    private long splashLoadTime = 0;
    private int splashTimeout = 0;

    /**
     * How long a full-screen ad may hold the suppression flags before they are assumed stuck.
     * Longer than any real interstitial, including a 30s rewarded video plus its end card.
     */
    private static final long INTERSTITIAL_SHOWING_TIMEOUT_MS = 90_000;

    private final Handler interstitialWatchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable interstitialWatchdogRunnable;

    private static final Handler showingAdWatchdogHandler = new Handler(Looper.getMainLooper());
    private static Runnable showingAdWatchdogRunnable;

    private boolean isInitialized = false;// on  - off ad resume on app
    private boolean lifecycleHooksAttached = false;
    private boolean isAppResumeEnabled = true;
    private boolean isInterstitialShowing = false;
    private boolean enableScreenContentCallback = false; // default =  true when use splash & false after show splash
    private boolean disableAdResumeByClickAction = false;
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
        disableAdResumeByClickAction = false;
        this.appResumeAdId = appOpenAdId;
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
    }

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
     * {@link #showAdIfAvailable(boolean)}. It used to be cleared only by the dismiss and
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
    }

    /** @see #disableAppResume() — same ownership rule; this is not an "undo my suppression". */
    public void enableAppResume() {
        isAppResumeEnabled = true;
    }

    public void setSplashActivity(Class splashActivity, String adId, int timeoutInMillis) {
        this.splashActivity = splashActivity;
        splashAdId = adId;
        this.splashTimeout = timeoutInMillis;
    }

    public void setAppResumeAdId(String appResumeAdId) {
        this.appResumeAdId = appResumeAdId;
    }

    public void setFullScreenContentCallback(FullScreenContentCallback callback) {
        this.fullScreenContentCallback = callback;
    }

    public void removeFullScreenContentCallback() {
        this.fullScreenContentCallback = null;
    }

    /**
     * Drops every buffered app-open ad — call it when the user turns premium, or the ad loaded
     * before they paid is still shown to them.
     */
    public void releaseCachedAds() {
        appResumeAd = null;
        splashAd = null;
        splashAdHigh = null;
        splashAdMedium = null;
        splashAdAll = null;
        splashAdOpen = null;
        splashAdInter = null;
    }

    /**
     * Request an ad
     */
    public void fetchAd(final boolean isSplash) {
        Log.d(TAG, "fetchAd: isSplash = " + isSplash);
        if (isAdAvailable(isSplash)) {
            return;
        }
        // GMA rejects a blank unit with "Cannot determine request type" on every call.
        String adUnitId = isSplash ? splashAdId : appResumeAdId;
        if (adUnitId == null || adUnitId.trim().isEmpty()) {
            Log.d(TAG, "fetchAd: no ad unit set yet (isSplash = " + isSplash + ")");
            return;
        }

        loadCallback =
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
                            AppOpenManager.this.appResumeAd = ad;
                            AppOpenManager.this.appResumeAd.setOnPaidEventListener(adValue -> {
                                ERainLogEventManager.logPaidAdImpression(myApplication.getApplicationContext(),
                                        adValue,
                                        ad.getAdUnitId(),
                                        ad.getResponseInfo()
                                                .getMediationAdapterClassName(), AdType.APP_OPEN);
                            });
                            AppOpenManager.this.appResumeLoadTime = (new Date()).getTime();
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
                    }


                };
        if (currentActivity != null) {
            if (AdGate.isPurchased(currentActivity))
                return;
            if (Arrays.asList(currentActivity.getResources().getStringArray(R.array.list_id_test)).contains(isSplash ? splashAdId : appResumeAdId)) {
                showTestIdAlert(currentActivity, isSplash, isSplash ? splashAdId : appResumeAdId);
            }

        }
        AdRequest request = getAdRequest();
        AppOpenAd.load(myApplication, isSplash ? splashAdId : appResumeAdId, request, loadCallback);
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
        AdRequest.Builder builder = new AdRequest.Builder();
        // Same rule as every other format: a refusal means non-personalized, not no request.
        Admob.applyPersonalization(builder);
        return builder.build();
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
        currentActivity = activity;
        Log.d(TAG, "onActivityStarted: " + currentActivity);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        currentActivity = activity;
        Log.d(TAG, "onActivityResumed: " + currentActivity);
        if (splashActivity == null) {
            if (!activity.getClass().getName().equals(AdActivity.class.getName())) {
                Log.d(TAG, "onActivityResumed 1: with " + activity.getClass().getName());
                fetchAd(false);
            }
        } else {
            if (!activity.getClass().getName().equals(splashActivity.getName()) && !activity.getClass().getName().equals(AdActivity.class.getName())) {
                Log.d(TAG, "onActivityResumed 2: with " + activity.getClass().getName());
                fetchAd(false);
            }
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

    public void showAdIfAvailable(final boolean isSplash) {
        if (currentActivity == null || AdGate.isPurchased(currentActivity)) {
            if (fullScreenContentCallback != null && enableScreenContentCallback) {
                fullScreenContentCallback.onAdDismissedFullScreenContent();
            }
            return;
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
            if (!isSplash) {
                fetchAd(false);
            }
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

    private void showResumeAds() {
        if (appResumeAd == null || currentActivity == null || AdGate.isPurchased(currentActivity)) {
            return;
        }
        if (ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {

            try {
                dismissDialogLoading();
                dialog = new ResumeLoadingDialog(currentActivity);
                try {
                    dialog.show();
                } catch (Exception e) {
                    if (fullScreenContentCallback != null && enableScreenContentCallback) {
                        fullScreenContentCallback.onAdDismissedFullScreenContent();

                    }
                    return;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (appResumeAd != null) {
                appResumeAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                    @Override
                    public void onAdDismissedFullScreenContent() {
                        appResumeAd = null;
                        if (fullScreenContentCallback != null && enableScreenContentCallback) {
                            fullScreenContentCallback.onAdDismissedFullScreenContent();
                        }
                        setShowingAd(false);
                        fetchAd(false);

                        dismissDialogLoading();
                    }

                    @Override
                    public void onAdFailedToShowFullScreenContent(AdError adError) {
                        Log.e(TAG, "onAdFailedToShowFullScreenContent: " + adError.getMessage());
                        if (fullScreenContentCallback != null && enableScreenContentCallback) {
                            fullScreenContentCallback.onAdFailedToShowFullScreenContent(adError);
                        }

                        if (currentActivity != null && !currentActivity.isDestroyed() && dialog != null && dialog.isShowing()) {
                            Log.d(TAG, "dismiss dialog loading ad open: ");
                            try {
                                dialog.dismiss();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        appResumeAd = null;
                        setShowingAd(false);
                        fetchAd(false);
                    }

                    @Override
                    public void onAdShowedFullScreenContent() {
                        if (fullScreenContentCallback != null && enableScreenContentCallback) {
                            fullScreenContentCallback.onAdShowedFullScreenContent();
                        }
                        setShowingAd(true);
                        appResumeAd = null;
                    }

                    @Override
                    public void onAdClicked() {
                        super.onAdClicked();
                        if (currentActivity != null) {
                            ERainLogEventManager.logClickAdsEvent(currentActivity, appResumeAdId);
                            if (fullScreenContentCallback != null) {
                                fullScreenContentCallback.onAdClicked();
                            }
                        }
                    }

                    @Override
                    public void onAdImpression() {
                        super.onAdImpression();
                        if (currentActivity != null) {
                            if (fullScreenContentCallback != null) {
                                fullScreenContentCallback.onAdImpression();
                            }
                        }
                    }
                });
                appResumeAd.setImmersiveMode(true);
                appResumeAd.show(currentActivity);
            } else {
                dismissDialogLoading();
            }
        }
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
                                disableAdResumeByClickAction = true;

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
                                disableAdResumeByClickAction = true;

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
                                disableAdResumeByClickAction = true;

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
                                disableAdResumeByClickAction = true;

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
                        disableAdResumeByClickAction = true;
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
                                disableAdResumeByClickAction = true;
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

        if (splashActivity != null && splashActivity.getName().equals(currentActivity.getClass().getName())) {
            String adId = splashAdId;
            if (adId == null) {
                Log.e(TAG, "splash ad id must not be null");
            }
            Log.d(TAG, "onStart: load and show splash ads");
            loadAndShowSplashAds(adId);
            return;
        }

        Log.d(TAG, "onStart: show resume ads :" + currentActivity.getClass().getName());
        showAdIfAvailable(false);
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

