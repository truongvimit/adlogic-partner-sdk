# Privacy → Goals flow

[English](privacy-goals-screen.md) · [Tiếng Việt](privacy-goals-screen.vi.md) · [हिन्दी](privacy-goals-screen.hi.md)

Flow chuẩn vẫn là `Splash → LFO → OB → inter_after_ob3 → Privacy → Goal`; `inter_after_ob3` chỉ có khi được bật (mặc định tắt). Privacy/Goal là phần
cuối của cùng onboarding flow, không phải một flow độc lập. SDK chỉ mở cụm này cho một flow chưa
hoàn tất và chỉ khi partner bật cờ riêng trong
`onboarding_config.json` (asset hoặc remote):

```json
{ "privacy_goals_screen": { "enabled": true } }
```

Mặc định là `false`. Code cũng bật được bằng `PrivacyGoalsScreenConfig(enabled = true)` (ví dụ
bên dưới); remote rồi asset app thắng giá trị trong code. Đặt `false` để bỏ cả cụm màn; khi đó
host tiếp tục nhánh hoàn tất thông thường. Cụm màn còn cần danh sách lựa chọn trong code:
`goal.options`. Không có danh sách này thì cờ bật bị bỏ qua
(logcat ghi cảnh báo), SDK không preload native Privacy và onboarding tiếp tục nhánh hoàn tất thông thường.
Nếu user đóng app khi đang ở Privacy hoặc Goal, state flow vẫn chưa completed và lần mở sau sẽ
chạy lại từ Splash → LFO.
Chỉ khi user bấm hoàn tất ở Goal SDK mới ghi nhận onboarding completed.
`native_select.isEnable = false` chỉ ẩn ad đầu; `native_select_alt.isEnable = false` chỉ tắt ad ALT.
Đây là nguồn bật/tắt duy nhất cho từng placement; không có `privacyAd.enabled` hay
`goalAd.enabled` riêng. Các gate consent, premium, UA và master ads vẫn áp dụng qua cùng AdsGuard
như LFO. `isEnable` của key bật/tắt cả waterfall `ids`; `isEnable` của một tầng chỉ dừng tầng đó.

Phần còn lại của trang mô tả flow mặc định `PER_SCREEN`; [Khi chạy SHARED_POOL](#khi-chạy-shared_pool) liệt kê những gì thay đổi ở flow `SHARED_POOL`.

SDK có layout mặc định. Partner không cần truyền `layoutRes` hay từng view ID trong `Config`. Android resource
merge sẽ cho resource của app override resource cùng tên trong SDK. Chỉ cần tạo các file sau trong
app (giữ đúng tên):

- `res/layout/ob_privacy_screen.xml`
- `res/layout/ob_goal_screen.xml`
- `res/layout/ob_goal_option.xml`

Contract bắt buộc chỉ còn ba điểm để SDK gắn logic:

- `ob_privacy_consent_checkbox`: view chọn đồng ý trên Privacy.
- `ob_goal_options`: `RecyclerView` chứa danh sách Goal; item root là vùng click và SDK cập nhật
  `isSelected`/`Checkable`.
- `ob_privacy_goals_ad`: `FrameLayout` cho ads. Dùng cùng một ID trên cả hai màn để ads giữ cùng
  kích thước và vị trí.

Nút Get Started/Continue không cần ID riêng. SDK tự tìm `Button` hoặc `TextView` clickable trong
layout. Với một UI có nhiều nút clickable, partner có thể đánh dấu nút đúng bằng ID tùy chọn
`ob_privacy_goals_continue`; SDK dùng marker này trước khi fallback tự tìm. Item layout chỉ cần
là một layout hiển thị theo ý partner; không cần khai báo ID title, image hay selected. Nếu có
TextView, SDK luôn gán tiêu đề option vào TextView đầu tiên ở mỗi lần bind, kể cả ViewHolder tái sử dụng; ImageView đầu tiên nhận ảnh option nếu được cung cấp. Dùng `tools:text` để preview, không dùng nhãn XML cố định cho dữ liệu option. Styling chọn dùng selector và `duplicateParentState="true"` trên view con.

Ví dụ config bật flow và cung cấp dữ liệu option:

```kotlin
privacyGoalsScreen = PrivacyGoalsScreenConfig(
    enabled = true,
    goal = GoalsScreenConfig(
        options = listOf(
            GoalOption("work", title = "Work", imageRes = R.drawable.partner_work),
            GoalOption("study", title = "Study", imageRes = R.drawable.partner_study),
            GoalOption("personal", title = "Personal", imageRes = R.drawable.partner_personal),
        ),
        selectionMode = SelectionMode.MULTIPLE,
        minSelection = 1,
    ),
)
```

Trong `ad_config.json`, thêm hai placement; `ad_config_debug.json` chỉ cần `native_select` và `native_select_alt`, mỗi key một tầng test trong `ids`:

```json
"native_select": {
  "ids": [{ "id": "...high", "isEnable": true }, { "id": "...all-price", "isEnable": true }],
  "isEnable": true,
  "click_action": "reload"
},
"native_select_alt": {
  "ids": [{ "id": "...high", "isEnable": true }, { "id": "...all-price", "isEnable": true }],
  "isEnable": true,
  "click_action": "reload"
}
```

`native_select` là ad đầu tiên. Khi consent hoặc lựa chọn đầu tiên xảy
ra, `native_select_alt` thay thế nó. Hai placement này dùng layout native
4:3 media-left của SDK (`ob_layout_native_media_left.xml`, template `2`), cùng frame cho preload và
show, trừ khi `templateId` của key chọn template khác; `components` không đổi khung này. Ad đầu
tiên được giữ nguyên đến khi ad ALT bind thành công; ALT không fill thì ad đầu tiên vẫn giữ.
Click ad dùng action `reload` mặc định; đổi bằng `click_action` trên `native_select`/`native_select_alt` (áp cho mọi tầng trong `ids`).

Nếu `enabled = false`, SDK không inflate các layout nên partner không cần khai báo chúng. Logic
consent, chọn/bỏ chọn, preload, swap ad, reload khi click và hoàn tất flow vẫn do SDK xử lý.

## Thời điểm, điều hướng và dữ liệu

Native Privacy đầu được preload khi tới trang cuối trong `onboarding.order`, kể cả fullscreen. Khi được bật, interstitial cuối OB vẫn theo `onboarding.exit_interstitial.next_screen_timing`: `UNDER_AD` mở Privacy bên dưới interstitial; `AFTER_AD` chờ ad đóng. Entry notification/widget/uninstall vẫn chờ ad đóng ở nhánh exit này.

Privacy chỉ cho tiếp tục sau khi đồng ý. Goal dùng `SelectionMode.SINGLE` hoặc `MULTIPLE` và `minSelection` của host; đặt minimum phù hợp số option (SINGLE dùng 1). Hoàn tất Goal ghi lựa chọn thành `GoalAnswer` (phát qua `OnboardingEvent.GoalsSelected`, có trong `OnboardingOutcome.Completed.goals` và đọc lại bằng `OnboardingSdk.selectedGoals()`) trước khi hoàn tất flow. Cụm Privacy/Goal hoàn tất trực tiếp, không mở tiếp OB5/paywall của nhánh exit thông thường. Back tại Goal quay về Privacy; Back tại Privacy đóng task. Lựa chọn Goal được chọn lại khi màn Goal được tạo lại; không phải tiến trình đã lưu qua process death.

## Khi chạy SHARED_POOL

Khi [`native_flow.type`](remote-settings.vi.md#kiểu-native-flow) là `SHARED_POOL`, Privacy và Goal là các chặng cuối của màn onboarding dùng pool ([Native flow SHARED_POOL](ads-onboarding-integration.vi.md#native-flow-shared_pool)). `privacy_goals_screen.enabled` (mặc định tắt), `goal.options`, contract layout ở trên và hai key `native_select` / `native_select_alt` giữ nguyên. Cả hai màn theo cùng một mẫu màn chọn:

| | Privacy | Goal |
| --- | --- | --- |
| Khi mở | Request `native_select` dạng load POLITE (bị bỏ qua khi pool đang có request khác chạy) và trở thành owner của pool | Như Privacy |
| Lựa chọn | Mỗi lần đổi consent, tick hay bỏ tick | Mỗi lần tap option |
| Mỗi lần chọn | Request `native_select_alt` dạng load FORCED (không bị bỏ qua khi pool đang có request khác chạy; khi `native_select_alt` đã giữ ad hoặc đang load thì các lần chọn sau không request gì) và chuyển owner sang nó | Như Privacy |
| Nút đi tiếp | Continue ẩn tới `native_flow.pool.select.next_reveal_delay_ms` (3000 ms) sau lần đổi consent đầu, sau đó chỉ dùng được khi consent đang được tick | Next ẩn tới cùng khoảng trễ sau lần tap đầu, sau đó dùng được khi đã chọn ít nhất `minSelection` option |
| Khi đi tiếp | Preload ad đầu của Goal dạng load POLITE rồi mở Goal | Ghi goal như ở trên, hiện interstitial splash còn lại khi `native_flow.pool.goals.exit_reuse_splash_inter` là `true`, rồi hoàn tất onboarding, không có paywall |
| Back | Không làm gì | Không làm gì |

Mỗi lần màn trở lại phía trước, màn lấy lại quyền owner của pool, refresh ad mình sở hữu trong khi ad đó vẫn hiện, và lặp lại request POLITE cho placement xin gần nhất. Tới khi ad của chính màn fill, slot hiện lựa chọn của pool: ưu tiên ad mới nhất chưa màn nào hiển thị, nếu không thì ad được hiển thị gần nhất, và shimmer khi có request đang chạy. Click native không reload cũng không chuyển màn (`click_action` bị bỏ qua). Ad khi quay lại app có thể mở đè lên cả hai màn (`native_flow.pool.resume_on_goals`, mặc định `true`). Không có interstitial cuối pager trước Privacy, và ad đầu của Privacy không được preload ở trang cuối pager: Privacy request nó khi mở. Privacy và Goal request riêng nhưng dùng chung ID của `native_select` / `native_select_alt`, nên doanh thu của các ID đó được báo dưới màn nào request gần nhất.
