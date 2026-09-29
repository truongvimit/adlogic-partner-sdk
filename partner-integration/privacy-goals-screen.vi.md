# Privacy → Goals flow

[English](privacy-goals-screen.md) · [Tiếng Việt](privacy-goals-screen.vi.md) · [हिन्दी](privacy-goals-screen.hi.md)

Flow chuẩn vẫn là `Splash → LFO → OB → inter_after_ob3 → Privacy → Goal`. Privacy/Goal là phần
cuối của cùng onboarding flow, không phải một flow độc lập. SDK chỉ mở cụm này cho một flow chưa
hoàn tất và chỉ khi partner bật cờ riêng trong
`onboarding_config.json` (asset hoặc remote):

```json
{ "privacy_goals_screen": { "enabled": true } }
```

Mặc định là `false`. Code cũng bật được bằng `PrivacyGoalsScreenConfig(enabled = true)` (ví dụ
bên dưới); remote rồi asset app thắng giá trị trong code. Đặt `false` để bỏ cả cụm màn; khi đó
host tiếp tục nhánh hoàn tất thông thường. Cụm màn còn cần danh sách lựa chọn trong code:
`goal.options`, hoặc `question.options` khi không khai goal. Không có cả hai thì cờ bật bị bỏ qua
(logcat ghi cảnh báo), SDK không preload native Privacy và onboarding tiếp tục nhánh hoàn tất thông thường.
Nếu user đóng app khi đang ở Privacy hoặc Goal, state flow vẫn chưa completed và lần mở sau sẽ
chạy lại từ Splash → LFO.
Chỉ khi user bấm hoàn tất ở Goal SDK mới ghi nhận onboarding completed.
`native_select.isEnable = false` chỉ ẩn ad đầu; `native_select_alt.isEnable = false` chỉ tắt ad ALT.
Đây là nguồn bật/tắt duy nhất cho từng placement; không có `privacyAd.enabled` hay
`goalAd.enabled` riêng. Các gate consent, premium, UA và master ads vẫn áp dụng qua cùng AdsGuard
như LFO. Base key tắt sẽ tắt cả waterfall dù `_high` vẫn bật.

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
TextView/ImageView trống, SDK sẽ bind dữ liệu option vào đó; nếu không có, UI vẫn hoạt động bình
thường và trạng thái chọn được phát qua root item.

Ví dụ config bật flow và cung cấp dữ liệu option:

```kotlin
privacyGoalsScreen = PrivacyGoalsScreenConfig(
    enabled = true,
    goal = GoalsScreenConfig(
        options = listOf(
            QuestionOption("work", title = "Work", imageRes = R.drawable.partner_work),
            QuestionOption("study", title = "Study", imageRes = R.drawable.partner_study),
            QuestionOption("personal", title = "Personal", imageRes = R.drawable.partner_personal),
        ),
        selectionMode = SelectionMode.MULTIPLE,
        minSelection = 1,
    ),
)
```

Trong `ad_config_debug.json` và `ad_config.json`, thêm bốn placement (mặc định bật):

```json
"native_select_high": { "id": "...", "isEnable": true },
"native_select": { "id": "...", "isEnable": true, "click_action": "reload" },
"native_select_alt_high": { "id": "...", "isEnable": true },
"native_select_alt": { "id": "...", "isEnable": true, "click_action": "reload" }
```

`native_select` và `native_select_high` là ad đầu tiên. Khi consent hoặc lựa chọn đầu tiên xảy
ra, `native_select_alt`/`native_select_alt_high` thay thế nó. Bốn placement này dùng layout native
4:3 media-left của SDK (`ob_layout_native_media_left.xml`), cùng frame cho preload và show. Ad đầu
tiên được giữ nguyên đến khi ad ALT bind thành công; ALT không fill thì ad đầu tiên vẫn giữ.
Click ad dùng action `reload` mặc định; đổi bằng `click_action` trên `native_select`/`native_select_alt` (không đọc ở key `_high`).

Nếu `enabled = false`, SDK không inflate các layout nên partner không cần khai báo chúng. Logic
consent, chọn/bỏ chọn, preload, swap ad, reload khi click và hoàn tất flow vẫn do SDK xử lý.

## Thời điểm, điều hướng và dữ liệu

Native Privacy đầu được preload khi tới trang cuối trong `onboarding.order`, kể cả fullscreen. Interstitial cuối OB vẫn theo `onboarding.exit_interstitial.next_screen_timing`: `UNDER_AD` mở Privacy bên dưới interstitial; `AFTER_AD` chờ ad đóng. Entry notification/widget/uninstall vẫn chờ ad đóng ở nhánh exit này.

Privacy chỉ cho tiếp tục sau khi đồng ý. Goal dùng `SelectionMode.SINGLE` hoặc `MULTIPLE` và `minSelection` của host; đặt minimum phù hợp số option (SINGLE dùng 1). Hoàn tất Goal lưu `QuestionAnswer` và phát `OnboardingEvent.QuestionAnswered` trước khi hoàn tất flow. Cụm Privacy/Goal hoàn tất trực tiếp, không mở tiếp OB5/question/paywall của nhánh exit thông thường. Back tại Goal quay về Privacy; Back tại Privacy đóng task. Lựa chọn Goal được chọn lại khi màn Goal được tạo lại; không phải tiến trình đã lưu qua process death.
