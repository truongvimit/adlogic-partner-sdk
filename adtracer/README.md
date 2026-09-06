# AdTracer

A local dashboard for ad requests, fills, shows and failures during development. AdTracer only
records events sent to it; connect the sample Trackkit sink to observe this SDK's events.
No Firebase account or analytics service is needed.

## Requirements and installation

Use `minSdk 24+`, `compileSdk 36+` and JDK 17. Add JitPack using the [root setup](../README.md).

```groovy
// app/build.gradle — same published tag as the other SDK modules.
def sdkVersion = '<tag>'
dependencies {
    debugImplementation "com.github.truongvimit.adlogic-partner-sdk:adtracer:$sdkVersion"
}
```

The current 5.1.0 work is not published; use an available
[repository tag](https://github.com/truongvimit/adlogic-partner-sdk/tags).
`debugImplementation` keeps this dashboard out of release builds. The module supplies its
`INTERNET` permission. The bridge below also needs `trackkit`, already exported by `ads`;
for a standalone bridge, declare `trackkit` explicitly at the same tag.

## Connect SDK events

**1. Copy the complete [AdTracerSink.kt](../app/src/debug/java/com/itg/template/tracking/AdTracerSink.kt)**
into `app/src/debug/java/com/example/app/debug/AdTracerSink.kt`. Change its package to
`com.example.app.debug` (or your own package). Keep its event and format mappings: they translate
Trackkit events into the names the dashboard counts.

**2. Add a debug entry point**, `app/src/debug/java/com/example/app/debug/DebugTracing.kt`:

```kotlin
package com.example.app.debug

import io.trackkit.Tracker

fun installAdTracing() {
    Tracker.addSink(AdTracerSink()) // The sink starts AdTracer in onInstall.
}
```

**3. Add the release counterpart**, `app/src/release/java/com/example/app/debug/DebugTracing.kt`:

```kotlin
package com.example.app.debug

fun installAdTracing() = Unit
```

Use the same package and signature in both files. Keep `io.adtracer` imports in `src/debug`;
a `BuildConfig.DEBUG` check in `src/main` does not make those imports compile in release.
Give any additional build types the appropriate implementation of this entry point.

**4. Call it once from your Application**, after Tracker installation and before ad requests:

```kotlin
import com.example.app.debug.installAdTracing
import io.trackkit.Tracker

// Inside Application.onCreate(), after super.onCreate():
Tracker.install(this) // Keep your existing TrackerConfig if already configured.
installAdTracing()
```

If ads setup already calls `Tracker.install`, add only `installAdTracing()` immediately after it.
Your release build calls the empty function and has no AdTracer dependency.

## Open the dashboard

Launch the debug app on a device/emulator connected through ADB, then run:

```bash
adb forward tcp:8686 tcp:8686
```

Open [localhost:8686](http://localhost:8686), then exercise an ad placement in the app.
If it does not open, run `adb logcat -s AdTracer`: startup logs the actual port and forwarding
command. The server tries 8686–8695; `AdTracer.dashboardPort` is `-1` if none is available.
Preview placements appear after enabling the dashboard's `Ads test (preview_*)` toggle.

## Optional: trace your own ad callbacks

For a loader outside this SDK, call `io.adtracer.AdTracer.start(context)` from debug-only code,
then report its callbacks with `loadRequested`, `loaded`, `shown`, `loadFailed`, and so on.
Use `io.adtracer.AdFormat` for the format; signatures are in
[AdTracer](src/main/java/io/adtracer/AdTracer.kt). Do not also report events sent by the bridge,
or they count twice. Up to 10 session journals are retained; pending events can be lost when the
process stops.

## Moving from the main / 5.0 setup

The integration is unchanged: debug dependency, Trackkit sink and a release no-op. No new
initialization step is needed. Keep the full sample sink instead of forwarding raw event names.

## Troubleshooting

| Symptom | Check |
|---|---|
| Release cannot resolve `io.adtracer` | Move imports/code to `src/debug`; add the matching release entry point. |
| Browser cannot connect | Launch the app, check the logged port, then forward that port with ADB. |
| No ad events | Register the sink after `Tracker.install` and before requesting ads. |
| Counts differ from callbacks | Preserve the sample mappings; remove duplicate reporting. This measures received events, not independent ad delivery. |
| Placement is `unknown` | Initialize placement config before ads; custom loaders can register IDs through `PlacementRegistry`. |

License: [MIT](../LICENSE).
