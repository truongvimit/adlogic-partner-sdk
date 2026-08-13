**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# Ads Integration Guide — Base Project (EN)

> Module guides: **[trackkit](trackkit/README.md)** · **[OnboardKit](onboardkitorigin/README.md)**

This document is the **mandatory reference standard** for partner teams integrating ads into Infinity products. Any ad-related change must follow the architecture, load/show flow, and gating rules defined in this base project.

---

## Consuming the SDKs via JitPack

Five modules are published as libraries:

| Module | Role |
|---|---|
| `ads` | Ad loading and showing |
| `onboardkitorigin` | First-open flow: splash, language, onboarding |
| `trackkit` | Vendor-free analytics contract (`Tracker`, `TrackSink`, taxonomy) |
| `trackkit-firebase` | Firebase/GA4 sink for `trackkit` |
| `adtracer` | Debug-only ad lifecycle dashboard |

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

    // Pick the sinks you actually want. Without a sink, Tracker validates and drops events.
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit-firebase:$sdkVersion"

    // adtracer ships in debug builds only — keep it out of release
    debugImplementation "com.github.truongvimit.adlogic-partner-sdk:adtracer:$sdkVersion"
}
```

Keep every module on the same tag. They are published together from one repository and are not
tested against each other across versions. Note the double quotes: Groovy only interpolates
`$sdkVersion` in double-quoted strings.

You do not need to declare `trackkit` yourself: `ads` and `onboardkitorigin` expose it as an `api`
dependency, and `trackkit-firebase` pulls it in too. Declare it explicitly only if you write your
own `TrackSink` in a module that depends on neither.

Note the group id: because this repository publishes several modules, JitPack namespaces them as
`com.github.<user>.<repo>` rather than `com.github.<user>`. The repository name is part of the group,
and JitPack derives it from the GitHub URL — it is not something `build.gradle` or `jitpack.yml` can
override. Flattening it to `com.github.truongvimit:ads` would require one repository per module.

Any tag pushed to this repository is resolvable; a commit hash or `main-SNAPSHOT` also works.

> **Note:** `onboardkitorigin` depends on `ads` at runtime scope, so its ad classes (`com.ads.module.*`) are not on your compile classpath through it. Declare `ads` explicitly, as shown above, if you call those APIs directly.

Requires JDK 17 and `minSdk` 24 or higher.

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
- The same LiveData observation pattern for native ad rendering.
- The same QA entry through DevSetting on Language screen.

Goal: reduce app-to-app drift, improve maintainability, simplify audits, and centralize technical support.

### 2. Load/show logic is the optimized baseline

The current base flow — early init in `GlobalApp`, config sync in `Splash`, next-screen preload, centralized gating in `AdsManager`, and organic handling via `ERainAd.getShouldDisplay*(enableUaCheck)` — is the result of iterative optimization for:

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

When customizing UI, only adjust layout/container. Do **not** remove `isEnable`, purchase, network, or `getShouldDisplay*(config.enableUaCheck)` checks.

### 4. Custom app screens must follow load/show rules

For any **new custom screen** (not already present in base), partners must follow the same rule set:

1. Add placement keys to `ad_config.json` / `ad_config_debug.json` and add matching properties in `AdRemoteConfig`.
2. Add load methods in `AdsManager` (native via `loadNativeInternal`, interstitial via `load` + `show` pattern).
3. In Activity/Fragment: load in `initViews` (optional short `postDelayed`), observe LiveData, call `populateNativeAdView` when ad exists, hide container on `null`.
4. For sensitive placements (onboarding-like, welcome, home, permission, widget...): **100% mandatory** to attach `ERainAd.getInstance().getShouldDisplay*(config.enableUaCheck)` using the mapping in section 4.
5. For banners: extend `BaseActivityWithBanner`, configure `BannerConfig`, do not load banner outside `AdsManager.loadBanner`.

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
5. Apply extra resume/inter rules:
   - `Admob.getInstance().setDisableAdResumeWhenClickAds(true)`
   - `Admob.getInstance().setOpenActivityAfterShowInterAds(true)`
   - `AppOpenManager.getInstance().disableAppResumeWithActivity(...)` for excluded screens.

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
    mERainAdConfig.intervalInterstitialAd = 35
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

`ads` does **not** log analytics itself. Every impression, click and purchase it observes is handed
to `Tracker` (from `trackkit`), which fans out to whatever sinks you registered. Register none and
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
| Purchases | `ads`, from the billing callback | `iap_success` |
| First-open funnel | `onboardkitorigin` | `fo_*` — see the OnboardKit guide |

Two things stay yours: the UMP outcome (`Tracker.setConsent(analytics, ads)` — call it from your
consent callback, exactly once) and any product event of your own (`Tracker.track("...")`).

Adjust is **not** a sink and needs no wiring here. It is an MMP, it lives inside `ads`, and it is
configured through `adjustConfig` in §1.5. See `trackkit/ARCHITECTURE.md` for why the split exists.

---

## 2. Load/Show Ads by placement

### 2.1 Splash
- Inter Splash:
  - Condition: `AdRemoteConfig.inter_splash.isEnable == true` and network available.
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
  - `AdsManager.loadNativeWelcome(...)`, gated by `getShouldDisplayNativeWelcomeBack(config.enableUaCheck)`.
- Inter welcome:
  - `AdsManager.loadInterWelcome(...)`, `AdsManager.showInterWelcome(...)`.
  - Welcome flow is triggered by `AppLifecycleObserver` when `ResumeAdsEntryRule.shouldShowWelcomeOnResume()` and `getShouldDisplayInterWelcomeBack(AdRemoteConfig.inter_welcome.enableUaCheck)` allow it.

### 2.5 Banner (normal / collapsible)
- Use `BaseActivityWithBanner`.
- `AdsManager.loadBanner(..., isCollapse = false)` => normal banner.
- `AdsManager.loadBanner(..., isCollapse = true)` => collapsible banner (SDK expand/collapse behavior).
- Reload interval follows `reloadIntervalSeconds`.

## 3. Global conditions for loading ads

In `AdsManager`, an ad loads only when all conditions pass:
- `adUnitConfig.isEnable == true`
- `!AppPurchase.getInstance().isPurchased(...)`
- Network available
- For mandatory organic-gated placements: `getShouldDisplay*(config.enableUaCheck) == true`

If any condition fails, native LiveData emits `null` so UI hides the ad container.

## 4. `getShouldDisplay*` standard per placement (100% mandatory)

> **Mandatory:** 100% of the placements below **must** also check the SDK `getShouldDisplay*` method.  
> The parameter is `enableUaCheck` from the placement config in `ad_config.json` / `ad_config_debug.json` (mapped to `AdUnitConfig.enableUaCheck`).  
> This is the organic/UA check flag (force organic via ads config) — **do not hard-code `true/false`**; always take it from the config of the placement being loaded/shown.
### 4.1 Standard mapping (from `AdsManager`)

| Ad placement | Required SDK method | Default `enable_ua_check` in ad_config.json | Param from ad_config | Code usage |
|--------------|---------------------|:-------------------------------------------:|----------------------|------------|
| **NativeOnboardingFull1** | `getShouldDisplayNativeOnboardingFull1(...)` |                   `true`                    | `config.enableUaCheck` | `AdsManager.loadNativeOnboardingFull` (+ full page insert in `OnBoardingActivity`) |
| **NativeOnboardingFull2** | `getShouldDisplayNativeOnboardingFull2(...)` |                   `true`                    | `config.enableUaCheck` | `AdsManager.loadNativeOnboardingFull2` (+ full page insert in `OnBoardingActivity`) |
| **NativeOnboardingNormal2** | `getShouldDisplayNativeOnboardingNormal2(...)` |                   `false`                   | `config.enableUaCheck` | `AdsManager.loadNativeOnboarding4` |
| **NativeHome** | `getShouldDisplayNativeHome(...)` |                   `false`                   | `config.enableUaCheck` | `AdsManager.loadNativeHome` |
| **NativePermission** | `getShouldDisplayNativePermission(...)` |                   `false`                   | `config.enableUaCheck` | `AdsManager.loadNativePermission` |
| **InterOnboarding** | `getShouldDisplayInterOnboarding(...)` |                   `true`                    | `config.enableUaCheck` | `AdsManager.loadInterOnboarding` / `showInterOnboarding` |
| **NativeWelcomeBack** | `getShouldDisplayNativeWelcomeBack(...)` |                   `false`                   | `config.enableUaCheck` | `AdsManager.loadNativeWelcome` |
| **InterWelcomeBack** | `getShouldDisplayInterWelcomeBack(...)` |                   `false`                   | `config.enableUaCheck` | `AppLifecycleObserver` (welcome screen redirect) |
| **WidgetUninstall** | `getShouldDisplayWidgetUninstall(...)` |                   `false`                   | `config.enableUaCheck` | `OnBoardingActivity` widget shortcut; `loadNativeSurvey` / `loadNativeConfirmUninstall` |

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
ERainAd.getInstance().getShouldDisplayNativeOnboardingFull1(config.enableUaCheck)
```

| JSON key | Kotlin field | Meaning |
|----------|--------------|---------|
| `enable_ua_check` | `AdUnitConfig.enableUaCheck` | Enable/disable organic (UA) check for **that** placement when calling `getShouldDisplay*` |

### 4.4 Mandatory load pattern

```kotlin
loadNativeInternal(
    activity,
    config,
    layoutRes,
    liveData,
    ERainAd.getInstance().getShouldDisplayNativeOnboardingFull1(config.enableUaCheck)
)
```

**Not compliant if:**
- Skipping `getShouldDisplay*` for any placement in the table above.
- Calling `getShouldDisplay*(true/false)` with a hard-coded value instead of `config.enableUaCheck`.
- Using the wrong gate method for a placement (e.g. Full1 using Normal2).

## 5. Organic mechanism

Organic is user classification from Ads SDK / growth logic used to:
- Reduce frequency or disable sensitive ad slots for certain users
- Balance retention, UX, and revenue
- Apply cohort rules without rewriting each screen

How it works in this app:
- The app does **not** compute organic with local rules.
- The app calls `ERainAd.getInstance().getShouldDisplay*(enableUaCheck)` with `enableUaCheck` from `ad_config`.
- When organic/cohort rules change, these method results change and directly affect load/show per slot.
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

### 6.2 Native (via AdsManager)
```kotlin
AdsManager.loadNativeOnboarding1(this, appSharedPref.firstOnBoarding, R.layout.layout_native_onboarding)
AdsManager.nativeOnboarding1AdLive.observe(this) { ad ->
    if (ad == null) hideAd() else showAd(ad)
}
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

## 7. Additional reference

- [Infinity UI Documentation — Language & Onboarding](https://interim-pink-4gmxxkfh.edgeone.app/) — UI, Remote Config, and ad-unit display conditions (placement/method names should be cross-checked against this document).
