**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# Trackkit

Send SDK and app events to Firebase or your own analytics backend through `Tracker`.

[Build setup and module selection](../README.md) · minSdk 24+ · compileSdk 36+ · JDK 17

## 1. Add a destination

For Firebase Analytics, add `suite-firebase`; it already exposes Trackkit. Complete [Firebase setup](../suite-firebase/README.md) with your app's `google-services.json` and Google Services plugin.

```groovy
// app/build.gradle
def sdkVersion = '5.2.4'
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

For your own backend instead, declare `com.github.truongvimit.adlogic-partner-sdk:trackkit:$sdkVersion` and implement `TrackSink`. Ads, onboarding, billing and paywall already expose Trackkit, so they need no extra Trackkit dependency.

## 2. Initialize once

Merge this into your existing `Application.onCreate()`, before the other kits emit events. The example below is an analytics-only app; keep your existing Application base when using ads. `BuildConfig` is your app's class.

```kotlin
import android.app.Application
import io.suite.firebase.FirebaseSink
import io.trackkit.Tracker
import io.trackkit.TrackerConfig

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Tracker.install(this, TrackerConfig(
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            strictValidation = BuildConfig.DEBUG,
        ))
        Tracker.addSink(FirebaseSink())
    }
}
```

Register `App` as `android:name` on the manifest's `<application>`. If your Application already installs Tracker, only add the missing sink. Each sink ID is registered once.

## 3. Send your app events

```kotlin
Tracker.track("app_document_open", mapOf("file_type" to "pdf"))
Tracker.screen("document_reader")
```

Use stable names and parameters, not file names or user data inside event names. Keep names to letters, digits and underscores, starting with a letter, up to 40 characters.

Ads, onboarding, billing and PayKit emit their own SDK events. Do not duplicate those from your app callbacks. Event names and parameters are in [TrackkitEvents](src/main/java/io/trackkit/TrackkitEvents.kt).

`fo_ad_bound` means a native view was bound; only vendor-confirmed `ad_show` means an ad was displayed. Keep these separate in reports.

## Connect consent

When using the ads module's `ConsentCenter`, consent is forwarded to Tracker automatically. Its current mapping is `analytics = true` and `ads = personalized`; this is distinct from permission to request ads. Read request authority through `ConsentCenter.canRequestAds()`.

With your own consent flow and no `ConsentCenter`, publish the actual result:

```kotlin
fun onConsentResolved(analyticsAllowed: Boolean, adsPersonalizationAllowed: Boolean) {
    Tracker.setConsent(analytics = analyticsAllowed, ads = adsPersonalizationAllowed)
}
```

Do not let two consent integrations overwrite each other. A later `ConsentCenter` update replaces a direct Tracker consent update.

`TrackerConfig.consentPolicy` defaults to `SEND_ALWAYS`. If your integration must hold events until consent is resolved, configure `QUEUE_UNTIL_RESOLVED` before installing Tracker. See [TrackerConfig and ConsentPolicy](src/main/java/io/trackkit/TrackkitApi.kt) and the [Firebase consent options](../suite-firebase/README.md).

## Check your integration

Open Logcat and filter by `Trackkit`. Add a console sink in debug builds to inspect emitted events:

```kotlin
import io.trackkit.sink.ConsoleSink

// Application.onCreate(), after Tracker.install(...)
if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
```

| Problem | Check |
| --- | --- |
| No events arrive | Confirm `Tracker.install` and a sink are registered before events are sent; inspect `Tracker.sinkIds()`. |
| Events are held/dropped | Check `Tracker.currentConsent` and your configured `consentPolicy`. |
| Duplicate SDK events | Remove manual copies from ad/onboarding callbacks; register each destination once. |
| Validation throws | Use `strictValidation = BuildConfig.DEBUG`, not `true` in release. |

## Other destinations and options

For a custom destination, implement `TrackSink.id` and `onEvent`; add `onScreen` if you use `Tracker.screen`. Sink callbacks run on the caller's thread: enqueue network work instead of blocking. See [TrackSink](src/main/java/io/trackkit/TrackkitApi.kt), [ConsoleSink](src/main/java/io/trackkit/sink/ConsoleSink.kt) and [Tracker](src/main/java/io/trackkit/Tracker.kt) for default parameters, user properties and revenue APIs.

## License

MIT — see [LICENSE](../LICENSE).
