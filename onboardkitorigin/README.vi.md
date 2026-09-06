# OnboardKit

Splash → chọn ngôn ngữ → onboarding → câu hỏi/paywall tùy chọn → app của bạn.
SDK quản lý chuyển màn, tải trước quảng cáo và lưu tiến trình; app cung cấp nội dung và màn đích cuối cùng.

[English](README.md) · [हिन्दी](README.hi.md)

## Trước khi tích hợp

- Dùng minSdk 24, compileSdk 36 và JDK 17. Làm theo [cấu hình build chung](../README.md) và dùng cùng một tag đã phát hành cho mọi module.
- Với provider quảng cáo tích hợp sẵn, hoàn thành [hướng dẫn ads](../ads/README.md) trước: metadata AdMob/Meta, asset cấu hình quảng cáo và `ERainAd.init()` trong Application.
- Nếu cần funnel, cài `Tracker` và sink trước OnboardKit; xem [Trackkit](../trackkit/README.md).
- Thêm cả hai dependency bên dưới. OnboardKit export Trackkit; code app dùng `com.ads.module.*` vẫn cần khai báo `ads` tường minh. Firebase và PayKit là tùy chọn.

```groovy
def sdkVersion = '5.1.1'
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
Khi chuyển tới app, dùng `NEW_TASK` và không thêm `CLEAR_TASK`: quảng cáo splash có thể vẫn cần Activity đang giữ nó.

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
        android:configChanges="orientation|screenSize|keyboardHidden"
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

Các mặc định cần biết:

- `notificationPermissionEnabled = true`: Android 13+ / target 33+ hỏi quyền thông báo sau consent. Đã cấp quyền hoặc đã ghi nhận kết quả hỏi tự động thì không hỏi lại; từ chối vẫn đi tiếp. Đặt `false` nếu app tự quản lý lời nhắc này.
- `noInternetPromptEnabled = true`: splash yêu cầu kết nối mạng trước khi tiếp tục. Đặt `false` nếu app cần cho phép mở offline.
- `lockPortrait = true`: các màn SDK, gồm splash kế thừa của app, bị khóa dọc. App hỗ trợ ngang cần đặt `false` và kiểm tra cả quy tắc hướng màn hình trong merged manifest.
- `consentTimeoutMs = 20_000`: luồng UMP mặc định do SDK quản lý **không giới hạn thời gian người dùng trả lời**. Ngân sách này vẫn giới hạn custom hook khi không có luồng consent do SDK quản lý đang chạy.
- Request quảng cáo splash và đồng hồ hiển thị bắt đầu sau khi các hộp thoại kết thúc và splash lấy lại focus ở foreground. SDK tải trước màn kế tiếp trước khi điều hướng; không cần thêm một luồng load-and-show khác.

## 3. Gắn quảng cáo và nội dung vào màn

Chỉ cấu hình các slot cần dùng; một số slot kế thừa unit dự phòng, được mô tả trong [AdsConfig](src/main/java/io/onboardkit/config/AdsConfig.kt).

| Cấu hình | Màn sử dụng |
|---|---|
| `splashBanner`, `splashInterstitial` | Banner / interstitial ở splash |
| `languageNative`, `languageDupNative` | Native đầu / native thay thế ở màn ngôn ngữ |
| `contentStepNative`, `stepNatives[StepId.OB1]` | Native chung cho trang nội dung / ghi đè riêng từng bước |
| `fullScreenStepNative` | Bước chỉ có quảng cáo; bỏ qua nếu không có unit dùng được |

`defaultSteps()` tạo OB1, OB2, OB3 (chỉ quảng cáo), OB4. Để dùng nội dung và ảnh riêng, thay bằng `steps(ContentStepDefinition(...), ...)`; xem [định nghĩa bước](src/main/java/io/onboardkit/config/StepDefinition.kt).
Waterfall native/interstitial nhận `tiers = listOf(highId, fallbackId)` theo thứ tự request; banner nhận một ID.

Tên JSON như `inter_splash`, `native_lang` cần được app gắn vào `AdsConfig`; SDK không tự suy ra mọi ánh xạ từ tên trường.
Dùng `AdRemoteConfig.getInstance().tiersFor(key)` và dựng lại config khi ID mới đã cập nhật trong `onRemoteFetched()`.
[OnboardKitSetup của app mẫu](../app/src/main/java/com/itg/template/app/OnboardKitSetup.kt) có đầy đủ cách ánh xạ và chọn native template.

## Tích hợp tùy chọn

- **Firebase:** splash fetch flag `ob_*` khi Firebase được cấu hình; nếu chưa có thì dùng cache/mặc định. [ObRemoteKeys](src/main/java/io/onboardkit/remote/RemoteKeys.kt) liệt kê key hỗ trợ. Muốn remote JSON quảng cáo hoặc sink GA4, thêm [suite-firebase](../suite-firebase/README.md); chỉ cài nguồn ad config chưa thực hiện fetch.
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
Entry dùng `inter_noti`, `inter_widget` hoặc `inter_uninstall`, dự phòng bằng unit splash thường; các entry này điều hướng sau quảng cáo, còn launcher thường mở màn tiếp theo ở dưới quảng cáo.

## Nâng cấp từ 5.0.0

- Giữ cách tích hợp `install → configure → splash` và cập nhật mọi module cùng phiên bản.
- Bỏ timeout tự đóng form UMP của SDK hoặc điều hướng khi form còn mở. Không cấp consent từ timeout hay boolean callback.
- Kiểm tra app nào sở hữu lời nhắc thông báo và hành vi khóa dọc theo mặc định bên trên.
- Native bind nay phát `fo_ad_bound`; dùng `ad_show` để đếm hiển thị quảng cáo thật. Cập nhật dashboard từng coi bind là impression.
- Khi thay native ở ngôn ngữ/câu hỏi, ad hiện tại được giữ trong lúc chờ. OB5 đếm lại khi trở về foreground. App không cần thêm lời gọi mới.

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
