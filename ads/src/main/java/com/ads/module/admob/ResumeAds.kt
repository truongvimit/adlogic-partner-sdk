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
 * Native and interstitial resume; app-open stays with [AppOpenManager]. Same opportunity as
 * app-open: the request goes out once the app has stayed in the background for
 * `open_resume.app_resume_load_delay_ms`, and only a return after that delay shows anything.
 * Native opens its screen at once, a skeleton standing in until the fill; interstitial waits for
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
        // opportunity, exactly as for app-open.
        if (!manager.isInitialized || !manager.isAppResumeEnabled || manager.isShowingAd ||
            manager.isInterstitialShowing || manager.resumeReturnSkipReason != null) return
        val host = manager.currentActivity
        if (host is AdActivity) return
        if (host != null && (manager.isResumeSuppressedFor(host) || manager.resumeSkipReasonFor(host) != null)) return
        if (placementSkipReason(placement) != null) return
        val delayMs = AdRemoteConfig.normalizeAppResumeLoadDelayMs(AdRemoteConfig.getInstance().appResumeLoadDelayMs)
        val next = Stay(type, placement, delayMs)
        stay = next
        val request = Runnable { request(next) }
        pendingRequest = request
        handler.postDelayed(request, delayMs)
    }

    override fun onStart(owner: LifecycleOwner) {
        val left = stay ?: return
        endStay()
        // Judged on the elapsed clock, which the timer is not: a device asleep through the delay
        // returns with the request still owed.
        if (SystemClock.elapsedRealtime() - left.leftAtMs < left.delayMs) return
        if (ResumeAdType.current() != left.type) return
        val manager = AppOpenManager.getInstance()
        val host = manager.currentActivity ?: return
        if (host is AdActivity || host.isFinishing || host.isDestroyed || !manager.isAppResumeEnabled ||
            manager.isResumeSuppressedFor(host) || manager.isShowingAd || manager.isInterstitialShowing) return
        val reason = manager.resumeSkipReasonFor(host) ?: placementSkipReason(left.placement)
        if (reason != null) {
            AdTracking.skipped(left.placement, formatOf(left.type), reason)
            return
        }
        request(left)
        when (left.type) {
            ResumeAdType.NATIVE -> nativeScreen?.show(host)
            ResumeAdType.INTER -> showInterstitial(host, left.placement)
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
        pendingRequest = null
        stay = null
    }
}
