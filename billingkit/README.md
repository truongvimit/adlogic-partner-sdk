# BillingKit

> Architecture: **[../trackkit/ARCHITECTURE.md](../trackkit/ARCHITECTURE.md)** · Paywall:
> **[../paykit/README.md](../paykit/README.md)** · Analytics:
> **[../trackkit/README.md](../trackkit/README.md)**

The Play Billing engine as a library: the `BillingClient` connection with reconnect backoff, the
product catalogue, the purchase and subscription flows, acknowledge/consume with retries, the
optional server-side verifier, the cached entitlement, and purchase telemetry. It used to live
inside `:ads`; it moved out so a partner that runs ads sells nothing extra, and a partner that
sells needs no ads.

- Java package `com.ads.module.billing` — **unchanged on purpose**, so pre-split imports keep
  compiling · resource prefix `bk_` · entry points `AppPurchase` (Java) and `Billing` (Kotlin)
- Ads are **not** in this module. When `:ads` is on the classpath, BillingKit plugs its premium
  answer into the `Entitlement` port automatically; without `:ads` nothing ad-related exists.
- Purchase events leave through `Tracker` (`iap_success`, `iap_fail`) and purchase revenue through
  the vendor-free `io.trackkit.mmp.MmpTracking` seam — this module never learns which MMP you ship.

**Read [`../trackkit/README.md`](../trackkit/README.md) too.** Without `Tracker.install()` plus a
sink, every event this SDK emits is validated and then discarded.

---

## 1. Which partners need this module

| Scenario | Declare | Notes |
|---|---|---|
| Ads only, no IAP | — | Skip this module; the APK ships no Play Billing class |
| IAP + prebuilt paywall, no ads | `billingkit` + `paykit` | No GMA/AdMob in the APK |
| IAP with your own paywall UI | `billingkit` | Neither `paykit` nor `ads` required |
| Ads and IAP | `ads` + `billingkit` (+ `paykit`) | Premium gating works exactly as before the split |

Upgrading an existing IAP app across the split is **one Gradle line** — see
[`../MIGRATION.md`](../MIGRATION.md).

## 2. Gradle setup

```groovy
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"
}
```

`trackkit` and coroutines arrive transitively (`api`). Keep every module of the SDK on one tag.

## 3. Quick start

```kotlin
// Application.onCreate — early, so the cached entitlement gates ads from the first frame
AppPurchase.getInstance().initBilling(
    this,
    listOf(
        PurchaseItem("premium_monthly", "monthly-base", "", AppPurchase.TYPE_IAP.SUBSCRIPTION),
        PurchaseItem("premium_lifetime", AppPurchase.TYPE_IAP.PURCHASE),
    ),
)
Billing.install(this)
```

Then, from a screen:

```kotlin
val result = AppPurchase.getInstance().subscribeProduct(activity, "premium_monthly", null)
// LaunchResult tells launch failures apart; the outcome arrives via Billing.purchaseEvents

lifecycleScope.launch {
    Billing.isPremium.collect { premium -> render(premium) }
}
```

Java hosts use the same `AppPurchase` singleton and register a `PurchaseCallback`; the listener
interfaces (`PurchaseListener`, `BillingListener`, `UpdatePurchaseListener` in
`com.ads.module.funtion`) are unchanged from the pre-split SDK.

If you use PayKit, skip `initBilling`: `PayKit.install` registers the catalogue from the paywall
document and calls `Billing.install` itself.

## 4. How it cooperates with `:ads`

`:ads` gates every load and show on `AdGate.isPurchased`, which reads an `Entitlement` port with no
billing knowledge behind it. The first touch of `AppPurchase` (or `Billing.install`) installs this
engine as the port's source — when `:ads` is present on the classpath, and silently skipped when it
is not. There is nothing to wire and nothing to configure; the hand-off is also why premium users
stop seeing ads the moment a purchase completes, exactly as before the split.

The dependency is one-way: this module `compileOnly`-references `:ads`; `:ads` knows nothing about
this module.

## 5. Dev mode

`BillingKit.setDevMode(true)` simulates purchases without Play — the dev bottom sheet grants a
process-local entitlement that the next real verification takes away. It is fail-closed: off by
default. When `:ads` is present, `ERainAdConfig.variantDev` keeps driving it exactly as before the
split; an explicit `setDevMode` call wins over the config flag.

## 6. Server-side verification

```kotlin
AppPurchase.getInstance().setPurchaseVerifier { productId, token, json, callback ->
    // your backend call; invoke exactly once
    callback.onResult(verified, reason)
}
```

Applied to purchases arriving from a billing flow, not to restores. Unset means every purchase is
treated as verified.

## 7. Telemetry

| Signal | Leaves through | Notes |
|---|---|---|
| `iap_success` / `iap_fail` | `Tracker` | This engine is the only emitter — the paywall reports views and clicks, never revenue |
| Purchase revenue for the MMP | `io.trackkit.mmp.MmpTracking.trackPurchaseRevenue` | `:ads` registers the Adjust relay; a host with a different MMP registers its own `Relay` |

## 8. R8 / ProGuard

Consumer rules ship in the AAR: the public API is kept, and the `compileOnly` references into
`:ads` are `-dontwarn`-ed so an adsless host shrinks cleanly. Nothing to add on your side.
