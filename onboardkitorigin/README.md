# OnboardKit

Splash → language → onboarding → optional question/paywall → your app.
The SDK owns screen transitions, ad preloading and saved progress; your app supplies content and the final destination.

[Tiếng Việt](README.vi.md) · [हिन्दी](README.hi.md)

## Before you start

- Use minSdk 24, compileSdk 36 and JDK 17. Follow the [shared build setup](../README.md) and use the same published tag for every module.
- For the built-in ad provider, first complete the [ads guide](../ads/README.md): AdMob/Meta manifest values, ad config assets and `ERainAd.init()` in your Application.
- Install `Tracker` and a sink before OnboardKit if you need the funnel; see [Trackkit](../trackkit/README.md).
- Add both dependencies below. OnboardKit exports Trackkit, but partner code using `com.ads.module.*` needs an explicit `ads` dependency. Firebase and PayKit setup are optional.

```groovy
def sdkVersion = '5.2.1'
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
Use `NEW_TASK` without `CLEAR_TASK` for the final handoff: the splash ad may still own its Activity.

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
        android:configChanges="orientation|screenSize|keyboardHidden"
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

Defaults to account for:

- `notificationPermissionEnabled = true`: Android 13+ / target 33+ requests notifications after consent. A grant or a recorded automatic request result skips later prompts; denial still continues. Set it to `false` if your app owns this prompt.
- `noInternetPromptEnabled = true`: splash asks the user to connect before continuing. Set it to `false` if your app should allow an offline start.
- `lockPortrait = true`: SDK screens, including your splash subclass, are locked to portrait. Landscape apps must set it to `false` and review merged manifest orientation rules too.
- `consentTimeoutMs = 20_000`: the default SDK-owned UMP flow does **not** time out the user's answer. The budget still bounds a custom hook when no SDK-owned consent flow is resolving.
- Authorized splash ads can load beneath the notification prompt while splash remains visible. Home blocks new requests. The minimum display time begins once the ad phase starts and overlaps loading/notification UI; a ready interstitial can show before that minimum, while navigation waits only its remaining time.

### Splash and language options

The supplied splash/provider owns consent, preloading and handoff; keep app-side delays out of
this flow. Configure only the defaults you need to change:

| Option | Default / use |
|---|---|
| `SplashConfig.minDisplayTimeMs` | 3000 ms; minimum before navigation, not before showing a ready interstitial. |
| `ob_splash_ad_budget_ms` | 60000 ms of ad waiting, starting after notification completes and splash has focus. |
| `ob_splash_lfo_parallel_preload_enabled` | `false`: preload the first language native after the splash waterfall settles or its wait expires. `true`: preload alongside splash ads. |
| `LanguageConfig.tapHintEnabled` + `ob_show_language_tap_hint` | Both must be enabled to show the language selection hand. |
| `ob_language_tap_hint_delay_sec` | 3 seconds; `0` shows immediately. Selecting a language cancels the hint; SETTINGS/preselected language hides it. |

`ob_*` values are optional Firebase Remote Config parameters. See
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

`defaultSteps()` creates OB1, OB2, OB3 (ad-only), OB4. For your copy and images, replace it with `steps(ContentStepDefinition(...), ...)`; see [step definitions](src/main/java/io/onboardkit/config/StepDefinition.kt).
Native/interstitial waterfalls accept `tiers = listOf(highId, fallbackId)` in request order; banners take one ID.

JSON names such as `inter_splash` or `native_lang` must be mapped into `AdsConfig`; the SDK does not infer every mapping from the field name.
Use `AdRemoteConfig.getInstance().tiersFor(key)` and rebuild the config after refreshed IDs arrive in `onRemoteFetched()`.
The sample's [OnboardKitSetup](../app/src/main/java/com/itg/template/app/OnboardKitSetup.kt) shows the complete mapping and native templates.

## Fullscreen page and onboarding exit ad

Configure these in the same `onboardKitConfig` block as your content. `StepId` is in
`io.onboardkit.core`; the other types below are in `io.onboardkit.config`.

```kotlin
// Include this among your content pages in steps(...).
AdFullScreenStepDefinition(
    StepId.OB3,
    skipButtonStyle = FullScreenSkipStyle.CLOSE_ICON, // TEXT for “Skip”
    skipButtonDelaySec = 1,
    autoNextEnabled = true,
    autoNextDelayMs = 3_000,
)

// Add these fields to your existing AdsConfig(...).
afterOnboardingInterstitial = InterstitialAdUnit("YOUR_INTERSTITIAL_UNIT_ID"),
afterOnboardingInterstitialEnabled = true,
```

The fullscreen page defaults to an X after 1 second and automatic advance after 3 seconds from
page selection; set `autoNextEnabled = false` for manual completion. Remote
`ob_skip_button_delay_sec >= 0` overrides the local skip delay; `-1` uses the local value.
`AdsConfig.fullScreenSkipStyle` sets the shared appearance for OB3/OB5. Standalone OB5 uses
its own 3-second skip and 15-second auto-dismiss defaults.

`inter_after_ob3` is a separate placement from splash. The built-in provider preloads it on
pager entry and waits up to 8 seconds for a fill on completion, then continues after dismissal
or a skip. Both `afterOnboardingInterstitialEnabled` and remote `ob_ads_inter_after_ob3_enabled`
must be true. Set the local switch to `false` if your app owns this ad trigger; this disables
both automatic preload and show. Keep this placement out of your content AutoBuffer group.

## App-open on return

Complete the [Ads app-open setup](../ads/README.md#app-open-on-return), then add
`appResume = InterstitialAdUnit("YOUR_APP_OPEN_UNIT_ID")` to `AdsConfig` using the same unit.
Language and onboarding content pages allow a ready resume ad on a genuine background/return.
Splash, standalone fullscreen and survey screens are excluded; fullscreen pager pages,
page transitions and the language confirmation dialog temporarily block it.
Remove any app-owned exclusion of language/content Activities only if you want resume ads there.
The SDK manages loading and screen eligibility; no Activity lifecycle callback is needed.

## Optional integrations

- **Firebase:** `ob_*` flags are fetched by splash when Firebase is configured; otherwise cached/default flags apply. [ObRemoteKeys](src/main/java/io/onboardkit/remote/RemoteKeys.kt) lists the supported keys. For remote ad JSON or a GA4 sink, add [suite-firebase](../suite-firebase/README.md); installing an ad config source alone does not fetch it.
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
Entries use `inter_noti`, `inter_widget` or `inter_uninstall`, falling back to the normal splash unit; these entries navigate after the ad, while a launcher start normally opens the next screen underneath it.

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
