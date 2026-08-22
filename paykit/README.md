# PayKit

> Architecture: **[../trackkit/ARCHITECTURE.md](../trackkit/ARCHITECTURE.md)** · Analytics:
> **[../trackkit/README.md](../trackkit/README.md)**

The paywall as a library: a themed purchase screen, a remote-config document that drives it, the
placement gate that decides where it may appear, and the whole analytics funnel. You supply product
ids, two legal URLs, and the moment you want it shown.

- Namespace `io.paykit` · resource prefix `pw_` · entry point `PayKit`
- Billing is **not** in this module. `:billingkit` owns the Play `BillingClient`; PayKit reaches it
  through one internal bridge file. `:ads` is not required at all — a paywall-without-ads APK
  ships no GMA class.
- Remote config goes through the vendor-free `PaywallConfigSource`. `:paykit-firebase` is the only
  adapter this repo ships; a host with its own remote config implements the interface, or hands the
  JSON over with `PayKit.applySnapshot`.
- Analytics go through `Tracker` from `:trackkit`. Wire one sink and the paywall funnel reports
  itself.

**Read [`../trackkit/README.md`](../trackkit/README.md) too.** Without `Tracker.install()` plus a
sink, every event this SDK emits is validated and then discarded.

---

## 1. Why the paywall and the billing engine are different modules

The `BillingClient`, the purchase verifier, the acknowledge/consume retries and the cached
entitlement all live in `:billingkit`, in `com.ads.module.billing`. They predate this module, and
`:ads` reads the entitlement through its `Entitlement` port to gate ad display — a purchased user
gets no ads because `AdGate` asks the port `:billingkit` plugs itself into. Moving billing into the
paywall would give two modules an opinion about who is premium.

So the split is:

| Layer | Module | Owns |
|---|---|---|
| Billing engine | `:billingkit` | Play connection, catalogue, purchase flow, acknowledge/consume, entitlement, `iap_success` |
| Paywall | `:paykit` | Config document, placement gate, theme, the screen, the funnel |

`io.paykit.billing.BillingBridge` is the only file in this module that imports `com.ads.module`,
and every other file speaks `PaywallPackage` and `PriceView`. Swapping engines later is a rewrite of
that one file, which is the entire reason it exists. It carries one non-billing call for the same
reason — `excludeFromAppResume`, which keeps an app-resume ad from landing on top of a purchase
flow returning from Play.

The consequence for you: **PayKit initialises billing for you.** `PayKit.install` hands the
catalogue from the config document to `AppPurchase.initBilling` and calls `Billing.install`. Do not
also call `initBilling` yourself with a second list — `initBilling` tears down the live client, and
the last caller wins.

---

## 2. Gradle setup

```groovy
// settings.gradle (dependencyResolutionManagement) or root build.gradle
repositories {
    google()
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
```

```groovy
// Replace <tag> with a tag from https://github.com/truongvimit/adlogic-partner-sdk/tags
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:paykit:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"

    // Only if the app also runs ads — the paywall itself never needs it.
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"

    // Only if your paywall document comes from Firebase Remote Config.
    implementation "com.github.truongvimit.adlogic-partner-sdk:paykit-firebase:$sdkVersion"

    // Without a sink the funnel is validated and dropped.
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit-firebase:$sdkVersion"
}
```

Inside this repository the modules are wired as projects instead:

```groovy
implementation project(':paykit')
implementation project(':paykit-firebase')
```

Which one you need:

| You want | Declare |
|---|---|
| The paywall, config from the bundled JSON or your own `PaywallConfigSource` | `paykit` |
| The paywall, config from Firebase Remote Config | `paykit` + `paykit-firebase` |

`paykit-firebase` declares `api project(':paykit')`, so declaring both is belt and braces rather
than a requirement — but declare `paykit` explicitly anyway, so a later change of config vendor is
one line and not a resolution surprise.

`paykit` exposes `trackkit`, the coroutines artifact and `kotlinx-serialization-json` as `api`
dependencies; you declare none of them yourself. It depends on `ads` at `implementation` scope, so
`com.ads.module.*` is not on your compile classpath through it — declare `ads` explicitly, as above,
because you need it for the ad side anyway.

`:onboardkitorigin` is a **`compileOnly`** dependency. See §9.

Keep every module on the same tag — they are published together and are not tested across versions.
Requires JDK 17, `minSdk` 24. `PaywallActivity` is declared in the library manifest with
`exported="false"` and merges automatically; you do **not** add it to yours. The library manifest
declares no permissions: the paywall never opens a socket itself.

---

## 3. Quick start

### 3.1 `Application.onCreate()` — config, install, source

```kotlin
override fun onCreate() {
    super.onCreate()

    initTracking()        // Tracker.install + addSink — see ../trackkit/README.md
    initAds()
    initPayKit()
}

private fun initPayKit() {
    val config = payKitConfig {
        termsUrl = "https://example.com/terms"
        privacyUrl = "https://example.com/privacy"
        defaultPlacements = setOf(PaywallPlacement.AFTER_ONBOARDING, PaywallPlacement.SETTING)
        exitButtonDelayMs = 3_000
        logLevel = if (BuildConfig.DEBUG) PayKitLogLevel.DEBUG else PayKitLogLevel.WARN
    }.getOrElse {
        Log.e(TAG, "PayKit config rejected", it)
        return
    }

    PayKit.install(this, config)
    PayKit.configSource(FirebaseConfigSource())     // optional, from :paykit-firebase
}
```

`payKitConfig { }` returns a `Result` — **check it.** Validation is cumulative: every problem is
collected into one `IllegalArgumentException`, so you fix the config in one pass instead of one
build per mistake. `termsUrl` and `privacyUrl` must be non-blank `http(s)` URLs with a host; that is
a store-policy requirement, and shipping `""` gives you a paywall whose legal buttons open an empty
URI.

| Builder property | Default | Meaning |
|---|---|---|
| `termsUrl` | `""` | Opened by the Terms link. Must be `http(s)` with a host. |
| `privacyUrl` | `""` | Opened by the Privacy link. Same rule. |
| `defaultPlacements` | `emptySet()` | Placements allowed until a **fetched** document names its own. Empty = nothing shows. |
| `exitButtonDelayMs` | `0` | Fallback delay before the close button appears, when the document omits one. Must be `>= 0`. |
| `singleClickWindowMs` | `700` | Tap gate shared by every control of one presentation. Must be `> 0`. |
| `logLevel` | `WARN` | `NONE`…`DEBUG`, logcat tag `PayKit`. |
| `fallbackConfigRes` | `0` | A `@RawRes` JSON in **your** app that replaces PayKit's bundled default at the bottom of the chain. Effectively required for a partner build: left at `0`, the bottom rung is the SDK's sample catalogue, whose ids exist in no Play console (`install` logs a warning). |

`install()` is idempotent and synchronous. It reads the cached or bundled document from disk,
registers that catalogue with billing, and starts observing the entitlement. It fetches nothing —
no remote config call, no blocking Play call — but registering the catalogue does open the Play
billing connection, so it belongs on the main thread of `Application.onCreate` and nowhere later.
A second call logs a warning and returns.

### 3.2 Splash — sync

`sync()` is the only call that fetches. Do it once, from the splash, in a coroutine, alongside your
other remote fetches:

```kotlin
lifecycleScope.launch {
    PayKit.sync(timeoutMs = 5_000)      // false on timeout or error; the previous snapshot stays
}
```

It never throws. On failure the document already in place keeps working, so the first run after an
install falls back to the bundled JSON rather than showing nothing. With no `configSource`
installed it returns `false` immediately and is otherwise harmless.

Sync before the first paywall, not after. A document that changes the package list re-registers the
catalogue with Play, and that tears down and rebuilds the billing connection.

If you already run your own remote config and simply hold the JSON, skip `configSource` and `sync`
entirely:

```kotlin
PayKit.applySnapshot(myRemoteConfig.getString("paywall_config"))
```

### 3.3 Launching

```kotlin
PayKit.launch(this, PaywallPlacement.SETTING)

// or with a per-presentation listener
PayKit.launch(this, PaywallPlacement.HOME, object : PaywallListener() {
    override fun onFinished(placement: PaywallPlacement, result: PaywallResult) {
        if (result is PaywallResult.Purchased) refreshUi()
    }
})
```

`launch` **does not open the paywall** when PayKit is not installed, when the user is already
premium, when the placement is not enabled, or when a second call lands inside one tap window. It
logs which one, and reports `onDismissed` + `onFinished(Dismissed)` to the listener that call was
given — a refusal still ends the presentation, so nothing waits on a callback that never comes.
Global listeners are not told, because nothing was ever shown.

`PaywallListener` is an open class, not an interface, so a callback added in a later release cannot
break a Java host. `onFinished` fires **exactly once per presentation, on every exit path**,
immediately after the specific callback — including back, a swipe-away, and a finish this screen did
not start.

| Callback | Fires |
|---|---|
| `onShown(placement)` | screen created, once per presentation (not on a rotation or a process-death restore) |
| `onPurchased(placement, productId)` | Play confirmed a flow this screen started, or a restore found one. An entitlement that merely arrives from Play's startup sweep dismisses instead — it was not bought here |
| `onContinueWithAds(placement)` | the continue-with-ads row was tapped |
| `onDismissed(placement)` | close, back, or the screen went away on its own |
| `onError(placement, code, message)` | the paywall could not run (§3.5) |
| `onFinished(placement, result)` | after every one of the above, exactly once |

Register a listener for the whole app with `PayKit.addListener` / `PayKit.removeListener`. Global
listeners and the one-shot from `launch` both fire; the one-shot is bound to the presentation it
was passed to — it survives a configuration change and is dropped when that presentation ends, so
it never leaks into the next one.

### 3.4 As an activity result

If you would rather have a result than a listener:

```kotlin
private val paywall = registerForActivityResult(PaywallContract()) { result ->
    when (result) {
        is PaywallResult.Purchased -> refreshUi()
        PaywallResult.ContinueWithAds, PaywallResult.Dismissed -> Unit
        is PaywallResult.Error -> Log.w(TAG, "paywall failed: ${result.code}")
    }
}

paywall.launch(PaywallPlacement.FEATURE_LOCK)
```

`PaywallContract` bypasses the gate in `PayKit.launch` — it builds the intent directly. The screen
still refuses to run without an installed config, but the premium and placement checks are yours to
make with `PayKit.isPremium()` and `PayKit.isEnabled(placement)`.

### 3.5 State and error codes

```kotlin
PayKit.state          // StateFlow<PayKitState>: Idle | Syncing | Ready(version, count) | Error(msg)
PayKit.isReady()      // state is Ready
PayKit.isPremium()    // the entitlement from :billingkit
```

`Ready` means a usable **config document** is loaded, not that billing has answered. After
`install()` it is normally already `Ready`, seeded from cache or from the bundled JSON;
`Error(message)` carries why every rung of the chain was skipped.

`PaywallResult.Error.code` is one of:

| Code | Meaning |
|---|---|
| `1` | PayKit was not installed — the screen was revived by process death before `Application.onCreate` ran |
| `2` | No usable configuration, or it contained no package |
| `3` | Billing did not become ready within 5 s |

---

## 4. The remote-config document

One JSON document drives the whole screen. It is resolved in this order, and the first rung that
parses wins:

1. the installed `PaywallConfigSource` (only consulted by `sync()`),
2. the prefs cache — the last document that parsed, written on every successful `sync`,
3. the bundled `res/raw/pw_default_config.json`, or your `fallbackConfigRes` if you set one.

A rung that fails to parse is skipped with its reason recorded in `PayKitState.Error`, never thrown
at the host. A changed `config_version` clears the cache before the new document is written.

A complete worked example — every field below appears in it. It is the shipped default plus two
fields that default deliberately leaves out: `placements`, because a bundled document must not
decide where a partner's paywall appears, and `badge`, because it is the one field that is literal
text and cannot be translated:

```json
{
  "config_version": 1,
  "packages": [
    {
      "id": "sub.weekly",
      "type": "subs",
      "base_plan_id": "weekly",
      "title_key": "pw_plan_weekly",
      "subtitle_key": "pw_plan_weekly_sub",
      "discount_percent": 0,
      "preselected": false
    },
    {
      "id": "sub.monthly",
      "type": "subs",
      "base_plan_id": "monthly",
      "title_key": "pw_plan_monthly",
      "subtitle_key": "pw_plan_monthly_sub",
      "discount_percent": 25,
      "preselected": false
    },
    {
      "id": "sub.yearly",
      "type": "subs",
      "base_plan_id": "yearly",
      "offer_id": "freetrial",
      "title_key": "pw_plan_yearly",
      "subtitle_key": "pw_plan_yearly_sub",
      "badge": "BEST VALUE",
      "discount_percent": 40,
      "preselected": true
    },
    {
      "id": "iap.lifetime",
      "type": "inapp",
      "title_key": "pw_plan_lifetime",
      "subtitle_key": "pw_plan_lifetime_sub",
      "discount_percent": 0,
      "preselected": false
    }
  ],
  "copy": {
    "headline_key": "pw_headline",
    "benefit_keys": ["pw_benefit_1", "pw_benefit_2", "pw_benefit_3", "pw_benefit_4"],
    "cta_key": "pw_cta_continue"
  },
  "tokens": {
    "text_primary": "#111827",
    "text_secondary": "#6B7280",
    "accent": "#2152FA",
    "background": "#FFFFFF",
    "surface": "#F3F4F6",
    "on_accent": "#FFFFFF",
    "cta_gradient": ["#2152FA", "#839DFF"]
  },
  "exit_button": { "enabled": true, "delay_ms": 3000 },
  "continue_with_ads": { "enabled": true },
  "restore": { "enabled": true }
}
```

### 4.1 Top level

| Field | Type | Default | Notes |
|---|---|---|---|
| `config_version` | int | `0` | Cache stamp. Change it to drop the cached document on the next successful sync. |
| `placements` | string[] | `[]` | Where the paywall may appear. **Non-empty replaces** `defaultPlacements`, but only in a document that was fetched or cached from one — a bundled document is a catalogue, not a placement policy, so its list is ignored. An unknown key is ignored and recorded. |
| `packages` | object[] | `[]` | At least one usable row is required — a document with none is rejected outright and the chain drops one rung. |
| `copy` | object | — | Each line takes a literal or a resource key name. Literal wins — see §4.3. |
| `tokens` | object | `{}` | Colours, one hop. Allow-listed keys only. |
| `exit_button.enabled` | bool | `true` | `false` hides the close button. Back still works. |
| `exit_button.delay_ms` | long | absent | Close appears after this delay. Negative is coerced to `0`. An explicit `0` switches the delay off remotely; only **omitting** the field falls back to `exitButtonDelayMs` from `PayKitConfig`. |
| `continue_with_ads.enabled` | bool | **`false` when the block is absent** | Present-but-empty (`{}`) means `true`. |
| `restore.enabled` | bool | **`false` when the block is absent** | Present-but-empty (`{}`) means `true`. |

Unknown fields anywhere in the document are ignored, so adding a key for a future release cannot
break shipped clients.

### 4.2 A package

| Field | Type | Default | Notes |
|---|---|---|---|
| `id` | string | — | The Play product id. Blank or duplicate rows are dropped and recorded. |
| `type` | string | — | `subs` · `inapp` · `consumable`. Anything else drops the row. |
| `base_plan_id` | string | `null` | Subscriptions. Used with `offer_id` to resolve the offer token. |
| `offer_id` | string | `null` | Subscriptions. Omit for the base plan with no offer. |
| `title` | string | `null` | Literal row title. Wins over `title_key`; not localisable. |
| `title_key` | string | `null` | String resource name. Used when `title` is absent. Unknown or missing renders `pw_plan_default`. |
| `subtitle` | string | `null` | Literal row subtitle. Wins over `subtitle_key`; not localisable. |
| `subtitle_key` | string | `null` | String resource name. Unknown key renders no subtitle. A free trial takes this slot when Play reports one. |
| `badge` | string | `null` | Literal only — there is no `badge_key`. Keep it short, or leave it out and let `discount_percent` speak. |
| `discount_percent` | int | `0` | `1..99` shows a struck-through old price. Out of range is clamped to `0` and recorded. |
| `preselected` | bool | `false` | The first `true` wins; later ones are ignored and recorded. With none, the first row is selected. |

Broken rows drop **individually**. Throwing the whole document away over one bad package would hand
the user a paywall with nothing to buy.

### 4.3 Copy: literal wins, key stays localised

Every line of copy comes in two forms, and the resolution order is always the same:

```
literal  →  resource key  →  bundled default
```

| Literal | Key | Renders |
|---|---|---|
| `headline` | `headline_key` | the headline |
| `benefits` | `benefit_keys` | one benefit line per entry |
| `cta` | `cta_key` | the buy button |
| `title` | `title_key` | a package row's title |
| `subtitle` | `subtitle_key` | a package row's subtitle |

A **key** is a resource *name*, resolved with `resources.getIdentifier(key, "string", packageName)`
against your app's merged string table. Ship `pw_headline` in `values-vi/`, `values-hi/` and the
rest and the paywall speaks those languages with no config change. An unknown key falls back to the
bundled default — never a crash, never a blank.

A **literal** is the text itself, and it beats the key. That is how a copy experiment goes out
without an app update. The trade is real and worth stating out loud: a literal is one string, so
that experiment runs in one language for everybody. Use literals for short-lived tests, keys for
the copy you ship.

The bundled `pw_default_config.json` uses keys only, so the out-of-the-box paywall is fully
localised. `benefits`/`benefit_keys` both omitted renders the four bundled benefit lines.

### 4.4 Tokens are hex, one hop

A token *is* a colour. There is no design-system indirection to look through.

| Key | Applies to |
|---|---|
| `text_primary` | headline, package title, price, benefit text |
| `text_secondary` | subtitles, old price, CTA note, continue-with-ads, footer, close icon |
| `accent` | selection stroke, check icon, badge fill, restore, legal links, progress |
| `background` | screen, selected package fill |
| `surface` | header block, unselected package fill |
| `on_accent` | CTA label, badge label |
| `cta_gradient` | array of **two or more** hex stops, drawn left to right |

Format is `#RRGGBB` or `#AARRGGBB` — alpha **first**, not the CSS `#RRGGBBAA`. Resolution is per key
against the bundled palette in `res/values/pw_colors.xml`, so one unparsable string costs that one
colour instead of blanking the screen. A key that is not on the list above is ignored and recorded.
`cta_gradient` is all-or-nothing: fewer than two stops, or any stop that fails the check, falls back
to the bundled gradient.

---

## 5. Placements and the fail-closed gate

```kotlin
enum class PaywallPlacement(val key: String) {
    SPLASH("splash"),
    AFTER_ONBOARDING("after_onboarding"),
    HOME("home"),
    SETTING("setting"),
    FEATURE_LOCK("feature_lock"),
    OTHER("other"),
}
```

`key` is the wire value in the `placements` array and the `source` param on every event, so renaming
a constant never changes what the remote console has to send. `PaywallPlacement.fromKey` returns
`null` for anything unknown — a typo in remote config must not resolve to some placement.

`PayKit.isEnabled(placement)` answers from the document's list when that document was fetched (or
cached from a fetch) and names any, and from `defaultPlacements` otherwise — a bundled document
supplies the catalogue, never the placement policy, so shipping one can never widen where a paywall
appears. Both empty means **nothing shows**: a half-configured partner gets no paywall rather than a
broken one. Nothing anywhere in this module treats "no configuration" as "show it anyway".

---

## 6. Analytics

Nothing below needs a call site of yours; it needs a registered `TrackSink`. Every event carries the
Trackkit default params (`app_vc`, `sdk_ver`, `session_no`, `install_day`, `consent_ads`) on top of
the ones listed.

| Moment | Event | Params |
|---|---|---|
| Screen created (once per presentation) | `iap_paywall_view` | `source` = placement key |
| CTA tapped | `iap_click` | `source`, `product_id` |
| Billing never became ready, a purchase error, a restore error | `iap_fail` | `product_id` (null when not tied to one), `error_code` |
| Continue-with-ads tapped | `iap_dismiss` | `source`, `reason` = `continue_with_ads` |
| Close or back | `iap_dismiss` | `source`, `reason` = `close` |
| Every exit path | `iap_paywall_result` | `source`, `status` = `purchased` / `dismissed` / `continue_with_ads` / `error` |

Every `iap_paywall_view` gets exactly one `iap_paywall_result`, so paywall conversion is computable
from that pair alone.

**`iap_success` is not emitted here, on purpose.** `:billingkit` emits it from `handlePurchase` the
moment Play confirms, with the real price and currency read from the product details. Emitting it from the
paywall as well would count the same revenue twice, on two different clocks — and the paywall does
not know the price in micros anyway, only the formatted string.

---

## 7. Purchase behaviour

### Restore

Set `"restore": { "enabled": true }` and the screen shows a Restore button. It re-queries Play for
everything the account owns, then:

| Play says | The user sees | The paywall |
|---|---|---|
| products restored **and** the entitlement is now premium | "Your purchase has been restored." | exits as `Purchased` |
| products restored but nothing grants premium | the same message | stays open |
| nothing owned | "There is no purchase to restore." | stays open |
| an error | the generic message, plus `iap_fail` with Play's code | stays open |

The entitlement, not Play's product list, decides whether a restore counts as a purchase. Play can
return an owned product that grants nothing in this app.

### Continue with ads

`"continue_with_ads": { "enabled": true }` adds the row. Tapping it exits with
`PaywallResult.ContinueWithAds` and emits `iap_dismiss` with `reason = continue_with_ads`, so the
"chose ads" branch is separable from "closed the sheet" in the funnel. PayKit itself does nothing
else — what happens next is your `onContinueWithAds` / `onFinished`.

### Free trials

Trial information comes from Play, never from the config document: if the offer resolved by
`base_plan_id` + `offer_id` carries a free trial, the row's subtitle is replaced by a localised
"3 days free", pluralised by the host's locale rules through `pw_trial_days` / `_weeks` / `_months`
/ `_years`. The trial wins the subtitle slot over `subtitle_key` because it is the line that
converts.

Compound ISO-8601 periods (`P1M15D`) collapse to a day count, because each plural resource carries
exactly one unit. An unparseable period renders as no trial rather than as a broken label.

### Lifetime and consumable products

| `type` | Play type | Sets premium | Use for |
|---|---|---|---|
| `subs` | subscription | yes | weekly / monthly / yearly plans |
| `inapp` | non-consumable purchase | yes | lifetime unlock |
| `consumable` | consumable purchase | **no** | coin packs, one-off unlocks you track yourself |

A `consumable` is consumed by `:billingkit` as soon as Play confirms it, so it never flips the entitlement
and `AdGate` keeps showing ads. The paywall still exits as `Purchased` and still reports the funnel
— granting whatever the user bought is the host's job. `subs` and `inapp` flip the entitlement, and
from that moment `PayKit.launch` refuses to open the paywall at all.

### Old prices

`discount_percent` on a row shows a struck-through price. It is taken from Play's own product
details when Play can price the offer, and reconstructed from the formatted string only as a
fallback — that reconstruction has to guess which separator is the decimal one, so prefer letting
Play answer by pricing the offer correctly in the console.

---

## 8. Customising the screen

### Colours, dimensions, icons, copy

Every resource in this module carries the `pw_` prefix, so you override any of them by declaring the
same name in your app — resource merging does the rest, no PayKit API involved.

| Override | Where | Effect |
|---|---|---|
| `res/values/colors.xml` → `pw_accent`, `pw_background`, `pw_surface`, `pw_text_primary`, `pw_text_secondary`, `pw_on_accent`, `pw_cta_gradient_start`, `pw_cta_gradient_end`, `pw_stroke`, `pw_scrim` | your app | the fallback palette behind `tokens` |
| `res/values/dimens.xml` → the `pw_radius_*`, `pw_stroke_*`, `pw_text_*`, `pw_*_padding` family | your app | geometry and type scale |
| `res/drawable/` → `pw_ic_check`, `pw_ic_close`, `pw_bg_cta`, `pw_bg_package_selected`, `pw_bg_package_unselected` | your app | icons and shapes |
| `res/values*/strings.xml` → any `pw_*` string or plural | your app | copy, and every translation |

The strings worth knowing by name: `pw_headline`, `pw_benefit_1`…`pw_benefit_4`, `pw_cta_continue`,
`pw_continue_with_ads`, `pw_restore`, `pw_cancel_anytime`, `pw_terms`, `pw_privacy`,
`pw_footer_legal` (takes `%1$s` = terms, `%2$s` = privacy), `pw_error_generic`, `pw_restore_done`,
`pw_restore_none`, the `pw_plan_*` titles and `pw_plan_*_sub` subtitles, and the four `pw_trial_*`
plurals.

`pw_footer_legal` must keep both placeholders. A translation that drops one degrades to plain text
— the link simply is not tappable — rather than crashing.

The shipped layout has **no header artwork**: `pw_header_image` is painted with the `surface` token.
To put art there, install a renderer, or override `res/layout/pw_activity_paywall.xml` in your app
and keep every `pw_` id — the default renderer binds them all by id, and a missing one fails at
inflate time.

### The renderer escape hatch

The screen, the state machine and the drawing are three different things. `PaywallActivity` owns the
lifecycle, `PaywallViewModel` owns the state machine, and everything visual goes through one
interface:

```kotlin
interface PaywallRenderer {
    fun onCreate(root: ViewGroup, actions: PaywallActions)
    fun render(state: PaywallUiState)
    fun onDestroy() {}
}
```

`PaywallUiState` is `Loading` · `Ready(...)` · `Purchasing(productId)` · `Restoring` ·
`Error(code, message)`, and `Ready` carries only resolved values — formatted prices, resolved
strings, ARGB ints. A renderer never sees a resource id, a config value, billing or analytics. It
reports intent back through `PaywallActions`: `Select(packageId)`, `Continue`, `ContinueWithAds`,
`Restore`, `Close`, `Terms`, `Privacy`.

That is what makes a Compose host cheap — plant a `ComposeView` into `root`:

```kotlin
class ComposePaywallRenderer : PaywallRenderer {

    private var state by mutableStateOf<PaywallUiState>(PaywallUiState.Loading)

    override fun onCreate(root: ViewGroup, actions: PaywallActions) {
        root.addView(ComposeView(root.context).apply { setContent { Paywall(state, actions::on) } })
    }

    override fun render(state: PaywallUiState) {
        this.state = state
    }
}

PayKit.renderer(ComposePaywallRenderer())     // before the first launch
```

There is no Compose anywhere in this repository, and this interface is why it does not need any.

One instance serves every presentation, so reset your renderer's state in `onCreate` rather than in
a constructor. Unset, the SDK uses `DefaultPaywallRenderer`, the View implementation it ships.

---

## 9. OnboardKit integration

`:onboardkitorigin` already has a `PaywallGate` SPI: `shouldShow(placement)` and
`present(activity, placement)`, called at five checkpoints in the first-open flow. PayKit ships the
implementation:

```kotlin
OnboardingSdk.install(this) {
    adProvider = ERainAdProvider()
    paywallGate = OnboardKitPaywallGate()
    listener = OnboardingListener { ctx, outcome -> /* … */ }
}
```

That is the whole wiring. The gate suspends until the paywall reports `onFinished`, so the flow
never advances underneath a live paywall — and because `launch` reports its refusals through the
same callback, a placement it declines resolves as `Dismissed` instead of stalling the flow.

| OnboardKit placement | PayKit placement |
|---|---|
| `SPLASH_INTER` | `SPLASH` |
| `AFTER_ONBOARDING` | `AFTER_ONBOARDING` |
| `AFTER_QUESTION_OLD_USER` | `OTHER` — the returning-user checkpoint has no dedicated PayKit placement |

`PaywallResult.Error` maps to `PaywallOutcome.Dismissed`. OnboardKit has no error outcome, and a
failed paywall must not strand the user mid-flow.

### The `compileOnly` caveat

`:paykit` depends on `:onboardkitorigin` with **`compileOnly`**. `OnboardKitPaywallGate` compiles
against the SPI, but the onboarding module is not pulled into your APK by adding the paywall — a
host that ships a paywall and no onboarding would otherwise carry four activities it never opens.

The consequence: **`OnboardKitPaywallGate` needs `:onboardkitorigin` on the runtime classpath.** If
you do not ship onboarding, never reference that class — the reference resolves at compile time and
fails with `NoClassDefFoundError` at runtime. The consumer ProGuard rules keep the class only when
`io.onboardkit.paywall.PaywallGate` is actually present, so R8 does not fail on the missing
superinterface either way.

### Both layers report the view

When the paywall is presented **through the gate**, OnboardKit emits `iap_paywall_view` and
`iap_paywall_result` for the checkpoint, and `PaywallActivity` emits its own pair for the screen.
For `AFTER_ONBOARDING` both carry `source = after_onboarding`, so that presentation appears twice in
the funnel. Slice by `source` and divide, or drop the OnboardKit pair from your dashboard — do not
read the raw count as impressions.

---

## 10. Troubleshooting

Set `logLevel = PayKitLogLevel.DEBUG` and read logcat tag `PayKit`. Every refusal below logs.

| Symptom | Likely cause | Check |
|---|---|---|
| Paywall does not show | `launch` refuses when PayKit is not installed, the user is premium, the placement is off, or a second call landed inside one tap window | `PayKit.isReady()`, `PayKit.isPremium()`, `PayKit.isEnabled(placement)` — the log line names which one, and the listener gets `onFinished(Dismissed)` |
| Paywall does not show, and `isEnabled` is false | fail-closed: `defaultPlacements` is empty and no fetched document named the placement | Set `defaultPlacements` in `payKitConfig`, or list the placement in the remote document — a bundled one is not consulted for placements |
| Paywall opens, then closes immediately with code `2` | every rung of the config chain was rejected | `PayKit.state` — `Error(message)` names the rung and the reason |
| Paywall opens, then closes immediately with code `3` | billing did not become ready in 5 s | Is Play Store signed in? Is the build on an internal-test track? Did something else call `AppPurchase.initBilling` after PayKit? |
| Prices are blank, and the log says "running on PayKit's own sample catalogue" | `fallbackConfigRes` was never set, so the ids are the SDK's demo ones | Point `fallbackConfigRes` at your own `res/raw` document, or land a `sync()` |
| Prices are blank | Play returned no product details for that id | Product ids in the document must match the console exactly; the app must be published to a track; the tester account must be licensed |
| One row's price is blank, the rest are fine | it is a subscription and the offer token did not resolve | `base_plan_id` + `offer_id` must name a real base plan and offer on that product; drop `offer_id` to use the base plan |
| No struck-through old price | `discount_percent` is `0` or out of `1..99`, or Play could not price the offer | Fix the value in the document; check `PayKitState.Error` for a "out of range" note |
| Purchase succeeds but ads keep showing | the product is `consumable`, which never sets the entitlement | Use `inapp` for a lifetime unlock. `:ads` gates on `AppPurchase.isPurchased`, and consumables are consumed |
| Purchase succeeds but your own UI still says free | you cached a flag of your own | Read `PayKit.isPremium()` / `Billing.isPremium`, do not mirror it |
| Restore button missing | `restore` block absent means `false` | Add `"restore": { "enabled": true }` |
| Continue-with-ads missing | same rule for `continue_with_ads` | Add the block |
| Close button never appears | `exit_button.delay_ms`, or `enabled: false` | Back always dismisses regardless — that is deliberate |
| Copy is English on a translated device | the `*_key` names resolve to your app's default `values/` | Ship the same `pw_*` names in `values-<lang>/` |
| A colour from remote config is ignored | it failed the `#RRGGBB` / `#AARRGGBB` check, or it is not an allow-listed key | `PayKit.state` records "token 'x' ignored" and why |
| Nothing reaches the dashboard | no `TrackSink` registered | `Tracker.install()` **and** `Tracker.addSink(...)` — see `../trackkit/README.md` |

---

## 11. Integration checklist

- [ ] `Tracker.install()` **and** at least one `Tracker.addSink(...)` — otherwise no event arrives
- [ ] `payKitConfig { }` result checked, not discarded
- [ ] `termsUrl` and `privacyUrl` are real `http(s)` URLs
- [ ] `defaultPlacements` names every placement you actually launch, or remote config does
- [ ] `PayKit.install()` in `Application.onCreate()`, before any screen can launch a paywall
- [ ] `PayKit.sync()` from the splash, inside a coroutine, before the first paywall
- [ ] Product ids in the document match the Play console exactly, base plans and offers included
- [ ] You do **not** call `AppPurchase.initBilling` yourself — PayKit registers the catalogue
- [ ] `pw_*` strings translated in your app for every locale you ship
- [ ] `onFinished` (or the `PaywallContract` result) navigates somewhere on **every** branch
- [ ] `OnboardKitPaywallGate` referenced only if `:onboardkitorigin` is on the runtime classpath
- [ ] Verified in a debug build with `logLevel = DEBUG` and `ConsoleSink`

---

## 12. Deliberate differences from the SDK this replaces

- **Back always works.** The audited paywall installed an empty `OnBackPressedCallback` and
  defaulted its exit button to off, which trapped the user on the screen — a store-policy risk. Here
  back is a real dismiss, always, and the close button is what the delay controls.
- **`onFinished` fires exactly once, on every exit path**, including a finish the screen did not
  start. Nothing has to guard against a missing callback or a double one.
- **A Restore button exists.** The audited paywall had none, so a reinstall on the same account had
  no path back to premium.
- **Colours are one hop.** Mapping `token-name → design-system → hex` produced two confirmed
  rendering bugs. A token is a hex string, validated, allow-listed, with a per-key bundled fallback.
- **User-visible text is a resource key.** English sentences in remote config cannot be localised;
  `*_key` can.
- **Placements fail closed.** No configuration shows no paywall.
- **Errors come from the billing engine's own result type**, never from string-matching a message.
- **One malformed row is one dropped row.** The document survives; the paywall still has something
  to sell.
- **`iap_success` is emitted once, by `:billingkit`.** Revenue is not counted on two clocks.
- **No permissions, no manifest `meta-data`.** The paywall opens no socket; fetching belongs to a
  `PaywallConfigSource`, and Remote Config defaults belong to the host.
