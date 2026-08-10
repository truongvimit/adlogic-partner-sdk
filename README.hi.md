**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# Ads Integration Guide — Base Project (HI)

यह दस्तावेज़ Infinity products में ads integration के लिए partner teams का **mandatory reference standard** है। Ads से जुड़ा कोई भी बदलाव इसी base project में defined architecture, load/show flow, और gating rules के अनुसार होना चाहिए।

---

## उद्देश्य और दायरा

### 1. सभी apps के लिए shared base

`Example-AdLogic-Partner` ecosystem के सभी Android apps के लिए **template/base** के रूप में design किया गया है। Partners को इस base को fork/clone करके यह सुनिश्चित करना चाहिए:

- वही Ads package structure (`AdRemoteConfig`, `RemoteConfigUtils`, `AdsManager`, `AdExtension`)
- वही config source flow (assets + Firebase Remote Config)
- native ad rendering के लिए वही LiveData observation pattern
- Language screen पर DevSetting के जरिए वही QA entry

लक्ष्य: app-to-app drift कम करना, maintainability बेहतर करना, audit आसान बनाना, और technical support को centralized रखना।

### 2. load/show logic optimized baseline है

वर्तमान base flow — `GlobalApp` में early init, `Splash` में config sync, next-screen preload, `AdsManager` में centralized gating, और `ERainAd.getShouldDisplay*(enableUaCheck)` से organic handling — iterative optimization का परिणाम है:

- सही load timing
- UI jank कम
- offline / purchased स्थिति में safe fallback
- cohort-based display control

**Partners core flow को modify नहीं करेंगे** (जैसे `AdsManager` bypass करके SDK direct call करना, organic gating हटाना, या load/show order बदलना), जब तक Infinity technical team approval न दे।

### 3. existing ad-enabled screens को current implementation follow करना अनिवार्य है

नीचे दिए गए screens पहले से implement हैं और इनके load/show behavior, preload points, और gating conditions को preserve करना जरूरी है:

| Screen | Placements / behavior |
| --- | --- |
| Splash | `inter_splash`, preload `native_language`, `open_resume` config |
| Language | Native language/click, preload onboarding page 1, DevSetting (`tvTitle`) |
| Onboarding | Native page 1 & 4, native full, `inter_onboarding`, uninstall widget |
| Welcome / Resume | `native_welcome`, `inter_welcome`, `ResumeAdsEntryRule` |
| Banner (Home + screens extending `BaseActivityWithBanner`) | Normal / collapsible banner, reload by config |

UI customization के समय केवल layout/container बदलें। `isEnable`, purchase, network, और `getShouldDisplay*(config.enableUaCheck)` checks **remove न करें**।

### 4. custom app screens को load/show rules follow करना होगा

किसी भी **new custom screen** (जो base में पहले से नहीं है) के लिए वही rule set लागू होगा:

1. `ad_config.json` / `ad_config_debug.json` में placement keys जोड़ें और `AdRemoteConfig` में matching properties जोड़ें।
2. `AdsManager` में load methods जोड़ें (native के लिए `loadNativeInternal`, interstitial के लिए `load` + `show` pattern)।
3. Activity/Fragment में `initViews` पर load करें (optional short `postDelayed`), LiveData observe करें, ad मिलने पर `populateNativeAdView` call करें, `null` होने पर container hide करें।
4. sensitive placements (onboarding-like, welcome, home, permission, widget...) के लिए **100% mandatory**: `ERainAd.getInstance().getShouldDisplay*(config.enableUaCheck)` section 4 mapping के अनुसार लगाएं।
5. banner के लिए `BaseActivityWithBanner` extend करें, `BannerConfig` configure करें, `AdsManager.loadBanner` के बाहर banner load न करें।

Detailed UI/Ads reference (CTA size, Done button delay, native placement by page):  
[Infinity UI Documentation — Language & Onboarding](https://interim-pink-4gmxxkfh.edgeone.app/).

---

## 1. Ads Initialization and Config

### 1.1 Config sources
- Debug: `ad_config_debug.json` read करें।
- Release: `ad_config.json` read करें, फिर optional override Firebase Remote Config (`ad_remote_config`) से लें।

### 1.2 Initialization points

`GlobalApp.onCreate()` में order (mandatory):

| Step | Call | Purpose |
|:---:|------|---------|
| 1 | `MobileAds.initialize(this)` | Google Mobile Ads SDK initialize |
| 2 | `DevConfig.init(...)` | DevConfig UI — ads lib versions (§1.3 देखें) |
| 3 | `initAdRemoteConfig()` | `AdRemoteConfig.initializeFromAssets(this)` |
| 4 | `initAds()` | `ERainAd` + resume/inter rules (§1.5 देखें) |
| 5 | `ResumeAdsEntryRule.shouldShowWelcomeOnResume()` | welcome flow हो तो `AppLifecycleObserver` register |

- `SplashActivity.checkRemoteConfigResult()`:
  - latest remote config apply: `AdRemoteConfig.initialize(this, RemoteConfigUtils.getAdRemoteConfig())`

### 1.3 `GlobalApp` में `DevConfig.init()` integration

`onCreate()` में **जल्दी** call करें — `initAdRemoteConfig()` और `initAds()` से पहले। Version parameters `BuildConfig` से आते हैं (`app/build.gradle` में declare अनिवार्य — §1.4):

```kotlin
DevConfig.init(
    context = this,
    nkhStudioVersion = BuildConfig.ERAIN_STUDIO_VERSION,
    playServicesAdsVersion = BuildConfig.PLAY_SERVICES_ADS_VERSION,
    gdprModuleVersion = BuildConfig.GDPR_MODULE_VERSION
)
```

| Parameter | `BuildConfig` field | DevConfig UI पर |
|-----------|---------------------|------------------|
| `nkhStudioVersion` | `ERAIN_STUDIO_VERSION` | ERain Studio / ads module version |
| `playServicesAdsVersion` | `PLAY_SERVICES_ADS_VERSION` | Google Play Services Ads version |
| `gdprModuleVersion` | `GDPR_MODULE_VERSION` | GDPR module version |

### 1.4 Ads QA के लिए DevSetting entry
- `LanguageActivity`: `mBinding.tvTitle.setOnAdminAdToggleListener()`
- QA यहां check कर सकता है: sdk versions, mediation, config id, ad id, reset organic।

> **`app/build.gradle` में mandatory:** DevConfig UI में version info सही दिखाने के लिए नीचे दिए गए 3 `buildConfigField` lines (दोनों `debug` और `release` में) घोषित करना अनिवार्य है:
>
> ```gradle
> buildConfigField "String", "ERAIN_STUDIO_VERSION", "\"$erain_studio_version\""
> buildConfigField "String", "PLAY_SERVICES_ADS_VERSION", "\"$play_services_ads_version\""
> buildConfigField "String", "GDPR_MODULE_VERSION", "\"$module_update_gdpr_version\""
> ```

**DevConfig testing guide (PO / Tester):** [DevConfig Testing Guide](https://share.jotbird.com/breezy-soaring-high-desert)

### 1.5 `GlobalApp` में `initAds()` integration (recommended standard)

वर्तमान base में core integration `GlobalApp.initAds()` में implement है। नया app बनाते समय partners को यही pattern follow करना चाहिए:

1. build type के अनुसार `environment` चुनें (`ERainAdConfig.ENVIRONMENT_DEVELOP` / `ERainAdConfig.ENVIRONMENT_PRODUCTION`)।
2. `mERainAdConfig = ERainAdConfig(this, environment)` बनाएं।
3. `ERainAd.init(...)` call से पहले required fields set करें:
   - `adjustConfig`
   - `facebookClientToken`
   - `adjustTokenTiktok`
   - `intervalInterstitialAd`
   - `idAdResume`
4. `ERainAd.getInstance().init(this, mERainAdConfig)` call करें।
5. resume/inter के extra rules apply करें:
   - `Admob.getInstance().setDisableAdResumeWhenClickAds(true)`
   - `Admob.getInstance().setOpenActivityAfterShowInterAds(true)`
   - excluded screens के लिए `AppOpenManager.getInstance().disableAppResumeWithActivity(...)`।

Reference snippet:
```kotlin
private fun initAds() {
    val environment =
        if (BuildConfig.DEBUG) ERainAdConfig.ENVIRONMENT_DEVELOP else ERainAdConfig.ENVIRONMENT_PRODUCTION
    mERainAdConfig = ERainAdConfig(this, environment)

    mERainAdConfig.adjustConfig = AdjustConfig(true, resources.getString(R.string.adjust_token))
    mERainAdConfig.facebookClientToken = resources.getString(R.string.facebook_client_token)
    mERainAdConfig.adjustTokenTiktok = resources.getString(R.string.event_token)
    mERainAdConfig.intervalInterstitialAd = 35
    mERainAdConfig.idAdResume = ""

    ERainAd.getInstance().init(this, mERainAdConfig)
}
```

> Note: `initAdRemoteConfig()` को `initAds()` से पहले call करना चाहिए, और remote config sync फिर भी `SplashActivity` में `RemoteConfigUtils.init(...)` + `AdRemoteConfig.initialize(...)` से होता है।

## 2. Load/Show Ads by placement

### 2.1 Splash
- Inter Splash:
  - Condition: `AdRemoteConfig.inter_splash.isEnable == true` और network available
  - API: `ERainAd.getInstance().loadSplashInterstitialAds(...)`
  - successful load (`onAdLoaded`) के बाद `native_language` preload
- Open Resume:
  - `ResumeAdsEntryRule.shouldEnableOpenResume()` से enable/disable

### 2.2 Language
- Native language:
  - Splash से preload: `AdsManager.loadNativeLanguage(...)`
  - Click variant: `AdsManager.loadNativeLanguageClick(...)`
- onboarding page 1 के लिए early preload:
  - `AdsManager.loadNativeOnboarding1(...)`

### 2.3 Onboarding
- `AdsManager.loadNativeOnboarding4(...)`
- `AdsManager.loadNativeOnboardingFull(...)`
- `AdsManager.loadInterOnboarding(...)`, और onboarding completion पर `AdsManager.showInterOnboarding(...)`

### 2.4 Welcome / Resume
- Native welcome:
  - `AdsManager.loadNativeWelcome(...)`, gate: `getShouldDisplayNativeWelcomeBack(config.enableUaCheck)`
- Inter welcome:
  - `AdsManager.loadInterWelcome(...)`, `AdsManager.showInterWelcome(...)`
  - Welcome flow `AppLifecycleObserver` द्वारा trigger होता है जब `ResumeAdsEntryRule.shouldShowWelcomeOnResume()` और `getShouldDisplayInterWelcomeBack(AdRemoteConfig.inter_welcome.enableUaCheck)` allow करते हैं।

### 2.5 Banner (normal / collapsible)
- `BaseActivityWithBanner` use करें।
- `AdsManager.loadBanner(..., isCollapse = false)` => normal banner
- `AdsManager.loadBanner(..., isCollapse = true)` => collapsible banner (SDK expand/collapse behavior)
- Reload interval: `reloadIntervalSeconds`

## 3. Global conditions for loading ads

`AdsManager` में ad तभी load होगा जब सभी conditions pass हों:
- `adUnitConfig.isEnable == true`
- `!AppPurchase.getInstance().isPurchased(...)`
- Network available
- mandatory organic-gated placements के लिए: `getShouldDisplay*(config.enableUaCheck) == true`

कोई भी condition fail होने पर native LiveData `null` emit करेगा, इसलिए UI ad container hide करेगा।

## 4. `getShouldDisplay*` standard per placement (100% mandatory)

> **Mandatory:** नीचे दिए गए placements में से 100% पर SDK का `getShouldDisplay*` check **जरूरी** है।  
> Parameter `enableUaCheck` है — `ad_config.json` / `ad_config_debug.json` के placement config से आता है (`AdUnitConfig.enableUaCheck` में map)।  
> यह organic/UA check flag है (ads config से force organic) — **`true/false` hard-code न करें**; हमेशा उसी placement के config से लें जो load/show हो रहा है।
### 4.1 Standard mapping (`AdsManager` के अनुसार)

| Ad placement | Required SDK method | Default `enable_ua_check` in ad_config.json | Param from ad_config | Code usage |
|--------------|---------------------|:----------------------------------------:|----------------------|------------|
| **NativeOnboardingFull1** | `getShouldDisplayNativeOnboardingFull1(...)` |                  `true`                  | `config.enableUaCheck` | `AdsManager.loadNativeOnboardingFull` (+ `OnBoardingActivity` में full page insert) |
| **NativeOnboardingFull2** | `getShouldDisplayNativeOnboardingFull2(...)` |                  `true`                  | `config.enableUaCheck` | `AdsManager.loadNativeOnboardingFull2` (+ `OnBoardingActivity` में full page insert) |
| **NativeOnboardingNormal2** | `getShouldDisplayNativeOnboardingNormal2(...)` |                 `false`                  | `config.enableUaCheck` | `AdsManager.loadNativeOnboarding4` |
| **NativeHome** | `getShouldDisplayNativeHome(...)` |                 `false`                  | `config.enableUaCheck` | `AdsManager.loadNativeHome` |
| **NativePermission** | `getShouldDisplayNativePermission(...)` |                 `false`                  | `config.enableUaCheck` | `AdsManager.loadNativePermission` |
| **InterOnboarding** | `getShouldDisplayInterOnboarding(...)` |                  `true`                  | `config.enableUaCheck` | `AdsManager.loadInterOnboarding` / `showInterOnboarding` |
| **NativeWelcomeBack** | `getShouldDisplayNativeWelcomeBack(...)` |                 `false`                  | `config.enableUaCheck` | `AdsManager.loadNativeWelcome` |
| **InterWelcomeBack** | `getShouldDisplayInterWelcomeBack(...)` |                 `false`                  | `config.enableUaCheck` | `AppLifecycleObserver` (welcome activity redirection) |
| **WidgetUninstall** | `getShouldDisplayWidgetUninstall(...)` |                 `false`                  | `config.enableUaCheck` | `OnBoardingActivity` widget shortcut; `loadNativeSurvey` / `loadNativeConfirmUninstall` |

> **ad_config defaults:** JSON declare करते समय ऊपर वाले column के default `enable_ua_check` set करें, जब तक Infinity अलग value न दे। उदाहरण: Full1/Full2/`inter_onboarding` default `true`; बाकी placements default `false`।

### 4.3 ad_config से param कैसे लें

हर placement के JSON में:

```json
"native_onboarding_fullscreen_1_3": {
  "id": "ca-app-pub-xxx/yyy",
  "isEnable": true,
  "enable_ua_check": true
}
```

Code में:

```kotlin
val config = AdRemoteConfig.native_onboarding_fullscreen_1_3
ERainAd.getInstance().getShouldDisplayNativeOnboardingFull1(config.enableUaCheck)
```

| JSON key | Kotlin field | Meaning |
|----------|--------------|---------|
| `enable_ua_check` | `AdUnitConfig.enableUaCheck` | उस placement के लिए organic (UA) check on/off जब `getShouldDisplay*` call हो |

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

**Compliant नहीं अगर:**
- ऊपर की table में किसी placement पर `getShouldDisplay*` skip करें।
- `getShouldDisplay*(true/false)` hard-code करें बजाय `config.enableUaCheck` के।
- गलत gate method इस्तेमाल करें (जैसे Full1 पर Normal2)।

## 5. Organic mechanism

Organic Ads SDK / growth logic से user classification है, जिसके लिए:
- कुछ users पर sensitive ad slots की frequency कम करना या disable करना
- retention, UX, और revenue का balance रखना
- हर screen rewrite किए बिना cohort rules लागू करना

इस app में कैसे काम करता है:
- app local rules से organic compute **नहीं** करता।
- app `ERainAd.getInstance().getShouldDisplay*(enableUaCheck)` call करता है, `enableUaCheck` `ad_config` से आता है।
- organic/cohort rules बदलने पर ये method results बदलते हैं और हर slot के load/show को सीधे affect करते हैं।
- DevSetting / Unlimited Ads + `reset organic` QA को सभी ad placements + uninstall widget re-verify करने में मदद करते हैं।

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

- [Infinity UI Documentation — Language & Onboarding](https://interim-pink-4gmxxkfh.edgeone.app/) — UI, Remote Config, और ad-unit display conditions (placement/method names को इस document से cross-check करना चाहिए)।
