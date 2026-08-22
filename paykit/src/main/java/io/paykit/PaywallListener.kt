package io.paykit

/**
 * Paywall callbacks, registered globally via [PayKit.addListener] or per presentation via
 * [PayKit.launch].
 *
 * An open class rather than an interface: a callback added later must not break the Java hosts
 * that already implement it.
 */
open class PaywallListener {

    open fun onShown(placement: PaywallPlacement) {}

    open fun onPurchased(placement: PaywallPlacement, productId: String) {}

    open fun onContinueWithAds(placement: PaywallPlacement) {}

    open fun onDismissed(placement: PaywallPlacement) {}

    open fun onError(placement: PaywallPlacement, code: Int, message: String) {}

    /** Fires exactly once per presentation, on every exit path, after the specific callback. */
    open fun onFinished(placement: PaywallPlacement, result: PaywallResult) {}
}
