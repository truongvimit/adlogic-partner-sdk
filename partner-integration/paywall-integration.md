# PayKit — ready-made paywall

[← Choose a guide](README.md) · [PayKit API](../paykit/README.md)

Your app supplies the catalog, the copy, the two terms/privacy URLs and where the paywall opens; PayKit handles the UI, Play prices, purchase/restore and premium. **Do not also call `AppPurchase.initBilling`**: PayKit already initializes BillingKit.

Only two resources are new, in step 2; everything else merges into your existing Gradle file, Application and paywall entry point.

## 1. Add the dependency

Complete the [build setup](../README.md#build-setup) and share the same `adlogicSdkVersion`. JDK 17, `minSdk 24+`, `compileSdk 36+`; without ads you do not need the mediation repositories.

```groovy
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:paykit:$sdkVersion"
    // The sample calls BillingKit.setDevMode, so declare the billing API directly.
    implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"
}
```

The `billingkit` dependency lets you switch to the real Play mode and read `Billing`; Trackkit is already available. No BillingClient, paywall Activity or layout of your own is needed.

## 2. Copy the catalog and resources

| Sample file | Copy to | What your app replaces |
| --- | --- | --- |
| [paywall_config.json](examples/paywall/paywall_config.json) | `app/src/main/res/raw/paywall_config.json` | Product IDs, base plan/offer, copy and the configuration of each package. |
| [paywall_strings.xml](examples/paywall/paywall_strings.xml) | `app/src/main/res/values/paywall_strings.xml` | Copy/benefits that match your product and the two real URLs; add translations under `values-<language>`. |

The JSON carries the full structure of the [example](../app/src/main/res/raw/paywall_config.json); keep badge/discount empty/0 until your app has a real offer. Replace the IDs/plans/offers with the products you created in Play Console; **there is no shared test product ID**.

Your app's `pw_*` resources override the SDK content; merge them into your existing resources when the names collide. Replace the two `example.com` URLs with your app's real pages.

One local JSON is enough. For a separate debug catalog, put the same file name at `app/src/debug/res/raw/paywall_config.json`; PayKit does not look for a `*_debug` file.

## 3. Install in your Application

Merge this into your existing Application: after Tracker if your app collects analytics, before `OnboardingSdk.install` if you use the paywall inside OB. Replace the package and your app's `R` import.

```kotlin
package com.example.app

import android.app.Application
import com.ads.module.billing.BillingKit
import io.paykit.PayKit
import io.paykit.PaywallPlacement
import io.paykit.payKitConfig

class PaywallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BillingKit.setDevMode(false)
        val config = payKitConfig {
            termsUrl = getString(R.string.paywall_terms_url)
            privacyUrl = getString(R.string.paywall_privacy_url)
            defaultPlacements = setOf(PaywallPlacement.SETTING)
            fallbackConfigRes = R.raw.paywall_config
        }.getOrThrow()
        PayKit.install(this, config)
    }
}
```

Register the Application with `android:name` if your app does not have one yet; the paywall Activity and theme are merged in from the SDK. Both URLs must be HTTP(S) with a host; otherwise `getOrThrow()` fails immediately.

The sample allows opening from Settings. **You must set `defaultPlacements`** because the SDK default is empty; `placements` in local JSON does not enable a slot. `isReady()` confirms the configuration only, not prices or entitlement from Play.

## 4. Open the paywall from a user action

Call it from your Premium button in an Activity; update the UI or navigation in `onFinished`.

```kotlin
package com.example.app

import android.app.Activity
import io.paykit.PayKit
import io.paykit.PaywallListener
import io.paykit.PaywallPlacement
import io.paykit.PaywallResult

fun Activity.openPremium(onFinished: (PaywallResult) -> Unit) {
    PayKit.launch(this, PaywallPlacement.SETTING, object : PaywallListener() {
        override fun onFinished(placement: PaywallPlacement, result: PaywallResult) {
            onFinished(result)
        }
    })
}
```

Results: `Purchased(productId)`, `ContinueWithAds`, `Dismissed`, `Error(code, message)`. A disabled placement, an already-premium user or a repeated click returns `Dismissed`; do not read every dismissal as the user closing the screen. Read entitlement from `PayKit.isPremium()` or observe [Billing.isPremium](billing-integration.md#3-observe-results-and-premium-state).

`ContinueWithAds` only closes the paywall; it shows no ad. Consumables need your app to grant the item itself; `Purchased` does not always mean premium. The SDK already handles the purchase and Restore buttons.

## 5. JSON and optional configuration tables

### JSON fields

| Field | Sample / how to use |
| --- | --- |
| `config_version` | JSON metadata; keep it as in the sample file. |
| `packages[].id`, `type` | Your real IDs; `subs`, `inapp`, `consumable`. At least one valid package is required. |
| `base_plan_id`, `offer_id` | Subscription coordinates. Omit `offer_id` so the SDK picks an offer inside the plan rather than forcing the base plan with no offer. When they do not match, the resolver may fall back. |
| `title_key`, `subtitle_key` | Names of app/SDK string resources. Literal `title`, `subtitle`, when present, win over the keys and are not localized. |
| `badge`, `discount_percent` | Empty/0 in the sample; a valid discount is 0–99. They only render a label and a comparison price; they do not change the Play price. |
| `preselected` | Selects the initial package; when several packages are true, the SDK keeps the first one. With no true value, the first package is selected. |
| `copy` | `headline_key`, `benefit_keys`, `cta_key`; literal `headline`, `benefits`, `cta` are supported when copy comes from remote. |
| `tokens` | Every color as in the example: `text_primary`, `text_secondary`, `accent`, `background`, `surface`, `on_accent`, `cta_gradient`. Change them only when you need a different look. |
| `exit_button` | Enabled in the sample, delay 3000 ms; it stays enabled when the object is missing. A JSON `delay_ms` overrides the local value, including 0; omit the delay to use the local one. |
| `continue_with_ads`, `restore` | The sample enables both. If your app has no ads, disable `continue_with_ads`; this button loads no ad. When both objects are omitted, the parser defaults these two features to off. |
| `placements` | Only JSON from remote/cache or `applySnapshot` with a nonempty list overrides the local set. An empty array falls back to `defaultPlacements`; it is not a kill switch. |

### Kotlin configuration

| Option | SDK default / when to change it |
| --- | --- |
| `defaultPlacements` | Empty; the sample uses `SETTING`. Add more from the enum below. |
| `fallbackConfigRes` | 0 uses the SDK demo catalog; the sample supplies your app's resource so it works without remote. |
| `termsUrl`, `privacyUrl` | Required; your app's HTTP(S) URLs. |
| `exitButtonDelayMs` | 0; used only when the JSON has no delay. The sample JSON already sets 3000. |
| `singleClickWindowMs` | 700 ms; no debounce of your own is needed around launch. |
| `logLevel` | `WARN`; use `PayKitLogLevel.DEBUG` when you need to inspect parsing/configuration. |
| `PayKit.sync(timeoutMs)` | 5 seconds; call it only when a remote source is installed, a failure keeps the current configuration. |
| `BillingKit.setDevMode` | The sample sets `false` to test against Play; `true` only simulates locally and does not check real products/offers. |
| Custom UI | `PayKit.renderer(...)` when you need your own renderer; not needed for the standard flow. |

`PaywallPlacement`: `SPLASH`, `AFTER_ONBOARDING`, `HOME`, `SETTING`, `FEATURE_LOCK`, `OTHER`; the matching JSON keys are lowercase. Use the enum at the call site; use `OTHER` for your app's other screens.

**Catalog limit:** the PayKit UI reads the renewal price and trial per product, while the purchase selects by base plan/offer; with several plans/offers, what is displayed can differ from the offer that is bought. The sample uses one base plan per product; check that the price, billing period and trial match Play before you release.

## 6. Connect with the other SDKs

- **Firebase:** install the source, then call `PayKit.sync()` as in the [Firebase guide](firebase-integration.md#4-remote-paywall-json). A local-only setup needs no sync; a valid remote cache wins over the bundled JSON.
- **Ads/premium:** add the [billing wait hook](billing-integration.md#when-your-app-has-adsonboarding) in the splash. PayKit already connects premium to the ad gate.
- **Analytics/Adjust:** install Tracker/sinks before PayKit; the SDK already emits the purchase/paywall events, do not send revenue again in `onFinished`.

**Paywall inside OB:** declare `onboardkitorigin`, install PayKit before OnboardKit; add `paywallGate = io.paykit.integration.OnboardKitPaywallGate()` to the `OnboardingSdk.install` block. Enable the slots you need in `defaultPlacements`:

| OB checkpoint | PayKit `PaywallPlacement` |
| --- | --- |
| Before the splash inter | `SPLASH` |
| After onboarding or the new-user survey | `AFTER_ONBOARDING` |
| After the returning-user survey | `OTHER` |

The SDK opens it at the checkpoint; do not launch it again from the OB-finished listener. `OTHER` is shared between the returning-user checkpoint and your app's own `OTHER` call site.

## 7. Checks

- [ ] `PayKit.isReady()` is true, it opens from the enabled enum value; the URLs/copy belong to your app.
- [ ] Price, billing period and offer/trial match Play; the sample product IDs have been replaced. Follow [how to prepare a license tester](billing-integration.md#1-prepare-and-add-the-dependency).
- [ ] Test purchase, cancel, error and restore; an already-premium user does not get the paywall again. Continue with ads only returns the callback.
- [ ] Test without Firebase/offline and with broken JSON; do not mistake a remote cache for your new resource.
- [ ] Going from OB to the paywall opens it only once; wait for billing in the splash, and on timeout continue with the stored premium state.

Parse errors: read the `PayKit` log. Empty prices: check the catalog and the Play account.
