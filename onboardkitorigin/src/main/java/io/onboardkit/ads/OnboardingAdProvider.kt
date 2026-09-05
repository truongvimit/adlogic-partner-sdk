package io.onboardkit.ads

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.NativeAdUnit

/** Load-time callbacks a screen can observe for one placement. All optional. */
interface AdEventListener {
    fun onLoaded() {}
    fun onFailedToLoad() {}
    /** A real vendor impression, never a successful bind or a preload fill. */
    fun onImpression() {}
    fun onClicked() {}

    /**
     * The ad's destination took the screen. Reported instead of the click by Meta's native
     * adapter, and never by Pangle's — a screen that must catch every departure listens to
     * this and [onClicked] both.
     */
    fun onAdOpened() {}
}

data class NativeAdRequest(
    val placement: AdPlacement,
    val unit: NativeAdUnit,
    @LayoutRes val layoutRes: Int,
)

/**
 * Vendor seam: the flow talks to this interface only.
 *
 * The SDK ships [io.onboardkit.ads.erain.ERainAdProvider], which bridges the project's `:ads`
 * module (AdMob legacy). Apps may inject any other implementation, or none at all — every
 * placement then reports [AdSkipReason.NO_PROVIDER] instead of failing.
 *
 * The provider owns load telemetry. Emit one request at the first actual vendor dispatch of a
 * logical attempt and one mutually exclusive loaded/failed terminal after its waterfall settles.
 * Cache hits, callers joining an in-flight request, and screen callbacks emit no additional load
 * events. Custom providers can use `AdLoadAttempt` with an immutable `AdLoadContext` at their
 * vendor boundary. Providers also own actual show telemetry and forward real vendor impressions
 * through [AdEventListener.onImpression]; the flow does not emit a second show. A native bind may
 * emit `ad_bound`, which does not imply visibility. The flow reports skips and forwards UI
 * callbacks; it does not infer a physical load or impression from [preloadNative], [bindNative],
 * or [AdEventListener.onFailedToLoad].
 */
interface OnboardingAdProvider {

    fun isPremium(context: Context): Boolean

    /** Fire-and-forget waterfall preload, highest floor first. Idempotent per placement. */
    fun preloadNative(activity: Activity, request: NativeAdRequest)

    fun isNativeReady(placement: AdPlacement): Boolean

    fun isNativeLoading(placement: AdPlacement): Boolean

    /**
     * Binds the buffered native into [container], swapping [shimmer] out.
     * Returns false when nothing is buffered — the caller keeps or hides the shimmer.
     */
    fun bindNative(
        activity: Activity,
        placement: AdPlacement,
        container: ViewGroup,
        shimmer: View?,
        listener: AdEventListener? = null,
    ): Boolean

    /** Cancels the pending request and drops the buffer for [placement]. */
    fun releaseNative(placement: AdPlacement)

    fun loadInterstitial(
        context: Context,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        listener: AdEventListener? = null,
    )

    fun isInterstitialReady(placement: AdPlacement): Boolean

    /**
     * Shows the buffered interstitial for [placement].
     *
     * Must call [ObInterstitialCallback.onNextAction] at most once, and exactly one terminal
     * callback. Callers rely on "the ad is on screen" and "the ad is gone" being two distinct
     * moments — see [showInterstitial].
     */
    fun showInterstitial(
        activity: Activity,
        placement: AdPlacement,
        callback: ObInterstitialCallback,
    )

    /** Wall clock of the last interstitial impression, `0` when the provider does not track it. */
    fun lastInterstitialShownAtMs(context: Context): Long = 0L

    /** Clicks spent on [adUnitId] in the current 24 h window, `0` when unknown. */
    fun clicksToday(context: Context, adUnitId: String): Int = 0

    /** Requires the ads-module banner include (`banner_container` + `shimmer_container_banner`). */
    fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener? = null)

    fun suppressAppResume(activityClass: Class<out Activity>)

    fun releaseAll()
}

/**
 * Optional banner capability for providers that capture the requesting placement explicitly.
 * Existing [OnboardingAdProvider] implementations retain their original JVM interface.
 */
interface PlacementAwareBannerProvider : OnboardingAdProvider {
    fun loadBanner(
        activity: Activity,
        placement: AdPlacement,
        unit: BannerAdUnit,
        listener: AdEventListener? = null,
    )
}

/**
 * Passes the semantic placement to capable providers and otherwise uses the original banner API.
 * The fallback does not manufacture request/load events; custom providers own those at dispatch.
 */
@JvmOverloads
fun OnboardingAdProvider.loadBanner(
    activity: Activity,
    placement: AdPlacement,
    unit: BannerAdUnit,
    listener: AdEventListener? = null,
) {
    if (this is PlacementAwareBannerProvider) loadBanner(activity, placement, unit, listener)
    else loadBanner(activity, unit, listener)
}
