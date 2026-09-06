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
    // 6. Initializes Adjust, MobileAds, AppOpenManager and FacebookSdk.
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

Use `ERainAdConfig.ENVIRONMENT_DEVELOP` on debug builds. `ERainAdConfig` documents the remaining
initialization options. For IAP, initialize `AppPurchase` and then call
`com.ads.module.billing.Billing.install(this)` from `:billingkit` before the first ad request;
it connects the premium signal to the ads gate. See [billingkit setup](../billingkit/README.md).

## The ad config file

Ship `app/src/main/assets/ad_config.json`, or install a document with
`AdRemoteConfig.initializeFromJson(json)`. With no document loaded, placement lookups return a
disabled `AdUnitConfig`. The JSON root is an object; each key is a placement name you choose.
A missing placement also returns a disabled config.

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

**Remote refresh.** `AdConfig.refresh(timeoutMs)` is a suspend function that fetches and applies
the document from the installed source. `ObSplashActivity` calls it during splash; for a custom
splash, call `lifecycleScope.launch { AdConfig.refresh() }` after installing the source.

**Debug config.** A debuggable build reads `assets/ad_config_debug.json` first, falling back to
`ad_config.json`. A successfully loaded asset document blocks remote refresh overrides by default;
`AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` permits them. This does not replace the ids
inside either file with test ids. Supply test ad units in the debug document or configure the
device for testing. Explicit `initializeFromJson(json)` calls are not blocked by this setting.

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
val cfg = BannerAdConfig(tiers, config.isUsable, false, BannerType.Collapsible())
BannerAdHelper(activity, lifecycleOwner, cfg)
    .attachInto(binding.frBanner)
    .also { it.placement = "banner_home" }
    .requestAds(BannerAdParam.Request)
```

`Collapsible(gravity)` must match the slot's screen edge (`"top"` / `"bottom"`).
The example leaves refresh to AdMob: `canReloadAds=false` and the default
`enableAutoReload=false`. To use SDK resume/timer reload instead, disable console refresh for
every tier and opt in through `BannerAdConfig`. `BannerAdParam.Reload` requests an ordinary
anchored banner; an explicit `Request` keeps the configured type, including collapsible.

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

The interval gate keeps its buffer when it declines a show. To check eligibility, use
`InterstitialAdManager.canShow(context, placement)` or `showSkipReason(...)`.
A lifecycle rejection before vendor show returns `SHOW_IN_BACKGROUND` and restores a still-valid
fill unless it was released or replaced; retry only at a new foreground trigger.

For a trigger that may wait for a fill, use `InterstitialAdManager.loadAndShow(...)` with
`InterLoadAndShowOptions`. Its default 8-second timeout bounds the caller's wait for loading,
not show preparation or the time the ad remains visible.

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
| `UnderAd` | on the same tick as `show()` | a destination that can prepare behind the ad without opening another Activity |

`AfterDismiss` is the SDK default. Set the app-wide default once from `Application.onCreate`, and
override per presentation where a placement needs the other one:

```kotlin
InterstitialAdManager.defaultNextAction = InterNextAction.UnderAd    // once, after ERainAd.init
InterstitialAdManager.show(activity, "inter_camera", callback,
    nextAction = InterNextAction.AfterDismiss)                       // this show only
```

Start the next screen from `onComplete`. With `UnderAd`, that callback runs before dismissal:
keep the showing host alive, and avoid launching another Activity from the destination's startup.
Use `AfterDismiss` when the destination needs exclusive screen access. Check destination transitions
with the ad formats and mediation adapters your app uses.

### Keeping interstitials buffered

Opt-in. Preloads one ad per placement according to its interval settings.

```kotlin
InterstitialAutoBuffer.configure(InterstitialBufferOptions(listOf("inter_all", "inter_back")))
InterstitialAutoBuffer.start(this)   // after AdRemoteConfig.initializeFromAssets and ERainAd.init
```

It shares the interstitial store with explicit `load` calls and reads ids through
`AdRemoteConfig.tiersFor`. Requests remain subject to consent, premium and placement gates.
`InterstitialBufferOptions` documents the interval and retry settings;
`InterstitialAutoBuffer.reserve(...)` excludes placements from automatic buffering.

## Native ad layout contract

Your native layout's root must be `com.google.android.gms.ads.nativead.NativeAdView`. The SDK binds
assets by id: `@id/ad_media`, `@id/ad_headline`, `@id/ad_body`, `@id/ad_call_to_action`,
`@id/ad_app_icon`, `@id/ad_price`, `@id/ad_stars` and `@id/ad_advertiser`.
A missing id leaves that asset unbound. The module declares these ids; your layout references them.
Use the supplied layouts as examples for the assets your chosen ad format requires.

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

`mayRequestAds` reports request authorization from UMP or an explicit host-managed decision.
When false, do not request ads. A timeout, network error or personalization choice does not grant
authorization. `ConsentCenter.canPersonalize()` is separate: when requests are authorized but
personalization is not, the SDK applies non-personalized request extras.

Use `onCompleted` for the request's completion and `onFormAnswered` only when you need to observe
an answer to a form shown by that request. Call `detach` from the requesting Activity's `onDestroy`
to release its pending callbacks. Helpers also block requests while a consent form is showing.

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
advertising-id / install-referrer reflection targets, and the Pangle and Mintegral SDKs. Mediation
dependencies also supply consumer rules. For intentionally excluded adapters, follow the exclusion
instructions in the [root README](../README.md).

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
