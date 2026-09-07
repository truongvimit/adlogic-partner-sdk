**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# Trackkit

Dùng `Tracker` để gửi event của SDK và app tới Firebase hoặc backend analytics riêng.

[Cấu hình build và chọn module](../README.vi.md) · minSdk 24+ · compileSdk 36+ · JDK 17

## 1. Thêm nơi nhận dữ liệu

Với Firebase Analytics, thêm `suite-firebase`; module này đã cung cấp Trackkit. Hoàn tất [cấu hình Firebase](../suite-firebase/README.md) với `google-services.json` của app và Google Services plugin.

```groovy
// app/build.gradle
def sdkVersion = '5.1.2'
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

Nếu dùng backend riêng, khai báo `com.github.truongvimit.adlogic-partner-sdk:trackkit:$sdkVersion` và implement `TrackSink`. Ads, onboarding, billing và paywall đã cung cấp Trackkit nên không cần khai báo thêm dependency Trackkit.

## 2. Khởi tạo một lần

Gộp đoạn này vào `Application.onCreate()` hiện tại, trước khi các kit khác phát event. Ví dụ dưới dành cho app chỉ có analytics; giữ class Application nền đang dùng nếu có ads. `BuildConfig` là class của app.

```kotlin
import android.app.Application
import io.suite.firebase.FirebaseSink
import io.trackkit.Tracker
import io.trackkit.TrackerConfig

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Tracker.install(this, TrackerConfig(
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            strictValidation = BuildConfig.DEBUG,
        ))
        Tracker.addSink(FirebaseSink())
    }
}
```

Đăng ký `App` vào `android:name` của thẻ `<application>` trong manifest. Nếu Application đã khởi tạo Tracker, chỉ thêm sink còn thiếu. Mỗi sink ID chỉ đăng ký một lần.

## 3. Gửi event của app

```kotlin
Tracker.track("app_document_open", mapOf("file_type" to "pdf"))
Tracker.screen("document_reader")
```

Dùng tên event cố định và truyền giá trị qua tham số; không đưa tên file hay dữ liệu người dùng vào tên event. Tên bắt đầu bằng chữ cái, chỉ gồm chữ, số, dấu gạch dưới và tối đa 40 ký tự.

Ads, onboarding, billing và PayKit tự phát event SDK. Không gửi lặp lại từ callback của app. Tên và tham số có trong [TrackkitEvents](src/main/java/io/trackkit/TrackkitEvents.kt).

## Nối consent

Khi dùng `ConsentCenter` của module ads, consent tự được gửi sang Tracker. Mapping hiện tại là `analytics = true` và `ads = personalized`; đây không phải quyền request quảng cáo. Đọc quyền request bằng `ConsentCenter.canRequestAds()`.

Nếu có flow consent riêng và không dùng `ConsentCenter`, gửi kết quả thực tế:

```kotlin
fun onConsentResolved(analyticsAllowed: Boolean, adsPersonalizationAllowed: Boolean) {
    Tracker.setConsent(analytics = analyticsAllowed, ads = adsPersonalizationAllowed)
}
```

Không để hai nơi cập nhật consent ghi đè nhau. Lần cập nhật tiếp theo của `ConsentCenter` sẽ thay thế consent đã set trực tiếp cho Tracker.

`TrackerConfig.consentPolicy` mặc định `SEND_ALWAYS`. Nếu app cần giữ event tới khi consent được giải quyết, cấu hình `QUEUE_UNTIL_RESOLVED` trước khi install Tracker. Xem [TrackerConfig và ConsentPolicy](src/main/java/io/trackkit/TrackkitApi.kt) cùng [tùy chọn consent Firebase](../suite-firebase/README.md).

## Kiểm tra tích hợp

Mở Logcat, lọc `Trackkit`. Thêm console sink ở bản debug để xem event được phát:

```kotlin
import io.trackkit.sink.ConsoleSink

// Application.onCreate(), after Tracker.install(...)
if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
```

| Vấn đề | Kiểm tra |
| --- | --- |
| Không nhận được event | Xác nhận `Tracker.install` và sink đã được đăng ký trước khi phát event; xem `Tracker.sinkIds()`. |
| Event bị giữ/bỏ | Xem `Tracker.currentConsent` và `consentPolicy` đang dùng. |
| Event SDK bị lặp | Bỏ phần tự gửi lại trong callback ads/onboarding; mỗi nơi nhận chỉ đăng ký một lần. |
| Validation ném lỗi | Dùng `strictValidation = BuildConfig.DEBUG`, không đặt `true` trong release. |

## Nâng cấp từ 5.0.0

Code khởi tạo giữ nguyên. Native bind chuyển sang `fo_ad_bound`, tách khỏi `ad_show` do vendor xác nhận. Sửa báo cáo nếu trước đây đang tính callback bind onboarding như impression thật.

## Backend riêng và tùy chọn khác

Với nơi nhận riêng, implement `TrackSink.id` và `onEvent`; thêm `onScreen` nếu dùng `Tracker.screen`. Callback sink chạy trên thread của bên gọi: đưa tác vụ mạng vào hàng đợi, không chặn thread. Xem [TrackSink](src/main/java/io/trackkit/TrackkitApi.kt), [ConsoleSink](src/main/java/io/trackkit/sink/ConsoleSink.kt) và [Tracker](src/main/java/io/trackkit/Tracker.kt) cho tham số mặc định, user property và API doanh thu.

## License

MIT — xem [LICENSE](../LICENSE).
