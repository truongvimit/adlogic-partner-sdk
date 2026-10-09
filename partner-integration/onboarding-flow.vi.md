# Luồng OB: 4 màn nội dung và 2 fullscreen

Mặc định: **OB1 → Full1 → OB2 → Full2 → OB3 → OB4**. App khai báo catalog các màn có thể dùng và thứ tự mặc định; Firebase có thể chọn một phần catalog và đổi thứ tự qua `onboarding_config.onboarding.order`.

| StepId | Placement trong `ad_remote_config` | Loại |
|---|---|---|
| `OB1` / `ob1` | `native_ob1` | Nội dung |
| `OB2` / `ob2` | `native_ob2` | Nội dung |
| `OB3` / `ob3` | `native_ob3` | Nội dung |
| `OB4` / `ob4` | `native_ob4` | Nội dung |
| `FULL1` / `full1` | `native_full1` | Native fullscreen |
| `FULL2` / `full2` | `native_full2` | Native fullscreen |

ID cố định theo màn, không theo vị trí. Đổi OB4 lên đầu vẫn dùng `native_ob4`. Các tầng nằm trong `ids` của đúng key đó. Hai fullscreen có buffer riêng. `native_fs` vẫn là native splash riêng; `inter_after_ob3` vẫn là interstitial sau toàn bộ OB, kể cả màn cuối là OB4 hoặc Full2.

## Khai báo trong app

```kotlin
val config = onboardKitConfig {
    steps(
        ContentStepDefinition(StepId.OB1, titleRes = R.string.onboarding_title_1),
        AdFullScreenStepDefinition(StepId.FULL1),
        ContentStepDefinition(StepId.OB2, titleRes = R.string.onboarding_title_2),
        AdFullScreenStepDefinition(StepId.FULL2),
        ContentStepDefinition(StepId.OB3, titleRes = R.string.onboarding_title_3),
        ContentStepDefinition(StepId.OB4, titleRes = R.string.onboarding_title_4),
    )
    ads = AdsConfig.fromAdConfig()
}.getOrThrow()
OnboardingSdk.configure(config).getOrThrow()
```

Thêm `enabled = false` vào definition để ẩn màn đó khi remote không gửi `onboarding.order` và không gửi `ob_enable_step_obN` cho màn đó. `order` remote có liệt kê màn (hoặc `ob_enable_step_obN = true` đã gửi) sẽ hiện màn; `order` trong asset app vẫn giữ màn ẩn. Muốn remote không hiện được màn, bỏ hẳn màn khỏi `steps(...)`: remote không thêm được màn app chưa khai báo. App tự cung cấp title/subtitle/image/layout như trước. `defaultSteps()` tạo sáu màn theo thứ tự trên với nội dung mẫu SDK.

## Đổi danh sách trên Firebase

Dùng ngay file **`onboarding_config.json`** và parameter Firebase **`onboarding_config`** hiện có, không tạo file hay parameter mới. Đặt `order` trong object `onboarding`. Partner có thể chỉ khai báo phần cần đổi như ví dụ dưới. Các field thiếu (splash, LFO, timeout, skip…) vẫn dùng fallback: Firebase hợp lệ → key `ob_*` cũ mà backend đã gửi → JSON partner → cấu hình Kotlin app → mặc định SDK. Không cần copy toàn bộ default SDK; chỉ giữ lại những tùy chỉnh riêng đã có của partner. Mảng `order` được thay thế toàn bộ, không nối với danh sách mặc định:

```json
{
  "schema_version": 1,
  "onboarding": {
    "order": ["ob4", "full2", "ob1", "ob3"]
  }
}
```

Ví dụ này hiển thị **OB4 → Full2 → OB1 → OB3**, không preload OB2/Full1. Quy tắc:

- Không có `order`: giữ thứ tự app khai báo.
- `order: []`: bỏ toàn bộ pager OB; tiếp tục nhánh hoàn tất hiện có.
- Chỉ các ID app khai báo mới được chọn. `order` remote chọn được cả màn `enabled = false`; `order` trong asset app chỉ chọn màn `enabled = true`.
- Danh sách sai kiểu, ID rỗng, trùng ID hoặc có ID ngoài catalog app (sai hoa/thường, `ob5`, gõ nhầm): bỏ cả override `order`, dùng nguồn kế tiếp bên dưới.
- `order` là danh sách duy nhất chọn và sắp xếp màn trong JSON: bỏ ID để bỏ màn, thêm lại ID để hiện màn. `onboarding.steps.<id>.enabled` đã bỏ và bị bỏ qua kể cả trong remote/cache cũ. Thứ tự ưu tiên: `order` remote > cờ cũ `ob_enable_step_ob1..4` mà backend đã gửi > `order` trong asset app. Cùng phép kiểm tra catalog áp dụng cho danh sách màn và flags/preload. Khi có `order` remote hợp lệ, các cờ cũ không lọc thêm màn; cờ đã gửi bật hoặc tắt màn theo cả hai chiều, kể cả khi asset app có `order`.
- Không cần khai báo `steps`. Chỉ dùng `steps.<id>` nếu cần ghi đè riêng hành vi ads (`behavior.*`) hoặc Skip/auto-next của màn fullscreen (`fullscreen.*`); `steps.<id>` chỉ nhận hai nhánh này. Template native lấy từ `templateId` trên key placement trong `ad_config`, không đặt ở đây. Không có công tắc bật/tắt màn hay placement ở đây. Bật/tắt ads từng vị trí bằng `ad_config.<placement>.isEnable`.
- Quy tắc ưu tiên settings là remote hợp lệ → key `ob_*` backend đã gửi → custom asset → app → default SDK; remote ở bất kỳ scope nào ưu tiên hơn asset ở bất kỳ scope nào. Không khai báo lại ad unit ID trong `onboarding_config`.

Danh sách màn được chốt tại lần preload OB đầu tiên ở LFO; LFO exit và pager dùng chung danh sách đó. Nếu vào thẳng pager mà chưa preload thì chốt tại pager entry. Remote đổi `order` sau thời điểm này áp dụng từ lượt splash tiếp theo, không loại một màn đã lên kế hoạch preload. Không có fetch riêng cho OB. Firebase cache/fetch interval vẫn áp dụng như tích hợp hiện tại; SDK không tự đặt interval, mặc định của Firebase là 12 giờ.

## Bật ads trên Firebase

Parameter **`ad_remote_config`**, kiểu String chứa document cấu hình ads hiện có. Thêm các placement cần dùng vào document đầy đủ, ví dụ một entry:

```json
{
  "native_full1": {
    "ids": [{ "id": "ca-app-pub-YOUR_ACCOUNT/YOUR_NATIVE_UNIT" }],
    "isEnable": true,
    "enable_ua_check": false
  }
}
```

Đây chỉ là trích đoạn minh họa: giữ các placement splash/LFO/home khác trong document khi publish. Mỗi `id` trong `ids` phải là native ad unit thực tế của app. Nhiều tầng thì thêm tầng `{"id", "isEnable"}` vào `ids` (cao nhất trước, all price cuối); `"id"` cấp placement và key `_high*` rời không còn được đọc, xem [Waterfall: mỗi placement một key](ads-onboarding-integration.vi.md#waterfall-mỗi-placement-một-key).

- Thiếu placement hoặc tất cả tầng bị tắt/ID không hợp lệ: không request. Content vẫn hiện, vùng ads ẩn; fullscreen bị bỏ qua.
- Consent, premium, cờ từng placement, UA và force-update gate vẫn được kiểm tra.
- App demo `:app` khai báo và bật sáu placement OB cùng các tầng trong cả debug/release assets. File mẫu partner [`examples/ads-onboarding/ad_config.json`](examples/ads-onboarding/ad_config.json) khai báo đủ sáu key nhưng để `isEnable = false`; bật key nào app dùng. Remote hợp lệ có thể ghi đè từng field. Remote đã activate/cache vẫn được dùng khi fetch thất bại.
- SDK nói chung vẫn hỗ trợ assets/raw ID của partner; thứ tự ưu tiên là `ad_remote_config` của backend > `ad_config.json` của app > raw ID trong code. Partner tự bật local assets thì đó vẫn là nguồn ads hợp lệ; muốn remote-only phải chủ động đặt các entry local thành `isEnable = false`.
- Mọi build đọc cấu hình từ `ad_config.json`. Build debuggable đọc thêm `ad_config_debug.json`, file này chỉ có `ids` cho mỗi key placement, thường một tầng test: các tầng này thay cả waterfall của vị trí, nên debug thường không chạy waterfall. Field khác trong file debug bị bỏ qua kèm log `WARN`; placement đang bật trong `ad_config.json` mà không có entry debug thì không có ad (cũng log `WARN`). `ad_remote_config` remote áp mọi field trừ ID; `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` nhận cả ID remote khai báo. Settings `onboarding_config` áp như release. Không dùng ad unit production để test.

## Preload và vòng đời ads

Tại callback **chọn ngôn ngữ đầu tiên** (vị trí trước đây preload OB1/OB2), SDK preload tất cả native thuộc danh sách OB hợp lệ: tối đa OB1, OB2, OB3, OB4, Full1, Full2. Không chờ tất cả tải xong mới cho tiếp tục LFO.

Điều kiện preload là **màn có trong danh sách đã chốt + placement có ID sử dụng được và được bật + các gate ads đều cho phép**. Chỉ có ID trong ad config không tự tạo request. `isEnable = false` ở key placement tắt cả waterfall, kể cả tầng trong `ids` vẫn bật. Request đang chờ foreground/focus kiểm tra lại placement và lấy ID hiện tại trước khi gửi.

Danh sách màn được giữ ổn định, nhưng các chặn ads theo từng placement, premium, consent, force update và UA vẫn có hiệu lực. SDK chỉ preload khi chưa biết có điều kiện chặn show; không thể bảo đảm mỗi preload đều có impression: người dùng có thể thoát/chuyển trang trước fill, ads no-fill, hoặc điều kiện ads thay đổi sau khi request đã gửi. Những thay đổi này vẫn phải chặn show; request đã gửi không thể thu hồi.

`inter_after_ob3` preload khi vào pager (OB1 trong flow mặc định); reorder không biến tên interstitial thành ràng buộc phải gặp OB3. Splash, UMP, billing và LFO giữ lịch chạy hiện có.

Mỗi placement OB có tối đa một lượt load/waterfall trong một lần chạy. Preload và màn hiển thị chia sẻ request đang chạy. Chọn ngôn ngữ lại, no-fill, swipe/back không khởi động lượt tải mới. Không refresh/reload theo timer, khi quay lại app, hay preload replacement sau impression cho sáu màn này, kể cả policy native chung bật reload. Hành động click lấy từ `click_action` trên key của trang trong ad_config (`native_ob1..4`, `native_full1/2`; áp cho mọi tầng trong `ids`), mặc định `auto_next`: chuyển trang khi quay lại; `none` giữ trang; `reload`/`reload_waterfall` giữ trang, tải ad thay thế lúc click và gắn vào khi quay lại. Xem [Hành động khi click native](remote-settings.vi.md#hành-động-khi-click-native).

Khi chuyển sang trang khác mà view còn tồn tại, content và fullscreen giữ native đã bind; swipe/back quay lại hiển thị cùng ad, không tạo request hay impression callback mới để mở swipe. Native chỉ được giải phóng khi view bị hủy. Request đang chạy vẫn có thể hoàn tất khi trang không được chọn; callback điều hướng chỉ tác động đến trang đang active. Fullscreen khởi động lại auto-next mỗi lượt ghé; Skip hiện ngay khi quay lại nếu được bật (hoặc cần chống kẹt). No-fill ở lượt đầu tự đi tiếp; khi quay lại trang lỗi, trang hiện fallback và cho thoát bằng Skip. Activity recreation giữ trạng thái lượt tải, không đảm bảo giữ view/ad đã bị hủy; splash mới hoặc `OnboardingSdk.reset()` bắt đầu lượt mới.

Các key `onboarding.preload.initial_content_trigger`, `initial_content_count`, `next_step_enabled`, `upcoming_fullscreen_enabled` đã bỏ: không còn chia nhỏ preload OB theo màn. Preload LFO và OB5 standalone giữ cơ chế riêng.

## Swipe và navigation

- `lock_pager_swipe = true`: khóa toàn bộ swipe.
- `false`: OB1 khóa theo ID dù nằm ở vị trí nào.
- OB2/OB3/OB4 và Full1/Full2 chỉ được swipe khi có callback impression; load/bind chưa mở swipe. Mỗi lượt vào trang bắt đầu ở trạng thái khóa; quay lại trang có ad đã hiện thì mở ngay.
- Content không có ad để hiện (không unit, premium, no-fill) được swipe ngay.
- No-fill fullscreen tự đi tiếp. Skip, auto-next, click-return và chống điều hướng trùng vẫn giữ.
- Màn cuối swipe hoàn tất khi `swipe_completes_last_step = true`; CTA vẫn dùng nhánh exit hiện có.

OB5 standalone và native splash không nằm trong sáu màn này; cấu hình riêng vẫn được giữ. Trạng thái đã hoàn tất onboarding không bị reset chỉ vì đổi danh sách.
