# suite-firebase

Connects SDK analytics to Firebase Analytics and optionally reads ad/paywall JSON from Firebase
Remote Config. Use only the integrations your app needs; this module does not include ads or PayKit.

## Install

Follow the [root build setup](../README.md). Add `google-services.json` for your app to `app/`,
then configure the plugin if your project does not already have it:

```groovy
// Root build.gradle
buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath "com.google.gms:google-services:4.4.3" }
}
```

```groovy
// app/build.gradle: keep your existing Android/Kotlin plugins.
plugins { id 'com.google.gms.google-services' }

def sdkVersion = '5.2.5' // Use the same published tag for every SDK module.
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

Requires minSdk 24 and JDK 17. Firebase Analytics, Remote Config and their BOM are included.
Add `ads` only for `FirebaseAdConfigSource`, or `paykit` for `FirebaseConfigSource`.

## Send analytics

After [Tracker installation](../trackkit/README.md) in `Application.onCreate`, register one sink:

```kotlin
import io.suite.firebase.FirebaseSink
import io.trackkit.Tracker

Tracker.addSink(FirebaseSink())
```

The sink forwards events routed through `Tracker`.

## Configure consent

Set initial consent values in your app manifest according to your consent flow and the
[Firebase consent defaults guide](https://developers.google.com/tag-platform/security/guides/app-consent?platform=android). For example,
an app that starts with all four values denied puts this inside `<application>`:

```xml
<meta-data android:name="google_analytics_default_allow_analytics_storage" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_storage" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_user_data" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_personalization_signals" android:value="false" />
```

The library declares no defaults. While both Tracker consent values are unknown, the sink
leaves the app's initial values unchanged.

`FirebaseSink()` defaults to `collectionFollowsConsent = true`: consent updates also enable or
disable analytics collection according to Tracker's **analytics** value. With
`FirebaseSink(collectionFollowsConsent = false)`, the sink still updates Firebase consent but
leaves the collection-enabled setting to your app. Choose this deliberately; it does not grant consent.

When you use `ConsentCenter`, its mapping grants the analytics axis and maps ad personalization
separately. Apps with another consent flow must publish their own Tracker consent; see
[Trackkit consent setup](../trackkit/README.md).

## Optional: load remote ad or paywall configuration

Complete the [ads setup](../ads/README.md) or [PayKit setup](../paykit/README.md) first, then add
the matching source in `Application.onCreate`:

```kotlin
import com.ads.module.config.AdConfig
import io.paykit.PayKit
import io.suite.firebase.FirebaseAdConfigSource
import io.suite.firebase.FirebaseConfigSource

// With ads, after local ad configuration is initialized:
AdConfig.install(FirebaseAdConfigSource())
// With PayKit, after PayKit.install:
PayKit.configSource(FirebaseConfigSource())
```

Keep only the imports and lines for modules your app uses. Installing a source does not fetch.
From a coroutine, call `AdConfig.refresh()` or `PayKit.sync()` before using its remote config.
`ObSplashActivity` already calls `AdConfig.refresh()`; do not add a duplicate call there.

Publish these **String** parameters on Firebase Console → Remote Config:

| Parameter | Content |
|---|---|
| `ad_remote_config` | The same JSON structure as your `assets/ad_config.json`. |
| `paywall_config` | Your paywall JSON, including product IDs and optional placements. |

Both sources accept a custom name through `key = "your_key"`. Blank values and in-app Firebase
defaults are ignored. The two sources share a pending fetch and keep a successful result for
the process; a failure permits a later retry. A failed refresh leaves the kit's existing config.

## Troubleshooting

| Problem | Check |
|---|---|
| Firebase is unavailable | Matching `app/google-services.json`, app plugin and a rebuilt APK. |
| Remote values are ignored | Publish the parameter; check its name, nonblank JSON and the module's parser requirements. |
| Debug ads keep local configuration | A successfully loaded debug asset is pinned by default; see [ads debug setup](../ads/README.md). |
| Analytics stops after a consent update | `Tracker.currentConsent` and your selected collection policy. |
