# Ads

Load and show AdMob ads from named placements. Start with local config and preload interstitials;
Firebase, Adjust, billing and app-open ads are optional. Using the supplied splash/onboarding?
Follow [onboardkitorigin](../onboardkitorigin/README.md) for that flow; it owns the consent request.

## Requirements and installation

Use `minSdk 24+`, `compileSdk 36+` and JDK 17. The repository's target SDK is 36; this is not a
new migration requirement. Add the repositories from the
[root setup](../README.md), including the mediation repositories. Google Mobile Ads and mediation
adapters are bundled; [build.gradle](build.gradle) lists versions and dependencies.

```groovy
// app/build.gradle — use the same published tag for every SDK module.
def sdkVersion = '5.1.2'
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

`NativeAdManager` owns one unused native and one pending load per placement across screens.
Use a distinct placement for each slot, even when two slots use the same AdMob unit. Requests
from `NativeAdHelper` always share this store; enabling preload on the helper is no longer required.
Keep helpers scoped to their screen/view; the shared manager already owns the cross-screen state,
so partners do not need a singleton helper holding an Activity or ad view.

```kotlin
// Optional: start earlier, after consent. Repeat calls do not append load batches.
NativeAdManager.preload(applicationContext, "native_home", nativeConfig)

// In the destination Activity; for a Fragment use viewLifecycleOwner.
val nativeHelper = NativeAdHelper(this, this, nativeConfig)
    .setNativeContentView(binding.frAds)
    .also { it.placement = "native_home" }
nativeHelper.show()
```

`show()` consumes a ready ad, joins its pending load, or starts one. Calls while this helper is
loading coalesce. Once binding succeeds the ad is out of the unused cache, so a later explicit
`show()` requests another ad and replaces the current one when ready. Removing it from the cache
does not destroy the ad backing the visible view. Failed replacement loads/binds keep a valid
current ad. Gate denial and expired ads cannot be used as survivors.

For a slot that refreshes while visible, use the same helper with `canReloadAds = true` in
`NativeAdConfig`, then call `applyReloadByTime(intervalMs)` before `show()`. The old native stays
visible while its replacement loads. Failure retries use the configured interval, not an immediate
request loop. Hidden/stopped slots do not issue timed refresh requests.

A real departure stops the helper's timer and ends the displayed ad. The shared request and any
unused fill survive: returning uses that fill or joins that request. A retained helper automatically
starts a fresh visit on resume. With an `AppCompatActivity`/`ViewModelStoreOwner` host, configuration
recreation (including rotation) instead restores the current presentation and remaining refresh
time. Recreate the helper with the same placement and call `show()` as usual, including in
`onCreate`; it binds when resumed. This does not return the consumed ad to the preload cache.
Use one active helper per slot. Ads are held only in memory and cannot survive process death.

For a retained ViewPager page or a custom navigator that only pauses/hides the view, call
`cancel()` on page unselection and `show()` on selection. A pause alone can also mean a
translucent dialog or configuration transition, so it only suspends refresh. OnboardKit wires
its page-selection callbacks this way.

`cancel()` detaches the current helper and disposes its presentation; it does not cancel the shared
network request. If the helper will not be reused, call `destroy()` to also remove its lifecycle
observer and view references. `NativeAdManager.release(placement)` explicitly invalidates unused/pending fills.
Do not call that manager release as routine screen/rotation cleanup if the next screen instance
must reuse the pending request or unused fill.
Call `ApNativeAd.destroy()` when disposing an ad obtained through the low-level polling API.

The old `NativeAdPreload` entry points delegate to this same store. `preloadWithKey()` now skips
when covered, just like `preloadWithKeyIfEmpty()`; their Boolean return meanings remain distinct
(started versus covered). Legacy `buffer`/`preloadBuffer` values no longer request batches: capacity
is one unused native per placement. `preloadOnResume` no longer selects a separate network path.
`preloadAfterShow` remains an optional request for the next unused ad; it does not replace the
current ad until another show/refresh trigger.

## Optional integrations

| Need | Add or configure |
|---|---|
| Remote placements / Firebase analytics | [suite-firebase](../suite-firebase/README.md); install `FirebaseAdConfigSource`, then call `AdConfig.refresh()` from a custom splash. The supplied onboarding splash already refreshes. |
| Adjust attribution/revenue | Set `ERainAdConfig.adjustConfig` before init; see [AdjustConfig](src/main/java/com/ads/module/config/AdjustConfig.java). Leave it unset to keep Adjust off. UA-gated placements require attribution. |
| Premium users without ads | Follow [PayKit](../paykit/README.md) for a prebuilt paywall; it initializes billing. For your own UI, follow [BillingKit](../billingkit/README.md). Complete that setup before ad requests. |
| Rewarded ads | Use `load`, `isReady` and `show`; grant only when `onClosed(earned)` has `earned=true`. See [RewardAdManager](src/main/java/com/ads/module/helper/reward/RewardAdManager.kt). |
| Automatic interstitial preload | Configure placements and start [InterstitialAutoBuffer](src/main/java/com/ads/module/helper/interstitial/InterstitialAutoBuffer.kt) from the first content screen after onboarding. It pauses in background and shares the manager cache and group gate. |
| App-open on return | Set `ERainAdConfig.idAdResume` before init; exclude splash/sensitive Activities with `AppOpenManager.disableAppResumeWithActivity`. See [AppOpenManager](src/main/java/com/ads/module/admob/AppOpenManager.java). |

## Resume and interstitial lifecycle migration

See the [SDK lifecycle contract and partner migration](../docs/ads-buffer-lifecycle.md).
Resume enablement no longer preloads: one eligible background stay schedules a load after two
seconds, and returning early cancels it. A ready ad is reused across returns; no post-show refill
or foreground fetch occurs. A late result is cached for the next return.

Only AutoBuffer's configured, non-reserved placements share the interstitial interval. Closing
a group ad or failing its final waterfall starts the interval; successful load does not. Splash
and after-onboarding placements outside the group do not read or update that gate. Start the
buffer at content entry, not in Application. `topUpNow()` no longer bypasses the gate. Managed
`loadAndShow()` calls are ready-only and proceed immediately when empty; explicit waiting for
non-managed placements remains available. Raw ERain/Admob calls have no placement group: use
`InterstitialAdManager` for managed ads and remove partner-side interval overrides/refills.

## Moving from the main / 5.0 setup

Dependencies, manifest entries and basic `load`/`show` calls keep the same shape. In the current
branch, helpers require consent authority and pause while its form is open; remove any timeout
fallback that grants permission. Keep preload separate from navigation; no new interstitial API
is required for migration. Banner `Reload` now requests an ordinary banner for collapsible
placements; explicit `Request` keeps collapsible.

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
