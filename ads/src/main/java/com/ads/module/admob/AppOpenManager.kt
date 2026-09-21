package com.ads.module.admob

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import com.ads.module.R
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.dialog.ResumeLoadingDialog
import com.ads.module.engine.adMainScope
import com.ads.module.engine.launchAfter
import com.ads.module.event.ERainLogEventManager
import com.ads.module.funtion.AdType
import com.ads.module.helper.AdGate
import com.ads.module.tracking.AdTracking
import com.google.android.gms.ads.AdActivity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import io.trackkit.AdFormat
import io.trackkit.PlacementRegistry
import io.trackkit.Tracker
import io.trackkit.TrackkitEvents
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** App-resume ads: loads one app-open ad while the app is in the background, shows it on return. */
object AppOpenManager : Application.ActivityLifecycleCallbacks, LifecycleObserver {
    private const val TAG = "AppOpenManager"
    private const val RESUME_ADS = 1
    private const val RESUME_PLACEMENT = "open_resume"

    @JvmStatic
    fun getInstance(): AppOpenManager = this

    private var appResumeAd: AppOpenAd? = null

    private var resumeBackgroundDeadlineMs = 0L
    private var resumeBackgroundFirstEligibleMs = 0L
    private var resumeBackgroundRequests = 0
    private var resumeBackground = false
    private var resumeDispatchAllowed = false
    private var pendingBackgroundLoad: Job? = null
    private var resumeFetchGeneration = 0L
    private var resumeFetchPending = false
    private var resumeFetchDeadlineMs = 0L
    private var resumeFetchStartedAtMs = 0L
    private var resumeFailureStreak = 0
    private var resumeRetryAfterMs = 0L
    private var resumeFetchTimeout: Job? = null

    private var fullScreenContentCallback: FullScreenContentCallback? = null

    private var appResumeAdId: String? = null
    private var resumeUnitFromConfig = false

    /**
     * The activity on top, tracked from `onActivityStarted` — already set when process ON_START
     * fires, which a tracker fed from `onActivityResumed` is not.
     */
    var currentActivity: Activity? = null
        private set
    private var resumedActivity: Activity? = null
    private var resumeHostGeneration = 0L
    private var activeResumeAttempt: Any? = null
    private var pendingResumeShow: Job? = null
    private var pendingResumeCancellation: Runnable? = null

    private var myApplication: Application? = null

    private var showingAd = false
    private var appResumeLoadTime = 0L

    private var initialized = false
    private var lifecycleHooksAttached = false
    private var isAppResumeEnabled = true
    private var interstitialShowing = false
    private var enableScreenContentCallback = false
    private val pendingResumeSkipReason = AtomicReference<String?>()

    @Volatile
    private var currentReturnSkipReason: String? = null
    private var resumeReturnGeneration = 0L
    private var resumeSkipPolicy: ResumeSkipPolicy? = null
    private val disabledAppOpenList = ArrayList<Class<*>>()
    private var dialog: Dialog? = null

    val isInitialized: Boolean
        get() = initialized

    /** True while an interstitial/reward presentation or its preparation owns the screen. */
    val isInterstitialShowing: Boolean
        get() = interstitialShowing

    /** True while an app-open ad owns the screen. */
    val isShowingAd: Boolean
        get() = showingAd

    /**
     * The one-shot reason the next host return is skipped, or the one captured for the return in
     * progress. A pure read: querying it never spends the one-shot.
     */
    val resumeReturnSkipReason: String?
        get() = pendingResumeSkipReason.get() ?: currentReturnSkipReason

    /** Observes the process lifecycle; idempotent, and a blank id only defers the first request. */
    fun init(application: Application?, appOpenAdId: String?) {
        setAppResumeAdId(appOpenAdId)
        // Hooks register even with a blank id: the id usually arrives later from remote config.
        initialized = true
        if (lifecycleHooksAttached) return
        lifecycleHooksAttached = true
        myApplication = application
        checkNotNull(application).registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun setInitialized(initialized: Boolean) {
        this.initialized = initialized
        if (!initialized) {
            cancelBackgroundLoad()
            cancelResumeFetch(false)
        }
    }

    /** Shown/dismissed/failed forwarding, captured per resume attempt when it starts. */
    fun setEnableScreenContentCallback(enableScreenContentCallback: Boolean) {
        this.enableScreenContentCallback = enableScreenContentCallback
    }

    /** Only the presentation's real completion may clear this: elapsed time proves nothing. */
    fun setInterstitialShowing(interstitialShowing: Boolean) {
        this.interstitialShowing = interstitialShowing
    }

    /** Marks the next host return as coming from an ad click; does not enable app-open mode. */
    fun disableAdResumeByClickAction() {
        skipNextResume("returning_from_ad_click")
    }

    /** `false` clears both the pending skip and the current-return snapshot. */
    fun setDisableAdResumeByClickAction(disabled: Boolean) {
        if (disabled) {
            disableAdResumeByClickAction()
        } else {
            pendingResumeSkipReason.set(null)
            currentReturnSkipReason = null
            resumeReturnGeneration++
        }
    }

    /**
     * Suppresses the next host return without changing OPEN/WELCOME/NONE mode; the latest reason
     * wins. A host return consumes it even while another gate blocks resume; AdActivity does not.
     */
    fun skipNextResume(reason: String) {
        require(reason.trim { it <= ' ' }.isNotEmpty()) { "Resume skip reason must not be blank" }
        pendingResumeSkipReason.set(reason)
    }

    /** Installs the optional host policy; null removes it. Call from Application setup on main. */
    fun setResumeSkipPolicy(policy: ResumeSkipPolicy?) {
        resumeSkipPolicy = policy
    }

    /**
     * Pure eligibility shared by app-open and a host's own welcome flow: never consumes a return
     * or emits telemetry. A throwing host policy fails open; core consent still applies.
     */
    fun resumeSkipReasonFor(activity: Activity): String? {
        if (activity is AdActivity) return "ad_activity"
        val returnReason = resumeReturnSkipReason
        if (returnReason != null) return returnReason
        val core = AdGate.skipReason(activity, true, true, false)
        if (core != null) return core.key
        val policy = resumeSkipPolicy ?: return null
        return try {
            policy.skipReasonFor(activity)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Resume policy failed; keeping prior extension fallback", error)
            null
        }
    }

    fun disableAppResumeWithActivity(activityClass: Class<*>?) {
        val excluded = checkNotNull(activityClass)
        Log.d(TAG, "disableAppResumeWithActivity: " + excluded.name)
        disabledAppOpenList.add(excluded)
    }

    fun enableAppResumeWithActivity(activityClass: Class<*>?) {
        val excluded = checkNotNull(activityClass)
        Log.d(TAG, "enableAppResumeWithActivity: " + excluded.name)
        disabledAppOpenList.remove(excluded)
    }

    /** The single answer for "is resume suppressed on this screen", for app-open and host flows. */
    fun isResumeSuppressedFor(activity: Activity?): Boolean {
        if (activity == null) return true
        return disabledAppOpenList.any { it.isInstance(activity) }
    }

    /**
     * Turns app-resume ads off for the rest of the process — the durable entry-mode switch. For
     * "not on this one return" use [disableAdResumeByClickAction]: [enableAppResume] has no memory.
     */
    fun disableAppResume() {
        isAppResumeEnabled = false
        cancelBackgroundLoad()
        cancelResumeFetch(false)
    }

    /** Enables resume ads; the next eligible background stay owns the first load. */
    fun enableAppResume() {
        isAppResumeEnabled = true
    }

    /**
     * Re-points the resume unit at what `open_resume` declares, empty id included. No-op until a
     * resume unit exists, and while `open_resume` carries no ad unit id.
     */
    fun applyRemoteConfig() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            adMainScope.launch { applyRemoteConfig() }
            return
        }
        if (!resumeUnitFromConfig && appResumeAdId.isNullOrEmpty()) return
        val unit = AdRemoteConfig.getInstance().ads[RESUME_PLACEMENT]
        if (unit == null || unit.waterfallIds.isEmpty()) return
        val ids = AdGate.adUnitIds(RESUME_PLACEMENT)
        resumeUnitFromConfig = true
        setAppResumeAdId(if (ids.isEmpty()) "" else ids[0])
    }

    /** Changes the unit on main; pending results for the previous unit cannot fill this buffer. */
    fun setAppResumeAdId(appResumeAdId: String?) {
        if (this.appResumeAdId == appResumeAdId) return
        cancelResumeFetch(true)
        appResumeAd = null
        this.appResumeAdId = appResumeAdId
    }

    /** Listener for future resume attempts; an attempt in progress keeps the one it captured. */
    fun setFullScreenContentCallback(callback: FullScreenContentCallback?) {
        fullScreenContentCallback = callback
    }

    fun removeFullScreenContentCallback() {
        fullScreenContentCallback = null
    }

    /** Drops the buffered app-open ad — call it when the user turns premium. */
    fun releaseCachedAds() {
        cancelResumeFetch(true)
        appResumeAd = null
    }

    /**
     * Only the delayed background opportunity dispatches a request. Foreground cancels scheduling,
     * never a dispatched result; a timeout frees a retry but a late fill can still be cached.
     */
    fun fetchAd() {
        if (AdGate.areRequestsHeld()) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            adMainScope.launch { fetchAd() }
            return
        }
        Log.d(TAG, "fetchAd")
        if (!resumeDispatchAllowed) return
        if (isAdAvailable() || resumeFetchPending) return
        if (!canFetchResume(false)) return
        if (!AdGate.isNetworkAvailable(checkNotNull(myApplication))) {
            scheduleBackgroundLoad(AdBehavior.number("app_open.load.offline_recheck_ms"))
            return
        }
        // GMA rejects a blank unit with "Cannot determine request type" on every call.
        val adUnitId = appResumeAdId
        if (adUnitId == null || adUnitId.trim { it <= ' ' }.isEmpty()) {
            Log.d(TAG, "fetchAd: no ad unit set yet")
            return
        }

        if (SystemClock.elapsedRealtime() < resumeRetryAfterMs) {
            Log.d(TAG, "fetchAd: resume backoff")
            scheduleBackgroundLoad(resumeRetryAfterMs - SystemClock.elapsedRealtime())
            return
        }
        val requestApplication = checkNotNull(myApplication)
        val personalized = ConsentCenter.canPersonalize()
        val generation = resumeFetchGeneration + 1
        val resumeTerminalReported = AtomicBoolean(false)
        val resumeRequestReported = AtomicBoolean(false)
        val resumeRequestedAtMs = Date().time
        val requestCallback = object : AppOpenAd.AppOpenAdLoadCallback() {
            override fun onAdLoaded(ad: AppOpenAd) {
                Log.d(TAG, "onAppOpenAdLoaded")
                if (!resumeTerminalReported.compareAndSet(false, true)) return
                reportResumeLoaded(adUnitId, resumeRequestedAtMs)
                if (!canContinueResumeFetch(generation, adUnitId, personalized)) {
                    reportResumeSkip(adUnitId, "fill_discarded")
                    return
                }
                ad.setOnPaidEventListener { adValue ->
                    ERainLogEventManager.logPaidAdImpression(
                        requestApplication.applicationContext, adValue, ad.adUnitId,
                        ad.responseInfo.mediationAdapterClassName, AdType.APP_OPEN,
                    )
                }
                appResumeAd = ad
                // A delayed callback must not renew the ad's original lifetime.
                appResumeLoadTime = resumeRequestedAtMs
                cancelResumeFetch(true)
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                Log.d(TAG, "onAppOpenAdFailedToLoad: message " + loadAdError.message)
                if (!resumeTerminalReported.compareAndSet(false, true)) return
                reportResumeLoadFailed(adUnitId, loadAdError.code)
                if (ownsResumeFetch(generation)) {
                    if (!canFetchResume(false)) {
                        if (ownsResumeFetch(generation)) cancelResumeFetch(false)
                    } else {
                        failResumeFetch(generation, "vendor_code=" + loadAdError.code)
                    }
                }
            }
        }
        val activity = currentActivity
        if (activity != null) {
            if (AdGate.engineBlocked(activity)) {
                if (ownsResumeFetch(generation)) cancelResumeFetch(false)
                return
            }
            if (activity.resources.getStringArray(R.array.list_id_test).contains(adUnitId)) {
                try {
                    showTestIdAlert(activity, adUnitId)
                } catch (error: RuntimeException) {
                    Log.w(TAG, "resume test-id notification unavailable", error)
                }
            }
        }
        try {
            val request = adRequest()
            if (appResumeAdId != adUnitId || !canFetchResume(false) ||
                ConsentCenter.canPersonalize() != personalized
            ) {
                return
            }
            if (!AdGate.isNetworkAvailable(requestApplication)) {
                scheduleBackgroundLoad(AdBehavior.number("app_open.load.offline_recheck_ms"))
                return
            }
            // Ownership changes only when a replacement is actually about to dispatch.
            resumeFetchGeneration = generation
            resumeFetchPending = true
            resumeFetchStartedAtMs = SystemClock.elapsedRealtime()
            resumeFetchDeadlineMs =
                resumeFetchStartedAtMs + AdBehavior.number("app_open.load.timeout_ms")
            val timeoutMs = AdBehavior.number("app_open.load.timeout_ms")
            resumeFetchTimeout = launchAfter(timeoutMs) {
                if (!ownsResumeFetch(generation)) return@launchAfter
                if (!canFetchResume(false)) {
                    if (ownsResumeFetch(generation)) cancelResumeFetch(false)
                } else {
                    failResumeFetch(generation, "timeout", retainLateResult = true)
                    reportResumeSkip(adUnitId, "load_timeout")
                }
            }
            Log.d(TAG, "resume load dispatch generation=$generation")
            // After every gate, so the funnel counts requests that actually reach GMA.
            resumeRequestReported.set(true)
            resumeBackgroundRequests++
            reportResumeRequest(adUnitId)
            AppOpenAd.load(requestApplication, adUnitId, request, requestCallback)
        } catch (error: RuntimeException) {
            if (error is CancellationException) throw error
            if (!resumeRequestReported.get()) {
                Log.w(TAG, "resume request preparation failed", error)
                scheduleBackgroundLoad(AdBehavior.number("app_open.load.offline_recheck_ms"))
                return
            }
            if (resumeTerminalReported.compareAndSet(false, true)) {
                reportResumeLoadFailed(adUnitId, null)
            }
            failResumeFetch(generation, "dispatch_error=" + error.javaClass.simpleName)
        }
    }

    /** True when a buffered app-open ad exists and is still within its cache lifetime. */
    fun isAdAvailable(): Boolean {
        val loadTime = appResumeLoadTime
        val fresh = Date().time - loadTime < AdBehavior.number("app_open.cache.max_age_ms")
        Log.d(TAG, "isAdAvailable: $fresh")
        return appResumeAd != null && fresh
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {
        // Activity start precedes process ON_START: a quick return must beat the delayed load.
        cancelBackgroundLoad()
        currentActivity = activity
        Log.d(TAG, "onActivityStarted: $currentActivity")
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        resumedActivity = activity
        Log.d(TAG, "onActivityResumed: $currentActivity")
        captureResumeReturn(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        val shown = dialog
        if (shown != null && shown.ownerActivity === activity) dismissResumeDialog(shown)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity === activity) {
            resumedActivity = null
            resumeHostGeneration++
            pendingResumeCancellation?.run()
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        if (resumedActivity === activity) {
            resumedActivity = null
            resumeHostGeneration++
        }
        // Destroys arrive after the next activity started: forget only the one still tracked.
        if (currentActivity === activity) {
            currentActivity = null
            Log.d(TAG, "onActivityDestroyed: null")
        }
    }

    fun showAdIfAvailable() {
        val activity = currentActivity
        if (activity == null || AdGate.isPurchased(activity)) {
            forwardDismissIfEnabled()
            return
        }

        val reason = (if (!isAppResumeEnabled) "disabled_config" else resumeSkipReasonFor(activity))
            ?: appOpenPolicySkipReasonFor(activity)
        if (reason != null) {
            Log.d(TAG, "showAdIfAvailable: resume policy blocked $reason")
            reportResumePolicySkip(reason)
            forwardDismissIfEnabled()
            return
        }

        val processState = ProcessLifecycleOwner.get().lifecycle.currentState
        Log.d(TAG, "showAdIfAvailable: $processState")
        Log.d(TAG, "showAd isSplash: false")
        if (!processState.isAtLeast(Lifecycle.State.STARTED)) {
            Log.d(TAG, "showAdIfAvailable: return")
            forwardDismissIfEnabled()
            return
        }

        if (!showingAd && isAdAvailable()) {
            Log.d(TAG, "Will show ad isSplash:false")
            showResumeAds()
        } else {
            Log.d(TAG, "Ad is not ready")
        }
    }

    @Suppress("DEPRECATION")
    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onResume() {
        cancelBackgroundLoad()
        captureResumeReturn(currentActivity)
        if (!isAppResumeEnabled) {
            Log.d(TAG, "onResume: app resume is disabled")
            return
        }
        if (interstitialShowing) {
            Log.d(TAG, "onResume: interstitial is showing")
            return
        }
        val host = currentActivity ?: run {
            Log.d(TAG, "onResume: no current activity")
            return
        }
        if (host is AdActivity) {
            Log.d(TAG, "onResume: vendor ad Activity is not a host return")
            return
        }
        if (isResumeSuppressedFor(host)) {
            Log.d(TAG, "onStart: activity is disabled")
            return
        }
        val sharedReason = resumeSkipReasonFor(host)
        if (sharedReason != null) {
            reportResumePolicySkip(sharedReason)
            return
        }
        val appOpenReason = appOpenPolicySkipReasonFor(host)
        if (appOpenReason != null) {
            reportResumePolicySkip(appOpenReason)
            return
        }

        if (!initialized) return
        if (!isAdAvailable()) return
        val candidate = appResumeAd
        val hostGeneration = resumeHostGeneration
        val fetchGeneration = resumeFetchGeneration
        // ON_START precedes RESUMED; only this already-eligible return is deferred.
        adMainScope.launch {
            if (host === currentActivity && hostGeneration == resumeHostGeneration &&
                fetchGeneration == resumeFetchGeneration && appResumeAd === candidate
            ) {
                showAdIfAvailable()
            }
        }
    }

    @Suppress("DEPRECATION")
    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onStop() {
        Log.d(TAG, "onStop: app stop")
        currentReturnSkipReason = null
        if (resumeBackground) return
        resumeBackground = true
        val activity = currentActivity
        if (!isAppResumeEnabled || !initialized || showingAd || interstitialShowing ||
            pendingResumeSkipReason.get() != null || activity is AdActivity
        ) {
            return
        }
        if (activity != null && (isResumeSuppressedFor(activity) ||
                resumeSkipReasonFor(activity) != null ||
                appOpenPolicySkipReasonFor(activity) != null)
        ) {
            return
        }
        val delayMs = AdRemoteConfig.normalizeAppResumeLoadDelayMs(
            AdRemoteConfig.getInstance().appResumeLoadDelayMs,
        )
        resumeBackgroundRequests = 0
        resumeBackgroundFirstEligibleMs = SystemClock.elapsedRealtime() + delayMs
        resumeBackgroundDeadlineMs = resumeBackgroundFirstEligibleMs +
            AdBehavior.number("app_open.load.background_retry_window_ms")
        scheduleBackgroundLoad(delayMs)
    }

    private fun resumeFailureBackoffMs(): List<Long> =
        AdBehavior.document.snapshot.longList("app_open.load.failure_backoff_ms", emptyList())

    private fun appOpenPolicySkipReasonFor(activity: Activity): String? {
        val policy = resumeSkipPolicy ?: return null
        return try {
            policy.appOpenSkipReasonFor(activity)
        } catch (error: RuntimeException) {
            Log.w(TAG, "App-open policy failed; keeping prior extension fallback", error)
            null
        }
    }

    private fun captureResumeReturn(activity: Activity?) {
        if (activity == null || activity is AdActivity) return
        val reason = pendingResumeSkipReason.getAndSet(null) ?: return
        currentReturnSkipReason = reason
        val generation = ++resumeReturnGeneration
        // The snapshot serves this dispatch's observers only, not the rest of the foreground.
        adMainScope.launch {
            if (resumeReturnGeneration == generation) currentReturnSkipReason = null
        }
        Log.d(TAG, "resume skip consumed by host return: $reason")
    }

    private fun reportResumePolicySkip(reason: String) = reportResumeSkip(appResumeAdId, reason)

    private fun reportResumeSkip(adUnitId: String?, reason: String) {
        AdTracking.skipped(resumePlacementFor(adUnitId), AdFormat.APP_OPEN, reason)
    }

    private fun resumePlacementFor(adUnitId: String?): String =
        PlacementRegistry.placementOf(adUnitId, RESUME_PLACEMENT)

    // Resume has no AdCallback for TrackingAdCallback to decorate, so these emit its funnel.
    private fun reportResumeRequest(adUnitId: String) {
        AdTracking.request(resumePlacementFor(adUnitId), AdFormat.APP_OPEN, adUnitId)
    }

    private fun reportResumeLoaded(adUnitId: String, requestedAtMs: Long) {
        if (!telemetryEnabled()) return
        Tracker.track(
            TrackkitEvents.Ad.Loaded(
                resumePlacementFor(adUnitId), AdFormat.APP_OPEN, adUnitId,
                System.currentTimeMillis() - requestedAtMs,
            ),
        )
    }

    private fun reportResumeLoadFailed(adUnitId: String, errorCode: Int?) {
        if (!telemetryEnabled()) return
        Tracker.track(
            TrackkitEvents.Ad.LoadFailed(
                resumePlacementFor(adUnitId), AdFormat.APP_OPEN, adUnitId, errorCode,
            ),
        )
    }

    private fun reportResumeShown(adUnitId: String?) {
        if (!telemetryEnabled()) return
        Tracker.track(
            TrackkitEvents.Ad.Show(resumePlacementFor(adUnitId), AdFormat.APP_OPEN, adUnitId),
        )
    }

    private fun reportResumeShowFailed(adUnitId: String?, errorCode: Int?) {
        if (!telemetryEnabled()) return
        Tracker.track(
            TrackkitEvents.Ad.ShowFailed(
                resumePlacementFor(adUnitId), AdFormat.APP_OPEN, adUnitId, errorCode,
            ),
        )
    }

    private fun telemetryEnabled() = AdBehavior.bool("diagnostics.ads_telemetry_enabled")

    private fun canFetchResume(checkNetwork: Boolean): Boolean {
        val application = myApplication
        if (application == null || !initialized) return false
        val reason = AdGate.skipReason(
            application, isAppResumeEnabled,
            AdGate.placementPassesUaGate(RESUME_PLACEMENT), checkNetwork,
        )
        if (reason != null) Log.d(TAG, "fetchAd: resume gate=" + reason.key)
        return reason == null
    }

    private fun ownsResumeFetch(generation: Long): Boolean =
        resumeFetchPending && resumeFetchGeneration == generation

    private fun canContinueResumeFetch(
        generation: Long,
        adUnitId: String,
        personalized: Boolean,
    ): Boolean {
        // A timeout frees the loading slot, but only replacement/invalidation revokes a result.
        if (resumeFetchGeneration != generation || resumeFetchDeadlineMs == 0L) return false
        if (SystemClock.elapsedRealtime() - resumeFetchStartedAtMs >=
            AdBehavior.number("app_open.cache.max_age_ms")
        ) {
            cancelResumeFetch(false)
            return false
        }
        if (appResumeAdId != adUnitId || !canFetchResume(false) ||
            ConsentCenter.canPersonalize() != personalized
        ) {
            if (resumeFetchGeneration == generation) cancelResumeFetch(false)
            return false
        }
        return resumeFetchGeneration == generation
    }

    private fun cancelResumeFetch(resetBackoff: Boolean) {
        resumeFetchGeneration++
        resumeFetchPending = false
        resumeFetchDeadlineMs = 0
        resumeFetchTimeout?.cancel()
        resumeFetchTimeout = null
        if (resetBackoff) {
            clearBackgroundLoadSchedule()
            resumeBackgroundDeadlineMs = 0
            resumeFailureStreak = 0
            resumeRetryAfterMs = 0
        }
    }

    private fun failResumeFetch(
        generation: Long,
        reason: String,
        retainLateResult: Boolean = false,
    ) {
        if (!ownsResumeFetch(generation)) return
        val backoff = resumeFailureBackoffMs()
        val delayMs = backoff[minOf(resumeFailureStreak, backoff.size - 1)]
        resumeFailureStreak = minOf(resumeFailureStreak + 1, backoff.size)
        if (retainLateResult) {
            resumeFetchPending = false
            resumeFetchTimeout?.cancel()
            resumeFetchTimeout = null
        } else {
            cancelResumeFetch(false)
        }
        resumeRetryAfterMs = SystemClock.elapsedRealtime() + delayMs
        Log.w(TAG, "resume load failed $reason; retry after ${delayMs}ms")
        scheduleBackgroundLoad(delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun showTestIdAlert(context: Context, id: String) {
        val notification = NotificationCompat.Builder(context, "warning_ads")
            .setContentTitle("Found test ad id")
            .setContentText("AppResume Ads: $id")
            .setSmallIcon(R.drawable.ic_warning)
            .build()
        val notificationManager = NotificationManagerCompat.from(context)
        notification.flags = notification.flags or Notification.FLAG_AUTO_CANCEL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "warning_ads", "Warning Ads", NotificationManager.IMPORTANCE_LOW,
            )
            notificationManager.createNotificationChannel(channel)
        }
        notificationManager.notify(RESUME_ADS, notification)
    }

    private fun adRequest(): AdRequest = AdRequest.Builder().build()

    private fun forwardDismissIfEnabled() {
        val delegate = fullScreenContentCallback
        if (delegate != null && enableScreenContentCallback) {
            delegate.onAdDismissedFullScreenContent()
        }
    }

    private fun canShowResumeOn(host: Activity?): Boolean =
        initialized && isAppResumeEnabled && !interstitialShowing &&
            host != null && host === currentActivity && host === resumedActivity &&
            !host.isFinishing && !host.isDestroyed && host !is AdActivity &&
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !isResumeSuppressedFor(host) &&
            resumeSkipReasonFor(host) == null && appOpenPolicySkipReasonFor(host) == null

    private fun showResumeAds() {
        val host = currentActivity
        val ad = appResumeAd
        if (showingAd || activeResumeAttempt != null || ad == null ||
            !isAdAvailable() || !canShowResumeOn(host) || host == null
        ) {
            return
        }

        val unit = appResumeAdId
        val generation = resumeFetchGeneration
        val hostGeneration = resumeHostGeneration
        val delegate = fullScreenContentCallback
        val forwardContent = enableScreenContentCallback
        val attempt = Any()
        activeResumeAttempt = attempt
        showingAd = true

        var loading: Dialog? = null
        try {
            loading = ResumeLoadingDialog(host)
            loading.setOwnerActivity(host)
            dialog = loading
            loading.show()
        } catch (error: RuntimeException) {
            // The loading indicator is cosmetic; a usable vendor ad does not depend on it.
            Log.w(TAG, "Resume loading dialog unavailable", error)
        }
        val ownedDialog = loading
        val callback = object : FullScreenContentCallback() {
            private var shown = false

            override fun onAdDismissedFullScreenContent() {
                if (!finishResumeAttempt(attempt, ownedDialog)) return
                if (delegate != null && forwardContent) {
                    forwardResumeCallback { delegate.onAdDismissedFullScreenContent() }
                }
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                if (!finishResumeAttempt(attempt, ownedDialog)) return
                reportResumeShowFailed(unit, error.code)
                if (delegate != null && forwardContent) {
                    forwardResumeCallback { delegate.onAdFailedToShowFullScreenContent(error) }
                }
            }

            override fun onAdShowedFullScreenContent() {
                if (activeResumeAttempt !== attempt || shown) return
                shown = true
                reportResumeShown(unit)
                // GMA can report shown before it paints: the backdrop stays until stop/end/timeout.
                if (delegate != null && forwardContent) {
                    forwardResumeCallback { delegate.onAdShowedFullScreenContent() }
                }
            }

            override fun onAdClicked() {
                if (activeResumeAttempt !== attempt) return
                disableAdResumeByClickAction()
                ERainLogEventManager.logClickAdsEvent(host, unit)
                if (delegate != null) forwardResumeCallback { delegate.onAdClicked() }
            }

            override fun onAdImpression() {
                if (activeResumeAttempt === attempt && delegate != null) {
                    forwardResumeCallback { delegate.onAdImpression() }
                }
            }
        }
        val cancelBeforeShow = Runnable {
            if (finishResumeAttempt(attempt, ownedDialog) && delegate != null && forwardContent) {
                forwardResumeCallback { delegate.onAdDismissedFullScreenContent() }
            }
        }
        val dispatch: () -> Unit = dispatch@{
            if (activeResumeAttempt !== attempt) return@dispatch
            pendingResumeShow = null
            pendingResumeCancellation = null
            try {
                if (!canShowResumeOn(host) || hostGeneration != resumeHostGeneration ||
                    appResumeAd !== ad || !isAdAvailable() || generation != resumeFetchGeneration ||
                    unit != appResumeAdId
                ) {
                    cancelBeforeShow.run()
                    return@dispatch
                }
                appResumeAd = null
                ad.show(host)
            } catch (error: RuntimeException) {
                if (error is CancellationException) throw error
                callback.onAdFailedToShowFullScreenContent(showThrew(error))
            } finally {
                // GMA opens asynchronously. Bound only the cosmetic window, never ad ownership.
                if (ownedDialog != null && dialog === ownedDialog && ownedDialog.isShowing) {
                    val loadingTimeoutMs =
                        AdBehavior.number("app_open.presentation.loading_timeout_ms")
                    launchAfter(loadingTimeoutMs) { dismissResumeDialog(ownedDialog) }
                }
            }
        }
        try {
            ad.fullScreenContentCallback = callback
            try {
                ad.setImmersiveMode(true)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Resume immersive mode unavailable", error)
            }
            // Dialog/vendor setup may synchronously change the host, policy, unit or cache.
            if (activeResumeAttempt !== attempt || !canShowResumeOn(host) ||
                hostGeneration != resumeHostGeneration ||
                appResumeAd !== ad || !isAdAvailable() || generation != resumeFetchGeneration ||
                unit != appResumeAdId
            ) {
                cancelBeforeShow.run()
                return
            }
            if (ownedDialog != null && ownedDialog.isShowing) {
                val preShowDelayMs = AdBehavior.number("app_open.presentation.pre_show_delay_ms")
                val pending = launchAfter(preShowDelayMs) { dispatch() }
                ownedDialog.setOnCancelListener {
                    if (pendingResumeShow === pending) cancelBeforeShow.run()
                }
                pendingResumeShow = pending
                pendingResumeCancellation = cancelBeforeShow
            } else {
                dispatch()
            }
        } catch (error: RuntimeException) {
            callback.onAdFailedToShowFullScreenContent(showThrew(error))
        }
    }

    private fun showThrew(error: RuntimeException) =
        AdError(0, "App-open show threw: " + error.javaClass.simpleName, "ERainStudio")

    private fun forwardResumeCallback(callback: () -> Unit) {
        try {
            callback()
        } catch (error: RuntimeException) {
            // Host code is not a vendor show failure and cannot release a visible ad.
            Log.w(TAG, "App-open host callback failed", error)
        }
    }

    private fun finishResumeAttempt(attempt: Any, ownedDialog: Dialog?): Boolean {
        if (activeResumeAttempt !== attempt) return false
        pendingResumeShow?.cancel()
        pendingResumeShow = null
        pendingResumeCancellation = null
        activeResumeAttempt = null
        showingAd = false
        dismissResumeDialog(ownedDialog)
        return true
    }

    private fun scheduleBackgroundLoad(delay: Long) {
        clearBackgroundLoadSchedule()
        val nowMs = SystemClock.elapsedRealtime()
        // A carried-over retry cannot shorten this stay's captured initial delay.
        val delayMs = maxOf(delay, resumeBackgroundFirstEligibleMs - nowMs)
        if (!resumeBackground || !isAppResumeEnabled || !initialized ||
            backgroundRequestsSpent() || nowMs >= resumeBackgroundDeadlineMs ||
            delayMs >= resumeBackgroundDeadlineMs - nowMs
        ) {
            return
        }
        pendingBackgroundLoad = launchAfter(delayMs) {
            pendingBackgroundLoad = null
            if (!resumeBackground || SystemClock.elapsedRealtime() >= resumeBackgroundDeadlineMs ||
                backgroundRequestsSpent()
            ) {
                return@launchAfter
            }
            resumeDispatchAllowed = true
            try {
                fetchAd()
            } finally {
                resumeDispatchAllowed = false
            }
        }
    }

    private fun backgroundRequestsSpent(): Boolean =
        resumeBackgroundRequests >= AdBehavior.number("app_open.load.max_background_requests")

    private fun clearBackgroundLoadSchedule() {
        pendingBackgroundLoad?.cancel()
        pendingBackgroundLoad = null
    }

    private fun cancelBackgroundLoad() {
        resumeBackground = false
        resumeBackgroundDeadlineMs = 0
        clearBackgroundLoadSchedule()
    }

    private fun dismissResumeDialog(ownedDialog: Dialog?) {
        if (dialog === ownedDialog) dialog = null
        if (ownedDialog == null) return
        try {
            if (ownedDialog.isShowing) ownedDialog.dismiss()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Resume loading dialog could not be dismissed", error)
        }
    }
}
