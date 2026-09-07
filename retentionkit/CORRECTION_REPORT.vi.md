# RetentionKit — báo cáo bàn giao

**SDK đã gom được các flow lặp lại thành module dùng chung, với facade cho partner tích hợp ít cấu hình.** Example đã chuyển sang nghiệp vụ generic và chạy entry qua Splash, OnboardKit, Main rồi đúng destination. Các finding review đã được sửa; **279 unit mới, 17 ca Pixel, first-open API36, 12 consumer và full example R8 đã đạt**. Release smoke đã đạt; thiết bị đã về profile mặc định và các worktree của task đã được dọn.

Root: `69d0f71ec970205902d8ca666b2876df3f938690`. Production/config/tests khớp bản kiểm thử `86048d619dfadb3371a8d7448f629d897b449d68`; khác biệt chỉ ở tài liệu. [Đối chiếu source](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/review-86048d6/merge-freeze.json).

## Thiết kế và hành vi

Sáu artifact: core, notifications, widgets, feedback, review và facade `retentionkit`. Partner chọn từng module hoặc toàn bộ; adapter Onboard/Ads/Billing/Firebase là phần tích hợp tùy chọn. Tắt module trong facade không tự loại dependency; muốn dependency nhỏ cần chọn artifact riêng.

| Flow | Hành vi chung |
|---|---|
| Notification/widget/shortcut | Cold/warm → Splash → entry interstitial kết thúc/bỏ qua hợp lệ → Main resumed → tính năng. Giữ đúng token; entry mới không làm backlog cũ tự mở. |
| Uninstall | Shortcut **Uninstall/Gỡ cài đặt**, icon thùng rác đỏ → Splash → Main → survey/native → Keep, thử tính năng hoặc xác nhận gỡ của Android. |
| Review | Success nghiệp vụ thật → threshold/cooldown/cap → Play Review; mở Store thủ công độc lập. |

Example có **Notes, Saved items, Text tools, Guide** với thao tác offline thật. Migration giữ dữ liệu/success ID cũ; alias cũ chỉ dùng để tương thích.

Đã tách TTL gửi/hiển thị notification khỏi thời hạn route: entry mới được tap hợp lệ vẫn sống qua onboarding dài, giới hạn core tối đa bảy ngày. Alarm quá hạn vẫn bị từ chối; timeout Android không đổi. Envelope đã post/stage trước sửa giữ expiry cũ. [Hợp đồng notification](/Users/Shared/AndroidProject/Example-AdLogic-Partner-main/retention-notifications/CONTRACT.md).

## Năm bước tích hợp

1. Trong Application, gọi `RetentionKit.install(..., RetentionKitOptions(...))`; cung cấp `featureProvider`, `localeProvider`, router và trạng thái entitlement có căn cứ. Xử lý `Installed`/`Failed`. [API](/Users/Shared/AndroidProject/Example-AdLogic-Partner-main/retentionkit/README.md).
2. Với OnboardKit, tạo `OnboardRetentionBridge(..., mainActivity=...)`, gắn `router`, `uiHost`, `adapters`. Capture ở Splash, lưu envelope đã materialize qua recreation; listener dùng `bridge.mainIntent(...)`. Main gắn `kit.mainHandoff(...)` trước `onStart`, chuyển tiếp `onNewIntent`/saved state. Màn cuối gọi `kit.capture` và `dispatchPending` cho token được chọn. [Entry contract](/Users/Shared/AndroidProject/Example-AdLogic-Partner-main/retentionkit/ENTRY_CONTRACT.md).
3. Dùng chung Firebase: `FirebaseRetentionConfigSource(legacyKeys=RetentionLegacyConfig.keys, legacyMapper=RetentionLegacyConfig::overrides)`. Một fetch chung; JSON override/removal ưu tiên, key vắng giữ mặc định, dữ liệu sai giữ cấu hình hợp lệ trước đó.
4. Giữ một chủ thể xin quyền, báo `permissionChanged()`; nối Billing đã xác minh và `businessSuccess(featureId, stableOperationId)`. UNKNOWN/subscriber chặn marketing. Bridge nhận ad click trực tiếp, tránh gửi lặp từ analytics.
5. Nối medium native qua Ads hiện có: `native_noti`, `native_widget`, `native_uninstall`; entry inter dùng `inter_noti`, `inter_widget`, `inter_uninstall`. Survey tùy biến bằng `FeedbackUiFactory`/controller, native bằng `FeedbackNativeContent`; `shortcutIconRes` độc lập với icon header `appIconRes`. Menu mở `feedback.openViaEntry()`. [Example](/Users/Shared/AndroidProject/Example-AdLogic-Partner-main/app/RETENTION_EXAMPLE.md), [feedback](/Users/Shared/AndroidProject/Example-AdLogic-Partner-main/retention-feedback/README.md).

## Thông số chuẩn

`COMMON_PLAN` là mặc định; `LEGACY_SDK` dành cho tương thích. Ưu tiên Test Plan chung và đặc tả lockscreen; giá trị SDK tự chọn được phân biệt với reference. [Audit nguồn](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/noti-spec-audit.md).

| Nhóm | Mặc định |
|---|---|
| Daily | 08:00/19:00 local; channel DEFAULT, yên lặng. |
| Winback | 11:00/14:00; dormant 14–45 ngày; tối đa ba reservation toàn campaign. |
| Lockscreen | 11:30/17:00/20:00; HIGH/PUBLIC; không timeout sau post; `replace=false` giữ thông báo chưa xử lý. |
| Onboarding | Còn unfinished/active, sau 24 giờ từ cài đặt, background xác nhận ba giây. |
| Ad return/App exit | Background ba giây; click đủ điều kiện ưu tiên ad-return. App exit không phải callback OS kill. |
| Reminder/Review | Reminder 15 phút; review 5 success/10 ngày/tối đa 3 attempt. |
| Chống spam | Guard 30 giây, priority/shared Updates, cap lưu bền. Guard30s và grace sau setup24h là giá trị SDK chọn. |

Calendar delivery TTL một giờ; onboarding/ad-return/app-exit năm phút. Channel, key RC, cohort và từng cap đầy đủ ở [notification guide](/Users/Shared/AndroidProject/Example-AdLogic-Partner-main/retention-notifications/README.md).

## Nghiệm thu

Review độc lập còn **0 finding Standards** tại `22c7fcb`, **0 finding Spec** sau `86048d6`. [Standards](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/review-22c7fcb/standards.md), [Spec](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/review-86048d6/spec.md). Regression RED/GREEN của selection, TTL và shortcut được lưu riêng; toàn bộ chín module đã chạy lại trên source cuối. [Evidence sửa lỗi](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/review-fixes-summary.md).

| Hạng mục | Kết quả có bằng chứng |
|---|---|
| Unit tại69d0f71 | **279/279**, một invocation `--rerun-tasks`, 393 tasks thực chạy; 0 failure/error/skip. [XML/source](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-units-69d0f71/result.json) |
| Pixel5/API34, APK860 | **17/17 thực chạy**, không hỗ trợ thao tác, hash app/test trước/sau khớp. [Runner](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/pixel-86048d6-full/result.json) |
| API36 cài mới | Config revision0, permission deny/allow thật; notification7103 → cùng token → Notes sau 9 phút 37 giây từ tap (10 phút 03 giây từ OS record). Native_noti có loaded/impression. Chỉ chỉnh giờ OS+25h trước đó để đạt grace; không tăng tốc onboarding đã nhận entry. [OS proof](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-first-install-api36.json) |
| Sáu publication QA, 12 consumer | **Đạt** toàn bộ R8/runtime graph/manifest/composition, project và POM-only Maven. Version `retentionkit-qa-20260907-69d0f71`. [Matrix](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/consumers-69d0f71/matrix-summary.json) |
| Full example release R8 | **Đạt**, 7 phút 53 giây; APK/mapping có SHA256. [Release](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/example-release-69d0f71/result.json) · [Minified OS smoke](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-release-device-smoke.json) |
| Manual Pixel | Uninstall đỏ/thùng rác → survey; rescue và ô ghim thứ tư tới Guide; Later và Đóng lockscreen hủy đúng notification; Store báo không tìm thấy app chưa phát hành, Back trở lại. Không suy diễn đã rate. [Shortcut](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/manual/093-final-shortcut-menu.png) · [Rescue](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/pixel-final-manual/actual-rescue-guide-retry.xml) |
| Release API36 | Cài update giữ data; reboot rồi mở launcher → Main; shortcut → survey; force-stop còn 0 alarm, mở lại khôi phục 7. [Minified OS smoke](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-release-device-smoke.json) |
| Khôi phục/dọn tài nguyên | **Đạt**: profile standard/revision0, hết notification QA; giữ data/widget17, khôi phục EN và settings, dừng emulator riêng, dọn bốn worktree task. [Final physical/restore proof](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-physical-manual-and-restore.json) · [Task worktree cleanup](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/task-worktree-cleanup.json) |

Unit/receiver với clock fixture không chứng minh OS wake; lifecycle/receipt chứng minh route, không chứng minh mọi ad impression. Callback/native và thao tác OS được ghi riêng. SDK không ép sáng màn hình, vượt force-stop/OEM, chặn uninstall hệ thống hoặc khẳng định đã rate/gỡ app. Local QA publication chưa phải phát hành remote; bản SDK5.1.1 hiện có không chứa RetentionKit.

Nhánh `codex/retentionkit` đã được push và commit theo từng phần. GitHub từ chối tạo draft PR với `must be a collaborator` do tài khoản API thiếu quyền ghi; nội dung PR đã chuẩn bị trong `.scratch/retentionkit/pr-body.md`. [PR attempt](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-pr-attempt.json).
