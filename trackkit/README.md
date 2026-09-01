# Trackkit

> One analytics facade every module reports through, and a sink per vendor.

Everything the app, `:ads`, `:onboardkitorigin`, `:paykit` and `:billingkit` emit goes through
`io.trackkit.Tracker`, so consent gating, default params, GA4 name validation, dedupe and cumulative
ad revenue are implemented once. The core is vendor-free: vendors arrive as separate sink modules.

Module layering: **[ARCHITECTURE.md](ARCHITECTURE.md)** · Tiếng Việt: [README.vi.md](README.vi.md) ·
हिन्दी: [README.hi.md](README.hi.md)

## Requirements

| | |
|---|---|
| minSdk / compileSdk / JDK | 24 / 36 / 17 |
| Adds to your build | no vendor dependency, no permission, no R8 rule; `consumer-rules.pro` ships in the AAR |

## Installation

```groovy
repositories { google(); mavenCentral(); maven { url 'https://jitpack.io' } }
def sdkVersion = '<tag>' // https://github.com/truongvimit/adlogic-partner-sdk/tags
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit:$sdkVersion"
    // FirebaseSink lives here.
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

`:ads`, `:onboardkitorigin`, `:paykit`, `:billingkit` and `:suite-firebase` each declare
`api project(':trackkit')`, so `Tracker` is already on the classpath with any of them; declare it
yourself only for a standalone `TrackSink`.

## Integration

Call `Tracker.install` on the first line of `Application.onCreate()`, then register your sinks.

```kotlin
override fun onCreate() {
    super.onCreate()
    Tracker.install(this, TrackerConfig(
        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        strictValidation = BuildConfig.DEBUG,
    ))
    Tracker.addSink(FirebaseSink(collectionFollowsConsent = false))
    if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
}
```

That is the whole integration. You do not wrap ad callbacks and you do not map ad units to
placements: `:ads` creates the ad objects, so `:ads` reports the ad lifecycle and paid impressions.

Events emitted before `install()` are buffered — 128 items, then the oldest are dropped with a
warning. A second `install()` is ignored. With no sink, every event is validated and discarded.

`TrackerConfig` carries the rest — reporting currency, consent policy, log level, the revenue
accumulator, default params — each documented in KDoc. The defaults work; set only what differs.

Also on `Tracker`: `track(name, params)`, `track(TrackEvent)`, `screen(name, screenClass)`,
`adRevenue(impression)`, `setDefault`, `setDefaults`, `setUserProperty`, `setUserId`, `removeSink`,
`flushPending()`, `sinkIds()`, and the `isInstalled` / `currentConsent` properties.

## Consent

Do not call `Tracker.setConsent` when `:ads` is on the classpath.
`com.ads.module.consent.ConsentCenter` is its only caller, resolves UMP for the whole process, and
passes `Tracker.setConsent(analytics = true, ads = personalized)`.

The analytics axis is always `true`: UMP asks about ads only, so a refusal must not also erase
`first_open`, retention and the funnel. Call it yourself only in an app with no `:ads`, from one
place.

## Events

`io.trackkit.TrackkitEvents` holds every name the suite emits, grouped by domain — ads, revenue,
first-open funnel, IAP, consent — and `TrackkitEvents.all()` returns the full set at runtime. Open
it in the IDE rather than copying a list that ages; each event class documents what it means.

Every event also carries `app_vc`, `sdk_ver`, `session_no`, `install_day`, and `consent_ads` once
UMP resolves.

**One setup step on your side:** GA4 stores custom parameters but does not report on them until they
are registered as custom dimensions. Register the params your dashboards need — the constants are on
`TrackkitEvents` as `PARAM_*` — or they stay in DebugView and BigQuery only. Screen views are not
events: `Tracker.screen()` lets each sink emit its own.

## Custom events

```kotlin
Tracker.track(SimpleEvent("app_widget_pinned", mapOf("source" to "home")))
```

Add a class to `TrackkitEvents` instead when more than one module emits the event, a dashboard or an
Adjust token depends on it, or its param spelling has to hold across releases.

| Rule on every name and param key | Limit | On violation |
|---|---|---|
| Grammar | `[a-zA-Z][a-zA-Z0-9_]{0,39}` | event rejected, param key dropped |
| Params per event | 25 | extra keys dropped |
| String param value / user property value | 100 / 36 characters | truncated |
| Reserved prefixes | `firebase_`, `google_`, `ga_` | rejected |
| PII / secret keys | purchase tokens, `email`, `phone`, `device_id`, `android_id`, `gaid`, `idfa`, `advertising_id` | rejected |

Convention on top of the validator: `<domain>_<object>_<action>`, lowercase `snake_case`, domain one
of `ad_`, `fo_`, `iap_`, `consent_`, `app_`. Never encode a variable in the name — one
`fo_step_complete` carrying a `step` param, not `ob1_complete` plus `ob2_complete`.

## Writing a custom sink

Only `id` and `onEvent` are required — `onInstall`, `onScreen`, `onUserProperty`, `onUserId`,
`onConsent` and `onAdRevenue` have no-op defaults, in Java too.

```kotlin
class MyBackendSink(private val api: MyApi) : TrackSink {
    override val id: String = "my_backend"

    override fun onEvent(name: String, params: Map<String, Any?>) {
        api.enqueue(name, params)
    }
    // impression.value is already in impression.currency — do not convert.
    override fun onAdRevenue(impression: AdImpression) {
        api.enqueueRevenue(impression.value, impression.currency)
    }
}

Tracker.addSink(MyBackendSink(api))
```

Every callback runs on the caller's thread and must not block. A throwing sink is caught and logged
by its `id`; the others still get the event. `addSink` ignores an `id` already registered. Params
arrive sanitised: no nulls, strings capped at 100 characters, at most 25 keys. For debug builds,
`io.trackkit.sink.ConsoleSink` logs the same payload.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Nothing reaches any vendor; logcat says `install() ran with no sink` | no sink registered | `Tracker.addSink(FirebaseSink())` or your own `TrackSink` |
| `N events were dropped before install (buffer overflow)` | over 128 events emitted before `install()` | move `Tracker.install` to the first line of `onCreate()` |
| `install() called twice` or `sink 'x' already registered` | a duplicate `install()` or two sinks sharing an `id` | keep one `install()`; give each sink a unique `id` |
| Every event stops after launch | `consentPolicy = DROP_UNTIL_GRANTED` and analytics consent not granted | with `:ads` the analytics axis is always granted; check that `ConsentCenter.request` ran |
| `ad_revenue_total` stays at 0 while `ad_impression` arrives | impressions are in a currency other than `reportingCurrency` | set `TrackerConfig.reportingCurrency` to the account currency |
| `IllegalArgumentException: Trackkit: …` in production | `strictValidation` left `true` in release | wire it to `BuildConfig.DEBUG` |

## License

MIT — see [LICENSE](../LICENSE).
