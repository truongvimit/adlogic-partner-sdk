# Trackkit — app and SDK events

[← Choose a guide](README.md) · [Trackkit API](../trackkit/README.md)

Three things to do: install Tracker, add a destination for events (a sink), then track your app's own events. Ads, OnboardKit, BillingKit and PayKit already emit their own events.

## 1. Dependency and event catalog

Per the [build setup](../README.md#build-setup), JDK 17 and `minSdk 24+`. Ads, OnboardKit, BillingKit, PayKit and suite-firebase already expose Trackkit as an API dependency; no need to declare it again. An app using Trackkit only declares:

```groovy
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit:$sdkVersion"
}
```

If you have your own events, copy [AppEvents.kt](examples/trackkit/AppEvents.kt) into your app package and change the keys to your real features. No catalog is needed for SDK events or analytics JSON.

## 2. Install and choose event destinations

Merge this into your existing Application, before installing Ads/OnboardKit/BillingKit/PayKit. Do not create a second Application if you already followed the ads guide.

```kotlin
package com.example.app

import android.app.Application
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import io.trackkit.sink.ConsoleSink

class AnalyticsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Tracker.install(this, TrackerConfig(appVersionCode = BuildConfig.VERSION_CODE.toLong()))
        if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
        // Add the production sinks you need here, before any SDK emits an event.
    }
}
```

Register `android:name` if your app has no Application yet. Keep your app module's BuildConfig; do not import a library BuildConfig. If your app already installs Tracker from the ads guide, only add the missing sink at the same place.

| Where your app wants events to go | Extra wiring step |
| --- | --- |
| Logcat while debugging | `ConsoleSink()` in the sample; no external service needed. |
| Firebase Analytics | [Firebase guide, step 2](firebase-integration.md#2-send-analytics-through-tracker). |
| Ad inspection dashboard | [AdTracer guide](adtracer-integration.md), bridge in debug only. |
| Adjust in the ads/OB flow | Not a Tracker sink; the ads SDK initializes Adjust itself and sends revenue. Only fill in the token per [Adjust setup](ads-onboarding-integration.md#adjust-tokens-and-verification). |
| Your own analytics backend | Implement `TrackSink` and `Tracker.addSink`; `id` and `onEvent` are required, add the screen/identity/consent callbacks if you use them. Enqueue network work in your app because callbacks run on the calling thread. |

**Register sinks before any event is emitted.** Events dispatched with no sink are lost; Tracker keeps no queue on disk. A sink with a duplicate `id` is ignored; check with `Tracker.sinkIds()`.

## 3. Track from the real interaction point

Example calls made after the user actually opens a feature, with the feature name defined by your app:

```kotlin
package com.example.app

import io.trackkit.Tracker

fun trackFeatureOpened(featureName: String) {
    Tracker.track(AppEvents.FEATURE_OPEN, mapOf(AppEvents.FEATURE_NAME to featureName))
}

fun trackHomeVisible() {
    Tracker.screen(AppEvents.HOME_SCREEN, "HomeActivity")
}
```

Call each event at one point only: after navigation or when the screen becomes visible. With Compose use a lifecycle/effect, not every recomposition. Firebase can track Activities itself; add a manual screen only when you need a different screen name, and avoid double counting.

Event/param names start with a letter, use only letters, digits and `_`, up to 40 characters; do not use the `firebase_`, `google_` or `ga_` prefixes. Each event takes at most 25 params **including defaults**; use String (up to 100 characters), number or Boolean, and send no sensitive data. Turn on `strictValidation` during QA to catch bad keys, excess params or overlong strings.

**Do not re-log ad request/show/impression/revenue, the OB funnel or purchase/paywall in UI callbacks.** The SDK already emits these events, including paid impressions through `Tracker.adRevenue`.

## 4. Configuration table

| Option | SDK default / when to change it |
| --- | --- |
| `appVersionCode` | 0; the sample reads your app's `BuildConfig.VERSION_CODE`. |
| `sdkVersion` | The SDK supplies this metadata itself; do not set or hardcode it in your app. |
| `reportingCurrency` | `USD`; change it when your app needs another unit for cumulative revenue. Impressions in another currency still reach the sinks but are not added to this accumulator; there is no automatic FX conversion. |
| `consentPolicy` | `SEND_ALWAYS`: dispatch immediately, sinks handle consent/collection. It does not mean the user has consented. |
| `consentPolicy = ConsentPolicy.QUEUE_UNTIL_RESOLVED` | Waits until analytics leaves `UNKNOWN`, then dispatches even when `DENIED`; sinks receive the consent and handle it. Not a send-only-when-granted mode. |
| `consentPolicy = ConsentPolicy.DROP_UNTIL_GRANTED` | Drops events while analytics is not granted; dropped events are not replayed. |
| `strictValidation` | `false`: logs the error, drops bad names/keys and excess params, truncates long strings. Set `BuildConfig.DEBUG` to throw during QA. |
| `logLevel` | 1 warnings; 0 off, 2 verbose. |
| `enableRevenueAccumulator` | `true`; turn it off only if your app does not use the cumulative revenue/milestone events Tracker emits. |
| `defaultParams` / `Tracker.setDefaults(...)` | Empty; the SDK already attaches `app_vc`, `sdk_ver`, `session_no`, `install_day`, `consent_ads` (after consent). Add only a few keys: defaults are kept before excess params are trimmed. |
| `Tracker.setUserId(...)`, `setUserProperty(...)` | Set them only when your app needs identity; use `null` to clear on logout. A user property value is truncated to 36 characters and a warning is logged. |
| `ConsoleSink(tag, ringSize)` | `Trackkit/Console`, 100; debug log and in-memory history, not analytics storage. |

An app using `ConsentCenter` already has analytics mapped to granted and ads to personalization; no extra call is needed. If your app uses its own consent flow instead of this bridge, wire the decision in one place:

```kotlin
package com.example.app

import io.trackkit.Tracker

fun onAppConsentResolved(analyticsAllowed: Boolean, adsPersonalizationAllowed: Boolean) {
    Tracker.setConsent(analytics = analyticsAllowed, ads = adsPersonalizationAllowed)
}
```

Pick one owner of consent, so your app flow and `ConsentCenter` do not overwrite each other. Use `Tracker.currentConsent` to check the mapping; Tracker consent does not replace the ads SDK's condition for being allowed to request.

## 5. Verification and required files

- [ ] Install before SDK events, `sinkIds()` lists the right destinations; ConsoleSink enabled in debug only.
- [ ] App events have centralized keys and are emitted exactly once at the owning point; screens are not double counted.
- [ ] Test consent unknown/granted/denied with the policy your app chose; logout clears identity if you set any.
- [ ] SDK revenue/purchase events are not sent again from UI callbacks.

All you need is Gradle, your existing Application, [AppEvents.kt](examples/trackkit/AppEvents.kt) and the track points in your app screens. Create your own `TrackSink` only when you really have a backend that is not supported yet; no wrapper layer around every SDK event is needed.
