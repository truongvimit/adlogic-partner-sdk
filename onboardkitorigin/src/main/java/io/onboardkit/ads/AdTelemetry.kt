package io.onboardkit.ads

import io.onboardkit.OnboardingSdk
import io.onboardkit.core.analytics.AnalyticsEvent
import io.trackkit.AdFormat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reports the lifecycle of one ad slot, then forwards to the screen's own listener.
 *
 * Revenue, impressions-with-money and clicks all arrive through the vendor callback inside `:ads`.
 * What only this layer knows is the *opportunity*: which onboarding screen asked, and whether it
 * got anything. Wrapping the listener keeps that reporting in one place — the screens used to
 * duplicate it, or more often skip it.
 *
 * Impression and failure are latched. A bound native reaches the screen twice on the common path
 * (once synchronously from `bindNative`'s own listener notification, once from the caller's
 * post-bind branch), which is exactly how `ob_ad_impression` came to be double-counted on OB3.
 */
internal class TrackedAdListener(
    private val placementKey: String,
    private val format: AdFormat,
    private val delegate: AdEventListener?,
) : AdEventListener {

    private val impressionReported = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)

    override fun onLoaded() {
        delegate?.onLoaded()
    }

    override fun onFailedToLoad() {
        if (failureReported.compareAndSet(false, true)) {
            OnboardingSdk.track(AnalyticsEvent.AdFailed(placementKey, format))
        }
        delegate?.onFailedToLoad()
    }

    override fun onImpression() {
        if (impressionReported.compareAndSet(false, true)) {
            OnboardingSdk.track(AnalyticsEvent.AdImpression(placementKey, format))
        }
        delegate?.onImpression()
    }

    override fun onClicked() {
        delegate?.onClicked()
    }
}

/** Wraps [listener] so this placement's load outcome and first impression reach analytics. */
internal fun AdPlacement.tracked(
    format: AdFormat,
    listener: AdEventListener? = null,
): AdEventListener = TrackedAdListener(key, format, listener)

/** A load is about to go out for this placement. */
internal fun AdPlacement.trackRequest(format: AdFormat) {
    OnboardingSdk.track(AnalyticsEvent.AdRequested(key, format))
}

/** Policy declined this placement before any request went out. */
internal fun AdPlacement.trackSkipped(format: AdFormat, reason: String) {
    OnboardingSdk.track(AnalyticsEvent.AdSkipped(key, format, reason))
}
