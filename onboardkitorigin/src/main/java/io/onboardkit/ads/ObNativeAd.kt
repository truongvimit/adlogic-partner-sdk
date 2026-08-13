package io.onboardkit.ads

import android.app.Activity
import android.view.View
import android.view.ViewGroup
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
 * @param onBound the ad is in [container]; the shimmer has been swapped out.
 * @param onShown GMA counted the impression. Use it to start dwell timers, not to navigate.
 * @param onUnavailable nothing can be shown here — hide the slot, show a fallback, or move on.
 */
internal fun Activity.showNativeAd(
    placement: AdPlacement,
    unit: NativeAdUnit?,
    container: ViewGroup,
    shimmer: View?,
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

    val listener = placement.tracked(
        object : AdEventListener {
            override fun onLoaded() = onMainThread {
                if (bindBuffered(provider, placement, container, shimmer)) onBound()
            }

            override fun onFailedToLoad() = onMainThread {
                ObLog.w(ObLog.Section.LOAD, "${placement.key} native unavailable — no fill")
                onUnavailable(AdSkipReason.NO_FILL)
            }

            override fun onImpression() = onMainThread { onShown() }
        },
    )

    placement.trackRequest()
    // Buffered by the preload chain on the common path, so the slot paints without a round trip
    if (bindBuffered(provider, placement, container, shimmer, listener)) {
        onBound()
        return
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
