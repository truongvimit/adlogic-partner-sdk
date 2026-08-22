package io.paykit.ui

/** The only channel a renderer has back into the SDK. */
fun interface PaywallActions {
    fun on(action: PaywallAction)
}

/** Every intent a user can express on the paywall. */
sealed interface PaywallAction {
    data class Select(val packageId: String) : PaywallAction
    data object Continue : PaywallAction
    data object ContinueWithAds : PaywallAction
    data object Restore : PaywallAction
    data object Close : PaywallAction
    data object Terms : PaywallAction
    data object Privacy : PaywallAction
}
