package io.onboardkit.ads

/**
 * The two moments of one interstitial presentation, in the shape the ads module reports them.
 *
 * They are separate because the Activity that was handed to `show()` must stay alive until the ad
 * is gone, while the next screen should already be starting underneath it. Collapsing both into
 * one callback is what kills an ad the app just paid to load.
 *
 * Implementations of [OnboardingAdProvider.showInterstitial] must call [onNextAction] at most
 * once and exactly one terminal callback ([onAdClosed] or [onAdSkipped]).
 */
open class ObInterstitialCallback {

    /** The ad is committed to the screen. Safe to start the destination underneath it. */
    open fun onNextAction() {}

    /** Terminal: the ad was displayed and dismissed. */
    open fun onAdClosed() {}

    /** Terminal: the ad never reached the screen. */
    open fun onAdSkipped(reason: AdSkipReason) {}
}
