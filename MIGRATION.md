# Migration: the billing engine moved from `ads` to `billingkit`

The Play Billing engine (`com.ads.module.billing.*` and the billing listeners in
`com.ads.module.funtion.*`) now ships as its own module, `billingkit`. `ads` no longer contains a
single Play Billing class, so an IAA-only partner no longer ships `com.android.billingclient` in
the APK or declares it in data safety.

## If your app sells IAP or subscriptions

Add **one line** to `build.gradle`, next to the modules you already declare:

```groovy
implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"
```

That is the entire migration:

- **No code changes.** Every class kept its fully qualified name — `com.ads.module.billing.AppPurchase`,
  `com.ads.module.billing.Billing`, `PurchaseItem`, `PurchaseVerifier`, the listeners in
  `com.ads.module.funtion.*`. Imports, call sites and ProGuard rules keep working as-is.
- **No behaviour changes.** Ad gating by premium, the cached entitlement, dev mode driven by
  `ERainAdConfig.variantDev`, purchase events and Adjust revenue all work exactly as before when
  `ads` and `billingkit` are both present. `billingkit` plugs itself into `ads` automatically.
- `paykit` already depends on `billingkit`, so a paywall app that upgrades every module to the same
  tag gets it transitively at runtime. Declare it explicitly anyway (as above) if your own code
  calls `AppPurchase` / `Billing` directly — `implementation` scope does not put it on your compile
  classpath through `paykit`.

## If your app runs ads only (no IAP)

Do nothing. Ads keep working, `AdGate.isPurchased` now answers through the `Entitlement` port and
returns `false` with no billing engine installed, and your APK drops the Play Billing library.

## If you build a paywall without ads

Declare `billingkit` (and `paykit` for the prebuilt UI) and skip `ads` entirely — the APK contains
no GMA/AdMob classes. Set dev mode through `BillingKit.setDevMode(boolean)`; it defaults to off.

## Deprecated, still working

- `ERainLogEventManager.onTrackRevenuePurchase(...)` / `onTrackPurchaseFail(...)` — kept and
  delegating; the engine now reports purchases itself through `Tracker` and
  `io.trackkit.mmp.MmpTracking`.
- `com.ads.module.event.MmpTracking` remains the MMP door for code that depends on `ads`; the
  registry underneath it moved to `io.trackkit.mmp.MmpTracking` so `billingkit` can report purchase
  revenue without knowing `ads` exists.
