**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# Trackkit

> Một facade analytics duy nhất để mọi module báo cáo qua, kèm một sink cho mỗi vendor.

Mọi thứ mà app, `:ads`, `:onboardkitorigin`, `:paykit` và `:billingkit` phát ra đều đi qua
`io.trackkit.Tracker`, nên việc chặn theo consent, default param, kiểm tra tên GA4, dedupe và cộng dồn
doanh thu quảng cáo chỉ phải làm một lần. Phần lõi không dính vendor: vendor nằm ở các module sink riêng.

Phân tầng module: **[ARCHITECTURE.md](ARCHITECTURE.md)**

## Requirements

| | |
|---|---|
| minSdk / compileSdk | 24 / 36 |
| JDK | 17 |
| Thêm vào build của bạn | không kéo theo vendor nào — một artifact annotation `compileOnly`, không permission, không luật R8; `consumer-rules.pro` đã nằm sẵn trong AAR |

## Installation

```groovy
repositories { google(); mavenCentral(); maven { url 'https://jitpack.io' } }
def sdkVersion = '<tag>' // https://github.com/truongvimit/adlogic-partner-sdk/tags
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit:$sdkVersion"
    // FirebaseSink lives here.
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

`:ads`, `:onboardkitorigin`, `:paykit`, `:billingkit` và `:suite-firebase` đều khai báo
`api project(':trackkit')`, nên chỉ cần có một trong số đó là `Tracker` đã sẵn trên classpath; chỉ tự
khai báo khi bạn viết một `TrackSink` độc lập. Giữ mọi module ở cùng một tag.

## Quick start

Gọi `Tracker.install` ở dòng đầu tiên của `Application.onCreate()`, rồi đăng ký các sink.

```kotlin
override fun onCreate() {
    super.onCreate()
    Tracker.install(this, TrackerConfig(
        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        strictValidation = BuildConfig.DEBUG,
        logLevel = if (BuildConfig.DEBUG) 2 else 1,
    ))
    Tracker.addSink(FirebaseSink(collectionFollowsConsent = false))
    if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
}
```

Event phát ra trước `install()` được buffer — 128 mục, quá thì mục cũ nhất bị bỏ kèm cảnh báo. Lần
`install()` thứ hai bị bỏ qua. Không đăng ký sink nào thì mọi event vẫn được kiểm tra rồi huỷ.

Tích hợp chỉ có vậy. Bạn không phải bọc callback quảng cáo, cũng không phải map ad unit sang
placement: `:ads` tạo ra object quảng cáo nên `:ads` báo cáo vòng đời quảng cáo và paid impression.

Các API khác trên `Tracker`: `track(name, params)`, `track(TrackEvent)`, `screen(name, screenClass)`,
`adRevenue(impression)`, `setDefault`, `setDefaults`, `setUserProperty`, `setUserId`, `removeSink`,
`flushPending()`, `sinkIds()`, cùng hai property `isInstalled` / `currentConsent`.

## Configuration

`TrackerConfig` — mọi trường đều tuỳ chọn.

| Trường | Kiểu | Mặc định | Tác dụng |
|---|---|---|---|
| `appVersionCode` | `Long` | `0L` | gắn vào mọi event dưới tên `app_vc` |
| `sdkVersion` | `String` | `"1.0.0"` | gắn vào dưới tên `sdk_ver` |
| `reportingCurrency` | `String` | `"USD"` | đơn vị tiền mà các event doanh thu cộng dồn tính theo; impression ở tiền tệ khác vẫn tới sink nhưng bị loại khỏi tổng |
| `consentPolicy` | `ConsentPolicy` | `SEND_ALWAYS` | xử lý thế nào khi consent còn là `UNKNOWN` |
| `strictValidation` | `Boolean` | `false` | ném exception khi tên hoặc param key sai, thay vì tự sửa |
| `logLevel` | `Int` | `1` | `0` tắt, `1` cảnh báo, `2` verbose |
| `enableRevenueAccumulator` | `Boolean` | `true` | phát bốn event cộng dồn `ad_revenue_*` |
| `defaultParams` | `Map<String, Any?>` | `emptyMap()` | merge vào mọi event, giống `Tracker.setDefaults` |

| `ConsentPolicy` | Hành vi khi consent còn là `UNKNOWN` |
|---|---|
| `SEND_ALWAYS` | gửi ngay; consent về sau chỉ bật/tắt cờ của vendor |
| `QUEUE_UNTIL_RESOLVED` | buffer, rồi flush ngay khi consent có kết quả, theo hướng nào cũng vậy |
| `DROP_UNTIL_GRANTED` | bỏ luôn; không phát lại gì khi consent đến sau |

## Consent

Đừng gọi `Tracker.setConsent` khi `:ads` có trên classpath.
`com.ads.module.consent.ConsentCenter` là caller duy nhất, nó xử lý UMP cho cả process và truyền
`Tracker.setConsent(analytics = true, ads = personalized)`.

Trục analytics luôn là `true`: UMP chỉ hỏi về quảng cáo, nên một lần từ chối không được xoá theo
`first_open`, retention và funnel. Chỉ tự gọi trong app không có `:ads`, và gọi từ đúng một chỗ.

## Event catalog

`io.trackkit.TrackkitEvents` giữ 37 tên; `TrackkitEvents.all()` trả về toàn bộ. Mọi event còn mang
thêm `app_vc`, `sdk_ver`, `session_no`, `install_day`, và `consent_ads` sau khi UMP có kết quả.

| Nhóm | Sự kiện | Param riêng của sự kiện |
|---|---|---|
| Ads | `ad_request`, `ad_loaded`, `ad_load_failed`, `ad_show`, `ad_show_failed`, `ad_click`, `ad_closed`, `ad_reward_earned`, `ad_skipped` | `placement`, `ad_format`, `ad_unit_id`, cùng `latency_ms`, `error_code` hoặc `reason` |
| Ads | `ad_impression` — một paid impression, qua `Tracker.adRevenue` | `placement`, `ad_format`, `ad_unit_id`, `ad_platform`, `ad_network`, `value`, `currency`, `precision` |
| Doanh thu | `ad_revenue_total`, `ad_revenue_micro_flush` (mỗi 0.01 đơn vị tiền báo cáo), `ad_revenue_d3`, `ad_revenue_d7` | `value`, `currency` |
| First open | `fo_flow_start`, `fo_splash_view`, `fo_splash_complete`, `fo_language_view`, `fo_language_select`, `fo_language_complete`, `fo_language_flow_complete`, `fo_step_view`, `fo_step_complete`, `fo_question_view`, `fo_question_answer`, `fo_question_complete`, `fo_flow_complete` | `step`, `index`, `screen_index`, `language`, `variant`, `dwell_ms`, `exit_reason`, `source`, `count`, `steps_shown`, `option_id`, `selected` |
| IAP | `iap_paywall_view`, `iap_paywall_result`, `iap_click`, `iap_success`, `iap_fail`, `iap_dismiss` | `source`, `status`, `product_id`, `value`, `currency`, `error_code`, `reason` |
| Consent | `consent_request`, `consent_shown`, `consent_result` | `status`, `error_code`, `source` |
| Khác | `app_install_referrer` | `referrer_source`, `referrer_medium`, `referrer_campaign`, `install_version`, `is_instant` |

Screen view không phải event — `Tracker.screen()` để mỗi sink tự phát theo cách của nó. Hãy khai báo
các param ở trên thành custom dimension trong GA4, nếu không chúng chỉ nằm ở DebugView và BigQuery.

## Custom events

```kotlin
Tracker.track(SimpleEvent("app_widget_pinned", mapOf("source" to "home")))
```

Hãy thêm tên vào `TrackkitEvents` khi có nhiều hơn một module phát event đó, khi một dashboard hoặc
một Adjust token phụ thuộc vào nó, hoặc khi cách viết param phải giữ nguyên qua các bản release.

| Quy tắc cho mọi tên và param key | Giới hạn | Khi vi phạm |
|---|---|---|
| Cú pháp | `[a-zA-Z][a-zA-Z0-9_]{0,39}` | event bị loại, param key bị bỏ |
| Số param mỗi event | 25 | key dư bị bỏ |
| Giá trị param chuỗi / giá trị user property | 100 / 36 ký tự | bị cắt |
| Tiền tố dành riêng | `firebase_`, `google_`, `ga_` | bị loại |
| Key PII / bí mật | `purchase_token`, `purchase_token_part_1`, `purchase_token_part_2`, `email`, `phone`, `device_id`, `android_id`, `gaid`, `idfa`, `advertising_id` | bị loại |

Quy ước đặt tên nằm trên validator: `<domain>_<object>_<action>`, viết thường kiểu `snake_case`,
domain là một trong `ad_`, `fo_`, `iap_`, `consent_`, `app_`. Không bao giờ nhét biến vào tên — một
`fo_step_complete` mang param `step`, chứ không phải `ob1_complete` cộng `ob2_complete`.

## Writing a custom sink

Chỉ `id` và `onEvent` là bắt buộc — `onInstall`, `onScreen`, `onUserProperty`, `onUserId`,
`onConsent` và `onAdRevenue` đều có default no-op, kể cả phía Java.

```kotlin
class MyBackendSink(private val api: MyApi) : TrackSink {
    override val id: String = "my_backend"

    override fun onEvent(name: String, params: Map<String, Any?>) {
        api.enqueue(name, params)
    }
    // impression.value is already in impression.currency — do not convert.
    override fun onAdRevenue(impression: AdImpression) {
        api.enqueueRevenue(impression.value, impression.currency)
    }
}

Tracker.addSink(MyBackendSink(api))
```

Mọi callback chạy trên thread của caller và không được block. Sink ném exception sẽ bị bắt và log
theo `id` của nó; các sink còn lại vẫn nhận được event. `addSink` bỏ qua `id` đã đăng ký. Param tới
nơi đã được sanitize: không có null, chuỗi cắt ở 100 ký tự, tối đa 25 key. Cho build debug,
`io.trackkit.sink.ConsoleSink(tag = "Trackkit/Console", ringSize = 100)` log đúng payload đó.

## Troubleshooting

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| Không vendor nào nhận được gì; logcat báo `install() ran with no sink` | chưa đăng ký sink nào | `Tracker.addSink(FirebaseSink())` hoặc `TrackSink` của bạn |
| `N events were dropped before install (buffer overflow)` | phát hơn 128 event trước `install()` | chuyển `Tracker.install` lên dòng đầu tiên của `onCreate()` |
| `install() called twice` hoặc `sink 'x' already registered` | gọi `install()` hai lần, hoặc hai sink trùng `id` | giữ đúng một `install()`; cho mỗi sink một `id` riêng |
| Mọi event ngừng hẳn sau khi mở app | `consentPolicy = DROP_UNTIL_GRANTED` và consent analytics chưa được cấp | có `:ads` thì trục analytics luôn được cấp; kiểm tra `ConsentCenter.request` đã chạy chưa |
| `ad_revenue_total` đứng ở 0 trong khi `ad_impression` vẫn về | impression ở đơn vị tiền khác `reportingCurrency` | đặt `TrackerConfig.reportingCurrency` bằng đơn vị tiền của tài khoản |
| `IllegalArgumentException: Trackkit: …` trên production | `strictValidation` vẫn để `true` ở bản release | nối nó vào `BuildConfig.DEBUG` |

## License

MIT — xem [LICENSE](../LICENSE).
