# PayKit

> A prebuilt Play-billing paywall screen driven by a remote JSON document.

PayKit ships the purchase screen, the placement gate that decides where it may appear, and the IAP
analytics funnel. You supply product ids, two legal URLs, and the moment you want it shown. The Play
`BillingClient` itself lives in `:billingkit`; PayKit registers your catalogue with it for you.

## Requirements

| | |
|---|---|
| minSdk / compileSdk / JDK | 24 / 36 / 17 |
| Namespace, resource prefix | `io.paykit`, `pw_` |
| Required module | `:billingkit` |
| Optional modules | `:suite-firebase` (remote document), `:onboardkitorigin` (gate), `:ads` |

`PaywallActivity` is declared `android:exported="false"` in the library manifest and merges
automatically; that manifest declares no permissions.

## Installation

Add `maven { url 'https://jitpack.io' }` to your repositories, then:

```groovy
// Replace <tag> with a tag from https://github.com/truongvimit/adlogic-partner-sdk/tags
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:paykit:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"

    // Only if the paywall document comes from Firebase Remote Config.
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

`:paykit` exposes `:trackkit`, `kotlinx-coroutines-android` and `kotlinx-serialization-json` as `api`
dependencies — do not declare them yourself. Events only reach a dashboard once `Tracker.install()`
and a sink are wired: [`../trackkit/README.md`](../trackkit/README.md).

## Integration

**1. `Application.onCreate()` — build the config, install, name a source.**

```kotlin
payKitConfig {
    termsUrl = "https://example.com/terms"
    privacyUrl = "https://example.com/privacy"
    defaultPlacements = setOf(PaywallPlacement.AFTER_ONBOARDING, PaywallPlacement.SETTING)
    fallbackConfigRes = R.raw.paywall_config
    logLevel = if (BuildConfig.DEBUG) PayKitLogLevel.DEBUG else PayKitLogLevel.WARN
}.onSuccess { config ->
    PayKit.install(this, config)
    PayKit.configSource(FirebaseConfigSource())   // optional, from :suite-firebase
}.onFailure {
    Log.e(TAG, "PayKit config rejected — the paywall stays off", it)
}
```

`payKitConfig { }` returns `Result<PayKitConfig>`; on failure it carries a `PayKitConfigException`
listing every problem at once. `install` is idempotent, synchronous and offline.

Three fields decide whether anything shows at all, so they are worth stating plainly:

- `termsUrl` and `privacyUrl` must be non-blank `http(s)` URLs with a host, or the config is
  rejected.
- `defaultPlacements` is empty by default, which shows no paywall anywhere until a fetched document
  names its own placements.
- `fallbackConfigRes` points at your own catalogue JSON in `res/raw`. Left unset, PayKit runs on its
  own sample ids and every price is blank.

The remaining knobs (exit-button delay, the double-tap window, log level) are documented on
`PayKitConfigBuilder`.

**2. Splash — fetch once, inside a coroutine.**

```kotlin
lifecycleScope.launch { PayKit.sync(timeoutMs = 3_000) }
```

`sync` is the only call that fetches. It never throws and returns `false` on timeout, on error, or
when no `configSource` was installed; the snapshot already in place keeps working. Sync before the
first paywall — a document that changes the package list re-registers the catalogue with Play. If
you run your own remote config, skip `configSource` and `sync` and call `PayKit.applySnapshot(json)`.

**3. Launch.**

```kotlin
PayKit.launch(activity, PaywallPlacement.SETTING)
```

## Showing a paywall

```kotlin
PayKit.launch(this, PaywallPlacement.HOME, object : PaywallListener() {
    override fun onFinished(placement: PaywallPlacement, result: PaywallResult) {
        if (result is PaywallResult.Purchased) refreshUi()
    }
})
```

`onFinished` fires exactly once per presentation on every exit path, after the specific callback
(`onShown`, `onPurchased`, `onContinueWithAds`, `onDismissed`, `onError`). `PaywallResult` is
`Purchased(productId)`, `ContinueWithAds`, `Dismissed` or `Error(code, message)`.

`launch` refuses — and reports `onDismissed` + `onFinished(Dismissed)` to that listener — when PayKit
is not installed, the user is already premium, the placement is not enabled, or a second call lands
inside the double-tap window. Global listeners are not told, because nothing was shown.

For a result instead of a listener, use `PaywallContract`. It builds the intent directly, so the
premium and placement checks are yours to make:

```kotlin
private val paywall = registerForActivityResult(PaywallContract()) { result -> … }

if (!PayKit.isPremium() && PayKit.isEnabled(PaywallPlacement.FEATURE_LOCK)) {
    paywall.launch(PaywallPlacement.FEATURE_LOCK)
}
```

Also on `PayKit`: `isReady()` (a usable **config document** is loaded, not that billing answered),
`state` (`Idle`, `Syncing`, `Ready`, `Error`), `addListener` / `removeListener` for a global
`PaywallListener`, and `renderer(...)` to replace the default screen with your own
`PaywallRenderer`.

## The paywall document

One JSON document drives the whole screen — which plans to sell, the copy, the colours, which
placements are allowed. `paykit/src/main/res/raw/pw_default_config.json` is a complete working
example; copy it into your own `res/raw` as the starting point for `fallbackConfigRes`.

```json
{
  "config_version": 1,
  "placements": ["after_onboarding", "setting"],
  "packages": [
    { "id": "sub.yearly", "type": "subs", "base_plan_id": "yearly", "offer_id": "freetrial",
      "title_key": "pw_plan_yearly", "discount_percent": 40, "preselected": true },
    { "id": "iap.lifetime", "type": "inapp", "title_key": "pw_plan_lifetime" }
  ],
  "restore": { "enabled": true },
  "continue_with_ads": { "enabled": true }
}
```

- `packages` needs at least one usable row or the whole document is rejected. `id` must match the
  Play console exactly, `type` is `subs`, `inapp` or `consumable`, and subscriptions price the base
  plan unless an `offer_id` names an offer.
- A broken row is dropped on its own so the rest of the document survives; unknown keys are ignored.
- Any `*_key` field is looked up as a string resource name in **your** app, so the copy stays
  localised; a literal field wins over its `*_key`, and a name that resolves to nothing falls back to
  PayKit's bundled default.
- The optional `copy`, `tokens`, `exit_button`, `continue_with_ads` and `restore` blocks are
  documented on the parser alongside the sample file above. A block that is absent is off.

Use `inapp`, not `consumable`, for a lifetime unlock — a consumable is consumed and never sets the
entitlement.

## Integrating with OnboardKit

`OnboardKitPaywallGate` implements OnboardKit's `PaywallGate` SPI. Install PayKit **before**
OnboardKit — the gate answers from PayKit's state at the first checkpoint.

```kotlin
OnboardingSdk.install(this) {
    paywallGate = OnboardKitPaywallGate()
}
```

OnboardKit's `SPLASH_INTER` maps to `PaywallPlacement.SPLASH`, `AFTER_ONBOARDING` to
`AFTER_ONBOARDING`, and `AFTER_QUESTION_OLD_USER` to `OTHER`. `PaywallResult.Error` arrives as
`PaywallOutcome.Dismissed`, so a failed paywall never strands the user mid-flow.

`:paykit` depends on `:onboardkitorigin` at `compileOnly` scope. Reference this class only when
`:onboardkitorigin` is on your runtime classpath.

## Remote config key

Only with `:suite-firebase`. Create the parameter in Firebase Console → Remote Config.

| Key | Type | What it holds |
|---|---|---|
| `paywall_config` | String (JSON) | The whole paywall document. Pass another name to `FirebaseConfigSource(key = "…")` |

Only a value published on the console counts; an in-app default is rejected. A missing or empty
value leaves PayKit on its cache, then on `fallbackConfigRes`.

## Troubleshooting

Set `logLevel = PayKitLogLevel.DEBUG` and read logcat tag `PayKit`. PayKit logs install, config
resolution and every `launch` refusal under that tag; the paywall screen itself reports through
`PayKit.state` and the `PaywallResult` error code instead.

| Symptom | Cause | Fix |
|---|---|---|
| Paywall never shows, `isEnabled` is false | `defaultPlacements` is empty and no fetched document names the placement | Name the placement in `payKitConfig`, or publish it in `placements` |
| Paywall opens then closes, code `2` | Every rung of the config chain was rejected | Read `PayKit.state`; `Error(message)` names the rung and the reason |
| Paywall opens then closes, code `3` | Billing was not ready in time | Play Store signed in, build on a test track, and nothing called `AppPurchase.initBilling` after PayKit |
| Prices blank, log says "running on PayKit's own sample catalogue" | `fallbackConfigRes` was never set | Point it at your own `res/raw` document, or land a `sync()` |
| One row priced, the rest blank | Play returned no product details for those ids | Ids must match the console exactly, including `base_plan_id` and `offer_id` |
| Purchase succeeds, ads keep showing | The product is `consumable` | Use `inapp` for a lifetime unlock |
| Restore or continue-with-ads row missing | The block is absent, which means `false` | Add `"restore": { "enabled": true }` |
| Copy is English on a translated device | `*_key` resolved against your default `values/` | Ship the same string name in `values-<lang>/` |

## License

MIT — see [LICENSE](../LICENSE).
