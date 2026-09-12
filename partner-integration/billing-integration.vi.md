# BillingKit — mua hàng với UI của app

[← Chọn hướng dẫn](README.vi.md) · [API BillingKit](../billingkit/README.md)

Dùng khi app **tự dựng màn mua hàng**. Nếu dùng UI của [PayKit](paywall-integration.vi.md), theo guide đó; PayKit đã khởi tạo BillingKit và catalog.

Chỉ thêm [BillingProducts.kt](examples/billing/BillingProducts.kt); phần còn lại ghép vào Gradle, Application và màn mua đang có. Không cần JSON hay BillingClient riêng.

## 1. Chuẩn bị và thêm dependency

Hoàn thành [cấu hình build chung](../README.vi.md#cấu-hình-build): JDK 17, `minSdk 24+`, `compileSdk 36+`, property `adlogicSdkVersion` và repository. App chỉ dùng billing không cần repository mediation, AdMob ID hoặc Firebase.

```groovy
// app/build.gradle
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"
}
```

Ví dụ dùng `AppCompatActivity`, `lifecycleScope`, `repeatOnLifecycle`; app cần AndroidX AppCompat và `androidx.lifecycle:lifecycle-runtime-ktx` như [build setup BillingKit](../billingkit/README.md#install). SDK đã cung cấp BillingClient ở runtime và API Trackkit/coroutines.

Tạo product ID, base plan/offer trên Play Console. ID mẫu **không phải sản phẩm test dùng chung**. Khi test, dùng package name đúng app, tài khoản license tester và kiểm tra dialog có phương thức thanh toán test; internal test track không tự cấp quyền license tester. [Google: kiểm thử Billing](https://developer.android.com/google/play/billing/test).

## 2. Catalog và Application

Copy `BillingProducts.kt`, đổi `com.example.app` và ID/plan/offer; chỉ giữ sản phẩm app bán. Dùng các hằng số này tại nút mua.

Ghép vào Application hiện có; nếu chưa có Application, tạo class bên dưới và khai báo `android:name` trong manifest:

```kotlin
package com.example.app

import android.app.Application
import com.ads.module.billing.AppPurchase
import com.ads.module.billing.Billing
import com.ads.module.billing.BillingKit

class BillingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Nếu dùng analytics, install Tracker/sink trước đoạn billing này.
        BillingKit.setDevMode(false)
        AppPurchase.getInstance().initBilling(this, BillingProducts.items)
        Billing.install(this)
    }
}
```

Giữ `setDevMode(false)` để dùng Play kể cả debug; bỏ dòng này có thể khiến billing kế thừa dev flag từ ads và mô phỏng giao dịch. Init một lần trong Application, không init/đóng connection theo từng màn.

## 3. Theo dõi kết quả và premium

Gọi hàm dưới **một lần trong `onCreate`** của màn mua: `onPremium` cập nhật quyền truy cập, `onPurchase` cập nhật trạng thái giao dịch.

```kotlin
package com.example.app

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ads.module.billing.Billing
import com.ads.module.billing.PurchaseEvent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

fun AppCompatActivity.observeBilling(
    onPremium: (Boolean) -> Unit,
    onPurchase: (PurchaseEvent) -> Unit,
) {
    lifecycleScope.launch {
        // Giữ collector trong lúc Play che màn app; flow này không replay.
        Billing.purchaseEvents.collect { onPurchase(it) }
    }
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            Billing.isPremium.collect { onPremium(it) }
        }
    }
}
```

| Kết quả | App làm gì |
| --- | --- |
| `Purchased`, `AlreadyOwned` | Cập nhật thông báo; lấy quyền premium từ `Billing.isPremium`. |
| `Pending` | Hiện chờ thanh toán; chưa cấp quyền mua hàng. |
| `Canceled` | Đóng loading, cho phép người dùng thao tác lại. |
| `Error(code, message)` | Hiện lỗi/thử lại, không tự bật premium. |

`purchaseEvents` không phát lại event cũ. Khôi phục quyền truy cập UI từ `Billing.isPremium` (cache lúc khởi động, cập nhật sau khi kiểm tra Play). Consumable cần app cấp vật phẩm riêng; không xem mọi `Purchased` là premium.

## 4. Giá và nút mua

`Billing.awaitReady()` chờ kết nối/kiểm tra giao dịch; giá tải riêng. Mẫu chờ giá tối đa 5 giây để UI có thể thử lại; đây là timeout của mẫu.

```kotlin
package com.example.app

import com.ads.module.billing.AppPurchase
import com.ads.module.billing.Billing
import com.ads.module.billing.ReadyResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

suspend fun lifetimePriceOrNull(): String? {
    if (Billing.awaitReady() != ReadyResult.Ready) return null
    val purchase = AppPurchase.getInstance()
    purchase.refreshProductDetails()
    return withTimeoutOrNull(5_000) {
        while (purchase.getPrice(BillingProducts.LIFETIME).isNullOrBlank()) delay(200)
        purchase.getPrice(BillingProducts.LIFETIME)
    }
}
```

Gọi trong `lifecycleScope.launch`: disable nút mua khi chờ, hiển thị giá từ Play và bật nút khi có giá; `null` thì cho thử lại. Không hardcode giá/currency.

Từ click mua của Activity, gọi một trong hai hàm dưới và xử lý `LaunchResult`:

```kotlin
package com.example.app

import android.app.Activity
import com.ads.module.billing.AppPurchase
import com.ads.module.billing.LaunchResult

fun Activity.buyLifetime(): LaunchResult =
    AppPurchase.getInstance().purchaseProduct(this, BillingProducts.LIFETIME)

fun Activity.buyYearly(): LaunchResult {
    val purchase = AppPurchase.getInstance()
    val token = purchase.resolveOfferToken(
        BillingProducts.YEARLY, BillingProducts.YEARLY_BASE_PLAN, BillingProducts.YEARLY_OFFER,
    )
    if (token.isBlank()) return LaunchResult.NO_OFFER
    return purchase.subscribeProduct(this, BillingProducts.YEARLY, token)
}
```

`LAUNCHED` chỉ mở được Play UI; chờ event và premium state. Kết quả khác phải kết thúc loading để xử lý lỗi/thử lại; `DEV_MODE` là mô phỏng.

Subscription: `getPriceSub(productId, token)` trả giá **pha đầu** của offer đã chọn; `getPriceSub(productId)` trả giá gia hạn của offer cuối trong dữ liệu Play, không chọn theo token. Mẫu dùng mỗi product một plan; với nhiều plan/offer, cần kiểm tra giá, chu kỳ và trial hiển thị khớp offer mua. Resolver có fallback; không suy ra trial từ tên ID mẫu.

Nút **Restore** gọi `Billing.restore()` trong coroutine. Xử lý `Restored(productIds)`, `NothingToRestore`, `Error(code, message)`; SDK cập nhật `isPremium`. Không cần init billing lại trước mỗi restore.

## 5. Bảng cấu hình và kết nối tùy chọn

| Cấu hình | Mặc định / trường hợp cần dùng |
| --- | --- |
| `BillingKit.setDevMode` | Chưa set thì kế thừa ads dev flag nếu có, nếu không thì `false`. Mẫu chủ động dùng `false`; chỉ `true` khi thử UI mô phỏng. |
| `PurchaseItem.type` | Chọn rõ `PURCHASE`, `SUBSCRIPTION` hoặc `CONSUMABLE`; không có catalog tự tạo trên Play. |
| Base plan / offer | Đặt tọa độ thật. `offerId = ""` để SDK chọn offer trong plan, **không ép mua base plan không ưu đãi**. Không khớp thì resolver có thể chọn offer khác. |
| `Billing.awaitReady(timeoutMs)` | 5 giây. Splash timeout/error thì tiếp tục với premium đã lưu trong app; màn mua chưa có giá thì cho thử lại. |
| `Billing.restore()` | Timeout nội bộ 10 giây, không có tham số timeout public. |
| Kiểm tra receipt qua backend | `AppPurchase.getInstance().setPurchaseVerifier(...)`; unset thì không có kiểm tra server của app. Hook chỉ áp dụng purchase từ billing flow, **không áp dụng startup/restore sweep**. Xem [PurchaseVerifier](../billingkit/src/main/java/com/ads/module/billing/PurchaseVerifier.java). |
| Account/profile của app | `AppPurchase.getInstance().setObfuscatedAccountId(...)` / `setObfuscatedProfileId(...)` trước launch khi cần liên kết tài khoản. |
| Analytics / Adjust | [Trackkit](trackkit-integration.vi.md) trước billing; Adjust dùng [wiring ads/Adjust](ads-onboarding-integration.vi.md#adjust-token-và-kiểm-tra), không tự gửi lặp purchase event. |

### Khi app có ads/onboarding

Luồng chuẩn dùng premium do BillingKit lưu trong app. `Billing.install()` nối nguồn này vào ad gate; PayKit cũng thực hiện bước đó. Trong `ObSplashActivity` đang có, thêm:

```kotlin
override suspend fun onInitBilling() {
    com.ads.module.billing.Billing.awaitReady()
}
```

**Splash timeout/error thì tiếp tục OB theo premium đã lưu trong app:** premium thì bỏ ads, chưa premium thì đi theo config ads. Không thêm màn chờ, reset premium hoặc tự ép premium về `false`. Khi Play trả kết quả xác minh thành công, BillingKit cập nhật cache, `Billing.isPremium` và ad gate; xác minh lỗi giữ giá trị trước đó.

Nếu cần giải phóng ads đã preload khi mua thành công, gọi một lần `AdGate.installPremiumObserver(appScope, Billing.isPremium)` với scope sống theo Application.

**Chỉ khi app quản lý premium bằng nguồn riêng:** BillingKit không tự đọc cache/repository của app. Sau `Billing.install()` hoặc `PayKit.install()`, cài `com.ads.module.helper.Entitlement.install(source)`; `source` thực hiện `EntitlementSource.isPremium(context)` và đọc premium đã tải từ nguồn hiện có của app. App tiếp tục đồng bộ nguồn này khi nhận kết quả mua/restore/backend. Cấu hình này chỉ đổi nguồn chặn ads, không đổi `Billing.isPremium` hay premium của PayKit; không gọi `setPurchase(false)` chỉ vì Play chưa trả lời.

## 6. Kiểm tra

- [ ] Catalog khớp Play; có giá thực trước khi bật nút mua.
- [ ] Test thành công, hủy, lỗi, pending; `LAUNCHED` không tự mở khóa premium.
- [ ] Mở lại app/restore đúng entitlement; consumable không cấp premium.
- [ ] Splash timeout: cache premium vẫn bỏ ads; cache chưa premium tiếp tục theo config ads. Mua/restore cập nhật UI và nguồn premium mà ad gate đang dùng.
- [ ] Giao dịch Play dùng license tester và `setDevMode(false)`, không nhầm với mô phỏng local.

Giá trống: kiểm tra catalog, tài khoản test và kết nối Play. Offer không đúng: kiểm tra plan/offer thực tế của sản phẩm.
