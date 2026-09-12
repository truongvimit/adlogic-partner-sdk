# Trackkit — event của app và SDK

[← Chọn hướng dẫn](README.vi.md) · [API Trackkit](../trackkit/README.vi.md)

Ba việc cần làm: install Tracker, thêm nơi nhận event (sink), rồi track event riêng của app. Ads, OnboardKit, BillingKit và PayKit đã tự phát event của chúng.

## 1. Dependency và event catalog

Theo [build setup](../README.vi.md#cấu-hình-build), JDK 17 và `minSdk 24+`. Ads, OnboardKit, BillingKit, PayKit và suite-firebase đã cung cấp Trackkit qua API dependency; không cần thêm lần nữa. App chỉ dùng Trackkit thì thêm:

```groovy
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit:$sdkVersion"
}
```

Nếu có event riêng, copy [AppEvents.kt](examples/trackkit/AppEvents.kt) vào package app và đổi key theo tính năng thật. Không cần catalog cho event SDK hay JSON analytics.

## 2. Install và chọn nơi nhận event

Ghép vào Application hiện có, trước install Ads/OnboardKit/BillingKit/PayKit. Không tạo một Application thứ hai nếu đã làm guide ads.

```kotlin
package com.example.app

import android.app.Application
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import io.trackkit.sink.ConsoleSink

class AnalyticsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Tracker.install(this, TrackerConfig(appVersionCode = BuildConfig.VERSION_CODE.toLong()))
        if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
        // Thêm sink production cần dùng tại đây, trước các SDK phát event.
    }
}
```

Đăng ký `android:name` nếu app chưa có Application. Giữ BuildConfig của module app, không import BuildConfig của thư viện. Nếu app đã install Tracker từ guide ads, chỉ thêm sink còn thiếu tại cùng vị trí.

| App muốn nhận event ở đâu | Bước nối thêm |
| --- | --- |
| Logcat khi debug | `ConsoleSink()` trong mẫu; không cần dịch vụ ngoài. |
| Firebase Analytics | [Guide Firebase, bước 2](firebase-integration.vi.md#2-gửi-analytics-qua-tracker). |
| Dashboard kiểm tra ads | [Guide AdTracer](adtracer-integration.vi.md), bridge chỉ ở debug. |
| Adjust trong luồng ads/OB | Không phải sink Tracker; SDK ads tự khởi tạo Adjust và gửi doanh thu. Chỉ điền token theo [Setup Adjust](ads-onboarding-integration.vi.md#adjust-token-và-kiểm-tra). |
| Analytics backend riêng | Implement `TrackSink` và `Tracker.addSink`; bắt buộc `id`, `onEvent`, thêm callback screen/identity/consent nếu dùng. Đưa network vào queue của app vì callback chạy trên thread gọi. |

**Cài sink trước khi phát event.** Event dispatch khi không có sink sẽ mất; Tracker không lưu hàng đợi trên đĩa. Sink trùng `id` bị bỏ qua; kiểm tra bằng `Tracker.sinkIds()`.

## 3. Track từ điểm thao tác thực tế

Ví dụ gọi sau khi người dùng thực sự mở một tính năng, với tên tính năng do app xác định:

```kotlin
package com.example.app

import io.trackkit.Tracker

fun trackFeatureOpened(featureName: String) {
    Tracker.track(AppEvents.FEATURE_OPEN, mapOf(AppEvents.FEATURE_NAME to featureName))
}

fun trackHomeVisible() {
    Tracker.screen(AppEvents.HOME_SCREEN, "HomeActivity")
}
```

Mỗi event chỉ gọi ở một điểm: sau navigation hoặc khi màn hình hiển thị. Với Compose dùng lifecycle/effect, không gọi mỗi lần recompose. Firebase có thể tự track Activity; chỉ bổ sung screen thủ công khi cần tên màn khác và tránh đếm lặp.

Tên event/param bắt đầu bằng chữ, chỉ dùng chữ/số/`_`, tối đa 40 ký tự; không dùng prefix `firebase_`, `google_`, `ga_`. Mỗi event tối đa 25 param **kể cả defaults**; dùng String (tối đa 100 ký tự), number hoặc Boolean, không gửi dữ liệu nhạy cảm. Bật `strictValidation` khi QA để bắt key sai, param dư hoặc string quá dài.

**Không log lại ad request/show/impression/revenue, OB funnel hay purchase/paywall trong callback UI.** SDK đã phát các event này, kể cả paid impression qua `Tracker.adRevenue`.

## 4. Bảng cấu hình

| Option | Default SDK / khi cần đổi |
| --- | --- |
| `appVersionCode` | 0; mẫu lấy `BuildConfig.VERSION_CODE` của app. |
| `sdkVersion` | SDK tự cấp metadata; không set/hardcode trong app. |
| `reportingCurrency` | `USD`; đổi khi app cần đơn vị cho cumulative revenue. Impression khác currency vẫn gửi sink nhưng không cộng vào accumulator này; không có quy đổi FX tự động. |
| `consentPolicy` | `SEND_ALWAYS`: dispatch ngay, sink xử lý consent/collection. Không đồng nghĩa người dùng đã đồng ý. |
| `consentPolicy = ConsentPolicy.QUEUE_UNTIL_RESOLVED` | Chờ analytics hết `UNKNOWN`, rồi dispatch cả khi `DENIED`; sink nhận consent để xử lý. Không phải chế độ chỉ gửi khi granted. |
| `consentPolicy = ConsentPolicy.DROP_UNTIL_GRANTED` | Bỏ event khi analytics chưa granted; không replay các event đã bỏ. |
| `strictValidation` | `false`: log lỗi, bỏ tên/key sai và param dư, cắt string dài. Đặt `BuildConfig.DEBUG` để ném exception khi QA. |
| `logLevel` | 1 warnings; 0 off, 2 verbose. |
| `enableRevenueAccumulator` | `true`; chỉ tắt nếu app không dùng các event tổng doanh thu/milestone do Tracker phát. |
| `defaultParams` / `Tracker.setDefaults(...)` | Rỗng; SDK đã gắn `app_vc`, `sdk_ver`, `session_no`, `install_day`, `consent_ads` (sau consent). Chỉ thêm vài key: defaults được giữ trước khi cắt param dư. |
| `Tracker.setUserId(...)`, `setUserProperty(...)` | Chỉ đặt khi app cần identity, dùng `null` để xóa khi logout. Giá trị user property bị cắt còn 36 ký tự và log cảnh báo. |
| `ConsoleSink(tag, ringSize)` | `Trackkit/Console`, 100; debug log và lịch sử trong bộ nhớ, không phải storage analytics. |

App dùng `ConsentCenter` đã được map analytics granted, ads theo personalization; không cần gọi thêm. Nếu app dùng flow consent riêng thay cho bridge này, nối quyết định tại một chỗ:

```kotlin
package com.example.app

import io.trackkit.Tracker

fun onAppConsentResolved(analyticsAllowed: Boolean, adsPersonalizationAllowed: Boolean) {
    Tracker.setConsent(analytics = analyticsAllowed, ads = adsPersonalizationAllowed)
}
```

Chọn một nơi sở hữu consent, tránh flow app và `ConsentCenter` ghi đè nhau. `Tracker.currentConsent` dùng để kiểm tra mapping; consent Tracker không thay thế điều kiện SDK quảng cáo được phép request.

## 5. Kiểm tra và file cần có

- [ ] Install trước event SDK, `sinkIds()` đúng các nơi nhận; ConsoleSink chỉ bật debug.
- [ ] Event app có key tập trung và phát đúng một lần tại điểm sở hữu; screen không bị đếm lặp.
- [ ] Thử consent unknown/granted/denied với policy app chọn, logout xóa identity nếu có.
- [ ] Revenue/purchase từ SDK không bị gửi lặp ở callback UI.

Chỉ cần Gradle, Application hiện có, [AppEvents.kt](examples/trackkit/AppEvents.kt) và điểm track trong màn app. Chỉ tạo `TrackSink` riêng khi thật sự có backend chưa được hỗ trợ; không cần thêm một lớp wrapper quanh mọi SDK event.
