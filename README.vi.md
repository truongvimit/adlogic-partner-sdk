**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# adlogic-partner-sdk

Bộ SDK Android cho quảng cáo, onboarding, analytics, billing và paywall. Chọn tính năng cần dùng, cấu hình build chung rồi làm theo quickstart của module tương ứng.

## Chọn module

| App cần | Khai báo | Bắt đầu tại |
| --- | --- | --- |
| Quảng cáo AdMob | `ads` | [Ads](ads/README.md) |
| Splash + ngôn ngữ + onboarding có quảng cáo | `ads + onboardkitorigin` | [OnboardKit](onboardkitorigin/README.vi.md) |
| Mua hàng với UI riêng | `billingkit` | [BillingKit](billingkit/README.md) |
| Màn paywall dựng sẵn | `paykit` | [PayKit](paykit/README.md) |
| Firebase Analytics hoặc remote config cho ads/paywall | `suite-firebase` (thêm `ads`/`paykit` nếu dùng source config tương ứng) | [Firebase](suite-firebase/README.md) |
| Analytics gửi về backend riêng | `trackkit` | [Trackkit](trackkit/README.vi.md) |
| Dashboard debug quảng cáo | `adtracer (debugImplementation)` | [AdTracer](adtracer/README.md) |

Chỉ thêm module cần dùng. Ads, onboarding, billing, paywall và Firebase đã cung cấp Trackkit. PayKit tự kéo billing engine lúc chạy; chỉ khai báo thêm `billingkit` nếu gọi API của nó trực tiếp. Firebase là tùy chọn. App chỉ dùng billing/paywall không kéo theo bộ quảng cáo.

## Cấu hình build

Dùng JDK 17, `minSdk 24+` và `compileSdk 36+`. Repo đang build với Kotlin 2.1.0, AGP 8.12.0, Gradle 8.13 và targetSdk 36.

Gộp các repository sau vào cấu hình Gradle đang có; không tạo thêm một khối `dependencyResolutionManagement` thứ hai. Ba repository mediation chỉ cần khi dùng ads/onboarding.

```groovy
// settings.gradle
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }

        // Only when ads or onboardkitorigin is included.
        maven { url 'https://artifact.bytedance.com/repository/pangle/' }
        maven { url 'https://android-sdk.is.com/' }
        maven { url 'https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea' }
    }
}
```

Hướng dẫn này dành cho phiên bản **5.2.4**. Giữ mọi module cùng phiên bản. Khi nâng cấp sau này, chọn một [tag đã phát hành](https://github.com/truongvimit/adlogic-partner-sdk/tags) và đọc README tại tag đó.

Ví dụ app dùng ads và onboarding. Với tổ hợp khác, thay tên artifact theo bảng trên.

```groovy
// app/build.gradle
def sdkVersion = '5.2.4'
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
}
```

## Thứ tự tích hợp

Khai báo class `Application` của app trong manifest. Trong `Application.onCreate()`, sau `super.onCreate()`, chỉ làm các bước tương ứng với module đã chọn:

| Bước | Cần làm | Hướng dẫn |
| --- | --- | --- |
| 1 · Analytics | Nếu thu thập event SDK, khởi tạo `Tracker` và đăng ký nơi nhận trước khi các kit phát event. | [Trackkit](trackkit/README.vi.md) |
| 2 · Ads | Cung cấp metadata AdMob/Meta và JSON quảng cáo, rồi khởi tạo `ERainAd`. | [Quickstart Ads](ads/README.md) |
| 3 · Mua hàng | Cài `PayKit` hoặc khởi tạo `BillingKit` nếu dùng UI riêng. Khi dùng PayKit, để PayKit khởi tạo billing. | [PayKit](paykit/README.md) / [BillingKit](billingkit/README.md) |
| 4 · Onboarding | Install/configure `OnboardingSdk` rồi đăng ký Activity kế thừa `ObSplashActivity`. | [OnboardKit](onboardkitorigin/README.vi.md) |

Khi dùng OnboardKit, splash tự chạy consent và bước notification. Nếu chỉ dùng ads, gọi `ConsentCenter.request(...)` từ Activity trước khi request quảng cáo. Firebase cần `google-services.json` của app và Google Services plugin; xem [hướng dẫn Firebase](suite-firebase/README.md).

## Giá trị app cần cung cấp

| Phần | Cần làm |
| --- | --- |
| Ads | AdMob app ID, Meta app ID/client token, placement ID trong `assets/ad_config.json` và ID test trong `ad_config_debug.json`. Nếu thiếu file debug, SDK dùng file thường. |
| Onboarding | Activity đích, ngôn ngữ/nội dung và các placement quảng cáo. |
| Mua hàng | Product ID trên Play và cách xác định premium. PayKit cần thêm URL điều khoản/quyền riêng tư và JSON catalog của app. |
| Firebase · tùy chọn | Cấu hình Firebase của app và các tham số Remote Config đã publish cho source cần dùng. |

## Kiểm tra sau tích hợp

- Mở bản debug dùng ID quảng cáo test; xác nhận khởi tạo và kết quả consent trước khi request ads.
- Thử thao tác khi chưa có ad: điều hướng vẫn phải hoàn tất. Kiểm tra từ chối notification và Home/quay lại.
- Nếu có mua hàng, kiểm tra khôi phục premium trước khi hiện ads và thử paywall với catalog trên Play của app.

Khi cần tùy biến thêm, mở các link source trong hướng dẫn module hoặc Go to Definition trong IDE. Bắt đầu bằng quickstart, không cần cấu hình mọi tùy chọn.

## License

MIT — xem [LICENSE](LICENSE).
