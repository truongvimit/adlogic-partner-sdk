# suite-firebase

> The suite's single Firebase adapter: GA4 sink, ad-config source, paywall-config source, and the
> one Remote Config client the two sources share.

This module provides Firebase implementations of the tracking and config interfaces exposed by
`:trackkit`, `:ads` and `:paykit`. Those modules do not depend on Firebase.

## Requirements

| | |
|---|---|
| minSdk / JDK | 24 / 17 |
| `app/google-services.json` | required |
| Plugin on the app module | `com.google.gms.google-services` |
| Root `buildscript` classpath | `com.google.gms:google-services:4.4.3` |
| For `FirebaseAdConfigSource` | `:ads` on the app classpath (`compileOnly` here) |
| For `FirebaseConfigSource` | `:paykit` on the app classpath (`compileOnly` here) |

**Consent Mode defaults are yours to declare.** Put the `google_analytics_default_allow_*`
`<meta-data>` entries in the **app** manifest, one per consent type the sink sets
(`ANALYTICS_STORAGE`, `AD_STORAGE`, `AD_USER_DATA`, `AD_PERSONALIZATION`). This module's manifest
declares none. When both Tracker consent axes are unknown, the sink leaves Firebase consent
unchanged; configure the initial values in the app.

`firebase-bom`, `firebase-analytics` and `firebase-config` are exported as `api`, so they are
available transitively. Align any additional Firebase dependencies with the BOM used by your app.

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

## Integration

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
shares an in-flight fetch and caches a successful result for the process. A failed or timed-out
fetch allows a later call to retry.

`collectionFollowsConsent = true` (the default) also sets analytics collection to the value of
`consent.analyticsGranted`. With `false`, the sink still calls Firebase `setConsent` but does not
change the collection-enabled flag. Choose this separately from the ad-personalization setting;
an ads denial alone is not an analytics denial in Tracker.

Both sources take the Remote Config parameter name as a constructor argument, so
`FirebaseAdConfigSource(key = "…")` and `FirebaseConfigSource(key = "…")` work if you name yours
differently.

### Default event parameters

`setDefaultEventParameters` is an instance member, so keep the sink in a variable. The params ride on
subsequent Firebase events, including automatically collected events and `screen_view`. It can be
called before `Tracker.install`; pass `null` to clear.

```kotlin
val sink = FirebaseSink(collectionFollowsConsent = false)
sink.setDefaultEventParameters(mapOf("build_channel" to "play"))
Tracker.addSink(sink)
```

## Remote Config parameters

Create these on the Firebase console. Both are one String parameter holding a whole JSON document.

| Parameter | Read by | Content |
|---|---|---|
| `ad_remote_config` | `FirebaseAdConfigSource` | The same document as `assets/ad_config.json` |
| `paywall_config` | `FirebaseConfigSource` | The paywall document |

Only values published on the console are read — an in-app default or a blank string is ignored.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Log `Firebase Remote Config unavailable — is Firebase initialised?` | No `google-services.json`, or the plugin is not applied | Add both, then rebuild |
| Log `Remote config fetch failed: …` | Network, throttling, or a wrong project | Retry; console fetch intervals apply |
| Parameter set, `AdConfig.refresh()` still returns false | The value is an in-app default, is blank, or the document has no placements | Publish the value on the console |
| `AdConfig.refresh()` returns false on a debug build | Any debuggable build is pinned to its assets by `AdRemoteConfig.initializeFromAssets` | Expected; use a release build, or `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` |
| Warning `sink 'firebase' already registered` | `Tracker.addSink(FirebaseSink())` called twice | Register it once |
| Analytics collection is disabled after a consent update | `collectionFollowsConsent = true` follows the analytics axis | Inspect `Tracker.currentConsent` and the chosen collection policy |
| A `Boolean` param reads as `1` / `0` in GA4 | GA4 stores String, long and double only | Expected; the sink encodes rather than drops it |

## License

MIT — see [LICENSE](../LICENSE).
