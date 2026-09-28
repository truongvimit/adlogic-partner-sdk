package io.onboardkit.ads

// onNextAction at most once, then exactly one of onAdClosed / onAdSkipped.
internal open class ObInterstitialCallback {

    open fun onNextAction() {}

    open fun onAdClosed() {}

    open fun onAdSkipped(reason: AdSkipReason) {}
}
