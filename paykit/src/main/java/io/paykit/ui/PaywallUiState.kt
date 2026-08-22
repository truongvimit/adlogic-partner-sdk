package io.paykit.ui

import io.paykit.design.PaywallTheme
import io.paykit.model.PriceView

/**
 * Everything a renderer needs to draw the paywall, already resolved and formatted.
 *
 * Nothing here is a resource id or a raw config value: a renderer that swaps Views for a
 * `ComposeView` needs no access to the SDK's resources, config store or billing engine.
 */
sealed interface PaywallUiState {

    data object Loading : PaywallUiState

    data class Ready(
        val packages: List<PriceView>,
        val selectedId: String,
        val theme: PaywallTheme,
        val headline: String,
        val benefits: List<String>,
        val ctaLabel: String,
        val closeVisible: Boolean,
        val continueWithAdsVisible: Boolean,
        val restoreVisible: Boolean,
    ) : PaywallUiState

    data class Purchasing(val productId: String) : PaywallUiState

    data object Restoring : PaywallUiState

    data class Error(val code: Int, val message: String) : PaywallUiState
}
