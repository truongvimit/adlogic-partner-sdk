# Sơ đồ Load và Show Ad - Splash, Language, Onboarding, Welcome, Banner

## Luồng chính (Cold Start Flow)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           APP START                                     │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        SPLASH ACTIVITY                                   │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 1. Check Consent & Init RemoteConfig                              │  │
│  │    - Chưa xác nhận → Gọi Consent Flow của ConsentHandler          │  │
│  │    - Đã xác nhận → Tiếp tục loading RemoteConfig                  │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                │                                         │
│                                ▼                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 2. Initialize Configs & App Resume Ad                             │  │
│  │    - Chờ lấy cấu hình RemoteConfig (Tối đa: 3s)                   │  │
│  │    - Khởi tạo AdRemoteConfig từ RemoteConfig (hoặc Assets fallback)│  │
│  │    - Cấu hình & kích hoạt App Resume Ad (open_resume)              │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                │                                         │
│                                ▼                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 3. Check Inter Splash Ad                                         │  │
│  │    Điều kiện: AdRemoteConfig.inter_splash.isEnable                │  │
│  │               && isNetwork()                                      │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                │                                         │
│                    ┌────────────┴────────────┐                           │
│                    │                        │                           │
│                    ▼                        ▼                           │
│  ┌──────────────────────────┐  ┌──────────────────────────┐            │
│  │ YES: Load & Show         │  │ NO: Skip Inter Splash    │            │
│  │      Splash Interstitial │  │                          │            │
│  │  - Gọi loadSplash...     │  │                          │            │
│  │    (timeout 30s, min 5s) │  │                          │            │
│  │  - Nếu onAdLoaded():     │  │                          │            │
│  │    → Gọi load Native     │  │                          │            │
│  │      Language Ad trong   │  │                          │            │
│  │      background          │  │                          │            │
│  │      (native_language_1/2│  │                          │            │
│  │      dựa vào             │  │                          │            │
│  │      firstLanguage)      │  │                          │            │
│  │  - Khi Ad kết thúc/lỗi:  │  │                          │            │
│  │    → onNextAction()      │  │                          │            │
│  └──────────────────────────┘  └──────────────────────────┘            │
│                    │                        │                           │
│                    └────────────┬────────────┘                           │
│                                 │                                         │
│                                 ▼                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 4. Navigate (moveActivity)                                        │  │
│  │    - Hiện Language lần đầu? (firstLanguage || firstOnBoarding)    │  │
│  │      → LanguageActivity                                          │  │
│  │    - Không → MainActivity                                        │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
                                │
                    ┌───────────┴───────────┐
                    │                       │
                    ▼                       ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      LANGUAGE ACTIVITY                                  │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 1. Check From Setting                                             │  │
│  │    - Yes → Hide Ads Container (flAds), hiện nút Done ngay         │  │
│  │    - No → Áp dụng delay hiển thị nút Done (nếu cấu hình bật)      │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                │                                         │
│                                ▼                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 2. Load Ads sau 100ms delay (postDelayed)                         │  │
│  │    - Tải Native Language Click Ad: `native_language_1_click`      │  │
│  │      hoặc `native_language_2_click` (dựa trên firstLanguage)     │  │
│  │    - Nếu NOT fromSetting: tải Native Onboarding 1                 │  │
│  │      `native_onboarding_1_1` / `native_onboarding_2_1`            │  │
│  │      (dựa trên firstOnBoarding)                                  │  │
│  │      → Emit qua: AdsManager.nativeOnboarding1AdLive                │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                │                                         │
│                                ▼                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 3. Show Native Language Ad                                       │  │
│  │    - Đăng ký observe: AdsManager.nativeLanguageAdLive             │  │
│  │      (Đã được tải song song ở SplashActivity khi load Splash Inter)│  │
│  │    - Emit non-null & có mạng → populateNativeAdView vào flAds     │  │
│  │    - Emit null/không mạng → ẩn flAds                              │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                │                                         │
│                                ▼                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 4. User Click Chọn Ngôn Ngữ                                       │  │
│  │    - Hủy observe `nativeLanguageAdLive`                           │  │
│  │    - Đăng ký observe & hiển thị `nativeLanguageClickAdLive`        │  │
│  │    - Kích hoạt delay hiển thị nút Done (Done button)              │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                │                                         │
│                                ▼                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 5. Navigate (Click Done)                                          │  │
│  │    - From Setting? → MainActivity                                 │  │
│  │    - No → OnBoardingActivity (Đồng thời đặt firstLanguage = false)│  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     ONBOARDING ACTIVITY                                 │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 1. Load Ads sau 100ms delay (postDelayed)                         │  │
│  │    - Tải Native Onboarding 4: `native_onboarding_1_4 / 2_4`       │  │
│  │    - Tải Native Onboarding Full: `native_onboarding_fullscreen_`  │  │
│  │      `1_3 / 2_3` (Chỉ load nếu UaCheck qua                        │  │
│  │      getShouldDisplayNativeOnboardingFull1(config.enableUaCheck)) │  │
│  │    - Tải Inter Onboarding: `inter_onboarding`                     │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                │                                         │
│                                ▼                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 2. Initialize Onboarding Pages (ViewPager2)                       │  │
│  │    Cấu trúc trang (List of OnboardingItem):                       │  │
│  │    - Trang 1 (Indicator 0): nativeOnboarding1AdLive (Đã load tại  │  │
│  │      màn LanguageActivity)                                        │  │
│  │    - Trang 2 (Indicator 1): Không quảng cáo                       │  │
│  │    - Trang 3 (Indicator 2): Không quảng cáo                       │  │
│  │    - Trang Fullscreen (Chỉ add nếu mạng OK và config cho phép):   │  │
│  │      Hiển thị Native Fullscreen (nativeAdOnBoardingFullLive)      │  │
│  │    - Trang 4/Trang cuối (Indicator 3): nativeOnboarding4AdLive    │  │
│  │    → Tổng số trang là 4 (nếu tắt Full) hoặc 5 (nếu bật Full)      │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                │                                         │
│                                ▼                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 3. Show Ads trên từng trang (OnboardingPageFragment)              │  │
│  │    - Dựa vào cờ config của Item để observe LiveData tương ứng:     │  │
│  │      - Page 1 → AdsManager.nativeOnboarding1AdLive                │  │
│  │      - Page 4 → AdsManager.nativeOnboarding4AdLive                │  │
│  │      - Page Full → AdsManager.nativeAdOnBoardingFullLive          │  │
│  │    - Logic hiển thị:                                              │  │
│  │      - Nếu Fullscreen Ad loaded: Ẩn nội dung page, show ad full   │  │
│  │      - Nếu Normal Ad loaded: Hiện nội dung page kèm ad nhỏ ở dưới │  │
│  │      - Nếu Ad load thất bại (null): Chỉ hiện nội dung page        │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                │                                         │
│                                ▼                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 4. Navigate (Click Next trang cuối)                               │  │
│  │    - Đặt firstOnBoarding = false                                  │  │
│  │    - Show Inter Onboarding (`inter_onboarding` đã load từ trước)  │  │
│  │    - Kết thúc quảng cáo → MainActivity                            │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         MAIN ACTIVITY                                    │
└─────────────────────────────────────────────────────────────────────────┘
```

## Luồng Welcome / Resume (Mở lại app từ Background)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           APP ONSTART                                   │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     APPLIFECYCLEOBSERVER                                │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 1. Check Current Screen                                           │  │
│  │    - Màn hình hiện tại nằm trong listActivityDisableResume?        │  │
│  │      (Splash, Language, Onboarding, Welcome, SurveyActivity)       │  │
│  │      → YES: Bỏ qua (không hiển thị Welcome/Resume Ads)             │  │
│  │      → NO: Tiếp tục check các điều kiện tiếp theo                  │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                │                                         │
│                                ▼                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 2. Check Conditions & SDK Limits                                  │  │
│  │    - Cần hiển thị Welcome? (`shouldShowWelcomeOnResume() == true`) │  │
│  │    - Không có interstitial ad nào khác đang show                  │  │
│  │    - Người dùng chưa mua VIP/IAP                                  │  │
│  │    - Gating SDK cho phép: `getShouldDisplayInterWelcomeBack(...)`  │  │
│  │      (sử dụng cờ: AdRemoteConfig.inter_welcome.enableUaCheck)     │  │
│  │    → YES: Điều hướng sang WelcomeActivity                          │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        WELCOME ACTIVITY                                 │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 3. Load Ads tại onCreate() / initViews()                          │  │
│  │    - Tải Native Welcome: `AdsManager.loadNativeWelcome(...)`      │  │
│  │      (gated bởi `getShouldDisplayNativeWelcomeBack(enableUaCheck)`)│  │
│  │    - Tải Inter Welcome: `AdsManager.loadInterWelcome(...)`        │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                │                                         │
│                                ▼                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 4. Observe & Hiển thị Native Welcome                              │  │
│  │    - Observe `AdsManager.nativeWelcomeAdLive`                     │  │
│  │    - ad != null & có mạng → Render ad trong container (Large shimmer)│  │
│  │    - ad == null/mất mạng → Ẩn container quảng cáo                  │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                │                                         │
│                                ▼                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 5. Click Start Button                                             │  │
│  │    - Gọi `AdsManager.showInterWelcome(this)`                      │  │
│  │    - Khi quảng cáo đóng hoặc xảy ra lỗi → `finish()` WelcomeActivity│  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

## Cơ chế tự động nạp lại Banner (BaseActivityWithBanner)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    BaseActivityWithBanner.onCreate()                    │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 1. Load Banner Lần Đầu (loadBanner)                                     │
│    - Kiểm tra `shouldShowBanner()`:                                      │
│      - Bật cấu hình `isEnable` && chưa mua VIP && layout có `fr_banner` │
│        → YES: Show container `fr_banner`, gọi AdsManager.loadBanner()   │
│        → NO: Ẩn container `fr_banner`, dọn dẹp Handler, thoát.          │
│    - Nếu có quảng cáo & reloadIntervalSeconds > 0:                      │
│      - Tính toán: timeNeedReloadBanner = currentTime + interval * 1000  │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    BaseActivityWithBanner.onResume()                    │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 2. Kích Hoạt Tự Động Làm Mới (reloadBannerIfNeeded)                     │
│    - Điều kiện reload: Banner đang hiển thị và `reloadInterval` > 0     │
│      → YES: Bắt đầu post handler chạy định kỳ mỗi 2000ms (Runnable)    │
│      → NO: Dọn dẹp Handler.                                             │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 3. Vòng Lặp Kiểm Tra Định Kỳ (reloadBannerRunnable - mỗi 2s)            │
│    - Đã đến thời điểm reload? (`timeNeedReloadBanner < currentTime`)    │
│      → YES: Gọi lại `loadBanner()` nạp quảng cáo mới, cập nhật lại      │
│        `timeNeedReloadBanner` tiếp theo.                                │
│      → NO: Bỏ qua lần này, tiếp tục xếp hàng Runnable chạy lại sau 2s.  │
└─────────────────────────────────────────────────────────────────────────┘
```

## Bảng tổng hợp Ad Units

### SplashActivity - Ad Units Loaded

| Ad Unit | Config Key | Storage Location | Mục đích |
|---------|-----------|------------------|----------|
| Interstitial Splash | `inter_splash` | `AdsManager.interSplashAd` (Load & Show qua SDK) | Hiển thị ngay tại màn Splash trước khi vào ứng dụng |
| App Open Resume | `open_resume` | `AppOpenManager` | Hiện quảng cáo khi người dùng mở lại app từ background |
| Native Language | `native_language_1` / `native_language_2` | `AdsManager.nativeLanguageAdLive` | Tải trước trong callback `onAdLoaded()` của Splash Inter, hiển thị tại màn Language |

> [!IMPORTANT]
> Quảng cáo Native Language chỉ được tải trước tại Splash nếu cấu hình bật Splash Interstitial và được tải thành công. Nếu không, luồng tải sẽ không kích hoạt tại Splash.

### LanguageActivity - Ad Units Loaded

| Ad Unit | Config Key | Storage Location | Mục đích |
|---------|-----------|------------------|----------|
| Native Language Click | `native_language_1_click` / `native_language_2_click` | `AdsManager.nativeLanguageClickAdLive` | Hiển thị thay thế quảng cáo chính khi người dùng nhấn chọn ngôn ngữ |
| Native Onboarding 1 | `native_onboarding_1_1` / `native_onboarding_2_1` | `AdsManager.nativeOnboarding1AdLive` | Tải trước cho trang đầu tiên của màn Onboarding |

### OnBoardingActivity - Ad Units Loaded

| Ad Unit | Config Key | Storage Location | Mục đích |
|---------|-----------|------------------|----------|
| Native Onboarding 4 | `native_onboarding_1_4` / `native_onboarding_2_4` | `AdsManager.nativeOnboarding4AdLive` | Hiển thị tại trang thứ 4 của Onboarding (Indicator 3) |
| Native Onboarding Full | `native_onboarding_fullscreen_1_3` / `native_onboarding_fullscreen_2_3` | `AdsManager.nativeAdOnBoardingFullLive` | Hiển thị toàn màn hình tại trang Fullscreen Onboarding (nếu bật) |
| Interstitial Onboarding | `inter_onboarding` | `AdsManager.interOnboarding` | Hiển thị khi người dùng nhấn nút Next ở trang Onboarding cuối cùng |

### WelcomeActivity - Ad Units Loaded

| Ad Unit | Config Key | Storage Location | Mục đích |
|---------|-----------|------------------|----------|
| Native Welcome | `native_welcome` | `AdsManager.nativeWelcomeAdLive` | Hiển thị native ad dạng lớn shimmerNativeLarge trên màn Welcome |
| Interstitial Welcome | `inter_welcome` | `AdsManager.interWelcomeAd` | Hiển thị khi người dùng nhấn nút Start của Welcome screen |

### Banners - Ad Units Loaded

| Ad Unit | Config Key | Storage Location | Mục đích |
|---------|-----------|------------------|----------|
| Banner Home | `banner_home` / `banner_splash` | Ads SDK Internal View | Hiển thị banner thường hoặc collapsible banner ở dưới cùng các màn hình |

## Bảng điều kiện Show Ad

| Điều kiện | Kiểm tra | Code |
|-----------|----------|------|
| Ad Enabled | Cấu hình quảng cáo bật | `config.isEnable` |
| Not Purchased | Người dùng chưa mua VIP/IAP | `!AppPurchase.getInstance().isPurchased(context)` |
| Has Network | Thiết bị có kết nối mạng internet | `context.isNetworkAvailable()` / `activity.isNetwork()` |
| Config Loaded | RemoteConfig đã khởi tạo thành công | `AdRemoteConfig.isInitialized()` |
| SDK User Action Limit | Điều kiện giới hạn quảng cáo từ SDK | `ERainAd.getInstance().getShouldDisplay...` |

## Timeline Load và Show Ad

| Thời điểm | Màn hình | Hành động | Ad Unit | Trạng thái / Ghi chú |
|-----------|----------|-----------|---------|----------------------|
| **T0** | SplashActivity | Khởi động Consent & RemoteConfig | - | Chờ fetch RemoteConfig |
| **T1** | SplashActivity | Khởi tạo cấu hình quảng cáo & App Open | `open_resume` | Khởi tạo cấu hình thành công |
| **T2** | SplashActivity | Tải quảng cáo Interstitial Splash | `inter_splash` | Đang tải (Timeout 30s) |
| **T3** | SplashActivity | **Chỉ khi Splash Inter loaded**: Tải Native Language | `native_language_1/2` | Đang tải ngầm |
| **T4** | SplashActivity | Show Splash Interstitial | `inter_splash` | Hiển thị quảng cáo nếu sẵn sàng |
| **T5** | LanguageActivity | Tải Ads sau 100ms delay | `native_language_1/2_click`, `native_onboarding_1_1/2_1` | Đang tải trong background |
| **T6** | LanguageActivity | Hiển thị Native Language | `native_language_1/2` | Observe từ T3 và render nếu OK |
| **T7** | LanguageActivity | Click ngôn ngữ: Show Click Ad | `native_language_1/2_click` | Hủy observe ad chính, observe & show click ad |
| **T8** | OnBoardingActivity | Tải Ads sau 100ms delay | `native_onboarding_1_4/2_4`, `fullscreen_1_3/2_3`, `inter_onboarding` | Đang tải trong background |
| **T9** | OnBoarding - Page 1 | Observe + Hiển thị Native Onboarding 1 | `native_onboarding_1_1/2_1` | Render ad trên page 1 (được load từ T5) |
| **T10** | OnBoarding - Page 2 & 3 | Hiển thị nội dung thông thường | - | Không chứa quảng cáo |
| **T11** | OnBoarding - Page Full | Observe + Hiển thị Native Fullscreen | `fullscreen_1_3/2_3` | Trình diễn toàn màn hình (nếu được kích hoạt ở T8) |
| **T12** | OnBoarding - Page 4 | Observe + Hiển thị Native Onboarding 4 | `native_onboarding_1_4/2_4` | Render ad trên page cuối (được load từ T8) |
| **T13** | OnBoardingActivity | Click Next cuối: Show Inter Onboarding | `inter_onboarding` | Show ad (nếu ready), đóng quảng cáo chuyển tiếp MainActivity |
| **T14** | AppLifecycleObserver | Nhận diện app khởi động lại từ bg | `inter_welcome` | Kiểm tra điều kiện và chuyển sang WelcomeActivity nếu OK |
| **T15** | WelcomeActivity | Khởi tạo & Tải Ads tại onCreate() | `native_welcome`, `inter_welcome` | Tải song song Native Welcome và Inter Welcome |
| **T16** | WelcomeActivity | Observe + Hiển thị Native Welcome | `native_welcome` | Hiển thị ad dạng lớn (Large shimmer) nếu ready |
| **T17** | WelcomeActivity | Click Start: Show Inter Welcome | `inter_welcome` | Trình chiếu Inter Welcome (nếu ready), đóng màn hình để vào app |

## Chi tiết các bước Load và Show Ad

### 1. SplashActivity

**Cấu hình & Consent:**
- Khởi tạo `RemoteConfigUtils` và `ConsentHandler`.
- Nếu chưa đồng ý Consent và không ở chế độ Bypass, gọi màn hình xin quyền Consent. Khi kết thúc, gọi `loadingRemoteConfig()`.
- Chờ nạp RemoteConfig bằng `CountDownTimer` tối đa 3000ms. Sau đó, khởi tạo `AdRemoteConfig`.
- Cài đặt App Open Resume `open_resume` thông qua `AppOpenManager` nếu thỏa mãn `ResumeAdsEntryRule.shouldEnableOpenResume()`.

**Tải và hiển thị Splash Ad:**
- Nếu `inter_splash` được bật và có mạng:
  - Gọi SDK để nạp và trình chiếu Splash Interstitial (`loadSplashInterstitialAds`).
  - Trong callback `onAdLoaded()` (khi ad tải thành công và chuẩn bị hiển thị): Tải trước Native Language Ad (`native_language_1` hoặc `native_language_2` dựa trên `firstLanguage`) vào `nativeLanguageAdLive`.
  - Trong callback `onNextAction()` (khi kết thúc/lỗi/bỏ qua quảng cáo): Gọi `moveActivity()` để chuyển tiếp màn hình.
- Nếu không tải Splash Interstitial hoặc không có mạng:
  - Gọi trực tiếp `moveActivity()`.
- Ở phương thức `onResume()`, gọi `onCheckShowSplashWhenFail` với thời gian chờ 1000ms để đảm bảo không bị đứng màn hình khi ứng dụng quay trở lại từ trạng thái dừng.

### 2. LanguageActivity

**Cài đặt & Tải Ads:**
- Nhận biến `isFromSetting` và `fromSetting` qua Intent. Nếu là mở từ Cài đặt, ẩn khung quảng cáo (`flAds`) và vô hiệu hóa cơ chế delay nút Done.
- Sau 100ms từ khi giao diện hiển thị:
  - Tải Native Language Click Ad (`native_language_1_click / 2_click` dựa trên `firstLanguage`) thông qua `loadNativeLanguageClick(...)`.
  - Nếu không phải từ Cài đặt, gọi `initAds()` để tải Native Onboarding 1 (`native_onboarding_1_1 / 2_1`) qua `loadNativeOnboarding1(...)`.

**Hiển thị:**
- Observe `AdsManager.nativeLanguageAdLive` (đã được tải trước từ SplashActivity):
  - Khi có quảng cáo trả về (non-null), gọi `populateNativeAdView(...)` để đưa quảng cáo vào container `flAds` và ẩn shimmer.
  - Nếu null hoặc mất mạng, ẩn toàn bộ `flAds`.
- Khi người dùng chạm vào một dòng ngôn ngữ trong danh sách:
  - Hủy theo dõi `nativeLanguageAdLive`.
  - Đăng ký theo dõi `AdsManager.nativeLanguageClickAdLive`. Nếu có ad click sẵn sàng, hiển thị thay thế trong `flAds`.
  - Kích hoạt cơ chế hiển thị nút Done chậm sau một khoảng thời gian `timeDelayDoneButton` nếu cấu hình cho phép.
- Nhấn Done: Chuyển đến `MainActivity` (nếu mở từ Setting) hoặc `OnBoardingActivity` (và gán `firstLanguage = false`).

### 3. OnBoardingActivity

**Khởi tạo danh sách trang và Tải Ads:**
- Sau 100ms khởi tạo:
  - Tải Native Onboarding 4 (`native_onboarding_1_4 / 2_4`) thông qua `loadNativeOnboarding4(...)`.
  - Tải Native Onboarding Full (`native_onboarding_fullscreen_1_3 / 2_3`) thông qua `loadNativeOnboardingFull(...)`.
  - Tải Inter Onboarding (`inter_onboarding`) thông qua `loadInterOnboarding(...)`.
- Khởi tạo danh sách các Slide trong ViewPager2:
  - Slide 1: Bật cờ `isHasNativeOnPage1 = true`.
  - Slide 2: Slide thường.
  - Slide 3: Slide thường.
  - Slide Fullscreen (Chỉ được thêm vào danh sách nếu có mạng và UaCheck qua `getShouldDisplayNativeOnboardingFull1(config.enableUaCheck)` thỏa mãn): Bật cờ `isHasNativeFull = true`.
  - Slide 4 (Slide cuối): Bật cờ `isHasNativeOnPage4 = true`.

**Hiển thị trang (OnboardingPageFragment):**
- Theo dõi LiveData quảng cáo tùy theo trang:
  - Nếu trang có `isHasNativeOnPage1` -> Observe `nativeOnboarding1AdLive`.
  - Nếu trang có `isHasNativeOnPage4` -> Observe `nativeOnboarding4AdLive`.
  - Nếu trang có `isHasNativeFull` -> Observe `nativeAdOnBoardingFullLive`.
- Khi nhận được dữ liệu quảng cáo (LiveData):
  - Trang Fullscreen: Nếu quảng cáo sẵn sàng, ẩn toàn bộ UI nội dung slide, hiển thị quảng cáo chiếm toàn màn hình kèm nút Đóng quảng cáo. Nếu không sẵn sàng, hiển thị lại UI nội dung trang bình thường.
  - Trang thường (Page 1 & 4): Hiển thị nội dung trang kết hợp hiển thị quảng cáo Native dạng trung bình (`shimmerNativeMedium`) ở phía dưới.
  - Các trang không cấu hình quảng cáo: Chỉ hiển thị nội dung slide.

**Chuyển tiếp:**
- Khi nhấn nút Next ở Slide cuối cùng, đặt `firstOnBoarding = false`.
- Gọi `AdsManager.showInterOnboarding(this)` để hiển thị Interstitial Onboarding.
- Khi quảng cáo đóng hoặc xảy ra lỗi, mở `MainActivity` và hoàn tất màn hình.

### 4. WelcomeActivity

**Kích hoạt và Điều hướng:**
- Khi ứng dụng nhận sự kiện `onStart()` (khởi động lại từ Background) trong `AppLifecycleObserver`:
  - Kiểm tra xem màn hình hiện tại có bị loại trừ (Splash, Language, Onboarding, Welcome, Survey) hay không.
  - Nếu không bị loại trừ, chưa mua IAP, không có Inter nào đang hiển thị, cấu hình `shouldShowWelcomeOnResume() == true` và SDK đồng ý `getShouldDisplayInterWelcomeBack(enableUaCheck) == true`:
    - Chuyển hướng người dùng sang `WelcomeActivity` (đồng thời ngăn cản App Open Ad thông thường hiển thị đè lên).

**Load và Show trong WelcomeActivity:**
- Ngay khi onCreate() / initViews():
  - Gọi `AdsManager.loadNativeWelcome(...)` để tải Native Welcome (gated bởi SDK UA check).
  - Gọi `AdsManager.loadInterWelcome(...)` để tải Inter Welcome.
- Hiển thị:
  - Theo dõi LiveData `nativeWelcomeAdLive`. Khi quảng cáo sẵn sàng (non-null), gọi `populateNativeAdView` render vào container `frAds` (với shimmerNativeLarge). Nếu null hoặc mất mạng, ẩn container quảng cáo.
- Chuyển tiếp:
  - Khi người dùng nhấn nút Start (`btnStart`), gọi `AdsManager.showInterWelcome(this)`. Khi quảng cáo kết thúc hoặc lỗi, gọi `finish()` đóng WelcomeActivity để đưa người dùng trở lại màn hình trước đó.

## Luồng LiveData

```
SplashActivity
  └─> loadSplashInterstitialAds()
        └─> onAdLoaded() ──> loadNativeLanguage()
                                └─> AdsManager.loadNativeInternal()
                                      └─> Cập nhật vào nativeLanguageAdLive

LanguageActivity
  ├─> Observe nativeLanguageAdLive (Được load từ SplashActivity)
  │     └─> non-null ──> populateNativeAdView()
  │     └─> null ──> Ẩn flAds
  ├─> Đợi 100ms ──> loadNativeLanguageClick() ──> Cập nhật nativeLanguageClickAdLive
  ├─> Đợi 100ms ──> loadNativeOnboarding1() ──> Cập nhật nativeOnboarding1AdLive
  └─> Click chọn ngôn ngữ:
        └─> Hủy observe nativeLanguageAdLive
        └─> Observe & hiển thị nativeLanguageClickAdLive

OnBoardingActivity
  ├─> Đợi 100ms ──> loadNativeOnboarding4() ──> Cập nhật nativeOnboarding4AdLive
  ├─> Đợi 100ms ──> loadNativeOnboardingFull() ──> Cập nhật nativeAdOnBoardingFullLive
  ├─> Đợi 100ms ──> loadInterOnboarding() ──> Lưu trữ interOnboarding
  └─> OnboardingPageFragment đăng ký nhận ad tương ứng từ LiveData:
        ├─> Trang 1 ──> Observe nativeOnboarding1AdLive (Đã load từ LanguageActivity)
        ├─> Trang Full ──> Observe nativeAdOnBoardingFullLive
        └─> Trang 4 ──> Observe nativeOnboarding4AdLive

WelcomeActivity
  ├─> onCreate ──> loadNativeWelcome() ──> Cập nhật nativeWelcomeAdLive
  │                   └─> Observe nativeWelcomeAdLive & render bằng shimmerNativeLarge
  └─> onCreate ──> loadInterWelcome() ──> Lưu trữ interWelcomeAd
```

## Điều kiện Show Ad

- **Ad Enabled**: `AdUnitConfig.isEnable == true`
- **Not Purchased**: `AppPurchase.getInstance().isPurchased() == false`
- **Has Network**: `isNetworkAvailable() == true`
- **Config Loaded**: RemoteConfig đã fetch thành công hoặc dùng default từ Assets
