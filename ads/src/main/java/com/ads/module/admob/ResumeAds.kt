package com.ads.module.admob

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.RestrictTo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ads.module.config.AdRemoteConfig
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.tracking.AdTracking
import io.trackkit.AdFormat
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Native and interstitial resume; app-open stays with [AppOpenManager]. Native requests nothing
 * while the user is away: every return opens its screen, which requests its own ad as it shows.
 * Interstitial requests once the user has been away for `open_resume.app_resume_load_delay_ms`, and
 * only a return after that delay shows it, waiting for its fill with the placement's load-and-show
 * timeout.
 *
 * Native follows the Activity callbacks [AppOpenManager] forwards, not the process lifecycle, whose
 * stop lags the last pause by 700 ms and never comes for a landing page inside the process. The
 * user is away when no Activity is started any more (a configuration change aside), or when an
 * [AdSurfaces] Activity came in front of the host after a click on a native ad: GMA's AdActivity,
 * which also carries Play's install sheet, or a network's landing page, see-through or not. An
 * overlay with no ad Activity (a dialog, a permission prompt) is not a trip. The return is the next
 * Activity outside [AdSurfaces] to resume: decided right then, opened once no [AdSurfaces] Activity
 * is visible any more, so the screen never opens over an ad. Interstitial keeps the process
 * lifecycle.
 */
object ResumeAds : DefaultLifecycleObserver {

    /** The screen native resume opens. OnboardKit registers one; without it native resume shows nothing. */
    interface NativeScreen {
        /** Opens the screen over [host]; the screen requests its own ad. */
        fun show(host: Activity)

        /** [show] where [afterAdClick] says the user is back from a native ad's landing page. */
        fun show(host: Activity, afterAdClick: Boolean) = show(host)
    }

    @JvmStatic
    @Volatile
    var nativeScreen: NativeScreen? = null

    private class Stay(
        val type: ResumeAdType,
        val placement: String,
        val delayMs: Long,
        var afterAdClick: Boolean,
    ) {
        val leftAtMs = SystemClock.elapsedRealtime()
        val fullscreenShowsAtLeave = AppOpenManager.fullscreenShows()
        var requested = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var application: Application? = null
    private var stay: Stay? = null
    private var pendingRequest: Runnable? = null
    private var pendingPresentation: Runnable? = null

    private val started: MutableSet<Activity> = Collections.newSetFromMap(IdentityHashMap())

    /** The last Activity outside [AdSurfaces] to resume: the screen a trip leaves from. */
    private var host: WeakReference<Activity>? = null

    /** The last Activity to resume is an [AdSurfaces] one. */
    private var adSurfaceInFront = false

    /** A native ad was clicked since the host last resumed. */
    private var nativeClicked = false

    /** That click's screen moves on by itself on the way back: its trip opens no native resume. */
    private var nativeClickExcused = false

    /** A return admitted while an [AdSurfaces] Activity was still visible: it opens once that is gone. */
    private class Admitted(val left: Stay, val host: WeakReference<Activity>)

    private var admitted: Admitted? = null

    /**
     * Activity/process callbacks can straddle the deadline by a frame or two on a real device.
     * Keep a return that is already within this small window alive until the exact deadline;
     * longer early returns still lose the opportunity as before.
     */
    private const val RETURN_DEADLINE_TOLERANCE_MS = 250L

    @JvmStatic
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun attach(app: Application) {
        if (application != null) return
        application = app
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /**
     * A native ad reported a click or an opened destination: mediation adapters each report only
     * one of the two. Its landing page covering the host is then a trip; the mark ends when the
     * host resumes.
     */
    @JvmStatic
    @MainThread
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun noteNativeClick() {
        nativeClicked = true
        // Reported after the user already left: the host has not resumed since, so it is this trip's.
        stay?.takeIf { it.type == ResumeAdType.NATIVE }?.afterAdClick = true
        if (nativeClickExcused) dropClickTrip()
        leaveIfAway()
    }

    /**
     * The native click just reported belongs to a screen that moves on by itself once the user
     * is back (an onboarding page whose click action is auto next): that trip opens no native
     * resume, so the screen can never land over the page it moves to. A plain trip to the
     * background still does. Either order with [noteNativeClick] works.
     */
    @JvmStatic
    @MainThread
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun excuseNativeClick() {
        nativeClickExcused = true
        dropClickTrip()
    }

    /** Already gone on the excused click: the host has not resumed since, so the trip is that click's. */
    private fun dropClickTrip() {
        if (stay?.let { it.type == ResumeAdType.NATIVE && it.afterAdClick } == true) endStay()
    }

    @get:RestrictTo(RestrictTo.Scope.TESTS)
    @JvmStatic
    val isNativeClickExcused: Boolean get() = nativeClickExcused

    @JvmStatic
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun onActivityStarted(activity: Activity) {
        started += activity
    }

    @JvmStatic
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun onActivityResumed(activity: Activity) {
        // Resumed implies started, also for an Activity that started before the callbacks attached.
        started += activity
        adSurfaceInFront = AdSurfaces.contains(activity)
        if (adSurfaceInFront) {
            leaveIfAway()
            return
        }
        val left = stay?.takeIf { it.type == ResumeAdType.NATIVE }
        host = WeakReference(activity)
        nativeClicked = false
        nativeClickExcused = false
        admitted = null
        if (left != null) returnTo(left, activity)
    }

    @JvmStatic
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun onActivityPaused(activity: Activity) {
        if (activity === admitted?.host?.get()) admitted = null
    }

    @JvmStatic
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun onActivityStopped(activity: Activity) {
        started -= activity
        if (returnOnceAdSurfacesGone()) return
        // A configuration change stops one instance and starts its replacement: never a trip.
        if (activity.isChangingConfigurations) return
        leaveIfAway()
    }

    @JvmStatic
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun onActivityDestroyed(activity: Activity) {
        started -= activity
        returnOnceAdSurfacesGone()
    }

    private fun adSurfaceVisible(): Boolean = started.any(AdSurfaces::contains)

    /** Opens a return admitted under a closing ad Activity, once none is visible any more. */
    private fun returnOnceAdSurfacesGone(): Boolean {
        val waiting = admitted ?: return false
        if (adSurfaceVisible()) return false
        admitted = null
        val back = waiting.host.get() ?: return false
        open(waiting.left, back)
        return true
    }

    /** Starts a native trip once the user is gone: every Activity stopped, or a native ad's page in front. */
    private fun leaveIfAway() {
        if (stay != null || nativeClicked && nativeClickExcused) return
        val away = started.isEmpty()
        val onLandingPage = nativeClicked && adSurfaceInFront
        if (!away && !onLandingPage) return
        if (ResumeAdType.current() != ResumeAdType.NATIVE || nativeScreen == null) return
        val from = host?.get() ?: return
        // Back out of the last screen closes the app: the next launch is a cold entry, not a return.
        if (from.isFinishing) return
        val manager = AppOpenManager.getInstance()
        // An external trip the partner excused is not a new opportunity. A return from an ad click is.
        val returnReason = manager.resumeReturnSkipReason?.takeIf { it != AppOpenManager.AD_CLICK_RETURN }
        if (!manager.isInitialized || !manager.isAppResumeEnabled || manager.isShowingAd ||
            manager.isInterstitialShowing || returnReason != null) return
        if (manager.isResumeSuppressedFor(from) || manager.resumeSkipReasonFor(from, false) != null) return
        val placement = ResumeAdType.NATIVE.placement ?: return
        if (placementSkipReason(placement) != null) return
        stay = Stay(ResumeAdType.NATIVE, placement, delayMs = 0L, afterAdClick = nativeClicked)
    }

    override fun onStop(owner: LifecycleOwner) {
        val manager = AppOpenManager.getInstance()
        // Before any early return: a click left unspent here would mark a later, unrelated trip.
        val afterAdClick = manager.takeAdClickAway()
        if (ResumeAdType.current() != ResumeAdType.INTER) return
        if (stay != null) endStay()
        val placement = ResumeAdType.INTER.placement ?: return
        // The SDK's own fullscreen and an external trip the partner excused, an ad click
        // included, are not a new opportunity, exactly as for app-open.
        if (!manager.isInitialized || !manager.isAppResumeEnabled || manager.isShowingAd ||
            manager.isInterstitialShowing || manager.resumeReturnSkipReason != null) return
        val host = manager.currentActivity
        if (AdSurfaces.contains(host)) return
        if (host != null && (manager.isResumeSuppressedFor(host) || manager.resumeSkipReasonFor(host) != null)) return
        if (placementSkipReason(placement) != null) return
        val delayMs = AdRemoteConfig.normalizeAppResumeLoadDelayMs(AdRemoteConfig.getInstance().appResumeLoadDelayMs)
        begin(Stay(ResumeAdType.INTER, placement, delayMs, afterAdClick))
    }

    override fun onStart(owner: LifecycleOwner) {
        val left = stay?.takeIf { it.type == ResumeAdType.INTER } ?: return
        val host = AppOpenManager.getInstance().currentActivity
        if (host == null) {
            endStay()
            return
        }
        returnTo(left, host)
    }

    private fun begin(next: Stay) {
        stay = next
        val request = Runnable {
            pendingRequest = null
            request(next)
        }
        pendingRequest = request
        handler.postDelayed(request, next.delayMs)
    }

    private fun returnTo(left: Stay, host: Activity) {
        // Judged on the elapsed clock, which the timer is not: a device asleep through the delay
        // returns with the request still owed.
        val remainingMs = left.delayMs - (SystemClock.elapsedRealtime() - left.leftAtMs)
        if (remainingMs > 0L) {
            if (remainingMs > RETURN_DEADLINE_TOLERANCE_MS) {
                endStay()
                return
            }
            schedulePresentation(left, host, remainingMs)
            return
        }
        endStay()
        present(left, host)
    }

    private fun schedulePresentation(left: Stay, host: Activity, remainingMs: Long) {
        // The background request has the same deadline. If the user is already returning, keep
        // the screen's show-first ordering even when both callbacks become due in this frame.
        pendingRequest?.let(handler::removeCallbacks)
        pendingRequest = null
        pendingPresentation?.let(handler::removeCallbacks)
        val presentation = Runnable {
            pendingPresentation = null
            if (stay !== left) return@Runnable
            endStay()
            present(left, host)
        }
        pendingPresentation = presentation
        handler.postDelayed(presentation, remainingMs)
    }

    /**
     * Decided the moment the host is back, as the user left it and with this return's one-shot
     * skip still readable; a page the click itself moves on afterwards cannot refuse it. An ad
     * Activity still closing over the host defers only the opening.
     */
    private fun present(left: Stay, host: Activity) {
        if (!admit(left, host)) return
        if (adSurfaceVisible()) {
            admitted = Admitted(left, WeakReference(host))
            return
        }
        open(left, host)
    }

    private fun admit(left: Stay, host: Activity): Boolean {
        if (!canOpen(left, host) || AdSurfaces.contains(host)) return false
        val manager = AppOpenManager.getInstance()
        if (manager.isResumeSuppressedFor(host)) return false
        val reason = manager.resumeSkipReasonFor(host, left.type != ResumeAdType.NATIVE)
            ?: placementSkipReason(left.placement)
        if (reason != null) {
            AdTracking.skipped(left.placement, formatOf(left.type), reason)
            return false
        }
        return true
    }

    /** Never over an ad: an ad Activity still visible, an app-open or an interstitial on screen. */
    private fun canOpen(left: Stay, host: Activity): Boolean {
        val manager = AppOpenManager.getInstance()
        if (ResumeAdType.current() != left.type || host.isFinishing || host.isDestroyed ||
            !manager.isAppResumeEnabled || manager.isShowingAd || manager.isInterstitialShowing) return false
        // A fullscreen ad shown while away covered the host itself; its close is not a return.
        return left.type != ResumeAdType.NATIVE || AppOpenManager.fullscreenShows() == left.fullscreenShowsAtLeave
    }

    private fun open(left: Stay, host: Activity) {
        if (!canOpen(left, host) || adSurfaceVisible()) return
        when (left.type) {
            ResumeAdType.NATIVE -> nativeScreen?.show(host, left.afterAdClick)
            ResumeAdType.INTER -> {
                request(left)
                showInterstitial(host, left.placement)
            }
            else -> Unit
        }
    }

    private fun request(left: Stay) {
        if (left.requested) return
        left.requested = true
        val app = application ?: return
        if (left.type == ResumeAdType.INTER) InterstitialAdManager.load(app, left.placement)
    }

    private fun showInterstitial(host: Activity, placement: String) {
        val activity = host as? AppCompatActivity ?: return
        activity.whenResumed { InterstitialAdManager.loadAndShow(activity, placement, InterShowCallback()) }
    }

    /** The opportunity is lost if the host stops before it resumes. */
    private fun AppCompatActivity.whenResumed(block: () -> Unit) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            block()
            return
        }
        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        source.lifecycle.removeObserver(this)
                        block()
                    }
                    Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> source.lifecycle.removeObserver(this)
                    else -> Unit
                }
            }
        })
    }

    private fun placementSkipReason(placement: String): String? = when {
        !AdGate.placementEnabled(placement) -> AdSkipReason.DISABLED_CONFIG.key
        !AdGate.placementPassesUaGate(placement) -> AdSkipReason.UA_GATE.key
        else -> null
    }

    private fun formatOf(type: ResumeAdType): AdFormat =
        if (type == ResumeAdType.INTER) AdFormat.INTERSTITIAL else AdFormat.NATIVE

    private fun endStay() {
        pendingRequest?.let(handler::removeCallbacks)
        pendingPresentation?.let(handler::removeCallbacks)
        pendingRequest = null
        pendingPresentation = null
        stay = null
    }

    /** Forgets every tracked Activity and mark; for tests that build their own lifecycles. */
    @JvmStatic
    @RestrictTo(RestrictTo.Scope.TESTS)
    fun resetForTest() {
        endStay()
        started.clear()
        host = null
        adSurfaceInFront = false
        nativeClicked = false
        nativeClickExcused = false
        admitted = null
    }
}
