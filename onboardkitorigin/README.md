# OnboardKit

Splash → language → onboarding → optional question/paywall → your app.
The SDK owns screen transitions, ad preloading and saved progress; your app supplies content and the final destination.

Default order: **OB1 → Full1 → OB2 → Full2 → OB3 → OB4**. Step IDs are stable identities:
`OB1..OB4` use `native_ob1..4`; `FULL1/FULL2` use `native_full1/2`.
The first language selection preloads every eligible native in the configured list. Pager entry
preloads the exit interstitial. Step ads never reload on return or refill after showing.
App definitions may set `enabled = false`. A remote `onboarding.order` selects and orders pages
from the whole catalog, disabled ones included; an `order` in your app asset selects among enabled
pages only. Without a remote order, a delivered `ob_enable_step_obN` adds or removes its page.
Remove IDs from `order` to omit pages; no `steps.*.enabled` map is needed or read.
Per-placement ads are controlled by `ad_config` (`isEnable`). See the [configuration and migration guide](../partner-integration/onboarding-flow.vi.md).

[Tiếng Việt](README.vi.md) · [हिन्दी](README.hi.md)

[Partner integration guides](../partner-integration/README.md) · [Ads + OnboardKit walkthrough](../partner-integration/ads-onboarding-integration.md)

## Before you start

- Use minSdk 24, compileSdk 36 and JDK 17. Follow the [shared build setup](../README.md) and use the same published tag for every module.
- For the built-in ad provider, first complete the [ads guide](../ads/README.md): AdMob/Meta manifest values, ad config assets and `ERainAd.init()` in your Application.
- Install `Tracker` and a sink before OnboardKit if you need the funnel; see [Trackkit](../trackkit/README.md).
- Add both dependencies below. OnboardKit exports Trackkit, but partner code using `com.ads.module.*` needs an explicit `ads` dependency. Firebase and PayKit setup are optional.

Set `adlogicSdkVersion` once in your app's `gradle.properties`; see the [shared build setup](../README.md#build-setup).

```groovy
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
}
```

## 1. Install and configure in your Application

Merge this into your existing Application **after the ads initialization above**; do not initialize ERain twice.
The snippet contains Google test ad units. Replace them with your units for release and use your own `MainActivity`.
Import your app's `R`, `BuildConfig` and `MainActivity` if they are in different packages; `io.onboardkit.config.*` covers all config types below.

```kotlin
import android.app.Application
import android.content.Intent
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.ads.erain.ERainTuning
import io.onboardkit.config.*
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Complete ERainAd.init here; add Tracker/sinks if needed (ads guide).
        ERainTuning.install()

        OnboardingSdk.install(this) {
            adProvider = ERainAdProvider()
            listener = OnboardingListener { context, outcome ->
                val extras = when (outcome) {
                    is OnboardingOutcome.Completed -> outcome.passthrough
                    is OnboardingOutcome.Skipped -> outcome.passthrough
                    is OnboardingOutcome.Aborted -> null
                }
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .apply { extras?.let { putExtras(it) } },
                )
            }
        }
        val config = onboardKitConfig {
            splash = SplashConfig(appNameRes = R.string.app_name)
            defaultSteps()
            ads = AdsConfig(
                splashInterstitial = InterstitialAdUnit(
                    "ca-app-pub-3940256099942544/1033173712",
                ),
                languageNative = NativeAdUnit(
                    "ca-app-pub-3940256099942544/2247696110",
                ),
                contentStepNative = NativeAdUnit(
                    "ca-app-pub-3940256099942544/2247696110",
                ),
            )
        }.getOrThrow()
        OnboardingSdk.configure(config).getOrThrow()
        OnboardingSdk.setFlowLogging(BuildConfig.DEBUG)
    }
}
```

Call `install()` before `configure()`. Both config building and configuration return a `Result`; this example fails visibly on invalid setup.
The listener handles all three outcomes. `Completed.selectedLanguage` also provides the chosen language.
Use `NEW_TASK` without `CLEAR_TASK` for the final handoff: the splash ad or the end-of-onboarding ad may still be on screen in this task.

## 2. Add your launcher splash

```kotlin
import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity()
```

```xml
<application android:name=".App">
    <activity android:name=".MainActivity" android:exported="false" />
    <activity
        android:name=".SplashActivity"
        android:exported="true"
        android:screenOrientation="portrait"
        android:configChanges="orientation|screenSize|keyboardHidden|uiMode|fontScale"
        android:theme="@style/ob_Theme_OnboardKit">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
</application>
```

Merge these declarations into your manifest; keep the ads guide's metadata and permissions. SDK screens are already declared by the library.
Do not call `OnboardingSdk.start()` or finish splash yourself; `ObSplashActivity` owns the flow.

### Default flow behavior

- System bars show status/caption bars and hide navigation by default; use `SystemBarConfig` to customize them.
- An incomplete flow starts again through Splash → LFO → OB on a new launch. A completed flow skips onboarding.
- Re-selecting the current language opens the popup immediately. Selecting another language opens it from the configured total tap count onward; re-select taps still count. Its native loads when the popup opens; click/open preloads a replacement to show on return.
- Native `behavior.click.action` selects one of `auto_next`, `none`, or `reload`. Content/fullscreen pager steps default to `auto_next`; LFO1/LFO2, OB5, splash, popup and question natives default to `reload`.
- `reload` starts a replacement request immediately on ad click/open and uses the result on return. Ordinary app resume does not trigger click reload. `auto_next` advances after return without requesting a replacement; `none` does neither.
- Native reload keeps the old ad visible without shimmer until a replacement binds successfully. Load failure keeps the old ad and its slot visible. Initial loading without an ad still uses shimmer.
- Set `lfo.native2.behavior.click.action = "auto_next"` to confirm the selected language on return. LFO1 `auto_next` selects the current/default language and advances to the second language slot. The action is fixed for each click trip and overrides legacy `reload.on_ad_click` / `BehaviorConfig.adClickReturnCompletesStep` switches. See the [remote settings guide](../partner-integration/remote-settings.md).


- `notificationPermissionEnabled = true`: Android 13+ / target 33+ requests notifications after consent and the remote fetch step, so a remote value fetched in that step applies to the same launch. A grant or a recorded automatic request result skips later prompts; denial still continues. Set it to `false` if your app owns this prompt.
- `noInternetPromptEnabled = true`: splash asks the user to connect before continuing. Set it to `false` if your app should allow an offline start.
- `lockPortrait = true`: SDK screens, including your splash subclass, are locked to portrait. Keep the splash `configChanges` above so the lock, dark mode or font scale does not recreate it. Landscape apps must set it to `false` and review merged manifest orientation rules too.
- `consentTimeoutMs = 20_000`: the default SDK-owned UMP flow does **not** time out the user's answer. The budget still bounds a custom hook when no SDK-owned consent flow is resolving.
- Authorized splash ads can load beneath the notification prompt while splash remains visible. Home blocks new requests. The minimum display time begins once the ad phase starts and overlaps loading/notification UI. By default the first-open flow (language/onboarding) uses `AFTER_AD`, and a launcher start past completed onboarding (your app or the returning-user question) uses `UNDER_AD`; notification, widget and uninstall entries always use `AFTER_AD`; override `nextScreenTiming()` in your splash and call `super` for the cases that keep the default. On a launcher start, a remote `splash.navigation.next_screen_timing` other than `AUTO` outranks your override. Both timings wait out the remaining minimum before showing the interstitial: `UNDER_AD` opens the destination and shows the ad together, while `AFTER_AD` opens the destination as soon as the ad is dismissed.

### Splash and language options

The supplied splash/provider owns consent, preloading and handoff; keep app-side delays out of
this flow. Configure only the defaults you need to change:

| Option | Default / use |
|---|---|
| `SplashConfig.minDisplayTimeMs` | 3000 ms before the splash interstitial shows, or before navigation when there is no ad. A remote `splash.timing.min_display_ms`, a delivered `ob_splash_min_display_ms` above 0, or `splash.timing.min_display_ms` in your app asset overrides it; otherwise your value applies. |
| `ob_splash_ad_budget_ms` | 60000 ms of ad waiting, starting after notification completes and splash has focus. |
| `ob_splash_lfo_parallel_preload_enabled` | `false`: preload the first language native after the splash waterfall settles or its wait expires. `true`: preload alongside splash ads. |
| `LanguageConfig.tapHintEnabled` | Shows the language selection hand. A remote `lfo.tap_hint.enabled` or a delivered `ob_show_language_tap_hint` decides instead, on or off. |
| `ob_language_tap_hint_delay_sec` | 3 seconds; `0` shows immediately. Selecting a language cancels the hint; SETTINGS/preselected language hides it. |

`ob_*` values are optional Firebase Remote Config parameters. A key the backend delivered outranks
the matching Kotlin option; one it never sent leaves your value in charge. See
[ObRemoteKeys](src/main/java/io/onboardkit/remote/RemoteKeys.kt) for other supported options.
Native slots outside onboarding follow the [Ads guide](../ads/README.md#native-preload-repeated-show-and-refresh).

## 3. Map ads and content to screens

Leave optional slots unset unless you need them; some slots inherit fallback units, as documented in [AdsConfig](src/main/java/io/onboardkit/config/AdsConfig.kt).

| Configuration | Screen |
|---|---|
| `splashBanner`, `splashInterstitial` | Splash banner / interstitial |
| `languageNative`, `languageDupNative` | First / replacement language native |
| `contentStepNative`, `stepNatives[StepId.OB1]` | Shared content native / per-step override |
| `fullScreenStepNative` | Ad-only step; skipped when no usable unit exists |
| `afterOnboardingInterstitial` | Separate interstitial at onboarding completion (`inter_after_ob3`) |
| `appResume` | App-open eligibility during language/content and in-app returns |

`defaultSteps()` creates OB1, Full1, OB2, Full2, OB3, OB4. For your copy and images, replace it with `steps(ContentStepDefinition(...), ...)`; see [step definitions](src/main/java/io/onboardkit/config/StepDefinition.kt).
Native/interstitial waterfalls accept `tiers = listOf(highId, fallbackId)` in request order; banners take one ID.

`AdsConfig.fromAdConfig()`, the `onboardKitConfig` default, binds the standard JSON names such as `inter_splash` or `native_lang` and keeps them live after each fetch; pass it a map for names that differ in your app.
Your own `ad_config.json` replaces a unit written in code only for keys bound this way. A standard key the backend's `ad_remote_config` declares applies without a binding and outranks any unit written in code, including `stepNatives` and `splashInterstitialOldUser`, so there is no need to rebuild the config in `onRemoteFetched()`.
A base key declared `isEnable: false` is the placement's master switch and turns off every `_high*` floor with it, so the slot gets no ad unit and the flow skips it.
The sample's [OnboardKitSetup](../app/src/main/java/com/itg/template/app/OnboardKitSetup.kt) shows the complete mapping and native templates.

## Fullscreen page and onboarding exit ad

Configure these in the same `onboardKitConfig` block as your content. `StepId` is in
`io.onboardkit.core`; the other types below are in `io.onboardkit.config`.

```kotlin
// Include this among your content pages in steps(...).
AdFullScreenStepDefinition(
    StepId.FULL1,
    skipButtonStyle = FullScreenSkipStyle.CLOSE_ICON, // TEXT for “Skip”
    skipButtonPosition = FullScreenSkipPosition.RIGHT, // LEFT mirrors it to the other side
    skipButtonDelaySec = 1,
    autoNextEnabled = true,
    autoNextDelayMs = 3_000,
)

// Add these fields to your existing AdsConfig(...).
afterOnboardingInterstitial = InterstitialAdUnit("YOUR_INTERSTITIAL_UNIT_ID"),
afterOnboardingInterstitialEnabled = true,
```

The example above sets an X after 1 second and automatic advance after 3 seconds from
page selection (SDK defaults are 5 and 15 seconds); set `autoNextEnabled = false` for manual completion. Step ad return completes
the step by default, so these placements do not preload/show a replacement on click. Remote
`ob_skip_button_delay_sec >= 0` overrides the local skip delay; `-1` uses the local value.
`AdsConfig.fullScreenSkipStyle` sets the shared appearance for Full1/Full2/OB5. Standalone OB5 uses
its own 3-second skip and 15-second auto-dismiss defaults.

With `BehaviorConfig.lockPagerSwipe = false`, OB1 stays locked, OB2/OB3/OB4 permit swipe, and fullscreen
permits swipe only after its ad is shown for the current visit. Loading or failure keeps fullscreen
swipe locked; X, timeout and automatic no-fill completion still work. Forward swipe on the last
content page uses the same exit interstitial as its CTA when `swipeCompletesLastStep = true`.
`lockPagerSwipe = true` disables this last-page gesture as well.

`inter_after_ob3` is a separate placement from splash. The built-in provider preloads it on
pager entry and waits up to 8 seconds for a fill on completion. By default
(`AdsConfig.afterOnboardingInterstitialTiming = NextScreenTiming.UNDER_AD`) the next screen starts
underneath the ad; notification, widget and uninstall entries wait for dismissal. Set
`NextScreenTiming.AFTER_AD` (`io.onboardkit.ads`) to always wait.
`afterOnboardingInterstitialEnabled` switches this placement; a remote
`onboarding.exit_interstitial.enabled` or a delivered `ob_ads_inter_after_ob3_enabled` decides
instead, on or off. Set the local switch to `false` if your app owns this ad trigger; while remote
is silent this disables both automatic preload and show. Keep this placement out of your content
AutoBuffer group.

## App-open on return

Complete the [Ads app-open setup](../ads/README.md#app-open-on-return). `AdsConfig.fromAdConfig()`
already binds `appResume` to `open_resume`, and an `open_resume` in the backend's `ad_remote_config`
fills it without a binding. With a hand-built `AdsConfig` and no remote entry, add
`appResume = AdRemoteConfig.getInstance().tiersFor("open_resume").takeIf { it.isNotEmpty() }?.let { InterstitialAdUnit(tiers = it) }`
so both read the same `open_resume` placement.
Language and onboarding content pages allow a ready resume ad on a genuine background/return.
Splash, standalone fullscreen and survey screens are excluded; fullscreen pager pages,
page transitions and the language confirmation dialog temporarily block it. The return from a
click on an onboarding ad skips it too, unless remote sets `app_open.presentation.skip_after_ad_click`
to `false`.
Remove any app-owned exclusion of language/content Activities only if you want resume ads there.
The SDK manages loading and screen eligibility; no Activity lifecycle callback is needed.

## Optional integrations

- **Firebase:** `ob_*` flags are fetched by splash when Firebase is configured; a host without `ObSplashActivity` picks them up after `AdConfig.refresh()`. Until a fetch lands, the values Firebase last delivered apply, and keys it never sent leave your configuration in charge. A fetch that lands after the splash deadline still applies for the rest of the session. [ObRemoteKeys](src/main/java/io/onboardkit/remote/RemoteKeys.kt) lists the supported keys. For remote ad JSON or a GA4 sink, add [suite-firebase](../suite-firebase/README.md); installing an ad config source alone does not fetch it.
- **Paywall:** install [PayKit](../paykit/README.md) first, then set `paywallGate = OnboardKitPaywallGate()` in `OnboardingSdk.install` (`io.paykit.integration`). Leaving the gate unset skips paywalls. Follow the billing readiness step below when purchases and ads are both used.
- **Custom consent:** keep the default `onConsentRequired()` for UMP. A custom override must publish its CMP result with `ConsentCenter.setHostConsent(canRequestAds, personalized)` before returning. Returning `true` alone is not permission to request ads; `setCanRequestAds(false)` is a separate host restriction, and `true` only removes that restriction. Always call `super.onDestroy()` if you override it.
- **Custom UI / survey:** see [screen configuration](src/main/java/io/onboardkit/config/OnboardKitConfig.kt) and [QuestionConfig](src/main/java/io/onboardkit/config/QuestionConfig.kt). Only splash and content-step `layoutRes` overrides are supported; unsupported layout fields fail validation. Preserve IDs when overriding SDK resources.

**Purchases and ads:** `PayKit.install()` / BillingKit initialization starts purchase verification asynchronously; it does not mean premium has already been restored. The base `onInitBilling()` hook is empty.
Replace the minimal splash above with an override that awaits `Billing.awaitReady()` before the splash ad phase.
Calling `Billing` directly also requires `implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"` at the same tag; see [BillingKit](../billingkit/README.md).

```kotlin
import com.ads.module.billing.Billing
import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity() {
    override suspend fun onInitBilling() {
        val readiness = Billing.awaitReady()
        // Apply your app's policy for ReadyResult.Timeout / ReadyResult.Error.
    }
}
```

`Billing.awaitReady()` returns `Ready`, `Timeout` or `Error`; use the result for your app's error policy. The existing `SplashConfig.billingTimeoutMs` (5,000 ms by default) also bounds the whole hook, so the splash deadline may cancel it before a result returns; a timeout does not establish purchase status.
See the [sample splash](../app/src/main/java/com/itg/template/ui/component/splash/SplashActivity.kt) for the integration point.

For notification/widget/uninstall launches, target your splash with [SplashEntry](src/main/java/io/onboardkit/ui/splash/SplashEntry.kt):

```kotlin
import io.onboardkit.ui.splash.SplashEntry

val intent = SplashEntry.WIDGET.intent(context, SplashActivity::class.java)
    .putExtra("widget_action", "open_document")
```

The listener above forwards extras for `Completed`/`Skipped`. Read them in your destination's `onCreate` and `onNewIntent`.
Entries use `inter_noti`, `inter_widget` or `inter_uninstall`; a key that is missing or switched off falls back to the user's segment (`inter_splash_o` for returning users, `inter_splash` for new ones), and switching a segment off silences its entries too; these entries always navigate after the ad is dismissed, because their destination opens a screen of its own, which would cover an ad still on screen. A launcher start opens LFO after the ad on first open, and your app underneath it once onboarding is done.

## Troubleshooting

| Symptom | Check |
|---|---|
| Flow skips immediately | `install()` ran before `configure()` and neither `Result` failed |
| Flow ends without entering your app | Listener handles `Completed`, `Skipped` and `Aborted` |
| `no_provider` / `consent_not_granted` | Provider is installed; inspect `ConsentCenter.canRequestAds()` and the host restriction |
| Ad-only page absent | `fullScreenStepNative` or its `stepNatives` override is usable |
| Custom splash banner absent | Layout contains `ob_splash_ad_container` with `layout_banner_control` included |

Use `OnboardingSdk.setFlowLogging(true)` during integration (`OB_FLOW` in Logcat).
For a later language change, call `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)` (`io.onboardkit.ui.language`).

[Sample Application](../app/src/main/java/com/itg/template/app/GlobalApp.kt) · [Sample splash](../app/src/main/java/com/itg/template/ui/component/splash/SplashActivity.kt) · [MIT license](../LICENSE)

## Grouped remote settings

`ad_behavior_config` and `onboarding_config` control behavior and SDK ad presentation experiments: native templates, Skip/X style, CTA corner radius and LFO confirm appearance. Ad IDs, unit switches, CTA color/height/position/components, banner reload cadence and resume load delay remain in `ad_remote_config` / `ad_config.json` / `ad_config_debug.json`. App layout/resource references, system bars, orientation and progress indicators remain in host code. For every field, remote wins — the document's own field, then a legacy `ob_*` key the backend delivered — over your app-asset JSON at any scope, then options set in code, then bundled defaults; keys the backend never sent do not count. `AdsConfig.enabled = false` therefore yields to a remote `flow.ads_enabled = true`. Native frames follow the same order: a remote onboarding template, then `positionCTA` from the backend's `ad_remote_config`, then a template in your app asset, then `positionCTA` from your `ad_config.json`, then the host template. Preloaded ads use the current template when bound without another network load. `onboardKitConfig` defaults to `AdsConfig.fromAdConfig()`, which keeps standard placement bindings live after fetch without configuring the SDK again. See the [complete field/default reference](../partner-integration/remote-settings.vi.md). Missing or invalid fields preserve local options, and a fetched document logs the fields it dropped under the `AdLogicSettings` tag; failed fetches preserve the last valid snapshot.
