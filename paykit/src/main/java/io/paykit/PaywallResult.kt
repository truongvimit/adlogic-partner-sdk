package io.paykit

/** How one paywall presentation ended. */
sealed interface PaywallResult {

    data class Purchased(val productId: String) : PaywallResult

    data object ContinueWithAds : PaywallResult

    data object Dismissed : PaywallResult

    data class Error(val code: Int, val message: String) : PaywallResult
}
