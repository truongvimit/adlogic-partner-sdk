# Màn Welcome Back

[English](welcome-back-screen.md) · [Tiếng Việt](welcome-back-screen.vi.md) · [हिन्दी](welcome-back-screen.hi.md)

Welcome Back là màn user cũ thấy sau splash mỗi lần mở app từ launcher: Splash (`inter_splash_o`) → Welcome Back → app. User chọn 1 trong 4 mục tiêu rồi Continue để vào app. Màn không bao giờ mở cho user mới hay cho lần vào từ notification, widget, uninstall; các đường đó giữ nguyên.

SDK mặc định ẩn. Bật trong asset app hoặc remote `onboarding_config`:

```json
{ "welcome_back": { "enabled": true } }
```

Khi tắt, cả hai native Welcome Back không được preload. Lần mở từ launcher của user cũ vào thẳng app, mở dưới ad splash, với `OnboardingOutcome.Skipped(ALREADY_COMPLETED)`.

SDK có sẵn 4 mục tiêu PDF (Edit PDF, Add text, Sign & fill, Split & merge). Thay bằng của app:

```kotlin
welcomeBackScreen = WelcomeBackScreenConfig(
    options = listOf(
        GoalOption("scan", titleRes = R.string.goal_scan, imageRes = R.drawable.ic_goal_scan),
        GoalOption("read", titleRes = R.string.goal_read, imageRes = R.drawable.ic_goal_read),
    ),
)
```

Danh sách rỗng thì màn tắt. Luôn chọn đơn.

## Hợp đồng layout

Muốn đổi giao diện, override các resource cùng tên trong app:

- `res/layout/ob_welcome_back_screen.xml`
- `res/layout/ob_welcome_back_option.xml`

Giữ `ob_welcome_back_options` là `RecyclerView`, `ob_welcome_back_continue` là nút hành động và `ob_welcome_back_ad` là `FrameLayout` nằm trong một block cha riêng. Root của item nhận click và `isSelected`; `TextView` đầu tiên nhận tiêu đề, `ImageView` đầu tiên nhận ảnh.

## Ads và kết thúc

Khai trong `ad_config.json` (id production), cùng dạng với các vị trí native khác; `ad_config_debug.json` chỉ cần `native_welcome1` và `native_welcome2`, mỗi key một tầng test trong `ids`:

```json
{
  "native_welcome1": {
    "ids": [{ "id": "HIGH_NATIVE_UNIT", "isEnable": true }, { "id": "BASE_NATIVE_UNIT", "isEnable": true }],
    "isEnable": true,
    "click_action": "reload"
  },
  "native_welcome2": {
    "ids": [{ "id": "ALT_HIGH_NATIVE_UNIT", "isEnable": true }, { "id": "ALT_BASE_NATIVE_UNIT", "isEnable": true }],
    "isEnable": true,
    "click_action": "reload"
  }
}
```

`native_welcome1` được splash preload cùng thời điểm và cùng mode với LFO1 (`splash.load.lfo1_preload_mode`). `native_welcome2` preload khi Welcome Back mở và thay ad đầu ở lần tap đầu tiên; ad thứ hai không fill thì giữ ad đầu. Cả hai dùng `templateId` và `components` của key tương ứng trong `ad_remote_config`, giống LFO. `isEnable` của mỗi key bật/tắt cả waterfall `ids`; màn vẫn hiện.

Với timing mặc định, màn mở sau khi inter splash đóng. Continue ghi lựa chọn thành `GoalAnswer` (phát qua `OnboardingEvent.GoalsSelected`, đọc lại sau bằng `OnboardingSdk.selectedGoals()`) và trả `OnboardingOutcome.Skipped(ALREADY_COMPLETED)` kèm passthrough của lần mở. Không đánh dấu hoàn tất gì và không bắn `FlowCompleted` / `fo_flow_complete`, nên màn hiện lại ở lần mở từ launcher kế tiếp. Back đóng app.
