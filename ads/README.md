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

Repositories (JitPack + the three mediation hosts) belong in the root project — see the
[root README](../README.md).

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

`:ads` exports `trackkit`, the UMP library, Shimmer and `kotlinx-coroutines-android` with `api`; it
merges the `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK` and `AD_ID` permissions and the Adjust
referrer receiver. The three `<meta-data>` entries and the string resources behind them are yours —
see [What your app must provide](../README.md#what-your-app-must-provide).

## Setup

All of it in `Application.onCreate()`, in this order. Types live in `io.trackkit`,
`com.ads.module.config` (`AdRemoteConfig`, `AdConfig`, `ERainAdConfig`, `AdjustConfig`),
`com.ads.module.consent`, `com.ads.module.ads`, `com.ads.module.admob` and `io.suite.firebase`.

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

Use `ERainAdConfig.ENVIRONMENT_DEVELOP` on debug builds. `ERainAdConfig` carries the rest of the
init knobs — read its KDoc for the current set. Selling IAP? Call `Billing.install(this)` from
`:billingkit` before the first ad request; it plugs the premium signal into the ads gate.

## The ad config file

Ship `app/src/main/assets/ad_config.json` — the name is fixed. Without it every placement is
disabled silently: the SDK logs `No ad config found` and hands back a disabled `AdUnitConfig`. Root
is an object, each key a placement name you choose. Unknown keys are skipped; a missing key never
throws.

```json
{
  "inter_splash": { "id": "ca-app-pub-XXX/1200371069", "isEnable": true },
  "native_home":  { "id": "ca-app-pub-XXX/1611434606", "isEnable": true, "heightCTA": 45 }
}
```

`id` and `isEnable` are the two a placement cannot work without. The rest — the UA gate, CTA
styling, which native blocks render and in what order, an explicit id list — are optional and
documented field by field on `AdUnitConfig`; open it in the IDE for the current set and defaults.

**Waterfall by key name.** A placement's floors are separate keys: `<key>_high`, `<key>_high1` …
`<key>_high9`, then the bare `<key>` last. `tiersFor("<key>")` turns that ladder into request order,
dropping disabled floors, blanks and repeats. Those eleven keys are the ceiling; past that, list the
ids in one key's `ids` array.

**Remote refresh.** An installed source is read only when you ask. `AdConfig.refresh(timeoutMs)` is
a suspend function that fetches, parses and swaps the active document; nothing else applies a remote
one. Call it from the splash: `lifecycleScope.launch { AdConfig.refresh() }`.

**Debug pin.** A debuggable build reads `assets/ad_config_debug.json` first, falling back to
`ad_config.json`. Either way a debuggable build blocks remote overrides for the whole process, so a
debug run can never spend live ad units — reverse that with
`AdRemoteConfig.setAllowRemoteOverrideInDebug(true)`.

## Showing ads

Every format follows the same three steps: read the placement's config, build the format's config
object, hand it to the helper. Set `placement` — that string is what reports request/skip telemetry.

**Native** (`com.ads.module.helper.adnative`) — the helper owns the view after
`setNativeContentView`. The skeleton comes from the ad layout unless you pass
`setShimmerLayoutView(view)` / `setShimmerLayout(layoutRes)`.

```kotlin
val config = AdRemoteConfig.getInstance().unit("native_home")      // never null
val tiers = AdRemoteConfig.getInstance().tiersFor("native_home")   // ids, highest floor first
val cfg = NativeAdConfig(tiers, config.isUsable, true, R.layout.native_home)
    .apply { forceUaCheck = config.enableUaCheck }

NativeAdHelper(activity, lifecycleOwner, cfg)
    .setNativeContentView(binding.frAds)
    .setNativeStyle(config.toNativeStyle())
    .also { it.placement = "native_home" }
    .requestAds(NativeAdParam.Request)
```

**Banner** (`com.ads.module.helper.banner`) — `attachInto(host)` resets an empty `FrameLayout` into
the module's banner slot, so `banner_container` / `shimmer_container_banner` are never yours to
declare. `BannerType` picks the AdMob request: `Normal`, `LargeAnchored`, `Collapsible(gravity)`,
`Inline(style)`, `InlineMaxHeight(maxHeightDp)` and `Fixed(size)` cover the AdMob banner families —
each variant's KDoc states the exact request it makes.

```kotlin
val cfg = BannerAdConfig(tiers, config.isUsable, true, BannerType.Collapsible())
BannerAdHelper(activity, lifecycleOwner, cfg)
    .attachInto(binding.frBanner)
    .also { it.placement = "banner_home" }
    .requestAds(BannerAdParam.Request)
```

`Collapsible(gravity)` must match the slot's screen edge (`"top"` / `"bottom"`).

**Interstitial** (`com.ads.module.helper.interstitial`) — one buffered ad per placement,
single-use, main thread only. `onComplete()` fires once on every path: hang navigation there, not on
`onClosed()`.

```kotlin
InterstitialAdManager.load(context, "inter_back", config.waterfallIds,
    InterLoadOptions(config.isUsable, AdGate.passesUaGate(config.enableUaCheck)))
InterstitialAdManager.show(activity, "inter_back", object : InterShowCallback() {
    override fun onComplete() = goNextScreen()
})
```

A show the interval rule declines **keeps its buffer** — the ad is withheld, not spent. To branch
without consuming it, ask first with `InterstitialAdManager.canShow(context, placement)`, or read
`showSkipReason(...)` for why not.

**Rewarded** (`com.ads.module.helper.reward`) — `loadAndShow` runs gate → load → show in one call;
`onSuccess` fires only when the user earned the reward and the ad closed. `load` / `isReady` /
`show` buffer ahead instead.

```kotlin
RewardAdManager.loadAndShow(activity, "reward_example", config.waterfallIds,
    enabled = config.isEnable,
    onSuccess = Runnable { grantReward() }, onFailed = Runnable { showTryAgain() })
```

### When the next screen starts

`InterNextAction` decides *when* `onComplete` fires, and with it whether the next screen starts
under the ad or after it.

| Value | `onComplete` fires | Use it for |
|---|---|---|
| `AfterDismiss` | after the ad is gone | a destination that must not exist behind the ad — camera, audio, video, or one that opens another Activity on entry |
| `UnderAd` | on the same tick as `show()` | everything else: the screen inflates under the ad and is painted when it closes |

`AfterDismiss` is the SDK default. Set the app-wide default once from `Application.onCreate`, and
override per presentation where a placement needs the other one:

```kotlin
InterstitialAdManager.defaultNextAction = InterNextAction.UnderAd    // once, after ERainAd.init
InterstitialAdManager.show(activity, "inter_camera", callback,
    nextAction = InterNextAction.AfterDismiss)                       // this show only
```

`UnderAd` is the optimization and it is wrong in exactly one case: the destination issues a second
`startActivity` on entry. GMA's ad Activity lives in your own task, so that launch is stacked *on
top of* the ad and covers the impression. Two rules follow:

- Start the next screen from `onComplete` and nothing else. Calling `finish()` there under `UnderAd`
  tears the host out from under the ad and the module drops the impression.
- The ad's window is translucent while the creative animates in, so a destination started under it
  plays its entry transition in full view. Suppress that on the destination —
  `overridePendingTransition(0, 0)` right after starting it, or `Intent.FLAG_ACTIVITY_NO_ANIMATION`
  — rather than delaying the start, which would break the launch order the ad depends on.

### Keeping interstitials buffered

Opt-in. Keeps one ad buffered per placement on the frequency clock, so a screen no longer pays for
a load it may never spend.

```kotlin
InterstitialAutoBuffer.configure(InterstitialBufferOptions(listOf("inter_all", "inter_back")))
InterstitialAutoBuffer.start(this)   // after AdRemoteConfig.initializeFromAssets and ERainAd.init
```

It buys when the interval expires, not when an ad is shown. It shares one store with explicit `load`
calls and never doubles them, takes its ids from `AdRemoteConfig.tiersFor` so a new `_high` floor
needs no code change, waits for the UMP answer, skips premium users and pauses a placement that will
not fill. `InterstitialBufferOptions` carries the timing knobs; `InterstitialAutoBuffer.reserve(...)`
marks placements it must never touch.

## Native ad layout contract

Your native layout's root must be `com.google.android.gms.ads.nativead.NativeAdView`. The SDK binds
by id; a missing id means that asset is not shown. `@id/ad_media` and `@id/ad_headline` are
mandatory — AdMob policy. Optional: `@id/ad_body`, `@id/ad_call_to_action`, `@id/ad_app_icon`,
`@id/ad_price`, `@id/ad_stars`, `@id/ad_advertiser`. The module declares every id; your layout only
references them.

To let the config file reorder blocks, add a vertical `LinearLayout` `@id/ad_container` holding
`@id/block_icon_headline`, `@id/ad_body`, `@id/ad_media` and `@id/ad_call_to_action`. Without
`ad_container` the SDK toggles visibility in place and keeps the layout's own order.

## Consent (UMP)

```kotlin
ConsentCenter.configure(ConsentOptions(timeoutMs = 20_000, testDeviceHashedId = "…")) // onCreate
ConsentCenter.request(this, screen = "splash") { mayRequestAds ->                     // splash
    if (mayRequestAds) requestSplashAds() else goNext()
}
ConsentCenter.detach(this)   // onDestroy of every Activity that called request()
```

`mayRequestAds = false` means "do not request yet" — form unanswered, or no network — not "the user
refused". A refusal completes the step and ads still run non-personalized, with
`ConsentCenter.canPersonalize()` carrying that verdict into the request extras. Hang restart logic on
`onFormAnswered`, never `onCompleted`. Skipping `detach` leaks the Activity for the timeout window.

Using `:onboardkitorigin`? `ObSplashActivity` runs this whole flow for you — do not call `request()`
yourself.

## The pre-request gate

`AdGate.passesUaGate(forceUaCheck)` is the single organic check. Feed it `AdUnitConfig.enableUaCheck`
(`enable_ua_check` in the config file), never a hard-coded boolean. The view helpers do it once you
set `forceUaCheck` on `NativeAdConfig` / `BannerAdConfig`; the managers take it as
`InterLoadOptions.passesUaGate`. Writing your own loader? `AdGate.skipReason(...)` is the whole gate
in one call. The organic flag comes from Adjust's attribution callback and reads `true` until
attribution arrives, so a force-organic placement is hidden in session one — and means nothing with
Adjust off.

## Remote config keys

Your app reads these and passes the values in; `:ads` does not fetch them itself.

| Key | Apply with |
|---|---|
| `ad_remote_config` | The whole `ad_config.json` document, read by `FirebaseAdConfigSource` (`:suite-firebase`) |
| `interstitial_interval_sec` | `ERainAd.getInstance().setIntervalInterstitialAd(sec)` — `0` = off |
| `max_click_ads_per_day` | `ERainAd.getInstance().setMaxClickAdsPerDay(n)` — `0` = off |

## ProGuard

`ads/consumer-rules.pro` applies to your build automatically — nothing to copy. It keeps the public
API of `com.ads.module.{ads,helper,config,consent}` and `AdsMultiDexApplication`, the Adjust /
advertising-id / install-referrer reflection targets, and the Pangle and Mintegral SDKs. AppLovin,
Vungle, Unity Ads, ironSource and the Facebook SDK ship their own rules; R8 full mode can undercut
them, so add `-dontwarn` where it reports.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| No ads anywhere, log `No ad config found` | `assets/ad_config.json` missing | Ship the file under that exact name |
| Log `Ad unit '<key>' not found`, one dark slot | Key absent from the config | Add the key; the SDK disables the slot, never crashes |
| Crash in `Application.onCreate` from `MobileAds` | No `APPLICATION_ID` meta-data | Add the meta-data and `app_id` placeholder for every build type |
| `FacebookException` on cold start | `ApplicationId` / `ClientToken` meta-data missing | Add both, backed by real string resources |
| Paid events report placement `unknown` | `initializeFromAssets` ran after `ERainAd.init` | Move it to step 2 of Setup |
| Remote document never applies | Nothing calls `AdConfig.refresh()` | Call it from the splash, after installing a source |
| Remote config never applies on a debug build | Debuggable build — remote overrides are pinned | `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` |
| Debug device outside the EEA never sees the UMP form | No `testDeviceHashedId` | Set it from the id UMP logs on the first run |

## License

MIT — see [LICENSE](../LICENSE).
