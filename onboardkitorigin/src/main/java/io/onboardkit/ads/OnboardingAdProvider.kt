package io.onboardkit.ads

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.NativeAdUnit

/** Callbacks a screen can observe for one placement. All optional. */
interface AdEventListener {
    fun onLoaded() {}
    fun onFailedToLoad() {}
    fun onImpression() {}
    fun onClicked() {}
}

data class NativeAdRequest(
    val placement: AdPlacement,
    val unit: NativeAdUnit,
    @LayoutRes val layoutRes: Int,
)

/**
 * Vendor seam: the flow talks to this interface only. The SDK ships [io.onboardkit.ads.erain.ERainAdProvider]
 * bridging the project's ads module; apps may inject any other implementation (or none).
 */
interface OnboardingAdProvider {

    fun isPremium(context: Context): Boolean

    /** Fire-and-forget waterfall preload (high floor first, then all-price). Idempotent. */
    fun preloadNative(activity: Activity, request: NativeAdRequest)

    fun isNativeReady(placement: AdPlacement): Boolean

    fun isNativeLoading(placement: AdPlacement): Boolean

    /** Preload is worthwhile only when nothing is buffered and nothing is in flight. */
    fun shouldPreloadNative(placement: AdPlacement): Boolean =
        !isNativeReady(placement) && !isNativeLoading(placement)

    /**
     * Binds the buffered native into [container], swapping [shimmer] out.
     * Returns false when no ad is available (caller keeps or hides the shimmer).
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

    /** Shows if ready, otherwise invokes [onFinished] immediately. Never blocks the flow. */
    fun showInterstitial(activity: Activity, placement: AdPlacement, onFinished: () -> Unit)

    /** Requires the ads-module banner include (`banner_container` + `shimmer_container_banner`). */
    fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener? = null)

    /** Blocks app-resume ads while a full-screen native is visible (no stacked ads). */
    fun suppressAppResume(activityClass: Class<out Activity>)

    fun allowAppResume(activityClass: Class<out Activity>)

    fun releaseAll()
}
