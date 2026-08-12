# Trackkit (VI)

> English: **[README.md](README.md)** · हिन्दी: **[README.hi.md](README.hi.md)** · Kiến trúc: **[ARCHITECTURE.md](ARCHITECTURE.md)**

Trackkit là điểm fan-out duy nhất cho analytics trong dự án. Mọi event mà app, `:ads` và
`:onboardkitorigin` sinh ra đều đi qua một facade, nhờ vậy cổng consent, default param, kiểm tra tên,
chống trùng và bộ tích luỹ ad-revenue chỉ được cài đặt **một lần** thay vì chép vào từng bản build
của partner. Nó thay thế bốn wrapper viết tay từng mâu thuẫn nhau về tiền tệ, đơn vị doanh thu và cả
việc Adjust có đang bật hay không; không cái nào còn tồn tại.

Module lõi **không phụ thuộc vendor nào** — cả khối dependency của nó chỉ có đúng một artifact
annotation ở dạng `compileOnly` — nên thêm `:trackkit` không làm APK của bạn nặng thêm gì. Vendor nằm
ở các module sink riêng: partner chỉ dùng Firebase sẽ không bao giờ compile bất kỳ SDK analytics nào
khác.

---

## Cài đặt Gradle

```groovy
repositories {
    google()
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
```

```groovy
dependencies {
    // Hợp đồng. Module nào phát event thì phụ thuộc vào đây.
    implementation 'com.github.truongvimit.adlogic-partner-sdk:trackkit:1.3.0'

    // Sink — chỉ lấy vendor bạn thật sự ship.
    implementation 'com.github.truongvimit.adlogic-partner-sdk:trackkit-firebase:1.3.0'
}
```

Vì repo này publish nhiều module, JitPack đặt group là `com.github.<user>.<repo>` — tên repo là một
phần của group id. Thay `1.3.0` bằng git tag bạn muốn dùng. Yêu cầu JDK 17, `minSdk` 24.

Trong nội bộ repo thì khai bằng project:

```groovy
implementation project(':trackkit')
implementation project(':trackkit-firebase')
```

---

## Bắt đầu nhanh

Ba dòng trong `Application.onCreate()`, **trước** mọi SDK khác — đây là chỗ duy nhất trong toàn app
gọi tên một vendor:

```kotlin
Tracker.install(
    this,
    TrackerConfig(
        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        strictValidation = BuildConfig.DEBUG,
    ),
)
Tracker.addSink(FirebaseSink())
if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
```

**Không đăng ký sink nào thì mọi event bị validate xong vứt đi.** `install()` có log cảnh báo cho
trường hợp đó, nhưng cách sửa là gọi `addSink`.

Rồi đặt tên placement, một lần, ngay cạnh chỗ bạn cấu hình ad unit:

```kotlin
// Ad unit id -> màn hình yêu cầu nó. Callback paid-event của AdMob chỉ biết ad unit id,
// nên đây chính là thứ giúp ad_impression nói được màn nào kiếm ra tiền.
PlacementRegistry.register(interSplashConfig.id, "inter_splash")
PlacementRegistry.register(nativeLanguageConfig.id, "native_language")
```

Đăng ký **mọi** id trong waterfall, không chỉ tier cao nhất. Module tự dựng ad request thì tự đăng ký
—`ERainAdProvider` trong `:onboardkitorigin` đăng ký từng unit onboarding trước khi load.

Cuối cùng, chỗ UMP trả kết quả — **đúng một chỗ duy nhất trong app**:

```kotlin
Tracker.setConsent(analytics = granted, ads = granted)
```

Hết. Bạn **không** phải bọc ad callback: `:ads` là nơi tạo ra đối tượng ads của AdMob nên `:ads` gắn
paid-event listener và báo cáo vòng đời ads. Cứ truyền `AdCallback` của bạn như xưa, nó quay về
nguyên vẹn.

Event phát trước `install()` được buffer chứ không mất, nên sai thứ tự cũng không mất dữ liệu. Tham
chiếu nối dây: `GlobalApp.initTracking()` và `ConsentHandler.resolveConsent()` trong `:app`.

---

## Danh mục event

Mọi event đều mang sẵn default param `app_vc`, `sdk_ver`, `session_no`, `install_day` và
`consent_ads`, nên phễu nào cũng cắt được theo cohort mà không cần join. Bảng dưới chỉ liệt kê param
riêng của từng event.

| Event | Bắn khi | Param | Phát bởi |
|---|---|---|---|
| `ad_request` | một lệnh load thực sự gửi đi | `placement`, `ad_format`, `ad_unit_id` | `:ads` |
| `ad_loaded` | network trả về fill | + `latency_ms` | `:ads` |
| `ad_load_failed` | không fill hoặc lỗi load | + `error_code` | `:ads` |
| `ad_show` | ads thực sự hiển thị | `placement`, `ad_format`, `ad_unit_id` | `:ads`, `:onboardkitorigin` |
| `ad_show_failed` | lệnh show bị từ chối | + `error_code` | `:ads` |
| `ad_click` | user chạm vào ads | `placement`, `ad_format`, `ad_unit_id` | `:ads` |
| `ad_closed` | ads full-screen bị đóng | `placement`, `ad_format`, `ad_unit_id` | `:ads` |
| `ad_skipped` | policy từ chối trước khi request (remote tắt, user đã mua, không có unit) | `placement`, `ad_format`, `reason` | `:ads`, `:onboardkitorigin` |
| `ad_impression` | một impression **có tiền**, qua `Tracker.adRevenue()` | `placement`, `ad_format`, `ad_unit_id`, `ad_platform`, `ad_network`, `value`, `currency`, `precision` | `:trackkit` |
| `ad_revenue_total` | mỗi paid impression — ad LTV tích luỹ | `value`, `currency` | `:trackkit` |
| `ad_revenue_micro_flush` | phần chưa flush vượt 0.01 đơn vị tiền báo cáo | `value`, `currency` | `:trackkit` |
| `ad_revenue_d3` / `d7` | paid impression đầu tiên sau install ≥ 3 / 7 ngày (một lần) | `value`, `currency` | `:trackkit` |
| `fo_flow_start` | vào luồng first-open (mẫu số; bắn cả khi luồng quyết định skip) | — | `:onboardkitorigin` |
| `fo_splash_view` / `fo_splash_complete` | splash hiện / xong | — / `dwell_ms` | `:onboardkitorigin` |
| `fo_language_view` | một màn language hiện | `screen_index`, `variant` | `:onboardkitorigin` |
| `fo_language_select` | chạm vào một dòng ngôn ngữ | `screen_index`, `language` | `:onboardkitorigin` |
| `fo_language_complete` | xác nhận ngôn ngữ | `screen_index`, `language`, `dwell_ms` | `:onboardkitorigin` |
| `fo_language_flow_complete` | toàn bộ luồng language xong | `language` | `:onboardkitorigin` |
| `fo_step_view` | một step onboarding hiện | `step`, `index`, `variant` | `:onboardkitorigin` |
| `fo_step_complete` | rời một step onboarding | `step`, `index`, `dwell_ms`, `exit_reason` | `:onboardkitorigin` |
| `fo_question_view` / `_answer` / `_complete` | màn câu hỏi | `source` / `option_id`+`selected` / `count` | `:onboardkitorigin` |
| `fo_flow_complete` | toàn bộ luồng first-open xong | `steps_shown`, `dwell_ms` | `:onboardkitorigin` |
| `iap_paywall_view` | paywall hiện | `source` | `:app`, `:onboardkitorigin` |
| `iap_paywall_result` | paywall đóng, dù kết quả gì | `source`, `status` (`purchased` / `dismissed` / `continue_with_ads`) | `:onboardkitorigin` |
| `iap_success` | purchase được xác nhận | `product_id`, `value`, `currency`, `source` | `:ads` |
| `app_install_referrer` | Play install referrer, đọc một lần mỗi install qua MMP | `referrer_source`, `referrer_medium`, `referrer_campaign`, `install_version`, `is_instant` | `:ads` |
| `consent_request` / `consent_shown` | form UMP được yêu cầu / thực sự hiện (một lần mỗi session) | — | `:app` |
| `consent_result` | UMP có kết quả | `status` (`granted`/`denied`/`not_required`/`error`), `error_code` | `:app` |

Screen view **không phải** event: `Tracker.screen(name, screenClass)` để mỗi sink tự phát screen-view
gốc của nó (`screen_view` trên Firebase), đúng thứ mà UI báo cáo mong đợi.

Hai quy tắc mà danh mục này sinh ra để cưỡng chế:

1. **Không bao giờ nhét biến vào tên event.** Một `fo_step_complete` mang param `step`, chứ không
   phải `ob1_complete`, `ob2_complete`, `ob3_complete`.
2. **Mọi ad event đều mang placement.** Callback paid-event của AdMob chỉ biết ad unit, nên không có
   `PlacementRegistry` thì không quy được doanh thu về màn hình nào.

---

## Thêm event riêng

Với thứ đặc thù của app mà không app nào khác báo cáo, dùng cửa thoát:

```kotlin
Tracker.track(SimpleEvent("app_widget_pinned", mapOf("source" to "home")))
```

Hãy thêm hẳn một entry vào `TrackkitEvents` khi **một trong các điều sau** đúng:

- nhiều hơn một module phát nó,
- có dashboard, Adjust token hoặc Meta custom conversion phụ thuộc vào nó,
- nó mang param mà cách viết phải ổn định qua các bản phát hành.

Entry trong danh mục là một class, nên param của nó trở thành chữ ký được compiler kiểm tra và
`TaxonomyTest` tự động validate tên. Chuỗi `SimpleEvent` chỉ được kiểm tra lúc chạy.

---

## Viết sink riêng

Implement `TrackSink` rồi đăng ký. Chỉ `id` và `onEvent` là bắt buộc, phần còn lại đều có no-op mặc
định.

```kotlin
class MyBackendSink(private val api: MyApi) : TrackSink {

    override val id: String = "my_backend"

    override fun onInstall(context: Context) = api.warmUp(context)

    override fun onEvent(name: String, params: Map<String, Any?>) = api.enqueue(name, params)

    override fun onConsent(consent: Consent) = api.setCollectionEnabled(consent.analyticsGranted)

    override fun onAdRevenue(impression: AdImpression) {
        // impression.value đã ở đúng impression.currency — đừng quy đổi.
        api.enqueueRevenue(impression.value, impression.currency)
    }
}

Tracker.addSink(MyBackendSink(api))
```

Hợp đồng:

- Mọi callback chạy trên thread của caller. **Không** block — đẩy sang queue của bạn.
- Trackkit bọc từng lời gọi trong `runCatching`, nên một sink ném exception không kéo sập các sink
  khác. Nó vẫn log cảnh báo kèm `id` của bạn.
- `id` phải ổn định và duy nhất; `addSink` bỏ qua sink thứ hai trùng id.
- Param tới nơi đã được làm sạch: không null, chuỗi cắt ở 100 ký tự, tối đa 25 key.

---

## Adjust

Adjust **không** phải sink của Trackkit. Nó nằm trong `:ads`, vì mọi tín hiệu nó tiêu thụ đều phát
sinh ở đó, và vì `:ads` đọc ngược kết luận attribution của Adjust để quyết định có hiện ads hay
không. Lý do đầy đủ và tiêu chí xem xét lại: [ARCHITECTURE.md](ARCHITECTURE.md).

Để gửi một mốc chuyển đổi từ module ngoài `:ads`:

```java
MmpTracking.trackEvent(adjustTokenForThisMilestone);
```

Sinh token trên dashboard Adjust trước — token rỗng sẽ bị bỏ qua kèm cảnh báo, không bao giờ được gửi
dưới dạng `AdjustEvent("")`.

---

## Tuỳ chọn của sink

Mỗi sink cấu hình qua constructor của chính nó; không có object settings toàn cục.

| Sink | Tham số | Mặc định | Tác dụng |
|---|---|---|---|
| `FirebaseSink` | `collectionFollowsConsent` | `true` | Gọi `setAnalyticsCollectionEnabled(analyticsGranted)`. **Truyền `false`** nếu form UMP chỉ hỏi về ads — công tắc thu thập cứng sẽ giết luôn `first_open`, retention và toàn bộ phễu với user từ chối cá nhân hoá, trong khi riêng Consent Mode đã đủ đáp ứng yêu cầu pháp lý. |

---

## Consent Mode mặc định

Trackkit set consent ngay khi UMP trả kết quả, nhưng trạng thái *trước* thời điểm đó là việc của
manifest. Khai bốn giá trị mặc định trong manifest của app, nếu không traffic trước consent sẽ dùng
mặc định của Firebase chứ không phải của bạn:

```xml
<meta-data android:name="google_analytics_default_allow_analytics_storage" android:value="true" />
<meta-data android:name="google_analytics_default_allow_ad_storage" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_user_data" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_personalization_signals" android:value="false" />
```

Manifest của thư viện cố ý **không** merge sẵn — mỗi partner có tư thế pháp lý khác nhau.

---

## Quy tắc đặt tên và giới hạn GA4

`EventValidator` cưỡng chế các luật dưới đây trên mọi tên event và mọi param key, còn `TaxonomyTest`
kiểm chứng toàn bộ danh mục ngay lúc build.

| Luật | Giới hạn | Vi phạm thì sao |
|---|---|---|
| Ngữ pháp tên event | `[a-zA-Z][a-zA-Z0-9_]*` | bị từ chối |
| Độ dài tên event | 40 ký tự | bị từ chối |
| Ngữ pháp param key | như tên event | key bị bỏ |
| Số param mỗi event | 25 | key dư bị bỏ |
| Chuỗi giá trị param | 100 ký tự | bị cắt |
| Tiền tố dành riêng | `firebase_`, `google_`, `ga_` | bị từ chối |
| Key PII / bí mật | `purchase_token*`, `email`, `phone`, `device_id`, `android_id`, `gaid`, `idfa`, `advertising_id` | bị từ chối |

Ngoài validator, quy ước của danh mục là `<domain>_<object>_<action>` viết thường `snake_case`, với
`domain` thuộc `ad_`, `fo_`, `iap_`, `consent_`, `app_`.

`strictValidation = true` biến mọi vi phạm thành exception. Nối nó vào `BuildConfig.DEBUG` để lỗi
taxonomy nổ ở QA; ở bản release nó hạ xuống thành một dòng log và một event đã được làm sạch.

---

## Những quyết định thiết kế bạn thừa hưởng

Đều là cố ý, và mỗi cái sửa một khiếm khuyết cụ thể tìm được khi audit thế hệ trước của pipeline
này. Nếu một con số trông "sai" so với dashboard cũ, đây là lý do.

- **Không bao giờ quy đổi tiền tệ.** Đường cũ nhân doanh thu AdMob với `26000` và MAX với `25000` —
  hai tỷ giá hardcode khác nhau trong cùng một file — rồi báo cho Meta là VND. Giờ
  `AdImpression.currency` được báo đúng như ad SDK đưa ra, và impression khác đơn vị tiền báo cáo bị
  loại khỏi tổng tích luỹ thay vì cộng dồn thành một con số vô nghĩa.
- **Không bắn purchase event mỗi impression.** Meta `logPurchase` từng bắn ở mọi impression. Giờ
  không còn gì làm vậy.
- **Doanh thu purchase không bị chia cho 1.000.000.** Helper cũ chia vô điều kiện, nên doanh thu IAP
  — vốn đã ở đơn vị tiền tệ — bị báo nhỏ đi khoảng một triệu lần.
- **Một nguồn sự thật cho Adjust.** Hai cờ độc lập từng mâu thuẫn nhau, nên "tắt Adjust" vẫn rò
  event. Giờ một lần kiểm tra duy nhất bao cả công tắc config lẫn việc `Adjust.initSdk` có thật sự
  thành công hay không.
- **Mọi ad event đều mang placement**, và `ad_show` / `ad_show_failed` tồn tại hẳn hoi, nên show rate
  và show failure nhìn thấy được chứ không phải suy đoán.
- **Không nhét biến vào tên event.** SDK bị audit phát `ob1_complete`, `ob2_complete`, … *và* một họ
  song song `complete_ob1`, `complete_ob2`, … đếm trùng cùng một transition trên hai đồng hồ khác
  nhau. Ở đây chỉ có một `fo_step_complete` mang param `step`.
- **Token Adjust rỗng bị chặn, không gửi.** `AdjustEvent("")` được nhận ở client rồi drop ở server,
  nên token rỗng làm mất doanh thu mà không có bất kỳ tín hiệu nào.
