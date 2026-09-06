# Trackkit

> Một facade analytics duy nhất mà mọi module báo cáo qua, và mỗi vendor là một sink.

`io.trackkit.Tracker` xử lý analytics của SDK do `:ads`, `:onboardkitorigin`, `:paykit` và
`:billingkit` phát ra. App có thể gửi event riêng qua cùng facade để áp dụng consent, tham số mặc
định, kiểm tra tên theo chuẩn GA4, khử trùng lặp và cộng dồn doanh thu quảng cáo. Phần lõi không phụ
thuộc SDK vendor; các sink kết nối nó với dịch vụ analytics.

English: [README.md](README.md) ·
हिन्दी: [README.hi.md](README.hi.md)

## Yêu cầu

| | |
|---|---|
| minSdk / compileSdk / JDK | 24 / 36 / 17 |
| Thêm gì vào build của bạn | không dependency vendor nào, không permission, không luật R8; `consumer-rules.pro` đã nằm trong AAR |

## Cài đặt

```groovy
repositories { google(); mavenCentral(); maven { url 'https://jitpack.io' } }
def sdkVersion = '<tag>' // https://github.com/truongvimit/adlogic-partner-sdk/tags
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit:$sdkVersion"
    // FirebaseSink nằm ở đây.
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

`:ads`, `:onboardkitorigin`, `:paykit`, `:billingkit` và `:suite-firebase` đều khai báo
`api project(':trackkit')`, nên chỉ cần có một trong số đó là `Tracker` đã nằm trên classpath; chỉ tự
khai báo khi bạn viết một `TrackSink` độc lập.

## Tích hợp

Gọi `Tracker.install` sau `super.onCreate()` và trước khi khởi tạo các module phát event, rồi đăng
ký các sink.

```kotlin
override fun onCreate() {
    super.onCreate()
    Tracker.install(this, TrackerConfig(
        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        strictValidation = BuildConfig.DEBUG,
    ))
    Tracker.addSink(FirebaseSink(collectionFollowsConsent = false))
    if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
}
```

SDK báo cáo vòng đời và impression có doanh thu của các đối tượng quảng cáo do nó quản lý. Sink
nhận các báo cáo đó mà không cần bọc callback của host. Với tích hợp quảng cáo riêng, cung cấp
placement qua `PlacementRegistry` hoặc payload event của bạn.

Event phát ra trước `install()` sẽ được buffer — 128 mục, sau đó những cái cũ nhất bị bỏ kèm cảnh
báo. Lần `install()` thứ hai bị bỏ qua. Không có sink nào thì mọi event vẫn được validate rồi bỏ đi.

`TrackerConfig` giữ phần còn lại — đơn vị tiền tệ báo cáo, chính sách consent, mức log, bộ cộng dồn
doanh thu, tham số mặc định — mỗi thứ đều có KDoc. Giá trị mặc định đã dùng được; chỉ set thứ khác đi.

Ngoài ra trên `Tracker`: `track(name, params)`, `track(TrackEvent)`, `screen(name, screenClass)`,
`adRevenue(impression)`, `setDefault`, `setDefaults`, `setUserProperty`, `setUserId`, `removeSink`,
`flushPending()`, `sinkIds()`, cùng hai property `isInstalled` / `currentConsent`.

## Consent

Khi dùng `com.ads.module.consent.ConsentCenter`, mỗi lần cập nhật consent sẽ gọi
`Tracker.setConsent(analytics = true, ads = personalized)`. Đây là cách ánh xạ hiện tại của SDK;
trục ads mô tả cá nhân hóa, không phải quyền gửi request quảng cáo. Dùng
`ConsentCenter.canRequestAds()` để kiểm tra quyền gửi request.

Cần điều phối các lần cập nhật consent: lần cập nhật tiếp theo từ `ConsentCenter` sẽ ghi đè lời gọi
`Tracker.setConsent` trực tiếp, kể cả giá trị analytics. Khi không dùng `ConsentCenter`, host cung
cấp consent cho Tracker từ flow riêng. Consent analytics và cá nhân hóa quảng cáo là hai thiết
lập riêng; chỉ từ chối ads không tắt analytics theo cách ánh xạ này.

## Event

`io.trackkit.TrackkitEvents` chứa mọi tên event mà bộ SDK phát ra, nhóm theo domain — quảng cáo,
doanh thu, funnel mở app lần đầu, IAP, consent — và `TrackkitEvents.all()` trả về toàn bộ tập lúc
runtime. Hãy mở nó trong IDE thay vì chép lại một danh sách sẽ cũ đi; mỗi class event đều tự mô tả ý
nghĩa của nó.

Event gửi qua `Tracker.track` còn mang theo `app_vc`, `sdk_ver`, `session_no`, `install_day`, và
`consent_ads` sau khi consent được cập nhật.

Dùng các hằng `PARAM_*` trên `TrackkitEvents` khi cấu hình custom dimension cho báo cáo GA4.
`Tracker.screen()` gọi `TrackSink.onScreen`; `FirebaseSink` chuyển lời gọi này thành event
`screen_view` của Firebase.

## Event tự định nghĩa

```kotlin
Tracker.track(SimpleEvent("app_widget_pinned", mapOf("source" to "home")))
```

Hãy thêm hẳn một class vào `TrackkitEvents` khi có nhiều hơn một module phát event đó, khi một
dashboard hay một Adjust token phụ thuộc vào nó, hoặc khi cách viết tham số của nó phải ổn định qua
các bản phát hành.

| Quy tắc cho mọi tên và key tham số | Giới hạn | Khi vi phạm |
|---|---|---|
| Ngữ pháp | `[a-zA-Z][a-zA-Z0-9_]{0,39}` | event bị từ chối, key tham số bị bỏ |
| Số tham số mỗi event | 25 | key thừa bị bỏ |
| Giá trị tham số chuỗi / giá trị user property | 100 / 36 ký tự | bị cắt bớt |
| Tiền tố dành riêng | `firebase_`, `google_`, `ga_` | bị từ chối |
| Key PII / bí mật | purchase token, `email`, `phone`, `device_id`, `android_id`, `gaid`, `idfa`, `advertising_id` | bị từ chối |

Quy ước nằm trên bộ validator: `<domain>_<object>_<action>`, chữ thường `snake_case`, domain thuộc
`ad_`, `fo_`, `iap_`, `consent_`, `app_`. Tuyệt đối đừng nhét biến vào tên — một `fo_step_complete`
mang tham số `step`, chứ không phải `ob1_complete` cộng `ob2_complete`.

## Viết sink riêng

Chỉ `id` và `onEvent` là bắt buộc — `onInstall`, `onScreen`, `onUserProperty`, `onUserId`,
`onConsent` và `onAdRevenue` đều có mặc định rỗng, kể cả trong Java.

```kotlin
class MyBackendSink(private val api: MyApi) : TrackSink {
    override val id: String = "my_backend"

    override fun onEvent(name: String, params: Map<String, Any?>) {
        api.enqueue(name, params)
    }
    // impression.value đã ở đúng impression.currency — đừng quy đổi.
    override fun onAdRevenue(impression: AdImpression) {
        api.enqueueRevenue(impression.value, impression.currency)
    }
}

Tracker.addSink(MyBackendSink(api))
```

Mọi callback chạy trên thread của phía gọi và không được block. Một sink ném exception sẽ bị bắt và
log kèm `id` của nó; các sink còn lại vẫn nhận được event. `addSink` bỏ qua `id` đã đăng ký. Tham số
đến nơi đã được làm sạch: không có null, chuỗi cắt ở 100 ký tự, tối đa 25 key. Cho build debug,
`io.trackkit.sink.ConsoleSink` log đúng payload đó.

## Xử lý sự cố

| Hiện tượng | Nguyên nhân | Cách xử lý |
|---|---|---|
| Không gì tới được vendor; logcat báo `install() ran with no sink` | chưa đăng ký sink nào | `Tracker.addSink(FirebaseSink())` hoặc `TrackSink` của bạn |
| `N events were dropped before install (buffer overflow)` | hơn 128 event phát ra trước `install()` | cài Tracker trước khi các module bắt đầu phát event |
| `install() called twice` hoặc `sink 'x' already registered` | gọi `install()` hai lần, hoặc hai sink trùng `id` | giữ một `install()`; cho mỗi sink một `id` riêng |
| Mọi event ngừng ngay sau khi mở app | `consentPolicy = DROP_UNTIL_GRANTED` và consent analytics chưa được cấp | kiểm tra `Tracker.currentConsent` và nơi chịu trách nhiệm cập nhật consent |
| `ad_revenue_total` đứng ở 0 dù `ad_impression` vẫn về | impression ở đơn vị tiền khác `reportingCurrency` | đặt `TrackerConfig.reportingCurrency` bằng đơn vị tiền của tài khoản |
| `IllegalArgumentException: Trackkit: …` trên production | `strictValidation` còn `true` ở bản release | nối nó với `BuildConfig.DEBUG` |

## License

MIT — xem [LICENSE](../LICENSE).
