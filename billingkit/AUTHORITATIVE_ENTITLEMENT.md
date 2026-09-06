# Current-process entitlement

`com.ads.module.billing.BillingEntitlement` has three states:

- `UNKNOWN`: no authoritative result for this process and registered catalogue.
- `VERIFIED_NON_PREMIUM`: all configured ownership queries in one current sweep returned OK with non-null payloads, and no configured entitlement was owned.
- `VERIFIED_PREMIUM`: a complete successful sweep found a configured entitlement, a configured non-consumable PURCHASED receipt passed the existing optional verifier, or the host explicitly granted premium with `setPurchase(true)`.

```kotlin
import com.ads.module.billing.Billing
import com.ads.module.billing.BillingEntitlement

val current = Billing.entitlement.value
appScope.launch {
    Billing.entitlement.collect { state ->
        when (state) {
            BillingEntitlement.UNKNOWN -> { /* wait for ownership evidence */ }
            BillingEntitlement.VERIFIED_NON_PREMIUM -> { /* verified free account */ }
            BillingEntitlement.VERIFIED_PREMIUM -> { /* premium account */ }
        }
    }
}
```

`Billing.entitlement: StateFlow<BillingEntitlement>` is the engine's own flow, available before or after `Billing.install(app)`. Java uses `Billing.getEntitlement()`, `AppPurchase.getInstance().getEntitlement()`, or synchronous `getEntitlementSnapshot()`. Installing or collecting after verification reads that completed result immediately; no new verification callback is required. Access creates the engine if needed, but does not initialize/connect BillingClient or start a query.

The initial state is UNKNOWN even if cached `isPremium` is true/false. An empty catalogue, disconnected client, missing/partial callback, null successful payload or any failed query cannot manufacture VERIFIED_NON_PREMIUM. Failures retain an earlier verified result. An older overlapping sweep cannot replace a newer one; a purchase grant invalidates sweeps started before that grant. A new complete sweep started after a purchase can verify a refund/revocation and publish VERIFIED_NON_PREMIUM. Registering a catalogue through `initBilling` resets this evidence to UNKNOWN and invalidates old callbacks.

Pending/rejected receipts, unknown product IDs, consumables and the simulated `grantDevPurchase` path do not verify an entitlement. `setPurchase(true)` is already documented as the host's explicit backend grant and verifies premium. Legacy `setPurchase(false)` and `updatePurchaseStatus()` do not prove a complete Play sweep and cannot verify free; use `verifyPurchased`/`Billing.restore` for fresh authoritative ownership evidence.

This additive API does not change legacy `isPremium`, `isPurchased`, `awaitReady`, `verifyFinish`, restore results, callbacks or Java entry points. Those APIs retain their cached/compatibility behavior and must not be used as proof of successful verification. In particular, `awaitReady() == Ready` is not a replacement for this flow's evidence. Snapshot and publication derive from one atomic versioned source, with no host callback invoked under an engine lock. This module does not depend on RetentionKit.
