package io.onboardkit.ads

import io.onboardkit.OnboardingSdk
import io.onboardkit.core.analytics.AnalyticsEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Forwards callbacks for one flow slot and coordinates click-return suppression.
 * The provider owns actual load/show telemetry. The first real impression after each load notifies
 * the screen once; binding or joining a pending load does not manufacture an impression.
 */
internal class FlowAdListener(
    private val delegate: AdEventListener?,
) : AdEventListener {

    private val impressionReported = AtomicBoolean(false)

    override fun onLoaded() {
        impressionReported.set(false)
        delegate?.onLoaded()
    }

    override fun onFailedToLoad() {
        delegate?.onFailedToLoad()
    }

    override fun onImpression() {
        if (impressionReported.compareAndSet(false, true)) {
            delegate?.onImpression()
        }
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

/** Wraps one flow slot's callbacks without duplicating its provider's telemetry. */
internal fun flowAdListener(
    listener: AdEventListener? = null,
): AdEventListener = FlowAdListener(listener)

/**
 * No ad was shown for this placement. [reason] is the precise cause, not a bucket — a dashboard
 * that cannot tell "premium" from "no fill" cannot act on either.
 */
internal fun AdPlacement.trackSkipped(reason: AdSkipReason) {
    OnboardingSdk.track(AnalyticsEvent.AdSkipped(key, format, reason.key))
}
