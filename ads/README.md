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
  "inter_back":     { "ids": [{ "id": "YOUR_INTERSTITIAL_UNIT_ID" }], "isEnable": true },
  "native_home":    { "ids": [{ "id": "YOUR_NATIVE_UNIT_ID" }], "isEnable": true },
  "banner_home":    { "ids": [{ "id": "YOUR_BANNER_UNIT_ID" }], "isEnable": true },
  "reward_example": { "ids": [{ "id": "YOUR_REWARDED_UNIT_ID" }], "isEnable": true }
}
```

Every build reads its settings from `ad_config.json`. A debuggable build also reads `ad_config_debug.json`, which holds only `ids` per placement key, normally one test floor: those floors replace the placement's whole waterfall, so each placement loads its test ID through the usual load path. Any other field in the debug file is ignored with a `WARN`, and a placement enabled in `ad_config.json` without a debug entry gets no ad (also a `WARN`). Remote `ad_remote_config` applies every field except IDs; `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` also takes the IDs remote declares. Without a debug file, debug requests the `ad_config.json` IDs.

Sources rank remote `ad_remote_config` (`ads_remote_config` is read when the Console has no
`ad_remote_config`) > `ad_config.json` > ad units set in code > SDK defaults.

Every placement lists its units in `"ids"` as floors `{"id": "...", "isEnable": true}`, highest
first and all-price last, requested in that order; a single unit is a one-floor array without a
floor `isEnable`, `"ids": [{ "id": "..." }]`. The
placement's `isEnable` is the master switch; a floor's own `isEnable: false` pauses only that floor
and keeps its ID in place. A placement-level `"id"` and separate `<placement>_high*` keys are not read. See [AdUnitConfig](src/main/java/com/ads/module/config/AdUnitConfig.kt).

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
`ad_skipped` your dashboard slices by; every floor in a key's `ids` reports under that key. Ad unit ids cannot stand in for it — Google's test units give
one id per format, and production payloads reuse a unit across screens, so several placements share
one id routinely. A raw string that drifts from the JSON does not fail either: the placement resolves
to no ad units, the slot silently never fills, and the only trace is `ad_skipped` with
`disabled_config` (native also logs "Ad unit '<key>' not found"). A constant turns
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
required form that is unavailable) or the network timeout (10 seconds by default, stopped before a
form is shown) opens an in-process request fallback, so `allowed` can be `true` without consent or
personalization. When `allowed` is false, continue without ads. By default, debuggable builds treat
every device as an EEA test device, so no hashed device ID is needed to see the form. Timeout and debug geography: [ConsentOptions](src/main/java/com/ads/module/consent/ConsentOptions.kt).

## 4. Preload interstitials; show only when ready

These are methods in your `AppCompatActivity`. Preload earlier, after consent; call the show
method only at a new navigation opportunity. `goNextScreen()` is your app's navigation.

```kotlin
import com.ads.module.helper.interstitial.InterstitialAdManager

fun preloadInterBack() = InterstitialAdManager.load(applicationContext, AppAdPlacement.INTER_BACK)

fun showInterBackOrContinue() =
    InterstitialAdManager.show(this, AppAdPlacement.INTER_BACK) { goNextScreen() }
```

Use the lambda when all you do is navigate; take the `InterShowCallback` overload when the screen
also needs `onShowed`, `onClosed`, `onSkipped` or `onClicked`. `loadAndShow` and
`RewardAdManager.show` have the same pair — rewarded passes `earned` to its lambda.

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
style — and the request is under way when they return. Pass `layoutId` or a
[`BannerType`](src/main/java/com/ads/module/helper/banner/BannerType.kt) to change the template;
build [NativeAdConfig](src/main/java/com/ads/module/helper/adnative/NativeAdConfig.kt) or
[BannerAdConfig](src/main/java/com/ads/module/helper/banner/BannerAdConfig.kt) yourself when the
app supplies its own ad units.

Banner shimmer sizing is owned by the SDK. `InlineMaxHeight(100)` reserves a 100dp
placeholder as soon as it is attached; other explicit inline caps and fixed banner sizes
reserve their configured height. The skeleton fills that height. At request time, the
loader updates it for the resolved size, including Remote Config changes. Uncapped inline
adaptive banners keep the configured shimmer layout height until the creative arrives,
because their requested ad size reports zero height. This also applies to the legacy
inline/medium banner layouts. Keep the host
`FrameLayout` at `wrap_content` so a failed/skipped banner can collapse; apps do not need
to resize SDK shimmer children or set a permanent minimum height.

Native includes a generated loading skeleton. To customize it, copy the
[supplied native layout](src/main/res/layout/custom_native_admob_medium.xml), keeping its
`NativeAdView` root, asset IDs and ad badge. The banner example refreshes through the SDK by
default (`banner.reload.allowed` and `banner.reload.auto_enabled` are `true`; `reloadIntervalSeconds`
sets the interval), so turn AdMob console refresh off for every tier of that banner or it refreshes
twice; to leave refresh to AdMob instead, set `banner.reload.allowed` to `false`. The native example
has no timer refresh (`native.reload.timer_enabled` is `false`).

## Native preload, repeated show, and refresh

Use a stable placement for each slot and one `NativeAdHelper` per Activity/view. A Fragment
uses `viewLifecycleOwner`. The shared manager keeps one unused ad and one pending request per
placement, so a singleton helper holding an Activity is unnecessary.

```kotlin
// Optional: preload earlier, after consent.
val nativeConfig = NativeAdConfig.forPlacement(AppAdPlacement.NATIVE_HOME, com.ads.module.R.layout.custom_native_admob_medium)
NativeAdManager.preload(applicationContext, AppAdPlacement.NATIVE_HOME, nativeConfig)

// Destination Activity; use viewLifecycleOwner for a Fragment.
val nativeHelper = NativeAdHelper.forPlacement(this, this, AppAdPlacement.NATIVE_HOME, binding.frAds)
```

- `show()` uses a ready ad, joins a pending request, or loads one. Repeated calls while loading
  share that request. Calling it again after a successful bind requests a replacement.
- For timed refresh, pass `canReloadAds = true` to `NativeAdHelper.forPlacement(...)` — it is a
  constructor value, not a settable property; building `NativeAdConfig` and the `NativeAdHelper`
  constructor yourself drops the placement's `ad_config` style — and call
  `applyReloadByTime(intervalMs)` on the returned helper. The current ad stays visible while loading;
  refresh pauses when the slot is hidden/stopped.
- Pause/stop retain the current native view and pending load, with or without refresh enabled.
  A fill received in background waits for resume and binds once; returning does not restart
  the initial request. Explicit resume/timer refresh still follows its configured interval
  and keeps the current ad until a replacement binds. Click reload uses its own shared request.
- For retained pager pages or custom navigation, call `cancel()` when the page is unselected
  and `show()` when selected. Use `destroy()` when permanently disposing the helper.
- After rotation, recreate the helper with the same placement and call `show()`; an
  `AppCompatActivity` host restores its current presentation. Ads do not survive process death.
- Do not call `NativeAdManager.release(placement)` for routine screen cleanup: it invalidates
  shared pending/unused ads. Use it only when deliberately discarding that placement's inventory.

`native.load.tier_timeout_ms` bounds each waterfall tier, not the whole screen or network
transfer. An expired tier advances to the next floor; a late native from that tier is destroyed.
The GMA `AdLoader` API used here has no public request-cancellation method, so this deadline
does not abort an outstanding network operation. Shared placement loads prevent duplicate
logical waterfalls, but do not impose a global limit on vendor requests across placements.

### Native click return

Each native takes exactly one click action from `click_action` on its placement's key in
`ad_config`; values, defaults and precedence are in the
[remote settings guide](../partner-integration/remote-settings.md#native-click-actions).

- `reload` (default for app screens): click/open immediately preloads a replacement from the
  all-price floor only. On return,
  the helper consumes a ready ad or joins that same request. It does not wait for resume to start
  loading. The current ad stays visible while waiting, without shimmer. Only a successful
  replacement bind removes the old ad; a failed reload keeps it. Shimmer is for initial loading
  without an ad.
- `reload_waterfall`: as `reload`, but the replacement walks every enabled floor in `ids`, highest first.
- `none`: keep the current ad, with no click replacement or automatic navigation.
- `auto_next`: no click replacement; the onboarding host advances on ad return.

The action is captured before notifying click listeners and stays fixed for that trip, even
if remote settings change. Duplicate click/open callbacks share one request. Ordinary app
resume does not count as an ad click. Popup destinations that pause without stopping the
host are supported. Consent, purchase and network gates still apply.

When the key declares no `click_action`, the helper uses its code default, `reload`. To make
that default `none`, call the following; `click_action` in ad_config still wins:

```kotlin
nativeHelper.setReloadOnAdClick(false)
```

Explicit timer/resume refresh options remain separate and are disabled by default.

## Optional integrations

| Need | Add or configure |
|---|---|
| Remote placements / Firebase analytics | [suite-firebase](../suite-firebase/README.md); install `FirebaseAdConfigSource`, then call `AdConfig.refresh()` from a custom splash. Installing applies the document the backend last delivered right away; the refresh fetches a newer one. The supplied onboarding splash already refreshes. |
| Adjust attribution/revenue | Set `ERainAdConfig.adjustConfig` before init; see [AdjustConfig](src/main/java/com/ads/module/config/AdjustConfig.java). Leave it unset to keep Adjust off. Every AdMob paid impression, app-open included, reports Adjust ad revenue; also set `eventAdImpression` on it to send the token-keyed impression event that networks such as Meta and TikTok read. UA-gated placements require attribution. |
| Premium users without ads | Follow [PayKit](../paykit/README.md) for a prebuilt paywall; it initializes billing. For your own UI, follow [BillingKit](../billingkit/README.md). Complete that setup before ad requests. |
| Rewarded ads | `RewardAdManager.preload(context, placement)` (or `load`) shares one cache/request per placement. `show(activity, placement) { earned -> }` consumes a ready ad; `loadAndShow(activity, placement, onSuccess, onFailed)` uses cache, waits for an active request, or loads. Grant only when earned; the manager does not refill automatically. `show` keeps the placement's config, UA, premium and consent gates. |
| Automatic interstitial preload | Configure placements and start [InterstitialAutoBuffer](src/main/java/com/ads/module/helper/interstitial/InterstitialAutoBuffer.kt) from the first content screen after onboarding. It pauses in background and shares the manager cache and group gate. |
| App-open on return | Declare `open_resume` in `ad_config.json` or the backend's `ad_remote_config`; `ERainAdConfig.idAdResume` is only the fallback when neither declares it. Exclude splash/sensitive Activities with `AppOpenManager.disableAppResumeWithActivity`; keep app-open off with `AppOpenManager.getInstance().disableAppResume()`. See [App-open on return](#app-open-on-return). |

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

### Limit automatic preload to selected screens

For a placement such as Back, let the app report the active screen and allow automatic requests
only where that placement has a useful show opportunity. Activity, Fragment and Compose hosts use
the same API; the SDK does not depend on Compose or Navigation.

```kotlin
// Once, alongside your existing configure(...), before content-entry start(...).
InterstitialAutoBuffer.setPreloadScreens(
    mapOf(AppAdPlacement.INTER_BACK to setOf("translate", "camera", "conversation")),
)

// Main thread: report the screen once it is selected and resumed.
val screen = InterstitialAutoBuffer.setCurrentScreen("translate")
// Close from this screen owner's pause/disposal callback.
screen.close()
```

Start the buffer from the first content screen, including Home; entering a feature must not
start a new timer. Changing the screen wakes the buffer to check existing eligibility. If the
preload deadline is still ahead, it waits only the remaining time. If the deadline and taps were
already satisfied on Home, entering an allowed screen prompts a request immediately, subject to
the other gates and existing cache/request. Screen changes never reset clocks or tap counts.

`setPreloadScreens` replaces the complete whitelist snapshot. An omitted placement keeps its
existing behavior; a present empty set disables its automatic preload. Restricted placements
require a matching screen; `null` means no active screen. Both setters and handle cleanup run on
the main thread. Closing an older screen handle cannot clear a newer registration. Re-registering
the same ID transfers ownership without restarting scheduling; there is no stack of old screens
to restore when a handle closes.

This gate controls auto-buffer preload/refill and `topUpNow()` only. Explicit manager `load`,
`loadAndShow` and `show` retain their existing behavior. Remove explicit Home/splash Back preloads
if the buffer should own that inventory. Leaving an allowed screen retains ready ads and pending
loads, so Back can still show a ready ad after navigation returns to Home. There is no automatic
show on entry and no change to the existing dismissal/final-failure clock semantics.

Use one active screen source for each navigation hierarchy and report Home/other destinations
too. See the [Vietnamese integration guide](../partner-integration/interstitial-preload-screens.vi.md)
for lifecycle-safe Activity, Fragment, custom navigation and Compose examples, timing scenarios
and the TranslatorGuru mapping.

### Opt-in content wait and independent placement clocks

For selected content placements, use the existing buffer with independent clocks. The buffer
only requests an ad when both the placement's tap threshold and
`max(0, interval_ms - preload_lead_ms)` have been satisfied. This applies to automatic preload,
explicit manager loads and refills after dismissal. A ready or in-flight ad does not trigger
another request. Presentation still requires the full interval and a new action.
A final load failure retries after the full placement interval (or the positive
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
and take the ready/join/cold path. With `interstitial_auto_buffer.shared_config: true`,
independent placements share a two-action presentation guard; it does not gate preload or combine
their clocks. Counters reset on the vendor's actual
show callback. Back that exits the app is outside this flow. Wire navigation only to `onComplete`.

Set `interstitial_auto_buffer.shared_config` to `false` in `ad_behavior_config` to isolate every
buffer placement, including `inter_all` and `inter_back`. Each uses its own `interval_ms`
and `tap_threshold` from `rules` (or the host's `intervalMsByPlacement` / `tapThresholds`).
The shared two-action guard is disabled: taps, actual shows, closes and final load failures only
affect that placement. A threshold of `0` needs no taps; `1` allows the first action to show once
the cooldown has elapsed. Ads still cannot present simultaneously.

```json
{
  "interstitial_auto_buffer": {
    "shared_config": false,
    "rules": {
      "inter_all": { "enabled": true, "interval_ms": 30000, "tap_threshold": 2 },
      "inter_back": { "enabled": true, "interval_ms": 30000, "tap_threshold": 1 }
    }
  }
}
```

The bundled asset uses `shared_config: false`, with ALL at 30 seconds / 2 taps and BACK at
30 seconds / 1 tap. With the bundled 2-second preload lead, loading starts no earlier than
28 seconds and still needs the placement's taps; showing waits the full 30 seconds. Remote or app-asset rules override host options, which override these
bundled values. `false` takes precedence over each rule's `independent_interval`; no
per-placement opt-in is needed. Placements without a rule or host value inherit the global
interval with separate clocks and a tap threshold of `0`. `true` preserves the shared guard, including explicit
`independentIntervalPlacements` / `independent_interval` overrides. The flag does not start
the buffer or enable disabled placements; keep the content-entry `start()` call above.

The content wait is on by default (bundled `interstitial.load_and_show.allow_wait_for_auto_buffer`
is `true`); set it `false`, or pass `allowWaitForAutoBuffer = false`, to keep a buffer placement
cache-only. Its budget runs from entry and is only floored at 0, with no upper clamp (bundled
`buffer_wait_timeout_ms` is 8,000ms). Zero budget uses a ready ad or skips without
starting a request for that invocation. Timeout/background detaches the UI wait; late fills stay
cached and need a new action. The existing ~800ms show preparation is additional to the fill wait.
Call `onGateChanged()` when a custom flag changes; SDK remote-config and consent changes already
notify it. Preload, waiting and delayed show read the same current placement authority.

Clicks with the content wait on (the default) emit `ad_interstitial_wait` with `placement`, `source` (`ready/join/cold`), `status`
and `wait_ms`; `dispatch` means handing off to show, not an impression. Use existing actual
`ad_impression` / `ad_skipped` events for presentation outcomes. Joining does not add `ad_request`.

## App-open on return

Declare the unit under `open_resume` in your ad JSON, then seed it before `ERainAd.init(...)`:

```kotlin
// Inside the ERainAdConfig(...).apply block from step 1:
idAdResume = AdGate.adUnitIds(AppAdPlacement.OPEN_RESUME).firstOrNull().orEmpty()
```

From then on the SDK re-points the unit at `open_resume` on every config update, so a remote
refresh needs no extra call. `open_resume` must carry an ad unit id; an entry that only tunes the
load delay leaves the unit as it is. An `open_resume` with an id in `ad_config.json` or the backend's
`ad_remote_config` outranks the seed, so app-open runs even when the app seeds nothing. To keep app-open off, call
`AppOpenManager.getInstance().disableAppResume()`; no config document turns it back on. Ship
`open_resume` enabled with a real id — `isEnable: false` empties the unit, and app-resume stays
off until the config turns it back on.

The app-resume load honours `open_resume.enable_ua_check` like every other placement, so a
UA-gated slot does not request on an organic install.

Exclude your custom splash and sensitive Activities during Application setup:

```kotlin
import com.ads.module.admob.AppOpenManager

AppOpenManager.getInstance().disableAppResumeWithActivity(SplashActivity::class.java)
```

OnboardKit handles its splash/fullscreen/survey exclusions. With OnboardKit, its placement gate
also needs `AdsConfig.appResume`: `AdsConfig.fromAdConfig()` binds it to `open_resume`, and an
`open_resume` in `ad_config.json` or the backend's `ad_remote_config` fills it without a binding; see the
[onboarding guide](../onboardkitorigin/README.md#app-open-on-return).

The SDK loads on a genuine background transition and shows only an already-ready ad on an
eligible return. It does not wait for a load on foreground entry. To tune background delay,
add `"app_resume_load_delay_ms": 2000` inside the `open_resume` placement in
`ad_config.json` or remote `ad_remote_config`:

```json
{
  "open_resume": {
    "ids": [{ "id": "your-app-open-ad-unit-id" }],
    "isEnable": true,
    "app_resume_load_delay_ms": 2000
  }
}
```

Use `open_resume` as the placement key in both asset files and remote config.
Place `app_resume_load_delay_ms` inside that entry.

Values are milliseconds, from 0 to 86,400,000; default 2000. A missing or invalid value
falls back to the next lower config source (e.g. remote to app asset), and to 2000 only when
no source has a valid value.
Returning before the delay cancels the scheduled load. No app-side lifecycle timer is required.

## Important behavior

**Fullscreen placement correction.** OB fullscreen uses `native_full1`/`native_full2`;
`native_fs` is an independent optional screen after the splash interstitial and before LFO,
disabled in the partner sample's JSON (`partner-integration/examples/ads-onboarding/ad_config.json`). It preloads after the interstitial loads and only
opens if ready after dismissal. The last content page uses `native_ob3`, with UA checks off
in the example. OB defaults now show X after 5 seconds and auto-advance after 15 seconds.

**Returning-user splash compatibility.** Returning users use the existing `inter_splash_o`
placement key, including its waterfall tiers. Switching `inter_splash_o` off silences returning users only,
entry launches included; switching `inter_splash` off does the same for new users and leaves returning
users on `inter_splash_o`.

**Grouped remote settings.** `ad_behavior_config` and `onboarding_config`
add validated remote overrides with bundled/custom local defaults and last-good remote cache.
A remote value, live or cached, outranks your app-asset JSON at any scope, which outranks options
set in code. OnboardKit resolves standard ad_config placements after fetch through
`AdsConfig.fromAdConfig()`, and applies a standard key that `ad_config.json` or the backend's
`ad_remote_config` declares even when your `AdsConfig` does not bind it; native template, CTA radius and Skip/X presentation
remain configurable for experiments.
See the [Firebase setup](../partner-integration/firebase-integration.md#remote-json) and
[all fields/defaults](../partner-integration/remote-settings.md).

**Rewarded cache.** `preload` shares `load`'s cache/request;
`loadAndShow` reuses a ready ad or joins an active load. The manager no longer triggers legacy
refill. Shown/impression callbacks are optional; each terminal completes once. Timeout, premium
gates and other formats keep their established behavior.
Partner screens call SDK APIs directly; `AdsAppManager` groups initialization and app policy.

**Lambda overloads.** `InterstitialAdManager.show`, `InterstitialAdManager.loadAndShow`
and `RewardAdManager.show` gained overloads that take the completion as a lambda. Nothing else
changed: the callback-taking overloads keep their signatures and are not deprecated, Java call sites
are untouched, and the lambda simply binds `onComplete` — same gates, same order, same timing, same
once-on-every-outcome guarantee. Rewarded passes `earned` to its lambda, since that is its outcome.

### Placement-driven entry point behavior

The id-taking overloads and the `NativeAdConfig` / `BannerAdConfig` constructors still work. The
placement-driven entry points have these behaviors:

| Change | What to do |
|---|---|
| A placement's waterfall is its own key's `"ids"` of `{"id", "isEnable"}` floors (highest first, all-price last); bare-string entries are skipped with a warning. A single unit is a one-floor array: a placement-level `"id"` is now an unknown field, ignored with a warning, in `ad_config.json` and `ad_config_debug.json` alike. `<key>_high` / `<key>_highN` keys are no longer read as floors (logged as a warning), so load and revenue events no longer split between `<key>` and `<key>_high`. `AdRemoteConfig.baseKeyOf()` is removed; `tiersFor`, `declares`, `remoteDeclares`, `declaredAboveCode` and `remoteDeclaresField` look at that one key only. `AdUnitConfig.id` is removed: the constructor's first parameter is now `ids: List<String>`. | Move each `_high*` ID into `<key>.ids` as `{"id", "isEnable"}` (keep its old `isEnable`; the old `id` becomes the last floor) in `ad_config.json` and Firebase `ad_remote_config`. Every other `"id": "X"`, `ad_config_debug.json` included, becomes `"ids": [{"id": "X"}]`. In Kotlin, pass `ids = listOf(...)` to `AdUnitConfig`. Rollout: [partner migration steps](../partner-integration/ads-onboarding-integration.md#waterfall-one-key-per-placement). |
| `show` applies the placement's config gate — `isEnable`, `enable_ua_check`, consent, premium — for any key your JSON **declares**, `InterstitialAdManager.show` and `RewardAdManager.show` alike, even with a fill already buffered. A key your JSON does not declare is unaffected. | Expect `DISABLED_CONFIG` / `UA_GATE` / `CONSENT_NOT_GRANTED` / `PURCHASED` on those paths: interstitial passes the reason to `onSkipped`; rewarded only calls `onFailedToShow(0)`, with the reason in `ad_skipped`. |
| App-resume load honours `open_resume.enable_ua_check`; it used to ignore it. | Set it `false` to keep the old behaviour. |
| The onboarding flow's `inter_after_ob3` is the interstitial flow placement whose key is also a JSON key, so its `enable_ua_check` now gates that interstitial on both load and show. | Set it `false` to keep showing the end-of-onboarding interstitial on organic installs. |
| `show` through a context that is not an `AppCompatActivity` reports `SHOW_IN_BACKGROUND` and keeps the fill, instead of `FAILED_TO_SHOW` and losing it. | Nothing; the fill survives for the next trigger. |

## Troubleshooting

| Symptom | Check |
|---|---|
| Init crashes | AdMob app ID placeholder, both Meta metadata entries/resources, and your Application registration. |
| No ads | Consent result, premium state, exact placement key, usable IDs and `isEnable`. `showSkipReason` explains an interstitial rejection. |
| Debug uses unexpected IDs | Supply `ad_config_debug.json` with test `ids` for every enabled placement key; the `WARN` from `AdRemoteConfig` lists enabled keys that have none. |
| Remote config stays unchanged | Install a source and refresh it. `AdConfig` logging `cleared` means the Console has neither `ad_remote_config` nor `ads_remote_config`; pass another name to `FirebaseAdConfigSource(key)`. A debuggable build keeps its `ad_config_debug.json` test IDs and applies every other remote field; call `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` only when you intend to spend the remote IDs. |
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

## App updates

`com.ads.module.ump.ITGUpdateManager` and `IUpdateInstanceCallback` restore the Play update API
(IMMEDIATE / FLEXIBLE), with lifecycle cleanup and update resumption. Consent stays in ConsentCenter.
`com.ads.module.update.ForceUpdateGate.await(activity, config)` holds startup navigation for outdated
versions; a mandatory policy remains blocking after Play cancellation or a Store round trip.
`enabled` defaults to false and must be explicitly true. `force` controls dismissal only;
`minVersionCode > installedVersionCode` controls version eligibility.
`:suite-firebase` offers `FirebaseUpdateConfig.activated()` after the shared remote step, or
`FirebaseUpdateConfig.fetch()` for standalone hosts, for the separate `force_update_config` parameter.
Only activated remote values can enable the Firebase integration; missing/malformed/local defaults
are off. OnboardKit hosts override `readForceUpdateConfig()` to supply a snapshot after the existing remote
step. The attempt retains that snapshot and its request hold across recreation. Mandatory updates block
all new AdLogic requests, including background loaders. Splash requests start after consent using the current policy; remote fetch does not block them. A required policy already known blocks requests. A policy delivered before presentation can block the show, but cannot undo requests already sent.

See the [detailed Vietnamese integration guide](../partner-integration/force-update-integration.vi.md)
for dependencies, Remote Config JSON, cache behavior, OnboardKit/standalone examples, and Play testing.

## Placement settings at runtime

Settings resolve as remote > delivered legacy settings > app root asset > host configuration > bundled SDK defaults. Within one source, screen/group overrides precede placement and format overrides. Empty behavior objects add no leaf values and allow fallback. A full default JSON copied into the app asset root is still an explicit override of host values. See [remote settings](../partner-integration/remote-settings.md) for the complete schema.

- `InterstitialAdManager.show(..., behavior)` accepts optional screen behavior; ordinary `show` uses the placement behavior. `loadAndShow` carries its captured behavior into presentation, including the ready-cache path. `presentation.loading_enabled` controls the loading dialog; the global pre-show delay remains separate. `AdCallback.showsInterstitialLoadingDialog()` is forwarded through ERainAd and tracking wrappers.
- An AutoBuffer rule in remote or the app asset adds its placement to the host's managed list. Only an explicit `"enabled": true` runs a placement the host did not list; without it that placement is managed but off, so preload, load and `show` skip with `DISABLED_CONFIG`. The host predicate and `enabled: false` can still block a listed one. The bundled rules cover `inter_all` and `inter_back` only and opt nothing in by themselves. The host must configure and start the buffer. A placement added while running has no initial cooldown on its own clock; only its tap threshold applies until its first show or failure. `tickMs = 0` follows the resolved interstitial interval.
- `BannerAdConfig.forPlacement` reads the placement's current `enable_ua_check` after refresh unless the host explicitly sets `forceUaCheck`. A non-positive `reloadIntervalSeconds` falls back to host cadence; it does not cause immediate reload.
- Native `components: []` preserves the XML layout. A scoped `presentation.cta_corner_radius_dp` applies even without an explicit native style; a CTA color is needed to replace the XML background.
- App-open `failure_backoff_ms` accepts 1–10 integers in 1–3,600,000 ms. Empty/invalid arrays fall back to the app/SDK schedule. Consent timeout and interstitial daily click-cap logs report the effective resolved values.
