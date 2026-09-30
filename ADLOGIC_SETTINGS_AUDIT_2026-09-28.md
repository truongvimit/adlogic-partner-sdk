# AdLogic settings contract

Tài liệu mô tả source hiện tại của nhánh. Hướng dẫn tích hợp đầy đủ và bảng mặc định nằm ở [Remote settings](partner-integration/remote-settings.vi.md), [Ads](ads/README.md), [OnboardKit](onboardkitorigin/README.vi.md) và [Privacy → Goal](partner-integration/privacy-goals-screen.vi.md). Metadata build dùng `adlogic_sdk_version` trong [versions.gradle](versions.gradle); metadata này không xác nhận các thay đổi trong nhánh đã được phát hành.

## Document và quyền sở hữu

| Firebase parameter | App asset | Nội dung |
| --- | --- | --- |
| `ad_remote_config` | `ad_config.json`; debug ưu tiên `ad_config_debug.json` | IDs/waterfall, `isEnable`, UA, CTA/components, `click_action`, banner cadence và `open_resume.app_resume_load_delay_ms` |
| `ad_behavior_config` | `ad_behavior_config.json` tùy chọn | Global ads gate, consent timeout, timeout/cache/reload/presentation theo format, placement behavior, AutoBuffer và telemetry |
| `onboarding_config` | `onboarding_config.json` tùy chọn | Splash/LFO/OB behavior, order, Skip/auto-next, exit interstitial, OB5/question và `privacy_goals_screen.enabled` |

SDK đóng gói hai grouped defaults dưới `adlogic_defaults/` và sinh typed defaults lúc build. File cùng tên ở asset root thuộc app tier, kể cả khi giống hoàn toàn bundled defaults. Nội dung UI, resource IDs, catalog ngôn ngữ và lựa chọn Goal thuộc code/resources của app. Firebase adapter nằm ở [suite-firebase](suite-firebase/README.md); PayKit dùng document riêng.

## Ưu tiên và cache

- Grouped setting: field remote hợp lệ → legacy `ob_*` đã được backend gửi → asset root của app → host config/hook → SDK default. Bất kỳ scope remote nào cũng thắng mọi scope asset. Trong cùng nguồn: screen → nhóm OB → placement → format. Object behavior rỗng không che leaf bên dưới.
- `ad_remote_config` merge theo từng field có mặt; remote thiếu field/placement thì giữ app/code phía dưới. `false`, `0`, chuỗi/mảng rỗng được xử lý theo contract riêng của field. `components: []` giữ XML, không ẩn toàn bộ native.
- Fetch grouped thành công thay toàn bộ remote document; `{}` hoặc `{"schema_version":1}` xóa override. Field thiếu/sai kiểu/range/enum/null fallback và log `AdLogicSettings`. Document malformed, blank hoặc schema không hỗ trợ giữ bản hợp lệ trước đó. Fetch lỗi/timeout giữ cache hợp lệ. Riêng ad-unit document, Firebase missing/blank sau fetch thành công xóa remote patch.
- Debug giữ IDs từ debug asset (fallback normal asset); các behavior field vẫn nhận remote như release. Key chỉ có remote bị bỏ trừ khi tắt slot. `setAllowRemoteOverrideInDebug(true)` cho phép lấy cả remote IDs.
- Remote refresh thuộc SDK scope, tiếp tục sau splash. Consent, remote và billing bắt đầu song song; request splash chờ consent/gate, không chờ fetch. Cấu hình mới áp dụng ở lần đọc tiếp theo; không khởi động lại request/timer/navigation đã chốt.
- Cảnh báo OnboardKit sau refresh liệt kê placement app map mà remote không khai báo; các key đó giữ app values.

## Hợp đồng consumer

| Consumer | Hành vi hiện tại |
| --- | --- |
| Native click | Chỉ lấy `click_action` từ base key của `ad_config`: `auto_next`, `none`, `reload`; floor `_high` không sở hữu action. Pager mặc định `auto_next` và không reload; `reload` trong pager xử lý như `none`. Native khác mặc định `reload`. |
| Native style | Remote `positionCTA` → app `positionCTA` → host/default. JSON `native_template`/`content_template` đã bỏ. Mỗi bind đọc lại màu/chiều cao/components, kể cả click/resume replacement. Frame cố định của popup/fullscreen/splash inline/Privacy/Goal giữ loại layout riêng. CTA corner radius theo behavior vẫn áp dụng khi helper không có style tường minh. |
| Banner | `enable_ua_check` của placement được đọc lại sau refresh; setter host tường minh ưu tiên hơn. Chỉ `reloadIntervalSeconds > 0` đổi cadence; thiếu/sai/0 dùng host/default. |
| Interstitial | Screen/placement `presentation.loading_enabled` theo behavior đã chốt cho cả ready-cache và load-and-show. Splash/question behavior nhận tier timeout, loading dialog, cache age; không nhận `load_and_show.*`. Exit OB có wait riêng. |
| AutoBuffer | Host phải configure/start. Rules remote/asset có thể thêm placement ngoài list host; host predicate/explicit false vẫn chặn. Placement mới được quản lý bắt đầu cooldown khi settings đổi. `tick_ms = 0` theo resolved interstitial interval. |
| App-open | `failure_backoff_ms` phải có 1–10 số nguyên trong 1–3,600,000 ms; rỗng/sai fallback app/SDK. |
| Diagnostics | Consent timeout và daily click-cap log dùng giá trị đang có hiệu lực. |

## Onboarding

`onboarding.order` chọn/sắp xếp catalog app. Order sai kiểu, rỗng ID, trùng ID hoặc chứa ID ngoài catalog bị loại cả field; cùng phép kiểm tra áp dụng cho flags và preload. `[]` bỏ pager. Remote order chọn được trang app khai báo disabled; asset order chỉ giữ trang enabled. Thứ tự chuẩn là `ob1, full1, ob2, full2, ob3, ob4`. Danh sách chốt tại lần preload OB đầu; remote order đến sau áp dụng lượt flow tiếp theo.

Mỗi placement pager có một lượt load/waterfall mỗi attempt. View còn tồn tại thì giữ native khi chuyển trang; revisit dùng cùng ad. View destroy giải phóng ad. Fullscreen auto-next bắt đầu lại mỗi lượt ghé, Skip hiện ngay khi revisit nếu được bật hoặc cần chống kẹt. No-fill lần đầu đi tiếp; revisit trang lỗi hiện fallback/Skip. Click action được chốt khi click và giữ đến lúc quay lại.

`lfo.languages.supported_codes` loại mã ngoài catalog, giữ thứ tự mã hợp lệ và loại trùng; rỗng sau lọc dùng catalog host. `default_code: ""` tường minh xóa default cấu hình, không xóa lựa chọn user đã lưu. Mã default phải thuộc danh sách hiển thị.

Splash `notification_settle_ms` mặc định 1000 ms. `splash.navigation.next_screen_timing` remote/asset khác AUTO thắng cả entry: `UNDER_AD` bỏ native_fs trong lượt đó. AUTO dùng AFTER_AD khi native_fs eligible hoặc first-open/entry, UNDER_AD cho launcher đã hoàn tất onboarding. Exit OB có timing riêng, entry vẫn AFTER_AD.

OB5 chỉ preload khi tới cuối pager và `onboarding.preload.ob5_on_last_step` bật; exit chỉ mở nếu native đã sẵn sàng. Tắt preload đó cũng ngăn OB5 mở dù `ob5.enabled = true`.

Privacy → Goal bật khi `privacy_goals_screen.enabled` và có host goal/question options. Native Privacy preload ở trang cuối; exit interstitial vẫn theo timing đã cấu hình. Base `native_select`/`native_select_alt` điều khiển từng waterfall. Hoàn tất Goal lưu câu trả lời và hoàn tất flow; không hoàn tất sớm khi rời pager.

## Nguồn và kiểm tra

- [SettingsDocument / SettingsSnapshot](ads/src/main/java/com/ads/module/config/settings/SettingsDocument.kt), [AdBehavior](ads/src/main/java/com/ads/module/config/settings/AdBehavior.kt), [AdRemoteConfig](ads/src/main/java/com/ads/module/config/AdRemoteConfig.kt).
- [OnboardingSettings](onboardkitorigin/src/main/java/io/onboardkit/remote/OnboardingSettings.kt), [PreloadChain](onboardkitorigin/src/main/java/io/onboardkit/ads/PreloadChain.kt), [OnboardingSdk](onboardkitorigin/src/main/java/io/onboardkit/OnboardingSdk.kt).
- [JSON mẫu](partner-integration/examples/ads-onboarding/), [validation](partner-integration/onboarding-flow-validation.md).

```sh
./gradlew :ads:testDebugUnitTest :onboardkitorigin:testDebugUnitTest :suite-firebase:testDebugUnitTest :app:testDebugUnitTest
```

Kết quả JVM không thay thế kiểm thử thiết bị cho UMP, notification, vendor ad fill và gesture thực tế.
