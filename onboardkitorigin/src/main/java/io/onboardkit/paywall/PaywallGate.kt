package io.onboardkit.paywall

import android.app.Activity

/** Checkpoints where a paywall may interleave with the flow. */
enum class PaywallPlacement {
    SPLASH_INTER,
    AFTER_ONBOARDING,
    AFTER_QUESTION_OLD_USER,
}

sealed interface PaywallOutcome {
    data object Purchased : PaywallOutcome
    data object Dismissed : PaywallOutcome
    data object ContinueWithAds : PaywallOutcome
}

/**
 * Injected by the app (RevenueCat, custom billing, …). The SDK only knows placements and
 * outcomes — no billing listener singleton, no race between screens.
 */
interface PaywallGate {
    suspend fun shouldShow(placement: PaywallPlacement): Boolean
    suspend fun present(activity: Activity, placement: PaywallPlacement): PaywallOutcome
}
