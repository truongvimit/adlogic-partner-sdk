# BillingKit — purchases with your own UI

[← Choose a guide](README.md) · [BillingKit API](../billingkit/README.md)

Use this when your app **builds its own purchase screen**. If you use the [PayKit](paywall-integration.md) UI, follow that guide instead; PayKit already initializes BillingKit and the catalogue.

Only [BillingProducts.kt](examples/billing/BillingProducts.kt) is new; the rest merges into your existing Gradle, Application and purchase screen. No separate JSON or BillingClient is needed.

## 1. Prepare and add the dependency

Complete the [shared build setup](../README.md#build-setup): JDK 17, `minSdk 24+`, `compileSdk 36+`, the `adlogicSdkVersion` property and the repositories. An app that only uses billing needs no mediation repositories, AdMob IDs or Firebase.

```groovy
// app/build.gradle
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"
}
```

The examples use `AppCompatActivity`, `lifecycleScope` and `repeatOnLifecycle`; your app needs AndroidX AppCompat and `androidx.lifecycle:lifecycle-runtime-ktx` as in the [BillingKit build setup](../billingkit/README.md#install). The SDK already provides BillingClient at runtime along with the Trackkit and coroutines APIs.

Create your product IDs and base plans/offers in the Play Console. The example IDs are **not shared test products**. When testing, use your app's own package name and a license tester account, and check that the dialog offers a test payment method; the internal test track does not grant license tester rights by itself. [Google: test Billing](https://developer.android.com/google/play/billing/test).

## 2. Catalogue and Application

Copy `BillingProducts.kt`, change `com.example.app` and the IDs/plans/offers; keep only the products your app sells. Use these constants at your purchase buttons.

Merge this into your existing Application; if you have no Application yet, create the class below and declare `android:name` in the manifest:

```kotlin
package com.example.app

import android.app.Application
import com.ads.module.billing.AppPurchase
import com.ads.module.billing.Billing
import com.ads.module.billing.BillingKit

class BillingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Using analytics? Install Tracker and its sinks before this billing block.
        BillingKit.setDevMode(false)
        AppPurchase.getInstance().initBilling(this, BillingProducts.items)
        Billing.install(this)
    }
}
```

Keep `setDevMode(false)` so Play is used in debug builds too; dropping this line can let billing inherit the ads dev flag and simulate transactions. Initialize once in Application; do not open/close the connection per screen.

## 3. Observe results and premium state

Call the function below **once in the `onCreate`** of your purchase screen: `onPremium` updates access, `onPurchase` updates transaction state.

```kotlin
package com.example.app

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ads.module.billing.Billing
import com.ads.module.billing.PurchaseEvent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

fun AppCompatActivity.observeBilling(
    onPremium: (Boolean) -> Unit,
    onPurchase: (PurchaseEvent) -> Unit,
) {
    lifecycleScope.launch {
        // Keep the collector alive while Play covers the app; this flow does not replay.
        Billing.purchaseEvents.collect { onPurchase(it) }
    }
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            Billing.isPremium.collect { onPremium(it) }
        }
    }
}
```

| Result | What your app does |
| --- | --- |
| `Purchased`, `AlreadyOwned` | Update the message; take premium access from `Billing.isPremium`. |
| `Pending` | Show payment pending; do not grant the purchase yet. |
| `Canceled` | Dismiss loading and let the user act again. |
| `Error(code, message)` | Show the error/retry; never turn premium on yourself. |

`purchaseEvents` does not replay old events. Restore UI access from `Billing.isPremium` (cached at startup, updated after the Play check). Consumables need your app to grant the item itself; do not treat every `Purchased` as premium.

## 4. Prices and the purchase button

`Billing.awaitReady()` waits for the connection and purchase verification; prices load separately. The sample waits up to 5 seconds for a price so the UI can retry; that timeout belongs to the sample.

```kotlin
package com.example.app

import com.ads.module.billing.AppPurchase
import com.ads.module.billing.Billing
import com.ads.module.billing.ReadyResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

suspend fun lifetimePriceOrNull(): String? {
    if (Billing.awaitReady() != ReadyResult.Ready) return null
    val purchase = AppPurchase.getInstance()
    purchase.refreshProductDetails()
    return withTimeoutOrNull(5_000) {
        while (purchase.getPrice(BillingProducts.LIFETIME).isNullOrBlank()) delay(200)
        purchase.getPrice(BillingProducts.LIFETIME)
    }
}
```

Call it inside `lifecycleScope.launch`: disable the purchase button while waiting, show the price from Play and enable the button once a price exists; on `null`, allow a retry. Do not hardcode prices or currency.

From the Activity's purchase click, call one of the two functions below and handle the `LaunchResult`:

```kotlin
package com.example.app

import android.app.Activity
import com.ads.module.billing.AppPurchase
import com.ads.module.billing.LaunchResult

fun Activity.buyLifetime(): LaunchResult =
    AppPurchase.getInstance().purchaseProduct(this, BillingProducts.LIFETIME)

fun Activity.buyYearly(): LaunchResult {
    val purchase = AppPurchase.getInstance()
    val token = purchase.resolveOfferToken(
        BillingProducts.YEARLY, BillingProducts.YEARLY_BASE_PLAN, BillingProducts.YEARLY_OFFER,
    )
    if (token.isBlank()) return LaunchResult.NO_OFFER
    return purchase.subscribeProduct(this, BillingProducts.YEARLY, token)
}
```

`LAUNCHED` only means the Play UI opened; wait for the event and the premium state. Any other result must end the loading state so the error/retry can be handled; `DEV_MODE` is a simulation.

Subscriptions: `getPriceSub(productId, token)` returns the **first phase** price of the selected offer; `getPriceSub(productId)` returns the renewal price of the last offer in the Play data, without selecting by token. The sample uses one plan per product; with several plans/offers, check that the displayed price, billing period and trial match the offer being purchased. The resolver has a fallback; do not infer a trial from the example ID names.

The **Restore** button calls `Billing.restore()` in a coroutine. Handle `Restored(productIds)`, `NothingToRestore` and `Error(code, message)`; the SDK updates `isPremium`. There is no need to initialize billing again before each restore.

## 5. Configuration table and optional integrations

| Configuration | Default / when you need it |
| --- | --- |
| `BillingKit.setDevMode` | Unset, it inherits the ads dev flag if there is one, otherwise `false`. The sample sets `false` explicitly; use `true` only to try the simulated UI. |
| `PurchaseItem.type` | Choose `PURCHASE`, `SUBSCRIPTION` or `CONSUMABLE` explicitly; no catalogue is created on Play for you. |
| Base plan / offer | Set the real coordinates. `offerId = ""` lets the SDK choose an offer inside the plan and **does not force a purchase of the base plan with no offer**. When they do not match, the resolver can pick another offer. |
| `Billing.awaitReady(timeoutMs)` | 5 seconds. On splash timeout/error, continue with the premium state stored in the app; when the purchase screen has no price yet, allow a retry. |
| `Billing.restore()` | Internal 10-second timeout, no public timeout parameter. |
| Receipt verification through your backend | `AppPurchase.getInstance().setPurchaseVerifier(...)`; unset, there is no server check of your own. The hook applies only to purchases from the billing flow, **not to the startup/restore sweep**. See [PurchaseVerifier](../billingkit/src/main/java/com/ads/module/billing/PurchaseVerifier.java). |
| Your app's account/profile | `AppPurchase.getInstance().setObfuscatedAccountId(...)` / `setObfuscatedProfileId(...)` before launching, when you need to link the account. |
| Analytics / Adjust | [Trackkit](trackkit-integration.md) before billing; Adjust uses the [ads/Adjust wiring](ads-onboarding-integration.md#adjust-tokens-and-verification) and does not send duplicate purchase events. |

### When your app has ads/onboarding

The standard flow uses the premium state BillingKit stores in the app. `Billing.install()` connects that source to the ad gate; PayKit performs the same step. In your existing `ObSplashActivity`, add:

```kotlin
override suspend fun onInitBilling() {
    com.ads.module.billing.Billing.awaitReady()
}
```

**On splash timeout/error, continue onboarding with the premium state stored in the app:** premium skips ads, not premium follows the ads config. Do not add a waiting screen, reset premium or force premium to `false` yourself. When Play returns a successful verification, BillingKit updates the cache, `Billing.isPremium` and the ad gate; a failed verification keeps the previous value.

To release preloaded ads on a successful purchase, call `AdGate.installPremiumObserver(appScope, Billing.isPremium)` once with a scope that lives as long as the Application.

**Only when your app manages premium from its own source:** BillingKit does not read your app's cache/repository. After `Billing.install()` or `PayKit.install()`, install `com.ads.module.helper.Entitlement.install(source)`; `source` implements `EntitlementSource.isPremium(context)` and reads the premium value already loaded from your app's existing source. Your app keeps that source in sync as it receives purchase/restore/backend results. This configuration only changes the source that gates ads; it does not change `Billing.isPremium` or PayKit's premium state. Do not call `setPurchase(false)` just because Play has not answered yet.

## 6. Verify

- [ ] The catalogue matches Play; a real price exists before the purchase button is enabled.
- [ ] Test success, cancel, error and pending; `LAUNCHED` does not unlock premium by itself.
- [ ] Relaunching the app/restore gives the right entitlement; consumables do not grant premium.
- [ ] Splash timeout: a premium cache still skips ads; a non-premium cache continues with the ads config. Purchase/restore updates the UI and the premium source the ad gate uses.
- [ ] Play transactions use a license tester and `setDevMode(false)`, not to be confused with the local simulation.

Empty price: check the catalogue, the test account and the Play connection. Wrong offer: check the product's actual plan/offer.
