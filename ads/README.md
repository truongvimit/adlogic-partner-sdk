# Ads

**Partner integration (Vietnamese): [Ads + OnboardKit step-by-step guide](../partner-integration/ads-onboarding-integration.vi.md)** — required files, sample ad JSON, defaults, optional configuration (including app-screen native/interstitial/app-open samples) and verification.

Load and show AdMob ads from named placements. Every entry point takes a placement key and
resolves the waterfall, the on/off switch and `enable_ua_check` from `ad_config.json` itself, so
your app needs no layer that translates config into SDK objects — just the catalog of keys it owns.
Keep those keys in one `object` of constants: a placement key is the only thing that tells two ad
positions apart, since one ad unit id routinely serves many placements. Start with local config
and preload interstitials;
Firebase, Adjust, billing and app-open ads are optional. Using the supplied splash/onboarding?
Follow [onboardkitorigin](../onboardkitorigin/README.md) for that flow; it owns the consent request.

## Requirements and installation

Use `minSdk 24+`, `compileSdk 36+` and JDK 17. Add the repositories from the
[root setup](../README.md), including the mediation repositories. Google Mobile Ads and mediation
adapters are bundled; [build.gradle](build.gradle) lists versions and dependencies.

Set `adlogicSdkVersion` once in your app's `gradle.properties`; see the [shared build setup](../README.md#build-setup).

```groovy
// app/build.gradle — use the same published tag for every SDK module.
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
android {
    defaultConfig {
        manifestPlaceholders = [app_id: 'YOUR_ADMOB_APP_ID'] // ca-app-pub-...~...
    }
}
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
}
```

Add these entries to your app's `AndroidManifest.xml`. Use your existing Application class if
you have one; the `PartnerApp` below is an example.

```xml
<application android:name=".PartnerApp">
    <meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="${app_id}" />
    <meta-data android:name="com.facebook.sdk.ApplicationId" android:value="@string/facebook_app_id" />
    <meta-data android:name="com.facebook.sdk.ClientToken" android:value="@string/facebook_client_token" />
</application>
```

Create `res/values/ad_keys.xml` with your real Meta values. Both are required by the current
`ERainAd.init`, which initializes `FacebookSdk` even when Adjust is disabled.

```xml
<resources>
    <string name="facebook_app_id" translatable="false">YOUR_META_APP_ID</string>
    <string name="facebook_client_token" translatable="false">YOUR_META_CLIENT_TOKEN</string>
</resources>
```

The SDK supplies `AutoInitEnabled`, `AutoLogAppEventsEnabled` and
`AdvertiserIDCollectionEnabled` as `true` in its library manifest. A host can override an
entry with `android:value="false" tools:replace="android:value"` (declare the `tools` XML
namespace). `facebookClientToken` in `ERainAdConfig` is optional when the manifest supplies it.

Meta mediation and Facebook Core are bundled; no additional Meta dependency is needed.

## 1. Initialize once in Application

```kotlin
import android.app.Application
import com.ads.module.ads.ERainAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.ERainAdConfig
import io.trackkit.Tracker
import io.trackkit.TrackerConfig

class PartnerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Tracker.install(this, TrackerConfig(appVersionCode = BuildConfig.VERSION_CODE.toLong()))
        AdRemoteConfig.initializeFromAssets(this)
        val environment = if (BuildConfig.DEBUG) ERainAdConfig.ENVIRONMENT_DEVELOP
                          else ERainAdConfig.ENVIRONMENT_PRODUCTION
        ERainAd.getInstance().init(this, ERainAdConfig(this, environment).apply {
            facebookClientToken = getString(R.string.facebook_client_token)
        })
    }
}
```

`BuildConfig` and `R` are your app's generated classes. No Adjust token or Firebase setup is
needed here. `Tracker` comes through `ads`; add a [sink](../trackkit/README.md) for analytics or
the [debug dashboard](../adtracer/README.md).

## 2. Add placements

Create `app/src/main/assets/ad_config.json`, replacing the placeholders with ad unit IDs:

```json
{
  "inter_back":  { "id": "YOUR_INTERSTITIAL_UNIT_ID", "isEnable": true },
  "native_home": { "id": "YOUR_NATIVE_UNIT_ID",       "isEnable": true },
  "banner_home": { "id": "YOUR_BANNER_UNIT_ID",       "isEnable": true }
}
```

Use test ad units in `ad_config_debug.json` for debug builds. If absent, debug falls back to
`ad_config.json`; the SDK does not replace live IDs with test IDs. Missing placements are disabled.
For waterfall floors, add `<placement>_high`, `_high1`…`_high9`; they are requested highest first
and the base key last. The base key is the placement's master switch — `"isEnable": false` there
turns off every floor. See [AdUnitConfig](src/main/java/com/ads/module/config/AdUnitConfig.kt).

Then name those keys once, in your app:

```kotlin
object AppAdPlacement {
    const val INTER_BACK = "inter_back"
    const val INTER_ALL = "inter_all"
    const val NATIVE_HOME = "native_home"
    const val BANNER_HOME = "banner_home"
    const val REWARD_EXAMPLE = "reward_example"
    const val OPEN_RESUME = "open_resume"
}
```

The key is the ad position's identity everywhere the SDK looks: the interstitial cache, the
frequency clock, the auto-buffer group, native preload, and every `ad_request` / `ad_impression` /
`ad_skipped` your dashboard slices by. Ad unit ids cannot stand in for it — Google's test units give
one id per format, and production payloads reuse a unit across screens, so several placements share
one id routinely. A raw string that drifts from the JSON does not fail either: `AdRemoteConfig.unit`
logs a warning, returns a disabled placeholder, and the slot silently never fills. A constant turns
that into a compile error. The samples below use this object.

## 3. Resolve consent before requesting ads

For a custom splash, call this from its Activity on the main thread. `preloadInterBack()` is
defined below; `openHome()` is your navigation. Continue without waiting for the ad load.

```kotlin
import com.ads.module.consent.ConsentCenter

ConsentCenter.request(this, screen = "splash") { allowed ->
    if (allowed) preloadInterBack()
    openHome()
}

// In the same Activity:
override fun onDestroy() {
    ConsentCenter.detach(this)
    super.onDestroy()
}
```

`allowed` means ads may be requested; it is not a consent grant. A failed UMP flow (an error, or a
required form that is unavailable) or the network timeout (20 seconds by default, stopped before a
form is shown) opens an in-process request fallback, so `allowed` can be `true` without consent or
personalization. When `allowed` is false, continue without ads. By default, debuggable builds treat
every device as an EEA test device, so no hashed device ID is needed to see the form. Timeout and debug geography: [ConsentOptions](src/main/java/com/ads/module/consent/ConsentOptions.kt).

## 4. Preload interstitials; show only when ready

These are methods in your `AppCompatActivity`. Preload earlier, after consent; call the show
method only at a new navigation opportunity. `goNextScreen()` is your app's navigation.

```kotlin
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager

fun preloadInterBack() = InterstitialAdManager.load(applicationContext, AppAdPlacement.INTER_BACK)

fun showInterBackOrContinue() =
    InterstitialAdManager.show(this, AppAdPlacement.INTER_BACK, object : InterShowCallback() {
        override fun onComplete() = goNextScreen()
    })
```

Both calls resolve the placement from your ad JSON: waterfall, on/off switch, `enable_ua_check`,
consent, premium, interval and readiness. Navigate only from `onComplete` — it runs exactly once,
including when the ad is skipped or fails. Do not pre-check `canShow()`: the trigger has to reach
`show` to count as an action. Load calls share an existing request/cache; preload again after
consumption, or use the auto-buffer below. Pass `adUnitIds` to the id-taking overload only when
your app supplies its own units.

## Native and banner slots

Call after consent from a resumed `AppCompatActivity`. `binding.frAds` and `binding.frBanner` are
empty `FrameLayout` containers. For a Fragment, pass its Activity and `viewLifecycleOwner`.

```kotlin
import com.ads.module.helper.adnative.NativeAdHelper
import com.ads.module.helper.banner.BannerAdHelper

NativeAdHelper.forPlacement(this, this, AppAdPlacement.NATIVE_HOME, binding.frAds)
BannerAdHelper.forPlacement(this, this, AppAdPlacement.BANNER_HOME, binding.frBanner)
```

Both resolve the placement from your ad JSON — waterfall, on/off switch, `enable_ua_check`, CTA
style — and the request is under way when they return. Pass `layoutRes` or a
[`BannerType`](src/main/java/com/ads/module/helper/banner/BannerType.kt) to change the template;
build [NativeAdConfig](src/main/java/com/ads/module/helper/adnative/NativeAdConfig.kt) or
[BannerAdConfig](src/main/java/com/ads/module/helper/banner/BannerAdConfig.kt) yourself when the
app supplies its own ad units.

Native includes a generated loading skeleton. To customize it, copy the
[supplied native layout](src/main/res/layout/custom_native_admob_medium.xml), keeping its
`NativeAdView` root, asset IDs and ad badge. Both examples leave refresh to AdMob; SDK refresh
requires disabling console refresh for every tier.

## Native preload, repeated show, and refresh

Use a stable placement for each slot and one `NativeAdHelper` per Activity/view. A Fragment
uses `viewLifecycleOwner`. The shared manager keeps one unused ad and one pending request per
placement, so a singleton helper holding an Activity is unnecessary.

```kotlin
// Optional: preload earlier, after consent.
val nativeConfig = NativeAdConfig.forPlacement(AppAdPlacement.NATIVE_HOME, R.layout.custom_native_admob_medium)
NativeAdManager.preload(applicationContext, AppAdPlacement.NATIVE_HOME, nativeConfig)

// Destination Activity; use viewLifecycleOwner for a Fragment.
val nativeHelper = NativeAdHelper.forPlacement(this, this, AppAdPlacement.NATIVE_HOME, binding.frAds)
```

- `show()` uses a ready ad, joins a pending request, or loads one. Repeated calls while loading
  share that request. Calling it again after a successful bind requests a replacement.
- For timed refresh, build the config with `NativeAdConfig.forPlacement(placement, layoutRes,
  canReloadAds = true)` — it is a constructor value, not a settable property — and call
  `applyReloadByTime(intervalMs)` before `show()`. The current ad stays visible while loading;
  refresh pauses when the slot is hidden/stopped.
- For retained pager pages or custom navigation, call `cancel()` when the page is unselected
  and `show()` when selected. Use `destroy()` when permanently disposing the helper.
- After rotation, recreate the helper with the same placement and call `show()`; an
  `AppCompatActivity` host restores its current presentation. Ads do not survive process death.
- Do not call `NativeAdManager.release(placement)` for routine screen cleanup: it invalidates
  shared pending/unused ads. Use it only when deliberately discarding that placement's inventory.

### Native click return

`NativeAdConfig.reloadOnAdClick` defaults to `true`. A click/open immediately preloads an
unused replacement for that placement. On return, `NativeAdHelper` consumes a ready ad or
waits for the same in-flight request; it never binds the preload while the user is away.
This also supports pause-only destinations and is independent of `canReloadAds`, debounce
and refresh timers. Normal consent, purchase and network gates still apply.

Disable this behavior for a screen that navigates away on ad return:

```kotlin
helper.setReloadOnAdClick(false)
```

The built-in onboarding provider disables it for all content/fullscreen step natives and
OB5. Language slots, the language popup, question native, and ordinary partner natives keep
it enabled. Step click-return navigation remains enabled by default.

## Optional integrations

| Need | Add or configure |
|---|---|
| Remote placements / Firebase analytics | [suite-firebase](../suite-firebase/README.md); install `FirebaseAdConfigSource`, then call `AdConfig.refresh()` from a custom splash. The supplied onboarding splash already refreshes. |
| Adjust attribution/revenue | Set `ERainAdConfig.adjustConfig` before init; see [AdjustConfig](src/main/java/com/ads/module/config/AdjustConfig.java). Leave it unset to keep Adjust off. UA-gated placements require attribution. |
| Premium users without ads | Follow [PayKit](../paykit/README.md) for a prebuilt paywall; it initializes billing. For your own UI, follow [BillingKit](../billingkit/README.md). Complete that setup before ad requests. |
| Rewarded ads | `RewardAdManager.loadAndShow(activity, AppAdPlacement.REWARD_EXAMPLE, onSuccess, onFailed)`, or `load` then `show`; grant only when `onClosed(earned)` has `earned=true`. `show` applies the placement's config gate for any key your JSON declares, so `onFailedToShow` also covers a disabled slot, the UA gate, premium and consent. |
| Automatic interstitial preload | Configure placements and start [InterstitialAutoBuffer](src/main/java/com/ads/module/helper/interstitial/InterstitialAutoBuffer.kt) from the first content screen after onboarding. It pauses in background and shares the manager cache and group gate. |
| App-open on return | Set `ERainAdConfig.idAdResume` from the `open_resume` placement before init; exclude splash/sensitive Activities with `AppOpenManager.disableAppResumeWithActivity`. See [App-open on return](#app-open-on-return). |

## Automatic interstitial preload

Set `intervalInterstitialAd = 30` (seconds, choose your own interval) in the
`ERainAdConfig(...).apply` block before initialization; `0` disables the interval gate.
Configure the group once after ads initialization. Include only your in-app content placements;
keep splash and `inter_after_ob3` outside this group. Define each placement in your ad JSON.

```kotlin
import com.ads.module.helper.interstitial.InterstitialAutoBuffer
import com.ads.module.helper.interstitial.InterstitialBufferOptions

// Application.onCreate(), after ERainAd.init(...).
InterstitialAutoBuffer.configure(
    InterstitialBufferOptions(placements = listOf(AppAdPlacement.INTER_BACK, AppAdPlacement.INTER_ALL)),
)

// First actual content Activity, after onboarding/consent/remote setup.
// Also cover direct notification, deep-link and restored entry paths.
InterstitialAutoBuffer.start(applicationContext)
```

Keep using `InterstitialAdManager.show` at navigation opportunities. The first preload
waits for the configured interstitial interval; closing a group ad or a final load failure
starts the next interval. The SDK pauses scheduling in background and retains ready ads.
Repeated `start()` is safe. `topUpNow()` checks eligibility and cannot bypass the interval.
Use the manager APIs for these placements and remove app-side refill timers or temporary
interval overrides. Call `stop()` only when you want to disable buffering.

### Opt-in content wait and independent placement clocks

For selected content placements, use the existing buffer with independent clocks. The buffer
preloads at `max(0, interval - 2_000ms)`; presentation still requires the full interval and a
new action. A final load failure retries after the full placement interval (or the positive
idle cadence when the interval is zero).

```kotlin
InterstitialAutoBuffer.configure(InterstitialBufferOptions(
    independentIntervalPlacements = setOf(AppAdPlacement.INTER_ALL, AppAdPlacement.INTER_BACK),
    placements = listOf(AppAdPlacement.INTER_ALL, AppAdPlacement.INTER_BACK),
    tapThresholds = mapOf(AppAdPlacement.INTER_ALL to 2),
    intervalMsByPlacement = mapOf(AppAdPlacement.INTER_ALL to 30_000L, AppAdPlacement.INTER_BACK to 30_000L),
    // Optional and purely additive: the buffer already applies isEnable and enable_ua_check.
    isPlacementEnabled = { placement -> contentPlacementEnabled(placement) },
))
// Keep start() on actual content entry, as above.

// One real content/navigation action. Coalesce repeated taps while this action is pending.
InterstitialAdManager.loadAndShow(activity, placement, callback,
    InterLoadAndShowOptions(
        allowWaitForAutoBuffer = true,
        timeoutMs = 5_000L,
        nextAction = InterNextAction.AfterDismiss,
    ),
)
```

Do not pre-check `canShow()` in this wrapper: the action must reach the manager to count once
and take the ready/join/cold path. All independent placements share a two-action presentation
guard; it does not gate preload or combine their clocks. Counters reset on the vendor's actual
show callback. Back that exits the app is outside this flow. Wire navigation only to `onComplete`.

The opt-in budget is clamped to 0–5,000ms from entry. Zero budget uses a ready ad or skips without
starting a request for that invocation. Timeout/background detaches the UI wait; late fills stay
cached and need a new action. The existing ~800ms show preparation is additional to the fill wait.
Call `onGateChanged()` when a custom flag changes; SDK remote-config and consent changes already
notify it. Preload, waiting and delayed show read the same current placement authority.

Opted-in clicks emit `ad_interstitial_wait` with `placement`, `source` (`ready/join/cold`), `status`
and `wait_ms`; `dispatch` means handing off to show, not an impression. Use existing actual
`ad_impression` / `ad_skipped` events for presentation outcomes. Joining does not add `ad_request`.

## App-open on return

Declare the unit under `open_resume` in your ad JSON, then seed it before `ERainAd.init(...)`;
the setter also enables resume ads:

```kotlin
// Inside the ERainAdConfig(...).apply block from step 1:
idAdResume = AdGate.adUnitIds(AppAdPlacement.OPEN_RESUME).firstOrNull().orEmpty()
```

From then on the SDK re-points the unit at `open_resume` on every config update, so a remote
refresh needs no extra call. Two limits: it only takes over once a non-empty unit exists, and
`open_resume` must carry an ad unit id. Ship it enabled with a real id — `isEnable: false` empties
the unit, and app-resume stays off until the config turns it back on.

The app-resume load honours `open_resume.enable_ua_check` like every other placement, so a
UA-gated slot does not request on an organic install.

Exclude your custom splash and sensitive Activities during Application setup:

```kotlin
import com.ads.module.admob.AppOpenManager

AppOpenManager.getInstance().disableAppResumeWithActivity(SplashActivity::class.java)
```

OnboardKit handles its splash/fullscreen/survey exclusions. With OnboardKit, also map
`AdsConfig.appResume` to your app-open unit so its placement gate is configured; see the
[onboarding guide](../onboardkitorigin/README.md#app-open-on-return).

The SDK loads on a genuine background transition and shows only an already-ready ad on an
eligible return. It does not wait for a load on foreground entry. To tune background delay,
add `"app_resume_load_delay_ms": 2000` inside the `open_resume` placement in
`ad_config.json`, `ad_config_debug.json`, or remote `ad_remote_config`:

```json
{
  "open_resume": {
    "id": "your-app-open-ad-unit-id",
    "isEnable": true,
    "app_resume_load_delay_ms": 2000
  }
}
```

Use `open_resume` as the placement key in both asset files and remote config.
Place `app_resume_load_delay_ms` inside that entry.

Values are milliseconds, from 0 to 86,400,000; default 2000. Missing or invalid values
use the default.
Returning before the delay cancels the scheduled load. No app-side lifecycle timer is required.

## Behaviour changes in 5.3.0

Suggested release number for the placement-driven entry points above. The API of 5.2.x is unchanged
— the id-taking overloads and the `NativeAdConfig` / `BannerAdConfig` constructors all still work —
but five behaviours differ:

| Change | What to do |
|---|---|
| `isEnable: false` on a **base key** now disables every `_high*` floor with it. Previously a floor stayed live. | Re-enable the base key if a payload meant to keep one floor. |
| `show` applies the placement's config gate — `isEnable`, `enable_ua_check`, consent, premium — for any key your JSON **declares**, `InterstitialAdManager.show` and `RewardAdManager.show` alike, even with a fill already buffered. A key your JSON does not declare is unaffected. | Expect `DISABLED_CONFIG` / `UA_GATE` / `CONSENT_NOT_GRANTED` / `PURCHASED` on those paths. |
| App-resume load honours `open_resume.enable_ua_check`; it used to ignore it. | Set it `false` to keep the old behaviour. |
| The onboarding flow's `inter_after_ob3` is the one flow placement whose key is also a JSON key, so its `enable_ua_check` now gates that interstitial on both load and show. | Set it `false` to keep showing the end-of-onboarding interstitial on organic installs. |
| `show` through a context that is not an `AppCompatActivity` reports `SHOW_IN_BACKGROUND` and keeps the fill, instead of `FAILED_TO_SHOW` and losing it. | Nothing; the fill survives for the next trigger. |

## Troubleshooting

| Symptom | Check |
|---|---|
| Init crashes | AdMob app ID placeholder, both Meta metadata entries/resources, and your Application registration. |
| No ads | Consent result, premium state, exact placement key, usable IDs and `isEnable`. `showSkipReason` explains an interstitial rejection. |
| Debug uses unexpected IDs | Supply `ad_config_debug.json`; debug does not substitute test IDs automatically. |
| Remote config stays unchanged | Install a source and refresh it. Debug assets are pinned by default; enable overrides explicitly only when intended. |
| Native/banner stays empty | Use the right container/lifecycle, wait for consent, and confirm the placement matches your JSON. |

Consumer ProGuard rules ship with the library. Bundled adapters are listed in [build.gradle](build.gradle).
License: [MIT](../LICENSE).

## System-bar API

```kotlin
import com.ads.module.util.AdSystemBars

// Default: hide navigation; show status and desktop caption bars.
AdSystemBars.setFullscreen(window)

// Optional overrides, including restoring bars previously hidden by another window.
AdSystemBars.setFullscreen(window, showNavigationBar = true)
```

Call after creating your window and when it regains focus (`onWindowFocusChanged(true)`).
This API changes bar visibility; apply visible-bar insets to your own content as needed.
Java supports `AdSystemBars.setFullscreen(getWindow())` with the same defaults.
