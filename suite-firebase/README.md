# suite-firebase

> The suite's single Firebase adapter: GA4 sink, ad-config source, paywall-config source, and the
> one Remote Config client the two sources share.

`:trackkit`, `:ads` and `:paykit` are all vendor-free. This module supplies their Firebase
implementations; the app wires each one in a single line.

## Requirements

| | |
|---|---|
| minSdk | 24 |
| JDK | 17 |
| `app/google-services.json` | required |
| Plugin on the app module | `com.google.gms.google-services` |
| Root `buildscript` classpath | `com.google.gms:google-services:4.4.3` |
| Consent Mode defaults | declare the `google_analytics_default_allow_*` `<meta-data>` in the **app** manifest, one per consent type the sink sets (`ANALYTICS_STORAGE`, `AD_STORAGE`, `AD_USER_DATA`, `AD_PERSONALIZATION`). This module's manifest declares none, so it cannot override yours. |
| For `FirebaseAdConfigSource` | `:ads` on the app classpath (`compileOnly` here) |
| For `FirebaseConfigSource` | `:paykit` on the app classpath (`compileOnly` here) |

`firebase-bom`, `firebase-analytics` and `firebase-config` are exported as `api` — do not declare
them again unless you want a different BOM version.

## Installation

```groovy
// root build.gradle
buildscript { dependencies { classpath "com.google.gms:google-services:4.4.3" } }
```

```groovy
// app/build.gradle
plugins { id 'com.google.gms.google-services' }

// Replace <tag> with a tag from https://github.com/truongvimit/adlogic-partner-sdk/tags
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"

    // Declare the kits you actually use — suite-firebase does not pull them in.
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:paykit:$sdkVersion"
}
```

Inside this repository the module is wired as `implementation project(':suite-firebase')` instead.

## Quick start

Three lines, each next to the kit it serves.

```kotlin
// 1. GA4 — after Tracker.install(...)
Tracker.addSink(FirebaseSink(collectionFollowsConsent = false))

// 2. Ad units — after AdRemoteConfig.initializeFromAssets(this)
AdConfig.install(FirebaseAdConfigSource())

// 3. Paywall document — after PayKit.install(...)
PayKit.configSource(FirebaseConfigSource())
```

Installing a source does not fetch. The fetch happens when the host calls `AdConfig.refresh(...)`
and `PayKit.sync(...)`, normally on the splash screen. Both go through `RemoteConfigClient`, which
runs one `fetchAndActivate` and hands the same result to every caller.

## Usage

### Default event parameters

`setDefaultEventParameters` is an instance member, so keep the sink in a variable. This replaces
step 1 of the Quick start — register the sink once. The params ride on every Firebase event,
including `first_open`, `session_start` and `screen_view`. Safe to call before `Tracker.install`.

```kotlin
val sink = FirebaseSink(collectionFollowsConsent = false)
sink.setDefaultEventParameters(mapOf("build_channel" to "play"))
Tracker.addSink(sink)
```

The argument is `Map<String, Any?>?`. Pass `null` to clear the defaults.

## Configuration

| Class | Parameter | Default | What it does |
|---|---|---|---|
| `FirebaseSink` | `collectionFollowsConsent` | `true` | On denial also calls `setAnalyticsCollectionEnabled(false)`. Pass `false` to keep pure Consent Mode, where Firebase still sends consent-less pings. Either way the sink only applies the resolved UMP decision on top of the manifest defaults above — without them there is nothing for it to sit on. |
| `FirebaseAdConfigSource` | `key` | `"ad_remote_config"` | Remote Config parameter holding the ad-config JSON. |
| `FirebaseConfigSource` | `key` | `"paywall_config"` | Remote Config parameter holding the paywall JSON. |

## Remote Config parameters

Create these on the Firebase console. Both are one String parameter holding a whole JSON document.

| Parameter | Type | Read by | Content |
|---|---|---|---|
| `ad_remote_config` | String | `FirebaseAdConfigSource` | The same document as `assets/ad_config.json` |
| `paywall_config` | String | `FirebaseConfigSource` | The paywall document |

Only values published on the console are read — an in-app default or a blank string is ignored.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Log `Firebase Remote Config unavailable — is Firebase initialised?` | No `google-services.json`, or the plugin is not applied | Add both, then rebuild |
| Log `Remote config fetch failed: …` | Network, throttling, or a wrong project | Retry; console fetch intervals apply |
| Parameter set, `AdConfig.refresh()` still returns false | The value is an in-app default, is blank, or the document has no placements | Publish the value on the console |
| `AdConfig.refresh()` returns false on a debug build | Any debuggable build is pinned to its assets by `AdRemoteConfig.initializeFromAssets` — the debug asset file does not have to exist | Expected; use a release build, or `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` |
| Warning `sink 'firebase' already registered` | `Tracker.addSink(FirebaseSink())` called twice | Register it once |
| `first_open` and retention drop after users decline the UMP form | `collectionFollowsConsent = true` | Pass `false` |
| A `Boolean` param reads as `1` / `0` in GA4 | GA4 stores String, long and double only | Expected; the sink encodes rather than drops it |

## License

MIT — see [LICENSE](../LICENSE).
