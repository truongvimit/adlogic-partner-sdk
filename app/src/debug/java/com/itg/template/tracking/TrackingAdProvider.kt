package com.itg.template.tracking

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.ads.module.admob.AppOpenManager
import io.adtracer.AdFormat
import io.adtracer.AdTracer
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decorator over the OnboardKit ad provider — the single seam every onboarding ad passes
 * through, so first-open placements are tracked without touching :ads or :onboardkitorigin.
 *
 * Native preload outcomes are resolved by polling ready/loading state (the chain passes no
 * listener), so `loaded` for these placements is outcome-exact but latency-approximate and
 * flagged `approx`. Shows are exact: one bindNative(true) == one consumed native.
 * Interstitial clicks never reach this seam (SDK gap) and are not measurable here.
 */
class TrackingAdProvider(private val delegate: OnboardingAdProvider) : OnboardingAdProvider {

    private val handler = Handler(Looper.getMainLooper())
    private val pendingNativePolls = ConcurrentHashMap<String, Runnable>()
    private val interInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val knownPlacements = ConcurrentHashMap<String, AdPlacement>()
    private val interListeners = ConcurrentHashMap<String, StableInterListener>()
    private val nativeListeners = ConcurrentHashMap<String, StableNativeListener>()

    override fun isPremium(context: Context): Boolean = delegate.isPremium(context)

    override fun preloadNative(activity: Activity, request: NativeAdRequest) {
        val placement = request.placement
        val key = placement.key
        knownPlacements[key] = placement
        // The delegate no-ops when buffered or in flight; emit only when a real request starts
        val isNewRequest = delegate.shouldPreloadNative(placement)
        delegate.preloadNative(activity, request)
        if (isNewRequest) {
            // Report the tier actually requested first, whatever the depth of the waterfall
            AdTracer.loadRequested(key, AdFormat.NATIVE, request.unit.topTierId)
            pollNativeOutcome(key, placement)
        }
    }

    override fun isNativeReady(placement: AdPlacement): Boolean = delegate.isNativeReady(placement)

    override fun isNativeLoading(placement: AdPlacement): Boolean = delegate.isNativeLoading(placement)

    override fun bindNative(
        activity: Activity,
        placement: AdPlacement,
        container: ViewGroup,
        shimmer: View?,
        listener: AdEventListener?,
    ): Boolean {
        val key = placement.key
        knownPlacements[key] = placement
        val stable = nativeListeners.getOrPut(key) { StableNativeListener(key) }
        stable.original = listener
        val bound = delegate.bindNative(activity, placement, container, shimmer, stable)
        if (bound) {
            resolvePollAsLoaded(key)
            AdTracer.shown(key, AdFormat.NATIVE)
        } else if (delegate.isNativeLoading(placement)) {
            // Screens bind optimistically before the fill and rebind on load — this is the
            // normal path, not a blocked show
            AdTracer.event("bind_deferred", key, AdFormat.NATIVE, reason = "still_loading")
        } else {
            AdTracer.showBlocked(key, AdFormat.NATIVE, "no_fill")
        }
        return bound
    }

    override fun releaseNative(placement: AdPlacement) {
        trackNativeRelease(placement, "released")
        delegate.releaseNative(placement)
    }

    override fun loadInterstitial(
        context: Context,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        listener: AdEventListener?,
    ) {
        val key = placement.key
        knownPlacements[key] = placement
        val stable = interListeners.getOrPut(key) { StableInterListener(key, placement) }
        stable.original = listener
        if (!delegate.isInterstitialReady(placement) && interInFlight.add(key)) {
            AdTracer.loadRequested(key, AdFormat.INTERSTITIAL, unit.topTierId)
        }
        delegate.loadInterstitial(context, placement, unit, stable)
    }

    override fun isInterstitialReady(placement: AdPlacement): Boolean = delegate.isInterstitialReady(placement)

    override fun showInterstitial(activity: Activity, placement: AdPlacement, onFinished: () -> Unit) {
        val key = placement.key
        if (!delegate.isInterstitialReady(placement)) {
            AdTracer.showBlocked(key, AdFormat.INTERSTITIAL, "not_ready")
            delegate.showInterstitial(activity, placement, onFinished)
            return
        }
        AdTracer.showRequested(key, AdFormat.INTERSTITIAL)
        val finished = AtomicBoolean(false)
        // Known SDK gap: interval-gated or show-failed inters never invoke onFinished here.
        // The marker is informational (never a terminal) so a slow user on a long creative
        // cannot produce blocked+shown for one show; re-arm while an ad is on screen.
        val watchdog = object : Runnable {
            override fun run() {
                if (finished.get()) return
                if (AppOpenManager.getInstance().isInterstitialShowing) {
                    handler.postDelayed(this, SHOWING_RECHECK_MS)
                    return
                }
                AdTracer.event("show_no_terminal", key, AdFormat.INTERSTITIAL, reason = "no_callback_15s")
            }
        }
        handler.postDelayed(watchdog, WATCHDOG_MS)
        delegate.showInterstitial(activity, placement) {
            if (finished.compareAndSet(false, true)) {
                handler.removeCallbacks(watchdog)
                // onFinished only fires from onAdClosed, which implies a completed show
                AdTracer.shown(key, AdFormat.INTERSTITIAL)
            }
            onFinished()
        }
    }

    override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) {
        val key = AdPlacement.SplashBanner.key
        AdTracer.loadRequested(key, AdFormat.BANNER, unit.id)
        delegate.loadBanner(
            activity, unit,
            object : AdEventListener {
                override fun onLoaded() {
                    AdTracer.loaded(key, AdFormat.BANNER)
                    listener?.onLoaded()
                }

                override fun onFailedToLoad() {
                    AdTracer.loadFailed(key, AdFormat.BANNER)
                    listener?.onFailedToLoad()
                }

                override fun onImpression() {
                    AdTracer.impression(key, AdFormat.BANNER)
                    listener?.onImpression()
                }

                override fun onClicked() {
                    AdTracer.clicked(key, AdFormat.BANNER)
                    listener?.onClicked()
                }
            },
        )
    }

    override fun suppressAppResume(activityClass: Class<out Activity>) = delegate.suppressAppResume(activityClass)

    override fun allowAppResume(activityClass: Class<out Activity>) = delegate.allowAppResume(activityClass)

    override fun releaseAll() {
        for (placement in knownPlacements.values) {
            trackNativeRelease(placement, "release_all")
            if (delegate.isInterstitialReady(placement)) {
                AdTracer.discarded(placement.key, AdFormat.INTERSTITIAL, "release_all")
            }
        }
        delegate.releaseAll()
    }

    // A release must settle the pending poll first, or the next tick would misread the emptied
    // buffer as a load failure
    private fun trackNativeRelease(placement: AdPlacement, via: String) {
        val key = placement.key
        if (delegate.isNativeReady(placement)) {
            resolvePollAsLoaded(key)
            AdTracer.discarded(key, AdFormat.NATIVE, "${via}_loaded")
        } else {
            val poll = pendingNativePolls.remove(key)
            if (poll != null) {
                handler.removeCallbacks(poll)
                AdTracer.discarded(key, AdFormat.NATIVE, "${via}_while_loading")
            }
        }
    }

    /**
     * The delegate registers listeners with addIfAbsent and never prunes them until release,
     * so one long-lived wrapper per key is mandatory — fresh wrappers per call would stack and
     * fan every notification out N times. Load-outcome emission is additionally gated on
     * removing the in-flight marker so only the first terminal per request counts.
     */
    private inner class StableInterListener(
        private val key: String,
        private val placement: AdPlacement,
    ) : AdEventListener {

        @Volatile var original: AdEventListener? = null

        override fun onLoaded() {
            if (interInFlight.remove(key)) {
                // The ads SDK reports a phantom null fill for purchased/click-capped users;
                // a wrapper that is not actually ready must not count as loaded
                if (delegate.isInterstitialReady(placement)) {
                    AdTracer.loaded(key, AdFormat.INTERSTITIAL)
                } else {
                    AdTracer.loadFailed(key, AdFormat.INTERSTITIAL, message = "sdk_gate_null")
                }
            }
            original?.onLoaded()
        }

        override fun onFailedToLoad() {
            if (interInFlight.remove(key)) {
                AdTracer.loadFailed(key, AdFormat.INTERSTITIAL)
            }
            original?.onFailedToLoad()
        }

        override fun onImpression() {
            original?.onImpression()
        }

        override fun onClicked() {
            original?.onClicked()
        }
    }

    private inner class StableNativeListener(private val key: String) : AdEventListener {

        @Volatile var original: AdEventListener? = null

        override fun onLoaded() {
            original?.onLoaded()
        }

        override fun onFailedToLoad() {
            original?.onFailedToLoad()
        }

        override fun onImpression() {
            // Not tracked: the provider self-fires impression at bind time and OB3 double-fires
            // it; bindNative(true) is the single reliable show signal for natives
            original?.onImpression()
        }

        override fun onClicked() {
            AdTracer.clicked(key, AdFormat.NATIVE)
            original?.onClicked()
        }
    }

    private fun pollNativeOutcome(key: String, placement: AdPlacement) {
        pendingNativePolls.remove(key)?.let(handler::removeCallbacks)
        val poll = object : Runnable {
            private var ticks = 0

            override fun run() {
                if (pendingNativePolls[key] !== this) return
                when {
                    delegate.isNativeReady(placement) -> resolvePollAsLoaded(key)
                    !delegate.isNativeLoading(placement) -> {
                        pendingNativePolls.remove(key)
                        AdTracer.loadFailed(key, AdFormat.NATIVE, message = "waterfall_exhausted")
                    }
                    ++ticks > MAX_POLL_TICKS -> {
                        pendingNativePolls.remove(key)
                        AdTracer.loadFailed(key, AdFormat.NATIVE, message = "poll_timeout")
                    }
                    else -> handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
        pendingNativePolls[key] = poll
        handler.postDelayed(poll, POLL_INTERVAL_MS)
    }

    private fun resolvePollAsLoaded(key: String) {
        val poll = pendingNativePolls.remove(key) ?: return
        handler.removeCallbacks(poll)
        AdTracer.loaded(key, AdFormat.NATIVE, approx = true)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 500L
        const val MAX_POLL_TICKS = 120
        const val WATCHDOG_MS = 15_000L
        const val SHOWING_RECHECK_MS = 5_000L
    }
}
