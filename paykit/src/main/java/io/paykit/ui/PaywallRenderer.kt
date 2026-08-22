package io.paykit.ui

import android.view.ViewGroup

/**
 * Draws the paywall. The SDK ships [DefaultPaywallRenderer]; a host already on Compose installs
 * its own and plants a `ComposeView` into [root], which is why this interface stays this small.
 *
 * Implementations read [PaywallUiState] and report intent through [PaywallActions] — they never
 * reach billing, config or analytics themselves.
 */
interface PaywallRenderer {

    /** [root] is the Activity content frame, still empty. */
    fun onCreate(root: ViewGroup, actions: PaywallActions)

    fun render(state: PaywallUiState)

    fun onDestroy() {}
}
