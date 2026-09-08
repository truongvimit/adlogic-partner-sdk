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
def sdkVersion = '5.1.2'
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

### Splash → Language preload experiment

`ob_splash_lfo_parallel_preload_enabled` is a Boolean, default **false**. Configure stable assignment in Firebase Remote Config/A/B Testing; the SDK does not randomize groups.

- **false / sequential (A):** preload LFO1 after the entire splash interstitial waterfall settles (loaded, failed, skipped), or its splash wait budget expires.
- **true / parallel (B):** preload LFO1 alongside splash loading once remote, the Language destination and request gates are resolved.

Only LFO1 moves. OB1 and LFO2 keep their existing triggers; `SAME_TIME`/`ALTERNATE` independently control remote-fetch versus splash-interstitial loading. A ready LFO1 binds, an in-flight request is joined, and a failed splash preload does not immediately retry on Language entry. Expired/empty inventory can load normally.

The mode and one launch attempt survive Activity recreation in memory. Fetch failure retains the cached remote snapshot. Console output is sufficient to compare runs: `splash_lfo attempt=<id> mode=sequential|parallel reason=<trigger>`; no extra experiment analytics are emitted.

`ob_splash_ad_budget_ms` (60,000 ms default) starts once after notification finishes/skips and splash resumes with focus. Banner waiting shares that deadline; elapsed background/recreation time is not reset, and late interstitial fill cannot reopen an expired opportunity. `ob_splash_notification_settle_ms` defaults to **0** and, when enabled, counts from the notification result. Existing minimum (3,000 ms), banner wait (0 ms), floors and per-tier network timeouts remain unchanged. Splash continues using the existing `show()` path.

### Partner integration notes

With `ObSplashActivity` and the supplied `ERainAdProvider`, keep the existing install/configure
flow. The SDK owns preload, notification, timers and handoff; no extra delay or splash
`loadAndShow()` call is needed. Native slots outside onboarding use the
[shared native manager and screen-scoped helper](../ads/README.md#native-preload-repeated-show-and-refresh).

The default **3 s minimum gates navigation, not interstitial presentation**. For launcher
`UNDER_AD`, an interstitial shown at ad-phase second 1 can still have splash underneath until
second 3; the next screen then opens beneath it. If shown at second 5, no minimum remains.
The **60 s budget bounds ad waiting after notification**, not total launch duration or the time
the user spends viewing the interstitial. Remote configuration can override these defaults.

If you inject your own [OnboardingAdProvider](src/main/java/io/onboardkit/ads/OnboardingAdProvider.kt),
implement the updated native contract: preload is idempotent per placement, joins pending loads
and skips usable unused fills; successful binding consumes that unused fill. Report terminal
preload failures through `isNativeLoadFailed()` so Language entry does not immediately retry
them (its compatibility default is `false`). `releaseNative()` ends the presentation while
preserving shared pending loads and unused fills. Recheck foreground eligibility before queued
requests start; `allowWhileVisible` only permits the visible splash beneath its notification
prompt. The supplied provider already handles these requirements.

## 3. Map ads and content to screens

Leave optional slots unset unless you need them; some slots inherit fallback units, as documented in [AdsConfig](src/main/java/io/onboardkit/config/AdsConfig.kt).

| Configuration | Screen |
|---|---|
| `splashBanner`, `splashInterstitial` | Splash banner / interstitial |
| `languageNative`, `languageDupNative` | First / replacement language native |
| `contentStepNative`, `stepNatives[StepId.OB1]` | Shared content native / per-step override |
| `fullScreenStepNative` | Ad-only step; skipped when no usable unit exists |

`defaultSteps()` creates OB1, OB2, OB3 (ad-only), OB4. For your copy and images, replace it with `steps(ContentStepDefinition(...), ...)`; see [step definitions](src/main/java/io/onboardkit/config/StepDefinition.kt).
Native/interstitial waterfalls accept `tiers = listOf(highId, fallbackId)` in request order; banners take one ID.

JSON names such as `inter_splash` or `native_lang` must be mapped into `AdsConfig`; the SDK does not infer every mapping from the field name.
Use `AdRemoteConfig.getInstance().tiersFor(key)` and rebuild the config after refreshed IDs arrive in `onRemoteFetched()`.
The sample's [OnboardKitSetup](../app/src/main/java/com/itg/template/app/OnboardKitSetup.kt) shows the complete mapping and native templates.

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

## Upgrading from 5.0.0

- Keep the existing `install → configure → splash` integration and update all module versions together.
- Remove any custom timeout that closes the SDK's UMP form or navigates while it is open. Do not grant consent from a timeout or boolean callback.
- Check notification ownership and portrait behavior against the defaults above.
- Native bind now reports `fo_ad_bound`; count actual ad displays with `ad_show`. Update dashboards that treated the old bind signal as an impression.
- Language/question native replacements keep the current creative while waiting. OB5 restarts its countdown after returning to foreground. No new host calls are required.

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

## Fullscreen Skip and automatic interstitial

Fullscreen steps (OB3) default to showing the close (X) button after **1 second** and advancing
after
**3 seconds from page selection**, including time after the device Home button is pressed.
No fill still skips the page immediately. Returning to the app does not restart the timer.
The deadline lives for the page visit; force-stop/process death is not a background timer.
Android may defer execution of a suspended process; a resumed page catches up to its deadline.
The pager can advance while stopped. If this is the final page, opening the next Activity,
paywall or interstitial waits for the task to resume; the completed page's timer does not restart.

```kotlin
steps(
    AdFullScreenStepDefinition(
        StepId.OB3,
        skipButtonStyle = FullScreenSkipStyle.CLOSE_ICON, // TEXT for “Skip”
        skipButtonDelaySec = 1,
        autoNextEnabled = true,
        autoNextDelayMs = 3_000,
    ),
)
ads = AdsConfig(
    fullScreenSkipStyle = FullScreenSkipStyle.CLOSE_ICON, // shared default for OB3 and OB5
    afterOnboardingInterstitial = InterstitialAdUnit("YOUR_AD_UNIT"),
    afterOnboardingInterstitialEnabled = false,
)
```

`skipButtonStyle = null` inherits `AdsConfig.fullScreenSkipStyle`, which defaults to `CLOSE_ICON`.
Set `TEXT` to display the word “Skip” instead. Both appearances use the
same timer and click action. `autoNextEnabled = false` restores manual completion.
The existing `ob_skip_button_delay_sec` remote key overrides the local Skip delay when
nonnegative; its new default `-1` inherits the page setting. An existing remote value (for
example `3`) still overrides it. Standalone OB5 keeps its 3-second Skip and 15-second
auto-dismiss defaults and its foreground countdown behavior.

`afterOnboardingInterstitialEnabled = false` disables **both automatic preload and show**
for `inter_after_ob3`, even when the unit is configured and the remote switch is true.
The remote key `ob_ads_inter_after_ob3_enabled` is still supported; both switches must be
true. The partner can show an interstitial at its own point using the Ads module directly.


## Resume during language and onboarding content

Language selection (including first open) and onboarding content pages now permit the configured
resume entry on a genuine background/foreground return, before onboarding completes. They use the
same SDK background delay, ready-only presentation, retained cache and bounded recovery as in-app
content; entering the flow or advancing a page does not request or show a resume ad.

Splash, dedicated fullscreen and Question screens remain excluded. The pager dynamically blocks
resume while an ad-only page is current, while scrolling, or after exit begins. The language
confirmation dialog and language exit also block resume. Finishing the fullscreen auto-next timer
does not queue a resume show: the next genuine eligible return is evaluated normally.

Existing partner Activity exclusions, consent, premium, placement flags, ad-click suppression and
active fullscreen ownership still apply. Partners that explicitly exclude LFO/onboarding classes
need to remove their own exclusions to use this expansion; SDK opt-in never clears partner policy.

The fullscreen three-second timer is not a policy guarantee. Google advises against displaying
app-open over other ads or immediately adjacent to them; see the
[AdMob implementation guidance](https://support.google.com/admob/answer/9341964?hl=en-GB).
