# Ads + OnboardKit integration

**OB catalog:** `ob1..ob4` → `native_ob1..4`; `full1/full2` → `native_full1/2`. Default: `ob1, full1, ob2, full2, ob3, ob4`. All eligible OB natives preload on language selection. Remote `onboarding.order` selects/reorders app-declared steps. [Configuration / Hướng dẫn chi tiết](onboarding-flow.vi.md). `native_fs` remains the separate splash native.

[← Choose a guide](README.md)

Sample flow: **Splash → language (LFO) → OB1 → Full1 → OB2 → Full2 → OB3 → OB4 → end-of-onboarding interstitial (when enabled) → MainActivity**. The SDK owns consent, notifications, ads and navigation; an ad shows only when it is eligible and filled.

Do steps 1–6 and replace the **package, app details, content/images and destination screen**. The code keeps the SDK defaults; the JSON keeps the example debug configuration. Fill in the app token to enable Adjust; Firebase, ads on return and purchases are in the [optional tables](#7-configure-only-what-your-app-needs).

Use the **newest SDK version** from [JitPack](https://jitpack.io/#truongvimit/adlogic-partner-sdk) for every module. It includes grouped `ad_behavior_config` / `onboarding_config`, custom local defaults and live `AdsConfig.fromAdConfig()` bindings. Adding Firebase keys alone does not update an older SDK already integrated in the app.

Optional Privacy → Goal screens run after the pager and the exit interstitial (when enabled; off by default) as the final part of onboarding. Enable `privacy_goals_screen.enabled` and supply goal options; see [Privacy → Goal](privacy-goals-screen.md).

A returning user who opens the app from the launcher skips Welcome Back by default and goes to the app after the splash. Enable `welcome_back.enabled=true` to show it. See [Welcome Back](welcome-back-screen.md).

This guide describes the default `PER_SCREEN` native flow. The alternative `SHARED_POOL` flow runs LFO → pager → Privacy → Goal in one SDK screen fed by one shared native pool; see [SHARED_POOL native flow](#shared_pool-native-flow).

Pager natives stay bound while their page view survives, so revisiting content or Full1/Full2 shows the same ad without another request. Fullscreen auto-next restarts on each visit; revisits show Skip immediately when enabled or required to prevent a trap. A first no-fill advances; revisiting a failed page shows its fallback and Skip. Ads are released when the view is destroyed.

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

Set the newest JitPack version once in your app project's root `gradle.properties`; every SDK module reads this property:

```properties
adlogicSdkVersion=NEWEST_VERSION
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
- **[ad_config_debug.json](examples/ads-onboarding/ad_config_debug.json):** test `ids` per placement key (normally one floor), nothing else; every other field comes from `ad_config.json`.

Ad unit IDs contain **`/`**. `ad_config.json` carries style, UA, app-resume delay and waterfalls for every build; the [debug example](examples/ads-onboarding/ad_config_debug.json) only maps each placement key to a test floor (interstitials `1033173712`). The OB slots are listed below; `fromAdConfig()` in step 4 also binds `native_resume` (native resume), `open_resume` (app-open), `native_welcome1/2` (Welcome Back) and `native_onboarding_fullscreen_1_4` (OB5). The remaining keys are for your app screens and do not create a display position by themselves. Sample values may differ from the parser defaults; see the [JSON field table](#fields-in-the-sample-json).

| JSON key | Position | Maps into `AdsConfig` in step 4 |
| --- | --- | --- |
| `banner_splash` | Splash banner | `splashBanner` |
| `native_splash` | Splash native, same slot as the banner | `splashInlineNative` |
| `inter_splash` | Interstitial when leaving splash | `splashInterstitial` |
| `native_fs` | Optional full-screen native after the splash interstitial, before LFO | `splashNative` |
| `native_lang` | First language native | `languageNative` |
| `native_lang_alt` | Replacement native after the first language selection | `languageDupNative` |
| `native_popup_lang` | Native in the language confirmation popup | `languageConfirmNative` |
| `native_ob1` | Content 1 — `StepId.OB1` | `stepNatives[StepId.OB1]` |
| `native_ob2` | Content 2 — `StepId.OB2` | `stepNatives[StepId.OB2]` |
| `native_full1` | Full1 — `StepId.FULL1` | `stepNatives[StepId.FULL1]` |
| `native_full2` | Full2 — `StepId.FULL2` | `stepNatives[StepId.FULL2]` |
| `native_ob3` | Content 3 — `StepId.OB3` | `stepNatives[StepId.OB3]` |
| `native_ob4` | Content 4 — `StepId.OB4` | `stepNatives[StepId.OB4]` |
| `inter_after_ob3` | After all of onboarding, before the destination screen | `afterOnboardingInterstitial` |

`inter_after_ob3` shows after the entire configured OB list. See [the current step catalog](onboarding-flow.vi.md).

Every build reads its settings from `ad_config.json`. A debuggable build also reads `ad_config_debug.json`, which holds only `ids` per placement key, normally one test floor: those floors replace the placement's whole waterfall, so each placement loads its test ID through the usual load path. Any other field in the debug file is ignored with a `WARN`, and a placement enabled in `ad_config.json` without a debug entry gets no ad (also a `WARN`). Remote `ad_remote_config` applies every field except IDs; `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` also takes the IDs remote declares. Without a debug file, debug requests the `ad_config.json` IDs.

The sample IDs come from [Google demo ad units](https://developers.google.com/admob/android/test-ads#demo_ad_units) and [AdMob App ID](https://developers.google.com/admob/android/quick-start). The fullscreen page uses a **native ID**. The sample shares one test ID per format; production needs separate IDs to split configuration, style and reporting per placement.

### Optional: grouped remote settings and custom local defaults

Two additional documents are already bundled in the SDK: [ad_behavior_config.json](examples/ads-onboarding/ad_behavior_config.json) and [onboarding_config.json](examples/ads-onboarding/onboarding_config.json). To tune them remotely, follow [Firebase: publish the three String parameters](firebase-integration.md#remote-json). Keep `ad_remote_config` for unit configuration; add `ad_behavior_config` and `onboarding_config` as separate String values containing the respective JSON objects. Both examples match the bundled defaults except in the onboarding example: `onboarding.primary_color` is `""` so the app keeps its existing UI color, while the SDK default is `"#FF375E"`; `onboarding.exit_interstitial.enabled` is `true`, while the SDK default is `false`; and the [`native_flow`](remote-settings.md#native-flow-type) scope is left out.

To customize fallback values, create same-named files in `app/src/main/assets/`, copy the full defaults or include only the fields you want to change, then rebuild. If SDK defaults suit the app, neither extra file is required. Valid remote/cache fields, then legacy `ob_*` keys the backend delivered, take priority over these files at any scope and over your Kotlin config; an offline fetch preserves a previous valid remote cache instead of forcing local values. See the [two local JSON examples and fallback rules](firebase-integration.md#local-defaults), and the [field/default reference](remote-settings.md).

## 3. Prepare the onboarding content

Use your existing `app_name` and launcher icon. Add these eight strings to `app/src/main/res/values/strings.xml` and replace the copy with your product's:

```xml
<resources>
    <string name="onboarding_title_1">Welcome</string>
    <string name="onboarding_des_1">Introduce the app’s main benefit.</string>
    <string name="onboarding_title_2">Easy to start</string>
    <string name="onboarding_des_2">Show the action the user needs to know.</string>
    <string name="onboarding_title_3">Ready to go</string>
    <string name="onboarding_des_3">Invite the user to get started.</string>
    <string name="onboarding_title_4">One more thing</string>
    <string name="onboarding_des_4">Highlight another feature worth knowing.</string>
</resources>
```

Add translations in `values-<language>/strings.xml`, including the `ob_*` keys from [ob_strings.xml](../onboardkitorigin/src/main/res/values/ob_strings.xml), because the SDK ships English only. In step 4, replace the four `imageRes` values with your app's images (OB3 and OB4 share one in the sample), or copy images [1](../app/src/main/res/drawable-nodpi/img_onboard_sample_1.png), [2](../app/src/main/res/drawable-nodpi/img_onboard_sample_2.png), [3](../app/src/main/res/drawable-nodpi/img_onboard_sample_4.png) from the example into `app/src/main/res/drawable-nodpi/` to try it out.

The SDK already supplies the layouts/Activities for LFO, the popup, OB and the native ads.

<details>
<summary>Optional: use your own layouts</summary>

Only `SplashConfig.layoutRes` and `ContentStepDefinition.layoutRes` accept custom layouts; other layout fields are rejected by `getOrThrow()`. Copy the [content layout](../onboardkitorigin/src/main/res/layout/ob_fragment_content_step.xml) and keep its root, IDs and view types; a broken contract logs to `OB_FLOW` and the SDK falls back to the default layout. For the [splash](../onboardkitorigin/src/main/res/layout/ob_activity_splash.xml), missing IDs are ignored but every ID you keep must have the right type; a banner needs `ob_splash_ad_container` containing `<include layout="@layout/layout_banner_control" />`.

</details>

## 4. Declare placements and configure OnboardKit

### `AppAdPlacement.kt` — your app's placement catalog

Copy [AppAdPlacement.kt](examples/ads-onboarding/AppAdPlacement.kt) into your app package, for example `app/src/main/java/com/example/app/`. The file holds one key per placement: the OB slots and your app slots. Floors live in that key's `ids`; they need no constants.

`AppAdPlacement.NATIVE_HOME` is the key `native_home`; `ad_config.json` holds its ad unit IDs and configuration, `ad_config_debug.json` only its test `ids`. Add a constant and the matching JSON key for each new placement.

Declare native/banner slots in the screen XML and call the SDK directly from that screen. The SDK owns loading, cache and the ad lifecycle. `AdsAppManager`, if used, only groups configuration, initialization and app-specific policy.

### `OnboardKitSetup.kt` — wire the OB keys into the SDK

Copy [OnboardKitSetup.kt](examples/ads-onboarding/OnboardKitSetup.kt) into the same package. Change `com.example.app` in the Kotlin files and keep your app's resources. The sample declares four content pages plus the two fullscreen pages (FULL1, FULL2), and uses **`AdsConfig.fromAdConfig()`** for standard placement bindings. Step 5 configures it once after `OnboardingSdk.install`; ID, gate and template changes are resolved from current settings without repeating setup after fetch. Apps with different keys pass only those associations to `fromAdConfig(mapOf(...))`; the [mapping table](remote-settings.md) lists the defaults.

A declared disabled placement keeps an empty unit, so it cannot borrow a different slot's ad. Only an absent LFO2 unit falls back to LFO1; turn the replacement action off with `onboarding_config.lfo.native2.enabled = false`.

LFO/Welcome Back/content OB natives take their frame from the placement key’s `templateId` (template `4` for LFO1/2 in `SHARED_POOL`, template `1` for classic LFO/Welcome Back/content OB) and their block order from its `components`; the default order puts the CTA at the bottom, `["cta","media","icon_headline"]` puts it on top. `positionCTA` has been removed: a payload that still sends it is ignored. The host fallbacks `AdsConfig.languageTemplate`/`contentStepTemplate` and the `NativeTemplate` enum are removed with it; delete them from your `AdsConfig`. `native_template`/`content_template` in `onboarding_config` are removed, including per-step overrides. Fullscreen/popup frames remain fixed. `colorCTA` colors both the CTA and Ad badge; color/height/components are re-read at every bind, including click/resume replacements.

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

`ERainTuning.install()` sets the `onComplete` timing for [app-screen interstitials](#app-screen-interstitial-with-a-placement-constant) and skips the next resume ad after an ad click; it does not change the OB flow.

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

The table below describes SDK defaults and existing local/legacy fallback APIs. For remote experiments or app-side JSON defaults, use the corresponding fields in the [grouped settings reference](remote-settings.md). Precedence for every setting: remote `onboarding_config` / `ad_behavior_config` field > legacy `ob_*` key the backend delivered > your app-asset JSON > the Kotlin options and hooks below > bundled SDK default.

Add only the options you need to change to the `onboardKitConfig { ... }` block in step 4; call `ERainAd`/`ConsentCenter` where the table says. `ob_*` keys belong to Firebase Remote Config and are **not in the ad JSON**; without Firebase the values it last delivered (cached) apply, and a key it never sent leaves your configuration in charge.

| Behavior | Default | Change it only when / where to change it |
| --- | --- | --- |
| Splash screen | SDK layout; minimum display 3000 ms from the ad-loading phase; Back and the back gesture are ignored | `onboarding_config.splash.timing.min_display_ms` (valid `0` explicitly removes this minimum) or a delivered `ob_splash_min_display_ms` (`<= 0` keeps the local value); while remote is silent, your app asset, then `SplashConfig.minDisplayTimeMs`. |
| Opening the next screen after the splash interstitial | The interstitial shows after the minimum display time. First-open LFO and returning-user Welcome Back: wait for dismissal. Launcher → app (`welcome_back.enabled` off): open underneath the ad. Notification/widget/uninstall: wait for dismissal | Override `SplashActivity.nextScreenTiming()`: `NextScreenTiming.AFTER_AD`/`UNDER_AD` (`io.onboardkit.ads`), or `super.nextScreenTiming()` to keep the default. A `splash.navigation.next_screen_timing` other than `AUTO`, from remote or your app asset, outranks the override on every launch, notification/widget/uninstall included; `UNDER_AD` also skips the splash native_fs screen for that launch |
| Waiting for the splash ad | Up to 60 seconds, after the notification step and once splash has focus | Remote `ob_splash_ad_budget_ms`; do not add a timer of your own |
| Remote / ads | Consent → slot + interstitial; SDK-owned remote runs in parallel and survives splash. | Current asset/cached/remote values apply; see fetch timing below. |
| LFO1 preload | `SEQUENTIAL`: interstitial result → LFO1. `PARALLEL`: splash requests → LFO1. | `splash.load.lfo1_preload_mode` |
| No network | Ask the user to connect and do not continue | `SplashConfig.noInternetPromptEnabled = false` to start offline through the UMP fallback below; the next splash asks for UMP again |
| Consent | 10-second network timeout; the form itself waits for the user | In your Application: `ConsentCenter.configure(ConsentOptions(timeoutMs = ...))` (`com.ads.module.consent`). Keep the SDK hook; `SplashConfig.consentTimeoutMs` does not change the UMP timeout |
| The UMP form during QA | Debuggable: every device counts as EEA (`setForceTesting`), no hashed ID needed; release: real geography | `ConsentOptions(debug = false)` to debug against real geography. `configure` replaces every option, so to change the timeout as well pass one `ConsentOptions(timeoutMs = ..., debug = false)` |
| Notifications | After consent and splash ad requests, without waiting for remote; Android 13+/target33+. Denial continues; recorded results prevent repeat prompts. | `SplashConfig.notificationPermissionEnabled = false` if your app sends no notifications or owns the prompt |
| Language | 21 languages, hand hint after 3 seconds, dimmed confirmation before a selection | `LanguageConfig.languages`: keep the languages you have translated; remote `lfo.languages.supported_codes` narrows this list, and a `defaultCode` it leaves out is not preselected. A remote or app-asset value sets `tapHintEnabled` and `confirmVisibleBeforeSelect` either way |
| Back on LFO | Nothing selected: Back is ignored. After a selection: Save appears and the language screen stays | `LanguageConfig.saveButtonOnBackEnabled = false`: ignore Back after a selection too. In SETTINGS, Back closes the screen |
| Replacing the native after a language selection | Enabled; the first native stays until the replacement ad can bind. If the replacement cannot show (no fill, or not bound in time), the first native reloads instead, unless the confirmation popup is already open, and a replacement that fills late is swapped in on the next language tap, before the first native requests again. After that, every tap on another language that opens no popup reloads the native on screen over its full waterfall, unless a load for it is still running; with the replacement off, the first tap reloads too. Re-selecting the current language never reloads. A remote `lfo.confirm_dialog.show_from_tap` moves the popup boundary, and the reload with it, from the next tap | `LanguageConfig.secondNativeOnSelectEnabled = false` to turn the replacement off; the reload has no setting |
| Language popup | Re-selecting the current language opens it immediately. Selecting another language opens it from the fourth total tap onward; re-select taps still count. Its native is first requested when the popup opens | `LanguageConfig.confirmDialogOnReselectEnabled = false` to turn it off; SETTINGS shows no popup |
| Native template | SDK: template `4` on LFO1/2 in `SHARED_POOL`, `1` on classic LFO/Welcome Back/content OB, `2` on splash inline and Privacy/Goal; CTA at the bottom | Set `ad_config.<key>.templateId` for the frame and `components` for the order. The backend's `ad_remote_config` beats your app asset field by field. [Precedence](remote-settings.md). |
| System bars | Status/caption bars shown, navigation bar hidden | `SystemBarConfig(showStatusBar, showNavigationBar, showCaptionBar)` |
| Native click and return on OB | Content and fullscreen pager pages advance on return (`auto_next`) | `"click_action": "none"` on the page's key (`native_ob1`…) to stay on the page, `"reload"`/`"reload_waterfall"` to stay and show a replacement; [Native click actions](remote-settings.md#native-click-actions) |
| Native click on LFO/popup, Welcome Back, OB5 or an app screen | Preloads as soon as the ad is clicked/opened; on return it binds a ready ad or waits for the in-flight request. The replacement requests the all-price floor only (`reload`); `reload_waterfall` walks every floor | `click_action` on the placement's key in ad_config, independent of timed refresh; [app-screen native example](#app-screen-native-with-a-placement-constant) |
| Relaunching with the flow unfinished | Runs Splash → LFO → OB again; OB is skipped only once the whole flow completes | No app-side first-open flag or checkpoint is needed |
| Fullscreen native page | X after 5 seconds, auto-next after 15 seconds from page selection; background time still counts, time spent in the page's ad after a click does not. Its shimmer fills the native host, with media across the viewport and the CTA at the bottom. | The fields of `AdFullScreenStepDefinition`; remote `ob_skip_button_delay_sec = -1` keeps the local delay |
| End-of-onboarding interstitial | Off (`onboarding.exit_interstitial.enabled` is `false`). Once enabled: preloaded on pager entry, waits up to 8 seconds for a fill on completion; the next screen opens underneath the ad. Notification/widget/uninstall: wait for dismissal | `afterOnboardingInterstitialEnabled = true`, or `onboarding.exit_interstitial.enabled: true` remotely or in the app asset, to turn it on; `AdsConfig.afterOnboardingInterstitialTiming = NextScreenTiming.AFTER_AD` to always wait for dismissal; leave it off if your app owns it. Keep it out of `InterstitialAutoBuffer` |
| OB navigation | With swipe enabled: OB1 stays locked. OB2/OB3/OB4 and fullscreen pages remain locked while loading/binding and unlock only after an ad impression; a content page with no ad to show (no unit, premium, no fill) unlocks at once. Each new visit starts locked. The global swipe lock still wins. When OB1 is the first page it shows a top-start back button; it, Back and the back gesture on OB1 return to LFO, which opens fresh and requests its natives again. No other page has the button: there Back goes one page back and exits the app from the first page. | `BehaviorConfig.lockPagerSwipe`, `swipeCompletesLastStep`, `backNavigatesBack` (`false`: Back exits the app from every page without the back button), `onboarding.steps.ob1.back.enabled = false` (remote or app asset) to remove the button from OB1, `lockPortrait`; a landscape app must change the manifest too |
| Interstitial interval | `ERainAdConfig.intervalInterstitialAd = 0` (no limit); it applies to the `InterstitialAutoBuffer` group only, not to splash/OB or interstitials you load yourself | Set it before init, or use `ERainAd.getInstance().setIntervalInterstitialAd(seconds)` |
| Interstitial click cap | Off (`0`) | `ERainAd.getInstance().setMaxClickAdsPerDay(n)`: at most `n` clicks per ad unit per 24 hours, then loading/showing stops. Call it when needed, usually after the remote fetch |
| OB5, paywall, resume | `ob_enable_step_ob5 = false`. With OB5 on: it opens if its native is loaded (underneath the final interstitial when that is enabled), and is skipped otherwise. With `fromAdConfig()`, OB5 binds `native_onboarding_fullscreen_1_4`; that key, once declared in `ad_config.json` or remote, beats a unit set in code. While it is undeclared, `ob5Native`, then `fullScreenStepNative` (host setup), applies. The paywall is not wired. [Ads on return](#ads-on-return) follow `app_resume.type` (default `native_resume`); `disableAppResume()` keeps them off | Set its ID under `native_onboarding_fullscreen_1_4` (`AdsConfig.ob5Native` applies only while that key is undeclared); wire the paywall only when you need it |

A UMP error or timeout can allow an **attempted request** in-process through the [AdLogic fallback](../ads/src/main/java/com/ads/module/consent/ConsentCenter.kt); it grants no consent and guarantees no fill. A host that turns requests off with `ConsentCenter.setHostConsent(false, false)` from its own CMP still wins; do not infer request permission from a timer or from personalization.

Consent is the request authority. Publish a custom CMP result with `ConsentCenter.setHostConsent(canRequestAds, personalized)` and read it with `ConsentCenter.canRequestAds()`. A hook timeout does not grant consent.

Install `adProvider = ERainAdProvider()`, or leave it unset for an onboarding without ads. `OnboardingAdProvider` is SDK-owned and cannot be implemented by partners. Set per-tier timeouts with `native.load.tier_timeout_ms` and `interstitial.load.tier_timeout_ms`. A language native blocked before its request may be requested once on the language screen; a completed no-fill is not retried.

### Fields in the sample JSON

`ad_config.json` uses test IDs in every placement and keeps `native_ob1..4`, `native_full1/2` and `native_fs` off (enable them to test those slots); `ad_config_debug.json` holds only the test `ids`.

| Field | Value in the sample | How it is used / where it applies |
| --- | --- | --- |
| `ids` | A test ad unit of the right format | The waterfall of `{"id", "isEnable"}` floors, highest first, all-price last; a single-unit placement is a one-floor array. A placement-level `"id"` is not read. Replace the IDs in the real file before release; do not change the placement keys. See [Waterfall](#waterfall-one-key-per-placement). |
| `isEnable` | `true`, except `false` on `native_ob1..4`, `native_full1/2` and `native_fs` | Turns a placement on or off: `false` turns off its whole waterfall. A floor in `ids` has its own `isEnable` to pause only that floor. |
| `enable_ua_check` | Both `true` and `false` in the example | `true` requires paid/non-organic attribution; until Adjust answers, the default is organic. The standard `AdsConfig.fromAdConfig()` bindings apply this gate to the corresponding OB placements too, including native LFO/OB and exit interstitial. Without Adjust, set it to `false` for the placements you want to show. |
| `reloadIntervalSeconds` | Banner: `30` | A positive value sets the auto-reload cadence, in seconds, of every placement-bound banner, splash included, while `banner.reload.auto_enabled` is `true` (the default). Absent, `0` or invalid uses the host/SDK value (15000 ms). App-screen setup: [App-screen banner](#additional-integrations). |
| `colorCTA` | `"default"` | Keeps the template color; set a color when you need a custom native. |
| `colorBackground` | absent | Optional native card background, e.g. `"#FFF4E5"`. Absent, `null`, `""` or `"default"` keeps the layout's own background; the card keeps its corners and border. Applies to every native frame and its loading skeleton. |
| `colorAdBadge` | absent | Optional Ad badge background, e.g. `"#007AFF"`; wins over `colorCTA` on the badge. Absent, `null`, `""` or `"default"` falls back to `colorCTA`, then the layout. Applies to every native frame and its loading skeleton. |
| `colorAdBadgeText` | absent | Optional Ad badge text color, e.g. `"#FFFFFF"`. Absent, `null`, `""` or `"default"` keeps the layout's text color. Applies to every native frame; the loading skeleton keeps its text hidden. |
| `heightCTA` | `45` for most natives, `44` on `native_splash`, `36` for the popup | CTA height in dp; the SDK uses `44` (the templates' button height) when the field is absent and clamps the value to 36–52 when applying it. |
| `templateId` | absent | Optional SDK native template for the placement: `1` = classic LFO card (full-width 1.68:1 media), `2` = 4:3 media-left card, `3` = 1.91:1 card, `4` = wide DashDrama-shaped LFO card (about 3.14:1 media). The slot is as tall as the card. Absent, `null` or an unknown number keeps the slot's default: `4` on LFO1/2 in `SHARED_POOL`, `1` on classic LFO/Welcome Back and OB content, `2` on Privacy/Goal and the splash bottom slot. The language popup and the fullscreen natives keep their fixed frames. See [Native templates](remote-settings.md#native-templates-cta-and-xskip-experiments). |
| `components` | `["icon_headline", "body", "media", "cta"]` | Block order, top to bottom; a missing block is hidden and an empty array keeps the XML. Absent, the default order puts the CTA at the bottom. Templates `1`, `3` and `4` and [app-screen natives](#app-screen-native-with-a-placement-constant) follow it; template `2`, the language popup and the fullscreen natives always show as drawn. |
| `app_resume_load_delay_ms` | `open_resume`: `2000`; `native_resume`: `0` | How long the app must stay in the background before the resume ad is requested; a quicker return shows nothing. `open_resume`'s applies to `open_resume` and `inter_resume`, `native_resume`'s to `native_resume` (both resume screens). |
| `click_action` | `"auto_next"` on `native_ob1..4`, `native_full1/2` and `native_fs`; `"reload"` on every other native | What a native ad click does on return. Covers every floor in `ids`; set it in `ad_config.json` or remote only (`ad_config_debug.json` ignores it). See [Native click actions](remote-settings.md#native-click-actions). |

### Waterfall: one key per placement

Each placement is one key, and it lists its floors in `"ids"`, highest floor first and the all-price floor last; each floor is `{"id": "...", "isEnable": true|false}`, and a floor without `isEnable` is on. The placement's `isEnable` is the master switch. To pause one floor, set that floor's `isEnable: false`: its ID stays in place, so turning it back on needs nothing else. A single-unit placement (banner, `inter_back`, `open_resume`…) is a one-floor array without a floor `isEnable`, `"ids": [{ "id": "..." }]`, since the placement's `isEnable` already switches it. A placement-level `"id"` is an unknown field, ignored with a warning, so that placement has no ad unit. Every other field applies to all floors. In `ad_config.json`, a placement that omits `isEnable` is off (unlike a floor, whose `isEnable` defaults to on) and a missing `enable_ua_check` is `false`; in a remote document, an omitted field keeps the lower layer's value.

```json
"native_ob1": {
  "ids": [
    { "id": "ca-app-pub-xxx/2222222222", "isEnable": false },
    { "id": "ca-app-pub-xxx/1111111111", "isEnable": true }
  ],
  "isEnable": true,
  "click_action": "auto_next"
},
"banner_home": {
  "ids": [{ "id": "ca-app-pub-xxx/3333333333" }],
  "isEnable": true
}
```

`ids` accepts only floor objects: a bare string is skipped with a warning, and a non-empty `ids` with no valid floor is ignored, so the layer below applies; an explicit `"ids": []` is a valid empty waterfall, so the placement requests nothing. A malformed floor is skipped alone. With every floor off the placement requests nothing. Across layers (remote > `ad_config.json` > code), a higher layer's `ids` replaces the lower layer's whole array, so Firebase must hold the full array with each floor's `isEnable`. Duplicate IDs are dropped; use separate IDs when you need to verify an individual floor.

**Breaking change — upgrading from `_high` keys.** Earlier SDK versions read floors from separate `<key>_high`, `<key>_high1`…`<key>_high9` keys. Those keys are no longer read: the parser logs a warning ending in `not read as floors; move their ids into the base key's "ids"`, and the placement requests only its own key. A placement-level `"id"` is no longer read either. Every floor now reports under that one key, so load and revenue events no longer split between `<key>` and `<key>_high`.

1. In `ad_config.json` and in the Firebase `ad_remote_config` value, move each `<key>_high*` ID into `<key>.ids` as `{"id", "isEnable"}` in the old order (`_high`, `_high1`…`_high9`), keeping that floor's old `isEnable`; the old `<key>.id` becomes the last floor. A key that used the old string form `"ids": ["a", "b"]`, which old SDKs accepted, contributes each string as a floor object in the same order, before that key's own `id`: the old SDK requested `<key>_high`, `<key>_high1`…`<key>_high9`, then `<key>`, and within each key its `ids` then its `id`, skipping repeated IDs. Delete the `_high*` entries. Every remaining `"id": "X"`, single-unit placements and every key of `ad_config_debug.json` included, becomes `"ids": [{"id": "X"}]`.
2. In Firebase, publish the new-format `ad_remote_config` under a condition on app version ≥ the first version that ships this SDK; older versions keep the old value. This condition is mandatory. An app on SDK 5.5.6 or newer that receives the new format does not crash: it ignores `ids` and keeps the waterfall built into its APK, but no longer picks up remote ID changes. An app on SDK 5.5.5 or older replaces its whole configuration with the remote document and loses every ad unit.
3. On the first launch after the update, a cached old-format value may still be active for one session. Its `id` and `_high*` entries are not read, so each placement keeps the waterfall from your `ad_config.json` until the next fetch; the value's other fields still apply.

### App-screen native with a placement constant

<details>
<summary>Open the native and preload example for app screens</summary>

Declare a slot in the screen XML (use a separate slot for each native/banner). OB handles its own slots:

```xml
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/ad_slot"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

Call the SDK from the screen after consent, with the `AppCompatActivity` resumed:

**Without Adjust:** keep `enable_ua_check` on `native_home` at `false` in `ad_config.json` (the sample already does) so the sample slot can show; `ad_config_debug.json` holds only test `ids`. Keep the test IDs during QA.

```kotlin
import android.widget.FrameLayout
import com.ads.module.helper.adnative.NativeAdHelper

val container = findViewById<FrameLayout>(R.id.ad_slot)
NativeAdHelper.forPlacement(this, this, AppAdPlacement.NATIVE_HOME, container)
```

The SDK resolves the placement's waterfall, `isEnable`, `enable_ua_check` and CTA style itself. Pass `layoutId` to change the template; the default is `com.ads.module.R.layout.custom_native_admob_medium` (no media — use `custom_native_admob_free_size` when you need media). A custom layout must keep its `NativeAdView` root, `ad_container`, `block_icon_headline`, the asset IDs and the Ad badge.

Keep one helper per slot/view and call `show()` to request and bind a new ad. A Fragment passes its Activity plus `viewLifecycleOwner`. Click reload is on by default; set `"click_action": "none"` on the placement's key only when your app navigates away itself on click-return.

Preload for Main: `NativeAdManager.preload(applicationContext, AppAdPlacement.NATIVE_HOME, NativeAdConfig.forPlacement(AppAdPlacement.NATIVE_HOME, layoutRes))` in `SplashActivity.onRemoteFetched()`. A helper on the same placement picks that ad up when it shows; an ad older than 60 minutes is reloaded. See [Native preload](../ads/README.md#native-preload-repeated-show-and-refresh).

Native helper behavior:

- A helper with a placement (`forPlacement`, or the `placement` argument of the `NativeAdHelper` constructor) binds and refills from that placement's store, so preload with the same placement key as above. An ad preloaded under the joined unit IDs (`NativeAdPreload.getInstance().preload(activity, config)`) is left unused and the helper requests its own, unless you pass that key as below. A key you pass to `setEnablePreload(enabled, key)` names the store ahead of the placement, the joined unit IDs key (`NativeAdPreload.getInstance().keyOf(config)`) included.
- `bindAvailable()` returns `false` and cancels the helper when the user has purchased, consent is withdrawn or the UA gate refuses; an ad already on screen is removed. The unused preloaded ad stays stored.
- On return from an ad click, the helper binds the replacement loaded at click time or waits for it; if that replacement failed, the current ad stays and no second request is made. After a configuration change such as rotation, the helper restores its ad, or joins the load it was waiting for, without a new request; a slot that ended with no fill stays empty.

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

The SDK default is `AfterDismiss`. The copied `PartnerApp` calls `ERainTuning.install()`, which selects `UnderAd`: navigation runs while the ad is showing. Pass `nextAction = InterNextAction.AfterDismiss` when finishing the host or starting camera/audio/video. This keeps the established timing.

To keep an interstitial ready automatically: [InterstitialAutoBuffer](../ads/README.md#automatic-interstitial-preload) — `configure` after `ERainAd.init`, `start` on the first content screen; show as above. Interval and click cap are in the [defaults table](#flow-defaults).

</details>

### Reward on an app screen

Call from a resumed Activity on the main thread, after consent; for example, from the watch-ad button:

```kotlin
import com.ads.module.helper.reward.RewardAdManager

RewardAdManager.loadAndShow(
    this, AppAdPlacement.REWARD_EXAMPLE,
    onSuccess = {
        closeLoading()
        grantReward()
    },
    onFailed = { closeLoading() },
)
```

Or preload earlier and show only the prepared ad on the button click:

```kotlin
RewardAdManager.preload(applicationContext, AppAdPlacement.REWARD_EXAMPLE)

// On the watch-ad button:
RewardAdManager.show(this, AppAdPlacement.REWARD_EXAMPLE) { earned ->
    if (earned) grantReward()
}
```

`preload` and `load` share one cache/request per placement. `show` uses a ready ad; `loadAndShow` uses that cache, waits for the running request, or starts loading when neither exists. There is no automatic refill. `onSuccess` runs after a reward is earned and the ad closes; `onFailed` handles other outcomes. Use SDK callbacks for the result; do not infer it with a timer.

Defaults: 30 seconds per load tier, one cached ad/request per placement, no automatic refill. An empty-cache `show` completes with `false`; a repeated `loadAndShow` while the same placement is waiting/showing calls `onFailed`. The lambda/Runnable result reflects reward received **before close**. For individual events, including a mediation reward reported after close, use [`RewardShowCallback`](../ads/src/main/java/com/ads/module/helper/reward/RewardAdManager.kt); the completed result is not revised.

### Additional integrations

| Need | Default / how to configure |
| --- | --- |
| Turn off a slot | `isEnable: false` on the placement key turns off its whole waterfall, for every format; testing an asset change needs a restart. The second LFO native has a fallback from step 4. |
| Add waterfall floors | `{"id", "isEnable"}` floors in the key's `ids`, highest first, all-price last ([format](#waterfall-one-key-per-placement)). The splash banner walks every floor of `banner_splash` like any other placement. |
| CTA / native style | Keep every field as in the example; the [JSON field table](#fields-in-the-sample-json) explains the values and where they apply. Step 4 already wires `templateId` and `components` into the native templates. |
| App-screen banner | `BannerAdHelper.forPlacement(this, this, "banner_home", container)`; add a `bannerType` argument, for example `BannerType.Collapsible()` ([the types](../ads/src/main/java/com/ads/module/helper/banner/BannerType.kt)). To change the type: `flagUserEnableReload = false`, `cancel()` the old helper, then create a new one. SDK refresh is on by default and `reloadIntervalSeconds` sets its interval, so turn AdMob console refresh off on every floor's ad unit to avoid a double refresh; to turn SDK refresh off, see [`banner.reload.*`](remote-settings.md#behavior-scopes). |
| UA / Adjust | Fill in the resources and use the wiring from [step 5](#adjust-tokens-and-verification). `enable_ua_check` keeps the example values; see the JSON field table for where it applies. |
| JSON from Firebase | [Publish the three String parameters](firebase-integration.md#remote-json): `ad_remote_config`, `ad_behavior_config`, `onboarding_config`. Install `FirebaseAdConfigSource()` once after assets; SDK splash refreshes them. A key declared in the backend's `ad_remote_config` or your `ad_config.json` outranks ad unit IDs written in code. [Custom local fallback](firebase-integration.md#local-defaults) is optional. |
| Firebase Analytics | Add `suite-firebase` and register `Tracker.addSink(FirebaseSink())` right after `Tracker.install`; choose the consent policy from the [Firebase guide](firebase-integration.md#initial-consent). |
| Ads on return | `app_resume.type` picks `native_resume` (default), `inter_resume`, `open_resume` or `none`; that type's `ad_config` key must be on with real IDs. `disableAppResume()` turns every type off. Follow [Ads on return](#ads-on-return). |
| App with premium / paywall | Follow [BillingKit](billing-integration.md) / [PayKit](paywall-integration.md) and the [concurrent billing hook](../onboardkitorigin/README.md#optional-integrations). `onInitBilling()` is empty by default; calling billing install does not mean premium has been restored. |
| Language change from Settings | `registerForActivityResult(StartActivityForResult())`, then `launch(ObLanguageActivity.intentFor(activity, LanguageScreenMode.SETTINGS))` (the types are in `io.onboardkit.ui.language`). On `RESULT_OK`: read `ObLanguageActivity.RESULT_LANGUAGE_CODE`, save it as in step 5 and call `recreate()`; Back returns no code. This screen has no ads. `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)` returns no result; read the choice with `OnboardingSdk.selectedLanguage()`. |
| Notification/widget entries | Use `SplashEntry` and keep the passthrough in your listener; see [OnboardKit](../onboardkitorigin/README.md#optional-integrations). A normal launcher start needs none of these entries. |

With the live placement bindings from step 4, remote JSON needs no extra splash override:

```kotlin
package com.example.app

import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity()
```

Splash starts consent, remote refresh and billing together. Its banner/native slot and interstitial request as soon as consent resolves, using the currently available configuration and entitlement; they do not wait for remote or billing. Remote values already cached or delivered outrank the asset. SDK-owned refresh continues after splash closes, with a background wait of at least 60 seconds. Later reads use newly applied values; requests, timers and navigation already committed are not restarted. `SAME_TIME` and `ALTERNATE` both follow this sequence. `onRemoteFetched()` runs only if splash is still alive; process-owned integrations should use `SettingsRegistry.addFetchListener`.

### Ads on return

<details>
<summary>Open the steps for ads on return</summary>

Once the app has stayed in the background for the selected type's delay, the SDK requests its ad; a quicker return requests and shows nothing. `native_resume` waits `native_resume.app_resume_load_delay_ms` (default 0 ms, so every return opens the screen), on the classic and the `SHARED_POOL` resume screen alike; `inter_resume` and `open_resume` wait `open_resume.app_resume_load_delay_ms` (default 2000 ms). A return from an ad's landing page opens the native resume screen too, whatever `app_open.presentation.skip_after_ad_click` says. `app_resume.type` in `ad_behavior_config` selects exactly one type, default `native_resume`:

| `app_resume.type` | `ad_config` key | On return |
|---|---|---|
| `native_resume` | `native_resume` | Opens the native resume screen at once: an image, a text card with a Start button, and the ad below; a skeleton stands in until the ad lands. Start and Back (button or gesture) show `inter_resume` when that key is declared and on (load-and-show with the loading dialog, `interstitial.load_and_show.wait_timeout_ms`) and closes the screen AFTER_AD: once the ad is closed the screen comes back in front and then closes, so its native (one that landed behind the ad included) is on screen for the exit. With the key off or undeclared, or an ad that does not show, they close it at once. System back (key or gesture) is held while the screen's native is loading, or bound but not yet seen: it does nothing until that native makes its impression (an ad already seen elsewhere counts at once) or nothing is loading for it any more; Start is never held. Either way the user lands on the screen they left. The screen also requests `native_resume` when it opens unless that ad is already loaded or loading, and preloads `inter_resume` while it is shown. The shown ad is kept: the next return shows it again at once and swaps in the new ad when that lands. |
| `inter_resume` | `inter_resume` | Load-and-show with the loading dialog, waiting at most `interstitial.load_and_show.wait_timeout_ms` (default 8000 ms). |
| `open_resume` | `open_resume` | App-open. |
| `none` | — | Nothing. |

When the selected type's key is off (`isEnable: false`) or undeclared, the return shows no ad; the SDK never falls back to another type. To switch types, change `app_resume.type`.

1. **Step 4:** `AdsConfig.fromAdConfig()` already binds `native_resume`, `inter_resume` (the resume screen's Start) and `open_resume`. With a manually built `AdsConfig(...)`, supply `resumeNative = NativeAdUnit(...)` / `resumeInterstitial = InterstitialAdUnit(...)` / `appResume = InterstitialAdUnit(...)` yourself unless `ad_config.json` or the backend's `ad_remote_config` declares the key.
2. **`open_resume` only**, step 5, in the `ERainAdConfig` `apply` block: `idAdResume = AdGate.adUnitIds(AppAdPlacement.OPEN_RESUME).firstOrNull().orEmpty()` (`AdGate` is `com.ads.module.helper.AdGate`). An `open_resume` declared in `ad_config.json` or the backend's `ad_remote_config` needs no seed.
3. **`ad_config`:** declare the key of every type you may select, **enabled with real IDs** in `ids`. `native_resume` takes the native fields (`templateId`, `components`, `click_action`, CTA colors; template 1 and `reload` by default).
4. **Outbound intents** (browser/share/review): call `AppOpenManager.getInstance().disableAdResumeByClickAction()` right after `startActivity(...)` to skip that return, whatever the type. `disableAppResume()`/`enableAppResume()` are process-wide switches for every type.

Screen exclusions apply to every type. Splash, OB5, Welcome Back and the native resume screen exclude themselves, so a return to the resume screen opens nothing; register only your app's own sensitive screens with `disableAppResumeWithActivity`. LFO and OB content pages may show resume ads on return, except on the fullscreen page and the last onboarding page, during page transitions, while the popup is open and, for `open_resume` and `inter_resume` only, after a click on an onboarding ad when `app_open.presentation.skip_after_ad_click` is `true`.

**Native resume screen.** Set its image and text in `onboardKitConfig { resumeScreen = ResumeScreenConfig(titleRes = …, subtitleRes = …, imageRes = …) }`, the way OB steps are declared; `title`/`subtitle` take a `CharSequence` over the resource, and anything unset keeps the SDK default. Start's text color is `native_resume.start_button.color` in `onboarding_config` (`""` keeps the layout color); override the `ob_start` string to rename it. For a different layout, override `res/layout/ob_resume_screen.xml`. Only `ob_resume_ad` (`FrameLayout`) and a Start Button/TextView are required (prefer `@id/ob_resume_start`); `ob_resume_image`, `ob_resume_title`, `ob_resume_subtitle` and `ob_resume_ad_block` are optional. The SDK keeps the request, binding and close logic. `native_resume.start_button.delay_ms` controls when Start becomes visible for both `PER_SCREEN` and `SHARED_POOL`.

For app-open delay and gating see [app-open](../onboardkitorigin/README.md#app-open-on-return).

</details>

### SHARED_POOL native flow

<details>
<summary>Open the SHARED_POOL guide</summary>

`native_flow.type` picks the flow that runs after the splash. `PER_SCREEN`, the default, is the flow the rest of this guide describes. `SHARED_POOL` reproduces the first-open flow of DashDrama 1.0.6, the reference app the pool was modelled on: LFO → onboarding pager → Goal, with the SDK's Privacy step before Goal (Privacy and Goal only when enabled). These stages run in one SDK activity, and every native slot in them draws from one pool shared by the whole process. The splash is the same in both flows, except that it does not preload LFO1 for a `SHARED_POOL` run (see LFO below). Keys, precedence, the `ob_native_reuse_enabled` alias, the `ob_native_flow` user property and every `native_flow.pool` default are listed in [Native flow type](remote-settings.md#native-flow-type); this section describes what the flow does.

**How the pool decides.** The pool holds at most one ad per placement and keeps it from screen to screen.

- **Owner screen.** The screen in front asks the pool for its placement and becomes the owner, unless the placement is refused for the whole run (premium, ads or the placement switched off, no ad unit, UA gate). A passing refusal (offline, consent not answered yet, requests held) still makes it the owner.
- **POLITE and FORCED loads.** Opening a screen, selecting a page and coming back to a screen are POLITE loads: they are skipped while any request is running in the pool, and a skipped load is dropped, not queued (`parity.retry_skipped_polite`). A language tap, a Privacy or Goal selection and the preload of the next full page are FORCED loads: they ignore the gate. The full-page preload is FORCED because it is asked in the same moment as the current page's own request, which would otherwise close a POLITE gate on it every time; it never changes the owner. Either way, a placement that already holds an ad or is loading requests nothing.
- **Single-flight gate.** The gate is open only while no placement has a request running, so a POLITE load does not start while another request runs. The check is made off the main thread and can lag a frame (`parity.async_gate`). FORCED loads, return refreshes and an adopted splash load ignore the gate; the resume screen's refresh is the exception and waits for the gate like a POLITE load. A refresh counts as a running request.
- **Selector.** Every slot shows the same pick: (1) the owner's ad, whenever the pool holds one for it; (2) otherwise the newest ad that no screen has shown yet; (3) otherwise the ad shown most recently; (4) otherwise a shimmer while a request runs, and a hidden slot when none does. A screen whose own ad has not arrived borrows another placement's ad, and its own ad replaces the borrowed one as soon as it fills.
- **Refresh on return.** When a screen comes back to the front (from the background, an ad click or the resume screen), it requests the ad it owns again from the first floor while that ad keeps showing: a fill replaces it, a no-fill keeps it. This is the SDK's refresh model, not DashDrama's, whose refresh did not count as a running request.
- **Expiry and floor wait.** Both are the SDK's values, not pool keys: an ad older than `native.cache.max_age_ms` (one hour) is dropped and requested again the next time a screen asks for that placement, and a floor that does not answer within `native.load.tier_timeout_ms` (30 seconds) counts as a failure, so the next floor is requested.
- **Floors.** A request walks the enabled floors of the key's `ids` in order. A repeated ID is requested again after a no-fill, unlike `PER_SCREEN`, which drops duplicates.

**Turn it on.**

- Set `native_flow.type` to `"SHARED_POOL"` in `onboarding_config`, remotely or in your app asset, or deliver the flat key `ob_native_reuse_enabled = true`. [Native flow type](remote-settings.md#native-flow-type) explains the precedence and why a fresh install whose first fetch has not landed runs the asset or SDK default.
- Keep the built-in `adProvider = ERainAdProvider()`; without it every run is `PER_SCREEN`.
- The type is decided once per run: the splash asks for it first, to know whether to preload LFO1, and the language handoff uses the same answer even if a remote fetch lands in between. Every `native_flow.pool` value is read when the splash opens the language screen. Both are kept for that run; a value fetched later applies from the next run. Each run that opens the language screen sets the user property `ob_native_flow` and logs `native_flow_type=…` to `OB_FLOW`.
- No code changes: the flow uses the same `ad_config` keys, `OnboardKitSetup`, content layouts and Privacy/Goal layouts as `PER_SCREEN`. Welcome Back and the in-app language picker stay `PER_SCREEN`.

**Screen by screen.** Placements are named by their `ad_config` key from [step 2](#2-add-the-ids-and-the-two-json-files).

| Screen | Placement | When it requests | Behaviour |
| --- | --- | --- | --- |
| LFO | `native_lang` until the first tap, then `native_lang_alt`; `native_popup_lang` while the confirmation popup is open | On open: POLITE `native_lang`. The splash does not preload LFO1 for a `SHARED_POOL` run, so the LFO requests it itself, as DashDrama does; if LFO1 was preloaded anyway, the LFO first takes that preload over (a ready ad shows with no new request, a running load is joined, a load still queued is cancelled), and the resume right after opening does not refresh an adopted splash ad that has not shown yet. Every language tap: FORCED `native_lang_alt`. On return: a refresh of the current key, then POLITE on that key. | The saved language is preselected; otherwise nothing is. The confirm button stays hidden until `native_flow.pool.lfo.confirm_button.reveal_delay_ms` (3 s) after the first tap. Done shows the splash interstitial's leftover when `lfo.exit.reuse_splash_inter` is `true`; after it closes (`native_flow.pool.exit.next_screen_timing`, `AFTER_AD`) the language is saved and applied (`native_flow.pool.locale_apply`, in place by default), the first page's ad is preloaded FORCED when that page is a full page, and the next stage opens; a content page, Privacy or Goal claims its own ad when it opens. A `native_lang` refused for the run hides the slot. On a fresh open `native_flow.pool.lfo.loading_dialog.duration_ms` (default `0`, off) holds a "Loading languages" card with a progress bar over the header and rows, never over the ad; rows take no tap until it ends. Its countdown stands still while the app is away and survives a recreation; OB1 back does not show it again. |
| Pager content page | The page's `native_obN`; a page whose key is off has no slot | On page selection: POLITE on the page's key, plus a FORCED preload of the next page's key when the next page is a full page. When the pager comes back to the front: a refresh of the owner's key, then POLITE on the current page's key. | While the page is laid out offscreen, its slot shows a still (non-animated) skeleton of the ad template, so the page slides in at its final layout instead of the ad block growing from zero height after the slide; the real ad binds once the page is resumed. Pages change only through the page's own controls; swipe is locked. |
| Full page | The page's `native_fullN` | As a content page. Full pages are the only pages preloaded one screen ahead, FORCED (it is asked together with the previous page's own request, so a POLITE preload would always be gate-skipped; it requests nothing if the full page already holds or is loading an ad, and never changes the owner): when the page before is selected, or at LFO's Done when the full page comes first. Reached while that preload is still loading, the page sends no extra request (its claim is gate-skipped) and shows the pool's newest ad until its own fill lands. | X appears `native_flow.pool.onboarding.fullscreen.skip.delay_ms` (2 s) after the page first resumes; the page advances by itself `native_flow.pool.onboarding.fullscreen.auto_next.delay_ms` (10 s) after it was selected, background time included. With nothing to show and no request running, or with its key refused for the run, the page advances at once. Return ads never open over it. |
| Privacy | `native_select` on open, `native_select_alt` on every consent change | On open: POLITE `native_select`. Every check or uncheck: FORCED `native_select_alt`. On return: a refresh of the owned key, then POLITE on the last key asked for. | Continue stays hidden until `native_flow.pool.select.next_reveal_delay_ms` (3 s) after the first consent change and works only while consent is checked. It opens Goal, which claims its own ad POLITE when it opens. Details: [Privacy → Goal](privacy-goals-screen.md#under-shared_pool). |
| Goal | `native_select` on open, `native_select_alt` on every option tap | As Privacy, with option taps as the selection. | Next stays hidden until the same delay after the first tap and works while at least `minSelection` options are picked. It records the goals, shows the splash interstitial's leftover when `native_flow.pool.goals.exit_reuse_splash_inter` is `true`, then completes onboarding without a paywall. |
| Resume screen | `native_resume` | Every request is POLITE and never jumps the gate. On open and on every return: a refresh of a held `native_resume` only while no other pool request runs (otherwise logged as `gate_skip refresh key=native_resume`); without that refresh, POLITE `native_resume`. | Opens on return when `app_resume.type` is `native_resume` and the app stayed in the background at least `native_resume.app_resume_load_delay_ms` (default 0), during the flow and after it in the same process. It never becomes the owner, so it shows the same pick as every other slot: during the flow usually the current screen's ad, after the flow the newest leftover not shown yet, otherwise the one shown most recently. While the user is on an ad landing page, the held ad is paused; returning redraws that same ad and does not start a replacement request. Start and Back go through `inter_resume` as on the classic screen; the return from that ad, which only closes the screen, requests nothing. |

Without Privacy → Goal the pager's last page ends the flow, with the paywall when one is wired, and never shows an exit interstitial. System Back does nothing on any stage.

**Left out from `PER_SCREEN`.** The splash's LFO1 preload, the LFO tap hint and device-language promotion, the LFO2 swap and its timers, `click_action` (a native click neither navigates nor reloads), OB5, pager swipe, the exit interstitial after the pager (`inter_after_ob3`, whatever `onboarding.exit_interstitial.enabled` says) and the classic fullscreen delays (`onboarding.fullscreen.skip.delay_ms`, `onboarding.fullscreen.auto_next.delay_ms`). Two SDK features are kept with their classic keys:

- The language confirmation popup (`lfo.confirm_dialog.enabled`, `lfo.confirm_dialog.show_from_tap`, a re-tap of the selected row). While it is open it owns the pool with `native_popup_lang`; closing it hands ownership back to LFO.
- The OB1 back button (`onboarding.steps.ob1.back.enabled`, OB1 as the first page). It returns to LFO with the picked language kept, the confirm button visible at once and the slot on `native_lang_alt`.

**Privacy → Goal.** Off by default (`privacy_goals_screen.enabled = false`) in both flows, and it needs `goal.options`. DashDrama's first-open flow ends on its goal screen, so parity needs it on; without it the pooled flow ends after the pager. Privacy comes with Goal, as in `PER_SCREEN`, although that flow has no step between the pager and its goal screen. See [Privacy → Goal](privacy-goals-screen.md#under-shared_pool).

**Ads on return.** The pool's resume screen needs `app_resume.type = native_resume` (the default) and `native_resume` declared and enabled in `ad_config`; with another type, return ads follow that type as described in [Ads on return](#ads-on-return). Like the classic screen, it opens after the app stayed in the background at least `native_resume.app_resume_load_delay_ms` (default 0). After the flow the pool keeps its ads until the process ends, so the resume screen, DashDrama's welcome back, shows onboarding leftovers, each for at most one hour after its fill, and requests `native_resume` only while it is open, always POLITE. This lasts until process death or a later `PER_SCREEN` run in the same process; the next cold start uses the regular resume screen. It is unrelated to the SDK's Welcome Back screen.

**Purchases.** Once the user is premium, every pooled slot hides and the pool stops requesting, including the next floor of a chain already running. The ads the pool already holds are released at the purchase only when the host calls `AdGate.installPremiumObserver(appScope, Billing.isPremium)` as in [BillingKit](billing-integration.md). Without it they stay in memory, never shown, until the flow ends or, for a purchase after the flow, until they expire one hour after their fill, a `PER_SCREEN` run releases the pool, or the process ends.

**Revenue and reports.** Revenue is attributed per ad unit: before each floor the pool registers that ad unit ID under the placement requesting it, and the paid event looks the placement up by ID. A borrowed ad therefore reports under the placement that loaded it, not the screen that showed it. An ID shared by several placements (Privacy and Goal both use `native_select`; the sample shares one test ID per format) reports under whichever placement requested it last, which skews per-placement reports; give each placement its own IDs. `ad_request` is sent once per new load chain (a return refresh sends none), not per slot. A chain that ends without an ad sends one `ad_load_failed` with no ad unit ID; a failed refresh sends none, since the old ad stays. A POLITE load skipped by the gate sends no `ad_skipped`. Segment dashboards by `ob_native_flow`.

**QA with DashDrama's 4-page shape.**

1. Put this in `app/src/main/assets/onboarding_config.json` and supply `goal.options` in code ([Privacy → Goal](privacy-goals-screen.md)):

   ```json
   {
     "native_flow": { "type": "SHARED_POOL" },
     "onboarding": { "order": ["ob1", "ob2", "full1", "ob3"] },
     "privacy_goals_screen": { "enabled": true }
   }
   ```

   Keep `privacy_goals_screen.enabled` `true`: the SDK default is `false`. To try the pool on your own pages instead, the asset needs only `{"native_flow":{"type":"SHARED_POOL"}}`. A remote `native_flow.type` outranks the asset.
2. In `ad_config.json`, turn on `native_ob1` and `native_full1` (off in the sample) and set `"isEnable": false` on `native_ob2` and `native_ob3`. Do not delete those two keys: a disabled key leaves its page without a slot, while a deleted key falls back to any unit your `AdsConfig` sets in code (for example `contentStepNative`). Keep `native_lang`, `native_lang_alt` and `native_resume` on, and add `native_select` and `native_select_alt` as in [Privacy → Goal](privacy-goals-screen.md#ads-and-completion) (the sample does not declare them), each with a test `ids` floor in `ad_config_debug.json`.
3. Clear app data and restart the process, then walk LFO → OB1 → OB2 → Full1 → OB3 → Privacy → Goal → your app. OB2 and OB3 show no ad.

**Reading the log.** Call `OnboardingSdk.setFlowLogging(true)` and filter Logcat on `OB_FLOW`. Pool lines read `[   +1234] LOAD   | pool | request key=language1 tier=0 refresh=false`: `key` is the pool key (`language1`, `language2`, `language_confirm`, `step_ob1`, `fullscreen_full1`, `step_partner_privacy`, `step_partner_goal_alt`, `native_resume`…) and `tier` is the 0-based position in the key's `ids`.

| Line after `pool \|` | Meaning |
| --- | --- |
| `activate` | A `SHARED_POOL` run started. `classic_run` means a `PER_SCREEN` run released the pool; `deactivate` means `OnboardingSdk.reset()` did. |
| `flow stage=…`, `stage -> …`, `ob1 back -> language` | The stage shown, a stage change, the OB1 back button. |
| `adopt language1 status=…` | Whether the LFO took over a splash LFO1 preload. Normally `none`, since the SDK splash does not preload LFO1 for a `SHARED_POOL` run. If LFO1 was preloaded anyway: `ready` (its ad was taken over), `pending` (its running load was joined), `cancelled_queue` (its queued load was cancelled and LFO requests its own), `none` (the splash load failed or never started). |
| `request key=… tier=… refresh=…` | One floor requested; `refresh=true` for a refresh on return. |
| `fill key=… refresh=…` | The placement now holds a new ad. |
| `fail key=… tier=…`, `tier_timeout key=… tier=…` | That floor did not fill or did not answer in time; the next floor follows. |
| `exhausted key=… refresh=… why=…` | The chain ended without a fill: `exhausted` (every floor tried), `refused reason=…` (a guard closed mid-chain), `adoption_failed`. A refresh keeps the ad it held. |
| `gate_skip key=…` | A POLITE load skipped because another request was running; no analytics event is sent. |
| `gate_skip refresh key=native_resume` | The resume screen did not refresh its held ad because another pool request was running; its POLITE request for `native_resume` follows under the same gate. No analytics event is sent. |
| `config_skip key=… reason=…` | Refused for the run (`premium`, `no_provider`, `ads_off_remote`, `ads_off_config`, `placement_off_remote`, `no_ad_unit`, `ua_gate`); the screen does not become the owner. |
| `transient_skip key=… reason=…` | Refused for now (`requests_held`, `offline`, `consent_not_granted`); the screen still becomes the owner. |
| `refresh_skip key=… reason=…` | A refresh refused by the same guards; the held ad stays. |
| `expired key=…` | The held ad passed `native.cache.max_age_ms` and was dropped. |
| `stale_fill key=…` | A fill arrived after its floor timed out or the pool was released; it is destroyed unshown. |
| `stuck_loading key=… refresh=… ms=…` | A request still running after `stuck_loading_log_ms`; log only. |
| `release_all loaders=…` | Every pooled ad was released: a purchase, a `PER_SCREEN` run or `OnboardingSdk.reset()`. |

`OnboardingSdk.dumpState()` is a suspend function; log it from a coroutine, for example `lifecycleScope.launch { Log.d("OB_STATE", OnboardingSdk.dumpState()) }`. It includes the run's native flow type, whether the pool is active and routes return ads, the run's gate skips per key, the owner, whether the gate is open, and one line per placement, for example `language1 #0 state=Showing(ts=…) ad=held inFlight=false refreshing=false adopted=false fillAtMs=… saved=2` (`saved` is the number of floors in its saved request).

**Deviations from DashDrama 1.0.6.**

- The splash's own natives (the bottom slot, `native_fs`) stay outside the pool and its gate.
- The SDK's ad guard (premium, consent, network, request hold, UA gate, placement switches) is checked before every request and every floor; an LFO without an ad unit hides its slot.
- A purchase hides every slot, stops pool requests and releases the pool's ads (at once with `AdGate.installPremiumObserver`, otherwise when the flow ends; see Purchases above).
- Privacy is an SDK step, run as a select screen.
- `click_action` is ignored.
- Ads are ordered by one strictly increasing timestamp, so two ads never tie; DashDrama breaks ties by map order.
- The language-completed events are sent at the Done tap; the language is saved after the LFO exit ad (the splash interstitial's leftover) closes.
- A slot's ad view is destroyed when its screen goes away (`view.destroy_on_teardown`); the ad stays in the pool.
- A refresh counts as a running request.
- Ad expiry (one hour) and the wait per floor (30 seconds) are the SDK's values.
- The language confirmation popup and the OB1 back button are kept.

</details>

## 8. Final checks

- [ ] If using grouped settings, verify remote overrides plus first-run offline local fallback and offline reuse of valid remote cache, per the [Firebase checklist](firebase-integration.md#remote-notes).
- [ ] A debug build opens the splash, Logcat tag `AdRemoteConfig` shows `Loaded ad_config.json with <n> placements (test ids=true)`, where `<n>` matches the example you shipped, and `OB_FLOW` reports no config or provider error.
- [ ] After a remote fetch, `OB_FLOW` has no `ad_config remote omits …` line for a placement you edit in the console. A listed key keeps the app's values whatever the console sets under another name: publish the field under that key, or bind your key with `fromAdConfig(mapOf(...))`.
- [ ] Walk LFO → OB → MainActivity on test ads; after enabling `native_full1/2`, FULL1 sits after OB1 and FULL2 after OB2, and the final interstitial, once enabled (`onboarding.exit_interstitial.enabled`, off by default), is owned by the SDK alone. LFO opens only after the splash interstitial is dismissed; with the final interstitial enabled, MainActivity is already there when it closes.
- [ ] LFO: selecting a language then pressing Back shows Save and stays on the screen; re-selecting the current language opens the popup immediately, while another language waits for the configured total tap count.
- [ ] Denying notifications still continues; Home and return from splash, LFO, the popup and OB do not navigate twice. After enabling `native_ob1..4`, a native click on an OB page advances the step on return; on LFO/the popup it stays and binds the replacement ad once ready.
- [ ] Enable `native_ob1`, `native_ob2` and `native_full1`, then disable `native_ob2`: content page 2 still shows and does not borrow page 1's native. Disable `native_full1` again: the ad-only page is skipped. Disable `inter_splash` and `inter_after_ob3`: the destination screen is still reached.
- [ ] If you use `SHARED_POOL`, walk the [4-page QA shape](#shared_pool-native-flow) with `OB_FLOW` on: the log shows `pool | adopt language1 status=none` and then the LFO's own `pool | request key=language1`, since the splash does not preload LFO1 for a `SHARED_POOL` run; OB2 and OB3 show no ad, and Goal's Next completes onboarding.
- [ ] Test with no network: by default the connection prompt appears; if you opted into offline support the flow still continues on the SDK timeout and does not hang on an app callback.
- [ ] Relaunch after completion: through the splash into your app, with no OB rerun; your screen is already there when the splash interstitial closes. Clear app data to test first-open; closing the app mid-OB and reopening must start at LFO after the splash.
- [ ] Your app screens use the selected language; check both the translations and the language split configuration when shipping an AAB.
- [ ] Before release: replace `admob_app_id` in `res/values/id_ads.xml` and every ad unit ID in **`ad_config.json`** with your app's IDs. Keep the debug JSON on test IDs. To keep the test App ID in debug, add an `admob_app_id` override in `app/src/debug/res/values/id_ads.xml`.

| Symptom | Quick check |
| --- | --- |
| Crash on launch | The AdMob/Meta metadata, real Meta credentials and your Application in the merged manifest |
| OB is empty or skipped | `install` before `configure`; use `steps(...)` in step 4 for your app's content, and check whether the remote cache has disabled the flow or a step |
| No ads | The file loaded, the key mapping, `isEnable`, consent/premium state, the remote flags; do not add a timer to show one anyway |
| Debug uses the wrong IDs | `ad_config_debug.json` has test `ids` for every enabled placement key (the `AdRemoteConfig` `WARN` lists enabled keys without one); `setAllowRemoteOverrideInDebug(true)` is not enabled in the standard setup |
| Remote `ad_remote_config` never applies (`AdConfig` logs `cleared`) | The Console parameter name: the SDK reads `ad_remote_config`, then `ads_remote_config`; any other name needs `FirebaseAdConfigSource("<name>")` |
| Remote changed the IDs but OB still uses the old ones | Install `FirebaseAdConfigSource`, use `AdsConfig.fromAdConfig()` with the correct keys and let splash refresh. Firebase's minimum fetch interval is 12 hours unless your app lowers it. Debug requests the test IDs of `ad_config_debug.json`; every other `ad_remote_config` field and the grouped settings still apply. |
| Native style/reporting does not separate per slot in testing | The demo IDs of one format are shared; the provider looks style/placement back up by ID in places. Use separate IDs when verifying the real configuration |

## Files your app actually needs

| File | What to do |
| --- | --- |
| `settings.gradle`, `app/build.gradle` | Merge the build configuration and the two dependencies |
| `app/src/main/AndroidManifest.xml` | Metadata, your Application and the splash launcher |
| `app/src/main/res/values/id_ads.xml` | AdMob App ID, Meta credentials and the Adjust token/event tokens |
| `app/src/main/assets/ad_config.json`, `ad_config_debug.json` | Copy both sample JSON files |
| `app/src/main/assets/ad_behavior_config.json`, `onboarding_config.json` (optional) | Create/copy only to customize local defaults; see [fallback rules](firebase-integration.md#local-defaults). |
| Your app's `strings.xml` and drawables | Copy, translations and images for the four content pages |
| `AppAdPlacement.kt` | The catalog of OB keys and your app's own keys; no ad unit IDs |
| [OnboardKitSetup.kt](examples/ads-onboarding/OnboardKitSetup.kt) | App content/resources and live standard ad_config placement bindings |
| Your existing Application or [PartnerApp.kt](examples/ads-onboarding/PartnerApp.kt) | Initializes the SDK once and picks the destination screen |
| `SplashActivity.kt` | Extends the SDK splash |

`MainActivity` is your app's existing destination screen. Apart from `AppAdPlacement.kt`, no file is needed to translate JSON into ad config — every entry point takes the placement key directly. For this basic flow you do not need to copy `AppConstants`, `RemoteConfigUtils`, DevConfig, Hilt or the example's paywall/uninstall screens.

[Ads reference](../ads/README.md) · [OnboardKit reference](../onboardkitorigin/README.md)
