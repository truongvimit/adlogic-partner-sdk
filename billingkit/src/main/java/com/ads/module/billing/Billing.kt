package com.ads.module.billing

import android.app.Application
import com.ads.module.funtion.BillingListener
import com.ads.module.funtion.PurchaseCallback
import com.android.billingclient.api.BillingClient
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Coroutine and Flow face of [AppPurchase]; every Java entry point stays where it was.
 *
 * [isPremium] is seeded from the cached entitlement so the first collection is right before Play
 * has answered, then republished on every verification.
 */
object Billing {

    private const val RESTORE_TIMEOUT_MS = 10_000L

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _purchaseEvents = MutableSharedFlow<PurchaseEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val purchaseEvents: SharedFlow<PurchaseEvent> = _purchaseEvents.asSharedFlow()

    private val installed = AtomicBoolean(false)
    private var application: Application? = null

    /**
     * Idempotent; call once from `Application.onCreate` after `AppPurchase.initBilling`.
     *
     * When `:ads` is on the classpath this also plugs the premium signal into its Entitlement
     * port, so ad gating follows purchases with no extra wiring.
     */
    @JvmStatic
    fun install(app: Application) {
        if (!installed.compareAndSet(false, true)) return
        application = app
        EntitlementWiring.installIntoAdsIfPresent()

        val purchase = AppPurchase.getInstance()
        _isPremium.value = purchase.isPurchased(app)

        // every Play answer republishes the flow, otherwise a refund or a cross-device restore
        // that arrives through the ordinary startup sweep is never seen by collectors
        purchase.addVerifyCompletionListener(BillingListener { syncPremium() })

        purchase.addPurchaseCallback(object : PurchaseCallback() {
            override fun onProductPurchased(orderId: String?, originalJson: String?) {
                syncPremium()
                emit(PurchaseEvent.Purchased(purchase.idPurchased.orEmpty(), orderId.orEmpty()))
            }

            override fun onPurchasePending(productId: String?) {
                emit(PurchaseEvent.Pending(productId.orEmpty()))
            }

            override fun onAlreadyOwned(productId: String?) {
                syncPremium()
                emit(PurchaseEvent.AlreadyOwned(productId.orEmpty()))
            }

            override fun onPurchaseError(responseCode: Int, message: String?) {
                emit(PurchaseEvent.Error(responseCode, message.orEmpty()))
            }

            override fun onUserCancelBilling() {
                emit(PurchaseEvent.Canceled)
            }
        })
    }

    /** Suspends until the first purchase verification completes. */
    suspend fun awaitReady(timeoutMs: Long = 5_000): ReadyResult {
        val purchase = AppPurchase.getInstance()
        if (purchase.isAvailable() && purchase.isVerifyFinish()) {
            syncPremium()
            return ReadyResult.Ready
        }
        val code = withTimeoutOrNull(timeoutMs) { awaitVerify(purchase) } ?: return ReadyResult.Timeout
        syncPremium()
        return if (code == BillingClient.BillingResponseCode.OK) ReadyResult.Ready else ReadyResult.Error(code)
    }

    /** Re-queries Play for everything this account owns and republishes [isPremium]. */
    suspend fun restore(): RestoreResult {
        val purchase = AppPurchase.getInstance()
        if (!purchase.isAvailable()) {
            return RestoreResult.Error(
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                "Billing is not connected",
            )
        }

        val code = withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
            awaitVerify(purchase) { purchase.verifyPurchased(true) }
        } ?: return RestoreResult.Error(
            BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
            "Restore timed out",
        )

        syncPremium()
        if (code != BillingClient.BillingResponseCode.OK) {
            return RestoreResult.Error(code, "Restore failed")
        }

        val restored = mutableListOf<String>()
        purchase.ownedInAppPurchases.forEach { restored.addAll(it.productId.orEmpty()) }
        purchase.ownerIdSubs.forEach { restored.addAll(it.productId.orEmpty()) }
        val productIds = restored.distinct()
        return if (productIds.isEmpty()) RestoreResult.NothingToRestore else RestoreResult.Restored(productIds)
    }

    private suspend fun awaitVerify(purchase: AppPurchase, onRegistered: () -> Unit = {}): Int =
        suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            val listener = BillingListener { code ->
                if (resumed.compareAndSet(false, true)) continuation.resume(code)
            }
            // unregistered on timeout, otherwise repeated waits grow the listener list without bound
            continuation.invokeOnCancellation { purchase.cancelAwaitNextVerify(listener) }
            purchase.awaitNextVerify(listener)
            onRegistered()
            // a completion that landed before this registration would never fire the listener
            if (purchase.isVerifyFinish() && resumed.compareAndSet(false, true)) {
                purchase.cancelAwaitNextVerify(listener)
                continuation.resume(BillingClient.BillingResponseCode.OK)
            }
        }

    private fun syncPremium() {
        val app = application ?: return
        _isPremium.value = AppPurchase.getInstance().isPurchased(app)
    }

    private fun emit(event: PurchaseEvent) {
        _purchaseEvents.tryEmit(event)
    }
}

sealed interface PurchaseEvent {
    data class Purchased(val productId: String, val orderId: String) : PurchaseEvent
    data class Pending(val productId: String) : PurchaseEvent
    data class AlreadyOwned(val productId: String) : PurchaseEvent
    data class Error(val code: Int, val message: String) : PurchaseEvent
    data object Canceled : PurchaseEvent
}

sealed interface ReadyResult {
    data object Ready : ReadyResult
    data object Timeout : ReadyResult
    data class Error(val code: Int) : ReadyResult
}

sealed interface RestoreResult {
    data class Restored(val productIds: List<String>) : RestoreResult
    data object NothingToRestore : RestoreResult
    data class Error(val code: Int, val message: String) : RestoreResult
}
