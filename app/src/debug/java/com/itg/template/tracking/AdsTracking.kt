package com.itg.template.tracking

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.funtion.AdCallback
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import io.adtracer.AdFormat
import io.adtracer.AdTracer
import io.onboardkit.ads.OnboardingAdProvider
import java.util.Collections
import java.util.WeakHashMap

/**
 * Debug-variant bridge between the app's ad callbacks and AdTracer. The release variant is a
 * no-op twin with identical signatures, so main-sourceset call sites compile in both builds
 * and R8 strips them from release.
 */
object AdsTracking {

    // AdErrors fabricated by the ads SDK when the app backgrounds during its 800ms show delay
    private val syntheticDomains = setOf("LuanDT", "MiaAd", "AperoAd")

    private const val WATCHDOG_MS = 15_000L
    private const val SHOWING_RECHECK_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())
    private val placementByNative = Collections.synchronizedMap(WeakHashMap<ApNativeAd, String>())
    private val renderedNatives = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ApNativeAd, Boolean>()),
    )
    private val bannerFilled = Collections.synchronizedSet(mutableSetOf<String>())

    fun init(app: Application) {
        AdTracer.start(app)
        attachResumeTracking()
    }

    // The single app-slot callback on AppOpenManager is unused by the app today; claiming it
    // is the only way to observe resume-ad shows without touching the ads SDK. Single-owner
    // slot: any future app code calling setFullScreenContentCallback would silently replace this.
    private fun attachResumeTracking() {
        val manager = AppOpenManager.getInstance()
        manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
            private var showInFlight = false

            override fun onAdShowedFullScreenContent() {
                showInFlight = true
                AdTracer.shown("open_resume", AdFormat.APP_OPEN)
            }

            override fun onAdDismissedFullScreenContent() {
                // The SDK fabricates dismisses for purchased users, null activity and dialog
                // failures — those never followed a real show
                if (showInFlight) {
                    showInFlight = false
                    AdTracer.dismissed("open_resume", AdFormat.APP_OPEN)
                } else {
                    AdTracer.event(
                        "dismissed", "open_resume", AdFormat.APP_OPEN,
                        reason = "no_show_preceded", synthetic = true,
                    )
                }
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                showInFlight = false
                AdTracer.showFailed(
                    "open_resume", AdFormat.APP_OPEN, adError.code, adError.message,
                    synthetic = syntheticDomains.contains(adError.domain),
                )
            }

            override fun onAdImpression() = AdTracer.impression("open_resume", AdFormat.APP_OPEN)

            override fun onAdClicked() = AdTracer.clicked("open_resume", AdFormat.APP_OPEN)
        })
        manager.setEnableScreenContentCallback(true)
    }

    fun wrapOnboardingProvider(delegate: OnboardingAdProvider): OnboardingAdProvider = TrackingAdProvider(delegate)

    fun nativeLoadRequested(placement: String, adUnitId: String) =
        AdTracer.loadRequested(placement, AdFormat.NATIVE, adUnitId)

    fun nativeLoadSkipped(placement: String, reason: String) =
        AdTracer.loadSkipped(placement, AdFormat.NATIVE, reason)

    fun trackedNativeLoadCallback(placement: String, wrapped: AdCallback): AdCallback = object : AdCallback() {
        override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
            placementByNative[nativeAd] = placement
            AdTracer.loaded(placement, AdFormat.NATIVE)
            wrapped.onNativeAdLoaded(nativeAd)
        }

        override fun onAdFailedToLoad(i: LoadAdError?) {
            AdTracer.loadFailed(placement, AdFormat.NATIVE, i?.code, i?.message)
            wrapped.onAdFailedToLoad(i)
        }

        override fun onAdClicked() {
            AdTracer.clicked(placement, AdFormat.NATIVE)
            wrapped.onAdClicked()
        }

        override fun onAdFailedToShow(adError: AdError?) = wrapped.onAdFailedToShow(adError)
    }

    fun nativeRendered(ad: ApNativeAd) {
        val placement = placementByNative[ad] ?: "native_unknown"
        // Sticky LiveData can re-deliver the same ad on observer re-registration; only the
        // first bind counts as a show
        if (renderedNatives.add(ad)) AdTracer.rendered(placement) else AdTracer.reRendered(placement)
    }

    fun nativeRenderDropped(ad: ApNativeAd) {
        AdTracer.event(
            "render_dropped", placementByNative[ad] ?: "native_unknown", AdFormat.NATIVE,
            reason = "empty_ad",
        )
    }

    fun interLoadRequested(placement: String, adUnitId: String) =
        AdTracer.loadRequested(placement, AdFormat.INTERSTITIAL, adUnitId)

    fun interLoadSkipped(placement: String, reason: String) =
        AdTracer.loadSkipped(placement, AdFormat.INTERSTITIAL, reason)

    fun trackedInterLoadCallback(placement: String, wrapped: AdCallback): AdCallback = object : AdCallback() {
        override fun onApInterstitialLoad(apInterstitialAd: ApInterstitialAd?) {
            // The SDK fires this with a null fill for purchased/click-capped users without
            // ever issuing a GMA request — counting it as loaded would corrupt show rate
            if (apInterstitialAd?.isReady == true) {
                AdTracer.loaded(placement, AdFormat.INTERSTITIAL)
            } else {
                AdTracer.loadFailed(placement, AdFormat.INTERSTITIAL, message = "sdk_gate_null")
            }
            wrapped.onApInterstitialLoad(apInterstitialAd)
        }

        override fun onAdFailedToLoad(i: LoadAdError?) {
            AdTracer.loadFailed(placement, AdFormat.INTERSTITIAL, i?.code, i?.message)
            wrapped.onAdFailedToLoad(i)
        }
    }

    fun interShowRequested(placement: String) = AdTracer.showRequested(placement, AdFormat.INTERSTITIAL)

    fun interShowBlocked(placement: String, reason: String) =
        AdTracer.showBlocked(placement, AdFormat.INTERSTITIAL, reason)

    /**
     * @param reloads mirrors the shouldReloadAds argument passed to forceShowInterstitial — the
     * SDK then silently issues a fresh load whose fill arrives on this same callback, so the
     * request side has to be paired here or Loaded would exceed Request.
     */
    fun trackedInterShowCallback(placement: String, wrapped: AdCallback, reloads: Boolean): AdCallback =
        object : AdCallback() {
            private var showStarted = false
            private var settled = false

            private val watchdog = object : Runnable {
                override fun run() {
                    if (settled) return
                    if (AppOpenManager.getInstance().isInterstitialShowing) {
                        handler.postDelayed(this, SHOWING_RECHECK_MS)
                        return
                    }
                    // The SDK returns without any callback when the process is not RESUMED
                    AdTracer.event(
                        "show_no_terminal", placement, AdFormat.INTERSTITIAL,
                        reason = "no_callback_15s",
                    )
                }
            }

            init {
                handler.postDelayed(watchdog, WATCHDOG_MS)
            }

            private fun settle() {
                settled = true
                handler.removeCallbacks(watchdog)
            }

            private fun trackAutoReloadRequest() {
                if (reloads) AdTracer.loadRequested(placement, AdFormat.INTERSTITIAL)
            }

            override fun onInterstitialShow() {
                showStarted = true
                AdTracer.showStarted(placement, AdFormat.INTERSTITIAL)
                wrapped.onInterstitialShow()
            }

            override fun onNextAction() {
                // With openActivityAfterShowInterAds the SDK fires onNextAction mid-show too;
                // only a bare onNextAction means the show was silently blocked
                if (!showStarted) {
                    settle()
                    AdTracer.showBlocked(placement, AdFormat.INTERSTITIAL, "interval_or_not_ready")
                }
                wrapped.onNextAction()
            }

            override fun onAdClosed() {
                settle()
                AdTracer.shown(placement, AdFormat.INTERSTITIAL)
                AdTracer.dismissed(placement, AdFormat.INTERSTITIAL)
                trackAutoReloadRequest()
                wrapped.onAdClosed()
            }

            override fun onAdFailedToShow(adError: AdError?) {
                settle()
                AdTracer.showFailed(
                    placement, AdFormat.INTERSTITIAL, adError?.code, adError?.message,
                    synthetic = syntheticDomains.contains(adError?.domain),
                )
                trackAutoReloadRequest()
                wrapped.onAdFailedToShow(adError)
            }

            override fun onAdClicked() {
                AdTracer.clicked(placement, AdFormat.INTERSTITIAL)
                wrapped.onAdClicked()
            }

            override fun onInterstitialLoad(interstitialAd: InterstitialAd?) {
                // Auto-reload fill delivered after close/show-fail when shouldReloadAds is on
                if (interstitialAd != null) {
                    AdTracer.event(
                        "loaded", placement, AdFormat.INTERSTITIAL, reason = "auto_reload",
                    )
                }
                wrapped.onInterstitialLoad(interstitialAd)
            }

            override fun onAdFailedToLoad(i: LoadAdError?) {
                AdTracer.loadFailed(placement, AdFormat.INTERSTITIAL, i?.code, i?.message)
                wrapped.onAdFailedToLoad(i)
            }
        }

    fun bannerLoadRequested(placement: String, adUnitId: String) {
        // Each app-side load builds a fresh AdView, so the next fill pairs with this request;
        // only GMA's own refreshes on that view are counted as refreshes
        bannerFilled.remove(placement)
        AdTracer.loadRequested(placement, AdFormat.BANNER, adUnitId)
    }

    fun bannerLoadSkipped(placement: String, reason: String) =
        AdTracer.loadSkipped(placement, AdFormat.BANNER, reason)

    /**
     * @param collapsible the SDK's collapsible listener never forwards onAdImpression, so a
     * loaded collapsible banner (already attached to the visible container) is counted as an
     * approximate impression instead.
     */
    fun trackedBannerCallback(placement: String, wrapped: AdCallback, collapsible: Boolean): AdCallback =
        object : AdCallback() {
            override fun onAdLoaded() {
                // GMA re-fires onAdLoaded on every auto-refresh of the same AdView; only the
                // first fill pairs with the app's request
                if (bannerFilled.add(placement)) {
                    AdTracer.loaded(placement, AdFormat.BANNER)
                } else {
                    AdTracer.event("refreshed", placement, AdFormat.BANNER, reason = "auto_refresh")
                }
                if (collapsible) {
                    AdTracer.event("impression", placement, AdFormat.BANNER, approx = true)
                }
                wrapped.onAdLoaded()
            }

            override fun onAdFailedToLoad(i: LoadAdError?) {
                AdTracer.loadFailed(placement, AdFormat.BANNER, i?.code, i?.message)
                wrapped.onAdFailedToLoad(i)
            }

            override fun onAdImpression() {
                AdTracer.impression(placement, AdFormat.BANNER)
                wrapped.onAdImpression()
            }

            override fun onAdClicked() {
                AdTracer.clicked(placement, AdFormat.BANNER)
                wrapped.onAdClicked()
            }
        }

    fun rewardLoadRequested(placement: String, adUnitId: String) =
        AdTracer.loadRequested(placement, AdFormat.REWARDED, adUnitId)

    fun rewardLoadSkipped(placement: String, reason: String) =
        AdTracer.loadSkipped(placement, AdFormat.REWARDED, reason)

    fun rewardLoaded(placement: String) = AdTracer.loaded(placement, AdFormat.REWARDED)

    fun rewardLoadFailed(placement: String, error: LoadAdError?) =
        AdTracer.loadFailed(placement, AdFormat.REWARDED, error?.code, error?.message)

    fun rewardShowRequested(placement: String) = AdTracer.showRequested(placement, AdFormat.REWARDED)

    fun rewardEarned(placement: String) = AdTracer.event("reward_earned", placement, AdFormat.REWARDED)

    fun rewardClosed(placement: String, earned: Boolean) {
        // A rewarded ad only dismisses after it actually displayed
        AdTracer.shown(placement, AdFormat.REWARDED)
        AdTracer.dismissed(placement, AdFormat.REWARDED)
        if (!earned) AdTracer.event("reward_not_earned", placement, AdFormat.REWARDED)
    }

    fun rewardShowFailed(placement: String, code: Int) =
        AdTracer.showFailed(placement, AdFormat.REWARDED, code)

    fun rewardClicked(placement: String) = AdTracer.clicked(placement, AdFormat.REWARDED)

    fun discarded(placement: String, format: String, reason: String) =
        AdTracer.discarded(placement, formatOf(format), reason)

    fun resumeOpportunity(outcome: String) =
        AdTracer.event("resume_opportunity", "welcome_resume", AdFormat.OTHER, reason = outcome)

    private fun formatOf(name: String): AdFormat =
        runCatching { AdFormat.valueOf(name) }.getOrDefault(AdFormat.OTHER)
}
