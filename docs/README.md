# Hướng dẫn tích hợp cho partner

Chọn hướng dẫn theo tính năng app cần. Làm lần lượt phần tích hợp cơ bản; chỉ mở bảng tùy chọn khi app cần thay đổi hành vi mặc định.

| App cần | Đọc tài liệu | Module |
| --- | --- | --- |
| Splash → ngôn ngữ → onboarding có quảng cáo → màn chính | **[Tích hợp Ads + OnboardKit](ads-onboarding-integration.vi.md)** | `ads` + `onboardkitorigin` |
| Ads trong các màn riêng của app | [Ads](../ads/README.md) | `ads` |
| Mua hàng với UI riêng | [BillingKit](../billingkit/README.md) | `billingkit` |
| Paywall dựng sẵn | [PayKit](../paykit/README.md) | `paykit` |
| Remote JSON / Firebase Analytics | [Firebase](../suite-firebase/README.md) | `suite-firebase` và module cần kết nối |
| Analytics / debug quảng cáo | [Trackkit](../trackkit/README.vi.md) / [AdTracer](../adtracer/README.md) | Theo hướng dẫn module |

Hiện guide từng bước trong `docs/` tập trung vào Ads + OnboardKit. Các tính năng còn lại dẫn tới tài liệu module đang có.

## Cách dùng tài liệu

- **Code cơ bản:** chỉ khai báo dữ liệu của app và phần nối SDK; giữ nguyên các giá trị mặc định.
- **Bảng tùy chọn:** nêu mặc định, lúc cần đổi và nơi cấu hình. Không cần chép cả bảng vào code hay Firebase.
- **File mẫu:** [ad_config.json](examples/ads-onboarding/ad_config.json) và [ad_config_debug.json](examples/ads-onboarding/ad_config_debug.json) đều dùng ad ID test. Copy vào `app/src/main/assets/`; thay ID ở file thật trước khi phát hành.
- **README module:** tra cứu thêm API, lifecycle và tùy biến khi cần.

## Phiên bản

Ví dụ dependency dùng tag **5.2.10**; giữ mọi module cùng tag. Các mặc định trong guide áp dụng cho bản này. Khi nâng cấp, dùng tài liệu đi cùng tag mới.

[README SDK](../README.vi.md) · [Bắt đầu tích hợp Ads + OnboardKit](ads-onboarding-integration.vi.md)
