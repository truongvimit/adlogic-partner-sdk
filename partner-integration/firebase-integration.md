# Firebase — Analytics and Remote Config

[English](firebase-integration.md) · [Tiếng Việt](firebase-integration.vi.md) · [हिन्दी](firebase-integration.hi.md)

[← Choose a guide](README.md) · [suite-firebase API](../suite-firebase/README.md)

Do step 1, then pick the parts your app needs: Analytics (step 2), remote ads (step 3), remote paywall (step 4). `suite-firebase` already provides Firebase Analytics, Remote Config and Trackkit.

## 1. Configure Firebase for your app

Complete the [build setup](../README.md#build-setup), JDK 17 and `minSdk 24+`. Register the Android app on Firebase Console with your real `applicationId` and download `google-services.json` into `app/`. Firebase issues this file; do not copy the example's. If debug has a different application ID, register the matching app and put its file in that source set. [Firebase: Android setup](https://firebase.google.com/docs/android/setup).

Keep your existing Google Services configuration. If you do not have the plugin yet, declare `googleServicesVersion` in `gradle.properties` to match your app toolchain and merge this into the root `build.gradle`:

```groovy
buildscript {
    def googleServicesVersion = project.providers.gradleProperty('googleServicesVersion').get()
    repositories { google(); mavenCentral() }
    dependencies { classpath "com.google.gms:google-services:$googleServicesVersion" }
}
```

In `app/build.gradle`, keep the Android/Kotlin plugins you already use:

```groovy
plugins { id 'com.google.gms.google-services' }

def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

Keep your current BOM if the app already configures Firebase; do not add Analytics/Remote Config again. Add Ads/PayKit only when you use that kit's remote source.

## 2. Send analytics through Tracker

In your existing Application, install [Tracker](trackkit-integration.md#2-install-and-choose-event-destinations), then add the sink **before installing the SDKs that emit events**:

```kotlin
import io.suite.firebase.FirebaseSink
import io.trackkit.Tracker

// In Application.onCreate, in the order given above:
Tracker.addSink(FirebaseSink())
```

The app sends events through `Tracker`; the sink forwards them to Firebase. The SDK already sends ads/purchase events and `ad_impression`; do not log them again in UI callbacks.

### Initial consent

Keep the consent configuration your app has chosen. The library declares no initial values; while both Tracker axes are still `UNKNOWN`, the sink leaves your Firebase settings unchanged. If your app chooses to start with all four values denied, put this inside `<application>`:

```xml
<meta-data android:name="google_analytics_default_allow_analytics_storage" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_storage" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_user_data" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_personalization_signals" android:value="false" />
```

This is your app's choice, not an SDK default. `ConsentCenter` is already wired to Tracker and always maps analytics to granted and ads to the personalization answer; the UMP form is not a separate analytics consent dialog. For an app with its own consent flow, see [Trackkit](trackkit-integration.md#4-configuration-table). Collection options are in the [table below](#5-configuration-table).

**Version requirement:** use SDK `5.3.6` or newer for grouped settings and `AdsConfig.fromAdConfig()`, with the same version for all modules. Adding Firebase keys alone does not update an older SDK.

## 3. Remote ads and onboarding JSON

<a id="remote-json"></a>

### 3.1. Install the source and publish three String parameters

Complete [Ads + OnboardKit](ads-onboarding-integration.md) first. In your Application, after `AdRemoteConfig.initializeFromAssets(this)`, install the source once. Keep `OnboardingSdk.install` and `OnboardKitSetup.configure()` before opening the splash:

```kotlin
import com.ads.module.config.AdConfig
import io.suite.firebase.FirebaseAdConfigSource

AdConfig.install(FirebaseAdConfigSource())
```

`FirebaseAdConfigSource` reads the ad-unit document and both grouped settings documents. No separate source, manual `getString`, field-by-field setter or additional Firebase fetch is needed.

1. Open your app's Firebase project → **Remote Config → Parameters**.
2. Keep the existing `ad_remote_config` key. Add **`ad_behavior_config`** and **`onboarding_config`**, with data type **String**.
3. Paste the corresponding JSON object's contents into each parameter's default value, starting with the files below. Paste raw `{ ... }`: no Markdown fences, outer quotes, escaped JSON string or wrapper named after the parameter. These are parameter values, not Firebase's whole-template import format.
4. Edit the values you need, save, then **Publish changes**. For an A/B test or condition, supply a JSON object for that parameter's variant too. The SDK merges valid fields with local defaults; a new remote object replaces the previous remote overrides, so include any overrides you want to retain.

| Firebase parameter (String) | Content to paste | Purpose |
| --- | --- | --- |
| `ad_remote_config` | [ad_config.json](examples/ads-onboarding/ad_config.json), with your production IDs | Existing unit IDs, floors, switches and CTA fields. Keep the existing parameter name. |
| `ad_behavior_config` | [ad_behavior_config.json](examples/ads-onboarding/ad_behavior_config.json) | Ad-format behavior, timeouts, reload/cache and native CTA radius. |
| `onboarding_config` | [onboarding_config.json](examples/ads-onboarding/onboarding_config.json) | Splash/LFO/OB behavior, native templates, X/Skip, swipe and preload. |

`ad_config.json` and `ad_config_debug.json` are local asset filenames; the default Firebase source reads **one** ad-unit key, `ad_remote_config`. Do not create new `ad_config`, `ad_config_debug`, `ad_behavior_config_debug` or `onboarding_config_debug` parameters for this setup. Debug keeps its test ad IDs pinned by default **but still reads the two new settings parameters**. Use your test project/conditions for experiments. Existing `ob_*` keys remain compatible; do not nest them in these JSON objects. [Firebase parameter types and conditions](https://firebase.google.com/docs/remote-config/parameters).

`ObSplashActivity` already calls `AdConfig.refresh()`. With the sample's `AdsConfig.fromAdConfig()`, IDs and settings resolve after fetch without calling `OnboardKitSetup.configure()` again in `onRemoteFetched`. Keep that hook only for app-specific work. Without SDK splash, await `AdConfig.refresh()` from your coroutine before the relevant screen/request, after initializing the kits.

<a id="local-defaults"></a>

### 3.2. Customize local defaults when remote is unavailable

Both JSON defaults are bundled in the SDK. **No app-side file is required when those defaults suit your app.** To change the offline/default behavior:

1. Create `app/src/main/assets/` if needed.
2. Create `ad_behavior_config.json` and/or `onboarding_config.json` with the exact names above. Copy the matching full sample, or write only the fields you want to change as below. App assets override same-named SDK assets.
3. Keep `schema_version: 1`, use the documented types and enums, and omit unused fields instead of setting them to `null`. Missing fields use the SDK defaults unless your existing host configuration supplies a fallback.
4. Rebuild and restart the app process. The SDK reads these assets at initialization; editing a file is not a runtime remote update. Do not add a parser or copy these values into Firebase `setDefaultsAsync`.

`app/src/main/assets/ad_behavior_config.json`:

```json
{
  "schema_version": 1,
  "native": {
    "load": { "tier_timeout_ms": 25000 }
  }
}
```

`app/src/main/assets/onboarding_config.json`:

```json
{
  "schema_version": 1,
  "splash": { "permissions": { "no_internet_prompt_enabled": false } },
  "lfo": { "native_template": "COMPACT" },
  "onboarding": {
    "navigation": { "lock_pager_swipe": false },
    "fullscreen": { "skip": { "delay_ms": 1500 } }
  }
}
```

This local example disables the SDK connection prompt so you can exercise the offline flow. Offline does not produce an ad fill; verify the resolved configuration and navigation, and test the visual template with test ads online.

These values are **custom examples**, not changes to the SDK defaults. The copied full samples match the SDK's actual defaults. No translation of JSON field names or enum values is needed.

| Situation | Values the SDK uses |
| --- | --- |
| Successful fetch with valid fields | Remote fields override the app's local JSON. |
| First run, fetch fails/timeouts, no valid remote cache | Custom local JSON → existing host fallback → bundled SDK defaults. |
| Fetch fails/timeouts after a successful fetch | Keep the last valid remote snapshot/cache; fields not supplied by it still use local fallbacks. **Failure does not force local values over a valid remote cache.** |
| Successful fetch omits a field/parameter, or a field has an invalid type/enum/range or `null` | Drop that remote field's old override and use local/host/default for it. Valid `false`/`0` values are preserved. |
| Malformed/blank whole JSON, or unsupported schema | Keep that document's last valid snapshot; with no valid remote snapshot, keep local/defaults. |

To remove all remote overrides for one of the new documents, publish `{}` or `{"schema_version":1}` and fetch successfully. A blank String is malformed JSON and keeps the previous valid snapshot. A successful remote document is persisted across process restarts, so changing a local file alone does not outrank cached remote fields. Test first-run local fallback on a test installation without remote cache, or remove the overrides successfully before testing offline. For the examples above, an uncached offline run uses native timeout **25000 ms**, LFO **COMPACT**, swipe unlocked and Skip delay **1500 ms**.

A custom/sparse app asset explicitly assigns all valid fields present, including `false`/`0`. An unchanged copy of the complete SDK default asset preserves existing constructor/setter fallbacks. A default value published on Firebase is a **remote** value; it differs from the SDK's bundled local default. Firebase in-app defaults are not accepted as fetched remote data by this source.

<a id="remote-notes"></a>

### 3.3. Timing, compatibility and QA

- Use an SDK build containing both grouped settings and `AdsConfig.fromAdConfig()`, with matching versions for all modules. Merely adding the keys cannot add this behavior to an older SDK.
- `ALTERNATE` waits for the remote step to finish (or its timeout/fallback) before requesting splash ads. `SAME_TIME` may start **splash banner/interstitial** earlier. **LFO1 preload is scheduled after the remote step in both strategies**; LFO `PARALLEL` means not waiting for splash interstitial loading to settle. The strategy is read at splash start, so a newly fetched change to it takes effect on a subsequent splash attempt.
- SDK template overrides select the native frame before `positionCTA`; CTA colors/height/components stay in `ad_remote_config`. Keep `R.layout`, resource references, system bars, orientation and progress indicators in app code. Consent/premium and app-owned request gates still apply.
- Firebase fetches are shared; a successful fetch is reused in the process and Firebase's fetch interval also applies. During Console QA, restart the process and account for that interval; opening an Activity again does not guarantee a fresh network fetch.
- In debug, `AdConfig.refresh()` may return `false` because ad IDs are pinned even though both settings documents were applied. Do not use that Boolean as a success flag for the two new documents.
- Verify a valid remote override, missing/invalid fields, first-run offline local fallback, and offline reuse of a previous valid remote. JSON files shipped locally must be rebuilt into the app.

See the [settings reference, ownership and full defaults](remote-settings.md) before changing fields for UA/MO experiments.

## 4. Remote paywall JSON

Complete [PayKit local](paywall-integration.md) first, including the dependency and the resource fallback. In your Application, after `PayKit.install`, call:

```kotlin
import io.paykit.PayKit
import io.suite.firebase.FirebaseConfigSource

// In Application.onCreate, in the order given above:
PayKit.configSource(FirebaseConfigSource())
```

Create a **String** parameter `paywall_config` on the Console, paste the [sample JSON](examples/paywall/paywall_config.json), change the catalog/copy to your app's, then Publish. You can add a non-empty `placements` to override the open points, per the [PayKit table](paywall-integration.md#5-json-and-optional-configuration-tables).

In the `onCreate` of the first Activity that uses the AndroidX lifecycle (the SDK splash included), after the Application has installed PayKit and the source, start the sync:

```kotlin
import androidx.lifecycle.lifecycleScope
import io.paykit.PayKit
import kotlinx.coroutines.launch

// Trong Activity.onCreate, sau super.onCreate:
lifecycleScope.launch { PayKit.sync() }
```

Open the paywall as the [PayKit guide](paywall-integration.md) shows; do not sync again on every click. Remote is used only once the sync has finished; before that, or after a failed fetch, the paywall opens with the current local/cache. This also works with a splash/OB gate: splash ads do not sync PayKit themselves and the app does not launch another paywall after the gate.

## 5. Configuration table

| Configuration | Default / change only when needed |
| --- | --- |
| `FirebaseSink(collectionFollowsConsent)` | `true`: once consent is decided, collection is on when analytics is granted and off otherwise. `false`: the app manages collection itself; the sink still updates Consent Mode. While both axes are still `UNKNOWN`, your Firebase settings are left as they are. |
| Consent mapping | Analytics → `ANALYTICS_STORAGE`; ads → `AD_STORAGE`, `AD_USER_DATA`, `AD_PERSONALIZATION`. Once one axis is decided, an axis still `UNKNOWN` is sent as denied. |
| `FirebaseSink.setDefaultEventParameters(...)` | No extra defaults. Use it when you need params on the events Firebase collects itself too; `Tracker.setDefaults` applies only to events that go through Tracker. |
| `FirebaseAdConfigSource(key)` | `ad_remote_config`; change it when your Console uses a different key. |
| `FirebaseConfigSource(key)` | `paywall_config`; change it when your Console uses a different key. |
| Ads/PayKit fetch | Installing a source does not fetch. The two sources share one fetch and keep a successful result for the process; a failure permits a retry, still subject to Firebase's minimum fetch interval. |
| Blank / Firebase in-app defaults | Both sources ignore them; do not use `setDefaultsAsync` in place of the kit's local JSON. |
| Offline / invalid JSON | The kit keeps its current config; PayKit's remote cache takes priority over the bundled fallback. No separate fallback code is needed. |
| Debug ads / paywall | Debug ad-unit IDs stay pinned by default, but both grouped settings documents still apply. PayKit has no equivalent pinning. Use suitable test projects/conditions. |

Call only `AdConfig.refresh()`/`PayKit.sync()`; no extra `fetchAndActivate` is needed. The `ob_*` remote flags have their own OnboardKit adapter; the two new JSON documents are applied by the ads source.

## 6. Verify and required files

- [ ] The build resolves Google Services and the application ID matches the Firebase JSON.
- [ ] `Tracker.sinkIds()` contains `firebase`; app and SDK events appear once, according to the chosen consent.
- [ ] Remote is published; test online, offline and invalid JSON against the local fallback.
- [ ] Debug ads still use the pinned asset; the paywall opens from local/cache even before remote finishes.

To watch Firebase DebugView, run `adb shell setprop debug.firebase.analytics.app <applicationId>` and open the app; turn it off with `adb shell setprop debug.firebase.analytics.app .none.`. [Firebase DebugView](https://firebase.google.com/docs/analytics/debugview).

| File | What to do |
| --- | --- |
| Existing root/app Gradle, `gradle.properties` | The plugin if you do not have it, and the dependency. |
| `app/google-services.json` or the matching source set | The file Firebase issues for that exact app. |
| Existing Application / manifest | Tracker sink, the remote sources you use, initial consent per your app. |
| Ads/PayKit local JSON | Keep the fallback from the matching guide; do not create a copy just to fetch remote. |
