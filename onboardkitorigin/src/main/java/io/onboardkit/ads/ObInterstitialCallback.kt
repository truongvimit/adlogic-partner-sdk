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

    /** Navigation is committed immediately before vendor show; it is not proof of presentation. */
    open fun onNextAction() {}

    /** The vendor confirmed presentation. Forward this to enable the host-return fallback. */
    open fun onPresented() {}

    /** Terminal: the ad was displayed and dismissed. */
    open fun onAdClosed() {}

    /** Terminal: the ad never reached the screen. */
    open fun onAdSkipped(reason: AdSkipReason) {}

    /**
     * Terminal with explicit analytics ownership. True means the provider has handled the
     * canonical outcome (skipped or vendor show-failed); the flow must not emit it again.
     * Delegates to the original callback so existing consumers continue receiving outcomes.
     * Custom providers using the original overload retain flow-owned skipped reporting.
     */
    open fun onAdSkipped(reason: AdSkipReason, telemetryReported: Boolean) {
        onAdSkipped(reason)
    }
}
