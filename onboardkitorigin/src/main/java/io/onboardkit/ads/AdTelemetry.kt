package io.onboardkit.ads

import io.onboardkit.OnboardingSdk
import io.onboardkit.core.analytics.AnalyticsEvent
import io.trackkit.AdFormat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reports the first impression of one ad slot and forwards its UI callbacks.
 *
 * Revenue, impressions-with-money and clicks all arrive through the vendor callback inside `:ads`.
 * Request and load terminals belong to the provider's actual load attempt. A screen may join an
 * existing request, bind a cached ad, or stop waiting before the provider finishes; none of those
 * UI actions creates another load event.
 *
 * Impression is latched. A bound native reaches the screen twice on the common path
 * (once synchronously from `bindNative`'s own listener notification, once from the caller's
 * post-bind branch), which is exactly how `ob_ad_impression` came to be double-counted on OB3.
 */
internal class TrackedAdListener(
    private val placementKey: String,
    private val format: AdFormat,
    private val delegate: AdEventListener?,
) : AdEventListener {

    private val impressionReported = AtomicBoolean(false)

    override fun onLoaded() {
        delegate?.onLoaded()
    }

    override fun onFailedToLoad() {
        delegate?.onFailedToLoad()
    }

    override fun onImpression() {
        if (impressionReported.compareAndSet(false, true)) {
            OnboardingSdk.track(AnalyticsEvent.AdImpression(placementKey, format))
        }
        delegate?.onImpression()
    }

    override fun onClicked() {
        // Every click in the flow passes through here, which makes it the one place that can tell
        // the resume guard the user is about to leave for an ad — a screen-by-screen hook would
        // be one missed override away from an app-resume ad greeting them on the way back.
        OnboardingSdk.appResume().onAdClicked()
        delegate?.onClicked()
    }

    override fun onAdOpened() {
        delegate?.onAdOpened()
    }
}

/** Wraps [listener] so this placement's first impression reaches analytics. */
internal fun AdPlacement.tracked(
    listener: AdEventListener? = null,
): AdEventListener = TrackedAdListener(key, format, listener)

/**
 * No ad was shown for this placement. [reason] is the precise cause, not a bucket — a dashboard
 * that cannot tell "premium" from "no fill" cannot act on either.
 */
internal fun AdPlacement.trackSkipped(reason: AdSkipReason) {
    OnboardingSdk.track(AnalyticsEvent.AdSkipped(key, format, reason.key))
}
