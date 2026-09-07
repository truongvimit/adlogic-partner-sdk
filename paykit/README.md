# PayKit

PayKit provides a purchase screen backed by Google Play Billing. You supply your product
catalogue, legal links and the places where it may appear. Firebase, ads and onboarding are optional.

## Install

Follow the [root build setup](../README.md), then add this to `app/build.gradle`:

```groovy
def sdkVersion = '5.1.2' // Use the same published tag for every SDK module.
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:paykit:$sdkVersion"
}
```

Requires minSdk 24. PayKit brings BillingKit at runtime and registers its Activity through the
merged manifest. Add `billingkit` explicitly only if your app also calls `Billing` or `AppPurchase`.

## 1. Create your catalogue

Create `app/src/main/res/raw/paywall_config.json`. Replace the product and base-plan IDs with
your Google Play catalogue; these example IDs do not create products in Play.

```json
{
  "config_version": 1,
  "packages": [
    { "id": "premium_monthly", "type": "subs", "base_plan_id": "monthly-base",
      "title": "Monthly", "preselected": true },
    { "id": "premium_lifetime", "type": "inapp", "title": "Lifetime" }
  ],
  "exit_button": { "enabled": true, "delay_ms": 0 },
  "restore": { "enabled": true }
}
```

Use `inapp` for a lifetime unlock. `consumable` products are consumed and do not grant premium.
Add `offer_id` to select a subscription offer; invalid coordinates can fall back to another
available offer, so verify the selected plan with your Play catalogue.

For localized labels, replace `title` with `title_key`, naming a string resource in your app.
See the [full JSON example](src/main/res/raw/pw_default_config.json) for copy, colors and
Continue with ads. At least one valid package is required.

## 2. Install in your Application

```kotlin
import android.app.Application
import android.util.Log
import io.paykit.PayKit
import io.paykit.PaywallPlacement
import io.paykit.payKitConfig

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        payKitConfig {
            termsUrl = "https://example.com/terms"     // Your real legal pages.
            privacyUrl = "https://example.com/privacy"
            defaultPlacements = setOf(PaywallPlacement.SETTING)
            fallbackConfigRes = R.raw.paywall_config
        }.onSuccess { config ->
            PayKit.install(this, config)
        }.onFailure { error ->
            Log.e("PayKit", "Invalid configuration", error)
        }
    }
}
```

Register the Application with `android:name` in your manifest, or add this to your existing
Application. Both legal URLs must be nonblank `http(s)` URLs with a host.

`PayKit.install` initializes BillingKit with this catalogue; do not initialize a second
catalogue with `AppPurchase.initBilling`. No `sync()` call is needed for this local setup.

`defaultPlacements` is empty by default, which disables the paywall. Set it in code for local
configuration: a `placements` array in bundled JSON does not enable slots. A nonempty array
from remote config or its cache overrides this set.

## 3. Open from your Activity

```kotlin
import android.util.Log
import io.paykit.PayKit
import io.paykit.PaywallListener
import io.paykit.PaywallPlacement
import io.paykit.PaywallResult

PayKit.launch(this, PaywallPlacement.SETTING, object : PaywallListener() {
    override fun onFinished(placement: PaywallPlacement, result: PaywallResult) {
        Log.d("PayKit", "Finished: $result") // Update your UI or continue navigation here.
    }
})
```

Use the same enabled placement as your configuration. The result is `Purchased`,
`ContinueWithAds`, `Dismissed` or `Error`. Already-premium users, disabled placements and
duplicate launch attempts return a dismissal to this listener. For current entitlement, read
`PayKit.isPremium()`; `PayKit.isReady()` describes the config, not Play Billing readiness.

## Optional: Firebase Remote Config

Add [suite-firebase](../suite-firebase/README.md) and complete its Firebase setup. Publish a
String parameter named `paywall_config` containing your JSON. Include, for example,
`"placements": ["setting", "after_onboarding"]` to control allowed locations remotely.

```kotlin
import io.suite.firebase.FirebaseConfigSource

// After PayKit.install, in Application.onCreate:
PayKit.configSource(FirebaseConfigSource())
```

Before the first paywall, call `PayKit.sync()` in a coroutine; its default timeout is 5 seconds.
A failed fetch keeps the current configuration. Startup uses a valid cached document before
your bundled catalogue. If your app fetches JSON itself, call `PayKit.applySnapshot(json)`
after install instead of adding a Firebase source.

## Optional: OnboardKit, ads and analytics

With [OnboardKit](../onboardkitorigin/README.md), install PayKit first and add this inside
your existing `OnboardingSdk.install(this) { ... }` block:

```kotlin
paywallGate = io.paykit.integration.OnboardKitPaywallGate()
```

Enable `AFTER_ONBOARDING` in PayKit to use that checkpoint. Add `onboardkitorigin` explicitly
before referencing this gate; PayKit alone does not include it.

With ads, BillingKit connects premium state to the ads gate. See
[BillingKit's optional integrations](../billingkit/README.md) for releasing preloaded ads and
controlling simulated purchases. For analytics, [install Tracker and a sink](../trackkit/README.md)
before PayKit.

## Troubleshooting

| Problem | Check |
|---|---|
| Paywall does not open | Successful install, enabled placement and `PayKit.isPremium()`. |
| Prices are empty or purchase fails | Your own product IDs, available Play products/offers and billing readiness. |
| Wrong catalogue after changing local JSON | A previously fetched document is cached and takes priority. |
| Configuration is rejected | Legal URLs and at least one valid `packages` row; read the `PayKit` log. |

For more UI options, see [PayKitConfig](src/main/java/io/paykit/PayKitConfig.kt).
Existing PayKit initialization can stay the same when upgrading from 5.0.0.
