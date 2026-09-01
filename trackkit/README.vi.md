# Trackkit

> Một facade analytics duy nhất mà mọi module báo cáo qua, và mỗi vendor là một sink.

Mọi thứ mà app, `:ads`, `:onboardkitorigin`, `:paykit` và `:billingkit` phát ra đều đi qua
`io.trackkit.Tracker`, nhờ đó việc chặn theo consent, tham số mặc định, kiểm tra tên theo chuẩn GA4,
khử trùng lặp và cộng dồn doanh thu quảng cáo chỉ được cài đặt một lần. Phần lõi không phụ thuộc
vendor: vendor đến dưới dạng các module sink riêng.

Phân tầng module: **[ARCHITECTURE.md](ARCHITECTURE.md)** · English: [README.md](README.md) ·
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

Gọi `Tracker.install` ở dòng đầu tiên của `Application.onCreate()`, rồi đăng ký các sink.

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

Đó là toàn bộ phần tích hợp. Bạn không cần bọc callback quảng cáo và cũng không cần map ad unit sang
placement: `:ads` là nơi tạo ra các đối tượng quảng cáo, nên `:ads` báo cáo vòng đời quảng cáo và các
impression có doanh thu.

Event phát ra trước `install()` sẽ được buffer — 128 mục, sau đó những cái cũ nhất bị bỏ kèm cảnh
báo. Lần `install()` thứ hai bị bỏ qua. Không có sink nào thì mọi event vẫn được validate rồi bỏ đi.

`TrackerConfig` giữ phần còn lại — đơn vị tiền tệ báo cáo, chính sách consent, mức log, bộ cộng dồn
doanh thu, tham số mặc định — mỗi thứ đều có KDoc. Giá trị mặc định đã dùng được; chỉ set thứ khác đi.

Ngoài ra trên `Tracker`: `track(name, params)`, `track(TrackEvent)`, `screen(name, screenClass)`,
`adRevenue(impression)`, `setDefault`, `setDefaults`, `setUserProperty`, `setUserId`, `removeSink`,
`flushPending()`, `sinkIds()`, cùng hai property `isInstalled` / `currentConsent`.

## Consent

Đừng gọi `Tracker.setConsent` khi `:ads` có trên classpath.
`com.ads.module.consent.ConsentCenter` là nơi duy nhất gọi nó, xử lý UMP cho cả process, và truyền
`Tracker.setConsent(analytics = true, ads = personalized)`.

Trục analytics luôn là `true`: UMP chỉ hỏi về quảng cáo, nên một lần từ chối không được phép xóa luôn
`first_open`, retention và funnel. Chỉ tự gọi nó trong app không có `:ads`, và gọi từ một chỗ duy
nhất.

## Event

`io.trackkit.TrackkitEvents` chứa mọi tên event mà bộ SDK phát ra, nhóm theo domain — quảng cáo,
doanh thu, funnel mở app lần đầu, IAP, consent — và `TrackkitEvents.all()` trả về toàn bộ tập lúc
runtime. Hãy mở nó trong IDE thay vì chép lại một danh sách sẽ cũ đi; mỗi class event đều tự mô tả ý
nghĩa của nó.

Mọi event còn mang theo `app_vc`, `sdk_ver`, `session_no`, `install_day`, và `consent_ads` sau khi
UMP có kết quả.

**Một bước bạn phải tự làm:** GA4 có lưu custom parameter nhưng sẽ không báo cáo chúng cho tới khi
được đăng ký làm custom dimension. Hãy đăng ký những tham số mà dashboard của bạn cần — các hằng số
nằm trên `TrackkitEvents` dưới dạng `PARAM_*` — nếu không chúng chỉ hiện trong DebugView và BigQuery.
Screen view không phải event: `Tracker.screen()` để mỗi sink tự phát theo cách của nó.

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
| `N events were dropped before install (buffer overflow)` | hơn 128 event phát ra trước `install()` | chuyển `Tracker.install` lên dòng đầu của `onCreate()` |
| `install() called twice` hoặc `sink 'x' already registered` | gọi `install()` hai lần, hoặc hai sink trùng `id` | giữ một `install()`; cho mỗi sink một `id` riêng |
| Mọi event ngừng ngay sau khi mở app | `consentPolicy = DROP_UNTIL_GRANTED` và consent analytics chưa được cấp | với `:ads` thì trục analytics luôn được cấp; kiểm tra `ConsentCenter.request` đã chạy chưa |
| `ad_revenue_total` đứng ở 0 dù `ad_impression` vẫn về | impression ở đơn vị tiền khác `reportingCurrency` | đặt `TrackerConfig.reportingCurrency` bằng đơn vị tiền của tài khoản |
| `IllegalArgumentException: Trackkit: …` trên production | `strictValidation` còn `true` ở bản release | nối nó với `BuildConfig.DEBUG` |

## License

MIT — xem [LICENSE](../LICENSE).
