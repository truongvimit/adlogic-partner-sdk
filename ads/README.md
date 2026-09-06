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
def sdkVersion = '<tag>'
android {
    defaultConfig {
        manifestPlaceholders = [app_id: 'YOUR_ADMOB_APP_ID'] // ca-app-pub-...~...
    }
}
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
}
```

The current 5.1.0 work is not published; choose an available
[repository tag](https://github.com/truongvimit/adlogic-partner-sdk/tags).

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

## Optional integrations

| Need | Add or configure |
|---|---|
| Remote placements / Firebase analytics | [suite-firebase](../suite-firebase/README.md); install `FirebaseAdConfigSource`, then call `AdConfig.refresh()` from a custom splash. The supplied onboarding splash already refreshes. |
| Adjust attribution/revenue | Set `ERainAdConfig.adjustConfig` before init; see [AdjustConfig](src/main/java/com/ads/module/config/AdjustConfig.java). Leave it unset to keep Adjust off. UA-gated placements require attribution. |
| Premium users without ads | Follow [PayKit](../paykit/README.md) for a prebuilt paywall; it initializes billing. For your own UI, follow [BillingKit](../billingkit/README.md). Complete that setup before ad requests. |
| Rewarded ads | Use `load`, `isReady` and `show`; grant only when `onClosed(earned)` has `earned=true`. See [RewardAdManager](src/main/java/com/ads/module/helper/reward/RewardAdManager.kt). |
| Automatic interstitial preload | Configure placements and start [InterstitialAutoBuffer](src/main/java/com/ads/module/helper/interstitial/InterstitialAutoBuffer.kt) after initialization. It shares the same cache. |
| App-open on return | Set `ERainAdConfig.idAdResume` before init; exclude splash/sensitive Activities with `AppOpenManager.disableAppResumeWithActivity`. See [AppOpenManager](src/main/java/com/ads/module/admob/AppOpenManager.java). |

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
