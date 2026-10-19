package com.ads.module.admob

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.google.android.gms.ads.AdActivity
import io.trackkit.AdFormat

/**
 * Native and interstitial resume; app-open stays with [AppOpenManager]. The request goes out once
 * the app has stayed in the background for the type's delay, and only a return after that delay
 * shows anything: `native_resume.app_resume_load_delay_ms` (default 0) for native,
 * `open_resume.app_resume_load_delay_ms` for interstitial. Native opens its screen at once, a
 * skeleton standing in until the fill, a return from an ad click included; interstitial waits for
 * its fill with the placement's load-and-show timeout.
 */
object ResumeAds : DefaultLifecycleObserver {

    /** The screen native resume opens. OnboardKit registers one; without it native resume shows nothing. */
    interface NativeScreen {
        /** Requests the screen's ad, on main; a load already running is joined, never doubled. */
        fun prepare(context: Context)

        /** Opens the screen over [host], or renews its ad when [host] is the screen itself. */
        fun show(host: Activity)
    }

    @JvmStatic
    @Volatile
    var nativeScreen: NativeScreen? = null

    private class Stay(val type: ResumeAdType, val placement: String, val delayMs: Long) {
        val leftAtMs = SystemClock.elapsedRealtime()
        var requested = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var application: Application? = null
    private var stay: Stay? = null
    private var pendingRequest: Runnable? = null
    private var pendingPresentation: Runnable? = null

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

    override fun onStop(owner: LifecycleOwner) {
        endStay()
        val type = ResumeAdType.current()
        if (type != ResumeAdType.NATIVE && type != ResumeAdType.INTER) return
        val placement = type.placement ?: return
        if (type == ResumeAdType.NATIVE && nativeScreen == null) return
        val manager = AppOpenManager.getInstance()
        // The SDK's own fullscreen and an external trip the partner excused are not a new
        // opportunity, exactly as for app-open. A return from an ad click still is one for native.
        val honorAdClick = type != ResumeAdType.NATIVE
        val returnReason = manager.resumeReturnSkipReason
            ?.takeIf { honorAdClick || it != AppOpenManager.AD_CLICK_RETURN }
        if (!manager.isInitialized || !manager.isAppResumeEnabled || manager.isShowingAd ||
            manager.isInterstitialShowing || returnReason != null) return
        val host = manager.currentActivity
        if (host is AdActivity) return
        if (host != null && (manager.isResumeSuppressedFor(host) || manager.resumeSkipReasonFor(host, honorAdClick) != null)) return
        if (placementSkipReason(placement) != null) return
        val config = AdRemoteConfig.getInstance()
        val delayMs = if (type == ResumeAdType.NATIVE) {
            config.nativeResumeLoadDelayMs.coerceIn(0L, AdRemoteConfig.MAX_APP_RESUME_LOAD_DELAY_MS)
        } else {
            AdRemoteConfig.normalizeAppResumeLoadDelayMs(config.appResumeLoadDelayMs)
        }
        val next = Stay(type, placement, delayMs)
        stay = next
        val request = Runnable {
            pendingRequest = null
            request(next)
        }
        pendingRequest = request
        handler.postDelayed(request, delayMs)
    }

    override fun onStart(owner: LifecycleOwner) {
        val left = stay ?: return
        // Judged on the elapsed clock, which the timer is not: a device asleep through the delay
        // returns with the request still owed.
        val remainingMs = left.delayMs - (SystemClock.elapsedRealtime() - left.leftAtMs)
        if (remainingMs > 0L) {
            if (remainingMs > RETURN_DEADLINE_TOLERANCE_MS) {
                endStay()
                return
            }
            schedulePresentation(left, remainingMs)
            return
        }
        endStay()
        present(left)
    }

    private fun schedulePresentation(left: Stay, remainingMs: Long) {
        // The background request has the same deadline. If the user is already returning, keep
        // the screen's show-first ordering even when both callbacks become due in this frame.
        pendingRequest?.let(handler::removeCallbacks)
        pendingRequest = null
        pendingPresentation?.let(handler::removeCallbacks)
        val presentation = Runnable {
            pendingPresentation = null
            if (stay !== left) return@Runnable
            endStay()
            present(left)
        }
        pendingPresentation = presentation
        handler.postDelayed(presentation, remainingMs)
    }

    private fun present(left: Stay) {
        if (ResumeAdType.current() != left.type) return
        val manager = AppOpenManager.getInstance()
        val host = manager.currentActivity ?: return
        if (host is AdActivity || host.isFinishing || host.isDestroyed || !manager.isAppResumeEnabled ||
            manager.isResumeSuppressedFor(host) || manager.isShowingAd || manager.isInterstitialShowing) return
        val reason = manager.resumeSkipReasonFor(host, left.type != ResumeAdType.NATIVE)
            ?: placementSkipReason(left.placement)
        if (reason != null) {
            AdTracking.skipped(left.placement, formatOf(left.type), reason)
            return
        }
        when (left.type) {
            // Native resume owns a real screen. Open it first so its skeleton and surrounding UI
            // are visible at the return deadline; the native request can run while that screen is
            // on top. A provider may do synchronous setup before dispatching its async load.
            ResumeAdType.NATIVE -> {
                nativeScreen?.show(host)
                request(left)
            }
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
        when (left.type) {
            ResumeAdType.NATIVE -> nativeScreen?.prepare(app)
            ResumeAdType.INTER -> InterstitialAdManager.load(app, left.placement)
            else -> Unit
        }
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
}
