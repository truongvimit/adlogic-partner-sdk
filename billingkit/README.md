# BillingKit

Use BillingKit when your app has its own purchase UI. It connects to Google Play, loads your
product catalogue, launches purchases and exposes premium state. For a ready-made purchase
screen, start with [PayKit](../paykit/README.md); PayKit initializes BillingKit for you.

## Install

Follow the [root build setup](../README.md), then add this to `app/build.gradle`:

```groovy
def sdkVersion = '5.2.0' // Use the same published tag for every SDK module.
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"
    implementation "androidx.lifecycle:lifecycle-runtime-ktx:2.10.0" // Activity examples below.
}
```

Requires minSdk 24. Keep your app's existing Lifecycle version if already configured. Ads and
Firebase are optional. The module includes Play Billing 9.0.0; no separate BillingClient is needed.

## 1. Register your products

Replace the example IDs with your Google Play catalogue. Use `PURCHASE` for a lifetime unlock,
`SUBSCRIPTION` for a subscription and `CONSUMABLE` for a repeatable purchase.

```kotlin
import android.app.Application
import com.ads.module.billing.AppPurchase
import com.ads.module.billing.Billing
import com.ads.module.billing.BillingKit
import com.ads.module.billing.PurchaseItem

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        BillingKit.setDevMode(false) // Use real Play Billing, including in debug builds.
        AppPurchase.getInstance().initBilling(
            this,
            listOf(
                PurchaseItem("premium_lifetime", AppPurchase.TYPE_IAP.PURCHASE),
                PurchaseItem("premium_monthly", "monthly-base", "intro-offer",
                    AppPurchase.TYPE_IAP.SUBSCRIPTION),
            ),
        )
        Billing.install(this)
    }
}
```

Register your Application class with `android:name` in the app manifest, or add this setup to
your existing Application. Call it once per process. If you use PayKit, let `PayKit.install`
register its JSON catalogue instead of calling `initBilling` again.

## 2. Observe purchases and premium state

Start collecting in your Activity's `onCreate`, before enabling purchase buttons:

```kotlin
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.ads.module.billing.AppPurchase
import com.ads.module.billing.Billing
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

lifecycleScope.launch {
    Billing.purchaseEvents.collect { event ->
        Log.d("Billing", "Purchase result: $event") // Update your purchase UI here.
    }
}
lifecycleScope.launch {
    Billing.isPremium.collect { premium ->
        Log.d("Billing", "Premium: $premium") // Unlock or lock your premium features here.
    }
}
```

`purchaseEvents` reports purchased, pending, already owned, canceled and error outcomes.
It does not replay old events; use `isPremium` for the current entitlement, including the
cached value at startup. A pending purchase is not a completed purchase.

## 3. Read prices and launch from a user action

```kotlin
import com.ads.module.billing.ReadyResult

lifecycleScope.launch {
    if (Billing.awaitReady() == ReadyResult.Ready) {
        val price = AppPurchase.getInstance().getPrice("premium_lifetime")
        Log.d("Billing", "Lifetime price: $price")
    }
}
```

`awaitReady()` waits up to 5 seconds for connection and purchase verification. Product details
load separately: an empty price means they are not available yet. Keep the purchase button
unavailable until its price/details exist; `refreshProductDetails()` requests them again.

From the current Activity's purchase button:

```kotlin
val result = AppPurchase.getInstance().purchaseProduct(this, "premium_lifetime")
Log.d("Billing", "Launch result: $result")
```

For the subscription registered above:

```kotlin
val purchase = AppPurchase.getInstance()
val token = purchase.resolveOfferToken("premium_monthly", "monthly-base", "intro-offer")
if (token.isNotBlank()) {
    val result = purchase.subscribeProduct(this, "premium_monthly", token)
    Log.d("Billing", "Launch result: $result")
}
```

`LAUNCHED` means the Play flow opened; wait for `purchaseEvents` for the purchase outcome.
The offer resolver can fall back to another available offer when your coordinates do not
match. Check the catalogue and `getSubscriptionOffers(productId)` when a specific offer matters.

## Restore and optional integrations

From your Restore button, call `Billing.restore()` in `lifecycleScope.launch`. It returns
`Restored(productIds)`, `NothingToRestore` or `Error(code, message)` and updates `isPremium`.

- **Ads:** initialize billing before the first ad request. `Billing.install` connects premium
  state to the ads gate. To release buffered ads after a purchase, call
  `AdGate.installPremiumObserver(appScope, Billing.isPremium)` once with your application-owned
  coroutine scope; `AdGate` is `com.ads.module.helper.AdGate` from the `ads` module.
- **Analytics:** [install Tracker and a sink](../trackkit/README.md) before billing if you want
  SDK purchase events sent to your analytics service.
- **Local simulation:** `BillingKit.setDevMode(true)` simulates purchases without Play. Keep it
  `false` when testing real purchases or shipping. An explicit value overrides the ads dev flag.

## Troubleshooting

| Problem | Check |
|---|---|
| Empty price or `PRODUCT_NOT_FOUND` | Exact product ID, available Play catalogue and completion of the product-details query. |
| `BILLING_NOT_READY` | Initialization and Play connection; wait for readiness and available product details. |
| `NO_OFFER` or unexpected subscription offer | Base plan/offer availability and the resolver's fallback logs. |
| Premium user still sees ads | Initialize billing before requests and inspect `Billing.isPremium`. |

For receipt verification, Java callbacks and other options, see
[AppPurchase](src/main/java/com/ads/module/billing/AppPurchase.java) and
[Billing](src/main/java/com/ads/module/billing/Billing.kt).
