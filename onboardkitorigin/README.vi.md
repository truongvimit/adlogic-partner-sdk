**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# OnboardKit

> Toàn bộ luồng mở app lần đầu, đóng gói thành thư viện: splash → chọn ngôn ngữ → các bước onboarding → quảng cáo full-screen tuỳ chọn → câu hỏi tuỳ chọn → app của bạn.

Quảng cáo, remote config, lưu trạng thái và funnel analytics đã nằm sẵn bên trong. Bạn chỉ cung cấp
ad unit id, nội dung hiển thị, và điểm đến khi luồng kết thúc.

## Requirements

| | |
|---|---|
| minSdk / compileSdk / JDK | 24 / 36 / 17 |
| Namespace, tiền tố resource, entry point | `io.onboardkit`, `ob_`, `OnboardingSdk` |
| Firebase | `google-services.json` + `com.google.gms.google-services`; thiếu thì mọi key `ob_*` giữ nguyên giá trị mặc định |
| Ad unit id | `assets/ad_config.json` qua `AdRemoteConfig`, hoặc ghi thẳng trong `AdsConfig` |

## Installation

```groovy
// Replace <tag> with a tag from https://github.com/truongvimit/adlogic-partner-sdk/tags
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

Phải khai báo `:ads` tường minh — bên trong module này nó là dependency `implementation`, nên nếu không
khai báo thì `com.ads.module.*` không có trên compile classpath của bạn. `:trackkit` đã export bằng `api`,
`consumer-rules.pro` đi kèm module, và bốn activity của SDK đã nằm trong manifest thư viện — đừng khai báo lại.

## Quick start

### 1. `Application.onCreate()`

`Tracker.install()` trước tiên — event phát sớm hơn chỉ được buffer. `OnboardingSdk.install()` trước
`configure()` — config truyền vào trước khi install sẽ bị bỏ, và cả luồng sau đó sẽ skip.

```kotlin
override fun onCreate() {
    super.onCreate()
    initTracking()                                    // Tracker.install + Tracker.addSink
    AdRemoteConfig.initializeFromAssets(this)         // assets/ad_config.json
    AdConfig.install(FirebaseAdConfigSource())        // optional: remote ad config
    ConsentCenter.configure(ConsentOptions(timeoutMs = 20_000, testDeviceHashedId = "…"))
    val adConfig = ERainAdConfig(this)                // fill its fields: see ../ads/README.md
    ERainAd.getInstance().init(this, adConfig)
    ERainTuning.install()                             // once, after ERainAd.init

    OnboardingSdk.install(this) {
        adProvider = ERainAdProvider()                // null for an ad-free flow
        paywallGate = MyPaywallGate()                 // optional
        listener = OnboardingListener { ctx, outcome -> goToMain(ctx, outcome) }
    }
    OnboardingSdk.configure(buildConfig()).onFailure { Log.e("OnboardKit", "rejected", it) }
    OnboardingSdk.setFlowLogging(BuildConfig.DEBUG)   // OB_FLOW logcat; on by default
}
```

Listener phải điều hướng cho cả `OnboardingOutcome.Completed`, `Skipped` **và** `Aborted` — không đăng ký listener thì outcome bị bỏ. `Completed.selectedLanguage` mang ngôn ngữ đã chọn; `OnboardingSdk.selectedLanguage()` đọc lại giá trị đó về sau.

### 2. Config

```kotlin
private fun buildConfig() = onboardKitConfig {
    splash = SplashConfig(logoRes = R.drawable.ic_logo, minDisplayTimeMs = 3_000)
    language = LanguageConfig(defaultCode = "en")
    defaultSteps()                                    // OB1, OB2, OB3 (ad-only), OB4
    question = QuestionConfig(options = listOf(QuestionOption("romance", "Romance")))
    ads = AdsConfig(
        splashBanner         = BannerAdUnit("ca-app-pub-…/1111"),
        splashInterstitial   = InterstitialAdUnit("ca-app-pub-…/2222"),
        languageNative       = NativeAdUnit.waterfall(highFloor = "…/3333", allPrice = "…/4444"),
        contentStepNative    = NativeAdUnit("ca-app-pub-…/5555"),
        fullScreenStepNative = NativeAdUnit("ca-app-pub-…/6666"),
    )
}.getOrThrow()
```

Thay vì `defaultSteps()`, bạn liệt kê bước của mình bằng `steps(vararg StepDefinition)` hoặc `step(…)`.
Thứ tự trong danh sách là thứ tự hiển thị; remote config chỉ tắt được một bước, không sắp xếp lại.

### 3. Splash

Launcher activity của bạn kế thừa `ObSplashActivity`. Consent, billing, remote fetch, request quảng cáo,
thời gian hiển thị tối thiểu, interstitial và điều hướng đều nằm bên trong; bạn chỉ điền vào các hook.

```kotlin
class SplashActivity : ObSplashActivity() {
    override suspend fun onInitBilling() { myEntitlement.awaitReady() }  // resolve premium first

    override fun onRemoteFetched() {
        // fetch your app's own remote keys here
        OnboardingSdk.configure(buildConfig())   // rebuild: remote may have changed ad unit ids
    }
}
```

Một entry mở thẳng vào feature — tap notification hay widget — phải giữ màn đích lại tới khi ad splash đóng,
nếu không màn mở feature sẽ chồng lên ad và che mất impression. Quyết định thuộc về lần khởi chạy, nên đây là
hook chứ không phải field config:

```kotlin
override fun nextScreenTiming(): NextScreenTiming =
    if (intent.hasExtra(EXTRA_WIDGET_ACTION)) NextScreenTiming.AFTER_AD
    else NextScreenTiming.UNDER_AD
```

Khai báo nó với `android:exported="true"`, một filter MAIN/LAUNCHER và theme AppCompat/MaterialComponents.
Đừng override `onConsentRequired()` — mặc định của nó chạy luồng UMP qua `ConsentCenter` trong `:ads`;
chỉ override để `return true` khi app không có bước consent. Đừng gọi `OnboardingSdk.start()` ở đây, nó
tự chạy khi pipeline hoàn tất. Nếu override `onDestroy()`, nhớ gọi `super.onDestroy()` —
`ConsentCenter.detach(this)` nằm ở đó. Về sau: `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)`.

## Configuration

| Config | Trường | Kiểu | Mặc định |
|---|---|---|---|
| `SplashConfig` | `layoutRes` / `logoRes` / `appNameRes` | `@LayoutRes` / `@DrawableRes` / `@StringRes Int` | `0` = layout của SDK / icon app / tên app |
| | `minDisplayTimeMs` / `remoteFetchTimeoutMs` / `consentTimeoutMs` / `billingTimeoutMs` | `Long` | `3_000` / `10_000` / `15_000` / `5_000` |
| | `adLoadStrategy` | `AdLoadStrategy` | `ALTERNATE`; `SAME_TIME` load quảng cáo ngay trong lúc fetch |
| `LanguageConfig` | `languages` / `defaultCode` | `List<ObLanguage>` / `String?` | `ObLanguages.ALL` (21 ngôn ngữ, kèm cờ) / `null` |
| | `secondNativeOnSelectEnabled` / `tapHintEnabled` / `confirmVisibleBeforeSelect` | `Boolean` | `true` |
| `BehaviorConfig` | `lockPagerSwipe` / `backNavigatesBack` / `reloadAdOnStepReturn` | `Boolean` | `true` / `true` / `false` |
| `SystemBarConfig` | `showStatusBar` / `showNavigationBar` | `Boolean` | `true` |
| `QuestionConfig` | `titleRes` / `ctaTextRes` / `title` | `@StringRes Int` / `CharSequence?` | `0` / `0` / `null` |
| (`null` là bỏ màn này) | `options` — `QuestionOption(id, title, titleRes, imageRes, imageUrl)` | `List<QuestionOption>` | `emptyList()`; rỗng cũng bỏ luôn màn hình |
| | `selectionMode` / `minSelection` / `refreshAdOnSelect` | `SelectionMode` / `Int` / `Boolean` | `MULTIPLE` / `1` (≥ 1) / `false` |

**Steps.** `ContentStepDefinition(id, titleRes = 0, subtitleRes = 0, title = null, subtitle = null, imageRes = 0,
layoutRes = 0, showsProgressIndicator = true)` và `AdFullScreenStepDefinition(id, showSkipButton = true,
skipButtonDelaySec = 3, autoNextEnabled = false, autoNextDelayMs = 15_000, layoutRes = 0)`. `id` là một `StepId`:
`OB1`…`OB5` — đây là vị trí trong luồng, không phải trang nội dung: OB3 là trang chỉ có quảng cáo của template mặc định, nên trang *nội dung* thứ ba là `StepId.OB4`.

**AdsConfig.** Slot để `null` thì không hiện quảng cáo. Mọi slot native/interstitial đều là waterfall: id xếp theo floor cao trước, request lần lượt từng id, dừng ở lần fill đầu tiên.

| Trường | Kiểu | Mặc định |
|---|---|---|
| `enabled` / `skipAdOnlyStepsWhenPremium` | `Boolean` | `true` — công tắc tổng / premium bỏ qua các bước chỉ có quảng cáo |
| `splashBanner` | `BannerAdUnit?` | `null` |
| `splashInterstitial` / `splashInterstitialOldUser` | `InterstitialAdUnit?` | `null`; old-user fallback về cái còn lại |
| `languageNative` / `languageDupNative` | `NativeAdUnit?` | `null`; dup fallback về `languageNative` |
| `contentStepNative`, `fullScreenStepNative`, `ob5Native`, `questionNative` | `NativeAdUnit?` | `null` |
| `stepNatives` | `Map<StepId, NativeAdUnit>` | `emptyMap()` — override theo từng trang cho `contentStepNative` / `fullScreenStepNative`; `stepNatives[OB5]` cũng đứng sau `ob5Native` |
| `questionInterstitial`, `appResume` | `InterstitialAdUnit?` | `null` |
| `contentStepTemplate` / `languageTemplate` / `questionTemplate` | `NativeTemplate` | `CTA_BOTTOM` |

**Native template.** Template chọn layout; `components` trong `ad_config.json` của app chọn khối nào hiện và
theo thứ tự nào.

| `NativeTemplate` | Layout | `components` điều khiển |
|---|---|---|
| `CTA_BOTTOM` | `ob_layout_native_cta_bottom` | thứ tự + ẩn/hiện |
| `COMPACT` | `ob_layout_native_compact` | chỉ ẩn/hiện — bố cục 2 hàng ngang, không phải stack dọc |
| `FULL_SCREEN` | `ob_layout_native_fullscreen` | chỉ ẩn/hiện — text đè lên media, không phải stack dọc |

`onboardKitConfig { }` trả về `Result.failure(ObConfigException)` khi: trùng `StepId`; `minSelection < 1`;
trùng id của question option; danh sách ngôn ngữ rỗng; một tier list toàn giá trị rỗng, chứa id rỗng hoặc lặp id; `splashBanner.id` rỗng; hoặc set bất kỳ knob nào trong năm knob `layoutRes` bị từ chối.

**Bước chỉ có quảng cáo.** Một trang `AdFullScreenStepDefinition` bị loại khỏi luồng khi placement của nó
chắc chắn không thể fill: không có ad unit, `enabled = false`, cờ remote tổng hoặc cờ remote của placement đang tắt,
không có provider, hoặc consent chưa được trả lời. Số bước, progress indicator và chỉ số resume đều co lại theo.
Premium không phải lý do loại bỏ — nó đi theo `skipAdOnlyStepsWhenPremium`. Một trang đã vào luồng rồi mới
fail fill thì thoát qua `StepHost.skipAdStep(stepId)`.

## Custom layouts

Chỉ `SplashConfig.layoutRes` và `ContentStepDefinition.layoutRes` được màn hình đọc. Năm knob `layoutRes`
còn lại bị từ chối — hãy để `0` và override layout cùng tên của SDK, giữ nguyên mọi id mà nó khai báo.

| Knob bị từ chối | Override layout này thay thế |
|---|---|
| `LanguageConfig.layoutRes` / `.itemLayoutRes` | `ob_activity_language.xml` / `ob_item_language.xml` |
| `QuestionConfig.layoutRes` / `.optionLayoutRes` | `ob_activity_question.xml` / `ob_item_question_option.xml` |
| `AdFullScreenStepDefinition.layoutRes` | `ob_fragment_ad_step.xml` |

- Splash bind từng id theo kiểu null-safe, nên id nào bạn bỏ đi thì nó bỏ qua id đó.
- Layout của content step phải có **đủ** mọi id, nếu không trang đó rơi về layout của SDK kèm một dòng log.
- Native template (`ob_layout_native_*`) dùng id chuẩn của AdMob; giữ đúng những id mà template bạn override đã khai báo.

| Màn hình | Id | Kiểu |
|---|---|---|
| Splash | `ob_splash_logo` / `ob_splash_app_name` / `ob_splash_progress` | `ImageView` / `TextView` / `ProgressBar` |
| | `ob_splash_ad_container` | `FrameLayout`; đặt `<include layout="@layout/layout_banner_control" />` bên trong, nếu không banner splash không có chỗ để gắn |
| Content step | `ob_step_image` / `ob_step_player` / `ob_step_card` | `ImageView` / `androidx.media3.ui.PlayerView` / `LinearLayout` |
| | `ob_step_title` / `ob_step_subtitle` / `ob_step_indicator` / `ob_primary_cta` | `TextView` / `TextView` / `ObStepIndicator` / `ObPrimaryButton` |
| | `ob_ad_block` / `ob_native_container` | `FrameLayout` (block bị ẩn khi slot bị từ chối) / `FrameLayout` |

Với màn hình của riêng bạn nằm trong luồng, `showInterstitial(placement, onNext, onFinished)` là extension
public trên `AppCompatActivity`: mở màn đích trong `onNext` (nằm dưới quảng cáo), đóng màn hiện tại trong
`onFinished`. Cả hai chạy tối đa một lần, `onNext` luôn chạy trước, trên mọi nhánh; đừng bao giờ gọi
`finish()` từ `onNext`. Không có API native tương đương công khai — hãy tự render native bằng
`NativeAdHelper` trong `:ads`.

## Remote config keys

Giá trị mặc định nằm trong `ObRemoteKeys`; không publish gì thì giữ nguyên các mặc định dưới đây.

| Key | Kiểu | Mặc định | Tác dụng |
|---|---|---|---|
| `ob_enable_all_ads` / `ob_enable_ui_content` | Boolean | `true` | Công tắc tắt toàn bộ quảng cáo / bật tắt UI điều khiển từ server |
| `ob_enable_step_ob1` … `ob_enable_step_ob4` | Boolean | `true` | Bật tắt một bước |
| `ob_enable_step_ob5` | Boolean | `false` | Màn quảng cáo full-screen độc lập sau pager |
| `ob_enable_question` / `ob_enable_question_old_user` | Boolean | `true` / `false` | Khảo sát cho user mới / cho user đã hoàn thành luồng |
| `ob_enable_language_native_2` / `ob_pass_lfo_if_completed` | Boolean | `true` | Native thứ hai khi chạm ngôn ngữ lần đầu / bỏ màn ngôn ngữ khi đã chọn ngôn ngữ |
| `ob_show_language_tap_hint` / `ob_show_language_confirm_before_select` | Boolean | `true` | Gợi ý bàn tay / nút xác nhận trước khi chọn; mỗi cái AND với trường tương ứng trong `LanguageConfig` |
| `ob_language_supported_codes` | String | `""` | Lọc và sắp thứ tự bằng CSV; rỗng = toàn bộ danh mục |
| `ob_reuse_splash_inter` | Boolean | `true` | Dùng lại interstitial splash còn trong buffer ở cuối pager |
| `ob_ads_splash_banner_enabled`, `ob_ads_splash_inter_enabled`, `ob_ads_language_native_enabled`, `ob_ads_content_native_enabled`, `ob_ads_fullscreen_native_enabled`, `ob_ads_question_native_enabled`, `ob_ads_question_inter_enabled`, `ob_ads_app_resume_enabled` | Boolean | `true` | Mỗi placement một công tắc, đều AND với `ob_enable_all_ads` |
| `ob_splash_min_display_ms` / `ob_splash_ad_budget_ms` / `ob_splash_banner_wait_ms` | Long | `3000` / `60000` / `0` | Ghi đè `SplashConfig.minDisplayTimeMs` khi > 0 / ngân sách cho cả waterfall của interstitial splash (30 s mỗi tầng) / splash chờ banner bao lâu trước tiên |
| `ob_skip_button_delay_sec` / `ob_fullscreen_auto_dismiss_sec` | Long | `3` / `15` | Ghi đè `AdFullScreenStepDefinition.skipButtonDelaySec` / auto-dismiss của OB5, chặn sàn ở 5 (trang trong pager dùng `autoNextDelayMs`) |
| `ob_show_skip_ob3` / `ob_show_skip_ob5` | Boolean | `true` | Nút skip trên trang pager chỉ có quảng cáo / trên OB5 |
| `ob_ui_content` / `ob_ui_design_tokens` | String | `""` | JSON theo từng bước (title, subtitle, màu, ảnh hoặc video) và token màu/typography của nó |
| `ob_question_config` / `ob_config_version` | String / Long | `""` / `0` | JSON thay toàn bộ danh sách option biên dịch sẵn / đổi giá trị để xoá cache cục bộ |

`ob_ads_splash_banner_enabled`, `ob_ads_splash_inter_enabled`, `ob_ads_app_resume_enabled`, `ob_splash_ad_budget_ms`
và `ob_splash_banner_wait_ms` không được cache cục bộ: ở cold start trước khi fetch về, chúng đọc ra giá trị mặc định.

## Analytics events

Tự động phát khi đã nối `Tracker.install()` và một `Tracker.addSink(...)`. Định danh event là `StepId`,
không bao giờ là chỉ số trang trong pager.

| Giai đoạn | Event |
|---|---|
| Flow | `fo_flow_start` (phát cả khi luồng bị skip), `fo_flow_complete` |
| Splash | `fo_splash_view`, `fo_splash_complete` |
| Language | `fo_language_view`, `fo_language_select`, `fo_language_complete`, `fo_language_flow_complete` |
| Steps | `fo_step_view`, `fo_step_complete` (`step`, `index`, `exit_reason` = `cta` / `skip` / `auto_next` / `ad_failed` / `auto_dismiss`) |
| Question | `fo_question_view`, `fo_question_answer`, `fo_question_complete` |
| Ads | `ad_request`, `ad_show`, `ad_load_failed`, `ad_skipped` (`reason`) |
| Paywall, screens | `iap_paywall_view`, `iap_paywall_result`; mỗi màn hình SDK một `Tracker.screen(...)` |

Các lý do của `ad_skipped`: `premium`, `consent_not_granted`, `ads_off_config`, `no_provider`, `no_ad_unit`, `ads_off_remote`, `placement_off_remote`, `no_fill`, `not_ready`, `offline`, `ua_gate`, `capped_by_module`, `purchased_at_paywall`, `suppressed_by_flow`, `returning_from_ad_click`, `failed_to_show`. (`no_handshake` đã ngừng phát sinh.)

Muốn tự nhận event thì thêm `analyticsPlugin { event -> log(event.name, event.params) }` bên trong `install`,
hoặc collect `OnboardingSdk.events` / `.state`. Plugin thấy tên event `ob_*` của chính SDK, không phải taxonomy
`fo_*` ở trên — taxonomy đó chỉ tồn tại ở phía `Tracker`. `isCompleted()`, `selectedLanguage()`, `answers()`,
`markCompleted()` và `reset()` đọc và xoá tiến độ đã lưu.

## Paywall gate

```kotlin
class MyPaywallGate : PaywallGate {
    override suspend fun shouldShow(placement: PaywallPlacement) =
        placement == PaywallPlacement.AFTER_ONBOARDING && !myEntitlement.isPremium

    override suspend fun present(activity: Activity, placement: PaywallPlacement): PaywallOutcome =
        PaywallOutcome.Dismissed   // or Purchased / ContinueWithAds
}
```

Các placement: `SPLASH_INTER`, `AFTER_ONBOARDING`, `AFTER_QUESTION_OLD_USER`. Để `paywallGate` trống thì
mọi checkpoint đi thẳng qua.

## Troubleshooting

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| Luồng không bao giờ chạy | `configure()` fail, hoặc chạy trước `install()` | Log cái `Result`; gọi `install()` trước |
| User không bao giờ thoát khỏi luồng | Thiếu `OnboardingListener`, hoặc listener bỏ qua `Skipped` | Xử lý cả ba outcome |
| Mọi placement báo `no_provider` | `adProvider` để null | `adProvider = ERainAdProvider()` |
| Mọi placement báo `consent_not_granted` | Form UMP chưa được trả lời trong `consentTimeoutMs` | Set `ConsentOptions(testDeviceHashedId = …)` |
| Trang chỉ có quảng cáo không bao giờ hiện | Không có unit dùng được cho `fullScreenStepNative` / `stepNatives[OB3]` | Cấu hình một cái; chỉ mình `ob_enable_step_ob3` là không đủ |
| Banner splash không bao giờ hiện | Thiếu `ob_splash_ad_container` hoặc thiếu include `layout_banner_control` | Thêm cả hai vào layout splash của bạn |

## License

MIT — xem [`../LICENSE`](../LICENSE).
