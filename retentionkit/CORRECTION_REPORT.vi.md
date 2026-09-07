# RetentionKit — chỉnh theo tài liệu Noti chung

Trạng thái: đang triển khai và kiểm thử; chưa phải biên bản nghiệm thu. Mốc trước sửa: `543de03`, nhánh giao: `codex/retentionkit`.

## Lỗi đã xác định

Example trước đây có hai nhánh bỏ qua luồng yêu cầu: adapter dùng `intentWithoutSplashAds`, còn Splash chuyển thẳng đến tính năng khi setup đã xong. Sau onboarding, example cũng bỏ qua Main; màn tính năng không có native ad. QA dùng router riêng đi thẳng đến tính năng, nên các test cũ không chứng minh được luồng Splash đầy đủ. Danh mục và nhiều fixture mang nội dung dịch ngôn ngữ khiến mẫu tích hợp trông như một app Translate.

## Hành vi cần đạt

| Điểm vào | Chuỗi chuẩn | Phần partner quyết định |
|---|---|---|
| Notification body/CTA, widget, feature shortcut | Splash → entry interstitial kết thúc hoặc được SDK Ads bỏ qua hợp lệ → Main → tính năng đã chọn | Nội dung, icon/ảnh, destination, native placement |
| Uninstall/feedback entry | Splash → entry interstitial kết thúc/bỏ qua hợp lệ → survey SDK có native | Nhãn/lý do/màu, custom View nếu cần |
| Thử tính năng từ survey | Quay qua Splash rồi đến đúng tính năng | Danh mục tính năng gợi ý |
| X, Later, swipe notification | Dismiss và cập nhật trạng thái; không mở Activity | Nhãn được bản địa hóa |
| Rate tự động | Business success thật → threshold/cooldown/cap → Play review | Sự kiện thành công, cấu hình tần suất |

SDK dùng một envelope/token xuyên suốt; warm entry cũng chạy Splash. Chỉ entry đang được chọn được tiếp tục, không tự mở một entry cũ còn trong hàng đợi. OnboardKit và Ads giữ quyền xử lý consent, Billing, giới hạn quảng cáo, entry key và callback; RetentionKit không tạo thêm bộ tải quảng cáo/config/quyền thứ hai.

Example đổi sang Notes, Saved items, Text tools và Guide. Đây là các thao tác offline thật để minh họa success event và destination; nội dung cũ được di chuyển có kiểm soát, không xóa dữ liệu app. Các thông số debug chỉ đổi điều kiện/thời gian test; đường đi qua Activity phải dùng cùng cấu hình tích hợp chuẩn.

## Cách đối chiếu tài liệu

Nguồn chính là [Test Plan](</Users/Shared/Panacea/Documents/Noti/Notification System Implementation & Test Plan.docx>), [đặc tả lockscreen](</Users/Shared/Panacea/Documents/Noti/Notification System Implementation & Test Plan/Images_attachments/260817_-_MO_-_Lockscreen_Noti_Logic_Spec_EN_(1).docx>), [Noti.pdf](</Users/Shared/Panacea/Documents/Noti/Notification System Implementation & Test Plan/Images_attachments/Noti.pdf>) và [Notification Guide](</Users/Shared/Panacea/Documents/Noti/Notification System Implementation & Test Plan/Images_attachments/NOTIFICATION_GUIDE.pdf>). Reference code Translate được đọc ở `63a2958`.

Thứ tự xử lý mâu thuẫn: yêu cầu mới của người dùng → Test Plan chung → đặc tả lockscreen cho chi tiết riêng → thông số/template PDF chưa được quy định ở trên → reference code → giá trị SDK tự chọn được ghi rõ. Không đưa các tên Camera/Translate/PDF trong ví dụ nội dung thành yêu cầu của mọi partner.

| Nội dung | Quy tắc đối chiếu |
|---|---|
| Lịch local | Daily 08:00/19:00; Winback 11:00/14:00; Lockscreen 11:30/17:00/20:00, có lịch cohort mới riêng |
| Thông báo chưa xử lý | Lockscreen không tự mất vì delivery TTL; `replace=false` giữ và bỏ qua slot tiếp theo, kể cả sang ngày; `true` thay thế |
| Chống spam | Shared guard, priority, một thông báo mỗi family, budget lưu bền; Winback common tối đa 3 lần toàn campaign |
| Winback dormancy | 14–45 ngày từ Noti.pdf; 48 giờ của bản SDK trước là lựa chọn cũ, không phải điều kiện đã xác minh trong Translate |
| Daily sound | Tài liệu mâu thuẫn; chọn channel DEFAULT nhưng nội dung yên lặng theo Guide. Không tuyên bố đồng thời silent và heads-up |
| Setup/subscriber | Không marketing khi còn setup trong ngày đầu; subscriber và entitlement chưa xác định đều bị chặn |
| Giá trị reference | Cohort mới 2 ngày, xác nhận background 3 giây, Reminder 15 phút, Review 5 success/10 ngày/tối đa 3 lần |
| Giá trị SDK chọn | Grace sau setup, thời hạn click token và độ dài shared guard phải được phân biệt với số do tài liệu quy định |

## Giới hạn cần báo đúng

Đặc tả lockscreen yêu cầu dev kiểm tra tính khả thi của bật màn hình 20 giây. `ACQUIRE_CAUSES_WAKEUP` đã deprecated và API mới có điều kiện quyền; `TURN_SCREEN_ON` dành cho home automation và không phải quyền thông thường của mọi app. Notification được gửi không chứng minh màn hình sáng; mẫu không dùng Activity mở ngầm để giả đạt yêu cầu này. [PowerManager](https://developer.android.com/reference/android/os/PowerManager.html), [quyền TURN_SCREEN_ON](https://developer.android.com/reference/android/Manifest.permission#TURN_SCREEN_ON), [khai báo quyền AOSP](https://android.googlesource.com/platform/frameworks/base/+/main/core/res/AndroidManifest.xml).

FGS tiến độ tác vụ và hướng dẫn battery trong tài liệu thuộc tác vụ thật của host, ví dụ dọn dẹp/chuyển đổi file; chúng không phải một campaign giữ chân người dùng. SDK không khởi chạy tác vụ giả để giữ notification sống. Force-stop, thời điểm alarm trên từng OEM, việc Play có hiện thẻ review và gỡ app thực sự đều phải được báo theo bằng chứng riêng.

## Kiểm thử của đợt sửa

| Nhóm | Bằng chứng cần có | Trạng thái |
|---|---|---|
| SDK entry/UI | Exact token, Main resumed, lifecycle/rapid tap, prompt và explicit entry không tranh quyền | Chờ test bản sửa |
| Notification | Profile/RC, shared guard/priority, cap bền, giữ lockscreen xuyên ngày, content/locale | Chờ test bản sửa |
| Example | Dữ liệu generic/migration, business success, cùng router trong debug/production | Chờ test bản sửa |
| Pixel 5 Android 14 | Notification/widget/shortcut/uninstall cold/warm; thực sự qua Splash; native/inter callback; quyền và recovery | Chờ APK bản sửa |
| Packaging | Unit/regression, full example R8 và consumer selective/Maven bị ảnh hưởng | Chờ source hợp nhất |
| Review | Hai lượt độc lập Standards/Spec; sửa tất cả finding có căn cứ | Chờ triển khai |

Mọi số test và bằng chứng trước đợt sửa chỉ là lịch sử. Nghiệm thu đợt này phải gắn với commit và hash APK mới. Device baseline đã ghi nhận APK cũ `dce485fb…`, model Pixel 5/API34, cùng các setting cần khôi phục. Không dùng hướng dẫn “clear data” trong tài liệu như quyền xóa dữ liệu thiết bị hiện tại.
