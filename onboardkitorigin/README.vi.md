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
| Firebase | `google-services.json` + `com.google.gms.google-services` để fetch giá trị `ob_*` từ server |
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
    ConsentCenter.configure(ConsentOptions(timeoutMs = 20_000))
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

`onboardKitConfig { }` trả về `Result` chứa config hợp lệ hoặc các lỗi validation.
`SplashConfig`, `LanguageConfig`, `BehaviorConfig`, `SystemBarConfig`, `QuestionConfig` và
`AdsConfig` ghi rõ tùy chọn và giá trị mặc định trong KDoc. Cấu hình nội dung và các slot quảng cáo
mà luồng của bạn cần.

**Slot quảng cáo.** Slot `null` thì không hiện quảng cáo. Mọi slot native và interstitial đều là
waterfall: id xếp từ giá sàn cao nhất trước, gọi từng cái một, dừng ở lần fill đầu tiên. `AdsConfig`
liệt kê đủ mọi slot mà luồng này có thể lấp.

Muốn giữ id trong `ad_config.json` thay vì hard-code, dùng helper phía app để chuyển giá trị
`AdRemoteConfig` thành các slot:

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
val remoteAds = runCatching { AdRemoteConfig.getInstance() }.getOrNull()
val config = onboardKitConfig {
    ads = AdsConfig(
        splashInterstitial = remoteAds.interstitial("inter_splash"),
        languageNative     = remoteAds.native("native_lang"),
        contentStepNative  = remoteAds.native("native_ob1"),
    )
}.getOrThrow()
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

Launcher activity kế thừa `ObSplashActivity`. SDK xử lý consent, billing, remote fetch, tải quảng
cáo, quyền thông báo, thời gian hiển thị tối thiểu và điều hướng. Override các hook để bổ sung
phần khởi tạo riêng của app.

```kotlin
class SplashActivity : ObSplashActivity() {
    override suspend fun onInitBilling() { myEntitlement.awaitReady() }  // xác định premium trước

    override fun onRemoteFetched() {
        // fetch các remote key riêng của app tại đây
        OnboardingSdk.configure(buildConfig())   // dựng lại: remote có thể đã đổi ad unit id
    }
}
```

`SplashConfig.notificationPermissionEnabled` mặc định là `true`. Trên Android 13+ khi target SDK
33+, splash xin `POST_NOTIFICATIONS` sau khi bước consent hoàn tất. Thư viện đã khai báo quyền này.
Không hiện prompt nếu quyền đã được cấp, lần hỏi tự động trước đã có kết quả, hoặc app đã gỡ quyền
khỏi manifest. Từ chối hay hủy không chặn luồng và không cấp quyền request quảng cáo. Nếu app tự
quản lý quyền thông báo, tắt lần hỏi tự động trong config:

```kotlin
splash = SplashConfig(notificationPermissionEnabled = false)
```

Consent và remote fetch có thể chạy song song. Banner/interstitial của splash đủ điều kiện vẫn có
thể tải khi prompt thông báo đang mở. Splash chờ các khoảng tải quảng cáo đã cấu hình, thời gian
hiển thị tối thiểu và kết quả xin quyền nếu có. Trước khi preload native của màn đích, splash chờ
Activity ở trạng thái resumed và có window focus, rồi bắt đầu preload trước khi thử hiện
interstitial splash và chuyển luồng. Thời gian chờ banner mặc định là `0`; thời gian chờ
interstitial kết thúc khi tải xong hoặc hết budget cấu hình, nên không yêu cầu mọi quảng cáo đều fill.

Khai báo launcher activity với `android:exported="true"`, intent-filter MAIN/LAUNCHER và theme
AppCompat/MaterialComponents. Với splash dọc, dùng:

```xml
<activity
    android:name=".SplashActivity"
    android:configChanges="orientation|screenSize|keyboardHidden|uiMode|fontScale"
    android:exported="true"
    android:screenOrientation="portrait"
    android:theme="@style/Theme.Splash">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

`configChanges` cho phép Activity tự xử lý các thay đổi đã liệt kê mà không tạo lại. Nếu giữ
`uiMode|fontScale`, hãy tự cập nhật các custom view bị ảnh hưởng. Activity vẫn có thể được tạo lại
vì nguyên nhân khác. Với app ngang hoặc tablet, xem lại orientation trong manifest và đặt
`BehaviorConfig.lockPortrait = false`.

- Đừng gọi `OnboardingSdk.start()` ở đây — nó tự chạy khi pipeline hoàn tất.
- Giữ `onConsentRequired()` mặc định để chạy UMP qua `ConsentCenter` trong `:ads`. Nếu dùng
  consent provider riêng, gọi `ConsentCenter.setHostConsent(...)` với quyền request và lựa chọn
  cá nhân hóa trước khi hoàn tất override. Chỉ `return true` không cấp quyền request quảng cáo.
  `OnboardingSdk.setCanRequestAds(false)` vẫn là giới hạn riêng ngay cả khi consent cho phép.
- Nếu override `onDestroy()`, nhớ gọi `super.onDestroy()` — `ConsentCenter.detach(this)` nằm ở đó.

Về sau, từ bất kỳ đâu: `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)`.

## Vào app từ notification, widget hoặc uninstall shortcut

`SplashEntry` (`NOTIFICATION`, `WIDGET`, `UNINSTALL`) đưa các lần mở tính năng qua splash, chọn
interstitial riêng cho từng entry và xác định thời điểm mở màn đích. Bạn cung cấp extra của tính
năng và xử lý màn đích trong listener.

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

Dùng `NEW_TASK` và không thêm `CLEAR_TASK` khi chuyển màn này: quảng cáo có thể vẫn đang hiển
thị, và xóa task sẽ finish Activity chứa nó. Xử lý extra ở **cả** `onCreate` lẫn `onNewIntent`,
vì nơi nhận phụ thuộc launch mode của màn đích và trạng thái task.

**4. Quảng cáo và thời điểm điều hướng theo entry.** Một lần mở qua `SplashEntry` dùng key
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
màn hiện tại trong `onFinished`. Mỗi callback chạy tối đa một lần, `onNext` trước `onFinished`;
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

Xem từng key `ob_*`, kiểu dữ liệu và giá trị mặc định tại `io.onboardkit.remote.ObRemoteKeys`.
SDK khôi phục các flag đã cache khi khởi động, dùng mặc định nếu chưa có cache. Khi Firebase đã
được cấu hình, splash tự fetch và áp dụng giá trị remote. Publish giá trị ghi đè trên Firebase console.

## Analytics

Funnel được phát tự động một khi `Tracker.install()` và một `Tracker.addSink(...)` đã được nối — xem
[`../trackkit/README.md`](../trackkit/README.md) để biết tên event. Nếu muốn nhận event nội bộ của
SDK, thêm `analyticsPlugin { event -> log(event.name, event.params) }` bên trong `install`, hoặc
collect `OnboardingSdk.events` / `.state`.

`isCompleted()`, `selectedLanguage()` và `answers()` đọc tiến trình đã lưu. `markCompleted()`
đánh dấu hoàn tất; `reset()` xóa tiến trình. Đây đều là hàm suspend.

## Xử lý sự cố

| Hiện tượng | Nguyên nhân | Cách xử lý |
|---|---|---|
| Luồng không bao giờ chạy | `configure()` thất bại, hoặc chạy trước `install()` | Log cái `Result`; gọi `install()` trước |
| Người dùng không thoát khỏi luồng | Không có `OnboardingListener`, hoặc nó bỏ qua `Skipped` | Xử lý cả ba outcome |
| Mọi placement báo `no_provider` | `adProvider` để null | `adProvider = ERainAdProvider()` |
| Mọi placement báo `consent_not_granted` | Consent chưa cho phép request, hoặc host đã tắt quảng cáo | Kiểm tra `ConsentCenter.canRequestAds()` và `OnboardingSdk.canRequestAds()`; hoàn tất UMP hoặc công bố kết quả consent provider riêng |
| Trang chỉ-quảng-cáo không xuất hiện | Không có unit dùng được cho `fullScreenStepNative` / `stepNatives[OB3]` | Cấu hình một cái; chỉ bật cờ remote của step là chưa đủ |
| Banner splash không hiện | Thiếu `ob_splash_ad_container` hoặc thiếu include `layout_banner_control` | Thêm cả hai vào layout splash |

## License

MIT — xem [`../LICENSE`](../LICENSE).
