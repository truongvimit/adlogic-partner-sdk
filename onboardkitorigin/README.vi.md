# OnboardKit

> Luồng mở app lần đầu đóng gói thành thư viện: splash → chọn ngôn ngữ → các bước onboarding →
> quảng cáo full-screen (tùy chọn) → câu hỏi khảo sát (tùy chọn) → app của bạn.

Quảng cáo, remote config, lưu trạng thái và funnel analytics đều nằm bên trong. Bạn chỉ cung cấp ad
unit id, nội dung hiển thị, và nơi cần đi tới khi luồng kết thúc.

English: **[README.md](README.md)** · हिन्दी: **[README.hi.md](README.hi.md)**

## Yêu cầu

| | |
|---|---|
| minSdk / compileSdk / JDK | 24 / 36 / 17 |
| Namespace, resource prefix, entry point | `io.onboardkit`, `ob_`, `OnboardingSdk` |
| Firebase | `google-services.json` + `com.google.gms.google-services`; thiếu thì mọi key `ob_*` giữ nguyên giá trị mặc định |
| Ad unit id | qua `AdRemoteConfig` từ `assets/ad_config.json`, hoặc ghi thẳng trong `AdsConfig` |

## Cài đặt

```groovy
// Thay <tag> bằng một tag tại https://github.com/truongvimit/adlogic-partner-sdk/tags
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

Phải khai báo `:ads` tường minh — bên trong module này nó là dependency `implementation`, nên
`com.ads.module.*` sẽ không nằm trên compile classpath của bạn. `:trackkit` được export bằng `api`,
`consumer-rules.pro` đi kèm module, và các activity của SDK đã nằm trong manifest thư viện — đừng
khai báo lại.

## Tích hợp

### 1. `Application.onCreate()`

`Tracker.install()` trước tiên — event phát ra sớm hơn chỉ được buffer. `OnboardingSdk.install()`
trước `configure()` — config truyền vào trước install sẽ bị bỏ, và khi đó toàn bộ luồng bị skip.

```kotlin
override fun onCreate() {
    super.onCreate()
    initTracking()                                    // Tracker.install + Tracker.addSink
    AdRemoteConfig.initializeFromAssets(this)         // assets/ad_config.json
    AdConfig.install(FirebaseAdConfigSource())        // tùy chọn: remote ad config
    ConsentCenter.configure(ConsentOptions(timeoutMs = 20_000, testDeviceHashedId = "…"))
    ERainAd.getInstance().init(this, buildERainAdConfig())   // xem ../ads/README.md
    ERainTuning.install()                             // một lần, sau ERainAd.init

    OnboardingSdk.install(this) {
        adProvider = ERainAdProvider()                // null nếu muốn luồng không quảng cáo
        paywallGate = OnboardKitPaywallGate()         // tùy chọn, từ :paykit
        listener = OnboardingListener { ctx, outcome -> goToMain(ctx, outcome) }
    }
    OnboardingSdk.configure(buildConfig()).onFailure { Log.e("OnboardKit", "rejected", it) }
    OnboardingSdk.setFlowLogging(BuildConfig.DEBUG)   // log OB_FLOW
}
```

Listener phải điều hướng ở cả `OnboardingOutcome.Completed`, `Skipped` **và** `Aborted` — không đăng
ký listener thì outcome bị bỏ qua. `Completed.selectedLanguage` mang theo ngôn ngữ đã chọn;
`OnboardingSdk.selectedLanguage()` đọc lại nó về sau.

### 2. Config

```kotlin
private fun buildConfig() = onboardKitConfig {
    splash = SplashConfig(logoRes = R.drawable.ic_logo, minDisplayTimeMs = 3_000)
    language = LanguageConfig(defaultCode = "en")
    defaultSteps()                                    // OB1, OB2, OB3 (chỉ quảng cáo), OB4
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

`onboardKitConfig { }` trả về một `Result` — nó validate và từ chối ngay, thay vì để crash về sau.
`SplashConfig`, `LanguageConfig`, `BehaviorConfig`, `SystemBarConfig`, `QuestionConfig` và
`AdsConfig` mỗi cái có bộ tùy chọn riêng, được ghi chú từng field bằng KDoc; mặc định đã là một luồng
chạy được, nên chỉ cần set thứ bạn muốn đổi.

**Slot quảng cáo.** Slot `null` thì không hiện quảng cáo. Mọi slot native và interstitial đều là
waterfall: id xếp từ giá sàn cao nhất trước, gọi từng cái một, dừng ở lần fill đầu tiên. `AdsConfig`
liệt kê đủ mọi slot mà luồng này có thể lấp.

Muốn giữ id trong `ad_config.json` thay vì hard-code, hãy nạp slot từ `AdRemoteConfig`. SDK không có
helper sẵn cho việc này — ba hàm dưới đây là phần glue phía app, và chừng đó là đủ:

```kotlin
private fun AdRemoteConfig?.native(baseKey: String): NativeAdUnit? =
    this?.tiersFor(baseKey)?.takeIf { it.isNotEmpty() }?.let { NativeAdUnit(tiers = it) }

private fun AdRemoteConfig?.interstitial(baseKey: String): InterstitialAdUnit? =
    this?.tiersFor(baseKey)?.takeIf { it.isNotEmpty() }?.let { InterstitialAdUnit(tiers = it) }

// Banner ở đây không có waterfall — chỉ tier cao nhất là id dùng được.
private fun AdUnitConfig?.toBanner(): BannerAdUnit? =
    this?.takeIf { it.isUsable }?.let { BannerAdUnit(id = it.waterfallIds.first()) }
```

```kotlin
val ads = runCatching { AdRemoteConfig.getInstance() }.getOrNull()
ads = AdsConfig(
    splashInterstitial = ads.interstitial("inter_splash"),
    languageNative     = ads.native("native_lang"),
    contentStepNative  = ads.native("native_ob1"),
)
```

**Các bước (step).** Thay cho `defaultSteps()`, bạn có thể tự liệt kê bằng
`steps(vararg StepDefinition)` hoặc `step(…)` — `ContentStepDefinition` cho trang nội dung,
`AdFullScreenStepDefinition` cho trang chỉ có quảng cáo. Thứ tự trong danh sách là thứ tự hiển thị;
remote config chỉ có thể tắt bớt một step. `id` là một `StepId` (`OB1`…`OB5`) — đó là **vị trí trong
luồng**, không phải số thứ tự trang nội dung: OB3 là trang chỉ-quảng-cáo của template mặc định, nên
trang *nội dung* thứ ba là `StepId.OB4`.

**Native template.** Các màn hình này ship sẵn một layout cho mỗi vị trí CTA, nên `NativeTemplate`
chọn layout chứ không dịch chuyển các block. Bạn có thể set thẳng, hoặc suy ra từ chính document
config bằng một helper phía app nữa:

```kotlin
private fun AdRemoteConfig?.templateOf(
    key: String,
    default: NativeTemplate = NativeTemplate.CTA_BOTTOM,
): NativeTemplate = when (this?.unit(key)?.positionCTA) {
    "TOP" -> NativeTemplate.CTA_TOP
    "BOTTOM" -> NativeTemplate.CTA_BOTTOM
    else -> default
}
```

### 3. Splash

Launcher activity của bạn kế thừa `ObSplashActivity`. Consent, billing, remote fetch, request quảng
cáo, thời gian hiển thị tối thiểu, interstitial và việc điều hướng ra ngoài đều nằm bên trong; bạn
chỉ điền vào các hook.

```kotlin
class SplashActivity : ObSplashActivity() {
    override suspend fun onInitBilling() { myEntitlement.awaitReady() }  // xác định premium trước

    override fun onRemoteFetched() {
        // fetch các remote key riêng của app tại đây
        OnboardingSdk.configure(buildConfig())   // dựng lại: remote có thể đã đổi ad unit id
    }
}
```

Khai báo nó với `android:exported="true"`, một intent-filter MAIN/LAUNCHER và theme
AppCompat/MaterialComponents.

- Đừng gọi `OnboardingSdk.start()` ở đây — nó tự chạy khi pipeline hoàn tất.
- Đừng override `onConsentRequired()`; mặc định của nó chạy luồng UMP qua `ConsentCenter` trong
  `:ads`. Chỉ override để `return true` nếu app không có bước consent.
- Nếu override `onDestroy()`, nhớ gọi `super.onDestroy()` — `ConsentCenter.detach(this)` nằm ở đó.

Về sau, từ bất kỳ đâu: `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)`.

## Vào app từ notification, widget hoặc uninstall shortcut

Một cú chạm chỉ đích danh một tính năng phải sống sót qua trọn luồng mở app lần đầu, rồi mở đúng tính
năng đó mà không che mất quảng cáo vừa được trả tiền. Phần wiring đó đã có sẵn dưới dạng
`SplashEntry` (`NOTIFICATION`, `WIDGET`, `UNINSTALL`) — intent vào app, ad unit mà nó tiêu, và thời
điểm chuyển màn đều đã được trả lời sẵn. Phần còn lại của bạn là các extra chỉ đích danh tính năng và
màn hình mà mỗi entry sẽ đáp xuống.

**1. Bắn intent của entry vào splash, không phải vào màn hình chính.** Cú chạm mở một session mới,
nên nó đi đúng con đường mà một cú chạm từ launcher đi. `SplashEntry.intent` gắn nhãn cho lần mở đó
và đã set sẵn `NEW_TASK or CLEAR_TASK`; bạn chỉ thêm extra của tính năng lên trên.

```kotlin
SplashEntry.WIDGET.intent(context, SplashActivity::class.java)
    .putExtra(EXTRA_WIDGET_ACTION, "merge_pdf")
```

**2. Các extra đi xuyên luồng dưới dạng passthrough.** `ObSplashActivity` nạp nó từ chính
`intent.extras` của mình, SDK mang nó qua mọi màn hình, và trả lại ở `Completed` và `Skipped` (không
bao giờ ở `Aborted`). Extra của bạn là dữ liệu mờ đối với SDK.

**3. Listener định tuyến outcome** — quyết định duy nhất mà mỗi app tự đưa ra:

```kotlin
listener = OnboardingListener { context, outcome ->
    val extras = when (outcome) {
        is OnboardingOutcome.Completed -> outcome.passthrough
        is OnboardingOutcome.Skipped -> outcome.passthrough
        is OnboardingOutcome.Aborted -> null
    }
    val destination = when (SplashEntry.from(extras)) {
        SplashEntry.UNINSTALL -> ConfirmUninstallActivity::class.java
        else -> MainActivity::class.java
    }
    context.startActivity(
        Intent(context, destination)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { extras?.let(::putExtras) },
    )
}
```

Chỉ `NEW_TASK`, tuyệt đối không `CLEAR_TASK`: đoạn này có thể chạy trong lúc quảng cáo đang hiển thị,
và xóa task sẽ finish luôn Activity đang chứa nó. Hãy đọc extra ở **cả** `onCreate` lẫn `onNewIntent`
— chạm nguội rơi vào cái đầu, chạm nóng rơi vào cái sau — và tiêu thụ nó ngay khi đọc.

**4. Ad unit và thời điểm chuyển màn đã được trả lời sẵn.** Một lần mở qua `SplashEntry` sẽ tiêu key
của chính entry đó (`inter_noti`, `inter_widget`, `inter_uninstall`), đầy đủ waterfall, và quay về
cách phân giải splash thông thường nếu key đó thiếu hoặc bị tắt. Nó cũng nhận `AFTER_AD`, trong khi
cú chạm từ launcher giữ `UNDER_AD` — cùng một đánh đổi như `InterNextAction` trong
[`../ads/README.md`](../ads/README.md#when-the-next-screen-starts). Chỉ override
`nextScreenTiming()` hoặc `splashInterstitialOverride()` khi cần chia nhỏ hơn.

## Layout tự viết

Chỉ `SplashConfig.layoutRes` và `ContentStepDefinition.layoutRes` được màn hình đọc. Các knob
`layoutRes` còn lại bị validation từ chối — hãy để chúng ở `0` và thay vào đó override layout cùng
tên của SDK, giữ nguyên mọi id mà nó khai báo.

| Thay vì | Hãy override layout này |
|---|---|
| `LanguageConfig.layoutRes` / `.itemLayoutRes` | `ob_activity_language.xml` / `ob_item_language.xml` |
| `QuestionConfig.layoutRes` / `.optionLayoutRes` | `ob_activity_question.xml` / `ob_item_question_option.xml` |
| `AdFullScreenStepDefinition.layoutRes` | `ob_fragment_ad_step.xml` |

Splash bind từng id theo kiểu null-safe, nên id nào bạn bỏ đi thì chỉ đơn giản là bị bỏ qua. Nhưng
layout của một content step phải mang **đủ** id của nó, nếu không trang đó sẽ quay về layout của SDK
kèm một dòng log.

| Màn hình | Id | Kiểu |
|---|---|---|
| Splash | `ob_splash_logo` / `ob_splash_app_name` / `ob_splash_progress` | `ImageView` / `TextView` / `ProgressBar` |
| | `ob_splash_ad_container` | `FrameLayout`; đặt `<include layout="@layout/layout_banner_control" />` bên trong, nếu không banner splash không có chỗ để gắn |
| Content step | `ob_step_image` / `ob_step_player` / `ob_step_card` | `ImageView` / `androidx.media3.ui.PlayerView` / `LinearLayout` |
| | `ob_step_title` / `ob_step_subtitle` / `ob_step_indicator` / `ob_primary_cta` | `TextView` / `TextView` / `ObStepIndicator` / `ObPrimaryButton` |
| | `ob_ad_block` / `ob_native_container` | `FrameLayout` (bị ẩn khi slot bị từ chối) / `FrameLayout` |

Với một màn hình của riêng bạn nằm trong luồng, `showInterstitial(placement, onNext, onFinished)` là
extension public trên `AppCompatActivity`: mở màn đích trong `onNext` (nằm dưới quảng cáo), finish
màn hiện tại trong `onFinished`. Cả hai chạy tối đa một lần, `onNext` luôn trước, trên mọi nhánh;
tuyệt đối đừng gọi `finish()` trong `onNext`. Không có API public tương đương cho native — hãy tự
render native bằng `NativeAdHelper` trong `:ads`.

## Paywall gate

```kotlin
class MyPaywallGate : PaywallGate {
    override suspend fun shouldShow(placement: PaywallPlacement) =
        placement == PaywallPlacement.AFTER_ONBOARDING && !myEntitlement.isPremium

    override suspend fun present(activity: Activity, placement: PaywallPlacement): PaywallOutcome =
        PaywallOutcome.Dismissed   // hoặc Purchased / ContinueWithAds
}
```

Các placement: `SPLASH_INTER`, `AFTER_ONBOARDING`, `AFTER_QUESTION_OLD_USER`. Không set `paywallGate`
thì mọi checkpoint đi thẳng qua. Có ship `:paykit`? Dùng luôn `OnboardKitPaywallGate` có sẵn của nó —
xem [`../paykit/README.md`](../paykit/README.md).

## Remote config

Mọi key `ob_*`, kiểu dữ liệu và giá trị mặc định của nó đều nằm trong
`io.onboardkit.remote.ObRemoteKeys` — một object duy nhất, mỗi key được ghi chú ngay tại chỗ khai
báo. Không publish gì thì luồng chạy theo các mặc định đó; publish một key trên Firebase console là
ghi đè. Phần này không cần code phía app: remote fetch của splash sẽ tự áp dụng.

## Analytics

Funnel được phát tự động một khi `Tracker.install()` và một `Tracker.addSink(...)` đã được nối — xem
[`../trackkit/README.md`](../trackkit/README.md) để biết tên event. Nếu muốn nhận event nội bộ của
SDK, thêm `analyticsPlugin { event -> log(event.name, event.params) }` bên trong `install`, hoặc
collect `OnboardingSdk.events` / `.state`.

`isCompleted()`, `selectedLanguage()`, `answers()`, `markCompleted()` và `reset()` đọc và xóa tiến
trình đã lưu.

## Xử lý sự cố

| Hiện tượng | Nguyên nhân | Cách xử lý |
|---|---|---|
| Luồng không bao giờ chạy | `configure()` thất bại, hoặc chạy trước `install()` | Log cái `Result`; gọi `install()` trước |
| Người dùng không thoát khỏi luồng | Không có `OnboardingListener`, hoặc nó bỏ qua `Skipped` | Xử lý cả ba outcome |
| Mọi placement báo `no_provider` | `adProvider` để null | `adProvider = ERainAdProvider()` |
| Mọi placement báo `consent_not_granted` | Form UMP chưa được trả lời trong `consentTimeoutMs` | Set `ConsentOptions(testDeviceHashedId = …)` |
| Trang chỉ-quảng-cáo không xuất hiện | Không có unit dùng được cho `fullScreenStepNative` / `stepNatives[OB3]` | Cấu hình một cái; chỉ bật cờ remote của step là chưa đủ |
| Banner splash không hiện | Thiếu `ob_splash_ad_container` hoặc thiếu include `layout_banner_control` | Thêm cả hai vào layout splash |

## License

MIT — xem [`../LICENSE`](../LICENSE).
