**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# Hướng dẫn tích hợp Ads — Base Project (VI)

Tài liệu này là **chuẩn tham chiếu bắt buộc** dành cho đối tác phát triển khi tích hợp quảng cáo trên các sản phẩm của Infinity. Mọi thay đổi liên quan Ads phải tuân thủ kiến trúc, luồng load/show và các rule gating được mô tả trong project base này.

---

## Mục đích và phạm vi áp dụng

### 1. Base chung cho toàn bộ ứng dụng

Project `Example-AdLogic-Partner` được xây dựng như **template/base** cho mọi app Android trong hệ sinh thái. Đối tác fork hoặc nhân bản từ base này để đảm bảo:

- Cùng một cách tổ chức package Ads (`AdRemoteConfig`, `RemoteConfigUtils`, `AdsManager`, `AdExtension`).
- Cùng cơ chế đọc config từ asset và Firebase Remote Config.
- Cùng pattern quan sát kết quả load (LiveData) và populate native ad.
- Cùng entry QA qua DevSetting trên màn Language.

Mục tiêu: giảm sai lệch giữa các app, dễ bảo trì, dễ audit và dễ hỗ trợ kỹ thuật tập trung.

### 2. Logic và flow load/show Ads là chuẩn tối ưu

Luồng hiện tại trong base — khởi tạo sớm tại `GlobalApp`, đồng bộ config tại `Splash`, preload theo màn kế tiếp, gate tập trung trong `AdsManager`, organic qua `ERainAd.getShouldDisplay*(enableUaCheck)` — đã được chuẩn hóa sau nhiều vòng tối ưu về **thời điểm load**, **tránh jank UI**, **fallback khi mất mạng/mua hàng**, và **điều kiện hiển thị theo cohort**.

**Đối tác không tự ý thay đổi flow cốt lõi** (ví dụ: gọi trực tiếp SDK bỏ qua `AdsManager`, bỏ gate organic, hoặc load/show không đúng thứ tự màn) trừ khi có phê duyệt kỹ thuật từ Infinity.

### 3. Các màn đã có sẵn Ads — bắt buộc follow đúng implementation

Các màn sau đã được implement đầy đủ; đối tác **phải giữ nguyên** cách gọi load/show, vị trí preload và điều kiện gate tương ứng:

| Màn hình | Placement / hành vi |
| --- | --- |
| Splash | `inter_splash`, preload `native_language`, cấu hình `open_resume` |
| Language | Native language / click, preload onboarding page 1, DevSetting (`tvTitle`) |
| Onboarding | Native page 1 & 4, native full, `inter_onboarding`, widget uninstall |
| Welcome / Resume | `native_welcome`, `inter_welcome`, rule `ResumeAdsEntryRule` |
| Banner (Home và màn extend `BaseActivityWithBanner`) | Banner thường / collapsible, reload theo config |

Khi customize UI, chỉ được thay layout/container; **không được bỏ** các điều kiện `isEnable`, purchase, network và `getShouldDisplay*(config.enableUaCheck)` đã gắn sẵn.

### 4. Màn custom của app — follow theo rule load & show

Với màn hình **do app tự thêm** (không có sẵn trong base), đối tác vẫn phải tuân thủ **cùng bộ rule**:

1. Khai báo placement trong `ad_config.json` / `ad_config_debug.json` và property tương ứng trong `AdRemoteConfig`.
2. Thêm method load trong `AdsManager` (native qua `loadNativeInternal`, inter qua pattern `load` + `show`).
3. Activity/Fragment: gọi load ở `initViews` (có thể `postDelayed` ngắn), observe LiveData, `populateNativeAdView` khi có ad; ẩn container khi `null`.
4. Nếu placement thuộc nhóm nhạy cảm (onboarding-like, welcome, home, permission, widget…): **bắt buộc 100%** gắn `ERainAd.getInstance().getShouldDisplay*(config.enableUaCheck)` đúng mapping mục 4.
5. Banner: extend `BaseActivityWithBanner`, cấu hình `BannerConfig`, không tự load banner ngoài `AdsManager.loadBanner`.

Tài liệu UI/Ads chi tiết (kích thước CTA, delay nút Done, vị trí native theo page): [Infinity UI Documentation — Language & Onboarding](https://interim-pink-4gmxxkfh.edgeone.app/).

---

## 1. Khởi tạo Ads và Config

### 1.1 Nguồn config
- Debug: đọc `ad_config_debug.json`.
- Release: đọc `ad_config.json`, sau đó có thể override bằng Firebase Remote Config (`ad_remote_config`).

### 1.2 Thời điểm khởi tạo

Thứ tự trong `GlobalApp.onCreate()` (bắt buộc follow):

| Bước | Gọi tại | Mục đích |
|:---:|---------|----------|
| 1 | `MobileAds.initialize(this)` | Khởi tạo Google Mobile Ads SDK |
| 2 | `DevConfig.init(...)` | DevConfig UI — version libs ads (xem mục 1.3) |
| 3 | `initAdRemoteConfig()` | `AdRemoteConfig.initializeFromAssets(this)` |
| 4 | `initAds()` | `ERainAd` + rule resume/inter (xem mục 1.5) |
| 5 | `ResumeAdsEntryRule.shouldShowWelcomeOnResume()` | Đăng ký `AppLifecycleObserver` nếu cần welcome flow |

- `SplashActivity.checkRemoteConfigResult()`:
  - `AdRemoteConfig.initialize(this, RemoteConfigUtils.getAdRemoteConfig())` để apply config mới nhất từ remote.

### 1.3 Tích hợp `DevConfig.init()` trong `GlobalApp`

Gọi **sớm** trong `onCreate()`, trước `initAdRemoteConfig()` và `initAds()`. Ba tham số version lấy từ `BuildConfig` (phải khai báo trong `app/build.gradle` — xem mục 1.4):

```kotlin
DevConfig.init(
    context = this,
    nkhStudioVersion = BuildConfig.ERAIN_STUDIO_VERSION,
    playServicesAdsVersion = BuildConfig.PLAY_SERVICES_ADS_VERSION,
    gdprModuleVersion = BuildConfig.GDPR_MODULE_VERSION
)
```

| Tham số | Nguồn `BuildConfig` | Hiển thị trên DevConfig UI |
|---------|---------------------|----------------------------|
| `nkhStudioVersion` | `ERAIN_STUDIO_VERSION` | ERain Studio / ads module version |
| `playServicesAdsVersion` | `PLAY_SERVICES_ADS_VERSION` | Google Play Services Ads version |
| `gdprModuleVersion` | `GDPR_MODULE_VERSION` | GDPR module version |

### 1.4 Entry mở DevSetting để QA ads
- `LanguageActivity`: `mBinding.tvTitle.setOnAdminAdToggleListener()`.
- Tại đây QA có thể check: version sdk ads, mediation, config id, ad id, reset organic.

> **Bắt buộc cấu hình trong `app/build.gradle`:** để DevConfig UI hiển thị đúng thông tin version, đối tác phải khai báo đủ 3 dòng `buildConfigField` bên dưới (ở cả `debug` và `release`):
>
> ```gradle
> buildConfigField "String", "ERAIN_STUDIO_VERSION", "\"$erain_studio_version\""
> buildConfigField "String", "PLAY_SERVICES_ADS_VERSION", "\"$play_services_ads_version\""
> buildConfigField "String", "GDPR_MODULE_VERSION", "\"$module_update_gdpr_version\""
> ```

**Hướng dẫn test DevConfig (PO / Tester):** [DevConfig Testing Guide](https://share.jotbird.com/breezy-soaring-high-desert)

### 1.5 Hướng dẫn tích hợp `initAds()` trong `GlobalApp`

Trong base hiện tại, phần tích hợp chính nằm ở `GlobalApp.initAds()`. Đối tác nên giữ nguyên pattern này khi tạo app mới:

1. Chọn `environment` theo build type (`ERainAdConfig.ENVIRONMENT_DEVELOP` / `ERainAdConfig.ENVIRONMENT_PRODUCTION`).
2. Tạo `mERainAdConfig = ERainAdConfig(this, environment)`.
3. Set các trường config cần thiết trước khi `ERainAd.init(...)`:
   - `adjustConfig`
   - `facebookClientToken`
   - `adjustTokenTiktok`
   - `intervalInterstitialAd`
   - `idAdResume`
4. Gọi `ERainAd.getInstance().init(this, mERainAdConfig)`.
5. Set các rule bổ sung cho resume/inter:
   - `Admob.getInstance().setDisableAdResumeWhenClickAds(true)`
   - `Admob.getInstance().setOpenActivityAfterShowInterAds(true)`
   - `AppOpenManager.getInstance().disableAppResumeWithActivity(...)` cho các màn cần loại trừ.

Snippet tham chiếu:
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

> Lưu ý: `initAdRemoteConfig()` vẫn cần gọi trước `initAds()`, và config remote vẫn được đồng bộ lại ở `SplashActivity` qua `RemoteConfigUtils.init(...)` + `AdRemoteConfig.initialize(...)`.

## 2. Cơ chế load/show Ads theo vị trí

### 2.1 Splash
- Inter Splash:
  - Điều kiện: `AdRemoteConfig.inter_splash.isEnable == true` và có mạng.
  - API: `ERainAd.getInstance().loadSplashInterstitialAds(...)`.
  - Sau khi load thành công (`onAdLoaded`) thì preload `native_language`.
- Open Resume:
  - Bật/tắt theo `ResumeAdsEntryRule.shouldEnableOpenResume()`.

### 2.2 Language
- Native language:
  - preload từ Splash: `AdsManager.loadNativeLanguage(...)`.
  - native click variant: `AdsManager.loadNativeLanguageClick(...)`.
- Native page onboarding 1 được load sớm:
  - `AdsManager.loadNativeOnboarding1(...)`.

### 2.3 Onboarding
- `AdsManager.loadNativeOnboarding4(...)`.
- `AdsManager.loadNativeOnboardingFull(...)`.
- `AdsManager.loadInterOnboarding(...)` và show bằng `AdsManager.showInterOnboarding(...)` khi kết thúc onboarding.

### 2.4 Welcome / Resume
- Native welcome:
  - `AdsManager.loadNativeWelcome(...)`, gate `getShouldDisplayNativeWelcomeBack(config.enableUaCheck)`.
- Inter welcome:
  - `AdsManager.loadInterWelcome(...)`, `AdsManager.showInterWelcome(...)`.
  - Flow welcome được kích hoạt bởi `AppLifecycleObserver` nếu `ResumeAdsEntryRule.shouldShowWelcomeOnResume()` và `getShouldDisplayInterWelcomeBack(AdRemoteConfig.inter_welcome.enableUaCheck)` cho phép.

### 2.5 Banner (normal / collapsible)
- Dùng `BaseActivityWithBanner`.
- `AdsManager.loadBanner(..., isCollapse = false)` => banner thường.
- `AdsManager.loadBanner(..., isCollapse = true)` => collapsible banner (expand/collapse theo SDK).
- Reload theo `reloadIntervalSeconds`.

## 3. Điều kiện chung để Ads được load

Trong `AdsManager`, một ad chỉ load khi thỏa đủ:
- `adUnitConfig.isEnable == true`.
- `!AppPurchase.getInstance().isPurchased(...)`.
- Có mạng.
- Với các vị trí bắt buộc gate organic: `getShouldDisplay*(config.enableUaCheck) == true`.

Nếu fail 1 điều kiện, native LiveData trả `null` để UI ẩn ad container.

## 4. Chuẩn `getShouldDisplay*` theo từng vị trí (bắt buộc 100%)

> **Bắt buộc:** 100% các vị trí dưới đây **phải** check thêm biến `getShouldDisplay*` của SDK.  
> Param truyền vào là `enableUaCheck` lấy từ config placement trong `ad_config.json` / `ad_config_debug.json` (map sang `AdUnitConfig.enableUaCheck`).  
> Đây là cờ organic/UA check (force organic theo config ads) — **không được hard-code `true/false`**, phải lấy từ config của đúng placement đang load/show.

### 4.1 Mapping chuẩn (theo `AdsManager`)

| Vị trí Ads | Method SDK bắt buộc | Default `enable_ua_check` trong ad_config.json | Param từ ad_config | Method / chỗ dùng trong code |
|------------|---------------------|:----------------------------------------------:|--------------------|------------------------------|
| **NativeOnboardingFull1** | `getShouldDisplayNativeOnboardingFull1(...)` |                     `true`                     | `config.enableUaCheck` | `AdsManager.loadNativeOnboardingFull` (+ chèn page full ở `OnBoardingActivity`) |
| **NativeOnboardingFull2** | `getShouldDisplayNativeOnboardingFull2(...)` |                     `true`                     | `config.enableUaCheck` | `AdsManager.loadNativeOnboardingFull2` (+ chèn page full ở `OnBoardingActivity`) |
| **NativeOnboardingNormal2** | `getShouldDisplayNativeOnboardingNormal2(...)` |                    `false`                     | `config.enableUaCheck` | `AdsManager.loadNativeOnboarding4` |
| **NativeHome** | `getShouldDisplayNativeHome(...)` |                    `false`                     | `config.enableUaCheck` | `AdsManager.loadNativeHome` |
| **NativePermission** | `getShouldDisplayNativePermission(...)` |                    `false`                     | `config.enableUaCheck` | `AdsManager.loadNativePermission` |
| **InterOnboarding** | `getShouldDisplayInterOnboarding(...)` |                     `true`                     | `config.enableUaCheck` | `AdsManager.loadInterOnboarding` / `showInterOnboarding` |
| **NativeWelcomeBack** | `getShouldDisplayNativeWelcomeBack(...)` |                    `false`                     | `config.enableUaCheck` | `AdsManager.loadNativeWelcome` |
| **InterWelcomeBack** | `getShouldDisplayInterWelcomeBack(...)` |                    `false`                     | `config.enableUaCheck` | `AppLifecycleObserver` (chuyển hướng màn Welcome) |
| **WidgetUninstall** | `getShouldDisplayWidgetUninstall(...)` |                    `false`                     | `config.enableUaCheck` | `OnBoardingActivity` widget shortcut; `loadNativeSurvey` / `loadNativeConfirmUninstall` |

> **Default trong `ad_config`:** khi khai báo JSON, các placement trên phải set `enable_ua_check` đúng default cột trên trừ khi Infinity chỉ định khác. Ví dụ Full1/Full2/`inter_onboarding` mặc định `true`; các vị trí còn lại mặc định `false`.

### 4.3 Cách lấy param từ ad_config

Trong JSON mỗi placement:

```json
"native_onboarding_fullscreen_1_3": {
  "id": "ca-app-pub-xxx/yyy",
  "isEnable": true,
  "enable_ua_check": true
}
```

Trong code:

```kotlin
val config = AdRemoteConfig.native_onboarding_fullscreen_1_3
ERainAd.getInstance().getShouldDisplayNativeOnboardingFull1(config.enableUaCheck)
```

| JSON key | Field Kotlin | Ý nghĩa |
|----------|--------------|---------|
| `enable_ua_check` | `AdUnitConfig.enableUaCheck` | Bật/tắt organic (UA) check cho **đúng** placement đó khi gọi `getShouldDisplay*` |

### 4.4 Pattern bắt buộc khi load

```kotlin
loadNativeInternal(
    activity,
    config,
    layoutRes,
    liveData,
    ERainAd.getInstance().getShouldDisplayNativeOnboardingFull1(config.enableUaCheck)
)
```

**Không đạt chuẩn nếu:**
- Bỏ qua `getShouldDisplay*` ở các vị trí bảng trên.
- Gọi `getShouldDisplay*(true/false)` hard-code thay vì `config.enableUaCheck`.
- Dùng nhầm method gate giữa các vị trí (ví dụ Full1 dùng Normal2).

## 5. Cơ chế Organic

Organic là cơ chế phân loại user từ Ads SDK / logic tăng trưởng để:
- Giảm tần suất hoặc tắt một số ad slot nhạy cảm với một nhóm user.
- Cân bằng retention, UX và revenue.
- Cho phép rule theo cohort mà không sửa từng màn hình.

Cách hoạt động trong app:
- App **không** tự tính organic bằng local rule.
- App gọi `ERainAd.getInstance().getShouldDisplay*(enableUaCheck)` với `enableUaCheck` lấy từ `ad_config`.
- Khi organic/cohort rule đổi, kết quả các method này đổi theo và ảnh hưởng trực tiếp load/show từng slot.
- DevSetting / Unlimited Ads + `reset organic` giúp QA verify lại toàn bộ vị trí ads + widget uninstall.

## 6. Ví dụ load/show (tham khảo)

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

### 6.2 Native (qua AdsManager)
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

### 6.4 Banner thường (normal)
```kotlin
override val bannerConfig = BannerConfig(
    adUnitConfig = AdRemoteConfig.banner_home,
    isCollapse = false
)
```

### 6.5 Banner collapsible (expand/collapse)
```kotlin
override val bannerConfig = BannerConfig(
    adUnitConfig = AdRemoteConfig.banner_home,
    isCollapse = true
)
```

## 7. Tài liệu tham chiếu bổ sung

- [Infinity UI Documentation — Language & Onboarding](https://interim-pink-4gmxxkfh.edgeone.app/) — UI, Remote Config và điều kiện hiển thị từng ad unit (đối chiếu tên method/placement với tài liệu này).
