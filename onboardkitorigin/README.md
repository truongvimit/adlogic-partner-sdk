# OnboardKit

> The first-open flow as a library: splash → language → onboarding steps → optional full-screen ad → optional question → your app.

Ads, remote config, state persistence and the analytics funnel are inside. You supply ad unit ids,
copy, and where to go when the flow finishes. Tiếng Việt: **[README.vi.md](README.vi.md)** · हिन्दी: **[README.hi.md](README.hi.md)**

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

Declare `:ads` explicitly — inside this module it is an `implementation` dependency, so `com.ads.module.*` is otherwise
off your compile classpath. `:trackkit` is exported with `api`, `consumer-rules.pro` ships with the module, and the four
SDK activities are in the library manifest — do not redeclare them.

## Quick start

### 1. `Application.onCreate()`

`Tracker.install()` first — earlier events are only buffered. `OnboardingSdk.install()` before `configure()`
— a config passed before install is dropped and the whole flow then skips.

```kotlin
override fun onCreate() {
    super.onCreate()
    initTracking()                                    // Tracker.install + Tracker.addSink
    AdRemoteConfig.initializeFromAssets(this)         // assets/ad_config.json
    AdConfig.install(FirebaseAdConfigSource())        // optional: remote ad config
    ConsentCenter.configure(ConsentOptions(timeoutMs = 20_000, testDeviceHashedId = "…"))
    val adConfig = ERainAdConfig(this)                // fill its fields: see ../ads/README.md
    ERainAd.getInstance().init(this, adConfig)
    ERainTuning.install()                             // once, after ERainAd.init

    OnboardingSdk.install(this) {
        adProvider = ERainAdProvider()                // null for an ad-free flow
        paywallGate = MyPaywallGate()                 // optional
        listener = OnboardingListener { ctx, outcome -> goToMain(ctx, outcome) }
    }
    OnboardingSdk.configure(buildConfig()).onFailure { Log.e("OnboardKit", "rejected", it) }
    OnboardingSdk.setFlowLogging(BuildConfig.DEBUG)   // OB_FLOW logcat; on by default
}
```

The listener must navigate on `OnboardingOutcome.Completed`, `Skipped` **and** `Aborted` — with none registered the outcome is dropped. `Completed.selectedLanguage` carries the chosen language; `OnboardingSdk.selectedLanguage()` reads it back later.

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

Instead of `defaultSteps()`, list your own with `steps(vararg StepDefinition)` or `step(…)`. List order is
display order; remote config can only toggle a step off.

### 3. Splash

Your launcher activity extends `ObSplashActivity`. Consent, billing, remote fetch, ad requests, minimum
display, interstitial and navigation are inside; you fill in hooks.

```kotlin
class SplashActivity : ObSplashActivity() {
    override suspend fun onInitBilling() { myEntitlement.awaitReady() }  // resolve premium first

    override fun onRemoteFetched() {
        // fetch your app's own remote keys here
        OnboardingSdk.configure(buildConfig())   // rebuild: remote may have changed ad unit ids
    }
}
```

Declare it with `android:exported="true"`, a MAIN/LAUNCHER filter and an AppCompat/MaterialComponents theme.
Do not override `onConsentRequired()` — its default runs the UMP flow through `ConsentCenter` in `:ads`;
override only to `return true` for an app with no consent step. Do not call `OnboardingSdk.start()` here, it
runs once the pipeline resolves. If you override `onDestroy()`, call `super.onDestroy()` —
`ConsentCenter.detach(this)` lives there. Later: `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)`.
For a launcher that also serves notification, widget or shortcut taps, see [Entering from a notification or widget](#entering-from-a-notification-or-widget).

## Entering from a notification or widget

A tap that names a feature has to survive the whole first-open flow and then open that feature without covering
the ad it just paid for. Four pieces, the last of which is a decision only you can make.

**1. Point the entry at the splash, not at your main screen.** The tap starts a session, so it takes the same
route a launcher tap does — consent, remote, the splash interstitial, then language / onboarding or straight
through. Carry the feature as an intent extra.

```kotlin
// notification trampoline, widget PendingIntent, shortcut …
Intent(context, SplashActivity::class.java).apply {
    putExtra(EXTRA_WIDGET_ACTION, "merge_pdf")
    // CLEAR_TASK as well as NEW_TASK: without it a task left over from an earlier session is
    // simply brought forward, and the splash — with it the ad and the routing — never runs.
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
}
```

**2. The extras ride through as the passthrough.** `ObSplashActivity` seeds it from its own `intent.extras`; the
SDK carries it across language, onboarding and the question screen, and hands it back on the terminal outcome.
Nothing in the flow reads it — it is opaque to the SDK.

| Outcome | Carries the passthrough |
|---|---|
| `Completed` | yes |
| `Skipped` | yes — the flow was configured off, already done, or had nothing to show |
| `Aborted` | no |

**3. The listener puts it back on your main screen's intent.**

```kotlin
listener = OnboardingListener { context, outcome ->
    val extras = when (outcome) {
        is OnboardingOutcome.Completed -> outcome.passthrough
        is OnboardingOutcome.Skipped -> outcome.passthrough
        is OnboardingOutcome.Aborted -> null
    }
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { extras?.let(::putExtras) },
    )
}
```

`NEW_TASK` only, never `CLEAR_TASK`: under `UNDER_AD` this runs while the ad is on screen, and clearing the task
would finish the Activity hosting it. Each SDK screen finishes itself once it has started the next, so nothing is
left on the stack anyway.

Read the extra in **both** `onCreate` and `onNewIntent` — a cold tap arrives in the first, a warm one in the
second — and consume it as you read it, or the launching intent re-opens the feature on the next configuration
change.

**4. Choose when the destination starts.**

```kotlin
class SplashActivity : ObSplashActivity() {
    override fun nextScreenTiming(): NextScreenTiming =
        if (intent.hasExtra(EXTRA_WIDGET_ACTION)) NextScreenTiming.AFTER_AD
        else NextScreenTiming.UNDER_AD
}
```

| | `UNDER_AD` (default) | `AFTER_AD` |
|---|---|---|
| Destination starts | on the same tick as `show()`, behind the ad | once the ad is gone |
| The user sees | the screen already painted when the ad closes | a short stall, then the screen |
| Use when | the destination is where the user stops | the destination opens something of its own on entry |

`AFTER_AD` is always safe; it only gives up the head start. `UNDER_AD` is the optimization, and it is wrong in
exactly one case: the destination issues a second `startActivity` on entry. GMA's ad Activity lives in your own
task, so that launch is stacked *on top of* the ad and covers the impression before it counts.

| Destination opens on entry | Timing |
|---|---|
| Nothing — the user lands and stays | `UNDER_AD` |
| A `Dialog`, bottom sheet or fragment transaction | `UNDER_AD` — windows on the destination's own token, they sit behind the ad |
| Another Activity | `AFTER_AD` |
| Camera, audio or video playback | `AFTER_AD` |

Decide per launch, not per app: a launcher tap and a widget tap reach the same splash and want different answers,
which is why this is a hook on the Activity rather than a field on `SplashConfig`. When a notification carries
several actions and only some open a feature, answering `AFTER_AD` for all of them is correct — it costs those
entries the head start and nothing else.

The same decision exists one layer down for interstitials you show yourself: a screen whose callback starts a
waypoint that then opens a feature needs `InterstitialAdManager.show(…, nextAction = InterNextAction.AfterDismiss)`.
See [`../ads/README.md`](../ads/README.md).

## Configuration

| Config | Field | Type | Default |
|---|---|---|---|
| `SplashConfig` | `layoutRes` / `logoRes` / `appNameRes` | `@LayoutRes` / `@DrawableRes` / `@StringRes Int` | `0` = SDK layout / app icon / app label |
| | `minDisplayTimeMs` / `remoteFetchTimeoutMs` / `consentTimeoutMs` / `billingTimeoutMs` | `Long` | `3_000` / `10_000` / `15_000` / `5_000` |
| | `adLoadStrategy` | `AdLoadStrategy` | `ALTERNATE`; `SAME_TIME` loads ads during the fetch |
| `LanguageConfig` | `languages` / `defaultCode` | `List<ObLanguage>` / `String?` | `ObLanguages.ALL` (21 languages, flags shipped) / `null` |
| | `secondNativeOnSelectEnabled` / `tapHintEnabled` / `confirmVisibleBeforeSelect` | `Boolean` | `true` |
| `BehaviorConfig` | `lockPagerSwipe` / `backNavigatesBack` / `reloadAdOnStepReturn` | `Boolean` | `true` / `true` / `false` |
| `SystemBarConfig` | `showStatusBar` / `showNavigationBar` | `Boolean` | `true` |
| `QuestionConfig` | `titleRes` / `ctaTextRes` / `title` | `@StringRes Int` / `CharSequence?` | `0` / `0` / `null` |
| (`null` skips it) | `options` — `QuestionOption(id, title, titleRes, imageRes, imageUrl)` | `List<QuestionOption>` | `emptyList()`; empty also skips the screen |
| | `selectionMode` / `minSelection` / `refreshAdOnSelect` | `SelectionMode` / `Int` / `Boolean` | `MULTIPLE` / `1` (≥ 1) / `false` |

**Steps.** `ContentStepDefinition(id, titleRes = 0, subtitleRes = 0, title = null, subtitle = null, imageRes = 0,
layoutRes = 0, showsProgressIndicator = true)` and `AdFullScreenStepDefinition(id, showSkipButton = true,
skipButtonDelaySec = 3, autoNextEnabled = false, autoNextDelayMs = 15_000, layoutRes = 0)`. `id` is a `StepId`:
`OB1`…`OB5` — positions in the flow, not content pages: OB3 is the ad-only page of the default template, so the third *content* page is `StepId.OB4`.

**AdsConfig.** A `null` slot shows no ad. Every native/interstitial slot is a waterfall: ids ordered highest floor first, one request at a time, stopping at the first fill.

| Field | Type | Default |
|---|---|---|
| `enabled` / `skipAdOnlyStepsWhenPremium` | `Boolean` | `true` — master switch / premium skips ad-only steps |
| `splashBanner` | `BannerAdUnit?` | `null` |
| `splashInterstitial` / `splashInterstitialOldUser` | `InterstitialAdUnit?` | `null`; old-user falls back to the other |
| `languageNative` / `languageDupNative` | `NativeAdUnit?` | `null`; dup falls back to `languageNative` |
| `contentStepNative`, `fullScreenStepNative`, `ob5Native`, `questionNative` | `NativeAdUnit?` | `null` |
| `stepNatives` | `Map<StepId, NativeAdUnit>` | `emptyMap()` — per-page override of `contentStepNative` / `fullScreenStepNative`; `stepNatives[OB5]` also backs `ob5Native` |
| `questionInterstitial`, `appResume` | `InterstitialAdUnit?` | `null` |
| `contentStepTemplate` / `languageTemplate` / `questionTemplate` | `NativeTemplate` | `CTA_TOP` / `CTA_BOTTOM` / `CTA_BOTTOM` |

**Interstitials start the next screen underneath the ad.** `onNext` runs on the same tick as the
vendor's `show()`, so the destination is created below the ad rather than on top of it; `onFinished`
runs when the ad is gone. This is not configurable — see `InterNextAction.UnderAd` in
[../ads/README.md](../ads/README.md) for why the ordering is fixed.

One consequence is worth knowing about: the ad's window is translucent while the creative animates
in, so a destination started this way plays its entry transition in full view through the ad. The
flow does **not** suppress that transition for you. If it is visible in your app, suppress it on the
destination itself — `overridePendingTransition(0, 0)` right after starting it, or
`Intent.FLAG_ACTIVITY_NO_ANIMATION` — rather than delaying the start, which would break the launch
order the ad depends on.

**Native templates.** These screens ship one layout per CTA position rather than moving blocks about, so
the template picks the layout and `components` is read for **visibility only** — it never reorders here.

| `NativeTemplate` | Layout |
|---|---|
| `CTA_TOP` | `ob_layout_native_cta_top` — CTA above the media |
| `CTA_BOTTOM` | `ob_layout_native_cta_bottom` — CTA below the media |
| `COMPACT` | `ob_layout_native_compact` — two rows, no media |
| `FULL_SCREEN` | `ob_layout_native_fullscreen` — media full-bleed, text overlaid |

Derive the template from the placement's `positionCTA` so one document drives both:

```kotlin
languageTemplate = ads.templateOf("native_lang")                                  // "BOTTOM" -> CTA_BOTTOM
contentStepTemplate = ads.templateOf("native_ob1", default = NativeTemplate.CTA_TOP)
```

Placements outside this flow leave `positionCTA` unset; there `components` orders the blocks. See
[../ads/README.md](../ads/README.md).

`onboardKitConfig { }` returns `Result.failure(ObConfigException)` on: duplicated `StepId`; `minSelection < 1`;
duplicated question option ids; an empty language list; a tier list that is all-blank, holds a blank id or repeats an id; a blank `splashBanner.id`; any of the five rejected `layoutRes` knobs set.

**Ad-only steps.** An `AdFullScreenStepDefinition` page is dropped from the flow when its placement can never fill:
no ad unit, `enabled = false`, master or per-placement remote flag down, no provider, or consent unanswered. Step
count, progress indicator and resume index shrink with it. Premium is not a removal reason — it follows
`skipAdOnlyStepsWhenPremium`. A page already in the flow that then fails to fill leaves through `StepHost.skipAdStep(stepId)`.

## Custom layouts

Only `SplashConfig.layoutRes` and `ContentStepDefinition.layoutRes` are read by a screen. The other five `layoutRes`
knobs are rejected — leave them at `0` and override the SDK layout of the same name instead, keeping every id it declares.

| Rejected knob | Override this layout instead |
|---|---|
| `LanguageConfig.layoutRes` / `.itemLayoutRes` | `ob_activity_language.xml` / `ob_item_language.xml` |
| `QuestionConfig.layoutRes` / `.optionLayoutRes` | `ob_activity_question.xml` / `ob_item_question_option.xml` |
| `AdFullScreenStepDefinition.layoutRes` | `ob_fragment_ad_step.xml` |

- Splash binds each id null-safely, so one you leave out is skipped.
- A content-step layout must carry **all** of its ids, or that page falls back to the SDK layout with a log.
- Native templates (`ob_layout_native_*`) use the standard AdMob ids; keep whichever ids the template you override already declares.

| Screen | Id | Type |
|---|---|---|
| Splash | `ob_splash_logo` / `ob_splash_app_name` / `ob_splash_progress` | `ImageView` / `TextView` / `ProgressBar` |
| | `ob_splash_ad_container` | `FrameLayout`; put `<include layout="@layout/layout_banner_control" />` inside it or the splash banner has nowhere to attach |
| Content step | `ob_step_image` / `ob_step_player` / `ob_step_card` | `ImageView` / `androidx.media3.ui.PlayerView` / `LinearLayout` |
| | `ob_step_title` / `ob_step_subtitle` / `ob_step_indicator` / `ob_primary_cta` | `TextView` / `TextView` / `ObStepIndicator` / `ObPrimaryButton` |
| | `ob_ad_block` / `ob_native_container` | `FrameLayout` (the block is hidden when the slot is declined) / `FrameLayout` |

For a screen of your own inside the flow, `showInterstitial(placement, onNext, onFinished)` is a public extension on
`AppCompatActivity`: start the destination in `onNext` (under the ad), finish the current screen in `onFinished`. Both
run at most once, `onNext` always first, on every path; never call `finish()` from `onNext`. No public native
equivalent — render your own natives through `NativeAdHelper` in `:ads`.

## Remote config keys

Defaults live in `ObRemoteKeys`; publishing nothing keeps the defaults below.

| Key | Type | Default | What it does |
|---|---|---|---|
| `ob_enable_all_ads` / `ob_enable_ui_content` | Boolean | `true` | Master ad kill switch / server-driven UI on/off |
| `ob_enable_step_ob1` … `ob_enable_step_ob4` | Boolean | `true` | Toggle one step |
| `ob_enable_step_ob5` | Boolean | `false` | The standalone full-screen ad screen after the pager |
| `ob_enable_question` / `ob_enable_question_old_user` | Boolean | `true` / `false` | Survey for new users / for users who already finished |
| `ob_enable_language_native_2` / `ob_pass_lfo_if_completed` | Boolean | `true` | Second native on the first language tap / skip the language screen once a language is chosen |
| `ob_show_language_tap_hint` / `ob_show_language_confirm_before_select` | Boolean | `true` | Hand hint / confirm button before a pick; each AND-ed with its `LanguageConfig` field |
| `ob_language_supported_codes` | String | `""` | CSV filter and order; empty = full catalog |
| `ob_reuse_splash_inter` | Boolean | `true` | Reuse a buffered splash interstitial at the end of the pager |
| `ob_ads_splash_banner_enabled`, `ob_ads_splash_inter_enabled`, `ob_ads_language_native_enabled`, `ob_ads_content_native_enabled`, `ob_ads_fullscreen_native_enabled`, `ob_ads_question_native_enabled`, `ob_ads_question_inter_enabled`, `ob_ads_app_resume_enabled` | Boolean | `true` | One switch per placement, each AND-ed with `ob_enable_all_ads` |
| `ob_splash_min_display_ms` / `ob_splash_ad_budget_ms` / `ob_splash_banner_wait_ms` | Long | `3000` / `60000` / `0` | Overrides `SplashConfig.minDisplayTimeMs` when > 0 / whole-waterfall budget for the splash interstitial (30 s per floor) / how long the splash holds for the banner first |
| `ob_skip_button_delay_sec` / `ob_fullscreen_auto_dismiss_sec` | Long | `3` / `15` | Overrides `AdFullScreenStepDefinition.skipButtonDelaySec` / OB5 auto-dismiss, floored at 5 (pager pages use `autoNextDelayMs`) |
| `ob_show_skip_ob3` / `ob_show_skip_ob5` | Boolean | `true` | Skip button on the ad-only pager page / on OB5 |
| `ob_ui_content` / `ob_ui_design_tokens` | String | `""` | Per-step JSON (title, subtitle, colors, image or video) and its color/typography tokens |
| `ob_question_config` / `ob_config_version` | String / Long | `""` / `0` | JSON replacing the whole compile-time option list / change the value to clear the local cache |

`ob_ads_splash_banner_enabled`, `ob_ads_splash_inter_enabled`, `ob_ads_app_resume_enabled`, `ob_splash_ad_budget_ms`
and `ob_splash_banner_wait_ms` are not cached locally: on a cold start before the fetch lands they read as their defaults.

## Analytics events

Emitted automatically once `Tracker.install()` and one `Tracker.addSink(...)` are wired. Event identity is
the `StepId`, never the pager index.

| Stage | Events |
|---|---|
| Flow | `fo_flow_start` (emitted even when the flow skips), `fo_flow_complete` |
| Splash | `fo_splash_view`, `fo_splash_complete` |
| Language | `fo_language_view`, `fo_language_select`, `fo_language_complete`, `fo_language_flow_complete` |
| Steps | `fo_step_view`, `fo_step_complete` (`step`, `index`, `exit_reason` = `cta` / `skip` / `auto_next` / `ad_failed` / `auto_dismiss`) |
| Question | `fo_question_view`, `fo_question_answer`, `fo_question_complete` |
| Ads | `ad_request`, `ad_show`, `ad_load_failed`, `ad_skipped` (`reason`) |
| Paywall, screens | `iap_paywall_view`, `iap_paywall_result`; one `Tracker.screen(...)` per SDK screen |

`ad_skipped` reasons: `premium`, `consent_not_granted`, `ads_off_config`, `no_provider`, `no_ad_unit`, `ads_off_remote`, `placement_off_remote`, `no_fill`, `not_ready`, `offline`, `ua_gate`, `capped_by_module`, `purchased_at_paywall`, `suppressed_by_flow`, `returning_from_ad_click`, `failed_to_show`. (`no_handshake` is retired — nothing raises it any more.)

To receive them yourself add `analyticsPlugin { event -> log(event.name, event.params) }` inside `install`, or collect
`OnboardingSdk.events` / `.state`. A plugin sees the SDK's own `ob_*` event names, not the `fo_*` taxonomy above —
those exist only on the `Tracker` side. `isCompleted()`, `selectedLanguage()`, `answers()`, `markCompleted()` and `reset()` read and clear persisted progress.

## Paywall gate

```kotlin
class MyPaywallGate : PaywallGate {
    override suspend fun shouldShow(placement: PaywallPlacement) =
        placement == PaywallPlacement.AFTER_ONBOARDING && !myEntitlement.isPremium

    override suspend fun present(activity: Activity, placement: PaywallPlacement): PaywallOutcome =
        PaywallOutcome.Dismissed   // or Purchased / ContinueWithAds
}
```

Placements: `SPLASH_INTER`, `AFTER_ONBOARDING`, `AFTER_QUESTION_OLD_USER`. Leave `paywallGate` unset and
every checkpoint passes straight through.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Flow never runs | `configure()` failed, or ran before `install()` | Log the `Result`; call `install()` first |
| User never leaves the flow | No `OnboardingListener`, or it ignores `Skipped` | Handle all three outcomes |
| Every placement says `no_provider` | `adProvider` left null | `adProvider = ERainAdProvider()` |
| Every placement says `consent_not_granted` | UMP form unanswered within `consentTimeoutMs` | Set `ConsentOptions(testDeviceHashedId = …)` |
| Ad-only page never appears | No usable unit for `fullScreenStepNative` / `stepNatives[OB3]` | Configure one; `ob_enable_step_ob3` alone is not enough |
| Splash banner never shows | `ob_splash_ad_container` or the `layout_banner_control` include is missing | Add both to your splash layout |

## License

MIT — see [`../LICENSE`](../LICENSE).
