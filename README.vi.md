**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# Hướng dẫn tích hợp Ads — Base Project (VI)

> Tài liệu module: **[trackkit](trackkit/README.vi.md)** · **[OnboardKit](onboardkitorigin/README.vi.md)** · **[PayKit](paykit/README.md)** · **[BillingKit](billingkit/README.md)**

Tài liệu này là **chuẩn tham chiếu bắt buộc** dành cho đối tác phát triển khi tích hợp quảng cáo trên các sản phẩm của Infinity. Mọi thay đổi liên quan Ads phải tuân thủ kiến trúc, luồng load/show và các rule gating được mô tả trong project base này.

## Dùng SDK qua JitPack

Tám module được publish dưới dạng thư viện:

| Module | Vai trò |
|---|---|
| `ads` | Load và show quảng cáo; gate premium qua port `Entitlement` |
| `billingkit` | Engine Play Billing — vẫn đúng các class `com.ads.module.billing` như trước khi tách |
| `onboardkitorigin` | Luồng first-open: splash, language, onboarding |
| `paykit` | UI paywall trên engine billing trong `billingkit` |
| `paykit-firebase` | Nguồn Firebase Remote Config cho `paykit` |
| `trackkit` | Contract analytics không dính vendor (`Tracker`, `TrackSink`, taxonomy) |
| `trackkit-firebase` | Sink Firebase/GA4 cho `trackkit` |
| `adtracer` | Dashboard vòng đời ads, chỉ cho debug |

Chỉ khai đúng những gì app kiếm tiền bằng — bốn kịch bản partner:

| # | Partner | Khai báo | APK chắc chắn không chứa |
|---|---|---|---|
| 1 | Chỉ ads, không IAP | `ads` (+ `trackkit-firebase`) | Không một class Play Billing nào |
| 2 | IAP + paywall dựng sẵn, không ads | `billingkit` + `paykit` | Không một class GMA/AdMob nào |
| 3 | IAP nhưng tự viết UI paywall | `billingkit` | Không cần `paykit` lẫn `ads` |
| 4 | Cả ads lẫn IAP | `ads` + `billingkit` (+ `paykit`) | — gate premium hoạt động y như trước |

Khai dependency:

```groovy
// Thay <tag> bằng một tag từ https://github.com/truongvimit/adlogic-partner-sdk/tags
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"

    // Engine billing. Chỉ khi app bán IAP/subscription — xem bảng kịch bản ở trên.
    implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"

    // Paywall. Thêm paykit-firebase chỉ khi document paywall lấy từ Remote Config.
    implementation "com.github.truongvimit.adlogic-partner-sdk:paykit:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:paykit-firebase:$sdkVersion"

    // Chọn sink bạn thật sự dùng. Không có sink, Tracker validate xong rồi bỏ event.
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit-firebase:$sdkVersion"

    // adtracer chỉ ship trong bản debug — giữ nó ngoài release
    debugImplementation "com.github.truongvimit.adlogic-partner-sdk:adtracer:$sdkVersion"
}
```

Giữ mọi module trên cùng một tag — chúng được publish cùng nhau từ một repository và không được
test chéo giữa các version.

> **Lưu ý:** `onboardkitorigin` phụ thuộc `ads`, còn `paykit` phụ thuộc `billingkit`, nhưng chỉ ở
> scope runtime — các class `com.ads.module.*` không vào compile classpath của bạn qua hai module
> đó. Khai `ads` / `billingkit` tường minh như trên nếu bạn gọi trực tiếp các API này.

### Nâng cấp: engine billing chuyển sang `billingkit`

Engine Play Billing đã rời `ads`. Nếu app bán IAP, thêm đúng một dòng Gradle —
`implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"` — và không đổi
gì khác: mọi class `com.ads.module.billing.*` giữ nguyên tên, package và hành vi. App chỉ chạy ads
không phải làm gì, và APK không còn thư viện Play Billing. Chi tiết: **[MIGRATION.md](MIGRATION.md)**.

### Nâng cấp lên 2.0.0

- Mười một method `ERainAd.getShouldDisplay*` đã bị **xóa**. Thay mọi call site bằng
  `AdGate.passesUaGate(config.enableUaCheck)` — hoặc `ERainAd.getInstance().shouldDisplayForUa(...)`,
  cùng một check nhưng không còn wrapper đặt tên theo placement. Thêm placement không còn cần release SDK.
- Package mới `com.ads.module.helper`: `AdGate` (gate duy nhất trước khi load), các store theo
  placement `InterstitialAdManager` / `RewardAdManager`, `NativeAdPreload`, và các helper mức view
  `NativeAdHelper` / `BannerAdHelper` — xem mục 2.6.
- Waterfall giờ phủ đủ bốn định dạng: `AdWaterfall.loadReward` đứng cạnh native/interstitial,
  và `BannerAdHelper` đi qua các tầng banner mà view không hề chớp giữa các tầng.
- `ERainAdProvider` của OnboardKit giờ là một adapter mỏng trên cùng các store đó — hành vi,
  placement key và telemetry không đổi.

### Nâng cấp lên 3.0.0

Không có API nào bị xóa trong `:ads`, và từng call site không phải làm gì để giữ hành vi cũ —
shimmer khai tường minh vẫn thắng. Nhưng `autoShimmer` mặc định **bật**: vị trí trước đây để
trống trong lúc load giờ hiện skeleton tự suy từ chính layout ad — xem mục 2.7.

- Màn hình có view shimmer riêng nằm trong ad container mà **không** truyền qua
  `setShimmerLayoutView` sẽ hiện hai skeleton: hoặc truyền nó vào `setShimmerLayoutView`,
  hoặc tắt theo placement bằng `nativeConfig.autoShimmer = false`.
- Dọn dẹp khuyến nghị: xóa các XML shimmer theo placement cùng các `<include>` và call
  `setShimmerLayoutView` — call site chỉ còn `setNativeContentView(...).requestAds(...)`.
  Tuỳ chọn: dùng `setNativeStyle` cho styling native theo remote config.
- Nội bộ OnboardKit: các layout `ob_shimmer_native_*` không còn tồn tại.

---

## Mục đích và phạm vi áp dụng

### 1. Base chung cho toàn bộ ứng dụng

Project `adlogic-partner-sdk` được xây dựng như **template/base** cho mọi app Android trong hệ sinh thái. Đối tác fork hoặc nhân bản từ base này để đảm bảo:

- Cùng một cách tổ chức package Ads (`AdRemoteConfig`, `RemoteConfigUtils`, `AdsManager`, `AdExtension`).
- Cùng cơ chế đọc config từ asset và Firebase Remote Config.
- Cùng pattern giao view cho render native/banner (`AdsManager.nativeHelper`, `BaseActivityWithBanner` — xem mục 2.6).
- Cùng entry QA qua DevSetting trên màn Language.

Mục tiêu: giảm sai lệch giữa các app, dễ bảo trì, dễ audit và dễ hỗ trợ kỹ thuật tập trung.

### 2. Logic và flow load/show Ads là chuẩn tối ưu

Luồng hiện tại trong base — khởi tạo sớm tại `GlobalApp`, đồng bộ config tại `Splash`, preload theo màn kế tiếp, gate tập trung trong `AdsManager`, organic qua `AdGate.passesUaGate(enableUaCheck)` — đã được chuẩn hóa sau nhiều vòng tối ưu về **thời điểm load**, **tránh jank UI**, **fallback khi mất mạng/mua hàng**, và **điều kiện hiển thị theo cohort**.

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

Khi customize UI, chỉ được thay layout/container; **không được bỏ** chuỗi `AdGate` — `AdGate.skipReason(...)` sở hữu các check `isEnable`, purchase, network, UA cùng telemetry skip của chúng.

### 4. Màn custom của app — follow theo rule load & show

Với màn hình **do app tự thêm** (không có sẵn trong base), đối tác vẫn phải tuân thủ **cùng bộ rule**:

1. Khai báo placement trong `ad_config.json` / `ad_config_debug.json` và property tương ứng trong `AdRemoteConfig`.
2. Native: tạo helper cho placement qua `AdsManager.nativeHelper(...)`; inter qua `InterstitialAdManager.load` + `show` — xem mục 2.6.
3. Activity/Fragment: giao container cho helper ở `initViews` và gọi `requestAds(NativeAdParam.Request)` — skeleton loading do SDK tự suy từ chính layout ad (xem mục 2.7), còn ẩn slot khi skip/fail là việc của helper, không phải của màn hình.
4. Nếu placement thuộc nhóm nhạy cảm (onboarding-like, welcome, home, permission, widget…): **bắt buộc 100%** gate bằng `AdGate.passesUaGate(config.enableUaCheck)` — factory `nativeHelper` tự nối gate từ `config.enableUaCheck`; xem mục 4.
5. Banner: extend `BaseActivityWithBanner` và khai báo `BannerConfig` — base chạy trên `BannerAdHelper`; không tự load banner bằng tay.

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
| 1 | `initTracking()` | `Tracker.install(...)` + `Tracker.addSink(...)` — **bắt buộc đầu tiên**, xem mục 1.6 |
| 2 | `DevConfig.init(...)` | DevConfig UI — version libs ads (xem mục 1.3) |
| 3 | `initAdRemoteConfig()` | `AdRemoteConfig.initializeFromAssets(this)` |
| 4 | `initAds()` | `ERainAd` + rule resume/inter (xem mục 1.5) |
| 5 | `initOnboardKit()` | `OnboardingSdk.install(...)` — xem tài liệu OnboardKit |
| 6 | `ResumeAdsEntryRule.shouldShowWelcomeOnResume()` | Đăng ký `AppLifecycleObserver` nếu cần welcome flow |

**Không** tự gọi `MobileAds.initialize()`: `ERainAd.init()` → `Admob.init()` đã gọi rồi, kèm log
trạng thái từng adapter. Gọi lần hai chỉ tạo ra một cuộc đua vô ích với lần đầu.

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
   - `adjustConfig` — app token cùng các event token (xem bảng ở mục 1.5.1)
   - `facebookClientToken`
   - `intervalInterstitialAd`
   - `idAdResume`
4. Gọi `ERainAd.getInstance().init(this, mERainAdConfig)`.
5. Gọi `ERainTuning.install()` — **một lần**, ngay sau `ERainAd.init`.
   Nó ghim các cờ process-wide của module (`openActivityAfterShowInterAds`,
   `disableAdResumeWhenClickAds`). **Đừng tự set**: chúng thay đổi *ý nghĩa của callback*, nên bật
   tắt theo từng màn sẽ để process ở đúng trạng thái mà màn cuối cùng chết trong đó — và một màn
   chết giữa hai lần toggle thì để sai vĩnh viễn.
6. `AppOpenManager.getInstance().disableAppResumeWithActivity(...)` chỉ cho các màn **của app**.
   OnboardKit tự loại trừ màn của nó.

Snippet tham chiếu:
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
    // 0 = module không tự áp interval; xem §3.2
    mERainAdConfig.intervalInterstitialAd = 0
    // Id rỗng tự tắt app-resume — không bao giờ request với ad unit rỗng
    mERainAdConfig.idAdResume = ""

    ERainAd.getInstance().init(this, mERainAdConfig)
}
```

> Lưu ý: `initAdRemoteConfig()` vẫn cần gọi trước `initAds()`, và config remote vẫn được đồng bộ lại ở `SplashActivity` qua `RemoteConfigUtils.init(...)` + `AdRemoteConfig.initialize(...)`.

### 1.5.1 Adjust token — một token, một cửa

| Trường | Là gì | Để trống nghĩa là |
|---|---|---|
| `adjustToken` | App token lấy từ dashboard Adjust | Adjust không bao giờ khởi tạo; có log lỗi |
| `eventAdImpression` | Event token bắn ở **mọi** paid impression, chồng lên `Adjust.trackAdRevenue` | Bỏ qua event — đây là trường hợp bình thường |
| `eventNamePurchase` | Event token bắn khi purchase thành công | Bỏ qua doanh thu purchase, kèm cảnh báo |
| `fbAppId` | Meta app id để Adjust forward sang Meta | Campaign do Meta attribute sẽ rỗng trên Adjust |

Mỗi token là một id sáu ký tự sinh trên dashboard Adjust, **không phải** tên event. Token rỗng bị
chặn có chủ đích: `AdjustEvent("")` được nhận ở client, bị drop ở server, và doanh thu biến mất
không một tín hiệu nào. Chỉ có **một** chỗ đặt token impression — `adjustConfig.eventAdImpression`.
Mọi định dạng ads (interstitial, native, banner, rewarded, app-open) đều đọc đúng trường đó.

### 1.6 Tracking — `Tracker.install()` là bắt buộc

Đây là bước partner hay bỏ sót nhất, vì thiếu nó không có gì crash cả.

`ads` và `billingkit` **không** tự ghi analytics. Mọi impression, click và purchase chúng quan sát
được đều đẩy sang `Tracker` (của `trackkit`), rồi `Tracker` fan-out tới các sink bạn đăng ký. Không đăng ký sink nào
thì `Tracker` validate từng event xong đưa vào một danh sách rỗng — dữ liệu im lặng không bao giờ
tới đâu. `Tracker.install` có log cảnh báo khi chạy mà không có sink nào, nhưng cách sửa là nối dây
cho đúng:

```kotlin
private fun initTracking() {
    Tracker.install(
        this,
        TrackerConfig(
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            strictValidation = BuildConfig.DEBUG,   // sai taxonomy thì fail ở QA, không phải ở prod
            logLevel = if (BuildConfig.DEBUG) 2 else 1,
        ),
    )
    Tracker.addSink(FirebaseSink())                 // từ trackkit-firebase
    if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
}
```

Gọi **đầu tiên** trong `onCreate()`. Event phát trước `install()` được buffer chứ không mất, nhưng
sẽ được flush kèm dữ liệu của session đang chạy tại thời điểm install thực sự xảy ra.

Những gì tự có sẵn khi đã có sink — không cần một call site nào của bạn:

| Tín hiệu | Phát bởi | Tên event |
|---|---|---|
| Vòng đời ads | `ads`, theo từng ad unit | `ad_request`, `ad_loaded`, `ad_load_failed`, `ad_show`, `ad_show_failed`, `ad_click`, `ad_closed` |
| Paid impression + ad LTV | `ads`, từ callback paid-event của AdMob | `ad_impression`, `ad_revenue_total`, `ad_revenue_micro_flush`, `ad_revenue_d3`, `ad_revenue_d7` |
| Purchase | `billingkit`, từ callback billing | `iap_success` |
| Phễu first-open | `onboardkitorigin` | `fo_*` — xem tài liệu OnboardKit |

Hai thứ vẫn thuộc về bạn: kết quả UMP (`Tracker.setConsent(analytics, ads)` — gọi từ callback consent,
đúng một lần) và event sản phẩm riêng (`Tracker.track("...")`).

Adjust **không** phải sink và không cần nối dây ở đây. Nó là MMP, nằm trong `ads`, và được cấu hình
qua `adjustConfig` ở mục 1.5. Xem `trackkit/ARCHITECTURE.md` để hiểu vì sao lại tách như vậy.

---

## 2. Cơ chế load/show Ads theo vị trí

### 2.0 Một vị trí, nhiều ad unit id — waterfall

Một vị trí không phải một ad unit id, mà là **một danh sách có thứ tự**: tầng cao trước, all-price
cuối. `AdWaterfall` request từng id một và dừng ở id đầu tiên fill được, nên tầng thấp chỉ được hỏi
sau khi tầng trên nó đã fail.

```kotlin
AdWaterfall.loadNative(activity, adUnitIds, layoutRes, callback)
AdWaterfall.loadInterstitial(context, adUnitIds, callback)
AdWaterfall.loadReward(context, adUnitIds, callback)
```

Banner cũng rơi qua các tầng của nó: `BannerAdHelper` đi qua `BannerAdConfig(tiers, …)` từng id
một, và slot không bao giờ chớp giữa các tầng — banner đang sống vẫn nằm trên màn hình trong lúc
các tầng thấp hơn được thử.

Truyền một id thì nó chạy y như load thường. Id rỗng và id trùng bị loại, nên payload remote điền
thiếu không tạo lỗ hổng trong thứ tự. Mỗi bước bị chặn bởi `REQUEST_AD_TIMEOUT` (30 s): một tầng
không bao giờ trả lời cũng không thể treo các tầng dưới nó.

Đây là **đường load duy nhất**. `AdsManager` và OnboardKit đều đi qua nó, nên hai bên không thể lệch
nhau. Đừng gọi `ERainAd.loadNativeAdResultCallback` / `getInterstitialAds` với một `config.id` —
làm vậy là tiêu tầng all-price và không bao giờ chạm tầng cao.

#### Đặt tên tầng trong remote config

Mỗi tầng là một key riêng. Hậu tố chính là bậc thang:

| Key | Bậc |
|---|---|
| `native_lang_high` | tầng cao nhất, request đầu tiên |
| `native_lang_high1` … `native_lang_high9` | các tầng tiếp theo, theo thứ tự số |
| `native_lang` | all-price, luôn cuối cùng |

`AdRemoteConfig.tiersFor("native_lang")` giải bậc thang đó thành thứ tự request. Thêm một tầng cho
một vị trí là **thay đổi remote config, không phải thay đổi code** — khai key là nó vào waterfall.
Cố ý **không có `_medium`**: nó chỉ là `_high1` mang tên khác, và hai cách viết cho cùng một tầng là
cách nhanh nhất để payload khai cả hai.

Cần hơn mười tầng, hoặc thứ tự không phải cao→thấp? Đưa thẳng các id vào mảng `ids` của một key —
danh sách đó được lấy làm waterfall nguyên văn:

```json
"inter_splash": { "id": "…/allprice", "ids": ["…/high", "…/high1"], "isEnable": true }
```

> Mỗi vị trí phải có ad unit id riêng. Hai vị trí dùng chung một id thì không thể tách nhau trong
> báo cáo doanh thu: paid-event callback của AdMob chỉ biết ad unit, còn `PlacementRegistry` map nó
> về vị trí nào **request sau cùng**.

### 2.1 Splash
- Inter Splash:
  - Điều kiện: `AdRemoteConfig.tiersFor("inter_splash")` khác rỗng (có ít nhất một tầng đang bật) và có mạng.
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
  - `AdsManager.nativeHelper(activity, owner, "native_welcome", AdRemoteConfig.native_welcome, layout)`, gate UA từ `config.enableUaCheck`.
- Inter welcome:
  - `AdsManager.loadInterWelcome(...)`, `AdsManager.showInterWelcome(...)`.
  - Flow welcome được kích hoạt bởi `AppLifecycleObserver` nếu `ResumeAdsEntryRule.shouldShowWelcomeOnResume()` và `shouldDisplayForUa(AdRemoteConfig.inter_welcome.enableUaCheck)` cho phép.

### 2.5 Banner (normal / collapsible)
- Dùng `BaseActivityWithBanner`.
- `AdsManager.loadBanner(..., isCollapse = false)` => banner thường.
- `AdsManager.loadBanner(..., isCollapse = true)` => collapsible banner (expand/collapse theo SDK).
- Reload theo `reloadIntervalSeconds`.

### 2.6 Lớp helper của SDK — store theo placement & helper cho view (từ 2.0.0)

Toàn bộ *cơ chế* ads — cache, hết hạn, dedup request đang bay, gating, contract show — nằm trong
`com.ads.module.helper`. App chỉ giữ lại policy theo placement: key nào, preload lúc nào, show ở
đâu. `AdsManager` bên trong delegate xuống lớp này, và `ERainAdProvider` của OnboardKit là một
adapter mỏng trên cùng các store đó, nên mọi consumer dùng chung một cache với một bộ rule.

**Định dạng full-screen** là các store theo placement key. Mỗi placement buffer một ad, hết hạn GMA
1 giờ, show dùng một lần, và `onComplete` bắn **đúng một lần** trong mọi kết cục — một lần show
fail hoặc bị skip không bao giờ kẹt được màn hình:

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

**Định dạng view** giao view của mình đúng một lần; sau đó helper sở hữu tất cả — shimmer, reload
khi resume, ẩn khi đã purchase, teardown:

```kotlin
NativeAdHelper(activity, this, NativeAdConfig(config.waterfallIds, true, true, R.layout.native_home))
    .setNativeContentView(binding.frAds)
    // No shimmer handed over: the loading skeleton is derived from R.layout.native_home (§2.7)
    .setEnablePreload(true, "native_home")
    .also { it.placement = "native_home" }
    .requestAds(NativeAdParam.Request)

BannerAdHelper(activity, this, BannerAdConfig(config.waterfallIds, true, false))
    .attachInto(binding.frAds)
    .also { it.placement = "banner_home" }
    .requestAds(BannerAdParam.Request)
```

`NativeAdPreload` là buffer preload theo key đứng sau native helper (`preloadWithKey`,
`pollAdNative`, hỗ trợ buffer > 1). Set `placement` là helper tự báo telemetry `ad_request` / skip
với đúng bộ reason key chuẩn.

**Reload không nháy.** Shimmer thuộc về slot *rỗng*: nó chỉ hiện khi placement chưa có ad nào. Một
khi ad đã nằm trên màn hình, việc reload — theo timer, lúc resume, hay qua một mức sàn waterfall
thấp hơn — chạy im lặng bên dưới nó; ad đang sống vẫn giữ nguyên chỗ và creative mới chỉ thay thế
nó đúng khoảnh khắc fill được. Một lần reload không có fill thì chẳng đổi gì cả, nên một slot không
bao giờ có thể đi ad → shimmer → ad.

### 2.7 Auto-shimmer & style native do SDK sở hữu (từ 3.0.0)

**Skeleton loading tự suy từ chính layout ad.** `NativeAdHelper` inflate một bản copy tách rời
của layout placement, sơn mọi view thành khối xám (text chuyển transparent nên độ rộng giữ
nguyên; media có sàn 160dp; badge "Ad" và các view gắn tag `shimmer_keep` không bị đụng tới),
rồi bọc trong `ShimmerFrameLayout`. Skeleton chỉ được sinh **lazy** tại lần Loading đầu tiên
mà không có gì khác để hiện — user đã purchase, preload trúng ngay, hay call site tự cấp
shimmer đều không tốn một chi phí nào. Thứ tự ưu tiên:

1. `setShimmerLayoutView(view)` — shimmer app tự cấp; SDK không bao giờ đụng vào view này.
2. `setShimmerLayout(@LayoutRes)` — **mới trong 3.0.0**, inflate skeleton từ một layout resource.
3. `NativeAdConfig.autoShimmer` (mặc định `true`) — skeleton tự sinh như trên.
4. Không gì cả — slot để trống như trước 3.0.0.

API public: `NativeAdShimmer.from(context, layoutId)`, `NativeAdShimmer.prewarm(context, layoutId)`
— gọi ở splash để trả trước chi phí inflate lạnh một lần — và `NativeAdShimmer.TAG_SHIMMER_KEEP`.
Skeleton tự sinh tắt `autoStart` của shimmer (không animation nào chạy sau lưng một view GONE —
helper sở hữu start/stop), được sinh lại sau khi Fragment recreate view hay đổi container, và
`setShimmerLayoutView` / `setShimmerLayout` / `setNativeContentView` gọi giữa lúc load sẽ hiện
**và** animate bản thay thế.

> **Thay đổi hành vi:** các vị trí trước đây để trống trong lúc load giờ hiện skeleton.
> Opt-out theo placement: `nativeConfig.autoShimmer = false`.

**Style do SDK apply.** `NativeAdStyle(components, ctaHeightDp, ctaBackgroundColor,
ctaCornerRadiusDp = 20)` cùng enum `NativeComponent { ICON_HEADLINE, BODY, MEDIA, CTA }`
(map từ remote qua `fromKey(String)`). `NativeAdHelper.setNativeStyle(style)` apply nó lên
**cả** ad đã load (binder mặc định) lẫn skeleton tự sinh — parity hình học theo cấu trúc,
nên cú swap skeleton → ad không bao giờ xê dịch. Style land **sau** bind của Admob nên các
exclusion giữ nguyên. Không set style thì layout render đúng như XML của nó — không có gì
ngầm định.

Contract layout để reorder được: một `LinearLayout` dọc `@id/ad_container` chứa
`@id/block_icon_headline`, `@id/ad_body`, `@id/ad_media`, `@id/ad_call_to_action`. Layout
không có `ad_container` chỉ được toggle visibility tại chỗ (layout phẳng thì các view
icon/headline/advertiser được xử lý từng cái; badge "Ad" — `ad_icon` — không bao giờ bị ẩn).
`NativeAdStyler.applyLayout` / `applyAppearance` / `populate` là public — binder mặc định giờ
đi qua `NativeAdStyler.populate`, hành vi y hệt `ERainAd.populateNativeAdView` khi style là
null (method đó của `ERainAd` vẫn còn). `setAutoShimmerDecorator {}` là escape hatch để hậu
xử lý skeleton tự sinh vượt ngoài những gì `NativeAdStyle` diễn tả được.

Trong app tham chiếu, policy style — clamp CTA 36–52dp, parse chuỗi màu, map key remote —
nằm ở `AdUnitConfig.toNativeStyle()`, và factory duy nhất cho mọi placement native là
`AdsManager.nativeHelper(activity, lifecycleOwner, placement, config, layoutRes, bypassUaGate = false)`
— truyền `placement = null` cho slot dashboard/preview để lần load preview không làm bẩn
revenue attribution (`nativeDashboardHelper` đã bị xóa). Call site chỉ còn:

```kotlin
AdsManager.nativeHelper(this, this, "native_home", AdRemoteConfig.native_home, R.layout.native_home)
    .setNativeContentView(binding.frAds)
    .requestAds(NativeAdParam.Request)
// No shimmer <include>, no setShimmerLayoutView: the skeleton comes from R.layout.native_home
```

OnboardKit dùng cùng cơ chế: slot native lấy skeleton tự suy từ template layout đã resolve —
bốn layout `ob_shimmer_native_*` đã bị xóa và các màn không còn `<include>` shimmer nào.
Signature `OnboardingAdProvider.bindNative` không đổi: skeleton tự sinh tới đúng argument
shimmer của nó.

## 3. Điều kiện chung để Ads được load

Một ad chỉ load khi thỏa đủ các điều kiện, đánh giá tại đúng một chỗ — `AdGate.skipReason(...)`:
- `adUnitConfig.isUsable` — bật **và** có ít nhất một id khác rỗng.
- `!AdGate.isPurchased(...)` — câu trả lời từ port `Entitlement`; khi có mặt, `billingkit` tự cắm source.
- Có mạng.
- Với các vị trí bắt buộc gate organic: `AdGate.passesUaGate(config.enableUaCheck) == true`.

Nếu fail 1 điều kiện, native LiveData trả `null` để UI ẩn ad container, và lý do được báo đúng một
lần qua `AdTracking.skipped(...)` với bộ reason key không đổi
(`disabled_config`, `purchased`, `offline`, `ua_gate`).

### 3.1 Consent chặn mọi request

Không ad nào được request trước khi luồng consent trả lời — đây là luật policy, không phải cuộc đua.
Splash giải quyết và công bố câu trả lời một lần:

```kotlin
class SplashActivity : ObSplashActivity() {
    override suspend fun onConsentRequired(): Boolean = /* true khi được phép request ads */
}
```

Trả `false` — hoặc không trả lời trong `consentTimeoutMs` — sẽ gọi `OnboardingSdk.setCanRequestAds(false)`,
và mọi vị trí báo `consent_not_granted` thay vì load. Flow vẫn chạy, chỉ là chạy không ad.
`ConsentHandler` hiện form UMP **một lần mỗi process**, nên splash và các màn sau dùng chung một câu
trả lời thay vì hỏi hai lần.

### 3.2 Tần suất interstitial chỉ nằm ở một chỗ

Giữ `ERainAdConfig.intervalInterstitialAd = 0`. Interval của module nuốt interstitial im lặng —
caller không phân biệt được với một lần user đóng ad — nên tần suất do
`ob_ads_interstitial_interval_sec` sở hữu: chỉnh được từ remote và báo `interval_not_elapsed` khi
chặn. Hai cap cho một luật là loại bug không ai tìm ra trong một quý.

## 4. Chuẩn gate UA/organic theo từng vị trí (bắt buộc 100%)

> **Bắt buộc:** 100% các vị trí dưới đây **phải** đi qua gate UA/organic.  
> Từ 2.0.0 chỉ còn đúng **một** call gate — `AdGate.passesUaGate(config.enableUaCheck)` — thay cho
> các method `getShouldDisplay*` theo từng placement trước đây (đã xóa: chúng là mười một delegate
> một dòng giống hệt nhau, thêm placement là phải release SDK; call site thậm chí còn trượt sang
> nhầm tên).  
> Param truyền vào là `enableUaCheck` lấy từ config placement trong `ad_config.json` / `ad_config_debug.json` (map sang `AdUnitConfig.enableUaCheck`).  
> Đây là cờ organic/UA check (force organic theo config ads) — **không được hard-code `true/false`**, phải lấy từ config của đúng placement đang load/show.

### 4.1 Mapping chuẩn (theo `AdsManager`)

Mọi hàng đều là cùng một call — `AdGate.passesUaGate(config.enableUaCheck)`; chỉ khác cờ config
theo từng placement:

| Vị trí Ads | Default `enable_ua_check` trong ad_config.json | Method / chỗ dùng trong code |
|------------|:----------------------------------------------:|------------------------------|
| **NativeOnboardingFull1** | `true` | `AdsManager.nativeHelper(..., "native_onboarding_fullscreen_1_3", ...)` (+ chèn page full ở `OnBoardingActivity`) |
| **NativeOnboardingFull2** | `true` | `AdsManager.nativeHelper(..., "native_onboarding_fullscreen_1_4", ...)` (+ chèn page full ở `OnBoardingActivity`) |
| **NativeOnboardingNormal2** | `false` | `AdsManager.nativeHelper(..., "native_onboarding_1_4", ...)` |
| **NativeHome** | `false` | `AdsManager.nativeHelper(..., "native_home", ...)` |
| **NativePermission** | `false` | `AdsManager.nativeHelper(..., "native_permission", ...)` |
| **InterOnboarding** | `true` | `AdsManager.loadInterOnboarding` / `showInterOnboarding` |
| **NativeWelcomeBack** | `false` | `AdsManager.nativeHelper(..., "native_welcome", ...)` trong `WelcomeActivity` |
| **InterWelcomeBack** | `false` | `AppLifecycleObserver` (chuyển hướng màn Welcome, qua `AdGate.passesUaGate`) |
| **WidgetUninstall** | `false` | `OnBoardingActivity` widget shortcut; `nativeHelper` trong `SurveyActivity` / `ConfirmUninstallActivity` |

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
AdGate.passesUaGate(config.enableUaCheck)
```

| JSON key | Field Kotlin | Ý nghĩa |
|----------|--------------|---------|
| `enable_ua_check` | `AdUnitConfig.enableUaCheck` | Bật/tắt organic (UA) check cho **đúng** placement đó khi gọi gate |

### 4.4 Pattern bắt buộc khi load

```kotlin
AdsManager.nativeHelper(
    activity, lifecycleOwner,
    "native_onboarding_fullscreen_1_3",
    AdRemoteConfig.native_onboarding_fullscreen_1_3,
    layoutRes,
)
    .setNativeContentView(binding.frAds)
    .requestAds(NativeAdParam.Request)
```

Factory tự đưa `config.enableUaCheck` vào gate UA của helper, nên màn hình không thể quên
hay hard-code nó.

**Không đạt chuẩn nếu:**
- Bỏ qua gate ở bất kỳ vị trí nào trong bảng trên.
- Truyền `true/false` hard-code thay vì `config.enableUaCheck`.
- Tự implement lại check organic thay vì gọi `AdGate` — một gate, một sự thật.

## 5. Cơ chế Organic

Organic là cơ chế phân loại user từ Ads SDK / logic tăng trưởng để:
- Giảm tần suất hoặc tắt một số ad slot nhạy cảm với một nhóm user.
- Cân bằng retention, UX và revenue.
- Cho phép rule theo cohort mà không sửa từng màn hình.

Cách hoạt động trong app:
- App **không** tự tính organic bằng local rule.
- App gọi `AdGate.passesUaGate(enableUaCheck)` — phía sau là `ERainAd.shouldDisplayForUa` — với `enableUaCheck` lấy từ `ad_config`.
- Khi organic/cohort rule đổi, câu trả lời của gate đổi theo và ảnh hưởng trực tiếp load/show từng slot.
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

### 6.2 Native (qua AdsManager.nativeHelper)
```kotlin
AdsManager.nativeHelper(
    this, this, "native_welcome", AdRemoteConfig.native_welcome, R.layout.layout_native_welcome,
)
    .setNativeContentView(mBinding.frAds)
    .requestAds(NativeAdParam.Request)
// No observe/hide code, no shimmer <include>: the helper binds on fill, hides the slot on
// skip/fail, and derives the loading skeleton from the ad layout itself (see §2.7)
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

## 7. Mua hàng và paywall

Billing nằm trong `:billingkit` (vẫn là `com.ads.module.billing`); UI paywall nằm trong `:paykit`.
Việc tách làm hai là có chủ đích: `:billingkit` sở hữu `BillingClient` của Play và trao entitlement
cho `:ads` qua port `Entitlement` — `AdGate` bỏ qua mọi placement với lý do `PURCHASED` ngay khi
`AppPurchase.isPurchased` thành true. Một module thứ hai cũng tự quyết ai là premium sẽ phá gate đó.

Wiring tối thiểu. Trong `GlobalApp.onCreate()`, sau `initAds()`:

```kotlin
val config = payKitConfig {
    termsUrl = "https://example.com/terms"
    privacyUrl = "https://example.com/privacy"
    defaultPlacements = setOf(PaywallPlacement.AFTER_ONBOARDING, PaywallPlacement.SETTING)
    exitButtonDelayMs = 3_000
}.getOrElse { Log.e(TAG, "PayKit config rejected", it); return }

PayKit.install(this, config)
PayKit.configSource(FirebaseConfigSource())     // tuỳ chọn, từ :paykit-firebase
```

Trong Splash. `onInitBilling` chạy trước request ads đầu tiên và phải trả về ngay khi biết
entitlement, nên chỉ await đúng cái đó; document paywall tới checkpoint mới cần:

```kotlin
override suspend fun onInitBilling() {
    lifecycleScope.launch { PayKit.sync(timeoutMs = 3_000) }
    Billing.awaitReady(timeoutMs = 5_000)
}
```

Ở màn hình nào cần hiện paywall:

```kotlin
PayKit.launch(activity, PaywallPlacement.SETTING)
```

Các rule bắt buộc:

- **Không tự gọi `AppPurchase.initBilling`.** `PayKit.install` đã đăng ký catalogue lấy từ document
  paywall. `initBilling` huỷ client đang chạy, nên người gọi sau với danh sách sản phẩm khác sẽ âm
  thầm ghi đè.
- **Placement fail-closed.** Không có `defaultPlacements` và cũng không có `placements` từ document
  đã fetch thì không có gì hiện lên; document bundled chỉ là catalogue, không đặt placement.
  `PayKit.launch` cũng từ chối với user đã premium hoặc placement đang tắt — nó ghi log và báo
  `onFinished(Dismissed)` cho listener của lần gọi đó.
- **`iap_success` do `:billingkit` bắn**, đúng một lần, khi Play xác nhận. Đừng bắn event mua hàng
  từ paywall hay từ màn hình của bạn; như vậy là đếm doanh thu hai lần.
- **Gate ads vẫn dựa trên `AppPurchase.isPurchased`.** UI của bạn hãy đọc `PayKit.isPremium()` /
  `Billing.isPremium`; đừng nhân bản trạng thái premium vào preference riêng.
- Sản phẩm `consumable` bị engine billing consume và không bao giờ set entitlement, nên nó
  **không** tắt ads. Dùng `inapp` cho gói lifetime.

Tài liệu đầy đủ — schema config, placement, analytics, theming, escape hatch cho Compose và gate
OnboardKit: **[paykit/README.md](paykit/README.md)**.

## 8. Tài liệu tham chiếu bổ sung

- [Infinity UI Documentation — Language & Onboarding](https://interim-pink-4gmxxkfh.edgeone.app/) — UI, Remote Config và điều kiện hiển thị từng ad unit (đối chiếu tên method/placement với tài liệu này).
