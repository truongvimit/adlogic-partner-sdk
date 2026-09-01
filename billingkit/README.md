# BillingKit

> The Play Billing engine as a library: connection, catalogue, purchase and subscription flows,
> acknowledge/consume, cached entitlement, purchase telemetry.

Java package `com.ads.module.billing`, resource prefix `bk_`. Two entry points: `AppPurchase` (the
Java-friendly singleton) and `Billing` (coroutines and Flow over it). No ad code ships here.

## Requirements

| | |
|---|---|
| minSdk | 24 |
| Module bytecode | Java 8 / Kotlin `jvmTarget` 1.8 (the host app may be higher) |
| Play Billing Library | 9.0.0 |
| Arrives transitively (`api`) | `trackkit`, `kotlinx-coroutines-android` |

## Installation

```groovy
repositories { google(); mavenCentral(); maven { url 'https://jitpack.io' } }

// Replace <tag> with a tag from https://github.com/truongvimit/adlogic-partner-sdk/tags
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"
}
```

## Integration

**1. Register the catalogue in `Application.onCreate`.** `Billing.install` is idempotent.

```kotlin
AppPurchase.getInstance().initBilling(
    this,
    listOf(
        PurchaseItem("premium_monthly", "monthly-base", "", AppPurchase.TYPE_IAP.SUBSCRIPTION),
        PurchaseItem("premium_lifetime", AppPurchase.TYPE_IAP.PURCHASE),
    ),
)
Billing.install(this)
```

`PurchaseItem(itemId, type)` is enough for a one-time product. For a subscription the four-argument
form records `basePlanId` and `offerId`; the flow only honours them once you pass them through
`resolveOfferToken` and hand the token to `subscribeProduct`. `type` is `TYPE_IAP.PURCHASE`,
`TYPE_IAP.SUBSCRIPTION` or `TYPE_IAP.CONSUMABLE`. **With PayKit, skip this step** — `PayKit.install`
does both calls.

**2. Wait for Play, then read prices.** All price getters return `""` until details are loaded.

```kotlin
lifecycleScope.launch {
    val ready = Billing.awaitReady(timeoutMs = 5_000)     // Ready / Timeout / Error(code)
    val monthly = AppPurchase.getInstance().getPriceSub("premium_monthly")   // renewal price
    val lifetime = AppPurchase.getInstance().getPrice("premium_lifetime")    // one-time price
}
```

**3. Launch a flow and collect the outcome.** A null offer token makes the SDK pick the first offer
that has a zero-price phase, else the first offer — resolve the token to charge the plan you
registered.

```kotlin
val token = AppPurchase.getInstance().resolveOfferToken("premium_monthly", "monthly-base", "")
val result = AppPurchase.getInstance().subscribeProduct(activity, "premium_monthly", token)
// one-time products: AppPurchase.getInstance().purchaseProduct(activity, "premium_lifetime")

lifecycleScope.launch {
    Billing.purchaseEvents.collect { /* Purchased, Pending, AlreadyOwned, Error, Canceled */ }
}
lifecycleScope.launch { Billing.isPremium.collect { premium -> render(premium) } }
```

`result` is a `LaunchResult` — `LAUNCHED` means Play took over; every other value names why it did
not, and the enum documents each one.

**4. Restore.** From a coroutine, `Billing.restore()` returns `Restored(productIds)`,
`NothingToRestore` or `Error(code, message)`. Java hosts call `verifyPurchased(true)`, wait for
`BillingListener.onInitBillingFinished`, then read `getOwnedInAppPurchases()` / `getOwnerIdSubs()`.

## Options

Set these on `AppPurchase.getInstance()` before or just after `initBilling`; each is documented in
KDoc. The ones most integrations touch:

| Call | What it does |
|---|---|
| `setConsumePurchase(boolean)` | Consume one-time purchases instead of acknowledging them |
| `setPurchaseVerifier(PurchaseVerifier)` | Server-side receipt check; unset treats every purchase as verified |
| `setObfuscatedAccountId` / `setObfuscatedProfileId` | Fraud signals sent with each billing flow |
| `setBillingListener(BillingListener, int)` | Init callback, forced to fire after the timeout in ms |
| `refreshProductDetails()` | Re-query Play for the registered catalogue |

The verifier runs on purchases arriving from a billing flow, not on restores:

```kotlin
AppPurchase.getInstance().setPurchaseVerifier { productId, token, originalJson, callback ->
    callback.onResult(verified, reason)   // exactly once, any thread
}
```

Purchases reach `Tracker` as `iap_success` / `iap_fail`
([`../trackkit/README.md`](../trackkit/README.md)). Revenue also goes to the vendor-free
`io.trackkit.mmp.MmpTracking.trackPurchaseRevenue` seam, which is a no-op until a relay is
registered — `:ads` registers the Adjust one during `ERainAd.init`.

## Premium gating

`:ads` gates every load and show on `AdGate.isPurchased(context)`, which reads the `Entitlement`
port. BillingKit installs itself as that port's source on the first `AppPurchase.getInstance()` call,
or from `Billing.install` — whichever runs first. The hand-off runs once and is skipped when `:ads`
is absent. For purchases that land mid-session, drop what is already preloaded:

```kotlin
AdGate.installPremiumObserver(scope, Billing.isPremium)
```

## Dev mode

`BillingKit.setDevMode(true)` simulates purchases in a bottom sheet instead of Play and adds
`AppPurchase.PRODUCT_ID_TEST` (`android.test.purchased`) to the catalogue. With no explicit call,
`BillingKit.isDevMode()` falls back to `:ads`, which writes it from `ERainAdConfig` during
`ERainAd.init`. An explicit `setDevMode` wins.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `purchaseProduct` returns `BILLING_NOT_READY` | Called before Play connected | Gate the button on `Billing.awaitReady()` or `BillingListener.onInitBillingFinished` |
| `subscribeProduct` returns `NO_OFFER` | Play returned no offer — no active base plan, or the user's region excludes every offer | Inspect `getSubscriptionOffers(productId)` |
| The wrong plan is charged | A null offer token, so the SDK picked the first free-trial offer | Resolve the token with `resolveOfferToken(productId, basePlanId, offerId)` and pass it in |
| Prices come back `""` | Details not fetched, or the id is not in the catalogue | Re-check `initBilling`, then `refreshProductDetails()` |
| A premium user still sees ads | `Billing.install` never ran before the first ad request | Call it in `Application.onCreate` |
| Ads keep showing right after a purchase | Preloaded ads are still buffered | `AdGate.installPremiumObserver(scope, Billing.isPremium)` |
| A release build grants premium for free | Dev mode is on; `initBilling` logs this at ERROR | `BillingKit.setDevMode(false)`, or use `ERainAdConfig.ENVIRONMENT_PRODUCTION` |

## License

MIT — see [`../LICENSE`](../LICENSE).
