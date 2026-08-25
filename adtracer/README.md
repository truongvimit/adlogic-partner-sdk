# AdTracer

> Debug-only ad lifecycle tracker with an embedded dashboard.

AdTracer records what happens to every ad opportunity — requested, skipped, filled, shown, clicked,
dismissed — journals it to disk and serves a live dashboard over loopback HTTP. Zero dependencies,
and `debugImplementation` keeps every byte of it out of release builds.

## Requirements

| | |
|---|---|
| minSdk | 24 |
| compileSdk | 36 |
| JDK | 17 |
| Permission | `INTERNET`, declared by the module — merges only into builds that include it |

## Installation

```groovy
// Replace <tag> with a tag from https://github.com/truongvimit/adlogic-partner-sdk/tags
def sdkVersion = '<tag>'
dependencies {
    // Debug builds only — never `implementation`
    debugImplementation "com.github.truongvimit.adlogic-partner-sdk:adtracer:$sdkVersion"
}
```

## Quick start

`io.adtracer` can only be imported from `src/debug`, so put the wiring behind one function whose
release twin does nothing.

1. Bridge Trackkit into AdTracer in `app/src/debug/java/.../tracking/AdTracerSink.kt` — every ad
   event `:ads` and `:onboardkitorigin` emit already goes through `Tracker`, so no call site needs
   wrapping. Same file as
   [`AdTracerSink.kt`](../app/src/debug/java/com/itg/template/tracking/AdTracerSink.kt).

```kotlin
import android.content.Context
import io.adtracer.AdTracer
import io.trackkit.AdFormat
import io.trackkit.TrackSink
import io.trackkit.TrackkitEvents
import io.adtracer.AdFormat as TracerFormat

class AdTracerSink : TrackSink {
    override val id: String = "adtracer"
    override fun onInstall(context: Context) = AdTracer.start(context)

    override fun onEvent(name: String, params: Map<String, Any?>) {
        val placement = params[TrackkitEvents.PARAM_PLACEMENT] as? String
        if (placement == null) {
            // Funnel / IAP / consent events: on the timeline, out of every ad aggregate
            AdTracer.event(name, "_tracer", TracerFormat.OTHER)
            return
        }
        val format = formatOf(params[TrackkitEvents.PARAM_AD_FORMAT] as? String)
        val adUnitId = params[TrackkitEvents.PARAM_AD_UNIT_ID] as? String
        val code = (params[TrackkitEvents.PARAM_ERROR_CODE] as? Number)?.toInt()
        val reason = params[TrackkitEvents.PARAM_REASON] as? String
        when (name) {
            TrackkitEvents.AD_REQUEST -> AdTracer.loadRequested(placement, format, adUnitId)
            TrackkitEvents.AD_LOADED -> AdTracer.loaded(placement, format)
            TrackkitEvents.AD_LOAD_FAILED -> AdTracer.loadFailed(placement, format, code)
            TrackkitEvents.AD_SHOW -> AdTracer.shown(placement, format)
            TrackkitEvents.AD_SHOW_FAILED -> AdTracer.showFailed(placement, format, code)
            TrackkitEvents.AD_IMPRESSION -> AdTracer.impression(placement, format)
            TrackkitEvents.AD_CLICK -> AdTracer.clicked(placement, format)
            TrackkitEvents.AD_CLOSED -> AdTracer.dismissed(placement, format)
            TrackkitEvents.AD_REWARD_EARNED -> AdTracer.event("reward_earned", placement, format)
            TrackkitEvents.AD_SKIPPED ->
                AdTracer.loadSkipped(placement, format, reason ?: "unknown")

            else -> AdTracer.event(name, placement, format, adUnitId, reason, code)
        }
    }

    private fun formatOf(key: String?): TracerFormat = when (AdFormat.fromKey(key)) {
        AdFormat.BANNER, AdFormat.COLLAPSIBLE_BANNER -> TracerFormat.BANNER
        AdFormat.INTERSTITIAL -> TracerFormat.INTERSTITIAL
        AdFormat.REWARDED, AdFormat.REWARDED_INTERSTITIAL -> TracerFormat.REWARDED
        AdFormat.NATIVE, AdFormat.NATIVE_FULL_SCREEN -> TracerFormat.NATIVE
        AdFormat.APP_OPEN -> TracerFormat.APP_OPEN
        AdFormat.UNKNOWN -> TracerFormat.OTHER
    }
}
```

Keep every branch. An ad event that lands in `else` is journalled under its raw Trackkit name, and
the dashboard counts only the canonical AdTracer types.

2. Register it from a variant seam — `app/src/debug/java/.../tracking/DebugSinks.kt`:

```kotlin
fun installDebugSinks() { Tracker.addSink(AdTracerSink()) }
```

`app/src/release/java/.../tracking/DebugSinks.kt`, same package and signature:

```kotlin
fun installDebugSinks() = Unit
```

3. Call `installDebugSinks()` in `Application.onCreate()`, after `Tracker.install(...)`.

Not using Trackkit? Call `AdTracer.start(context)` once, then these from your own ad callbacks —
each is a no-op until `start` runs, and none of them throw.

| Function | Meaning |
|---|---|
| `loadRequested(placement, format, adUnitId?)` / `loadSkipped(…, reason)` | Asked for an ad / decided not to |
| `loaded(placement, format, approx?)` / `loadFailed(…, code?, message?)` | Fill / no fill |
| `showRequested` / `showStarted` / `shown(placement, format)` | Show attempt, start, display |
| `showBlocked(…, reason)` / `showFailed(…, code?, message?, synthetic?)` | Show suppressed / failed |
| `impression` / `clicked` / `dismissed(placement, format)` | SDK impression, click, close |
| `rendered(placement)` / `reRendered(placement)` / `discarded(…, reason)` | Native bound, rebound, fill dropped |
| `event(type, placement, format, …)` | Escape hatch for custom types |

`format` is `io.adtracer.AdFormat`: `NATIVE`, `INTERSTITIAL`, `BANNER`, `REWARDED`, `APP_OPEN`,
`OTHER`. `approx = true` marks an inferred value (`≈`), `synthetic = true` an SDK-fabricated event.

## Viewing the dashboard

Install the **debug** build, forward the port, open it in a browser:

```bash
adb forward tcp:8686 tcp:8686
```

Open **http://localhost:8686**. If 8686 is taken the server tries 8687–8695 and logs the bound port
(`adb logcat -s AdTracer`); `AdTracer.dashboardPort` holds it, or `-1` when none was free. The server
binds `127.0.0.1` only, so it is never reachable from the LAN.

## What it records

One event per observation: `seq`, `sessionId`, wall-clock and `elapsedRealtime` timestamps, type,
placement, format, plus optional ad unit id, reason, error code and message.

- `(sessionId, seq)` is gapless per session, so a browser reconnect never duplicates or drops.
- Events reach `filesDir/adtracer/s-<sessionId>.ndjson` **before** the browser, so killing the app
  keeps the history; the 10 newest sessions are kept and browsable.
- Emitting is a bounded, non-blocking hand-off; under overload it drops events and reports how many
  as a `tracer_overflow` event rather than growing the queue.
- Placements starting with `preview_` stay hidden until the `Ads test (preview_*)` toggle is on.
- `/api/sessions` lists the journals as JSON, `/api/session/<file>` returns one as raw NDJSON, and
  `GET /events` streams the same records live as SSE frames.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Unresolved reference: io.adtracer` | Imported from `src/main` | Import only from `src/debug`, behind a variant seam |
| Release build fails to compile | The two `DebugSinks.kt` twins drifted | Same package, same signature in both |
| Dashboard does not open | `adb forward` not run, or another port | Run the forward; check `adb logcat -s AdTracer` |
| Dashboard opens but stays empty | Sink never registered | Confirm `installDebugSinks()` runs after `Tracker.install` |
| Every placement reads `unknown` | Ad unit ids not mapped | `io.trackkit.PlacementRegistry.register(adUnitId, placement)` — see [`../trackkit/README.md`](../trackkit/README.md) |

## License

MIT — see [LICENSE](../LICENSE).
