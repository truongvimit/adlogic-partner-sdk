# Hướng dẫn tích hợp cho partner

Chọn hướng dẫn theo tính năng app cần. Làm lần lượt phần tích hợp cơ bản; chỉ mở bảng tùy chọn khi app cần thay đổi hành vi mặc định.

| App cần | Đọc tài liệu | Module |
| --- | --- | --- |
| Splash → ngôn ngữ → onboarding có quảng cáo → màn chính | **[Tích hợp Ads + OnboardKit](ads-onboarding-integration.vi.md)** | `ads` + `onboardkitorigin` |
| Ads trong các màn riêng của app | Đã làm guide Ads + OnboardKit: dùng mẫu [native](ads-onboarding-integration.vi.md#native-ở-màn-app-dùng-placement-constant), [inter](ads-onboarding-integration.vi.md#interstitial-ở-màn-app-dùng-placement-constant), [app-open](ads-onboarding-integration.vi.md#app-open-khi-quay-lại) với [AppAdPlacement.kt](examples/ads-onboarding/AppAdPlacement.kt) của app. Không dùng OnboardKit: làm theo [Ads](../ads/README.md) từ init, placement đến consent. | `ads` |
| Mua hàng với UI riêng | [Tích hợp BillingKit](billing-integration.vi.md) | `billingkit` |
| Paywall dựng sẵn | [Tích hợp PayKit](paywall-integration.vi.md) | `paykit`, thêm `billingkit` khi gọi API trực tiếp |
| Remote JSON / Firebase Analytics | [Tích hợp Firebase](firebase-integration.vi.md) | `suite-firebase` và module cần kết nối |
| Event của app / chọn nơi nhận analytics | [Tích hợp Trackkit](trackkit-integration.vi.md) | `trackkit` đã được các kit cung cấp |
| Dashboard debug quảng cáo | [Tích hợp AdTracer](adtracer-integration.vi.md) | `adtracer` chỉ trong debug |

## Thứ tự ghép vào app

1. **Chọn tính năng chính:** Ads/OB, BillingKit với UI riêng hoặc PayKit có UI sẵn. App có thể ghép ads và mua hàng; PayKit tự khởi tạo billing nên không đăng ký thêm catalog bằng `AppPurchase.initBilling`.
2. **Nếu thu thập analytics:** install Tracker và sink trong Application trước các kit phát event. Firebase và AdTracer là các nơi nhận tùy chọn; Adjust nối theo [guide ads/Adjust](ads-onboarding-integration.vi.md#adjust-token-và-kiểm-tra).
3. **Khởi tạo kit và dữ liệu local:** dùng Application hiện có. Với OB có paywall, install PayKit trước OnboardKit; chờ billing trong hook splash trước ads.
4. **Nếu dùng remote:** cài nguồn Firebase sau local setup. Splash OB đã refresh ads; paywall cần `PayKit.sync()` riêng trước lúc cần remote config.
5. **Kiểm thử:** làm checklist cuối guide đã chọn; thêm AdTracer nếu cần theo dõi luồng ads trên dashboard.

Không cần làm tất cả guide. Mỗi guide ghi rõ dependency, file cần có và phần tùy chọn; không copy nhiều Application hay nhiều lần install cùng một SDK.

## Cách dùng tài liệu

- **Code cơ bản:** chỉ khai báo dữ liệu của app và phần nối SDK; giữ các hành vi mặc định SDK; JSON mẫu giữ cấu hình của example.
- **Bảng tùy chọn:** nêu mặc định, lúc cần đổi và nơi cấu hình. Không cần chép cả bảng vào code hay Firebase.
- **Placement app:** [AppAdPlacement.kt](examples/ads-onboarding/AppAdPlacement.kt) tập trung key OB và các màn app; ad unit ID nằm trong JSON.
- **File mẫu:** [ad_config.json](examples/ads-onboarding/ad_config.json) và [ad_config_debug.json](examples/ads-onboarding/ad_config_debug.json) đều dùng ad ID test. Copy vào `app/src/main/assets/`; thay ID ở file thật trước khi phát hành.
- **README module:** tra cứu thêm API, lifecycle và tùy biến khi cần.

## File mẫu để copy

| Tính năng | Mẫu | Nơi dùng |
| --- | --- | --- |
| Ads + OB | [Production JSON](examples/ads-onboarding/ad_config.json), [debug JSON](examples/ads-onboarding/ad_config_debug.json), [AppAdPlacement](examples/ads-onboarding/AppAdPlacement.kt), [OnboardKitSetup](examples/ads-onboarding/OnboardKitSetup.kt), [PartnerApp](examples/ads-onboarding/PartnerApp.kt) | Assets, catalog và khởi tạo SDK; cả hai JSON dùng ad ID test. |
| Billing UI riêng | [BillingProducts.kt](examples/billing/BillingProducts.kt) | Catalog product/base plan/offer; thay bằng sản phẩm Play của app. |
| Paywall | [paywall_config.json](examples/paywall/paywall_config.json), [paywall_strings.xml](examples/paywall/paywall_strings.xml) | `res/raw`, `res/values`; đủ field example, thay catalog/copy/URL. |
| Event app | [AppEvents.kt](examples/trackkit/AppEvents.kt) | Key event/param tập trung, chỉ giữ event app cần. |
| Dashboard debug | [AdTracerSink](examples/adtracer/debug/AdTracerSink.kt), [debug entry](examples/adtracer/debug/DebugSinks.kt), [release no-op](examples/adtracer/release/DebugSinks.kt) | Source sets debug/release; cùng package app. |

Firebase dùng `google-services.json` do Console cấp đúng app; không có file credentials mẫu để copy.

Các ví dụ dependency đọc chung `adlogicSdkVersion` trong `gradle.properties` của app; xem [cấu hình build](../README.vi.md#cấu-hình-build). Khi nâng SDK, cập nhật property của app.

[README SDK](../README.vi.md) · [Bắt đầu tích hợp Ads + OnboardKit](ads-onboarding-integration.vi.md)
