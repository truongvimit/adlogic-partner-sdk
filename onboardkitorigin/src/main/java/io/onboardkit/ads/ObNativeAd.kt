package io.onboardkit.ads

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.ads.module.helper.adnative.NativeAdShimmer
import com.facebook.shimmer.ShimmerFrameLayout
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.core.ObLog

/**
 * Fills one native slot: asks the guard, binds what is already buffered, requests what is not.
 *
 * Every screen used to repeat these twenty lines, and every copy drifted — one reported the real
 * skip reason, the next hard-coded `placement_off_remote`; one hid its ad block, another left the
 * shimmer spinning forever. One implementation means a partner reading any screen sees the same
 * three outcomes.
 *
 * Exactly one of [onBound] / [onUnavailable] runs for a given attempt, always on the main thread.
 *
 * While the request is in flight the slot shows a skeleton auto-derived from the
 * placement's resolved template layout ([NativeAdShimmer]) — no per-template shimmer XML,
 * and the skeleton's geometry always matches the ad that replaces it.
 *
 * @param onBound the ad is in [container]; the skeleton has been swapped out.
 * @param onShown GMA counted the impression. Use it to start dwell timers, not to navigate.
 * @param onUnavailable nothing can be shown here — hide the slot, show a fallback, or move on.
 */
internal fun Activity.showNativeAd(
    placement: AdPlacement,
    unit: NativeAdUnit?,
    container: ViewGroup,
    onBound: () -> Unit = {},
    onShown: () -> Unit = {},
    onUnavailable: (AdSkipReason) -> Unit = {},
) {
    val provider = OnboardingSdk.provider()
    if (provider == null || unit == null) {
        placement.reportUnavailable(AdSkipReason.NO_AD_UNIT, onUnavailable)
        return
    }
    OnboardingSdk.guard().skipReason(this, placement)?.let { reason ->
        placement.reportUnavailable(reason, onUnavailable)
        return
    }

    var skeleton: ShimmerFrameLayout? = null
    val listener = placement.tracked(
        object : AdEventListener {
            override fun onLoaded() = onMainThread {
                if (bindBuffered(provider, placement, container, skeleton)) onBound()
            }

            override fun onFailedToLoad() = onMainThread {
                skeleton?.stopShimmer()
                ObLog.w(ObLog.Section.LOAD, "${placement.key} native unavailable — no fill")
                onUnavailable(AdSkipReason.NO_FILL)
            }

            override fun onImpression() = onMainThread { onShown() }
        },
    )

    placement.trackRequest()
    // Buffered by the preload chain on the common path, so the slot paints without a round trip
    if (bindBuffered(provider, placement, container, shimmer = null, listener)) {
        onBound()
        return
    }
    // Occupy the slot for the whole load window; the bind's removeAllViews swaps it out
    skeleton = NativeAdShimmer.from(this, NativeTemplates.layoutForPlacement(placement)).also {
        container.removeAllViews()
        container.addView(it)
        container.visibility = View.VISIBLE
        it.startShimmer()
    }
    provider.preloadNative(
        this,
        NativeAdRequest(placement, unit, NativeTemplates.layoutForPlacement(placement)),
    )
}

private fun AdPlacement.reportUnavailable(
    reason: AdSkipReason,
    onUnavailable: (AdSkipReason) -> Unit,
) {
    trackSkipped(reason)
    onUnavailable(reason)
}

private fun Activity.bindBuffered(
    provider: OnboardingAdProvider,
    placement: AdPlacement,
    container: ViewGroup,
    shimmer: View?,
    listener: AdEventListener? = null,
): Boolean {
    if (isFinishing || isDestroyed) return false
    return provider.bindNative(this, placement, container, shimmer, listener)
}

/** Vendor callbacks arrive on the main thread today, but nothing in the contract promises it. */
private fun Activity.onMainThread(block: () -> Unit) {
    if (isFinishing || isDestroyed) return
    runOnUiThread {
        if (!isFinishing && !isDestroyed) block()
    }
}
