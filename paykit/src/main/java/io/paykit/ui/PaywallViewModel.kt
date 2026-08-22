package io.paykit.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.paykit.PaywallPlacement
import io.paykit.PaywallResult
import io.paykit.analytics.PaywallTracking
import io.paykit.billing.AlreadyOwnedEvent
import io.paykit.billing.BillingBridge
import io.paykit.billing.CanceledEvent
import io.paykit.billing.LaunchResult
import io.paykit.billing.NothingToRestoreResult
import io.paykit.billing.PendingEvent
import io.paykit.billing.PurchaseErrorEvent
import io.paykit.billing.PurchasedEvent
import io.paykit.billing.RestoreErrorResult
import io.paykit.billing.RestoredResult
import io.paykit.design.PaywallTheme
import io.paykit.model.PaywallPackage
import io.paykit.model.PriceView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-shot side effects the screen must perform on the ViewModel's behalf. */
internal sealed interface PaywallEffect {
    data class Exit(val result: PaywallResult) : PaywallEffect
    data class OpenLink(val url: String) : PaywallEffect
    data class Message(val kind: PaywallMessage) : PaywallEffect
}

/** Named instead of a string resource so the ViewModel stays free of `Resources`. */
internal enum class PaywallMessage {
    PURCHASE_FAILED,
    RESTORE_DONE,
    RESTORE_NOTHING,
    RESTORE_FAILED,
}

/**
 * Owns the paywall's state machine: load, select, purchase, restore, exit.
 *
 * It never touches `Context`, `Activity` or `Resources`. Everything that needs one arrives
 * through [Environment], and the single call that genuinely requires an Activity — launching
 * Play's billing flow — takes it as a parameter and never stores it.
 */
class PaywallViewModel(
    // A default rather than a call-site constant, so a test can load on its own scheduler.
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** Supplied by the screen: everything that only a `Context` can produce. */
    internal interface Environment {
        val placement: PaywallPlacement
        val termsUrl: String
        val privacyUrl: String

        /** Config, copy and theme already resolved; null when nothing usable could be read. */
        suspend fun loadContent(): Content?
    }

    internal data class Content(
        val packages: List<PaywallPackage>,
        val theme: PaywallTheme,
        val headline: String,
        val benefits: List<String>,
        val ctaLabel: String,
        val preselectedId: String?,
        val exitButtonEnabled: Boolean,
        val exitButtonDelayMs: Long,
        val continueWithAdsEnabled: Boolean,
        val restoreEnabled: Boolean,
    )

    private val _uiState = MutableStateFlow<PaywallUiState>(PaywallUiState.Loading)
    val uiState: StateFlow<PaywallUiState> = _uiState.asStateFlow()

    private val effectChannel = Channel<PaywallEffect>(Channel.BUFFERED)
    internal val effects = effectChannel.receiveAsFlow()

    private var environment: Environment? = null
    private var catalog: List<PaywallPackage> = emptyList()
    private var ready: PaywallUiState.Ready? = null
    private var started = false

    // The outcome is kept here, not only in the effect channel: the screen can be destroyed with
    // an Exit still buffered, and it has to report what was decided rather than assume a dismissal.
    private var committed: PaywallResult? = null

    /** Held apart from the UI state: a launch failure arrives after [showReady] cleared it. */
    private var pendingPurchaseId: String? = null

    /** Idempotent: a re-created screen re-attaches its [Environment] but never reloads. */
    internal fun start(environment: Environment) {
        this.environment = environment
        if (started) return
        started = true
        viewModelScope.launch { load(environment) }
        viewModelScope.launch { observePurchases() }
        viewModelScope.launch { observePremium() }
    }

    /** The outcome already decided, for a screen destroyed before it collected the exit effect. */
    internal fun committedResult(): PaywallResult? = committed

    internal fun onAction(action: PaywallAction, host: Activity) {
        when (action) {
            is PaywallAction.Select -> select(action.packageId)
            PaywallAction.Continue -> purchase(host)
            PaywallAction.ContinueWithAds -> continueWithAds()
            PaywallAction.Restore -> restore()
            PaywallAction.Close -> close()
            PaywallAction.Terms -> openLink(environment?.termsUrl)
            PaywallAction.Privacy -> openLink(environment?.privacyUrl)
        }
    }

    private suspend fun load(environment: Environment) {
        val content = withContext(io) { environment.loadContent() }
        if (content == null || content.packages.isEmpty()) {
            fail(ERROR_NO_CONFIG, "No paywall configuration is available")
            return
        }
        // Prices come from Play; a paywall drawn before they arrive shows empty price rows.
        if (!BillingBridge.awaitReady(BILLING_TIMEOUT_MS)) {
            PaywallTracking.fail(null, ERROR_BILLING_UNAVAILABLE)
            fail(ERROR_BILLING_UNAVAILABLE, "Billing is not available")
            return
        }
        catalog = content.packages
        val selected = content.preselectedId ?: content.packages.first().id
        ready = PaywallUiState.Ready(
            packages = priceViews(selected),
            selectedId = selected,
            theme = content.theme,
            headline = content.headline,
            benefits = content.benefits,
            ctaLabel = content.ctaLabel,
            closeVisible = content.exitButtonEnabled && content.exitButtonDelayMs <= 0L,
            continueWithAdsVisible = content.continueWithAdsEnabled,
            restoreVisible = content.restoreEnabled,
        )
        showReady()
        if (content.exitButtonEnabled && content.exitButtonDelayMs > 0L) {
            revealCloseAfter(content.exitButtonDelayMs)
        }
    }

    // On viewModelScope, so the reveal survives a rotation instead of dying with a posted callback.
    private fun revealCloseAfter(delayMs: Long) {
        viewModelScope.launch {
            delay(delayMs)
            mutateReady { it.copy(closeVisible = true) }
        }
    }

    private suspend fun observePurchases() {
        BillingBridge.events.collect { event ->
            when (event) {
                is PurchasedEvent -> purchased(event.productId)
                is AlreadyOwnedEvent -> purchased(event.productId)
                // A deferred payment is not an entitlement yet; isPremium flips if Play settles it.
                is PendingEvent -> settle()
                is PurchaseErrorEvent -> {
                    PaywallTracking.fail(pendingPurchaseId, event.code)
                    emitMessage(PaywallMessage.PURCHASE_FAILED)
                    settle()
                }
                is CanceledEvent -> settle()
            }
        }
    }

    private suspend fun observePremium() {
        var wasPremium = BillingBridge.isPremium.value
        BillingBridge.isPremium.collect { premium ->
            val hadPremium = wasPremium
            wasPremium = premium
            if (!premium || hadPremium) return@collect
            when (val current = _uiState.value) {
                // Only a flow this screen launched may be reported as bought here, and it carries
                // the id the user actually chose rather than whichever row happens to be selected.
                is PaywallUiState.Purchasing -> purchased(current.productId)
                // restore() owns its own outcome and knows the real restored id.
                PaywallUiState.Restoring -> Unit
                // An entitlement that merely arrived from Play's verification sweep closes the
                // screen without claiming a sale nobody made on it.
                PaywallUiState.Loading, is PaywallUiState.Ready, is PaywallUiState.Error ->
                    exit(PaywallResult.Dismissed)
            }
        }
    }

    private fun select(packageId: String) {
        val current = _uiState.value as? PaywallUiState.Ready ?: return
        if (current.selectedId == packageId) return
        mutateReady { it.copy(selectedId = packageId, packages = priceViews(packageId)) }
    }

    // Guarded on the live state, not on the stored Ready: the state machine, and not a renderer's
    // own debouncing, is what keeps a second billing flow from opening on top of the first.
    private fun purchase(host: Activity) {
        val current = _uiState.value as? PaywallUiState.Ready ?: return
        val target = catalog.firstOrNull { it.id == current.selectedId } ?: return
        PaywallTracking.click(placement(), target.id)
        pendingPurchaseId = target.id
        _uiState.value = PaywallUiState.Purchasing(target.id)
        when (BillingBridge.launch(host, target)) {
            // Dev mode shows the simulated sheet and reports through the same callbacks as Play.
            LaunchResult.LAUNCHED, LaunchResult.DEV_MODE -> Unit
            // Every other outcome is fanned out as a purchase event, which owns the message and
            // the iap_fail; this only drops the overlay so a lost event cannot strand the screen.
            else -> showReady()
        }
    }

    private fun restore() {
        val current = _uiState.value as? PaywallUiState.Ready ?: return
        if (!current.restoreVisible) return
        _uiState.value = PaywallUiState.Restoring
        viewModelScope.launch {
            when (val result = BillingBridge.restore()) {
                is RestoredResult -> {
                    emitMessage(PaywallMessage.RESTORE_DONE)
                    // Play can return an owned product that grants nothing here, so it is the
                    // entitlement that decides whether this counts as a purchase.
                    if (BillingBridge.isPremium.value) {
                        purchased(result.productIds.firstOrNull() ?: current.selectedId)
                    } else {
                        showReady()
                    }
                }
                is NothingToRestoreResult -> {
                    emitMessage(PaywallMessage.RESTORE_NOTHING)
                    showReady()
                }
                is RestoreErrorResult -> {
                    PaywallTracking.fail(null, result.code)
                    emitMessage(PaywallMessage.RESTORE_FAILED)
                    showReady()
                }
            }
        }
    }

    private fun continueWithAds() {
        PaywallTracking.continueWithAds(placement())
        exit(PaywallResult.ContinueWithAds)
    }

    private fun close() {
        PaywallTracking.close(placement())
        exit(PaywallResult.Dismissed)
    }

    // Iap.Success belongs to :ads handlePurchase; emitting it here too would double-count revenue.
    private fun purchased(productId: String) {
        exit(PaywallResult.Purchased(productId))
    }

    // No iap_fail here: the terminal iap_paywall_result already carries the error, and mixing
    // these codes into the same error_code dimension as Play's would make neither readable.
    private fun fail(code: Int, message: String) {
        _uiState.value = PaywallUiState.Error(code, message)
        exit(PaywallResult.Error(code, message))
    }

    private fun exit(result: PaywallResult) {
        if (committed != null) return
        committed = result
        effectChannel.trySend(PaywallEffect.Exit(result))
    }

    // One place to end a purchase attempt: the pending id must not outlive it, or the next failure
    // would be attributed to a product this screen never launched.
    private fun settle() {
        pendingPurchaseId = null
        showReady()
    }

    private fun openLink(url: String?) {
        effectChannel.trySend(PaywallEffect.OpenLink(url ?: return))
    }

    private fun emitMessage(kind: PaywallMessage) {
        effectChannel.trySend(PaywallEffect.Message(kind))
    }

    private fun priceViews(selectedId: String): List<PriceView> =
        catalog.map { BillingBridge.priceOf(it, it.id == selectedId) }

    private fun mutateReady(block: (PaywallUiState.Ready) -> PaywallUiState.Ready) {
        val next = block(ready ?: return)
        ready = next
        // While purchasing or restoring the overlay owns the screen; showReady replays this later.
        if (_uiState.value is PaywallUiState.Ready) _uiState.value = next
    }

    private fun showReady() {
        _uiState.value = ready ?: return
    }

    private fun placement(): PaywallPlacement = environment?.placement ?: PaywallPlacement.OTHER

    internal companion object {
        internal const val ERROR_NOT_INSTALLED = 1
        internal const val ERROR_NO_CONFIG = 2
        internal const val ERROR_BILLING_UNAVAILABLE = 3

        private const val BILLING_TIMEOUT_MS = 5_000L
    }
}
