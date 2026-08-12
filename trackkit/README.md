# Trackkit

Trackkit is the single fan-out point for analytics in this project. Every event the app, `:ads` and
`:onboardkitorigin` produce goes through one facade, so consent gating, default params, name
validation, dedupe and ad-revenue accumulation are implemented **once** instead of being copied into
each partner build. It replaces the per-partner copies of `FirebaseAnalyticsUtil`, `ERainAdjust`,
`ERainLogEventManager` and `ITGTrackingHelper` — four wrappers that disagreed with each other about
currency, revenue units and whether Adjust was even enabled. The core module has **no vendor
dependency**: Firebase, Adjust and Meta live in separate sink modules, so a partner that only wants
Firebase never compiles the other two SDKs into its APK.

**[ARCHITECTURE.md](ARCHITECTURE.md)** explains the module layering — which module may depend on
SDK, and why instrumentation lives in the module that owns the ad object rather than at your call
sites. Read it before adding an SDK or a module.

---

## Gradle setup

Same JitPack pattern as the other published modules in this repository.

```groovy
// settings.gradle (dependencyResolutionManagement) or root build.gradle
repositories {
    google()
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
```

```groovy
dependencies {
    // The contract. Depend on this from any module that emits events.
    implementation 'com.github.truongvimit.adlogic-partner-sdk:trackkit:1.2.0'

    // Sinks — take only the vendors you actually ship.
    implementation 'com.github.truongvimit.adlogic-partner-sdk:trackkit-firebase:1.2.0'
}
```

Because this repository publishes several modules, JitPack namespaces them as
`com.github.<user>.<repo>`; the repository name is part of the group id. Replace `1.2.0` with the
git tag you want to consume.

Inside this repository the modules are wired as projects instead:

```groovy
implementation project(':trackkit')
implementation project(':trackkit-firebase')
```

Requires JDK 17 and `minSdk` 24.

---

## Quickstart

Three lines in `Application.onCreate()`, before any other SDK initialises — this is the only place
in the whole app that names a vendor:

```kotlin
Tracker.install(this, TrackerConfig(appVersionCode = BuildConfig.VERSION_CODE.toLong(), strictValidation = BuildConfig.DEBUG))
if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
```

Then name your placements, once, next to where you configure the ad units:

```kotlin
// Ad unit id -> the screen that asks for it. AdMob's paid-event callback only knows the unit id,
// so this is what lets ad_impression say which screen earned the money.
PlacementRegistry.register(interSplashConfig.id, "inter_splash")
PlacementRegistry.register(nativeLanguageConfig.id, "native_language")
```

Register every id in a waterfall, not just the top tier. Modules that build their own ad requests
already register theirs — `ERainAdProvider` in `:onboardkitorigin` registers each onboarding unit
before it loads.

Then, wherever UMP resolves — **exactly one place in the app**:

```kotlin
Tracker.setConsent(analytics = granted, ads = granted)
```

That is the whole integration. You do **not** wrap ad callbacks: `:ads` creates the AdMob and
AppLovin ad objects, so `:ads` attaches the paid-event listener and reports the ad lifecycle. Pass
your own `AdCallback` exactly as you always did and it comes back unwrapped. See
[ARCHITECTURE.md §6](ARCHITECTURE.md#6-the-instrumentation-rule) for why.

Events tracked before `install()` are buffered, not dropped, so ordering mistakes cost you nothing.
See `GlobalApp.initTracking()` and `ConsentHandler.resolveConsent()` in `:app` for the reference
wiring.

---

## Event catalog

Every event also carries the default params `app_vc`, `sdk_ver`, `session_no`, `install_day` and
`consent_ads`, so any funnel can be sliced by cohort without a join. Params listed below are the
event-specific ones.

| Event                    | Fires when                                                                    | Params                                                                                                | Emitted by                  |
|--------------------------|-------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------|-----------------------------|
| `ad_request`             | a load is actually sent to the network                                        | `placement`, `ad_format`, `ad_unit_id`                                                                | `:ads`                      |
| `ad_loaded`              | the network returned a fill                                                   | `placement`, `ad_format`, `ad_unit_id`, `latency_ms`                                                  | `:ads`                      |
| `ad_load_failed`         | no fill or a load error                                                       | `placement`, `ad_format`, `ad_unit_id`, `error_code`                                                  | `:ads`                      |
| `ad_show`                | the ad was actually displayed                                                 | `placement`, `ad_format`, `ad_unit_id`                                                                | `:ads`, `:onboardkitorigin` |
| `ad_show_failed`         | the show call was rejected                                                    | `placement`, `ad_format`, `ad_unit_id`, `error_code`                                                  | `:ads`                      |
| `ad_click`               | user tapped the ad                                                            | `placement`, `ad_format`, `ad_unit_id`                                                                | `:ads`                      |
| `ad_closed`              | full-screen ad dismissed                                                      | `placement`, `ad_format`, `ad_unit_id`                                                                | `:ads`                      |
| `ad_reward_earned`       | rewarded callback fired                                                       | `placement`, `ad_format`, `ad_unit_id`                                                                | `:ads`                      |
| `ad_skipped`             | a show opportunity policy declined (remote flag off, purchased user, no fill) | `placement`, `ad_format`, `reason`                                                                    | `:ads`                      |
| `ad_impression`          | a **paid** impression, via `Tracker.adRevenue()`                              | `placement`, `ad_format`, `ad_unit_id`, `ad_platform`, `ad_network`, `value`, `currency`, `precision` | `:trackkit`                 |
| `ad_revenue_total`       | every paid impression — cumulative ad LTV                                     | `value`, `currency`                                                                                   | `:trackkit`                 |
| `ad_revenue_micro_flush` | the un-flushed bucket crosses 0.01 of the reporting currency                  | `value`, `currency`                                                                                   | `:trackkit`                 |
| `ad_revenue_d3`          | first paid impression at least 3 days after install (once)                    | `value`, `currency`                                                                                   | `:trackkit`                 |
| `ad_revenue_d7`          | first paid impression at least 7 days after install (once)                    | `value`, `currency`                                                                                   | `:trackkit`                 |
| `fo_splash_view`         | splash screen shown                                                           | —                                                                                                     | `:onboardkitorigin`         |
| `fo_splash_complete`     | splash finished                                                               | `dwell_ms`                                                                                            | `:onboardkitorigin`         |
| `fo_language_view`       | a language screen shown                                                       | `screen_index`, `variant`                                                                             | `:onboardkitorigin`         |
| `fo_language_select`     | a language row tapped                                                         | `screen_index`, `language`                                                                            | `:onboardkitorigin`         |
| `fo_language_complete`   | language confirmed                                                            | `screen_index`, `language`, `dwell_ms`                                                                | `:onboardkitorigin`         |
| `fo_step_view`           | an onboarding step shown                                                      | `step`, `index`, `variant`                                                                            | `:onboardkitorigin`         |
| `fo_step_complete`       | an onboarding step left                                                       | `step`, `index`, `dwell_ms`, `exit_reason`                                                            | `:onboardkitorigin`         |
| `fo_question_view`       | question screen shown                                                         | `source`                                                                                              | `:onboardkitorigin`         |
| `fo_question_answer`     | an option toggled                                                             | `option_id`, `selected`                                                                               | `:onboardkitorigin`         |
| `fo_question_complete`   | questions submitted                                                           | `count`                                                                                               | `:onboardkitorigin`         |
| `fo_flow_complete`       | the whole first-open flow finished                                            | `count`, `dwell_ms`                                                                                   | `:onboardkitorigin`         |
| `iap_paywall_view`       | paywall shown                                                                 | `source`                                                                                              | `:app`                      |
| `iap_click`              | a product tapped on the paywall                                               | `source`, `product_id`                                                                                | `:app`                      |
| `iap_success`            | purchase acknowledged                                                         | `product_id`, `value`, `currency`, `source`                                                           | `:app`                      |
| `iap_fail`               | billing returned an error                                                     | `product_id`, `error_code`                                                                            | `:app`                      |
| `iap_dismiss`            | paywall closed without buying                                                 | `source`                                                                                              | `:app`                      |
| `consent_request`        | UMP form requested (once per session)                                         | —                                                                                                     | `:app`                      |
| `consent_shown`          | UMP form actually displayed (once per session)                                | —                                                                                                     | `:app`                      |
| `consent_result`         | UMP resolved                                                                  | `status` (`granted` / `denied` / `not_required` / `error`), `error_code`                              | `:app`                      |
| `app_ui_click`           | compat shim for the old `logEventClick`                                       | `screen`, `action`                                                                                    | `:app`                      |
| `app_screen_flow`        | compat shim for the old `fromScreenToScreen`                                  | `from_screen`, `to_screen`                                                                            | `:app`                      |

Screen views are not events: `Tracker.screen(name, screenClass)` lets each sink emit its own native
screen-view (`screen_view` on Firebase), which is what the reporting UI expects.

Two rules the catalog exists to enforce:

1. **Never encode a variable in the event name.** One `fo_step_complete` with a `step` param, not
   `ob1_complete`, `ob2_complete`, `ob3_complete`.
2. **Every ad event carries its placement.** AdMob's paid-event callback only knows the ad unit, so
   without `PlacementRegistry` the revenue cannot be attributed to a screen.

---

## Adding a custom event

For something app-specific that no other app will ever report on, use the escape hatch:

```kotlin
Tracker.track(SimpleEvent("app_widget_pinned", mapOf("source" to "home")))
```

Add a catalog entry in `TrackkitEvents` instead when **any** of these is true:

- more than one module emits it,
- a dashboard, an Adjust token or a Meta custom conversion depends on it,
- it takes params whose spelling matters across releases.

A catalog entry is a class, so its params become a compile-checked signature and `TaxonomyTest`
validates its name automatically. A `SimpleEvent` string is checked only at runtime.

---

## Writing a custom sink

Implement `TrackSink` and register it. Only `id` and `onEvent` are required; everything else has a
default no-op.

```kotlin
class MyBackendSink(private val api: MyApi) : TrackSink {

    override val id: String = "my_backend"

    override fun onInstall(context: Context) {
        api.warmUp(context)
    }

    override fun onEvent(name: String, params: Map<String, Any?>) {
        api.enqueue(name, params)
    }

    override fun onConsent(consent: Consent) {
        api.setCollectionEnabled(consent.analyticsGranted)
    }

    override fun onAdRevenue(impression: AdImpression) {
        // impression.value is already in impression.currency — do not convert.
        api.enqueueRevenue(impression.value, impression.currency)
    }
}

Tracker.addSink(MyBackendSink(api))
```

Contract:

- Every callback runs on the caller's thread. Do not block — hand off to your own queue.
- Trackkit wraps each call in `runCatching`, so a throwing sink cannot take down the others. It will
  still log a warning naming your `id`.
- `id` must be stable and unique; `addSink` ignores a second sink with an id already registered.
- Params arrive already sanitised: no nulls, strings capped at 100 chars, at most 25 keys.

---

## Adjust

Adjust is **not** a Trackkit sink. It lives in `:ads`, because every signal it consumes already
originates there and because `:ads` reads Adjust's attribution verdict back to gate ad display.
Full reasoning and the criteria for revisiting it: [ARCHITECTURE.md](ARCHITECTURE.md).

To send a conversion milestone from a module outside `:ads`:

```java
MmpTracking.trackEvent(adjustTokenForThisMilestone);
```

Mint the token on the Adjust dashboard first — a blank token is skipped with a warning, never sent
as
`AdjustEvent("")`.

## Sink options

Every sink is configured through its constructor; there is no global settings object.

| Sink           | Parameter                  | Default | What it does                                                                                                                                                                                                                                                                                              |
|----------------|----------------------------|---------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `FirebaseSink` | `collectionFollowsConsent` | `true`  | Calls `setAnalyticsCollectionEnabled(analyticsGranted)`. **Pass `false`** if the UMP form only asks about ads — a hard collection switch also kills `first_open`, retention and the whole funnel for users who decline personalisation, while Consent Mode alone already satisfies the legal requirement. |

---

## Consent Mode defaults

Trackkit sets consent the moment UMP resolves, but the state *before* that is a manifest concern.
Declare the four defaults in the app manifest, otherwise pre-consent traffic uses Firebase's own
defaults rather than yours:

```xml
<meta-data android:name="google_analytics_default_allow_analytics_storage" android:value="true" />
<meta-data android:name="google_analytics_default_allow_ad_storage" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_user_data" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_personalization_signals" android:value="false" />
```

The library manifest deliberately does not merge these — every partner's legal posture differs.

---

## Naming rules and GA4 limits

`EventValidator` enforces the following on every event name and every param key, and `TaxonomyTest`
asserts it over the whole catalog at build time.

| Rule               | Limit                                                                                            | What happens on a violation |
|--------------------|--------------------------------------------------------------------------------------------------|-----------------------------|
| Event name grammar | `[a-zA-Z][a-zA-Z0-9_]*`                                                                          | rejected                    |
| Event name length  | 40 characters                                                                                    | rejected                    |
| Param key grammar  | same as event names                                                                              | key dropped                 |
| Params per event   | 25                                                                                               | extra keys dropped          |
| String param value | 100 characters                                                                                   | truncated                   |
| Reserved prefixes  | `firebase_`, `google_`, `ga_`                                                                    | rejected                    |
| PII / secret keys  | `purchase_token*`, `email`, `phone`, `device_id`, `android_id`, `gaid`, `idfa`, `advertising_id` | rejected                    |

On top of the validator, the catalog convention is `<domain>_<object>_<action>` in lowercase
`snake_case`, with `domain` one of `ad_`, `fo_`, `iap_`, `consent_`, `app_`.

`strictValidation = true` turns every violation into an exception. Wire it to `BuildConfig.DEBUG` so
a taxonomy mistake fails in QA; in release builds it degrades to a log line and a sanitised event.

This matters because GA4 drops an over-long name or a malformed key **silently**. The name limit is
also why `logEventClick` and `fromScreenToScreen` no longer concatenate — `"OnboardingHostActivity"

+ "_click_" + "continueButton"` is 45 characters and simply never arrived.

---

## Migrating from the old wrappers

| Old call                                                                        | New call                                                                                     |
|---------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| `ERainLogEventManager.logPaidAdImpression(ctx, adValue, unitId, adapter, type)` | `Tracker.adRevenue(AdImpression(...))`                                                       |
| `ERainLogEventManager.logClickAdsEvent(ctx, unitId)`                            | `Tracker.track(TrackkitEvents.Ad.Click(placement, format, unitId))`                          |
| `ERainLogEventManager.logCurrentTotalRevenueAd(ctx, name)`                      | nothing — `ad_revenue_total` is automatic                                                    |
| `ERainLogEventManager.logTotalRevenue001Ad(ctx)`                                | nothing — `ad_revenue_micro_flush` is automatic                                              |
| `ERainLogEventManager.logTotalRevenueAdIn3DaysIfNeed(ctx)`                      | nothing — `ad_revenue_d3` is automatic                                                       |
| `ERainLogEventManager.logTotalRevenueAdIn7DaysIfNeed(ctx)`                      | nothing — `ad_revenue_d7` is automatic                                                       |
| `ERainAdjust.onTrackEvent(name)`                                                | `Tracker.track(name)` plus a token entry in the Adjust map                                   |
| `ERainAdjust.onTrackRevenuePurchase(revenue, currency)`                         | `Tracker.track(TrackkitEvents.Iap.Success(productId, value, currency))`                      |
| `FirebaseAnalyticsUtil.logEventWithAds(ctx, bundle)`                            | `Tracker.adRevenue(AdImpression(...))`                                                       |
| `FacebookEventUtils.*` / `AppEventsLogger.logPurchase` per impression           | nothing — Meta is not a destination in this build; the hardcoded VND multiplier is gone      |
| `ITGTrackingHelper.logEvent(name, bundle)`                                      | `Tracker.track(name, params)`                                                                |
| `ITGTrackingHelper.logEventClick(activity, event, bundle)`                      | `Tracker.track(SimpleEvent("app_ui_click", mapOf("screen" to …, "action" to …)))`            |
| `ITGTrackingHelper.fromScreenToScreen(from, to)`                                | `Tracker.track(SimpleEvent("app_screen_flow", mapOf("from_screen" to …, "to_screen" to …)))` |
| `ITGTrackingHelper.addScreenTrack(name)`                                        | `Tracker.screen(name)`                                                                       |
| `ITGTrackingHelper.userProperty(name, value)`                                   | `Tracker.setUserProperty(name, value)`                                                       |

Behaviour differences to expect after migrating — all of them intentional:

- **No currency conversion.** The old path multiplied AdMob revenue by `26000` and MAX revenue by
  `25000` and reported the result to Meta as VND. `AdImpression.currency` is now reported as the ad
  SDK gave it, and mixed-currency impressions are excluded from the cumulative total rather than
  summed into a meaningless figure.
- **No purchase event per impression.** Meta `logPurchase` used to fire on every single ad
  impression. It doesn't any more.
- **Purchase revenue is no longer divided by 1,000,000.** The old `onTrackRevenue` divided
  unconditionally, so IAP revenue — already expressed in currency units — was reported about a
  million times too small.
- **One source of truth for Adjust.** `ERainAdjust.enableAdjust` and `AdjustConfig.isEnableAdjust()`
  could disagree; now the sink is either registered or it is not.
- **Every ad event carries a placement**, and `ad_show` / `ad_show_failed` exist at all, so show
  rate
  and show failures are finally visible.
