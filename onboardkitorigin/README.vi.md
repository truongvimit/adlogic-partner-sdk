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
def sdkVersion = '5.1.2'
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
- Splash có thể tải ads đã được cho phép dưới hộp thoại notification khi còn hiển thị; nhấn Home sẽ chặn request mới. Minimum bắt đầu cùng pha tải ads và chạy chồng với loading/notification. Inter ready được show ngay, còn chuyển màn chỉ đợi phần minimum còn thiếu.

### Thời gian hiện bàn tay tại LFO

Remote Config `ob_language_tap_hint_delay_sec` là số nguyên giây, mặc định **3**.
`0` hiện ngay; giá trị âm hoặc không hợp lệ dùng lại mặc định 3 giây.
Chỉ có hiệu lực khi cả `LanguageConfig.tapHintEnabled` và `ob_show_language_tap_hint`
đều bật. Chọn ngôn ngữ trước khi hết giờ sẽ hủy bàn tay; bàn tay đang hiện cũng ẩn
khi chọn. SETTINGS và trường hợp có `defaultCode` không hiện bàn tay.

### Thử nghiệm preload Splash → Language

`ob_splash_lfo_parallel_preload_enabled` là Boolean, mặc định **false**. Chia nhóm ổn định bằng Firebase Remote Config/A/B Testing; SDK không tự random.

- **false / sequential (A):** preload LFO1 sau khi toàn bộ waterfall inter splash kết thúc (loaded, failed, skipped), hoặc hết budget chờ tại splash.
- **true / parallel (B):** preload LFO1 cùng pha tải splash khi đã biết remote, đích đến Language và các điều kiện cho phép request.

Chỉ đổi lịch LFO1; OB1/LFO2 giữ trigger cũ. `SAME_TIME`/`ALTERNATE` vẫn độc lập điều khiển thứ tự remote và tải inter. LFO1 ready thì bind, loading thì join request cũ; preload đã failed không retry ngay khi vào Language. Ads hết hạn/cache rỗng vẫn tải bình thường.

Mode và attempt được giữ trong bộ nhớ khi Activity tạo lại. Fetch lỗi giữ snapshot remote đã cache. Chỉ cần xem console: `splash_lfo attempt=<id> mode=sequential|parallel reason=<trigger>`; không thêm analytics riêng cho thử nghiệm.

`ob_splash_ad_budget_ms` (mặc định 60.000 ms) bắt đầu một lần sau khi notification kết thúc/bỏ qua và splash resumed + có focus. Banner dùng chung deadline; thời gian ở nền/recreation không được cấp lại, inter về trễ không được khôi phục lượt show đã hết hạn. `ob_splash_notification_settle_ms` mặc định **0**, nếu bật sẽ tính từ lúc có kết quả quyền. Giữ nguyên minimum (3.000 ms), banner wait (0 ms), floor và timeout mạng từng tier. Splash vẫn dùng `show()` hiện tại.

### Lưu ý tích hợp cho partner

Với `ObSplashActivity` và `ERainAdProvider` có sẵn, giữ cách install/configure hiện tại.
SDK quản lý preload, notification, timer và chuyển màn; không cần thêm delay hay gọi
`loadAndShow()` ở splash. Native ngoài onboarding dùng
[manager dùng chung và helper theo màn/view](../ads/README.md#native-preload-repeated-show-and-refresh).

Mặc định **minimum 3 s chặn chuyển màn, không chặn show inter**. Với launcher `UNDER_AD`,
inter show ở giây 1 của pha ads thì bên dưới vẫn có thể là splash tới giây 3, sau đó mới mở
màn kế tiếp dưới inter. Nếu show ở giây 5 thì không còn minimum phải đợi.
**Budget 60 s giới hạn chờ ads sau notification**, không phải tổng thời gian khởi động hay
thời gian người dùng xem inter. Remote config có thể ghi đè các mặc định này.

Nếu tự cài [OnboardingAdProvider](src/main/java/io/onboardkit/ads/OnboardingAdProvider.kt),
cần đáp ứng contract native mới: preload theo placement phải join request đang tải và skip
khi còn ads chưa dùng hợp lệ; bind thành công lấy ads khỏi cache chưa dùng. Trả kết quả lỗi
preload qua `isNativeLoadFailed()` để Language không retry ngay (mặc định tương thích là `false`).
`releaseNative()` kết thúc phần hiển thị nhưng giữ request chung và ads chưa dùng.
Kiểm tra lại điều kiện foreground trước khi chạy request đang chờ; `allowWhileVisible` chỉ
cho phép splash còn hiển thị dưới prompt notification của nó. Provider có sẵn đã xử lý các yêu cầu này.

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
