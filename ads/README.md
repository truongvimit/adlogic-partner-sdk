# Ads

Load and show AdMob ads from named placements. Start with local config and preload interstitials;
Firebase, Adjust, billing and app-open ads are optional. Using the supplied splash/onboarding?
Follow [onboardkitorigin](../onboardkitorigin/README.md) for that flow; it owns the consent request.

## Requirements and installation

Use `minSdk 24+`, `compileSdk 36+` and JDK 17. Add the repositories from the
[root setup](../README.md), including the mediation repositories. Google Mobile Ads and mediation
adapters are bundled; [build.gradle](build.gradle) lists versions and dependencies.

```groovy
// app/build.gradle — use the same published tag for every SDK module.
def sdkVersion = '5.2.9'
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
For waterfall floors, add `<placement>_high`, `_high1`…`_high9`; `tiersFor(placement)` returns
enabled floors first and the base placement last. See [AdUnitConfig](src/main/java/com/ads/module/config/AdUnitConfig.kt).

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

When `allowed` is false, continue without ads. A timeout or personalization choice does not grant
request permission. The default network timeout is 20 seconds and stops while a form is visible.
Optional test-device settings: [ConsentOptions](src/main/java/com/ads/module/consent/ConsentOptions.kt).

## 4. Preload interstitials; show only when ready

These are methods in your `AppCompatActivity`. Preload earlier, after consent; call the show
method only at a new navigation opportunity. `goNextScreen()` is your app's navigation.

```kotlin
import com.ads.module.config.AdRemoteConfig
import com.ads.module.helper.AdGate
import com.ads.module.helper.interstitial.InterLoadOptions
import com.ads.module.helper.interstitial.InterNextAction
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager

fun preloadInterBack() {
    val config = AdRemoteConfig.getInstance()
    val unit = config.unit("inter_back")
    InterstitialAdManager.load(applicationContext, "inter_back", config.tiersFor("inter_back"),
        InterLoadOptions(enabled = unit.isUsable,
            passesUaGate = AdGate.passesUaGate(unit.enableUaCheck)))
}

fun showInterBackOrContinue() {
    val unit = AdRemoteConfig.getInstance().unit("inter_back")
    if (!unit.isUsable || !AdGate.passesUaGate(unit.enableUaCheck) ||
        !InterstitialAdManager.canShow(this, "inter_back")) {
        goNextScreen()
        return
    }
    InterstitialAdManager.show(this, "inter_back", object : InterShowCallback() {
        override fun onComplete() = goNextScreen()
    }, nextAction = InterNextAction.AfterDismiss)
}
```

`canShow` checks the buffer, consent/premium and interval rules. A miss continues immediately;
a later fill stays buffered for another opportunity and does not show itself. Load calls share
an existing request/cache. Preload again after consumption, or use the optional auto-buffer below.
The existing buffered show path still has an approximately 800 ms preparation dialog.
Navigate only from `onComplete` after invoking `show`; it also runs when show fails or is skipped.

## Native and banner slots

Call after consent from a resumed `AppCompatActivity`. `binding.frAds` and `binding.frBanner` are empty
`FrameLayout` containers. For a Fragment, pass its Activity and `viewLifecycleOwner`.

```kotlin
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.toNativeStyle
import com.ads.module.helper.adnative.*
import com.ads.module.helper.banner.*

val config = AdRemoteConfig.getInstance()
val native = config.unit("native_home")
val nativeConfig = NativeAdConfig(config.tiersFor("native_home"), native.isUsable, false,
    com.ads.module.R.layout.custom_native_admob_medium).apply { forceUaCheck = native.enableUaCheck }
NativeAdHelper(this, this, nativeConfig)
    .setNativeContentView(binding.frAds)
    .setNativeStyle(native.toNativeStyle())
    .also { it.placement = "native_home" }
    .requestAds(NativeAdParam.Request)

val banner = config.unit("banner_home")
val bannerConfig = BannerAdConfig(config.tiersFor("banner_home"), banner.isUsable, false)
    .apply { forceUaCheck = banner.enableUaCheck }
BannerAdHelper(this, this, bannerConfig)
    .attachInto(binding.frBanner)
    .also { it.placement = "banner_home" }
    .requestAds(BannerAdParam.Request)
```

Native includes a generated loading skeleton. To customize it, copy the
[supplied native layout](src/main/res/layout/custom_native_admob_medium.xml), keeping its
`NativeAdView` root, asset IDs and ad badge. The banner example leaves refresh to AdMob:
`canReloadAds=false` and `enableAutoReload=false`. SDK refresh requires disabling console refresh
for every tier; see [BannerAdConfig](src/main/java/com/ads/module/helper/banner/BannerAdConfig.kt).

## Native preload, repeated show, and refresh

Use a stable placement for each slot and one `NativeAdHelper` per Activity/view. A Fragment
uses `viewLifecycleOwner`. The shared manager keeps one unused ad and one pending request per
placement, so a singleton helper holding an Activity is unnecessary.

```kotlin
// Optional: preload earlier, after consent, using the nativeConfig from the previous example.
NativeAdManager.preload(applicationContext, "native_home", nativeConfig)

// Destination Activity; use viewLifecycleOwner for a Fragment.
val nativeHelper = NativeAdHelper(this, this, nativeConfig)
    .setNativeContentView(binding.frAds)
    .setNativeStyle(native.toNativeStyle())
    .also { it.placement = "native_home" }
nativeHelper.show()
```

- `show()` uses a ready ad, joins a pending request, or loads one. Repeated calls while loading
  share that request. Calling it again after a successful bind requests a replacement.
- For timed refresh, set `NativeAdConfig.canReloadAds = true`, then call
  `applyReloadByTime(intervalMs)` before `show()`. The current ad stays visible while loading;
  refresh pauses when the slot is hidden/stopped.
- For retained pager pages or custom navigation, call `cancel()` when the page is unselected
  and `show()` when selected. Use `destroy()` when permanently disposing the helper.
- After rotation, recreate the helper with the same placement and call `show()`; an
  `AppCompatActivity` host restores its current presentation. Ads do not survive process death.
- Do not call `NativeAdManager.release(placement)` for routine screen cleanup: it invalidates
  shared pending/unused ads. Use it only when deliberately discarding that placement's inventory.

## Optional integrations

| Need | Add or configure |
|---|---|
| Remote placements / Firebase analytics | [suite-firebase](../suite-firebase/README.md); install `FirebaseAdConfigSource`, then call `AdConfig.refresh()` from a custom splash. The supplied onboarding splash already refreshes. |
| Adjust attribution/revenue | Set `ERainAdConfig.adjustConfig` before init; see [AdjustConfig](src/main/java/com/ads/module/config/AdjustConfig.java). Leave it unset to keep Adjust off. UA-gated placements require attribution. |
| Premium users without ads | Follow [PayKit](../paykit/README.md) for a prebuilt paywall; it initializes billing. For your own UI, follow [BillingKit](../billingkit/README.md). Complete that setup before ad requests. |
| Rewarded ads | Use `load`, `isReady` and `show`; grant only when `onClosed(earned)` has `earned=true`. See [RewardAdManager](src/main/java/com/ads/module/helper/reward/RewardAdManager.kt). |
| Automatic interstitial preload | Configure placements and start [InterstitialAutoBuffer](src/main/java/com/ads/module/helper/interstitial/InterstitialAutoBuffer.kt) from the first content screen after onboarding. It pauses in background and shares the manager cache and group gate. |
| App-open on return | Set `ERainAdConfig.idAdResume` before init; exclude splash/sensitive Activities with `AppOpenManager.disableAppResumeWithActivity`. See [AppOpenManager](src/main/java/com/ads/module/admob/AppOpenManager.java). |

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
    InterstitialBufferOptions(placements = listOf("inter_back", "inter_all")),
)

// First actual content Activity, after onboarding/consent/remote setup.
// Also cover direct notification, deep-link and restored entry paths.
InterstitialAutoBuffer.start(applicationContext)
```

Keep using `InterstitialAdManager.canShow/show` at navigation opportunities. The first preload
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
    independentIntervalPlacements = setOf("inter_all", "inter_back"),
    placements = listOf("inter_all", "inter_back"),
    tapThresholds = mapOf("inter_all" to 2),
    intervalMsByPlacement = mapOf("inter_all" to 30_000L, "inter_back" to 30_000L),
    isPlacementEnabled = { placement -> contentPlacementEnabled(placement) },
))
// Keep start() on actual content entry, as above.

// One real content/navigation action. Coalesce repeated taps while this action is pending.
InterstitialAdManager.loadAndShow(activity, placement, adUnitIds, callback,
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

The old options constructors and default managed cache-only behavior remain available. The new
constructors require the opt-in boolean/set, preserving the old JVM constructor descriptors.
Opted-in clicks emit `ad_interstitial_wait` with `placement`, `source` (`ready/join/cold`), `status`
and `wait_ms`; `dispatch` means handing off to show, not an impression. Use existing actual
`ad_impression` / `ad_skipped` events for presentation outcomes. Joining does not add `ad_request`.

## App-open on return

Set the app-open unit before `ERainAd.init(...)`; the setter also enables resume ads:

```kotlin
// Inside the ERainAdConfig(...).apply block from step 1:
idAdResume = "YOUR_APP_OPEN_UNIT_ID" // Use a test unit in debug.
```

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

Since 5.2.8, the resume placement key is `open_resume`, matching partner apps.
If you adopted `app_resume` in 5.2.3–5.2.7, rename it to `open_resume` in both
asset files and remote config. The nested field remains `app_resume_load_delay_ms`.

Values are milliseconds, from 0 to 86,400,000; default 2000. Missing or invalid values
use the default. The former top-level field is no longer read.
Returning before the delay cancels the scheduled load. No app-side lifecycle timer is required.

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
