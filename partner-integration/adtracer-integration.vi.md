# AdTracer — dashboard debug quảng cáo

[← Chọn hướng dẫn](README.vi.md) · [API AdTracer](../adtracer/README.md)

Dùng để xem request, load/show, lỗi và timeline của luồng ads/OB khi QA. Ba bước: thêm dependency debug, copy bridge, mở dashboard.

## 1. Thêm dependency debug

Hoàn thành [Ads + OnboardKit](ads-onboarding-integration.vi.md) và giữ Tracker đã install trong Application. Theo [build setup](../README.vi.md#cấu-hình-build), thêm vào app:

```groovy
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    debugImplementation "com.github.truongvimit.adlogic-partner-sdk:adtracer:$sdkVersion"
}
```

Trackkit đã có qua các SDK; không cần thêm Firebase để mở dashboard. Không dùng `implementation` cho AdTracer vì dashboard chỉ cần trong bản debug.

## 2. Copy bridge theo source set

Ba file dưới lấy từ cách nối của example, đổi package thành package app. Hai file `DebugSinks.kt` phải cùng package và cùng chữ ký hàm:

| File mẫu | Copy tới |
| --- | --- |
| [debug/AdTracerSink.kt](examples/adtracer/debug/AdTracerSink.kt) | `app/src/debug/java/<package>/tracking/AdTracerSink.kt` |
| [debug/DebugSinks.kt](examples/adtracer/debug/DebugSinks.kt) | `app/src/debug/java/<package>/tracking/DebugSinks.kt` |
| [release/DebugSinks.kt](examples/adtracer/release/DebugSinks.kt) | `app/src/release/java/<package>/tracking/DebugSinks.kt` |

`<package>` là đường dẫn package app, ví dụ `com/example/app`. Đặt đúng source set; build type khác cũng cần một bản hàm tương ứng, dùng no-op nếu không cần dashboard.

Trong Application hiện có, **sau `Tracker.install` và trước SDK phát ads events**, gọi:

```kotlin
import com.example.app.tracking.installDebugSinks

// Trong Application.onCreate, sau Tracker.install:
installDebugSinks()
```

Sink tự gọi `AdTracer.start(context)`. Release gọi hàm no-op; không import `io.adtracer` ở `main`, kể cả khi call nằm trong `if (BuildConfig.DEBUG)`.

## 3. Mở dashboard

Chạy bản debug trên thiết bị/emulator, mở Logcat lọc `AdTracer` và tìm dòng `Dashboard ready`. Port mặc định là 8686; nếu bận SDK thử lần lượt tới 8695. Dùng port thực tế trong log cho cả hai phía:

```bash
adb forward tcp:8686 tcp:8686
```

Mở [dashboard localhost](http://localhost:8686) trên máy tính, rồi thực hiện luồng splash/OB và các vị trí ads của app. Nếu có nhiều thiết bị, dùng `adb -s <serial> forward ...`. `AdTracer.dashboardPort` là port đang chạy, `-1` nếu không mở được server.

Kiểm tra timeline theo placement. ID test trong [JSON mẫu](examples/ads-onboarding/ad_config_debug.json) dùng chung cho nhiều vị trí nên click/impression tra theo ad unit ID có thể hiện dưới placement khác. Khi QA bằng ID test, đối chiếu thêm load/show và Logcat `OB_FLOW`; không kết luận luồng sai chỉ từ placement của impression. Preview/mock trên dashboard không phải quảng cáo thật được phục vụ.

## 4. Bảng hành vi và tùy chọn

| Nội dung | Default / khi cần thay đổi |
| --- | --- |
| Khởi động | Một lần qua `AdTracerSink.onInstall`; gọi AdTracer trước start sẽ không ghi event. |
| Retention | Giữ tối đa 10 session journal; phục vụ QA, không phải kho analytics đầy đủ. |
| Placement màn app | Truyền `placement = AppAdPlacement.…` vào helper/manager; bỏ qua có thể thiếu `ad_request`/`ad_skipped`. SDK tra click/impression theo ad unit ID; ID chưa đăng ký hiện `unknown`. |
| Placement OB | Hiện key nội bộ OnboardKit; xem bảng tra bên dưới khi cần đối chiếu JSON. |
| Event không có placement | Hiện timeline dưới `_tracer`, không cộng vào thống kê quảng cáo. |
| Ad revenue | Sink xử lý event `ad_impression`; không gửi thêm trong `onAdRevenue` để tránh hai impression. |
| Format | Bridge đã map banner/collapsible, native/fullscreen, interstitial, rewarded và app-open; không hardcode format tại từng màn. |
| Loader riêng ngoài SDK | Chỉ khi loader chưa phát event Tracker mới cần nối sự kiện. Tra [API AdTracer](../adtracer/README.md); không thêm lần gọi trực tiếp cho event đã đi qua sink. |

Không cần `adtracer_config.json`, manifest Activity riêng hoặc sao chép các màn preview của example. Dùng catalog placement của app đang có; dashboard không quyết định ad ID hay enable quảng cáo.

<details>
<summary>Tra key JSON của luồng OB trên dashboard</summary>

| Key JSON | Placement trên dashboard |
| --- | --- |
| `banner_splash` | `splash_banner` |
| `inter_splash` | `splash_inter` |
| `native_lang` / `native_lang_alt` | `language1` / `language2` |
| `native_popup_lang` | `language_confirm` |
| `native_ob1` / `native_ob2` / `native_ob3` | `step_ob1` / `step_ob2` / `step_ob4` |
| `native_fs` | `fullscreen_ob3` |
| `inter_after_ob3` / `open_resume` | Giữ nguyên key. |

</details>

## 5. Kiểm tra trước khi bàn giao

- [ ] Debug có sink `adtracer` trong `Tracker.sinkIds()` và log port, dashboard mở được qua ADB.
- [ ] Một request thật tạo một chuỗi request/load/show theo đúng placement; impression không nhân đôi.
- [ ] Luồng OB và ad ở màn app đều xuất hiện; lỗi load/show có thông tin tương ứng.
- [ ] Build release thành công với hàm no-op; runtime classpath release không có AdTracer.

Nếu dashboard trống, kiểm tra thứ tự install, sink ID, consent policy Tracker và app đã thực sự request ad. Nếu không mở URL, kiểm tra thiết bị ADB/port theo log trước khi sửa config quảng cáo.
