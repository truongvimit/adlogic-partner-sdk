# OnboardKit

[Luồng 6 màn OB, remote order và preload](../partner-integration/onboarding-flow.vi.md).

Splash → chọn ngôn ngữ → onboarding → câu hỏi/paywall tùy chọn → app của bạn.
SDK quản lý chuyển màn, tải trước quảng cáo và lưu tiến trình; app cung cấp nội dung và màn đích cuối cùng.

[English](README.md) · [हिन्दी](README.hi.md)

[Hướng dẫn cho partner](../partner-integration/README.vi.md) · [Tích hợp Ads + OnboardKit từng bước](../partner-integration/ads-onboarding-integration.vi.md)

## Trước khi tích hợp

- Dùng minSdk 24, compileSdk 36 và JDK 17. Làm theo [cấu hình build chung](../README.vi.md) và dùng cùng một tag đã phát hành cho mọi module.
- Với provider quảng cáo tích hợp sẵn, hoàn thành [hướng dẫn ads](../ads/README.md) trước: metadata AdMob/Meta, asset cấu hình quảng cáo và `ERainAd.init()` trong Application.
- Nếu cần funnel, cài `Tracker` và sink trước OnboardKit; xem [Trackkit](../trackkit/README.vi.md).
- Thêm cả hai dependency bên dưới. OnboardKit export Trackkit; code app dùng `com.ads.module.*` vẫn cần khai báo `ads` tường minh. Firebase và PayKit là tùy chọn.

Đặt `adlogicSdkVersion` một lần trong `gradle.properties` của app; xem [cấu hình build chung](../README.vi.md#cấu-hình-build).

```groovy
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
}
```

## 1. Install và configure trong Application

Ghép đoạn này vào Application hiện có **sau bước khởi tạo ads bên trên**; không khởi tạo ERain hai lần.
Ví dụ dùng ad unit test của Google. Thay bằng unit của bạn khi phát hành và dùng `MainActivity` của app.
Import `R`, `BuildConfig` và `MainActivity` của app nếu khác package; `io.onboardkit.config.*` đã bao gồm mọi kiểu config bên dưới.

```kotlin
import android.app.Application
import android.content.Intent
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.ads.erain.ERainTuning
import io.onboardkit.config.*
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Complete ERainAd.init here; add Tracker/sinks if needed (ads guide).
        ERainTuning.install()

        OnboardingSdk.install(this) {
            adProvider = ERainAdProvider()
            listener = OnboardingListener { context, outcome ->
                val extras = when (outcome) {
                    is OnboardingOutcome.Completed -> outcome.passthrough
                    is OnboardingOutcome.Skipped -> outcome.passthrough
                    is OnboardingOutcome.Aborted -> null
                }
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .apply { extras?.let { putExtras(it) } },
                )
            }
        }
        val config = onboardKitConfig {
            splash = SplashConfig(appNameRes = R.string.app_name)
            defaultSteps()
            ads = AdsConfig(
                splashInterstitial = InterstitialAdUnit(
                    "ca-app-pub-3940256099942544/1033173712",
                ),
                languageNative = NativeAdUnit(
                    "ca-app-pub-3940256099942544/2247696110",
                ),
                contentStepNative = NativeAdUnit(
                    "ca-app-pub-3940256099942544/2247696110",
                ),
            )
        }.getOrThrow()
        OnboardingSdk.configure(config).getOrThrow()
        OnboardingSdk.setFlowLogging(BuildConfig.DEBUG)
    }
}
```

Gọi `install()` trước `configure()`. Cả bước tạo config và configure đều trả về `Result`; ví dụ dùng `getOrThrow()` để lỗi tích hợp hiện rõ.
Listener xử lý đủ ba kết quả. `Completed.selectedLanguage` còn trả về ngôn ngữ đã chọn.
Khi chuyển tới app, dùng `NEW_TASK` và không thêm `CLEAR_TASK`: quảng cáo splash hoặc inter cuối onboarding có thể vẫn đang hiển thị trong task này.

## 2. Thêm splash làm launcher

```kotlin
import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity()
```

```xml
<application android:name=".App">
    <activity android:name=".MainActivity" android:exported="false" />
    <activity
        android:name=".SplashActivity"
        android:exported="true"
        android:screenOrientation="portrait"
        android:configChanges="orientation|screenSize|keyboardHidden|uiMode|fontScale"
        android:theme="@style/ob_Theme_OnboardKit">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
</application>
```

Ghép các khai báo vào manifest; giữ metadata và permission theo hướng dẫn ads. Thư viện đã khai báo các màn SDK.
Không tự gọi `OnboardingSdk.start()` hay finish splash; `ObSplashActivity` quản lý luồng này.

### Hành vi mặc định

- Hiện status/caption bar, ẩn navigation bar; dùng `SystemBarConfig` khi cần đổi.
- Chưa hoàn thành flow thì lần mở mới chạy lại Splash → LFO → OB. Đã hoàn thành thì bỏ qua onboarding.
- Chọn lại ngôn ngữ hiện tại thì popup mở ngay. Chọn ngôn ngữ khác chỉ mở khi đủ tổng số click đã cấu hình; click chọn lại vẫn được cộng count. Native được tải khi mở popup; click/open preload ad thay thế để hiện khi quay lại.
- Native `behavior.click.action` chọn một trong `auto_next`, `none`, `reload`. Bước content/fullscreen trong pager mặc định `auto_next`; native LFO1/LFO2, OB5, splash, popup và khảo sát mặc định `reload`.
- `reload` gửi request thay thế ngay khi click/open ad và dùng kết quả khi quay lại; resume app thông thường không kích hoạt click reload. `auto_next` chuyển tiếp khi quay lại mà không xin ad thay thế; `none` không làm gì cả.
- Action cố định cho mỗi lượt click và override các switch cũ `reload.on_ad_click` / `BehaviorConfig.adClickReturnCompletesStep`. Xem [hướng dẫn remote settings](../partner-integration/remote-settings.vi.md).


- `notificationPermissionEnabled = true`: Android 13+ / target 33+ hỏi quyền thông báo sau consent và bước fetch remote, nên giá trị remote fetch ở bước đó áp dụng ngay trong lần mở này. Đã cấp quyền hoặc đã ghi nhận kết quả hỏi tự động thì không hỏi lại; từ chối vẫn đi tiếp. Đặt `false` nếu app tự quản lý lời nhắc này.
- `noInternetPromptEnabled = true`: splash yêu cầu kết nối mạng trước khi tiếp tục. Đặt `false` nếu app cần cho phép mở offline.
- `lockPortrait = true`: các màn SDK, gồm splash kế thừa của app, bị khóa dọc. Giữ `configChanges` của splash như mẫu trên để việc khóa dọc, đổi dark mode hay cỡ chữ không tạo lại Activity. App hỗ trợ ngang cần đặt `false` và kiểm tra cả quy tắc hướng màn hình trong merged manifest.
- `consentTimeoutMs = 20_000`: luồng UMP mặc định do SDK quản lý **không giới hạn thời gian người dùng trả lời**. Ngân sách này vẫn giới hạn custom hook khi không có luồng consent do SDK quản lý đang chạy.
- Splash có thể tải ads đã được cho phép dưới hộp thoại notification khi còn hiển thị; nhấn Home sẽ chặn request mới. Minimum bắt đầu cùng pha tải ads và chạy chồng với loading/notification. Mặc định luồng lần đầu (ngôn ngữ/onboarding) dùng `AFTER_AD`, mở từ launcher khi onboarding đã xong (vào app hoặc khảo sát người dùng cũ) dùng `UNDER_AD`; entry notification, widget, uninstall luôn dùng `AFTER_AD`; override `nextScreenTiming()` trong splash và gọi `super` cho trường hợp giữ mặc định. Khi mở từ launcher, remote `splash.navigation.next_screen_timing` khác `AUTO` được ưu tiên hơn override của app. Cả hai kiểu đều chờ đủ minimum rồi mới show inter: `UNDER_AD` mở màn và show inter liên tiếp, còn `AFTER_AD` chuyển màn ngay khi đóng quảng cáo.

### Tùy chọn splash và ngôn ngữ

Splash/provider có sẵn quản lý consent, preload và chuyển màn. Chỉ cấu hình các giá trị cần đổi;
không thêm timer chờ vào luồng này.

| Tùy chọn | Mặc định / cách dùng |
|---|---|
| `SplashConfig.minDisplayTimeMs` | 3000 ms trước khi show inter splash, hoặc trước khi chuyển màn nếu không có quảng cáo. Remote `splash.timing.min_display_ms`, `ob_splash_min_display_ms` đã được gửi về và lớn hơn 0, hoặc `splash.timing.min_display_ms` trong asset của app sẽ ghi đè trường này; nếu không, giá trị của app được áp dụng. |
| `ob_splash_ad_budget_ms` | Chờ quảng cáo tối đa 60000 ms, tính sau khi notification hoàn tất và splash có focus. |
| `ob_splash_lfo_parallel_preload_enabled` | `false`: preload native ngôn ngữ đầu sau khi waterfall splash kết thúc hoặc hết thời gian chờ. `true`: preload cùng quảng cáo splash. |
| `LanguageConfig.tapHintEnabled` | Hiện bàn tay gợi ý chọn ngôn ngữ. Remote `lfo.tap_hint.enabled` hoặc `ob_show_language_tap_hint` đã được gửi về sẽ quyết định thay, bật hay tắt. |
| `ob_language_tap_hint_delay_sec` | 3 giây; `0` hiện ngay. Chọn ngôn ngữ sẽ hủy gợi ý; SETTINGS/ngôn ngữ chọn sẵn không hiện gợi ý. |

Các tham số `ob_*` là tùy chọn trong Firebase Remote Config. Key backend đã gửi về được ưu tiên hơn
tùy chọn Kotlin tương ứng; key chưa từng được gửi thì giữ giá trị của app. Xem các key khác tại
[ObRemoteKeys](src/main/java/io/onboardkit/remote/RemoteKeys.kt).
Native ngoài onboarding làm theo [hướng dẫn Ads](../ads/README.md#native-preload-repeated-show-and-refresh).

## 3. Gắn quảng cáo và nội dung vào màn

Chỉ cấu hình các slot cần dùng; một số slot kế thừa unit dự phòng, được mô tả trong [AdsConfig](src/main/java/io/onboardkit/config/AdsConfig.kt).

| Cấu hình | Màn sử dụng |
|---|---|
| `splashBanner`, `splashInterstitial` | Banner / interstitial ở splash |
| `languageNative`, `languageDupNative` | Native đầu / native thay thế ở màn ngôn ngữ |
| `contentStepNative`, `stepNatives[StepId.OB1]` | Native chung cho trang nội dung / ghi đè riêng từng bước |
| `fullScreenStepNative` | Bước chỉ có quảng cáo; bỏ qua nếu không có unit dùng được |
| `afterOnboardingInterstitial` | Interstitial riêng khi hoàn thành onboarding (`inter_after_ob3`) |
| `appResume` | Điều kiện app-open ở màn ngôn ngữ/nội dung và khi quay lại app |

`defaultSteps()` tạo OB1, Full1, OB2, Full2, OB3, OB4. Để dùng nội dung và ảnh riêng, thay bằng `steps(ContentStepDefinition(...), ...)`; xem [định nghĩa bước](src/main/java/io/onboardkit/config/StepDefinition.kt).
Waterfall native/interstitial nhận `tiers = listOf(highId, fallbackId)` theo thứ tự request; banner nhận một ID.

`AdsConfig.fromAdConfig()`, mặc định của `onboardKitConfig`, gắn các tên JSON chuẩn như `inter_splash`, `native_lang` và giữ chúng cập nhật sau mỗi lần fetch; truyền thêm map cho các tên khác trong app.
`ad_config.json` của app chỉ thay unit viết trong code với các key được gắn theo cách này. Key chuẩn mà `ad_remote_config` trên backend khai báo được áp dụng không cần gắn và được ưu tiên hơn mọi unit viết trong code, kể cả `stepNatives` và `splashInterstitialOldUser`, nên không cần dựng lại config trong `onRemoteFetched()`.
Key gốc khai báo `isEnable: false` là công tắc tổng, tắt luôn mọi tầng `_high*`, nên slot không có ad unit và flow bỏ qua.
[OnboardKitSetup của app mẫu](../app/src/main/java/com/itg/template/app/OnboardKitSetup.kt) có đầy đủ cách ánh xạ và chọn native template.

## Trang fullscreen và interstitial sau onboarding

Cấu hình trong cùng khối `onboardKitConfig` với nội dung của app. `StepId` thuộc
`io.onboardkit.core`; các kiểu còn lại bên dưới thuộc `io.onboardkit.config`.

```kotlin
// Include this among your content pages in steps(...).
AdFullScreenStepDefinition(
    StepId.FULL1,
    skipButtonStyle = FullScreenSkipStyle.CLOSE_ICON, // TEXT for “Skip”
    skipButtonPosition = FullScreenSkipPosition.RIGHT, // LEFT mirrors it to the other side
    skipButtonDelaySec = 1,
    autoNextEnabled = true,
    autoNextDelayMs = 3_000,
)

// Add these fields to your existing AdsConfig(...).
afterOnboardingInterstitial = InterstitialAdUnit("YOUR_INTERSTITIAL_UNIT_ID"),
afterOnboardingInterstitialEnabled = true,
```

Ví dụ trên hiện X sau 1 giây và tự chuyển sau 3 giây tính từ lúc chọn trang (default SDK là 5 và 15 giây);
đặt `autoNextEnabled = false` nếu chỉ muốn chuyển bằng thao tác người dùng. Mặc định quay về
từ ad của step sẽ hoàn thành bước, nên các placement này không preload/show ad thay thế khi click. Remote
`ob_skip_button_delay_sec >= 0` ghi đè delay local; `-1` dùng local.
`AdsConfig.fullScreenSkipStyle` đặt kiểu nút chung cho Full1/Full2/OB5. OB5 độc lập có mặc định riêng:
hiện nút sau 3 giây và tự đóng sau 15 giây.

Với `BehaviorConfig.lockPagerSwipe = false`, OB1 vẫn khóa, OB2/OB3/OB4 cho vuốt, còn trang fullscreen
chỉ cho vuốt sau khi ad đã hiện trong lượt xem đó. Khi đang tải hoặc lỗi, trang fullscreen vẫn khóa vuốt;
X, timeout và tự hoàn thành khi no-fill vẫn hoạt động. Với `swipeCompletesLastStep = true`, vuốt tới ở trang
content cuối đi qua đúng inter thoát như CTA của nó. `lockPagerSwipe = true` tắt luôn cử chỉ này.

`inter_after_ob3` là placement riêng với splash. Provider có sẵn preload khi vào pager và
chờ fill tối đa 8 giây lúc hoàn thành. Mặc định
(`AdsConfig.afterOnboardingInterstitialTiming = NextScreenTiming.UNDER_AD`) màn tiếp theo mở dưới
quảng cáo; entry notification, widget, uninstall chờ đóng quảng cáo. Đặt
`NextScreenTiming.AFTER_AD` (`io.onboardkit.ads`) để luôn chờ đóng.
`afterOnboardingInterstitialEnabled` bật/tắt placement này; remote
`onboarding.exit_interstitial.enabled` hoặc `ob_ads_inter_after_ob3_enabled` đã được gửi về sẽ quyết định
thay, bật hay tắt. Đặt switch local thành `false` nếu app tự quản lý thời điểm hiện ad này; khi remote
không gửi gì, SDK sẽ tắt cả preload lẫn show tự động. Không đưa placement này vào nhóm AutoBuffer của màn nội dung.

## App-open khi quay lại app

Hoàn tất [cấu hình app-open](../ads/README.md#app-open-on-return). `AdsConfig.fromAdConfig()`
đã gắn `appResume` với `open_resume`, và `open_resume` trong `ad_remote_config` trên backend điền slot
này không cần gắn. Nếu tự dựng `AdsConfig` và remote không có entry này, thêm
`appResume = AdRemoteConfig.getInstance().tiersFor("open_resume").takeIf { it.isNotEmpty() }?.let { InterstitialAdUnit(tiers = it) }`
để cả hai cùng đọc placement `open_resume`.
Màn ngôn ngữ và nội dung onboarding cho phép hiện resume ad đã sẵn sàng khi thực sự ra nền/quay lại.
Splash, fullscreen độc lập và khảo sát được loại trừ; trang fullscreen trong pager,
chuyển trang và dialog xác nhận ngôn ngữ tạm chặn resume. Lần quay lại sau khi click quảng cáo
onboarding cũng bỏ qua resume, trừ khi remote đặt `app_open.presentation.skip_after_ad_click` thành `false`.
Chỉ bỏ exclusion do app đặt cho màn ngôn ngữ/nội dung nếu muốn hiện resume tại đó.
SDK quản lý tải và điều kiện màn hình; không cần thêm callback lifecycle của Activity.

## Tích hợp tùy chọn

- **Firebase:** splash fetch flag `ob_*` khi Firebase được cấu hình; host không dùng `ObSplashActivity` nhận các flag này sau `AdConfig.refresh()`. Trước khi fetch xong, dùng giá trị Firebase đã gửi lần gần nhất; key chưa từng được gửi thì giữ cấu hình của app. Fetch xong sau deadline splash vẫn được áp dụng cho phần còn lại của phiên. [ObRemoteKeys](src/main/java/io/onboardkit/remote/RemoteKeys.kt) liệt kê key hỗ trợ. Muốn remote JSON quảng cáo hoặc sink GA4, thêm [suite-firebase](../suite-firebase/README.md); chỉ cài nguồn ad config chưa thực hiện fetch.
- **Paywall:** cài [PayKit](../paykit/README.md) trước, rồi đặt `paywallGate = OnboardKitPaywallGate()` trong `OnboardingSdk.install` (`io.paykit.integration`). Không đặt gate thì bỏ qua paywall. Nếu app có cả mua hàng và quảng cáo, làm thêm bước chờ billing bên dưới.
- **Consent riêng:** giữ `onConsentRequired()` mặc định nếu dùng UMP. Nếu override bằng CMP riêng, công bố kết quả qua `ConsentCenter.setHostConsent(canRequestAds, personalized)` trước khi trả về. Chỉ trả `true` không cấp quyền request; `setCanRequestAds(false)` là giới hạn riêng của host, còn `true` chỉ gỡ giới hạn đó. Nếu override `onDestroy()`, luôn gọi `super.onDestroy()`.
- **Giao diện / khảo sát:** xem [cấu hình màn](src/main/java/io/onboardkit/config/OnboardKitConfig.kt) và [QuestionConfig](src/main/java/io/onboardkit/config/QuestionConfig.kt). Chỉ splash và bước nội dung hỗ trợ ghi đè `layoutRes`; trường layout chưa hỗ trợ sẽ báo lỗi validation. Giữ nguyên ID khi ghi đè resource SDK.

**Mua hàng và quảng cáo:** `PayKit.install()` / khởi tạo BillingKit bắt đầu xác minh giao dịch bất đồng bộ; gọi xong chưa có nghĩa đã khôi phục premium. Hook `onInitBilling()` mặc định đang trống.
Thay splash tối thiểu bên trên bằng override chờ `Billing.awaitReady()` trước giai đoạn quảng cáo splash.
Gọi trực tiếp `Billing` còn cần thêm `implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"` cùng tag; xem [BillingKit](../billingkit/README.md).

```kotlin
import com.ads.module.billing.Billing
import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity() {
    override suspend fun onInitBilling() {
        val readiness = Billing.awaitReady()
        // Apply your app's policy for ReadyResult.Timeout / ReadyResult.Error.
    }
}
```

`Billing.awaitReady()` trả `Ready`, `Timeout` hoặc `Error`; dùng kết quả theo chính sách xử lý lỗi của app. `SplashConfig.billingTimeoutMs` hiện có (mặc định 5.000 ms) cũng giới hạn toàn bộ hook, nên deadline splash có thể hủy hook trước khi có kết quả; timeout không xác định trạng thái mua hàng.
Xem vị trí tích hợp trong [splash mẫu](../app/src/main/java/com/itg/template/ui/component/splash/SplashActivity.kt).

Với entry từ thông báo/widget/uninstall, trỏ về splash bằng [SplashEntry](src/main/java/io/onboardkit/ui/splash/SplashEntry.kt):

```kotlin
import io.onboardkit.ui.splash.SplashEntry

val intent = SplashEntry.WIDGET.intent(context, SplashActivity::class.java)
    .putExtra("widget_action", "open_document")
```

Listener bên trên chuyển tiếp extras cho `Completed`/`Skipped`. Đọc chúng ở cả `onCreate` và `onNewIntent` của màn đích.
Entry dùng `inter_noti`, `inter_widget` hoặc `inter_uninstall`; key thiếu hoặc bị tắt thì chuyển về tệp user (`inter_splash_o` cho user cũ, `inter_splash` cho user mới), và tắt tệp nào thì entry của tệp đó cũng tắt; các entry này luôn điều hướng sau khi đóng quảng cáo, vì màn đích của chúng tự mở thêm một màn và màn đó sẽ che quảng cáo đang hiển thị. Mở từ launcher thì lần đầu vào LFO sau khi đóng quảng cáo, onboarding đã xong thì mở app dưới quảng cáo.

## Xử lý lỗi tích hợp

| Hiện tượng | Kiểm tra |
|---|---|
| Luồng bị bỏ qua ngay | `install()` chạy trước `configure()` và cả hai `Result` không lỗi |
| Kết thúc luồng nhưng chưa vào app | Listener xử lý đủ `Completed`, `Skipped`, `Aborted` |
| `no_provider` / `consent_not_granted` | Đã cài provider; xem `ConsentCenter.canRequestAds()` và giới hạn từ host |
| Không có trang chỉ quảng cáo | `fullScreenStepNative` hoặc giá trị ghi đè trong `stepNatives` dùng được |
| Banner splash tùy chỉnh không xuất hiện | Layout có `ob_splash_ad_container` chứa include `layout_banner_control` |

Bật `OnboardingSdk.setFlowLogging(true)` khi tích hợp (`OB_FLOW` trong Logcat).
Để đổi ngôn ngữ về sau, gọi `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)` (`io.onboardkit.ui.language`).

[Application mẫu](../app/src/main/java/com/itg/template/app/GlobalApp.kt) · [Splash mẫu](../app/src/main/java/com/itg/template/ui/component/splash/SplashActivity.kt) · [Giấy phép MIT](../LICENSE)
