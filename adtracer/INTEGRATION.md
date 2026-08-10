# AdTracer — Integration Guide

A debug-only ad lifecycle tracker with a built-in web dashboard.
**Zero dependencies. Zero code changes to your ads SDK. Zero bytes in release builds.**

---

## How it works (30 seconds)

```
Your ad callbacks  ──►  AdsTracking (your adapter)  ──►  AdTracer.event(...)
                                                              │
                                        journal on disk ◄─────┤
                                                              ▼
                                              http://localhost:8686  (dashboard)
```

AdTracer knows **nothing** about AdMob, Applovin, or any SDK. You call plain functions
like `AdTracer.loaded("home_native", AdFormat.NATIVE)` from wherever your ad callbacks
already fire. That's the whole contract.

---

## Step 1 — Add the module

Copy the `adtracer/` folder into your project root, then:

**`settings.gradle`**
```groovy
include ':adtracer'
```

**`app/build.gradle`**
```groovy
dependencies {
    // debugImplementation = never packaged into release
    debugImplementation project(':adtracer')
}
```

> The module declares `INTERNET` permission itself (needed for the loopback socket).
> It merges only into builds that include the module, so your release manifest is untouched.

---

## Step 2 — Start it in your Application

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AdsTracking.init(this)   // see Step 3
    }
}
```

---

## Step 3 — Create your adapter (the only file you write)

Because `:adtracer` is `debugImplementation`, main-sourceset code **cannot** reference it
directly. The pattern is a **twin object**: one real, one no-op, with identical signatures.

### `app/src/debug/java/.../AdsTracking.kt` — real implementation

```kotlin
package com.yourapp.tracking

import android.app.Application
import io.adtracer.AdFormat
import io.adtracer.AdTracer

object AdsTracking {

    fun init(app: Application) = AdTracer.start(app)

    fun nativeLoadRequested(placement: String, adUnitId: String) =
        AdTracer.loadRequested(placement, AdFormat.NATIVE, adUnitId)

    fun nativeLoadSkipped(placement: String, reason: String) =
        AdTracer.loadSkipped(placement, AdFormat.NATIVE, reason)

    // Wrap the app's callback so tracking happens without touching call sites
    fun trackedNativeCallback(placement: String, wrapped: AdCallback): AdCallback =
        object : AdCallback() {
            override fun onNativeAdLoaded(ad: ApNativeAd) {
                AdTracer.loaded(placement, AdFormat.NATIVE)
                wrapped.onNativeAdLoaded(ad)
            }
            override fun onAdFailedToLoad(e: LoadAdError?) {
                AdTracer.loadFailed(placement, AdFormat.NATIVE, e?.code, e?.message)
                wrapped.onAdFailedToLoad(e)
            }
            override fun onAdClicked() {
                AdTracer.clicked(placement, AdFormat.NATIVE)
                wrapped.onAdClicked()
            }
        }
}
```

### `app/src/release/java/.../AdsTracking.kt` — no-op twin

```kotlin
package com.yourapp.tracking

import android.app.Application

@Suppress("UNUSED_PARAMETER")
object AdsTracking {
    fun init(app: Application) = Unit
    fun nativeLoadRequested(placement: String, adUnitId: String) = Unit
    fun nativeLoadSkipped(placement: String, reason: String) = Unit
    fun trackedNativeCallback(placement: String, wrapped: AdCallback): AdCallback = wrapped
}
```

> [!IMPORTANT]
> Both files must have the **same package, same object name, same signatures**.
> R8 inlines and strips the empty release bodies → literally zero runtime cost.

---

## Step 4 — Call it from your ad code

```kotlin
fun loadNativeHome(activity: Activity) {
    val skipReason = when {
        !config.isEnable -> "disabled_config"
        isPurchased()    -> "purchased"
        !isOnline()      -> "offline"
        else             -> null
    }
    if (skipReason != null) {
        AdsTracking.nativeLoadSkipped("native_home", skipReason)   // ← why nothing loaded
        return
    }

    AdsTracking.nativeLoadRequested("native_home", config.id)      // ← request
    adSdk.loadNative(
        activity, config.id,
        AdsTracking.trackedNativeCallback("native_home", myCallback)  // ← outcome
    )
}
```

And at the point the ad is actually **put on screen**:

```kotlin
container.addView(adView)
AdTracer.rendered("native_home")   // via your adapter
```

That's it. Three touch points per placement: **skip / request / outcome**.

---

## Step 5 — Open the dashboard

```bash
adb forward tcp:8686 tcp:8686
```

Open **http://localhost:8686**. Metrics update live.

If port 8686 is taken, AdTracer tries 8687–8695. Check the actual port:

```bash
adb logcat -s AdTracer
```

---

## API reference

`AdTracer` — all methods are no-ops until `start()` is called, and never throw.

| Method | Meaning |
|---|---|
| `start(context)` | Boot the tracker + dashboard. Call once. |
| `loadRequested(placement, format, adUnitId?)` | App asked for an ad |
| `loadSkipped(placement, format, reason)` | App decided not to ask (purchased, offline…) |
| `loaded(placement, format, approx?)` | Fill received |
| `loadFailed(placement, format, code?, message?)` | No fill |
| `showRequested(placement, format)` | App tried to show |
| `showBlocked(placement, format, reason)` | Show suppressed (interval, not ready…) |
| `shown(placement, format)` | Ad actually displayed |
| `showFailed(placement, format, code?, message?, synthetic?)` | Show attempt failed |
| `impression(placement, format)` | SDK-reported impression |
| `clicked(placement, format)` | Click |
| `dismissed(placement, format)` | Full-screen ad closed |
| `rendered(placement)` / `reRendered(placement)` | Native bound to a view |
| `discarded(placement, format, reason)` | Fill thrown away unused |
| `event(type, placement, format, …)` | Escape hatch for custom types |

**Formats:** `NATIVE`, `INTERSTITIAL`, `BANNER`, `REWARDED`, `APP_OPEN`, `OTHER`

**Useful flags:**
- `synthetic = true` — SDK fabricated this event (fake dismiss, fake error). Excluded from metrics.
- `approx = true` — inferred, not directly reported. Shown with a `≈` badge.

---

## Naming placements

Use `snake_case`, one stable key per surface: `native_home`, `inter_welcome`,
`banner_home`, `reward_daily`, `open_resume`.

Prefix test/preview placements with `preview_` — the dashboard filters them out of totals by default.

---

## Reading the numbers

| | |
|---|---|
| **Show rate** | `shown / loaded`, **not** `/ requested` — SDKs auto-reload and banners auto-refresh |
| `≈` | approximate / inferred value |
| `—` | the SDK never reports this signal (≠ zero) |
| `synthetic` | fabricated by the SDK, excluded from metrics |

---

## Guarantees

- Events are journaled to disk (NDJSON) **before** being pushed — killing the app keeps history.
- Last 10 sessions are browsable from the dropdown.
- `(sessionId, seq)` is gapless, so browser reconnects never duplicate or drop events.
- The server binds **127.0.0.1 only** — not reachable on your LAN.
- Emitting is a bounded, non-blocking hand-off to a background thread; a tracker failure
  can never crash the host app.
- Release builds: module not packaged, call sites are empty functions removed by R8.

---

## Common mistakes

| Problem | Fix |
|---|---|
| `Unresolved reference: io.adtracer` in main sourceset | You can only import it from `src/debug`. Go through the `AdsTracking` twin. |
| Release build fails to compile | The two `AdsTracking` twins drifted. Keep signatures identical. |
| Dashboard is empty | `adb forward` not run, or wrong port — check `adb logcat -s AdTracer`. |
| `loaded` > `requested` | The SDK auto-reloads after a show. Pair that reload with an extra `loadRequested`. |
| Duplicate `shown` for natives | Sticky LiveData re-delivery — dedupe by ad instance and use `reRendered` for repeats. |
