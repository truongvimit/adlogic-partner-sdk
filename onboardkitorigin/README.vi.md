# OnboardKit (VI)

> English: **[README.md](README.md)** · हिन्दी: **[README.hi.md](README.hi.md)**

Luồng first-open đóng gói thành thư viện: splash → language → các step onboarding → màn ads
full-screen tuỳ chọn → màn câu hỏi tuỳ chọn → vào app của bạn. Ads, remote config, lưu trạng thái,
phễu analytics và mọi bảo đảm kiểu "user không được kẹt ở đây" đều nằm bên trong. Bạn chỉ cung cấp ad
unit id, nội dung, và điểm đến khi luồng kết thúc.

- Namespace `io.onboardkit` · tiền tố resource `ob_` · entry point `OnboardingSdk`
- Ads đi qua interface `OnboardingAdProvider`. `ERainAdProvider` là cầu nối sang `:ads`
  (ERainAd/AdMob); bạn có thể truyền implementation của mình, hoặc `null` để chạy luồng không ads.
- Analytics đi qua `Tracker` của `:trackkit`. Nối một sink là cả phễu tự báo cáo.

**Đọc cả [`../trackkit/README.vi.md`](../trackkit/README.vi.md).** Không có `Tracker.install()` kèm
một sink thì mọi event SDK này phát ra đều bị validate rồi vứt đi.

---

## 1. Cài đặt Gradle

```groovy
// Thay <tag> bằng một tag tại https://github.com/truongvimit/adlogic-partner-sdk/tags
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"          // cho ERainAdProvider
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit-firebase:$sdkVersion"
}
```

Giữ mọi module trên cùng một tag — chúng publish cùng nhau và không được test chéo giữa các version.

`onboardkitorigin` phụ thuộc `ads` ở runtime scope, nên `com.ads.module.*` **không** nằm trên compile
classpath của bạn qua nó — phải khai `ads` tường minh nếu bạn khởi tạo `ERainAdProvider` hoặc gọi API
ads trực tiếp.

Yêu cầu JDK 17, `minSdk` 24. Bốn activity của luồng đã khai trong manifest của thư viện và tự merge;
bạn **không** cần thêm vào manifest của mình.

---

## 2. Tích hợp qua bốn bước

### 2.1 `Application.onCreate()` — install

Thứ tự quan trọng. `Tracker.install()` phải trước: event phát trước nó được buffer chứ không mất,
nhưng sẽ được gán vào session đang chạy tại thời điểm install thực sự xảy ra.

```kotlin
override fun onCreate() {
    super.onCreate()

    initTracking()        // Tracker.install + addSink — xem trackkit/README.vi.md
    initAds()             // ERainAd.getInstance().init(this, config)
    initOnboardKit()      // bên dưới
}

private fun initOnboardKit() {
    OnboardingSdk.install(this) {
        adProvider = ERainAdProvider()        // hoặc null nếu không dùng ads
        paywallGate = MyPaywallGate()         // tuỳ chọn, xem mục 6
        listener = OnboardingListener { ctx, outcome ->
            if (outcome is OnboardingOutcome.Completed) {
                outcome.selectedLanguage?.let { AppPrefs(ctx).languageCode = it }
            }
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    OnboardingSdk.configure(buildConfig()).onFailure { Log.e(TAG, "OnboardKit config bị từ chối", it) }
}
```

`install()` đồng bộ và nhẹ. `configure()` trả về `Result` — **phải kiểm tra**. Config bị từ chối sẽ
không được áp dụng, và luồng sau đó tự báo là đã skip mà không có triệu chứng nào khác.

### 2.2 Config

```kotlin
private fun buildConfig() = onboardKitConfig {
    splash = SplashConfig(
        logoRes = R.drawable.ic_logo,
        minDisplayTimeMs = 3_000,
    )
    language = LanguageConfig(defaultCode = "en")

    defaultSteps()                            // OB1, OB2, OB3 (ads full-screen), OB4

    question = QuestionConfig(
        options = listOf(
            QuestionOption("romance", "Romance", imageRes = R.drawable.opt_romance),
            QuestionOption("scifi", "Sci-Fi", imageRes = R.drawable.opt_scifi),
        ),
    )

    ads = AdsConfig(
        splashInterstitial   = InterstitialAdUnit("ca-app-pub-…/1111"),
        splashBanner         = BannerAdUnit("ca-app-pub-…/2222"),
        languageNative       = NativeAdUnit.waterfall(highFloor = "…/3333", allPrice = "…/4444"),
        languageDupNative    = NativeAdUnit("ca-app-pub-…/5555"),
        contentStepNative    = NativeAdUnit("ca-app-pub-…/6666"),
        fullScreenStepNative = NativeAdUnit("ca-app-pub-…/7777"),
        ob5Native            = NativeAdUnit("ca-app-pub-…/8888"),
        questionNative       = NativeAdUnit("ca-app-pub-…/9999"),
        questionInterstitial = InterstitialAdUnit("ca-app-pub-…/0000"),
    )
}.getOrThrow()
```

`defaultSteps()` là template OB1–OB4. Muốn tự chọn:

```kotlin
steps(
    ContentStepDefinition(StepId.OB1, titleRes = R.string.ob1_title, imageRes = R.drawable.ob1),
    AdFullScreenStepDefinition(StepId.OB3, showSkipButton = true, skipButtonDelaySec = 3),
    ContentStepDefinition(StepId.OB4, layoutRes = R.layout.my_ob4),   // layout của bạn, xem mục 4
)
```

**Thứ tự step cố định trong code**; remote config chỉ có thể tắt một step, không bao giờ đổi thứ tự.
Đây là chủ đích — một danh sách sắp xếp được từ remote chính là cách SDK bị audit bắn nhầm event
hoàn thành của step khác khi có step bị tắt. Một step được bật/tắt theo `StepId` của nó:
`StepId.OB1` đọc `ob_enable_step_ob1`, tương tự tới OB5.

#### Ad unit riêng từng trang, và cái bẫy đánh số

`contentStepNative` là pool dùng chung cho các trang content. Muốn bán riêng từng trang thì khai
entry riêng cho trang đó — trang nào không khai sẽ rơi về pool chung, nên khai một trang không bắt
bạn phải khai hết:

```kotlin
ads = AdsConfig(
    contentStepNative = NativeAdUnit("…/shared"),      // dùng cho trang không có entry dưới đây
    stepNatives = mapOf(
        StepId.OB1 to NativeAdUnit.waterfall(highFloor = "…/1111", allPrice = "…/2222"),
        StepId.OB2 to NativeAdUnit("…/3333"),
    ),
)
```

Chú ý cách đánh số. `StepId` đếm **vị trí trong flow**, và trang ad-only chiếm một vị trí — nên với
bố cục mặc định OB1, OB2, **OB3 = trang ad full-screen**, OB4, thì trang *content* thứ ba là
`StepId.OB4`. Nếu remote key của bạn đếm theo trang content (`native_ob1..3`) thì hai bên không
khớp, và `StepId.OB3 to native("native_fs")` đọc lên y như gõ nhầm. Đặt tên vai trò một lần, ngay
chỗ khai flow, thì phần còn lại của file hết nói dối:

```kotlin
private object Page {
    val CONTENT_1      = StepId.OB1
    val CONTENT_2      = StepId.OB2
    val AD_FULL_SCREEN = StepId.OB3
    val CONTENT_3      = StepId.OB4
}
```

Mọi unit ở trên đều là **waterfall**: danh sách xếp tầng cao trước, provider đi từng id một, 30 s mỗi
tầng, dừng ở tầng đầu tiên fill được. `NativeAdUnit("id")` chỉ là waterfall một tầng.

#### Ad được request lúc nào

Chuỗi preload chạy trước một màn, riêng trang ad-only chạy trước hai: nó không có nội dung nào của
riêng nó, nên tới nơi mà ad chưa fill là user ngồi nhìn spinner.

| Khi user đang ở | SDK request |
|---|---|
| Splash (sau remote) | native language, native step đầu, banner + interstitial splash |
| Language | native language thứ hai, native step đầu |
| Step *n* | step *n+1*, cộng thêm step ad-only kế tiếp dù nó ở đâu |
| Step cuối | OB5 và question |

### 2.3 Splash — kế thừa, đừng chép

Activity launcher của bạn kế thừa `ObSplashActivity`. Chuỗi xử lý — consent, fetch remote, request
ads, billing, thời gian hiện tối thiểu — đã nằm sẵn bên trong; bạn chỉ điền các hook cần dùng.

```kotlin
class SplashActivity : ObSplashActivity() {

    /** Trả về "ads đã được phép request chưa". Hiện UMP ở đây. */
    override suspend fun onConsentRequired(): Boolean {
        // Gọi Tracker.setConsent(analytics, ads) đúng một lần từ callback consent.
        return userGrantedConsent
    }

    override suspend fun onInitBilling() { /* khởi tạo AppPurchase */ }

    override fun onRemoteFetched() { /* remote key riêng của bạn đã sẵn sàng */ }
}
```

`onConsentRequired` là cổng chặn cho **mọi** ad trong flow, không riêng cái ở splash. Trả `false` —
hoặc không trả lời trong `consentTimeoutMs` — sẽ chạy cả onboarding không ad thay vì request khi
chưa có câu trả lời; mỗi vị trí báo `consent_not_granted` nên việc bỏ qua là thấy được chứ không im
lặng. Fetch remote chạy song song với consent (nó không request ad nào); còn load ad thì không.

Khai nó là launcher trong manifest như bình thường. **Không** tự gọi `OnboardingSdk.start()` —
`ObSplashActivity` gọi khi pipeline của nó xong.

### 2.4 Vào lại từ Settings

```kotlin
OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)
```

Chế độ `SETTINGS` không hiện ads, có nút back thật, và bị loại khỏi phễu first-open nên không thể
thổi phồng tỷ lệ chuyển đổi LFO.

---

## 3. Những gì tự có sẵn

Không mục nào dưới đây cần call site của bạn. Chỉ cần đã đăng ký một `TrackSink`.

| Giai đoạn | Event |
|---|---|
| Vào luồng | `fo_flow_start` — mẫu số, bắn cả khi luồng quyết định skip |
| Splash | `fo_splash_view`, `fo_splash_complete` (`dwell_ms`) |
| Language | `fo_language_view`, `fo_language_select`, `fo_language_complete` (đều có `screen_index`), `fo_language_flow_complete` |
| Steps | `fo_step_view`, `fo_step_complete` — kèm `step`, `index`, `dwell_ms`, `exit_reason` (`cta` / `skip` / `auto_next` / `ad_failed` / `auto_dismiss`) |
| Câu hỏi | `fo_question_view`, `fo_question_answer`, `fo_question_complete` |
| Kết thúc luồng | `fo_flow_complete` (`steps_shown`, `dwell_ms`) |
| Slot ads | `ad_request`, `ad_show`, `ad_load_failed`, `ad_skipped` (`reason` = `policy` / `no_ad_unit` / `not_ready`) |
| Paywall | `iap_paywall_view`, `iap_paywall_result` (`status`) |
| Màn hình | `screen_view` cho mỗi màn của luồng |

Danh tính event là **step id**, không bao giờ là pager index, nên tắt một step không thể đẩy step khác
sang nhầm tên event.

Muốn nhận thêm trong code của bạn thì thêm plugin:

```kotlin
OnboardingSdk.install(this) {
    analyticsPlugin { event -> myOwnLogger.log(event.name, event.params) }
}
```

Bạn cũng có thể quan sát luồng dưới dạng `Flow<OnboardingEvent>` qua `OnboardingSdk.events`, hoặc đọc
trạng thái bằng `isCompleted()`, `selectedLanguage()`, `answers()`.

---

## 4. Tự cấp layout — hợp đồng về id

Truyền `layoutRes` ở step, `LanguageConfig.layoutRes`, hoặc `SplashConfig.layoutRes`. SDK bind theo
id, nên các id sau **phải tồn tại**, không thì slot bị bỏ qua:

| Id | Kiểu | Ở đâu |
|---|---|---|
| `ob_native_container` | `FrameLayout` | mọi màn có native |
| `ob_native_shimmer` | include shimmer | cạnh container |
| `ob_ad_block` | `ViewGroup` | vùng bọc, bị ẩn khi slot bị từ chối |
| `ob_primary_cta` | `ObPrimaryButton` | content step |
| `ob_step_indicator` | `ObStepIndicator` | content step |
| `ob_skip_button` | `View` | màn ads full-screen |
| `ob_ad_block_2`, `ob_native_container_2`, `ob_native_shimmer_2` | như trên | **chỉ màn language** — slot native thứ hai |
| `ob_splash_logo`, `ob_splash_app_name`, `ob_splash_progress`, `ob_splash_ad_container` | | splash |

Template native dùng id chuẩn của AdMob (`ad_headline`, `ad_media`, `ad_call_to_action`, …) để
`Admob.populateUnifiedNativeAdView` bind được.

---

## 4.1 Show ad từ màn của riêng bạn

Bạn không cần phần này cho flow có sẵn — các màn của SDK tự load và show ad của chúng. Phần này dành
cho màn bạn tự thêm vào flow. Có đúng hai entry point, không có gì khác:

```kotlin
// Full-screen ad. Hai mốc, vì chúng không phải cùng một mốc.
showInterstitial(
    AdPlacement.SplashInterstitial,
    onNext = { startNextScreen() },   // ad đã lên: start đích DƯỚI nó
    onFinished = { finish() },        // ad đã đóng: lúc này mới finish màn hiện tại
)

// Native slot
showNativeAd(
    placement = AdPlacement.QuestionNative,
    unit = config.ads.questionNative,
    container = binding.nativeContainer,
    shimmer = binding.nativeShimmer.root,
    onUnavailable = { binding.adBlock.isVisible = false },
)
```

Start đích ở `onNext` cho nó trọn thời gian ad hiển thị để inflate và bind, nên khi ad đóng là nó đã
vẽ xong. Cả hai callback chạy **tối đa một lần**, `onNext` luôn trước `onFinished`, trên mọi đường —
kể cả đường không có ad nào. Màn của bạn không cần cờ chống gọi hai lần.

Nếu bước kế chỉ quyết định được sau ad — ví dụ nhánh paywall — thì bỏ `onNext`, làm hết trong
`onFinished`. Cái giá là user thấy khựng một nhịp; cái được là không start một đích có thể không dùng.

Tuyệt đối không gọi `finish()` trong `onNext`: Activity truyền vào `show()` phải sống lâu hơn ad,
finish ở đó là giết đúng cái impression bạn vừa tốn tiền load.

Muốn hỏi trước khi làm: `OnboardingSdk.guard().skipReason(context, placement)` trả `null` khi được
show, hoặc trả đúng lý do khi không.

---

## 5. Remote config key

Tất cả đều có tiền tố `ob_` nên không đụng namespace remote của app bạn. Giá trị mặc định nằm trong
code (`ObRemoteKeys`) — không có file defaults XML nào phải đồng bộ.

**Kill switch** `ob_enable_all_ads`, `ob_enable_ui_content`
**Steps** `ob_enable_step_ob1`…`ob5`, `ob_enable_question`, `ob_enable_question_old_user`
**Language** `ob_enable_language_native_2`, `ob_pass_lfo_if_completed`, `ob_language_supported_codes` (CSV)
**Bật/tắt ads** `ob_reuse_splash_inter`, `ob_ads_splash_banner_enabled`, `ob_ads_splash_inter_enabled`, `ob_ads_{language,content,fullscreen,question}_native_enabled`, `ob_ads_question_inter_enabled`, `ob_ads_app_resume_enabled`
**Override ad unit** `ob_ads_splash_inter_id`, `ob_ads_splash_inter_id_old_user` (rỗng = dùng id compile-time)
**Tần suất** `ob_ads_interstitial_interval_sec`, `ob_ads_click_cap_per_day` — cả hai mặc định `0`, nghĩa là tắt
**Thời gian** `ob_splash_min_display_ms` (3000), `ob_splash_ad_budget_ms` (60000), `ob_splash_banner_wait_ms` (0), `ob_skip_button_delay_sec`, `ob_fullscreen_auto_dismiss_sec`
**Nút skip** `ob_show_skip_ob3`, `ob_show_skip_ob5`
**Template** `ob_native_template_{content,language,question}` = `cta_top` | `cta_bottom` | `compact`
**UI điều khiển từ server** `ob_ui_content`, `ob_ui_design_tokens`, `ob_question_config`
**Dấu phiên bản cache** `ob_config_version` — đổi giá trị để xoá cache UI cục bộ

`ob_enable_step_ob5` mặc định **false**: OB5 là màn ads full-screen độc lập, tắt trừ khi bạn chủ động
bật.

---

## 6. Paywall (tuỳ chọn)

```kotlin
class MyPaywallGate : PaywallGate {
    override suspend fun shouldShow(placement: PaywallPlacement) =
        placement == PaywallPlacement.AFTER_ONBOARDING && !AppPurchase.getInstance().isPurchased
    override suspend fun present(activity: Activity, placement: PaywallPlacement): PaywallOutcome {
        return PaywallOutcome.Dismissed
    }
}
```

Placement: `SPLASH_INTER`, `AFTER_ONBOARDING`, `AFTER_QUESTION_OLD_USER`. Mỗi lần hiện đều báo
`iap_paywall_view` + `iap_paywall_result`; bản thân giao dịch do lớp billing báo là `iap_success`, nên
doanh thu không bao giờ bị đếm hai lần.

---

## 7. Checklist tích hợp

- [ ] `Tracker.install()` **và** ít nhất một `Tracker.addSink(...)` — thiếu là không event nào tới nơi
- [ ] `Tracker.setConsent(analytics, ads)` gọi đúng một lần, từ callback UMP
- [ ] `OnboardingSdk.install()` trước `configure()`, cả hai trong `Application.onCreate()`
- [ ] Kết quả `configure()` được kiểm tra, không bỏ qua
- [ ] Activity launcher kế thừa `ObSplashActivity`
- [ ] Có ad unit id cho mọi placement bạn bật — id rỗng sẽ báo `ad_skipped/no_ad_unit`
- [ ] Remote key đã publish với đúng mặc định ở mục 5, hoặc bỏ hẳn (code tự dùng mặc định)
- [ ] `OnboardingListener` điều hướng đi đâu đó ở **cả** `Completed` lẫn `Skipped`
- [ ] Layout tự cấp có đủ id ở mục 4
- [ ] Kiểm chứng trên bản debug: `ConsoleSink` in ra mọi event rời khỏi SDK

---

## 8. Khác biệt chủ đích so với SDK mà nó thay thế

- Checkpoint `lastCompletedStep`: kill app giữa luồng thì mở lại đúng step đang dở, không quay về màn
  language từ đầu.
- LFO2 (impression native thứ hai trên cùng một màn) và refresh-ads-khi-chạm-đáp-án **mặc định tắt** —
  bật qua config kèm remote.
- Premium ẩn ads ở mọi màn kể cả OB5, và có thể bỏ hẳn các step chỉ có ads.
- Màn ads full-screen luôn có lối ra: Skip bị ép hiện khi auto-next tắt, cộng auto-dismiss cấu hình
  từ remote.
- Đáp án câu hỏi được lưu vào DataStore và báo cáo lên analytics — bản gốc không lưu, cũng không log.
- Một step hoặc đáp án lỗi từ remote chỉ bị bỏ riêng phần tử đó, không làm hỏng cả màn.
- `fo_flow_complete` bắn ở **mọi** lối thoát. Ở SDK bị audit, event tương đương bị bỏ qua ở hai trong
  ba lối ra, nên phần lớn user không bao giờ sinh ra nó.
