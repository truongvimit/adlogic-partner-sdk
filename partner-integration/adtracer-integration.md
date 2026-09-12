# AdTracer — ad debugging dashboard

[← Choose a guide](README.md) · [AdTracer API](../adtracer/README.md)

Use it to inspect requests, load/show, failures and the timeline of the ads/OB flow during QA. Three steps: add the debug dependency, copy the bridge, open the dashboard.

## 1. Add the debug dependency

Complete [Ads + OnboardKit](ads-onboarding-integration.md) and keep the Tracker installed in your Application. Following the [build setup](../README.md#build-setup), add to the app:

```groovy
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    debugImplementation "com.github.truongvimit.adlogic-partner-sdk:adtracer:$sdkVersion"
}
```

Trackkit already comes with the SDKs; no Firebase is needed to open the dashboard. Do not use `implementation` for AdTracer: the dashboard is only needed in debug builds.

## 2. Copy the bridge per source set

The three files below come from the example's wiring; change their package to your app package. The two `DebugSinks.kt` files must share the same package and the same function signature:

| Sample file | Copy to |
| --- | --- |
| [debug/AdTracerSink.kt](examples/adtracer/debug/AdTracerSink.kt) | `app/src/debug/java/<package>/tracking/AdTracerSink.kt` |
| [debug/DebugSinks.kt](examples/adtracer/debug/DebugSinks.kt) | `app/src/debug/java/<package>/tracking/DebugSinks.kt` |
| [release/DebugSinks.kt](examples/adtracer/release/DebugSinks.kt) | `app/src/release/java/<package>/tracking/DebugSinks.kt` |

`<package>` is your app package path, for example `com/example/app`. Put each file in the right source set; any other build type also needs its own version of the function — use the no-op when the dashboard is not needed.

In your existing Application, **after `Tracker.install` and before the SDK emits ads events**, call:

```kotlin
import com.example.app.tracking.installDebugSinks

// Trong Application.onCreate, sau Tracker.install:
installDebugSinks()
```

The sink calls `AdTracer.start(context)` itself. Release calls the no-op function; do not import `io.adtracer` in `main`, even when the call sits inside `if (BuildConfig.DEBUG)`.

## 3. Open the dashboard

Run the debug build on a device/emulator, open Logcat filtered on `AdTracer` and look for the `Dashboard ready` line. The default port is 8686; if it is busy the SDK tries the next ones up to 8695. Use the actual port from the log on both sides:

```bash
adb forward tcp:8686 tcp:8686
```

Open the [localhost dashboard](http://localhost:8686) on your computer, then run the splash/OB flow and the app's ad placements. With several devices, use `adb -s <serial> forward ...`. `AdTracer.dashboardPort` is the running port, `-1` when the server cannot start.

Read the timeline per placement. The test IDs in the [sample JSON](examples/ads-onboarding/ad_config_debug.json) are shared by several placements, so clicks/impressions resolved by ad unit ID can appear under a different placement. When QA runs on test IDs, cross-check load/show and the `OB_FLOW` Logcat; do not conclude the flow is wrong from the impression's placement alone. Preview/mock entries on the dashboard are not real served ads.

## 4. Behavior and options table

| Item | Default / when to change |
| --- | --- |
| Startup | Once through `AdTracerSink.onInstall`; calling AdTracer before start records no event. |
| Retention | Keeps at most 10 session journals; it serves QA, not a full analytics store. |
| App screen placement | Pass `placement = AppAdPlacement.…` to the helper/manager; omitting it can lose `ad_request`/`ad_skipped`. The SDK resolves clicks/impressions by ad unit ID; an unregistered ID shows as `unknown`. |
| OB placement | Shows OnboardKit's internal keys; see the lookup table below when you need to match the JSON. |
| Event without a placement | Appears in the timeline under `_tracer` and does not count toward the ad statistics. |
| Ad revenue | The sink handles the `ad_impression` event; do not report again in `onAdRevenue`, to avoid two impressions. |
| Format | The bridge already maps banner/collapsible, native/fullscreen, interstitial, rewarded and app-open; do not hardcode the format per screen. |
| Custom loader outside the SDK | Wire events only when the loader does not already emit Tracker events. See the [AdTracer API](../adtracer/README.md); do not add a direct call for an event that already goes through the sink. |

No `adtracer_config.json`, no dedicated manifest Activity and no copy of the example's preview screens are needed. Use the app's existing placement catalog; the dashboard does not decide ad IDs or enable ads.

<details>
<summary>JSON key lookup for the OB flow on the dashboard</summary>

| JSON key | Placement on the dashboard |
| --- | --- |
| `banner_splash` | `splash_banner` |
| `inter_splash` | `splash_inter` |
| `native_lang` / `native_lang_alt` | `language1` / `language2` |
| `native_popup_lang` | `language_confirm` |
| `native_ob1` / `native_ob2` / `native_ob3` | `step_ob1` / `step_ob2` / `step_ob4` |
| `native_fs` | `fullscreen_ob3` |
| `inter_after_ob3` / `open_resume` | Key is unchanged. |

</details>

## 5. Checks before handover

- [ ] Debug has the `adtracer` sink in `Tracker.sinkIds()` and logs the port; the dashboard opens over ADB.
- [ ] One real request produces one request/load/show chain under the right placement; impressions are not doubled.
- [ ] The OB flow and the app-screen ads both show up; load/show failures carry the matching details.
- [ ] The release build succeeds with the no-op function; the release runtime classpath has no AdTracer.

If the dashboard is empty, check the install order, the sink ID, the Tracker consent policy and that the app really requested an ad. If the URL does not open, check the ADB device/port against the log before changing the ads config.
