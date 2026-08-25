# PayKit

> A prebuilt Play-billing paywall screen driven by a remote JSON document.

PayKit ships the purchase screen, the placement gate that decides where it may appear, and the IAP
analytics funnel. You supply product ids, two legal URLs, and the moment you want it shown. The
Play `BillingClient` itself lives in `:billingkit`; PayKit registers your catalogue with it for you.

## Requirements

| | |
|---|---|
| minSdk | 24 |
| compileSdk | 36 |
| JDK | 17 |
| Namespace | `io.paykit` |
| Resource prefix | `pw_` — every resource this module merges into your app is named `pw_*` |
| Required module | `:billingkit` (PayKit depends on it at `implementation` scope) |
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

Keep every module on the same tag. `:paykit` exposes `:trackkit`, `kotlinx-coroutines-android` and
`kotlinx-serialization-json` as `api` dependencies — do not declare them yourself. Events only reach
a dashboard once `Tracker.install()` and a sink are wired: [`../trackkit/README.md`](../trackkit/README.md).

## Quick start

**1. `Application.onCreate()` — build the config, install, name a source.**

```kotlin
payKitConfig {
    termsUrl = "https://example.com/terms"
    privacyUrl = "https://example.com/privacy"
    defaultPlacements = setOf(PaywallPlacement.AFTER_ONBOARDING, PaywallPlacement.SETTING)
    exitButtonDelayMs = 3_000
    logLevel = if (BuildConfig.DEBUG) PayKitLogLevel.DEBUG else PayKitLogLevel.WARN
    fallbackConfigRes = R.raw.paywall_config
}.onSuccess { config ->
    PayKit.install(this, config)
    PayKit.configSource(FirebaseConfigSource())   // optional, from :suite-firebase
}.onFailure {
    Log.e(TAG, "PayKit config rejected — the paywall stays off", it)
}
```

`payKitConfig { }` returns `Result<PayKitConfig>`; on failure it carries a `PayKitConfigException`
listing every problem at once. `install` is idempotent, synchronous and offline; a second call is
ignored with a warning.

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

## Configuration

`payKitConfig { }` builds a `PayKitConfig`. Every field is a `var` on `PayKitConfigBuilder`.

| Field | Type | Default | What it does |
|---|---|---|---|
| `termsUrl` | `String` | `""` | Terms link in the paywall footer. Must be a non-blank `http(s)` URL with a host, or the build fails validation |
| `privacyUrl` | `String` | `""` | Privacy link, same rule |
| `defaultPlacements` | `Set<PaywallPlacement>` | `emptySet()` | Placements allowed until a fetched document names its own. Empty shows no paywall anywhere |
| `exitButtonDelayMs` | `Long` | `0` | Delay before the close button appears. Must be `>= 0`. Any `exit_button.delay_ms` in the active document overrides it — including the bundled one, which sets `3000` while `fallbackConfigRes` is unset |
| `singleClickWindowMs` | `Long` | `700` | Tap window that collapses two launches into one. Must be `> 0` |
| `logLevel` | `PayKitLogLevel` | `WARN` | `NONE`, `ERROR`, `WARN`, `INFO`, `DEBUG`. Logcat tag `PayKit` |
| `fallbackConfigRes` | `@RawRes Int` | `0` | Your own catalogue JSON in `res/raw`, used before any fetch lands. Left at `0`, PayKit runs on its own sample ids and every price is blank |

Runtime entry points:

| Call | Returns | Notes |
|---|---|---|
| `PayKit.install(app, config)` | — | Call from `Application.onCreate`. Registers the catalogue with `:billingkit` — do not also call `AppPurchase.initBilling` yourself |
| `PayKit.configSource(source)` | — | Any `PaywallConfigSource`. `StaticConfigSource` and `RawResourceConfigSource` ship in this module |
| `PayKit.sync(timeoutMs)` | `Boolean` | `suspend`. Default timeout `5_000` |
| `PayKit.applySnapshot(json)` | — | Adopt a document you fetched yourself |
| `PayKit.isEnabled(placement)` | `Boolean` | The placement gate |
| `PayKit.isReady()` | `Boolean` | A usable **config document** is loaded, not that billing answered |
| `PayKit.isPremium()` | `Boolean` | The entitlement from `:billingkit` |
| `PayKit.state` | `StateFlow<PayKitState>` | `Idle`, `Syncing`, `Ready(configVersion, packageCount)`, `Error(message)` |
| `PayKit.addListener` / `removeListener` | — | Global `PaywallListener` |
| `PayKit.renderer(renderer)` | — | Replace `DefaultPaywallRenderer` with your own `PaywallRenderer` |

## Paywall document schema

Top level:

| Key | Type | Default | What it does |
|---|---|---|---|
| `config_version` | Int | `0` | Reported in `PayKitState.Ready` |
| `placements` | array of String | `[]` | `splash`, `after_onboarding`, `home`, `setting`, `feature_lock`, `other`. An unknown key is dropped |
| `packages` | array | `[]` | At least one usable row, or the whole document is rejected |
| `copy` | object | absent | `headline`, `headline_key`, `benefits`, `benefit_keys`, `cta`, `cta_key`. A literal wins over its `*_key`; the key is looked up as a string resource name in your app and stays localised, and a name that resolves to nothing falls back to PayKit's bundled default |
| `tokens` | object | `{}` | `text_primary`, `text_secondary`, `accent`, `background`, `surface`, `on_accent` as `#RRGGBB` or `#AARRGGBB`; `cta_gradient` as an array of two or more such strings. Any other key, or a bad value, is ignored per key |
| `exit_button` | object | `enabled: true` | `enabled` (Boolean), `delay_ms` (Long). Omitting `delay_ms` leaves `exitButtonDelayMs` in charge |
| `continue_with_ads` | object | block absent → `false` | `enabled` (Boolean) |
| `restore` | object | block absent → `false` | `enabled` (Boolean) |

One package:

| Key | Type | Default | What it does |
|---|---|---|---|
| `id` | String | — | Play product id. Blank or duplicate drops the row |
| `type` | String | — | `subs`, `inapp` or `consumable`. Any other value drops the row |
| `base_plan_id` | String | `null` | Subscriptions |
| `offer_id` | String | `null` | Subscriptions; omit to price the base plan |
| `title` / `title_key` | String | `null` | Row title. With neither set, or a `title_key` that resolves to nothing, PayKit's own default label is used — never the raw product id |
| `subtitle` / `subtitle_key` | String | `null` | Row subtitle. Unset or unresolved leaves the row with no subtitle |
| `badge` | String | `null` | Badge text |
| `discount_percent` | Int | `0` | `0..99`; out of range is recorded and reset to `0` |
| `preselected` | Boolean | `false` | First claimant wins; with none set, the first row is selected |

Unknown keys are ignored; a broken row is dropped on its own so the rest of the document survives.
The reason is recorded on the config and only surfaces through `PayKit.state` when every row was
dropped and the document is rejected. `paykit/src/main/res/raw/pw_default_config.json` is a complete
working example.

## Usage

### Showing a paywall

```kotlin
PayKit.launch(this, PaywallPlacement.HOME, object : PaywallListener() {
    override fun onFinished(placement: PaywallPlacement, result: PaywallResult) {
        if (result is PaywallResult.Purchased) refreshUi()
    }
})
```

`launch` refuses — and reports `onDismissed` + `onFinished(Dismissed)` to that listener — when
PayKit is not installed, the user is already premium, the placement is not enabled, or a second call
lands inside `singleClickWindowMs`. Global listeners are not told, because nothing was shown.

| Callback on `PaywallListener` | Fires |
|---|---|
| `onShown(placement)` | Screen created |
| `onPurchased(placement, productId)` | Play confirmed a purchase started here, or a restore found one |
| `onContinueWithAds(placement)` | The continue-with-ads row was tapped |
| `onDismissed(placement)` | Close, back, or the screen went away |
| `onError(placement, code, message)` | The paywall could not run |
| `onFinished(placement, result)` | Exactly once per presentation, on every exit path, after the specific callback |

`PaywallResult` is `Purchased(productId)`, `ContinueWithAds`, `Dismissed` or `Error(code, message)`.
Error codes: `1` PayKit not installed, `2` no usable configuration, `3` billing not ready within 5 s.

For a result instead of a listener, use `PaywallContract`. It builds the intent directly, so the
premium and placement checks are yours to make:

```kotlin
private val paywall = registerForActivityResult(PaywallContract()) { result -> … }

if (!PayKit.isPremium() && PayKit.isEnabled(PaywallPlacement.FEATURE_LOCK)) {
    paywall.launch(PaywallPlacement.FEATURE_LOCK)
}
```

### Integrating with OnboardKit

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

| Key | Type | Default | What it does |
|---|---|---|---|
| `paywall_config` | String (JSON) | none | The whole paywall document. Pass another name to `FirebaseConfigSource(key = "…")` |

Only a value published on the console counts; an in-app default is rejected. A missing or empty
value leaves PayKit on its cache, then on `fallbackConfigRes`.

## Troubleshooting

Set `logLevel = PayKitLogLevel.DEBUG` and read logcat tag `PayKit`. PayKit logs install, config
resolution and every `launch` refusal under that tag; the paywall screen itself reports through
`PayKit.state` and the `PaywallResult` error code instead.

| Symptom | Cause | Fix |
|---|---|---|
| Paywall never shows, `isEnabled` is false | `defaultPlacements` is empty and no fetched document names the placement — a bundled one never does | Name the placement in `payKitConfig`, or publish it in `placements` |
| Paywall opens then closes, code `2` | Every rung of the config chain was rejected | Read `PayKit.state`; `Error(message)` names the rung and the reason |
| Paywall opens then closes, code `3` | Billing was not ready in 5 s | Play Store signed in, build on a test track, and nothing called `AppPurchase.initBilling` after PayKit |
| Prices blank, log says "running on PayKit's own sample catalogue" | `fallbackConfigRes` was never set | Point it at your own `res/raw` document, or land a `sync()` |
| One row priced, the rest blank | Play returned no product details for those ids | Ids must match the console exactly, including `base_plan_id` and `offer_id` |
| Purchase succeeds, ads keep showing | The product is `consumable`, which is consumed and never sets the entitlement | Use `inapp` for a lifetime unlock |
| Restore or continue-with-ads row missing | The block is absent, which means `false` | Add `"restore": { "enabled": true }` / `"continue_with_ads": { "enabled": true }` |
| Copy is English on a translated device | `*_key` resolved against your default `values/` | Ship the same string name in `values-<lang>/` |

## License

MIT — see [LICENSE](../LICENSE).
