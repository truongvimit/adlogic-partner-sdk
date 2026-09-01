# OnboardKit

> The first-open flow as a library: splash → language → onboarding steps → optional full-screen ad
> → optional question → your app.

Ads, remote config, state persistence and the analytics funnel are inside. You supply ad unit ids,
copy, and where to go when the flow finishes.

Tiếng Việt: **[README.vi.md](README.vi.md)** · हिन्दी: **[README.hi.md](README.hi.md)**

## Requirements

| | |
|---|---|
| minSdk / compileSdk / JDK | 24 / 36 / 17 |
| Namespace, resource prefix, entry point | `io.onboardkit`, `ob_`, `OnboardingSdk` |
| Firebase | `google-services.json` + `com.google.gms.google-services`; without it every `ob_*` key stays at its default |
| Ad unit ids | `assets/ad_config.json` via `AdRemoteConfig`, or literals in `AdsConfig` |

## Installation

```groovy
// Replace <tag> with a tag from https://github.com/truongvimit/adlogic-partner-sdk/tags
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

Declare `:ads` explicitly — inside this module it is an `implementation` dependency, so
`com.ads.module.*` is otherwise off your compile classpath. `:trackkit` is exported with `api`,
`consumer-rules.pro` ships with the module, and the SDK activities are in the library manifest — do
not redeclare them.

## Integration

### 1. `Application.onCreate()`

`Tracker.install()` first — earlier events are only buffered. `OnboardingSdk.install()` before
`configure()` — a config passed before install is dropped and the whole flow then skips.

```kotlin
override fun onCreate() {
    super.onCreate()
    initTracking()                                    // Tracker.install + Tracker.addSink
    AdRemoteConfig.initializeFromAssets(this)         // assets/ad_config.json
    AdConfig.install(FirebaseAdConfigSource())        // optional: remote ad config
    ConsentCenter.configure(ConsentOptions(timeoutMs = 20_000, testDeviceHashedId = "…"))
    ERainAd.getInstance().init(this, buildERainAdConfig())   // see ../ads/README.md
    ERainTuning.install()                             // once, after ERainAd.init

    OnboardingSdk.install(this) {
        adProvider = ERainAdProvider()                // null for an ad-free flow
        paywallGate = OnboardKitPaywallGate()         // optional, from :paykit
        listener = OnboardingListener { ctx, outcome -> goToMain(ctx, outcome) }
    }
    OnboardingSdk.configure(buildConfig()).onFailure { Log.e("OnboardKit", "rejected", it) }
    OnboardingSdk.setFlowLogging(BuildConfig.DEBUG)   // OB_FLOW logcat
}
```

The listener must navigate on `OnboardingOutcome.Completed`, `Skipped` **and** `Aborted` — with none
registered the outcome is dropped. `Completed.selectedLanguage` carries the chosen language;
`OnboardingSdk.selectedLanguage()` reads it back later.

### 2. The config

```kotlin
private fun buildConfig() = onboardKitConfig {
    splash = SplashConfig(logoRes = R.drawable.ic_logo, minDisplayTimeMs = 3_000)
    language = LanguageConfig(defaultCode = "en")
    defaultSteps()                                    // OB1, OB2, OB3 (ad-only), OB4
    question = QuestionConfig(options = listOf(QuestionOption("romance", "Romance")))
    ads = AdsConfig(
        splashBanner         = BannerAdUnit("ca-app-pub-…/1111"),
        splashInterstitial   = InterstitialAdUnit("ca-app-pub-…/2222"),
        languageNative       = NativeAdUnit.waterfall(highFloor = "…/3333", allPrice = "…/4444"),
        contentStepNative    = NativeAdUnit("ca-app-pub-…/5555"),
        fullScreenStepNative = NativeAdUnit("ca-app-pub-…/6666"),
    )
}.getOrThrow()
```

`onboardKitConfig { }` returns a `Result` — it validates and rejects rather than crashing later.
`SplashConfig`, `LanguageConfig`, `BehaviorConfig`, `SystemBarConfig`, `QuestionConfig` and
`AdsConfig` each carry their own knobs, documented field by field in KDoc; the defaults are a
working flow, so set only what you want to change.

**Ad slots.** A `null` slot shows no ad. Every native and interstitial slot is a waterfall: ids
ordered highest floor first, one request at a time, stopping at the first fill. `AdsConfig` lists
every slot the flow can fill.

To keep the ids in `ad_config.json` instead of hard-coding them, feed the slots from
`AdRemoteConfig`. The SDK has no helper for this — these three are app-side glue, and they are all
you need:

```kotlin
private fun AdRemoteConfig?.native(baseKey: String): NativeAdUnit? =
    this?.tiersFor(baseKey)?.takeIf { it.isNotEmpty() }?.let { NativeAdUnit(tiers = it) }

private fun AdRemoteConfig?.interstitial(baseKey: String): InterstitialAdUnit? =
    this?.tiersFor(baseKey)?.takeIf { it.isNotEmpty() }?.let { InterstitialAdUnit(tiers = it) }

// Banners have no waterfall here — the top tier is the only id that can be used.
private fun AdUnitConfig?.toBanner(): BannerAdUnit? =
    this?.takeIf { it.isUsable }?.let { BannerAdUnit(id = it.waterfallIds.first()) }
```

```kotlin
val ads = runCatching { AdRemoteConfig.getInstance() }.getOrNull()
ads = AdsConfig(
    splashInterstitial = ads.interstitial("inter_splash"),
    languageNative     = ads.native("native_lang"),
    contentStepNative  = ads.native("native_ob1"),
)
```

**Steps.** Instead of `defaultSteps()`, list your own with `steps(vararg StepDefinition)` or
`step(…)` — `ContentStepDefinition` for a content page, `AdFullScreenStepDefinition` for an ad-only
one. List order is display order; remote config can only toggle a step off. `id` is a `StepId`
(`OB1`…`OB5`) — a position in the flow, not a content page: OB3 is the ad-only page of the default
template, so the third *content* page is `StepId.OB4`.

**Native templates.** These screens ship one layout per CTA position, so `NativeTemplate` picks the
layout rather than moving blocks about. Set it literally, or derive it from the same config document
with one more app-side helper:

```kotlin
private fun AdRemoteConfig?.templateOf(
    key: String,
    default: NativeTemplate = NativeTemplate.CTA_BOTTOM,
): NativeTemplate = when (this?.unit(key)?.positionCTA) {
    "TOP" -> NativeTemplate.CTA_TOP
    "BOTTOM" -> NativeTemplate.CTA_BOTTOM
    else -> default
}
```

### 3. Splash

Your launcher activity extends `ObSplashActivity`. Consent, billing, remote fetch, ad requests,
minimum display, the interstitial and the navigation out are inside; you fill in hooks.

```kotlin
class SplashActivity : ObSplashActivity() {
    override suspend fun onInitBilling() { myEntitlement.awaitReady() }  // resolve premium first

    override fun onRemoteFetched() {
        // fetch your app's own remote keys here
        OnboardingSdk.configure(buildConfig())   // rebuild: remote may have changed ad unit ids
    }
}
```

Declare it with `android:exported="true"`, a MAIN/LAUNCHER filter and an
AppCompat/MaterialComponents theme.

- Do not call `OnboardingSdk.start()` here — it runs once the pipeline resolves.
- Do not override `onConsentRequired()`; its default runs the UMP flow through `ConsentCenter` in
  `:ads`. Override it only to `return true` for an app with no consent step.
- If you override `onDestroy()`, call `super.onDestroy()` — `ConsentCenter.detach(this)` lives there.

Later, from anywhere: `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)`.

## Entering from a notification, widget or uninstall shortcut

A tap that names a feature has to survive the whole first-open flow and then open that feature
without covering the ad it just paid for. That wiring ships as `SplashEntry` (`NOTIFICATION`,
`WIDGET`, `UNINSTALL`) — the entry intent, the ad unit it spends and the timing are answered for
you. What stays yours is the extras that name a feature and the screen each entry lands on.

**1. Fire the entry's intent at the splash, not at your main screen.** The tap starts a session, so
it takes the same route a launcher tap does. `SplashEntry.intent` tags the launch and already sets
`NEW_TASK or CLEAR_TASK`; add feature extras on top.

```kotlin
SplashEntry.WIDGET.intent(context, SplashActivity::class.java)
    .putExtra(EXTRA_WIDGET_ACTION, "merge_pdf")
```

**2. The extras ride through as the passthrough.** `ObSplashActivity` seeds it from its own
`intent.extras`, the SDK carries it across every screen, and hands it back on `Completed` and
`Skipped` (never on `Aborted`). Your feature extras are opaque to the SDK.

**3. The listener routes the outcome** — the one decision each app makes for itself:

```kotlin
listener = OnboardingListener { context, outcome ->
    val extras = when (outcome) {
        is OnboardingOutcome.Completed -> outcome.passthrough
        is OnboardingOutcome.Skipped -> outcome.passthrough
        is OnboardingOutcome.Aborted -> null
    }
    val destination = when (SplashEntry.from(extras)) {
        SplashEntry.UNINSTALL -> ConfirmUninstallActivity::class.java
        else -> MainActivity::class.java
    }
    context.startActivity(
        Intent(context, destination)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { extras?.let(::putExtras) },
    )
}
```

`NEW_TASK` only, never `CLEAR_TASK`: this can run while the ad is on screen, and clearing the task
would finish the Activity hosting it. Read your extra in **both** `onCreate` and `onNewIntent` — a
cold tap arrives in the first, a warm one in the second — and consume it as you read it.

**4. The ad unit and the timing are answered for you.** A `SplashEntry` launch spends its entry's
key (`inter_noti`, `inter_widget`, `inter_uninstall`), full waterfall included, falling back to the
regular splash resolution when that key is missing or disabled. It also gets `AFTER_AD`, while a
launcher tap keeps `UNDER_AD` — the same trade-off as `InterNextAction` in
[`../ads/README.md`](../ads/README.md#when-the-next-screen-starts). Override
`nextScreenTiming()` or `splashInterstitialOverride()` only for a finer split.

## Custom layouts

Only `SplashConfig.layoutRes` and `ContentStepDefinition.layoutRes` are read by a screen. The other
`layoutRes` knobs are rejected by validation — leave them at `0` and override the SDK layout of the
same name instead, keeping every id it declares.

| Instead of | Override this layout |
|---|---|
| `LanguageConfig.layoutRes` / `.itemLayoutRes` | `ob_activity_language.xml` / `ob_item_language.xml` |
| `QuestionConfig.layoutRes` / `.optionLayoutRes` | `ob_activity_question.xml` / `ob_item_question_option.xml` |
| `AdFullScreenStepDefinition.layoutRes` | `ob_fragment_ad_step.xml` |

Splash binds each id null-safely, so one you leave out is skipped. A content-step layout must carry
**all** of its ids, or that page falls back to the SDK layout with a log.

| Screen | Id | Type |
|---|---|---|
| Splash | `ob_splash_logo` / `ob_splash_app_name` / `ob_splash_progress` | `ImageView` / `TextView` / `ProgressBar` |
| | `ob_splash_ad_container` | `FrameLayout`; put `<include layout="@layout/layout_banner_control" />` inside it or the splash banner has nowhere to attach |
| Content step | `ob_step_image` / `ob_step_player` / `ob_step_card` | `ImageView` / `androidx.media3.ui.PlayerView` / `LinearLayout` |
| | `ob_step_title` / `ob_step_subtitle` / `ob_step_indicator` / `ob_primary_cta` | `TextView` / `TextView` / `ObStepIndicator` / `ObPrimaryButton` |
| | `ob_ad_block` / `ob_native_container` | `FrameLayout` (hidden when the slot is declined) / `FrameLayout` |

For a screen of your own inside the flow, `showInterstitial(placement, onNext, onFinished)` is a
public extension on `AppCompatActivity`: start the destination in `onNext` (under the ad), finish the
current screen in `onFinished`. Both run at most once, `onNext` always first, on every path; never
call `finish()` from `onNext`. There is no public native equivalent — render your own natives through
`NativeAdHelper` in `:ads`.

## Paywall gate

```kotlin
class MyPaywallGate : PaywallGate {
    override suspend fun shouldShow(placement: PaywallPlacement) =
        placement == PaywallPlacement.AFTER_ONBOARDING && !myEntitlement.isPremium

    override suspend fun present(activity: Activity, placement: PaywallPlacement): PaywallOutcome =
        PaywallOutcome.Dismissed   // or Purchased / ContinueWithAds
}
```

Placements: `SPLASH_INTER`, `AFTER_ONBOARDING`, `AFTER_QUESTION_OLD_USER`. Leave `paywallGate` unset
and every checkpoint passes straight through. Shipping `:paykit`? Use its ready-made
`OnboardKitPaywallGate` instead — see [`../paykit/README.md`](../paykit/README.md).

## Remote config

Every `ob_*` key, its type and its default live in `io.onboardkit.remote.ObRemoteKeys` — one object,
each key documented where it is declared. Publish nothing and the flow runs on those defaults;
publish a key on the Firebase console to override it. Nothing here needs app code: the splash's
remote fetch applies them.

## Analytics

The funnel is emitted automatically once `Tracker.install()` and one `Tracker.addSink(...)` are
wired — see [`../trackkit/README.md`](../trackkit/README.md) for the event names. To receive the
SDK's own events instead, add `analyticsPlugin { event -> log(event.name, event.params) }` inside
`install`, or collect `OnboardingSdk.events` / `.state`.

`isCompleted()`, `selectedLanguage()`, `answers()`, `markCompleted()` and `reset()` read and clear
persisted progress.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Flow never runs | `configure()` failed, or ran before `install()` | Log the `Result`; call `install()` first |
| User never leaves the flow | No `OnboardingListener`, or it ignores `Skipped` | Handle all three outcomes |
| Every placement says `no_provider` | `adProvider` left null | `adProvider = ERainAdProvider()` |
| Every placement says `consent_not_granted` | UMP form unanswered within `consentTimeoutMs` | Set `ConsentOptions(testDeviceHashedId = …)` |
| Ad-only page never appears | No usable unit for `fullScreenStepNative` / `stepNatives[OB3]` | Configure one; the remote step flag alone is not enough |
| Splash banner never shows | `ob_splash_ad_container` or the `layout_banner_control` include is missing | Add both to your splash layout |

## License

MIT — see [`../LICENSE`](../LICENSE).
