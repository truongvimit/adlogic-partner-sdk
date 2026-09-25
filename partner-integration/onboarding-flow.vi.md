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

ID cố định theo màn, không theo vị trí. Đổi OB4 lên đầu vẫn dùng `native_ob4`. Các tầng `_high`, `_high1`…`_high9` thuộc đúng placement gốc. Hai fullscreen có buffer riêng. `native_fs` vẫn là native splash riêng; `inter_after_ob3` vẫn là interstitial sau toàn bộ OB, kể cả màn cuối là OB4 hoặc Full2.

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
- Chỉ các ID app khai báo mới được chọn. `order` remote chọn được cả màn `enabled = false`; `order` trong asset app chỉ chọn màn `enabled = true`. ID chưa biết được bỏ qua.
- Danh sách sai kiểu, ID rỗng hoặc trùng ID: bỏ override `order`, dùng nguồn kế tiếp bên dưới.
- `order` là danh sách duy nhất chọn và sắp xếp màn trong JSON: bỏ ID để bỏ màn, thêm lại ID để hiện màn. `onboarding.steps.<id>.enabled` đã bỏ và bị bỏ qua kể cả trong remote/cache cũ. Thứ tự ưu tiên: `order` remote > cờ cũ `ob_enable_step_ob1..4` mà backend đã gửi > `order` trong asset app. Khi có `order` remote hợp lệ, các cờ cũ không lọc thêm màn; cờ đã gửi bật hoặc tắt màn theo cả hai chiều, kể cả khi asset app có `order`.
- Không cần khai báo `steps`. Chỉ dùng `steps.<id>` nếu cần ghi đè riêng template, hành vi ads hoặc nút Skip/auto-next; không có công tắc bật/tắt màn hay placement ở đây. Bật/tắt ads từng vị trí bằng `ad_config.<placement>.isEnable`.
- Quy tắc ưu tiên settings là remote hợp lệ → key `ob_*` backend đã gửi → custom asset → app → default SDK; remote ở bất kỳ scope nào ưu tiên hơn asset ở bất kỳ scope nào. Không khai báo lại ad unit ID trong `onboarding_config`.

Danh sách màn được chốt tại lần preload OB đầu tiên ở LFO; LFO exit và pager dùng chung danh sách đó. Nếu vào thẳng pager mà chưa preload thì chốt tại pager entry. Remote đổi `order` sau thời điểm này áp dụng từ lượt splash tiếp theo, không loại một màn đã lên kế hoạch preload. Không có fetch riêng cho OB. Firebase cache/fetch interval vẫn áp dụng như tích hợp hiện tại; SDK không tự đặt interval, mặc định của Firebase là 12 giờ.

## Bật ads trên Firebase

Parameter **`ad_remote_config`**, kiểu String chứa document cấu hình ads hiện có. Thêm các placement cần dùng vào document đầy đủ, ví dụ một entry:

```json
{
  "native_full1": {
    "id": "ca-app-pub-YOUR_ACCOUNT/YOUR_NATIVE_UNIT",
    "isEnable": true,
    "enable_ua_check": false
  }
}
```

Đây chỉ là trích đoạn minh họa: giữ các placement splash/LFO/home khác trong document khi publish. `id` phải là native ad unit thực tế của app. Có thể dùng `ids` hoặc các key floor theo hướng dẫn ads hiện có.

- Thiếu placement hoặc tất cả tầng bị tắt/ID không hợp lệ: không request. Content vẫn hiện, vùng ads ẩn; fullscreen bị bỏ qua.
- Consent, premium, master switch, UA và force-update gate vẫn được kiểm tra.
- Example release để cả sáu placement và các tầng của chúng `isEnable = false`: cài mới chưa có remote sẽ không tải ads OB. Remote đã activate/cache vẫn có thể dùng khi fetch thất bại.
- SDK nói chung vẫn hỗ trợ assets/raw ID của partner; placement nào `ad_remote_config` của backend khai báo thì remote ưu tiên hơn raw ID trong code. Partner tự bật local assets thì đó vẫn là nguồn ads hợp lệ; muốn remote-only phải giữ các entry local tắt như example.
- Debug mặc định giữ ad unit test của `ad_config_debug.json` (hoặc `ad_config.json` khi không có file debug) và không để remote thay ad IDs; các field khác của `ad_remote_config` (ví dụ `isEnable`) và settings `onboarding_config` vẫn thử được qua remote. Key chỉ remote khai báo bị bỏ, trừ khi nó tắt slot. Không dùng ad unit production để test.

## Preload và vòng đời ads

Tại callback **chọn ngôn ngữ đầu tiên** (vị trí trước đây preload OB1/OB2), SDK preload tất cả native thuộc danh sách OB hợp lệ: tối đa OB1, OB2, OB3, OB4, Full1, Full2. Không chờ tất cả tải xong mới cho tiếp tục LFO.

Điều kiện preload là **màn có trong danh sách đã chốt + placement có ID sử dụng được và được bật + các gate ads đều cho phép**. Chỉ có ID trong ad config không tự tạo request. `isEnable = false` ở placement gốc tắt cả waterfall, kể cả tầng `_high` vẫn bật. Request đang chờ foreground/focus kiểm tra lại placement và lấy ID hiện tại trước khi gửi.

Danh sách màn được giữ ổn định, nhưng các chặn ads (placement/master switch, premium, consent, force update, UA) vẫn có hiệu lực. SDK chỉ preload khi chưa biết có điều kiện chặn show; không thể bảo đảm mỗi preload đều có impression: người dùng có thể thoát/chuyển trang trước fill, ads no-fill, hoặc điều kiện ads thay đổi sau khi request đã gửi. Những thay đổi này vẫn phải chặn show; request đã gửi không thể thu hồi.

`inter_after_ob3` preload khi vào pager (OB1 trong flow mặc định); reorder không biến tên interstitial thành ràng buộc phải gặp OB3. Splash, UMP, billing và LFO giữ lịch chạy hiện có.

Mỗi placement OB có tối đa một lượt load/waterfall trong một lần chạy. Preload và màn hiển thị chia sẻ request đang chạy. Chọn ngôn ngữ lại, no-fill, swipe/back không khởi động lượt tải mới. Không refresh/reload hoặc preload replacement sau impression/click cho sáu màn này, kể cả policy native chung bật reload. `click.action = auto_next` vẫn chuyển trang khi quay lại; `none` giữ trang; `reload` được xử lý như `none` trong pager OB.

Khi rời màn, ads đã hiển thị được giải phóng như trước. Quay lại content vẫn thấy nội dung nhưng không xin ads mới; fullscreen đã tiêu thụ ads sẽ đi tiếp nếu không còn ad hợp lệ. Request chưa hoàn tất/ads chưa dùng vẫn có thể được nhận. Activity recreation giữ trạng thái lượt tải; splash mới hoặc `OnboardingSdk.reset()` bắt đầu lượt mới.

Các key `onboarding.preload.initial_content_trigger`, `initial_content_count`, `next_step_enabled`, `upcoming_fullscreen_enabled` đã bỏ: không còn chia nhỏ preload OB theo màn. Preload LFO, OB5 standalone và question giữ cơ chế riêng.

## Swipe và navigation

- `lock_pager_swipe = true`: khóa toàn bộ swipe.
- `false`: OB1 khóa theo ID dù nằm ở vị trí nào; các content khác được swipe.
- Full1/Full2 chỉ được swipe khi có callback impression; load/bind chưa mở swipe.
- No-fill fullscreen tự đi tiếp. Skip, auto-next, click-return và chống điều hướng trùng vẫn giữ.
- Màn cuối swipe hoàn tất khi `swipe_completes_last_step = true`; CTA vẫn dùng nhánh exit hiện có.

## Chuyển từ mapping cũ

1. Thay fullscreen `AdFullScreenStepDefinition(StepId.OB3)` bằng `FULL1`; thêm `FULL2` nếu dùng.
2. Chuyển content cũ `OB4`/`native_ob3` thành `OB3`; thêm content `OB4`/`native_ob4`.
3. Đổi `native_fsob` và từng floor sang `native_full1`; khai báo riêng `native_full2`. Không có alias tự động về key cũ.
4. Chuyển settings/UI/analytics cũ theo danh tính mới: `ob3` bây giờ là content, fullscreen dùng `full1`/`full2`.
5. Khi phát hành cho nhiều version app, dùng điều kiện app version trên Firebase để payload mới tới build hỗ trợ mapping mới; giữ payload cũ cho build cũ.

OB5 standalone/question và native splash không nằm trong sáu màn này; cấu hình riêng vẫn được giữ. Trạng thái đã hoàn tất onboarding không bị reset chỉ vì đổi danh sách.
