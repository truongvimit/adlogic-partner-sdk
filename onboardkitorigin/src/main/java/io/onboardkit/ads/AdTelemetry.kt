package io.onboardkit.ads

import io.onboardkit.OnboardingSdk
import io.onboardkit.core.analytics.AnalyticsEvent
import io.trackkit.AdFormat
import java.util.concurrent.atomic.AtomicBoolean

/** Internal bind signal keeps flow analytics separate from the public display callback. */
internal interface NativeBindListener {
    fun onNativeBound()
}

/**
 * Reports the lifecycle of one ad slot, then forwards to the screen's own listener.
 *
 * Revenue, impressions-with-money and clicks all arrive through the vendor callback inside `:ads`.
 * What only this layer knows is the *opportunity*: which onboarding screen asked, and whether it
 * got anything. Wrapping the listener keeps that reporting in one place — the screens used to
 * duplicate it, or more often skip it.
 *
 * The native bind signal and failure are latched for flow analytics. The legacy
 * `ob_ad_impression` event maps to `fo_ad_bound`; vendor-counted `ad_show` remains owned by `:ads`.
 * Listener forwarding is unchanged for screens and partner providers.
 */
internal class TrackedAdListener(
    private val placementKey: String,
    private val format: AdFormat,
    private val delegate: AdEventListener?,
) : AdEventListener, NativeBindListener {

    private val boundReported = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)

    override fun onLoaded() {
        delegate?.onLoaded()
    }

    override fun onFailedToLoad() {
        if (failureReported.compareAndSet(false, true)) {
            if (com.ads.module.config.settings.AdBehavior.bool("diagnostics.ads_telemetry_enabled")) OnboardingSdk.track(AnalyticsEvent.AdFailed(placementKey, format))
        }
        delegate?.onFailedToLoad()
    }

    private fun reportBound() {
        if (boundReported.compareAndSet(false, true)) {
            if (com.ads.module.config.settings.AdBehavior.bool("diagnostics.ads_telemetry_enabled")) OnboardingSdk.track(AnalyticsEvent.AdImpression(placementKey, format))
        }
    }

    override fun onNativeBound() {
        reportBound()
        (delegate as? NativeBindListener)?.onNativeBound()
    }

    override fun onImpression() {
        // Compatibility for partner providers that only report impressions.
        reportBound()
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

/** Wraps [listener] so this placement's load outcome and first native bind reach flow analytics. */
internal fun AdPlacement.tracked(
    listener: AdEventListener? = null,
): AdEventListener = TrackedAdListener(key, format, listener)

/** A load is about to go out for this placement. */
internal fun AdPlacement.trackRequest() {
    if (com.ads.module.config.settings.AdBehavior.bool("diagnostics.ads_telemetry_enabled")) OnboardingSdk.track(AnalyticsEvent.AdRequested(key, format))
}

/**
 * No ad was shown for this placement. [reason] is the precise cause, not a bucket — a dashboard
 * that cannot tell "premium" from "no fill" cannot act on either.
 */
internal fun AdPlacement.trackSkipped(reason: AdSkipReason) {
    if (com.ads.module.config.settings.AdBehavior.bool("diagnostics.ads_telemetry_enabled")) OnboardingSdk.track(AnalyticsEvent.AdSkipped(key, format, reason.key))
}
