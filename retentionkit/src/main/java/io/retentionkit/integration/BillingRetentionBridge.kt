package io.retentionkit.integration

import com.ads.module.billing.Billing
import com.ads.module.billing.BillingEntitlement
import io.retentionkit.core.RetentionEntitlement
import io.retentionkit.core.RetentionModule
import io.retentionkit.core.RetentionRuntime
import io.retentionkit.core.RetentionSignal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/** Optional BillingKit adapter; cached/default isPremium and awaitReady are not entitlement proof. */
class BillingRetentionBridge @JvmOverloads constructor(
    private val entitlement: StateFlow<BillingEntitlement> = Billing.entitlement,
) : RetentionModule {
    override val id = "billing-bridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var closed = true
    override fun attach(runtime: RetentionRuntime) {
        closed = false
        fun publish(value: BillingEntitlement) {
            if (!closed) runtime.signal(RetentionSignal.EntitlementChanged(when (value) {
                BillingEntitlement.UNKNOWN -> RetentionEntitlement.UNKNOWN
                BillingEntitlement.VERIFIED_NON_PREMIUM -> RetentionEntitlement.NON_SUBSCRIBER
                BillingEntitlement.VERIFIED_PREMIUM -> RetentionEntitlement.SUBSCRIBER
            }))
        }
        // The engine-owned StateFlow replays verified state even when Billing.install ran late.
        publish(entitlement.value)
        scope.launch { entitlement.collect(::publish) }
    }
    override fun shutdown() { closed = true; scope.cancel() }
}
