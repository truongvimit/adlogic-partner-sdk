package com.ads.module.helper.interstitial

/**
 * When [InterShowCallback.onComplete] fires — which is to say, when the next screen starts.
 *
 * The module has always had this switch as a process-wide boolean
 * (`Admob.setOpenActivityAfterShowInterAds`); what it never had was a way to choose per show.
 * It is a real per-placement choice: a destination that must not exist behind the ad — one that
 * opens the camera, starts audio, or plays its own video — has to wait for [AfterDismiss], while
 * every ordinary screen is better off inflated and painted before the user first sees it.
 *
 * [InterstitialAdManager.defaultNextAction] is the app-wide default; pass a value to
 * [InterstitialAdManager.show] to override it for one presentation.
 */
sealed interface InterNextAction {

    /**
     * `onComplete` fires once the ad is gone — after `onClosed`, or after the skip that stood in
     * for it. The next screen starts on an empty stage.
     *
     * The module's default, and Apero's `openActivityAfterShowInterAds = false`.
     */
    data object AfterDismiss : InterNextAction

    /**
     * `onComplete` fires on the same tick as `show()`, immediately before it, so the next screen
     * inflates and binds *underneath* the ad and is already painted when it closes. `onClosed`
     * still follows later.
     *
     * Apero's `openActivityAfterShowInterAds = true`. Start the destination from `onComplete` and
     * nothing else — a caller that also calls `finish()` there tears the host out from under the
     * ad it just paid for.
     *
     * **The ordering is fixed, and deliberately not tunable.** GMA's `AdActivity` is declared with
     * no `taskAffinity` and the default launch mode, so it lives in the host's own task: a
     * `startActivity` issued while it is on top is stacked *above* the ad, which covers the
     * impression, and GMA then reports the dismissal the caller was waiting for. Firing on the
     * same tick as `show()` is what queues both launches together and lets the ad land on top.
     *
     * A next screen started this way plays its entry transition under a window that is still
     * translucent while the creative animates in, so the transition is briefly visible through the
     * ad. Suppress the transition — `overridePendingTransition(0, 0)`, or
     * `Intent.FLAG_ACTIVITY_NO_ANIMATION` — rather than delaying the start: only the first leaves
     * the launch order intact.
     */
    data object UnderAd : InterNextAction
}

/** What one `AdCallback.onNextAction` from the module means to the store. */
internal enum class NextActionMeaning {

    /** Nothing reached the screen: the module refused by one of its own frequency caps. */
    MODULE_CAP,

    /** The ad is going up, and the next screen may start underneath it. */
    NEXT_SCREEN,

    /** Already accounted for: the dismissal's own, or the one trailing a show failure. */
    IGNORED,
}

/**
 * The module spends one callback — `onNextAction` — on three unrelated events, and which one it is
 * can only be read from the state around it. [committed] is the `onInterstitialShow` marker;
 * [completed] says this presentation has already reported its outcome.
 */
internal fun meaningOfNextAction(
    nextAction: InterNextAction,
    committed: Boolean,
    completed: Boolean,
): NextActionMeaning = when {
    // onAdFailedToShow is chased by a bare onNextAction: that pair is one failure, not a cap.
    completed -> NextActionMeaning.IGNORED
    // No commit marker, so the module short-circuited before showing anything.
    !committed -> NextActionMeaning.MODULE_CAP
    nextAction == InterNextAction.UnderAd -> NextActionMeaning.NEXT_SCREEN
    // The dismissal's own onNextAction, which lands just before onAdClosed. Completing on it would
    // report the outcome one callback before onClosed got to say what the outcome was.
    else -> NextActionMeaning.IGNORED
}
