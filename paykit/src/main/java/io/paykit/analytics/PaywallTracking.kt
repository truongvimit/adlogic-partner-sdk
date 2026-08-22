package io.paykit.analytics

import io.paykit.PaywallPlacement
import io.paykit.PaywallResult
import io.trackkit.Tracker
import io.trackkit.TrackkitEvents

/**
 * Every paywall event, emitted through the shared taxonomy and nowhere else.
 *
 * `iap_success` is deliberately missing: `:ads` emits it when Play confirms the purchase, so
 * emitting it here as well would double-count the revenue on two different clocks.
 */
internal object PaywallTracking {

    private const val REASON_CLOSE = "close"
    private const val REASON_CONTINUE_WITH_ADS = "continue_with_ads"
    private const val STATUS_PURCHASED = "purchased"
    private const val STATUS_DISMISSED = "dismissed"
    private const val STATUS_ERROR = "error"

    fun paywallView(placement: PaywallPlacement) {
        Tracker.track(TrackkitEvents.Iap.PaywallView(placement.key))
    }

    fun click(placement: PaywallPlacement, productId: String) {
        Tracker.track(TrackkitEvents.Iap.Click(placement.key, productId))
    }

    fun fail(productId: String?, errorCode: Int?) {
        Tracker.track(TrackkitEvents.Iap.Fail(productId, errorCode))
    }

    fun continueWithAds(placement: PaywallPlacement) {
        Tracker.track(TrackkitEvents.Iap.Dismiss(placement.key, REASON_CONTINUE_WITH_ADS))
    }

    fun close(placement: PaywallPlacement) {
        Tracker.track(TrackkitEvents.Iap.Dismiss(placement.key, REASON_CLOSE))
    }

    /** Terminal event: every [paywallView] gets exactly one, so conversion is the pair alone. */
    fun result(placement: PaywallPlacement, result: PaywallResult) {
        val status = when (result) {
            is PaywallResult.Purchased -> STATUS_PURCHASED
            is PaywallResult.ContinueWithAds -> REASON_CONTINUE_WITH_ADS
            is PaywallResult.Dismissed -> STATUS_DISMISSED
            is PaywallResult.Error -> STATUS_ERROR
        }
        Tracker.track(TrackkitEvents.Iap.PaywallResult(placement.key, status))
    }
}
