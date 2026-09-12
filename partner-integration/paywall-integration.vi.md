# PayKit — paywall dựng sẵn

[← Chọn hướng dẫn](README.vi.md) · [API PayKit](../paykit/README.md)

App cung cấp catalog, nội dung, hai URL điều khoản/quyền riêng tư và nơi mở paywall; PayKit lo UI, giá Play, mua/restore và premium. **Không gọi thêm `AppPurchase.initBilling`**: PayKit đã khởi tạo BillingKit.

Chỉ thêm hai resource ở bước 2; phần còn lại ghép vào Gradle, Application và điểm mở paywall đang có.

## 1. Thêm dependency

Hoàn thành [build setup](../README.vi.md#cấu-hình-build), dùng chung `adlogicSdkVersion`. JDK 17, `minSdk 24+`, `compileSdk 36+`; chưa dùng ads thì không cần repository mediation.

```groovy
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:paykit:$sdkVersion"
    // Mẫu gọi BillingKit.setDevMode nên khai báo API billing trực tiếp.
    implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"
}
```

Dependency `billingkit` cho phép đặt chế độ Play thật và đọc `Billing`; Trackkit có sẵn. Không cần BillingClient, paywall Activity hay layout riêng.

## 2. Copy catalog và resources

| File mẫu | Copy tới | App thay gì |
| --- | --- | --- |
| [paywall_config.json](examples/paywall/paywall_config.json) | `app/src/main/res/raw/paywall_config.json` | Product ID, base plan/offer, nội dung và cấu hình từng gói. |
| [paywall_strings.xml](examples/paywall/paywall_strings.xml) | `app/src/main/res/values/paywall_strings.xml` | Copy/benefit đúng sản phẩm và hai URL thật; thêm bản dịch vào `values-<language>`. |

JSON có đủ cấu trúc của [example](../app/src/main/res/raw/paywall_config.json), giữ badge/discount rỗng/0 tới khi app có ưu đãi thật. Thay ID/plan/offer bằng sản phẩm đã tạo trên Play Console; **không có product ID test dùng chung**.

Resource `pw_*` của app override nội dung SDK; ghép vào resource đang có nếu trùng tên. Thay hai URL `example.com` bằng trang thật của app.

Chỉ cần một JSON local. Muốn catalog riêng cho debug, đặt cùng tên tại `app/src/debug/res/raw/paywall_config.json`; PayKit không tự tìm file `*_debug`.

## 3. Install trong Application

Ghép vào Application hiện có, sau Tracker nếu app thu thập analytics, trước `OnboardingSdk.install` nếu dùng paywall trong OB. Thay package/import `R` của app.

```kotlin
package com.example.app

import android.app.Application
import com.ads.module.billing.BillingKit
import io.paykit.PayKit
import io.paykit.PaywallPlacement
import io.paykit.payKitConfig

class PaywallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BillingKit.setDevMode(false)
        val config = payKitConfig {
            termsUrl = getString(R.string.paywall_terms_url)
            privacyUrl = getString(R.string.paywall_privacy_url)
            defaultPlacements = setOf(PaywallPlacement.SETTING)
            fallbackConfigRes = R.raw.paywall_config
        }.getOrThrow()
        PayKit.install(this, config)
    }
}
```

Đăng ký Application qua `android:name` nếu app chưa có; Activity/theme paywall được merge từ SDK. Hai URL phải là HTTP(S) có host, nếu sai `getOrThrow()` báo lỗi ngay.

Mẫu cho phép mở tại Settings. **Phải chọn `defaultPlacements`** vì default SDK rỗng; `placements` trong JSON local không bật vị trí. `isReady()` chỉ xác nhận config, chưa xác nhận giá/entitlement từ Play.

## 4. Mở paywall từ thao tác người dùng

Gọi từ nút Premium trong Activity; cập nhật UI/điều hướng tại `onFinished`.

```kotlin
package com.example.app

import android.app.Activity
import io.paykit.PayKit
import io.paykit.PaywallListener
import io.paykit.PaywallPlacement
import io.paykit.PaywallResult

fun Activity.openPremium(onFinished: (PaywallResult) -> Unit) {
    PayKit.launch(this, PaywallPlacement.SETTING, object : PaywallListener() {
        override fun onFinished(placement: PaywallPlacement, result: PaywallResult) {
            onFinished(result)
        }
    })
}
```

Kết quả: `Purchased(productId)`, `ContinueWithAds`, `Dismissed`, `Error(code, message)`. Placement tắt, đã premium hoặc click lặp trả `Dismissed`; không xem mọi dismissal là người dùng đóng màn. Lấy entitlement từ `PayKit.isPremium()` hoặc quan sát [Billing.isPremium](billing-integration.vi.md#3-theo-dõi-kết-quả-và-premium).

`ContinueWithAds` chỉ đóng paywall, không show ad. Consumable cần app cấp vật phẩm riêng; `Purchased` không luôn đồng nghĩa premium. SDK đã xử lý nút mua/Restore.

## 5. Bảng JSON và config tùy chọn

### Các field JSON

| Field | Mẫu / cách dùng |
| --- | --- |
| `config_version` | Metadata JSON; giữ như file mẫu. |
| `packages[].id`, `type` | ID thật; `subs`, `inapp`, `consumable`. Phải có ít nhất một package hợp lệ. |
| `base_plan_id`, `offer_id` | Tọa độ subscription. Bỏ `offer_id` để SDK chọn offer trong plan, không ép mua base plan không ưu đãi. Không khớp thì resolver có thể fallback. |
| `title_key`, `subtitle_key` | Tên string resource app/SDK. `title`, `subtitle` literal nếu có sẽ ưu tiên hơn key và không tự dịch. |
| `badge`, `discount_percent` | Rỗng/0 trong mẫu; discount hợp lệ 0–99. Chỉ hiển thị nhãn/giá so sánh, không đổi giá Play. |
| `preselected` | Chọn gói ban đầu; nếu nhiều gói cùng true, SDK giữ gói đầu. Không có true thì chọn gói đầu. |
| `copy` | `headline_key`, `benefit_keys`, `cta_key`; hỗ trợ literal `headline`, `benefits`, `cta` khi cần remote copy. |
| `tokens` | Đủ màu như example: `text_primary`, `text_secondary`, `accent`, `background`, `surface`, `on_accent`, `cta_gradient`. Chỉ đổi khi cần giao diện khác. |
| `exit_button` | Mẫu bật, delay 3000 ms; thiếu object vẫn bật. `delay_ms` JSON ghi đè local, kể cả 0; bỏ delay để dùng local. |
| `continue_with_ads`, `restore` | Mẫu bật cả hai. Nếu app không có ads, tắt `continue_with_ads`; nút này không tải quảng cáo. Khi bỏ cả object, parser mặc định hai tính năng này tắt. |
| `placements` | Chỉ JSON từ remote/cache hoặc `applySnapshot` có danh sách không rỗng mới ghi đè local. Array rỗng fallback về `defaultPlacements`, không phải kill switch. |

### Cấu hình Kotlin

| Option | Default SDK / khi cần đổi |
| --- | --- |
| `defaultPlacements` | Rỗng; mẫu dùng `SETTING`. Chọn thêm từ enum bên dưới. |
| `fallbackConfigRes` | 0 dùng catalog demo của SDK; mẫu cấp resource app để hoạt động không cần remote. |
| `termsUrl`, `privacyUrl` | Bắt buộc; URL HTTP(S) của app. |
| `exitButtonDelayMs` | 0; chỉ dùng khi JSON không có delay. Mẫu JSON đã đặt 3000. |
| `singleClickWindowMs` | 700 ms; không cần debounce riêng quanh launch. |
| `logLevel` | `WARN`; dùng `PayKitLogLevel.DEBUG` khi cần xem parse/config. |
| `PayKit.sync(timeoutMs)` | 5 giây; chỉ gọi nếu đã cài nguồn remote, lỗi giữ cấu hình hiện có. |
| `BillingKit.setDevMode` | Mẫu đặt `false` để kiểm tra Play; `true` chỉ mô phỏng local, không kiểm tra sản phẩm/offer thật. |
| Custom UI | `PayKit.renderer(...)` khi cần renderer riêng; không cần cho luồng chuẩn. |

`PaywallPlacement`: `SPLASH`, `AFTER_ONBOARDING`, `HOME`, `SETTING`, `FEATURE_LOCK`, `OTHER`; key JSON tương ứng là chữ thường. Dùng enum tại điểm gọi; vị trí app khác dùng `OTHER`.

**Giới hạn catalog:** UI PayKit lấy giá gia hạn/trial theo product, còn lúc mua chọn theo base plan/offer; với nhiều plan/offer, thông tin hiển thị có thể lệch offer mua. Mẫu dùng mỗi product một base plan; kiểm tra giá, chu kỳ và trial khớp Play trước khi phát hành.

## 6. Kết nối với SDK khác

- **Firebase:** cài nguồn rồi gọi `PayKit.sync()` như [guide Firebase](firebase-integration.vi.md#4-json-paywall-từ-remote). Local-only không cần sync; cache remote hợp lệ ưu tiên hơn JSON bundled.
- **Ads/premium:** thêm [hook chờ billing](billing-integration.vi.md#khi-app-có-adsonboarding) trong splash. PayKit đã nối premium vào ad gate.
- **Analytics/Adjust:** cài Tracker/sinks trước PayKit; SDK đã phát purchase/paywall event, không gửi lặp revenue trong `onFinished`.

**Paywall trong OB:** khai báo `onboardkitorigin`, install PayKit trước OnboardKit; thêm `paywallGate = io.paykit.integration.OnboardKitPaywallGate()` vào block `OnboardingSdk.install`. Enable vị trí cần dùng trong `defaultPlacements`:

| Checkpoint OB | `PaywallPlacement` của PayKit |
| --- | --- |
| Trước inter splash | `SPLASH` |
| Sau onboarding hoặc khảo sát user mới | `AFTER_ONBOARDING` |
| Sau khảo sát user cũ | `OTHER` |

SDK mở tại checkpoint; không launch thêm từ listener hoàn tất OB. `OTHER` dùng chung cho checkpoint user cũ và điểm gọi `OTHER` của app.

## 7. Kiểm tra

- [ ] `PayKit.isReady()` true, mở từ đúng enum đã enable; URL/copy đúng app.
- [ ] Giá, chu kỳ, offer/trial khớp Play; product ID mẫu đã được thay. Theo [cách chuẩn bị license tester](billing-integration.vi.md#1-chuẩn-bị-và-thêm-dependency).
- [ ] Thử mua, hủy, lỗi, restore; đã premium không mở lại paywall. Continue with ads chỉ trả callback.
- [ ] Thử không có Firebase/mất mạng và JSON lỗi; không nhầm cache remote với resource mới.
- [ ] Đi từ OB tới paywall chỉ mở một lần; chờ billing trong splash, hết hạn tiếp tục theo premium đã lưu.

Lỗi parse: xem log `PayKit`. Giá trống: kiểm tra catalog và tài khoản Play.
