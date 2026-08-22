**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# Ads Integration Guide — Base Project (EN)

> Module guides: **[trackkit](trackkit/README.md)** · **[OnboardKit](onboardkitorigin/README.md)** · **[PayKit](paykit/README.md)** · **[BillingKit](billingkit/README.md)**

This document is the **mandatory reference standard** for partner teams integrating ads into Infinity products. Any ad-related change must follow the architecture, load/show flow, and gating rules defined in this base project.

---

## Consuming the SDKs via JitPack

Eight modules are published as libraries:

| Module | Role |
|---|---|
| `ads` | Ad loading and showing; premium gating through the `Entitlement` port |
| `billingkit` | The Play billing engine — same `com.ads.module.billing` classes as before the split |
| `onboardkitorigin` | First-open flow: splash, language, onboarding |
| `paykit` | Paywall UI over the billing engine in `billingkit` |
| `paykit-firebase` | Firebase Remote Config source for `paykit` |
| `trackkit` | Vendor-free analytics contract (`Tracker`, `TrackSink`, taxonomy) |
| `trackkit-firebase` | Firebase/GA4 sink for `trackkit` |
| `adtracer` | Debug-only ad lifecycle dashboard |

Declare only what your app monetizes with — the four partner scenarios:

| # | Partner | Declares | What the APK provably lacks |
|---|---|---|---|
| 1 | Ads only, no IAP | `ads` (+ `trackkit-firebase`) | No Play Billing class at all |
| 2 | IAP + prebuilt paywall, no ads | `billingkit` + `paykit` | No GMA/AdMob class at all |
| 3 | IAP with your own paywall UI | `billingkit` | Neither `paykit` nor `ads` |
| 4 | Ads and IAP | `ads` + `billingkit` (+ `paykit`) | — premium gating works as before |

Add the JitPack repository:

```groovy
// settings.gradle (dependencyResolutionManagement) or root build.gradle
repositories {
    google()
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
```

Then declare the dependencies:

```groovy
// Replace <tag> with a tag from https://github.com/truongvimit/adlogic-partner-sdk/tags
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"

    // Billing engine. Only if the app sells IAP/subscriptions — see the scenario table above.
    implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"

    // Paywall. Add paykit-firebase only if the paywall document comes from Remote Config.
    implementation "com.github.truongvimit.adlogic-partner-sdk:paykit:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:paykit-firebase:$sdkVersion"

    // Pick the sinks you actually want. Without a sink, Tracker validates and drops events.
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit-firebase:$sdkVersion"

    // adtracer ships in debug builds only — keep it out of release
    debugImplementation "com.github.truongvimit.adlogic-partner-sdk:adtracer:$sdkVersion"
}
```

Keep every module on the same tag. They are published together from one repository and are not
tested against each other across versions. Note the double quotes: Groovy only interpolates
`$sdkVersion` in double-quoted strings.

You do not need to declare `trackkit` yourself: `ads`, `onboardkitorigin` and `paykit` expose it as
an `api` dependency, and `trackkit-firebase` pulls it in too. Declare it explicitly only if you write
your own `TrackSink` in a module that depends on none of them.

Note the group id: because this repository publishes several modules, JitPack namespaces them as
`com.github.<user>.<repo>` rather than `com.github.<user>`. The repository name is part of the group,
and JitPack derives it from the GitHub URL — it is not something `build.gradle` or `jitpack.yml` can
override. Flattening it to `com.github.truongvimit:ads` would require one repository per module.

Any tag pushed to this repository is resolvable; a commit hash or `main-SNAPSHOT` also works.

> **Note:** `onboardkitorigin` depends on `ads`, and `paykit` on `billingkit`, at runtime scope only — their `com.ads.module.*` classes are not on your compile classpath through either of them. Declare `ads` / `billingkit` explicitly, as shown above, if you call those APIs directly.

Requires JDK 17 and `minSdk` 24 or higher.

### Migrating: the billing engine moved to `billingkit`

The Play billing engine left `ads`. If your app sells IAP, add exactly one Gradle line —
`implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"` — and change
nothing else: every `com.ads.module.billing.*` class kept its name, package and behavior. Ads-only
apps do nothing and stop shipping the Play Billing library. Details: **[MIGRATION.md](MIGRATION.md)**.

### Migrating to 2.0.0

- The eleven `ERainAd.getShouldDisplay*` methods are **removed**. Replace every call with
  `AdGate.passesUaGate(config.enableUaCheck)` — or `ERainAd.getInstance().shouldDisplayForUa(...)`,
  the same check without placement-named wrappers. Adding a placement no longer needs an SDK release.
- New package `com.ads.module.helper`: `AdGate` (the one pre-load gate), placement-keyed
  `InterstitialAdManager` / `RewardAdManager` stores, `NativeAdPreload`, and view-level
  `NativeAdHelper` / `BannerAdHelper` — see §2.6.
- The waterfall now covers all four formats: `AdWaterfall.loadReward` joins native/interstitial,
  and `BannerAdHelper` walks banner floors with no view flicker between tiers.
- OnboardKit's `ERainAdProvider` is now a thin adapter over the same stores — behavior, placement
  keys, and telemetry are unchanged.

---

## Building the sample app

The `:app` module is a reference integration, not a published artifact. To build it after cloning:

1. Create a Firebase project and register an Android app with the package name `com.itg.template` (or change `applicationId` in [app/build.gradle](app/build.gradle) to match your own).
2. Download `google-services.json` and place it at `app/google-services.json`.

That file is intentionally not committed: it is environment-specific and carries the project's Firebase API key. The three published SDKs do not depend on it, so consuming them via JitPack requires no Firebase setup on your side.

---

## Purpose and Scope

### 1. Shared base for all apps

`adlogic-partner-sdk` is designed as the **template/base** for all Android apps in the ecosystem. Partners should fork or clone this base to keep:

- The same Ads package structure (`AdRemoteConfig`, `RemoteConfigUtils`, `AdsManager`, `AdExtension`).
- The same config source flow (assets + Firebase Remote Config).
- The same view-handover pattern for native/banner rendering (`AdsManager.nativeHelper`, `BaseActivityWithBanner` — see §2.6).
- The same QA entry through DevSetting on Language screen.

Goal: reduce app-to-app drift, improve maintainability, simplify audits, and centralize technical support.

### 2. Load/show logic is the optimized baseline

The current base flow — early init in `GlobalApp`, config sync in `Splash`, next-screen preload, centralized gating in `AdsManager`, and organic handling via `AdGate.passesUaGate(enableUaCheck)` — is the result of iterative optimization for:

- Correct load timing
- Reduced UI jank
- Safe fallback when offline / purchased
- Cohort-based display control

**Partners must not change the core flow** (for example: calling SDK directly and bypassing `AdsManager`, removing organic gating, or changing load/show order) unless approved by Infinity technical team.

### 3. Existing ad-enabled screens must follow current implementation

The following screens are already implemented and must preserve load/show behavior, preload points, and gating conditions:

| Screen | Placements / behavior |
| --- | --- |
| Splash | `inter_splash`, preload `native_language`, `open_resume` config |
| Language | Native language/click, preload onboarding page 1, DevSetting (`tvTitle`) |
| Onboarding | Native page 1 & 4, native full, `inter_onboarding`, uninstall widget |
| Welcome / Resume | `native_welcome`, `inter_welcome`, `ResumeAdsEntryRule` |
| Banner (Home + screens extending `BaseActivityWithBanner`) | Normal / collapsible banner, reload by config |

When customizing UI, only adjust layout/container. Do **not** remove the `AdGate` chain — `AdGate.skipReason(...)` owns the `isEnable`, purchase, network, and UA checks plus their skip telemetry.

### 4. Custom app screens must follow load/show rules

For any **new custom screen** (not already present in base), partners must follow the same rule set:

1. Add placement keys to `ad_config.json` / `ad_config_debug.json` and add matching properties in `AdRemoteConfig`.
2. Native: build the placement's helper via `AdsManager.nativeHelper(...)`; interstitial via `InterstitialAdManager.load` + `show` — see §2.6.
3. In Activity/Fragment: hand container + shimmer to the helper in `initViews` and call `requestAds(NativeAdParam.Request)` — hiding the slot on skip/fail is the helper's job, not the screen's.
4. For sensitive placements (onboarding-like, welcome, home, permission, widget...): **100% mandatory** to gate with `AdGate.passesUaGate(config.enableUaCheck)` — the `nativeHelper` factory wires it from `config.enableUaCheck` automatically; see section 4.
5. For banners: extend `BaseActivityWithBanner` and declare `BannerConfig` — the base rides `BannerAdHelper`; do not load banners by hand.

Detailed UI/Ads reference (CTA size, Done button delay, native placement by page):
[Infinity UI Documentation — Language & Onboarding](https://interim-pink-4gmxxkfh.edgeone.app/).

---

## 1. Ads Initialization and Config

### 1.1 Config sources
- Debug: read `ad_config_debug.json`.
- Release: read `ad_config.json`, then optionally override from Firebase Remote Config (`ad_remote_config`).

### 1.2 Initialization points

Order in `GlobalApp.onCreate()` (mandatory):

| Step | Call | Purpose |
|:---:|------|---------|
| 1 | `initTracking()` | `Tracker.install(...)` + `Tracker.addSink(...)` — **must be first**, see §1.6 |
| 2 | `DevConfig.init(...)` | DevConfig UI — ads lib versions (see §1.3) |
| 3 | `initAdRemoteConfig()` | `AdRemoteConfig.initializeFromAssets(this)` |
| 4 | `initAds()` | `ERainAd` + resume/inter rules (see §1.5) |
| 5 | `initOnboardKit()` | `OnboardingSdk.install(...)` — see the OnboardKit guide |
| 6 | `ResumeAdsEntryRule.shouldShowWelcomeOnResume()` | Register `AppLifecycleObserver` when welcome flow applies |

Do **not** call `MobileAds.initialize()` yourself: `ERainAd.init()` → `Admob.init()` already does it,
and with the adapter-status logging attached. A second call just races the first.

- `SplashActivity.checkRemoteConfigResult()`:
  - `AdRemoteConfig.initialize(this, RemoteConfigUtils.getAdRemoteConfig())` to apply latest remote config.

### 1.3 `DevConfig.init()` integration in `GlobalApp`

Call **early** in `onCreate()`, before `initAdRemoteConfig()` and `initAds()`. Version parameters come from `BuildConfig` (must be declared in `app/build.gradle` — see §1.4):

```kotlin
DevConfig.init(
    context = this,
    nkhStudioVersion = BuildConfig.ERAIN_STUDIO_VERSION,
    playServicesAdsVersion = BuildConfig.PLAY_SERVICES_ADS_VERSION,
    gdprModuleVersion = BuildConfig.GDPR_MODULE_VERSION
)
```

| Parameter | `BuildConfig` field | Shown on DevConfig UI |
|-----------|---------------------|------------------------|
| `nkhStudioVersion` | `ERAIN_STUDIO_VERSION` | ERain Studio / ads module version |
| `playServicesAdsVersion` | `PLAY_SERVICES_ADS_VERSION` | Google Play Services Ads version |
| `gdprModuleVersion` | `GDPR_MODULE_VERSION` | GDPR module version |

### 1.4 DevSetting entry for Ads QA
- `LanguageActivity`: `mBinding.tvTitle.setOnAdminAdToggleListener()`
- QA can check: sdk versions, mediation, config id, ad id, reset organic.

> **Mandatory in `app/build.gradle`:** to make DevConfig UI show version info correctly, partners must declare all 3 `buildConfigField` lines below (in both `debug` and `release`):
>
> ```gradle
> buildConfigField "String", "ERAIN_STUDIO_VERSION", "\"$erain_studio_version\""
> buildConfigField "String", "PLAY_SERVICES_ADS_VERSION", "\"$play_services_ads_version\""
> buildConfigField "String", "GDPR_MODULE_VERSION", "\"$module_update_gdpr_version\""
> ```

**DevConfig testing guide (PO / Tester):** [DevConfig Testing Guide](https://share.jotbird.com/breezy-soaring-high-desert)

### 1.5 `initAds()` integration in `GlobalApp` (recommended standard)

In the current base, the core integration is implemented in `GlobalApp.initAds()`. Partners should keep this pattern when creating new apps:

1. Select `environment` by build type (`ERainAdConfig.ENVIRONMENT_DEVELOP` / `ERainAdConfig.ENVIRONMENT_PRODUCTION`).
2. Create `mERainAdConfig = ERainAdConfig(this, environment)`.
3. Set required config fields before calling `ERainAd.init(...)`:
   - `adjustConfig` — app token plus the event tokens (see the table in §1.5.1)
   - `facebookClientToken`
   - `intervalInterstitialAd`
   - `idAdResume`
4. Call `ERainAd.getInstance().init(this, mERainAdConfig)`.
5. Call `ERainTuning.install()` — **once**, right after `ERainAd.init`.
   It pins the module's process-wide switches (`openActivityAfterShowInterAds`,
   `disableAdResumeWhenClickAds`). Do **not** set them yourself: they change what a *callback
   means*, so toggling them per screen leaves the process in whatever state the last screen died
   in, and a screen that dies between two toggles leaves it wrong for good.
6. `AppOpenManager.getInstance().disableAppResumeWithActivity(...)` for your **own** excluded
   screens only. OnboardKit excludes its own screens automatically.

Reference snippet:
```kotlin
private fun initAds() {
    val environment =
        if (BuildConfig.DEBUG) ERainAdConfig.ENVIRONMENT_DEVELOP else ERainAdConfig.ENVIRONMENT_PRODUCTION
    mERainAdConfig = ERainAdConfig(this, environment)

    val adjustConfig = AdjustConfig(true, resources.getString(R.string.adjust_token))
    adjustConfig.eventAdImpression = getString(R.string.event_token)
    adjustConfig.eventNamePurchase = getString(R.string.adjust_event_token_purchase)
    adjustConfig.fbAppId = getString(R.string.facebook_app_id)

    mERainAdConfig.adjustConfig = adjustConfig
    mERainAdConfig.facebookClientToken = resources.getString(R.string.facebook_client_token)
    // 0 = the ads module enforces no interval of its own; see §3.2
    mERainAdConfig.intervalInterstitialAd = 0
    // A blank id switches app-resume off — it never requests with an empty ad unit
    mERainAdConfig.idAdResume = ""

    ERainAd.getInstance().init(this, mERainAdConfig)
}
```

> Note: `initAdRemoteConfig()` should still run before `initAds()`, and remote config is still synced in `SplashActivity` via `RemoteConfigUtils.init(...)` + `AdRemoteConfig.initialize(...)`.

### 1.5.1 Adjust tokens — one token, one door

| Field | What it is | Blank means |
|---|---|---|
| `adjustToken` | App token from the Adjust dashboard | Adjust never initialises; an error is logged |
| `eventAdImpression` | Event token fired on **every** paid impression, on top of `Adjust.trackAdRevenue` | The event is skipped — the normal case |
| `eventNamePurchase` | Event token fired when a purchase completes | Purchase revenue is skipped, with a warning |
| `fbAppId` | Meta app id, so Adjust can forward to Meta | Meta-attributed campaigns stay empty in Adjust |

Every token is a six-character id minted on the Adjust dashboard, **not** an event name. A blank one
is skipped deliberately: `AdjustEvent("")` is accepted client-side, dropped server-side, and the
revenue disappears with no signal. There is exactly one place to set the impression token —
`adjustConfig.eventAdImpression`. Every ad format (interstitial, native, banner, rewarded, app-open)
reads that same field.

### 1.6 Tracking — `Tracker.install()` is mandatory

This is the step partners miss most often, because nothing crashes without it.

`ads` and `billingkit` do **not** log analytics themselves. Every impression, click and purchase
they observe is handed to `Tracker` (from `trackkit`), which fans out to whatever sinks you
registered. Register none and
`Tracker` validates each event and then hands it to an empty list — the data silently never arrives.
`Tracker.install` logs a warning when it runs with no sink, but the fix is to wire it:

```kotlin
private fun initTracking() {
    Tracker.install(
        this,
        TrackerConfig(
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            strictValidation = BuildConfig.DEBUG,   // taxonomy mistakes fail in QA, never in prod
            logLevel = if (BuildConfig.DEBUG) 2 else 1,
        ),
    )
    Tracker.addSink(FirebaseSink())                 // from trackkit-firebase
    if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
}
```

Call it **first** in `onCreate()`. Events tracked before `install()` are buffered, not lost, but they
are flushed with the session data of whatever session is current when install finally runs.

What you get for free once a sink exists — no call sites of your own:

| Signal | Emitted by | Event names |
|---|---|---|
| Ad lifecycle | `ads`, per ad unit | `ad_request`, `ad_loaded`, `ad_load_failed`, `ad_show`, `ad_show_failed`, `ad_click`, `ad_closed` |
| Paid impressions + ad LTV | `ads`, from the AdMob paid-event callback | `ad_impression`, `ad_revenue_total`, `ad_revenue_micro_flush`, `ad_revenue_d3`, `ad_revenue_d7` |
| Purchases | `billingkit`, from the billing callback | `iap_success` |
| First-open funnel | `onboardkitorigin` | `fo_*` — see the OnboardKit guide |

Two things stay yours: the UMP outcome (`Tracker.setConsent(analytics, ads)` — call it from your
consent callback, exactly once) and any product event of your own (`Tracker.track("...")`).

Adjust is **not** a sink and needs no wiring here. It is an MMP, it lives inside `ads`, and it is
configured through `adjustConfig` in §1.5. See `trackkit/ARCHITECTURE.md` for why the split exists.

---

## 2. Load/Show Ads by placement

### 2.0 One placement, many ad unit ids — the waterfall

A placement is not one ad unit id, it is an **ordered list**: highest floor first, all-price last.
`AdWaterfall` requests them one at a time and stops at the first fill, so a lower floor is only
ever asked for after the one above it has failed.

```kotlin
AdWaterfall.loadNative(activity, adUnitIds, layoutRes, callback)
AdWaterfall.loadInterstitial(context, adUnitIds, callback)
AdWaterfall.loadReward(context, adUnitIds, callback)
```

Banners fall through their floors too: `BannerAdHelper` walks `BannerAdConfig(tiers, …)` one id at
a time, and the slot never blinks between floors — a surviving banner stays on screen while lower
floors are tried.

Pass one id and it behaves exactly like a plain load. Blank and repeated ids are dropped, so a
half-filled remote payload cannot open a hole in the order. Every step is bounded by
`REQUEST_AD_TIMEOUT` (30 s): a floor that never answers cannot stall the floors below it.

This is the **only** load path. `AdsManager` and OnboardKit both go through it, which is why they
cannot drift apart. Do not call `ERainAd.loadNativeAdResultCallback` / `getInterstitialAds` with a
single `config.id` — that spends the all-price floor and never touches the high one.

#### Naming the floors in remote config

Each floor is its own remote key. The suffix is the rung:

| Key | Rung |
|---|---|
| `native_lang_high` | highest floor, requested first |
| `native_lang_high1` … `native_lang_high9` | further floors, in numeric order |
| `native_lang` | all-price, always last |

`AdRemoteConfig.tiersFor("native_lang")` resolves that ladder into request order. Adding a floor to
a placement is a **remote-config change, never a code change** — declare the key and it joins the
waterfall. There is deliberately no `_medium`: it would only be `_high1` under a second name, and
two spellings for one floor is how a payload ends up declaring both.

Need more than ten floors, or an order that is not high→low? Put the ids straight into one key's
`ids` array — that list is taken as the waterfall verbatim:

```json
"inter_splash": { "id": "…/allprice", "ids": ["…/high", "…/high1"], "isEnable": true }
```

> Give every placement its own ad unit ids. Two placements sharing one id cannot be told apart in
> revenue reporting: AdMob's paid-event callback knows only the ad unit, and `PlacementRegistry`
> maps it back to whichever placement requested it last.

### 2.1 Splash
- Inter Splash:
  - Condition: `AdRemoteConfig.tiersFor("inter_splash")` is non-empty (at least one enabled floor) and network available.
  - API: `ERainAd.getInstance().loadSplashInterstitialAds(...)`.
  - On successful load (`onAdLoaded`), preload `native_language`.
- Open Resume:
  - Enabled/disabled by `ResumeAdsEntryRule.shouldEnableOpenResume()`.

### 2.2 Language
- Native language:
  - Preload from Splash: `AdsManager.loadNativeLanguage(...)`
  - Click variant: `AdsManager.loadNativeLanguageClick(...)`
- Early preload for onboarding page 1:
  - `AdsManager.loadNativeOnboarding1(...)`

### 2.3 Onboarding
- `AdsManager.loadNativeOnboarding4(...)`
- `AdsManager.loadNativeOnboardingFull(...)`
- `AdsManager.loadInterOnboarding(...)`, then show via `AdsManager.showInterOnboarding(...)` at onboarding completion.

### 2.4 Welcome / Resume
- Native welcome:
  - `AdsManager.nativeHelper(activity, owner, "native_welcome", AdRemoteConfig.native_welcome, layout)`, UA-gated from `config.enableUaCheck`.
- Inter welcome:
  - `AdsManager.loadInterWelcome(...)`, `AdsManager.showInterWelcome(...)`.
  - Welcome flow is triggered by `AppLifecycleObserver` when `ResumeAdsEntryRule.shouldShowWelcomeOnResume()` and `shouldDisplayForUa(AdRemoteConfig.inter_welcome.enableUaCheck)` allow it.

### 2.5 Banner (normal / collapsible)
- Use `BaseActivityWithBanner`.
- `AdsManager.loadBanner(..., isCollapse = false)` => normal banner.
- `AdsManager.loadBanner(..., isCollapse = true)` => collapsible banner (SDK expand/collapse behavior).
- Reload interval follows `reloadIntervalSeconds`.

### 2.6 SDK helper layer — placement stores & view helpers (since 2.0.0)

All ad *mechanism* — cache, expiry, in-flight dedup, gating, the show contract — lives in
`com.ads.module.helper`. The app keeps only placement policy: which key, when to preload, where to
show. `AdsManager` delegates to this layer internally, and OnboardKit's `ERainAdProvider` is a thin
adapter over the same stores, so every consumer shares one cache with one rule set.

**Full-screen formats** are placement-keyed stores. One buffered ad per placement, 1-hour GMA
expiry, single-use show, and `onComplete` fires **exactly once** on every outcome — a failed or
skipped show can never strand a screen:

```kotlin
// Preload where you know the screen is coming; show at the navigation edge
InterstitialAdManager.load(
    context, "inter_back", config.waterfallIds,
    InterLoadOptions(
        enabled = config.isUsable,
        passesUaGate = AdGate.passesUaGate(config.enableUaCheck),
    ),
)
InterstitialAdManager.show(activity, "inter_back", object : InterShowCallback() {
    override fun onComplete() = goNextScreen()
})

// Rewarded: the classic gate → load → show chain in one call
RewardAdManager.loadAndShow(
    activity, "reward_example", config.waterfallIds,
    enabled = config.isEnable,
    onSuccess = { grantReward() }, onFailed = { showTryAgain() },
)
```

**View formats** hand their views over once; the helper owns everything after that — shimmer,
reload-on-resume, hide-when-purchased, teardown:

```kotlin
NativeAdHelper(activity, this, NativeAdConfig(config.waterfallIds, true, true, R.layout.native_home))
    .setNativeContentView(binding.frAds)
    .setShimmerLayoutView(binding.shimmer)
    .setEnablePreload(true, "native_home")
    .also { it.placement = "native_home" }
    .requestAds(NativeAdParam.Request)

BannerAdHelper(activity, this, BannerAdConfig(config.waterfallIds, true, false))
    .attachInto(binding.frAds)
    .also { it.placement = "banner_home" }
    .requestAds(BannerAdParam.Request)
```

`NativeAdPreload` is the keyed preload buffer behind the native helper (`preloadWithKey`,
`pollAdNative`, buffer > 1 supported). Setting `placement` makes the helper report
`ad_request` / skip telemetry itself with the standard reason keys.

**Reload never blinks.** The shimmer belongs to the *empty* slot: it only appears while a
placement has no ad yet. Once an ad is on screen, a reload — by timer, on resume, or through
a lower waterfall floor — runs silently underneath it; the live ad keeps its place and the new
creative replaces it the moment it fills. A reload that finds no fill changes nothing at all,
so a slot can never go ad → shimmer → ad.

## 3. Global conditions for loading ads

An ad loads only when all conditions pass, evaluated in one place — `AdGate.skipReason(...)`:
- `adUnitConfig.isUsable` — enabled **and** has at least one non-blank id
- `!AdGate.isPurchased(...)` — the `Entitlement` port's answer; `billingkit` installs the source when present
- Network available
- For mandatory organic-gated placements: `AdGate.passesUaGate(config.enableUaCheck) == true`

If any condition fails, native LiveData emits `null` so UI hides the ad container, and the reason
is reported once through `AdTracking.skipped(...)` with the unchanged keys
(`disabled_config`, `purchased`, `offline`, `ua_gate`).

### 3.1 Consent gates every request

No ad request may go out before the consent flow has answered — that is a policy rule, not a race
to win. The splash resolves it and publishes the answer once:

```kotlin
class SplashActivity : ObSplashActivity() {
    override suspend fun onConsentRequired(): Boolean = /* true when ads may be requested */
}
```

A `false` answer — or no answer inside `consentTimeoutMs` — calls `OnboardingSdk.setCanRequestAds(false)`,
and every placement then reports `consent_not_granted` instead of loading. The flow still runs; it
just runs without ads. `ConsentHandler` shows the UMP form **once per process**, so splash and any
later screen share one answer rather than asking twice.

### 3.2 Interstitial frequency lives in one place

Keep `ERainAdConfig.intervalInterstitialAd = 0`. The module's own interval swallows an interstitial
silently — the caller cannot tell it apart from a dismissal — so frequency is owned by
`ob_ads_interstitial_interval_sec`, which is remote-tunable and reports `interval_not_elapsed` when
it blocks. Two caps for one rule is a bug nobody finds for a quarter.

## 4. UA/organic gate standard per placement (100% mandatory)

> **Mandatory:** 100% of the placements below **must** pass through the UA/organic gate.  
> Since 2.0.0 there is exactly **one** gate call — `AdGate.passesUaGate(config.enableUaCheck)` —
> replacing the old per-placement `getShouldDisplay*` methods (removed: they were eleven identical
> one-line delegates, and adding a placement needed an SDK release; call sites even drifted onto
> wrong names).  
> The parameter is `enableUaCheck` from the placement config in `ad_config.json` / `ad_config_debug.json` (mapped to `AdUnitConfig.enableUaCheck`).  
> This is the organic/UA check flag (force organic via ads config) — **do not hard-code `true/false`**; always take it from the config of the placement being loaded/shown.

### 4.1 Standard mapping (from `AdsManager`)

Every row is the same call — `AdGate.passesUaGate(config.enableUaCheck)`; only the config flag
differs per placement:

| Ad placement | Default `enable_ua_check` in ad_config.json | Code usage |
|--------------|:-------------------------------------------:|------------|
| **NativeOnboardingFull1** | `true` | `AdsManager.nativeHelper(..., "native_onboarding_fullscreen_1_3", ...)` (+ full page insert in `OnBoardingActivity`) |
| **NativeOnboardingFull2** | `true` | `AdsManager.nativeHelper(..., "native_onboarding_fullscreen_1_4", ...)` (+ full page insert in `OnBoardingActivity`) |
| **NativeOnboardingNormal2** | `false` | `AdsManager.nativeHelper(..., "native_onboarding_1_4", ...)` |
| **NativeHome** | `false` | `AdsManager.nativeHelper(..., "native_home", ...)` |
| **NativePermission** | `false` | `AdsManager.nativeHelper(..., "native_permission", ...)` |
| **InterOnboarding** | `true` | `AdsManager.loadInterOnboarding` / `showInterOnboarding` |
| **NativeWelcomeBack** | `false` | `AdsManager.nativeHelper(..., "native_welcome", ...)` in `WelcomeActivity` |
| **InterWelcomeBack** | `false` | `AppLifecycleObserver` (welcome screen redirect, via `AdGate.passesUaGate`) |
| **WidgetUninstall** | `false` | `OnBoardingActivity` widget shortcut; `nativeHelper` in `SurveyActivity` / `ConfirmUninstallActivity` |

> **ad_config defaults:** when declaring JSON, set `enable_ua_check` to the default in the column above unless Infinity specifies otherwise. Example: Full1/Full2/`inter_onboarding` default `true`; remaining placements default `false`.

### 4.3 How to read the param from ad_config

In JSON for each placement:

```json
"native_onboarding_fullscreen_1_3": {
  "id": "ca-app-pub-xxx/yyy",
  "isEnable": true,
  "enable_ua_check": true
}
```

In code:

```kotlin
val config = AdRemoteConfig.native_onboarding_fullscreen_1_3
AdGate.passesUaGate(config.enableUaCheck)
```

| JSON key | Kotlin field | Meaning |
|----------|--------------|---------|
| `enable_ua_check` | `AdUnitConfig.enableUaCheck` | Enable/disable organic (UA) check for **that** placement when calling the gate |

### 4.4 Mandatory load pattern

```kotlin
AdsManager.nativeHelper(
    activity, lifecycleOwner,
    "native_onboarding_fullscreen_1_3",
    AdRemoteConfig.native_onboarding_fullscreen_1_3,
    layoutRes,
)
    .setNativeContentView(binding.frAds)
    .setShimmerLayoutView(binding.shimmer)
    .requestAds(NativeAdParam.Request)
```

The factory feeds `config.enableUaCheck` into the helper's UA gate, so a screen cannot
forget or hard-code it.

**Not compliant if:**
- Skipping the gate for any placement in the table above.
- Passing a hard-coded `true/false` instead of `config.enableUaCheck`.
- Re-implementing the organic check locally instead of calling `AdGate` — one gate, one truth.

## 5. Organic mechanism

Organic is user classification from Ads SDK / growth logic used to:
- Reduce frequency or disable sensitive ad slots for certain users
- Balance retention, UX, and revenue
- Apply cohort rules without rewriting each screen

How it works in this app:
- The app does **not** compute organic with local rules.
- The app calls `AdGate.passesUaGate(enableUaCheck)` — backed by `ERainAd.shouldDisplayForUa` —
  with `enableUaCheck` from `ad_config`.
- When organic/cohort rules change, the gate's answer changes and directly affects load/show per slot.
- DevSetting / Unlimited Ads + `reset organic` help QA re-verify all ad placements + uninstall widget.

## 6. Load/show examples (quick reference)

### 6.1 Inter Splash
```kotlin
if (AdRemoteConfig.inter_splash.isEnable && isNetwork(this)) {
    ERainAd.getInstance().loadSplashInterstitialAds(
        this, AdRemoteConfig.inter_splash.id, 30000, 5000, object : AdCallback() {
            override fun onNextAction() { moveActivity() }
        }
    )
} else moveActivity()
```

### 6.2 Native (via AdsManager.nativeHelper)
```kotlin
AdsManager.nativeHelper(
    this, this, "native_welcome", AdRemoteConfig.native_welcome, R.layout.layout_native_welcome,
)
    .setNativeContentView(mBinding.frAds)
    .setShimmerLayoutView(mBinding.shimmerAds.shimmerNativeLarge)
    .requestAds(NativeAdParam.Request)
// No observe/hide code: the helper binds on fill and hides the slot on skip/fail
```

### 6.3 Inter (Onboarding)
```kotlin
AdsManager.loadInterOnboarding(this)
AdsManager.showInterOnboarding(this) {
    goNextScreen()
}
```

### 6.4 Normal banner
```kotlin
override val bannerConfig = BannerConfig(
    adUnitConfig = AdRemoteConfig.banner_home,
    isCollapse = false
)
```

### 6.5 Collapsible banner (expand/collapse)
```kotlin
override val bannerConfig = BannerConfig(
    adUnitConfig = AdRemoteConfig.banner_home,
    isCollapse = true
)
```

## 7. Purchases and the paywall

Billing lives in `:billingkit` (still `com.ads.module.billing`); the paywall UI lives in `:paykit`.
The split is deliberate: `:billingkit` owns the Play `BillingClient` and hands the entitlement to
`:ads` through the `Entitlement` port — `AdGate` skips every placement with `PURCHASED` the moment
`AppPurchase.isPurchased` turns true. A second module with its own opinion about who is premium
would break that gate.

Minimum wiring. In `GlobalApp.onCreate()`, after `initAds()`:

```kotlin
val config = payKitConfig {
    termsUrl = "https://example.com/terms"
    privacyUrl = "https://example.com/privacy"
    defaultPlacements = setOf(PaywallPlacement.AFTER_ONBOARDING, PaywallPlacement.SETTING)
    exitButtonDelayMs = 3_000
}.getOrElse { Log.e(TAG, "PayKit config rejected", it); return }

PayKit.install(this, config)
PayKit.configSource(FirebaseConfigSource())     // optional, from :paykit-firebase
```

In Splash. `onInitBilling` runs before the first ad request and must return as soon as the
entitlement is known, so await only that; the paywall document is not needed until a checkpoint:

```kotlin
override suspend fun onInitBilling() {
    lifecycleScope.launch { PayKit.sync(timeoutMs = 3_000) }
    Billing.awaitReady(timeoutMs = 5_000)
}
```

Wherever a screen wants it:

```kotlin
PayKit.launch(activity, PaywallPlacement.SETTING)
```

Rules that are not optional:

- **Do not call `AppPurchase.initBilling` yourself.** `PayKit.install` registers the catalogue from
  the paywall document. `initBilling` tears down the live client, so a second caller with a
  different product list silently wins.
- **Placements fail closed.** With no `defaultPlacements` and no fetched `placements`, nothing
  shows; a bundled document is a catalogue and never names placements. `PayKit.launch` also refuses
  for a premium user and for a disabled placement — it logs, and reports `onFinished(Dismissed)` to
  the listener that call was given.
- **`iap_success` is emitted by `:billingkit`**, once, when Play confirms. Do not emit a purchase
  event from a paywall or a screen of your own; that counts the same revenue twice.
- **Ad gating stays on `AppPurchase.isPurchased`.** Read `PayKit.isPremium()` / `Billing.isPremium`
  for your own UI; do not mirror premium into a preference of your own.
- A `consumable` product is consumed by the billing engine and never sets the entitlement, so it
  does not remove ads. Use `inapp` for a lifetime unlock.

Full guide — config schema, placements, analytics, theming, the Compose escape hatch, and the
OnboardKit gate: **[paykit/README.md](paykit/README.md)**.

## 8. Additional reference

- [Infinity UI Documentation — Language & Onboarding](https://interim-pink-4gmxxkfh.edgeone.app/) — UI, Remote Config, and ad-unit display conditions (placement/method names should be cross-checked against this document).
