# Firebase — Analytics and Remote Config

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

## 3. Remote ads JSON

Complete [Ads + OnboardKit](ads-onboarding-integration.md) first, including both local JSON files and asset initialization. Add the `ads` dependency as that guide describes. In your Application, after the local ad config is loaded, call once:

```kotlin
import com.ads.module.config.AdConfig
import io.suite.firebase.FirebaseAdConfigSource

// In Application.onCreate, in the order given above:
AdConfig.install(FirebaseAdConfigSource())
```

On Firebase Console → Remote Config, create a **String** parameter `ad_remote_config`, paste the [ad_config.json](examples/ads-onboarding/ad_config.json) structure, swap in your own ad IDs, then **Publish**. No separate schema or remote file is needed.

`ObSplashActivity` already calls `AdConfig.refresh()`; keep `OnboardKitSetup.configure` inside `onRemoteFetched` as the OB guide shows, so setup uses the fetched config. An app that does not use the SDK splash calls `AdConfig.refresh()` from a coroutine before the point where it needs the remote config.

A debug asset that loads successfully is pinned by default, so remote does not replace debug ads. To check remote ads, use the test configuration described in the [ads guide](ads-onboarding-integration.md); keep test ad IDs in test environments. OnboardKit's `ob_*` parameters are their own keys on the Console; do not put them in the ads JSON.

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
| Debug ads / paywall | Ads pin the debug asset by default; PayKit has no equivalent pinning. Pick a suitable test Firebase project/conditions. |

Call only `AdConfig.refresh()`/`PayKit.sync()`; no extra `fetchAndActivate` is needed. The `ob_*` remote flags have their own fetch flow, managed by OnboardKit.

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
