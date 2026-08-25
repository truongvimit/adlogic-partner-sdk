# ads

> AdMob loading, showing, gating and UMP consent for one Android app.

`:ads` owns the ad mechanism: waterfall requests, per-placement caching, the pre-request gate,
native/banner view lifecycle and the UMP consent flow. Your app ships ad unit ids in a JSON file
and asks for a placement by name.

## Requirements

| | |
|---|---|
| minSdk / compileSdk / targetSdk | 24 / 36 / 36 |
| Google Mobile Ads | `play-services-ads:25.3.0`, bundled |
| AdMob app id | required — `manifestPlaceholders[app_id]` |
| Meta app id + client token | required — `ERainAd.init` initializes `FacebookSdk` unconditionally |

## Installation

Repositories (JitPack + the three mediation hosts) belong in the root project — see the [root README](../README.md).

```groovy
// app/build.gradle
android {
    buildTypes {
        debug   { manifestPlaceholders = [app_id: "ca-app-pub-XXXXXXXX~YYYYYYYY"] }
        release { manifestPlaceholders = [app_id: "ca-app-pub-XXXXXXXX~YYYYYYYY"] }
    }
}
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    // only for the remote ad-config source (FirebaseAdConfigSource)
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

`:ads` exports `trackkit`, the UMP library, Shimmer and `kotlinx-coroutines-android` with `api`; it merges
the `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK` and `AD_ID` permissions, the Adjust referrer
receiver and the two GMA optimization flags. These three `<meta-data>` entries are yours:

```xml
<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="${app_id}" />
<meta-data android:name="com.facebook.sdk.ApplicationId"  android:value="@string/facebook_app_id" />
<meta-data android:name="com.facebook.sdk.ClientToken"    android:value="@string/facebook_client_token" />
```

`facebook_app_id` and `facebook_client_token` are mandatory string resources. `adjust_token`, `event_token`
and `adjust_event_token_purchase` are read only by your `AdjustConfig` code; a blank `adjust_token` turns Adjust off.

## Quick start

All of it in `Application.onCreate()`, in this order. Types live in `io.trackkit`, `com.ads.module.config`
(`AdRemoteConfig`, `AdConfig`, `ERainAdConfig`, `AdjustConfig`), `com.ads.module.consent`, `com.ads.module.ads`,
`com.ads.module.admob` and `io.suite.firebase` (`FirebaseAdConfigSource`).

```kotlin
override fun onCreate() {
    super.onCreate()

    // 1. First: every ad event :ads emits is only buffered until this runs.
    Tracker.install(this, TrackerConfig(appVersionCode = BuildConfig.VERSION_CODE.toLong()))
    AdRemoteConfig.initializeFromAssets(this)   // 2. assets/ad_config.json + placement binding
    AdConfig.install(FirebaseAdConfigSource())  // 3. optional; the splash calls AdConfig.refresh()
    ConsentCenter.configure(ConsentOptions(timeoutMs = 20_000, testDeviceHashedId = "…"))  // 4.

    val adConfig = ERainAdConfig(this, ERainAdConfig.ENVIRONMENT_PRODUCTION).apply {  // 5.
        adjustConfig = AdjustConfig(true, getString(R.string.adjust_token)).apply {
            eventAdImpression = getString(R.string.event_token)
            eventNamePurchase = getString(R.string.adjust_event_token_purchase)
            fbAppId = getString(R.string.facebook_app_id)
        }
        facebookClientToken = getString(R.string.facebook_client_token)
        idAdResume = ""                 // blank turns app-resume off
        intervalInterstitialAd = 0      // 0 = no interval; let remote config own the rule
        listDeviceTest = listOf("1E25A7D66221E2116062EA114AFE2982")
    }
    // 6. Adjust, MobileAds.initialize, AppOpenManager, FacebookSdk. Never call MobileAds.initialize yourself.
    ERainAd.getInstance().init(this, adConfig)

    // Screens the app-open ad must never cover.
    AppOpenManager.getInstance().disableAppResumeWithActivity(SplashActivity::class.java)
}
```

| Step | Must precede | Because |
|---|---|---|
| 1 | everything below | ad events emitted earlier are only buffered |
| 2 | 6 | it binds ad unit ids to placements; paid events report `unknown` otherwise |
| 4 | the first `ConsentCenter.request()` | `request()` reads the options set here |

Use `ERainAdConfig.ENVIRONMENT_DEVELOP` on debug builds. Selling IAP? Call `Billing.install(this)`
from `:billingkit` before the first ad request — it plugs the premium signal into the ads gate.

## Ad config file

Ship `app/src/main/assets/ad_config.json` — the name is fixed. Without it every placement is disabled
silently: the SDK logs `No ad config found` and hands back `AdUnitConfig(id = "", isEnable = false)`. Root
is an object, each key a placement name you choose. Unknown keys are skipped; no key makes the parser throw.

| Key | Type | Default | What it does |
|---|---|---|---|
| `id` | String | `""` | The ad unit id. An unquoted number is accepted. |
| `isEnable` | Boolean | `false` | Placement on/off. The string `"true"` is accepted. |
| `enable_ua_check` | Boolean | `false` | Routes this placement through the UA/organic gate. |
| `reloadIntervalSeconds` | Int | `null` | Exposed on `AdUnitConfig`; your app decides what to do with it. |
| `colorCTA` | String | `"default"` | CTA background color. `"default"`, blank or unparseable = no override. |
| `heightCTA` | Int or `"default"` | `40` | CTA height in dp, clamped to 36..52 by `toNativeStyle()`. |
| `positionCTA` | String? | `null` | `"TOP"` / `"BOTTOM"`, uppercased. Set it only for a placement whose screen ships one layout per position — the onboarding flow. `null` hands the order to `components`. |
| `components` | String[] | `["icon_headline","body","media","cta"]` | Native blocks to show, in order. Empty array = the default. |
| `ids` | String[] | `[]` | Explicit waterfall, highest floor first; `id` is appended last. A bare string is accepted. |

```json
{ "inter_splash": { "id": "ca-app-pub-XXX/1200371069", "isEnable": true, "enable_ua_check": false },
  "native_home":  { "id": "ca-app-pub-XXX/1611434606", "isEnable": true, "heightCTA": 45 } }
```

**Who orders the blocks.** A block missing from `components` is always hidden. Who decides the *order*
depends on `positionCTA`:

| `positionCTA` | Order comes from | `components` does |
|---|---|---|
| `null` | `components`, top to bottom | order **and** visibility |
| `"TOP"` / `"BOTTOM"` | the layout the screen picks for that position | visibility only |

Reordering also needs the layout's four blocks — `block_icon_headline`, `ad_body`, `ad_media`,
`ad_call_to_action` — as direct children of a vertical `LinearLayout` with `android:id="@id/ad_container"`.
A layout without `ad_container` keeps its own order and only gets show/hide.

The onboarding flow is the one place that names a position: it ships `ob_layout_native_cta_top` and
`ob_layout_native_cta_bottom`, built for their CTA position, and picks between them from `positionCTA`.
Every other placement leaves it `null`.

**Waterfall by key name.** A placement's floors are separate keys: `<key>_high`, `<key>_high1` … `<key>_high9`,
then the bare `<key>` last. `tiersFor("<key>")` turns that ladder into request order, dropping disabled floors,
blanks and repeats. Those eleven keys are the ceiling; past that, list the ids in one key's `ids` array.

**Remote refresh.** An installed source is read only when you ask. `AdConfig.refresh(timeoutMs = 10_000)` is a
suspend function that fetches, parses and swaps the active document, returning true when it did; nothing else
applies a remote document. Call it from the splash: `lifecycleScope.launch { AdConfig.refresh() }`.

**Debug pin.** A debuggable build reads `assets/ad_config_debug.json` first, falling back to `ad_config.json`
when absent. Either way a debuggable build blocks remote overrides for the whole process, so a debug run can
never spend live ad units — reverse that with `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)`.

## Loading and showing ads

**Native** (`com.ads.module.helper.adnative`) — the helper owns the view after `setNativeContentView`. The
skeleton comes from the ad layout unless you pass `setShimmerLayoutView(view)` / `setShimmerLayout(layoutRes)`
or set `autoShimmer = false`.

```kotlin
val config = AdRemoteConfig.getInstance().unit("native_home")      // never null
val tiers = AdRemoteConfig.getInstance().tiersFor("native_home")   // ids, highest floor first
val cfg = NativeAdConfig(tiers, config.isUsable, true, R.layout.native_home)
    .apply { forceUaCheck = config.enableUaCheck }

NativeAdHelper(activity, lifecycleOwner, cfg)
    .setNativeContentView(binding.frAds)
    .setNativeStyle(config.toNativeStyle())
    .also { it.placement = "native_home" }   // set it: this is what reports request/skip telemetry
    .requestAds(NativeAdParam.Request)
```

**Banner** (`com.ads.module.helper.banner`) — `attachInto(host)` resets an empty `FrameLayout` into the module's
banner slot, so `banner_container` / `shimmer_container_banner` are never yours to declare. `BannerType` is
`Normal`, `Collapsible(gravity)` or `Inline(style)`.

```kotlin
val cfg = BannerAdConfig(tiers, config.isUsable, true, BannerType.Collapsible())
BannerAdHelper(activity, lifecycleOwner, cfg)
    .attachInto(binding.frBanner)
    .also { it.placement = "banner_home" }
    .requestAds(BannerAdParam.Request)
```

**Interstitial** (`com.ads.module.helper.interstitial`) — one buffered ad per placement, single-use,
main thread only. `onComplete()` fires once on every path: hang navigation there, not on `onClosed()`.

```kotlin
InterstitialAdManager.load(context, "inter_back", config.waterfallIds,
    InterLoadOptions(config.isUsable, AdGate.passesUaGate(config.enableUaCheck)))
InterstitialAdManager.show(activity, "inter_back", object : InterShowCallback() {
    override fun onComplete() = goNextScreen()
})
```

`onComplete` fires once either way; `InterNextAction` decides *when*, and with it whether the next
screen starts under the ad or after it:

| Value | `onComplete` fires | Use it for |
|---|---|---|
| `AfterDismiss` | after the ad is gone, just after `onClosed` | a destination that must not exist behind the ad — camera, audio, video |
| `UnderAd` | on the same tick as `show()`, before `onClosed` | everything else: the screen inflates and binds under the ad and is painted when it closes |

`AfterDismiss` is the SDK default and matches Apero's `openActivityAfterShowInterAds = false`;
`UnderAd` is `= true`. Set the app-wide default once, from `Application.onCreate`, and override it
per presentation where a placement needs the other one:

```kotlin
InterstitialAdManager.defaultNextAction = InterNextAction.UnderAd    // once, after ERainAd.init
InterstitialAdManager.show(activity, "inter_camera", callback,
    nextAction = InterNextAction.AfterDismiss)                       // this show only
```

`ERainTuning.install()` sets the default to `UnderAd`, so an app using `:onboardkitorigin` already
has it; the onboarding flow pins the *mode* per show regardless, so changing the default never
changes what an onboarding callback means. Start the next screen from `onComplete` and nothing else — calling `finish()` there under `UnderAd` tears the
host out from under the ad and the module drops the impression.

**Why `UnderAd` fires before `show()` and not after.** The module holds a loading dialog for 800 ms,
then calls `onNextAction` and `show()` on the same tick. Both Activities are queued together, so the
ad lands on top of the next screen. Delaying the hand-back is not an option: GMA's `AdActivity` is
declared with no `taskAffinity` and the default launch mode, so it lives in the host's own task —
a `startActivity` issued once it is on top is stacked *above* the ad, covering the impression, and
GMA then reports the dismissal the caller was waiting for.

`AdActivity` is also `@android:style/Theme.Translucent`, so until the creative has animated in the
user is looking through it at the next screen playing its entry transition. Suppress that transition
— `overridePendingTransition(0, 0)` right after starting it, or `Intent.FLAG_ACTIVITY_NO_ANIMATION`
— rather than delaying the start; only the first leaves the launch order intact.

A show the interval rule declines **keeps its buffer** — the ad is withheld, not spent, and the next
eligible tap uses it. Ask before showing when you want to branch without consuming:

```kotlin
if (InterstitialAdManager.canShow(context, "inter_back")) showLoadingDialog()
// or, for the reason: InterstitialAdManager.showSkipReason(context, "inter_back")
```

### Interstitial auto-buffer

Opt-in. Keeps one ad buffered per placement on the frequency clock, so a screen no longer pays for
a load it may never spend, and a placement the user reaches by an unexpected route is still filled.

```kotlin
InterstitialAutoBuffer.configure(InterstitialBufferOptions(listOf("inter_all", "inter_back")))
InterstitialAutoBuffer.start(this)   // after AdRemoteConfig.initializeFromAssets and ERainAd.init
```

| Key | Type | Default | What it does |
|---|---|---|---|
| `placements` | `List<String>` | `[]` | Placement keys to keep buffered. Empty = the buffer does nothing |
| `tickMs` | `Long` | `0` | Check period. `0` follows `intervalInterstitialAd` |
| `idleTickMs` | `Long` | `30_000` | Period used when the interval rule is off |
| `minTickMs` | `Long` | `5_000` | Floor on the period |
| `backoffMs` | `Long` | `30_000` | First wait after a placement fails to fill; doubles |
| `maxBackoffMs` | `Long` | `300_000` | Ceiling on that wait |

It buys **when the interval expires, not when an ad is shown** — an ad bought at the moment of an
impression would sit idle for a whole interval and age against the buffer's own expiry. Show at
t=0 with a 30s interval and the replacement is requested at t=30, so a tap at t=31 shows and a tap
at t=30 does not. Want it sooner? That is what `InterstitialAdManager.load` is for.

It shares one store with explicit `load` calls and never doubles them: a load a screen starts itself
runs normally *even mid-interval*, a tick that finds it still in flight starts nothing, and the ad it
produces *is* the buffer. Ad unit ids come from `AdRemoteConfig.tiersFor`, so adding a `_high` floor
needs no code change. It waits for the UMP answer, skips premium users, and pauses a placement that
will not fill.

`InterstitialAutoBuffer.reserve(...)` marks placements it must never touch; `:onboardkitorigin`
reserves its own splash and question interstitials, which the flow reuses on its own schedule.

**Rewarded** (`com.ads.module.helper.reward`) — `loadAndShow` runs gate → load → show in one call; `onSuccess`
fires only when the user earned the reward and the ad closed. `load` / `isReady` / `show` buffer ahead instead.

```kotlin
RewardAdManager.loadAndShow(activity, "reward_example", config.waterfallIds,
    enabled = config.isEnable,
    onSuccess = Runnable { grantReward() }, onFailed = Runnable { showTryAgain() })
```

## Native ad layout contract

Your native layout's root must be `com.google.android.gms.ads.nativead.NativeAdView`. The SDK binds by id; a
missing id means that asset is not shown. `@id/ad_media` and `@id/ad_headline` are mandatory — AdMob policy.
Optional: `@id/ad_body`, `@id/ad_call_to_action`, `@id/ad_app_icon`, `@id/ad_price`, `@id/ad_stars`,
`@id/ad_advertiser`. The module declares every id; your layout only references them.

For `components` to reorder blocks, leave `positionCTA` unset and add a vertical `LinearLayout`
`@id/ad_container` holding
`@id/block_icon_headline`, `@id/ad_body`, `@id/ad_media` and `@id/ad_call_to_action`. Without `ad_container`
the SDK toggles visibility in place, and if `block_icon_headline` is missing too it falls back to toggling
`ad_app_icon` / `ad_headline` / `ad_advertiser` individually. With `ad_container` present but no
`block_icon_headline`, those three views are left untouched.

## Consent (UMP)

```kotlin
ConsentCenter.configure(ConsentOptions(timeoutMs = 20_000, testDeviceHashedId = "…")) // onCreate
ConsentCenter.request(this, screen = "splash") { mayRequestAds ->                     // splash
    if (mayRequestAds) requestSplashAds() else goNext()
}
ConsentCenter.detach(this)   // onDestroy of every Activity that called request()
```

`mayRequestAds = false` means "do not request yet" — form unanswered, or no network — not "the user refused".
A refusal completes the step and ads still run non-personalized, with `ConsentCenter.canPersonalize()` carrying
that verdict into the request extras. Hang restart logic on `onFormAnswered`, never `onCompleted`. Skipping
`detach` leaks the Activity for the timeout window.

## UA / organic gate

`AdGate.passesUaGate(forceUaCheck)` is the single organic check. Feed it `AdUnitConfig.enableUaCheck`
(`enable_ua_check` in the config file), never a hard-coded boolean. The view helpers do it once you set
`forceUaCheck` on `NativeAdConfig` / `BannerAdConfig`; the managers take it as `InterLoadOptions.passesUaGate`.
Writing your own loader? `AdGate.skipReason(...)` is the whole pre-request gate (`disabled_config` →
`purchased` → `offline` → `ua_gate`). The organic flag comes from Adjust's attribution callback and reads `true`
until attribution arrives, so a force-organic placement is hidden in session one — and means nothing with Adjust off.

## Remote config keys

Your app reads these and passes the values in; `:ads` does not fetch them itself.

| Key | Type | Default | What it does |
|---|---|---|---|
| `ad_remote_config` | String | — | The whole `ad_config.json` document, read by `FirebaseAdConfigSource` (`:suite-firebase`); the key name is a constructor argument |
| `interstitial_interval_sec` | Long | `0` | Seconds between interstitials, `0` = off. Apply with `ERainAd.getInstance().setIntervalInterstitialAd(sec)` |
| `max_click_ads_per_day` | Long | `0` | Clicks per ad unit per 24h before interstitials from that unit stop loading and showing, `0` = off. Clicks on every format count. Apply with `ERainAd.getInstance().setMaxClickAdsPerDay(n)` |
| `on_show_dialog_consent` | Boolean | `true` | App-side switch for a second-chance consent prompt outside splash |

## ProGuard

`ads/consumer-rules.pro` applies to your build automatically — nothing to copy. It keeps the public API of
`com.ads.module.{ads,helper,config,consent}` and `AdsMultiDexApplication`, the Adjust / advertising-id /
install-referrer reflection targets, and the Pangle and Mintegral SDKs. AppLovin, Vungle, Unity Ads, ironSource
and the Facebook SDK ship their own rules; R8 full mode can undercut them, so add `-dontwarn` where it reports.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| No ads anywhere, log `No ad config found` | `assets/ad_config.json` missing | Ship the file under that exact name |
| Log `Ad unit '<key>' not found`, one dark slot | Key absent from the config | Add the key; the SDK disables the slot, never crashes |
| Crash in `Application.onCreate` from `MobileAds` | No `APPLICATION_ID` meta-data | Add the meta-data and `app_id` placeholder for every build type |
| `FacebookException` on cold start | `ApplicationId` / `ClientToken` meta-data missing | Add both, backed by real string resources |
| Paid events report placement `unknown` | `initializeFromAssets` ran after `ERainAd.init` | Move it to step 2 of Quick start |
| Remote document never applies | Nothing calls `AdConfig.refresh()` | Call it from the splash, after installing a source |
| Remote config never applies on a debug build | Debuggable build — remote overrides are pinned | `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` |
| Debug device outside the EEA never sees the UMP form | No `testDeviceHashedId` | Set it from the id UMP logs on the first run |

## License

MIT — see [LICENSE](../LICENSE).
