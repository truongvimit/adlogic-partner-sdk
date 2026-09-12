# Ads + OnboardKit integration

[← Choose a guide](README.md)

Sample flow: **Splash → language (LFO) → content 1 → content 2 → fullscreen native → content 3 → end-of-onboarding interstitial → MainActivity**. The SDK owns consent, notifications, ads and navigation; an ad shows only when it is eligible and filled.

Do steps 1–6 and replace the **package, app details, content/images and destination screen**. The code keeps the SDK defaults; the JSON keeps the example debug configuration. Fill in the app token to enable Adjust; Firebase, app-open and purchases are in the [optional tables](#7-configure-only-what-your-app-needs).

## 1. Add the dependencies

Requires JDK 17, `minSdk 24+` and `compileSdk 36+`; AGP/Kotlin follow [versions.gradle](../versions.gradle) and the [Gradle wrapper](../gradle/wrapper/gradle-wrapper.properties). Merge the Groovy below into your existing blocks.

In `settings.gradle`, add the missing repositories:

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
        maven { url 'https://artifact.bytedance.com/repository/pangle/' }
        maven { url 'https://android-sdk.is.com/' }
        maven { url 'https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea' }
    }
}
```

Replace `<published-tag>` with a [published tag](https://github.com/truongvimit/adlogic-partner-sdk/tags) in your app project's root `gradle.properties`:

```properties
adlogicSdkVersion=<published-tag>
```

Every module reads this same property.

In `app/build.gradle`:

```groovy
android {
    compileSdk 36
    defaultConfig { minSdk 24 }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = '17' }
    buildFeatures { buildConfig = true }
    // Keep the translations in the app bundle so the language picker works offline.
    bundle { language { enableSplit = false } }
}

def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
}
```

Keep your app's `targetSdk` (the repo uses 36), and use your existing AndroidX/AppCompat setup and `MainActivity`. Sync Gradle, then continue. The SDK already bundles GMA/UMP, mediation, Trackkit and consumer rules for a `minifyEnabled` release: do not re-add those dependencies, `MobileAds.initialize()` or the example's `app/proguard-rules.pro`.

## 2. Add the IDs and the two JSON files

### `app/src/main/res/values/id_ads.xml`

```xml
<resources>
    <string name="admob_app_id" translatable="false">ca-app-pub-3940256099942544~3347511713</string>
    <string name="facebook_app_id" translatable="false">YOUR_META_APP_ID</string>
    <string name="facebook_client_token" translatable="false">YOUR_META_CLIENT_TOKEN</string>
    <!-- Fill in the app token to enable Adjust; leave empty if unused. -->
    <string name="adjust_token" translatable="false"></string>
    <!-- Event token from Adjust; only needed for the ad-revenue event. -->
    <string name="event_token" translatable="false"></string>
    <!-- Purchase event token; only needed when the app has IAP. -->
    <string name="adjust_event_token_purchase" translatable="false"></string>
</resources>
```

The test AdMob App ID contains **`~`**. Replace `YOUR_META_*` with the credentials your team provides before running: `ERainAd.init()` always initializes the Facebook SDK, even with Adjust off. Keep all three Adjust fields; leave them empty while unused, and fill in the real tokens per the [Adjust table](#adjust-tokens-and-verification) when you do use them.

### `app/src/main/assets/`

Copy both files into `assets` under exactly these names (create the folder if it is missing):

- **[ad_config.json](examples/ads-onboarding/ad_config.json):** the release configuration; every ID is currently a test ID.
- **[ad_config_debug.json](examples/ads-onboarding/ad_config_debug.json):** the debug configuration; keep the test IDs.

Ad unit IDs contain **`/`**. Each file holds **45 entries**, like the [debug example](../app/src/main/assets/ad_config_debug.json), covering style, UA, app-resume delay and waterfalls; the interstitials use test ID `1033173712` and the native high tier uses the native video test unit. The 10 OB slots are listed below; the remaining keys are for your app screens and do not create a display position by themselves. Sample values may differ from the parser defaults; see the [JSON field table](#fields-in-the-sample-json).

| JSON key | Position | Maps into `AdsConfig` in step 4 |
| --- | --- | --- |
| `banner_splash` | Splash banner | `splashBanner` |
| `inter_splash` | Interstitial when leaving splash | `splashInterstitial` |
| `native_lang` | First language native | `languageNative` |
| `native_lang_alt` | Replacement native after the first language selection | `languageDupNative` |
| `native_popup_lang` | Native in the language confirmation popup | `languageConfirmNative` |
| `native_ob1` | Content 1 — `StepId.OB1` | `stepNatives[StepId.OB1]` |
| `native_ob2` | Content 2 — `StepId.OB2` | `stepNatives[StepId.OB2]` |
| `native_fs` | Ad-only page — `StepId.OB3` | `fullScreenStepNative` |
| `native_ob3` | Content 3 — **`StepId.OB4`** | `stepNatives[StepId.OB4]` |
| `inter_after_ob3` | After all of onboarding, before the destination screen | `afterOnboardingInterstitial` |

Keep this mapping: `native_ob3` is content 3 at **OB4**; **OB3** is the fullscreen page. `inter_after_ob3` shows after all of onboarding.

The SDK picks the file by debuggable. A debug build with a missing or invalid JSON falls back to the real file; it does not substitute test IDs for live IDs. A debug asset that loads successfully blocks remote overrides by default.

The sample IDs come from [Google demo ad units](https://developers.google.com/admob/android/test-ads#demo_ad_units) and [AdMob App ID](https://developers.google.com/admob/android/quick-start). The fullscreen page uses a **native ID**. The sample shares one test ID per format; production needs separate IDs to split configuration, style and reporting per placement.

## 3. Prepare the onboarding content

Use your existing `app_name` and launcher icon. Add these six strings to `app/src/main/res/values/strings.xml` and replace the copy with your product's:

```xml
<resources>
    <string name="onboarding_title_1">Welcome</string>
    <string name="onboarding_des_1">Introduce the app’s main benefit.</string>
    <string name="onboarding_title_2">Easy to start</string>
    <string name="onboarding_des_2">Show the action the user needs to know.</string>
    <string name="onboarding_title_3">Ready to go</string>
    <string name="onboarding_des_3">Invite the user to get started.</string>
</resources>
```

Add translations in `values-<language>/strings.xml`, including the `ob_*` keys from [ob_strings.xml](../onboardkitorigin/src/main/res/values/ob_strings.xml), because the SDK ships English only. In step 4, replace the three `imageRes` values with your app's images, or copy images [1](../app/src/main/res/drawable-nodpi/img_onboard_sample_1.png), [2](../app/src/main/res/drawable-nodpi/img_onboard_sample_2.png), [3](../app/src/main/res/drawable-nodpi/img_onboard_sample_4.png) from the example into `app/src/main/res/drawable-nodpi/` to try it out.

The SDK already supplies the layouts/Activities for LFO, the popup, OB and the native ads.

<details>
<summary>Optional: use your own layouts</summary>

Only `SplashConfig.layoutRes` and `ContentStepDefinition.layoutRes` accept custom layouts; other layout fields are rejected by `getOrThrow()`. Copy the [content layout](../onboardkitorigin/src/main/res/layout/ob_fragment_content_step.xml) and keep its root, IDs and view types; a broken contract logs to `OB_FLOW` and the SDK falls back to the default layout. For the [splash](../onboardkitorigin/src/main/res/layout/ob_activity_splash.xml), missing IDs are ignored but every ID you keep must have the right type; a banner needs `ob_splash_ad_container` containing `<include layout="@layout/layout_banner_control" />`.

</details>

## 4. Declare placements and configure OnboardKit

### `AppAdPlacement.kt` — your app's placement catalog

Copy [AppAdPlacement.kt](examples/ads-onboarding/AppAdPlacement.kt) into your app package, for example `app/src/main/java/com/example/app/`. The file holds **35 base keys**: the 10 OB slots and your app slots. The SDK finds the `_high`, `_high1`… floors itself; floors need no constants.

**A placement key is the only identity an ad position has.** Ad unit IDs cannot tell positions apart: the sample JSON declares 45 placements with only **8 ad unit IDs** — one native test ID serves 25 placements — and production payloads routinely reuse one unit across screens. Everything the SDK keys by placement: the interstitial cache, the frequency clock, the AutoBuffer group, native preload, and every `ad_request` / `ad_impression` / `ad_skipped` your dashboard slices by. So the key must be written in exactly one place.

A mistyped raw string does not fail either: `AdRemoteConfig.unit()` logs a warning and returns a disabled placeholder, and the slot silently never fills. A constant turns that into a compile error.

- `AppAdPlacement.NATIVE_HOME` is the **key** `native_home`; use it when loading and showing.
- The JSON holds the **ad unit IDs and config**; the constants hold only keys.
- `io.onboardkit.ads.AdPlacement` is fixed inside the SDK; your app adds its slots to `AppAdPlacement`.

A new slot needs a constant, the same key in both JSON files, and load/show code on the app screen. No other adapter file is needed for ads: the entry points take the constant and read the config themselves.

### `OnboardKitSetup.kt` — wire the OB keys into the SDK

Copy [OnboardKitSetup.kt](examples/ads-onboarding/OnboardKitSetup.kt) into the same package. Change `com.example.app` in every Kotlin file; import `R`, `BuildConfig` and `MainActivity` if they live in another package. The sample already wires the three content pages plus the fullscreen page, the enabled ad floors and the templates from the JSON. Step 5 calls `configure()` after `OnboardingSdk.install`.

The sample leaves `contentStepNative` unset: with every `native_ob2` floor disabled, page 2 does not borrow page 1's ad. `languageDupNative = null` is the exception and falls back to the first native; turn the replacement off with `secondNativeOnSelectEnabled = false`.

Templates follow `positionCTA`: `languageTemplate` reads `native_lang`, and `contentStepTemplate` reads `native_ob1` for every content page. The fullscreen page and the popup use fixed layouts. The provider applies CTA color, CTA height and component visibility from the ad config.

## 5. Initialize in your Application

Copy [PartnerApp.kt](examples/ads-onboarding/PartnerApp.kt), or merge its `onCreate`, listener and two constants into your existing Application, keeping your base class/Hilt. Change `MainActivity` to your destination screen.

The sample already wires everything:

| Part | What your app replaces |
| --- | --- |
| Tracker, JSON, ERainAd, ERainTuning, OnboardKit | Keep the order from the sample; add a sink only if you need analytics. |
| Ads/Adjust environment | The sample picks debug/release from `BuildConfig.DEBUG` itself. |
| Adjust | Fill in the tokens in `id_ads.xml`; leave them empty while unused. |
| Completion listener | The destination screen, and how the language is saved if your app already has its own mechanism. |

Order: **Tracker → JSON → ERainAd → ERainTuning → install OnboardKit → configure**. The Meta token is read from the manifest. Tracker needs a sink to send events to Firebase or your backend.

`ERainTuning.install()` sets the `onComplete` timing for [app-screen interstitials](#app-screen-interstitial-with-a-placement-constant) and drops app-open after an ad click; it does not change the OB flow.

The listener routes `Completed`/`Skipped`/`Aborted` to your main screen; change `Aborted` if you need to. Keep `NEW_TASK` without `CLEAR_TASK` so the main screen can open underneath a returning user's splash interstitial or the end-of-onboarding interstitial. The SDK saves completion and navigates by itself; add no timers.

**Your app screens must apply the locale themselves.** The sample stores `Completed.selectedLanguage` (`en-US`, `es`). If your app has no locale mechanism yet, add this to your base Activity/MainActivity and import `android.content.Context`, `android.content.res.Configuration` and `java.util.Locale`. Change `PartnerApp` to your real Application name:

```kotlin
override fun attachBaseContext(newBase: Context) {
    val code = newBase.getSharedPreferences(PartnerApp.PREFS, Context.MODE_PRIVATE)
        .getString(PartnerApp.LANGUAGE, null)
        ?: return super.attachBaseContext(newBase)
    val config = Configuration(newBase.resources.configuration)
        .apply { setLocale(Locale.forLanguageTag(code)) }
    super.attachBaseContext(newBase.createConfigurationContext(config))
}
```

Read the saved choice with `OnboardingSdk.selectedLanguage()` (suspend). The SDK does not translate your app or update its preferences.

### Adjust: tokens and verification

The sample uses **`com.ads.module.config.AdjustConfig`**. The SDK owns Adjust, its lifecycle/attribution and revenue; fill in the resources only — do not add `Adjust.initSdk()` or a Tracker sink for Adjust.

| Resource in `id_ads.xml` | Assigned to | What to fill in |
| --- | --- | --- |
| `adjust_token` | `AdjustConfig(true, token)` | The app token; a value enables Adjust, empty disables it. |
| `facebook_app_id` | `fbAppId` | The same Meta App ID as in the manifest, used for the Meta integration through Adjust. |
| `event_token` | `eventAdImpression` | A 6-character event token for paid impressions; fill it in only when you need it. |
| `adjust_event_token_purchase` | `eventNamePurchase` | The 6-character purchase event **token**, not the event name; leave it empty until you have IAP. |

The SDK already calls the Adjust ad-revenue API. `event_token` additionally sends the revenue as an event for Meta/TikTok…; do not add both sources together in reporting. An empty token is skipped, and revenue is not re-sent from an app callback.

Debug uses the Adjust **sandbox**, release **production**. Check the `ERainAdjust` log for `Adjust initialised (sandbox)` or a token error, then cross-check sessions/events in Adjust. Testing purchases needs billing and a test transaction, not just the event token.

## 6. Register the splash as launcher

Create `SplashActivity.kt` in the same package:

```kotlin
package com.example.app

import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity()
```

Merge this into `app/src/main/AndroidManifest.xml`, adjusting your existing class names/entries. Move the old launcher to the splash and keep only one launcher:

```xml
<application android:name=".PartnerApp">
    <meta-data
        android:name="com.google.android.gms.ads.APPLICATION_ID"
        android:value="@string/admob_app_id" />
    <meta-data
        android:name="com.facebook.sdk.ApplicationId"
        android:value="@string/facebook_app_id" />
    <meta-data
        android:name="com.facebook.sdk.ClientToken"
        android:value="@string/facebook_client_token" />

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

Keep `configChanges` so a dark-mode or font-size change does not recreate the splash. The SDK already declares its Activities and the network/`AD_ID`/`POST_NOTIFICATIONS` permissions. Check the Application, launcher, metadata and theme in the **Merged Manifest**; replace any old `${app_id}` entry with the resource from the sample.

The splash handles UMP/notifications, ads and navigation itself. You do not need to override lifecycle callbacks, call consent or `OnboardingSdk.start()`, or preload/show anything for OB.

Steps 1–6 are enough to run the standard flow. Verify it with the [checklist](#8-final-checks); read the sections below only when you need to change behavior or add ads to your app screens.

## 7. Configure only what your app needs

### Flow defaults

Add only the options you need to change to the `onboardKitConfig { ... }` block in step 4; call `ERainAd`/`ConsentCenter` where the table says. `ob_*` keys belong to Firebase Remote Config and are **not in the ad JSON**; without Firebase the cached/default values apply.

| Behavior | Default | Change it only when / where to change it |
| --- | --- | --- |
| Splash screen | SDK layout, at least 3 seconds from the start of ad loading | `ob_splash_min_display_ms` (3000) overrides `SplashConfig.minDisplayTimeMs` while greater than 0; only a remote `0` uses the local value. Without Firebase it is always 3000 ms |
| Opening the next screen after the splash interstitial | The interstitial shows after the minimum display time. First-open LFO: wait for dismissal. Launcher → app / returning-user question: open underneath the ad. Notification/widget/uninstall: wait for dismissal | Override `SplashActivity.nextScreenTiming()`: `NextScreenTiming.AFTER_AD`/`UNDER_AD` (`io.onboardkit.ads`), or `super.nextScreenTiming()` to keep the default |
| Waiting for the splash ad | Up to 60 seconds, after the notification step and once splash has focus | Remote `ob_splash_ad_budget_ms`; do not add a timer of your own |
| Fetching and loading ads | `AdLoadStrategy.ALTERNATE`: wait for remote before loading | `SplashConfig.adLoadStrategy`, when your app accepts using the current IDs before the fetch |
| Preloading the first LFO native | After the splash interstitial waterfall settles or its budget expires | Remote `ob_splash_lfo_parallel_preload_enabled = true` if you need parallel loading |
| No network | Ask the user to connect and do not continue | `SplashConfig.noInternetPromptEnabled = false` to start offline through the UMP fallback below; the next splash asks for UMP again |
| Consent | 20-second network timeout; the form itself waits for the user | In your Application: `ConsentCenter.configure(ConsentOptions(timeoutMs = ...))` (`com.ads.module.consent`). Keep the SDK hook; `SplashConfig.consentTimeoutMs` does not change the UMP timeout |
| The UMP form during QA | Debuggable: every device counts as EEA (`setForceTesting`), no hashed ID needed; release: real geography | `ConsentOptions(debug = false)` to debug against real geography. `configure` replaces every option, so to change the timeout as well pass one `ConsentOptions(timeoutMs = ..., debug = false)` |
| Notifications | Requested after consent on Android 13+ / target 33+; a denial still continues and is not asked again | `SplashConfig.notificationPermissionEnabled = false` if your app sends no notifications or owns the prompt |
| Language | 21 languages, hand hint after 3 seconds, confirmation hidden before a selection | `LanguageConfig.languages`: keep the languages you have translated; `tapHintEnabled` and `confirmVisibleBeforeSelect` also need their matching remote flags enabled |
| Back on LFO | Nothing selected: Back is ignored. After a selection: Save appears and the language screen stays | `LanguageConfig.saveButtonOnBackEnabled = false`: ignore Back after a selection too. In SETTINGS, Back closes the screen |
| Replacing the native after a language selection | Enabled; the first native stays until the replacement ad can bind | `LanguageConfig.secondNativeOnSelectEnabled = false` to turn it off |
| Language popup | From the fourth tap onward, including re-selecting an item; its native is first requested when the popup opens | `LanguageConfig.confirmDialogOnReselectEnabled = false` to turn it off; SETTINGS shows no popup |
| Native template | SDK: CTA at the bottom on LFO, CTA at the top on content; the sample JSON picks `BOTTOM` for both through the step 4 mapping | Change `positionCTA` on `native_lang` / `native_ob1`, or the fallback in the setup |
| System bars | Status/caption bars shown, navigation bar hidden | `SystemBarConfig(showStatusBar, showNavigationBar, showCaptionBar)` |
| Native click and return on OB | Advances the step (`BehaviorConfig.adClickReturnCompletesStep = true`); OB/OB5 disable the replacement preload on click | `adClickReturnCompletesStep = false` to stay on the page; do not re-enable click preload in the provider |
| Native click on LFO/popup or an app screen | Preloads as soon as the ad is clicked/opened; on return it binds a ready ad or waits for the in-flight request | `NativeAdConfig.reloadOnAdClick = true` by default, independent of timed refresh; [app-screen native example](#app-screen-native-with-a-placement-constant) |
| Relaunching with the flow unfinished | Runs Splash → LFO → OB again; OB is skipped only once the whole flow completes | No app-side first-open flag or checkpoint is needed |
| Fullscreen native page | X button after 1 second, auto-next 3 seconds after the page is selected; time in background still counts | The fields of `AdFullScreenStepDefinition`; remote `ob_skip_button_delay_sec = -1` keeps the local delay |
| End-of-onboarding interstitial | Preloaded on pager entry, waits up to 8 seconds for a fill on completion; the next screen opens underneath the ad. Notification/widget/uninstall: wait for dismissal | `AdsConfig.afterOnboardingInterstitialTiming = NextScreenTiming.AFTER_AD` to always wait for dismissal; `afterOnboardingInterstitialEnabled = false` if your app owns it. Keep it out of `InterstitialAutoBuffer` |
| OB navigation | Pager swipe locked; a forward swipe on the last page can still complete it; Back goes one page back and exits the app on the first page; portrait locked | `BehaviorConfig.lockPagerSwipe`, `swipeCompletesLastStep`, `backNavigatesBack` (`false`: Back always exits the app), `lockPortrait`; a landscape app must change the manifest too |
| Interstitial interval | `ERainAdConfig.intervalInterstitialAd = 0` (no limit); it applies to the `InterstitialAutoBuffer` group only, not to splash/OB or interstitials you load yourself | Set it before init, or use `ERainAd.getInstance().setIntervalInterstitialAd(seconds)` |
| Interstitial click cap | Off (`0`) | `ERainAd.getInstance().setMaxClickAdsPerDay(n)`: at most `n` clicks per ad unit per 24 hours, then loading/showing stops. Call it when needed, usually after the remote fetch |
| OB5, question, paywall, app-open | `ob_enable_step_ob5 = false`. With OB5 on: it opens underneath the final interstitial if its native is loaded, and is skipped otherwise. A null `ob5Native` uses `fullScreenStepNative` (`native_fs`). The other features are not wired | `AdsConfig.ob5Native` to give it its own ID; wire the question/paywall/app-open only when you need them |

A UMP error or timeout can allow an **attempted request** in-process through the [AdLogic fallback](../ads/src/main/java/com/ads/module/consent/ConsentCenter.kt); it grants no consent and guarantees no fill. A host that turns ads off still wins; do not infer request permission from a timer or from personalization.

### Fields in the sample JSON

Both JSON files keep the example debug fields and values; only the interstitials were normalized to the test ID.

| Field | Value in the sample | How it is used / where it applies |
| --- | --- | --- |
| `id` | A test ad unit of the right format | Replace the IDs in the real file before release; do not change the placement keys. |
| `isEnable` | As in the example: mostly `true`, welcome `false` | Turns a placement on or off. The base key is the master switch: `false` on the base key turns off the whole waterfall. |
| `enable_ua_check` | Both `true` and `false` occur | `true` allows paid/non-organic only; the SDK applies it on load and on show, app-resume included. An install counts as organic until Adjust returns attribution, so without Adjust you must set `false` on any app slot that has to show. The OB provider does not apply the JSON UA gate, **except for `inter_after_ob3`** — that key is also a JSON key, so the SDK applies both `isEnable` and `enable_ua_check` to it; setting `true` turns off the end-of-onboarding interstitial for organic installs. |
| `reloadIntervalSeconds` | Banner: `30` | Parsed only; the helpers ignore it and it changes no refresh, splash included. For refresh, see [App-screen banner](#additional-integrations). |
| `colorCTA` | `"default"` | Keeps the template color; set a color when you need a custom native. |
| `heightCTA` | `45` for ordinary natives, `36` for the popup | CTA height in dp; the SDK uses `40` when the field is absent and clamps the value to 36–52 when applying it. |
| `positionCTA` | `"BOTTOM"` or `null` | The step 4 mapping picks the LFO/content template from the base key. `null` uses the SDK fallback; the fullscreen page and popup keep their own layouts. |
| `components` | `["icon_headline", "body", "media", "cta"]` | A missing block is hidden; an empty array shows everything. OB changes visibility only; an [app-screen native](#app-screen-native-with-a-placement-constant) also uses the order when `positionCTA: null`. |
| `app_resume_load_delay_ms` | `open_resume`: `2000` | How long to wait before loading the app-open ad after the app goes to background; it only takes effect once app-resume is enabled. |

The waterfall reads `_high`, `_high1`… and then the base key; you can also use `ids` to declare several floors in one entry. Duplicate IDs are dropped; use separate IDs when you need to verify an individual floor or style.

### App-screen native with a placement constant

<details>
<summary>Open the native and preload example for app screens</summary>

Call this on your app screen after consent, with the `AppCompatActivity` resumed; `container` is a `FrameLayout`. OB handles its own natives.

**Without Adjust:** set `enable_ua_check` on `native_home` to `false` in **both JSON files** so the sample slot can show; keep the test IDs during QA.

```kotlin
import com.ads.module.helper.adnative.NativeAdHelper

NativeAdHelper.forPlacement(this, this, AppAdPlacement.NATIVE_HOME, container)
```

The SDK resolves the placement's waterfall, `isEnable`, `enable_ua_check` and CTA style itself. Pass `layoutRes` to change the template; the default is `com.ads.module.R.layout.custom_native_admob_medium` (no media — use `custom_native_admob_free_size` when you need media). A custom layout must keep its `NativeAdView` root, `ad_container`, `block_icon_headline`, the asset IDs and the Ad badge.

Keep one helper per slot/view and call `show()` to show it again. A Fragment passes its Activity plus `viewLifecycleOwner`. `reloadOnAdClick` is on by default; turn it off only when your app navigates away itself on click-return.

Preload for Main: `NativeAdManager.preload(applicationContext, AppAdPlacement.NATIVE_HOME, NativeAdConfig.forPlacement(AppAdPlacement.NATIVE_HOME, layoutRes))` in `SplashActivity.onRemoteFetched()`. A helper on the same placement picks that ad up when it shows; an ad older than 60 minutes is reloaded. See [Native preload](../ads/README.md#native-preload-repeated-show-and-refresh).

</details>

### App-screen interstitial with a placement constant

<details>
<summary>Open the interstitial preload/show example for app screens</summary>

Preload after consent and show at a new navigation opportunity. Do not apply this sample or AutoBuffer to `inter_splash` or `inter_after_ob3`; OB owns those.

```kotlin
import com.ads.module.helper.interstitial.InterstitialAdManager

InterstitialAdManager.load(applicationContext, AppAdPlacement.INTER_BACK)

InterstitialAdManager.show(this, AppAdPlacement.INTER_BACK) { goNext() }
```

Use the lambda when all you do is navigate; take the `InterShowCallback` overload when you also need `onShowed`/`onClosed`/`onSkipped`/`onClicked`. Your app needs no wrapper file.

The SDK resolves the placement's waterfall, `isEnable`, `enable_ua_check`, consent/premium, interval and readiness itself. Navigate only from `onComplete`; it runs exactly once, including when there is no ad or the show fails. Do not use `onClosed` and do not pre-check `canShow()`. `load` does not request again while a load is in flight or an ad is cached. With an ad ready, a dialog runs for about 800 ms before the show.

By default the callback runs after the ad is dismissed. Add `nextAction = InterNextAction.UnderAd` to run the callback as soon as the ad shows — use it only for a `startActivity` that opens an ordinary screen, never with `finish()` or to start camera/audio/video underneath the ad.

To keep an interstitial ready automatically: [InterstitialAutoBuffer](../ads/README.md#automatic-interstitial-preload) — `configure` after `ERainAd.init`, `start` on the first content screen; show as above. Interval and click cap are in the [defaults table](#flow-defaults).

</details>

### Additional integrations

| Need | Default / how to configure |
| --- | --- |
| Turn off a slot | `isEnable: false` on the **base key** turns off the whole waterfall, for every format; testing an asset change needs a restart. The second LFO native has a fallback from step 4. |
| Add waterfall floors | `<key>_high`, `<key>_high1`…`<key>_high9`, then the base key. The splash banner uses only the first ID of `banner_splash` and reads no separate floors. |
| CTA / native style | Keep every field as in the example; the [JSON field table](#fields-in-the-sample-json) explains the values and where they apply. Step 4 already wires `positionCTA` into the native templates. |
| App-screen banner | `BannerAdHelper.forPlacement(this, this, "banner_home", container)`; add a `bannerType` argument, for example `BannerType.Collapsible()` ([the types](../ads/src/main/java/com/ads/module/helper/banner/BannerType.kt)). To change the type: `flagUserEnableReload = false`, `cancel()` the old helper, then create a new one. SDK refresh requires disabling AdMob refresh on every floor, then building `BannerAdConfig.forPlacement("banner_home", bannerType, canReloadAds = true)` and passing it to the `BannerAdHelper` constructor. |
| UA / Adjust | Fill in the resources and use the wiring from [step 5](#adjust-tokens-and-verification). `enable_ua_check` keeps the example values; see the JSON field table for where it applies. |
| JSON from Firebase | [Set up Firebase](firebase-integration.md), then `AdConfig.install(FirebaseAdConfigSource())` after the assets in your Application. Publish the String `ad_remote_config` holding the **entire JSON** to replace the current config; add the splash hook below. |
| Firebase Analytics | Add `suite-firebase` and register `Tracker.addSink(FirebaseSink())` right after `Tracker.install`; choose the consent policy from the [Firebase guide](firebase-integration.md#initial-consent). |
| App-open on return | Not enabled; the JSON already contains `open_resume`, but adding the JSON alone does not enable the feature. Follow [App-open on return](#app-open-on-return). |
| App with premium / paywall | Follow [BillingKit](billing-integration.md) / [PayKit](paywall-integration.md) and the [hook that awaits billing before ads](../onboardkitorigin/README.md#optional-integrations). `onInitBilling()` is empty by default; calling billing install does not mean premium has been restored. |
| Language change from Settings | `registerForActivityResult(StartActivityForResult())`, then `launch(ObLanguageActivity.intentFor(activity, LanguageScreenMode.SETTINGS))` (the types are in `io.onboardkit.ui.language`). On `RESULT_OK`: read `ObLanguageActivity.RESULT_LANGUAGE_CODE`, save it as in step 5 and call `recreate()`; Back returns no code. This screen has no ads. `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)` returns no result; read the choice with `OnboardingSdk.selectedLanguage()`. |
| Notification/widget entries | Use `SplashEntry` and keep the passthrough in your listener; see [OnboardKit](../onboardkitorigin/README.md#optional-integrations). A normal launcher start needs none of these entries. |

When you use remote JSON, replace the splash class from step 6 with:

```kotlin
package com.example.app

import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity() {
    override fun onRemoteFetched() {
        OnboardKitSetup.configure()
    }
}
```

The SDK fetches before the hook; `configure()` rebuilds the mapping. Do not fetch a second time or copy `RemoteConfigUtils`. Without remote, preload or app-open, keep the one-line splash.

### App-open on return

<details>
<summary>Open the steps for enabling app-open</summary>

Do this only if your product uses app-open. `AppOpenManager` is in `com.ads.module.admob`.

1. **Step 4**, in `AdsConfig`: `appResume = adConfig.interstitial(AppAdPlacement.OPEN_RESUME)`. Without this field, OnboardKit blocks app-open on **every screen**, your app screens included.
2. **Step 5**, in the `ERainAdConfig` `apply` block: `idAdResume = AdGate.adUnitIds(AppAdPlacement.OPEN_RESUME).firstOrNull().orEmpty()`.
3. **Remote JSON:** nothing to add — the SDK re-points the app-resume unit at `open_resume` on every config change, including on/off through `isEnable`. Two conditions: your app has already seeded a non-empty app-resume ID in step 2, and `open_resume` carries an ad unit ID. Because step 2 is read at init (when only the asset config exists), ship `open_resume` **enabled with a real ID** in `ad_config.json`.
4. **Outbound intents** (browser/share/review): call `AppOpenManager.getInstance().disableAdResumeByClickAction()` right after `startActivity(...)` to skip that return. `disableAppResume()`/`enableAppResume()` are process-wide switches.

Splash, OB5 and the question screen exclude themselves; register only your app's own sensitive screens. LFO and OB content pages may show a ready app-open ad on return, except on the fullscreen page, during page transitions and while the popup is open. For delay and gating see [app-open](../onboardkitorigin/README.md#app-open-on-return).

</details>

## 8. Final checks

- [ ] A debug build opens the splash, Logcat tag `AdRemoteConfig` shows `Loaded ad_config_debug.json with 45 placements (debug=true)`, and `OB_FLOW` reports no config or provider error.
- [ ] Walk LFO → OB → MainActivity on test ads; the fullscreen native sits between content 2 and 3, and the final interstitial is owned by the SDK alone. LFO opens only after the splash interstitial is dismissed; MainActivity is already there when the final interstitial closes.
- [ ] LFO: selecting a language then pressing Back shows Save and stays on the screen; the fourth item tap opens the popup.
- [ ] Denying notifications still continues; Home and return from splash, LFO, the popup and OB do not navigate twice. A native click on an OB page advances the step on return; on LFO/the popup it stays and binds the replacement ad once ready.
- [ ] Disable both `native_ob2` and `native_ob2_high`: content page 2 still shows and does not borrow page 1's native. Disable both `native_fs` and `native_fs_high`: the ad-only page is skipped. Disable `inter_splash`, `inter_after_ob3` and all their `_high*` floors: the destination screen is still reached.
- [ ] Test with no network: by default the connection prompt appears; if you opted into offline support the flow still continues on the SDK timeout and does not hang on an app callback.
- [ ] Relaunch after completion: through the splash into your app, with no OB rerun; your screen is already there when the splash interstitial closes. Clear app data to test first-open; closing the app mid-OB and reopening must start at LFO after the splash.
- [ ] Your app screens use the selected language; check both the translations and the language split configuration when shipping an AAB.
- [ ] Before release: replace `admob_app_id` in `res/values/id_ads.xml` and every ad unit ID in **`ad_config.json`** with your app's IDs. Keep the debug JSON on test IDs. To keep the test App ID in debug, add an `admob_app_id` override in `app/src/debug/res/values/id_ads.xml`.

| Symptom | Quick check |
| --- | --- |
| Crash on launch | The AdMob/Meta metadata, real Meta credentials and your Application in the merged manifest |
| OB is empty or skipped | `install` before `configure`; use `steps(...)` in step 4 for your app's content, and check whether the remote cache has disabled the flow or a step |
| No ads | The file loaded, the key mapping, `isEnable`, consent/premium state, the remote flags; do not add a timer to show one anyway |
| Debug uses the wrong IDs | A valid debug file is present; `setAllowRemoteOverrideInDebug(true)` is not enabled in the standard setup |
| Remote changed the IDs but OB still uses the old ones | `FirebaseAdConfigSource` is installed and the setup is called again in `onRemoteFetched()`; debug deliberately pins the assets by default |
| Native style/reporting does not separate per slot in testing | The demo IDs of one format are shared; the provider looks style/placement back up by ID in places. Use separate IDs when verifying the real configuration |

## Files your app actually needs

| File | What to do |
| --- | --- |
| `settings.gradle`, `app/build.gradle` | Merge the build configuration and the two dependencies |
| `app/src/main/AndroidManifest.xml` | Metadata, your Application and the splash launcher |
| `app/src/main/res/values/id_ads.xml` | AdMob App ID, Meta credentials and the Adjust token/event tokens |
| `app/src/main/assets/ad_config.json`, `ad_config_debug.json` | Copy both sample JSON files |
| Your app's `strings.xml` and drawables | Copy, translations and images for the three pages |
| `AppAdPlacement.kt` | The catalog of OB keys and your app's own keys; no ad unit IDs |
| [OnboardKitSetup.kt](examples/ads-onboarding/OnboardKitSetup.kt) | Maps content, placement constants and native templates |
| Your existing Application or [PartnerApp.kt](examples/ads-onboarding/PartnerApp.kt) | Initializes the SDK once and picks the destination screen |
| `SplashActivity.kt` | Extends the SDK splash |

`MainActivity` is your app's existing destination screen. Apart from `AppAdPlacement.kt`, no file is needed to translate JSON into ad config — every entry point takes the placement key directly. For this basic flow you do not need to copy `AppConstants`, `RemoteConfigUtils`, `ResumeAdsEntryRule`, `AppLifecycleObserver`, DevConfig, Hilt or the example's paywall/welcome/uninstall screens.

[Ads reference](../ads/README.md) · [OnboardKit reference](../onboardkitorigin/README.md)
