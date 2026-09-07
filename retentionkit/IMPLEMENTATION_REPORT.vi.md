# RetentionKit — Báo cáo bàn giao triển khai

**Ngày 07/09/2026 · Nhánh `codex/retentionkit` · SDK `bf68f1e`, app mẫu `2ff967a`.**

Đã hoàn tất mã SDK, app mẫu, sửa các lỗi tìm qua review/ADB và các kiểm tra trong phạm vi khả dụng. Hai phần còn lại được ghi rõ: nghiệm thu UI đầy đủ khi Pixel5 được dùng riêng và tạo PR bằng tài khoản GitHub có quyền ghi. Pixel đã mở khóa và ghi nhận15/15 ca Android tại4356938; thao tác Home/Back/mở app đồng thời đã làm gián đoạn phần manual còn lại. Xem [báo cáo physical hiện tại](../.scratch/retentionkit/physical-acceptance.md). Các số liệu dưới đây là checkpoint trước đợt này; bản7cb23c3 có360 test chạy mới và562 test trong phạm vi tổng hợp, tách rõ với APK4356938 đang trên máy.

RetentionKit đã được triển khai thành **một bộ SDK dùng chung, có core và các module chọn riêng**, kèm app ví dụ hoạt động thực tế. Partner có thể dùng toàn bộ qua một lần cài đặt hoặc chỉ lấy chức năng cần thiết. Phần lặp lại — lịch, điều kiện hiển thị, lưu trạng thái, chống trùng, điều hướng và phối hợp UI — nằm trong SDK; nội dung, tính năng đích và sự kiện nghiệp vụ vẫn thuộc app.

Bằng chứng unit/Robolectric cuối có **549/549 test đạt**, không lỗi hoặc bỏ qua: 532 ca trên cây thư viện không đổi tại `bf68f1e`, cộng 17 ca app chạy mới tại cây bằng `2ff967a`. App debug/test APK và full release R8 đều build thành công. Bản app cuối đạt **15/15 ca Android trên emulator API36**; sáu artifact được kiểm qua **12/12 consumer R8** dùng project hoặc Maven POM thật.

## 1. Cấu trúc đã bàn giao

| Artifact | Trách nhiệm | Khi partner chọn riêng |
|---|---|---|
| `retention-core` | Catalogue đa ngôn ngữ, trạng thái, cấu hình, token điều hướng, vòng đời, điều phối UI và diagnostics. | Nền tảng dùng chung; không kéo vendor ads/billing/Firebase. |
| `retention-notifications` | Bảy chiến dịch, template, lịch inexact, receivers, chống trùng, quota và rotation. | Core + notifications. |
| `retention-widgets` | Widget lưới tính năng, pin, nhiều instance, resize/restore và feature shortcuts. | Core + widgets. |
| `retention-feedback` | Feedback tùy chọn lý do, Keep, gợi ý tính năng và chuyển App Info. | Core + feedback; tách khỏi review. |
| `retention-review` | Play In-App Review theo thành công nghiệp vụ; Rate mở Store. | Core + review + Play Review. |
| `retentionkit` | Facade cài đặt một lần, kết nối toàn bộ module và các adapter chọn dùng. | Bao gồm tất cả artifact trên. |

**Tắt module bằng `null` trong facade chỉ tắt hành vi; muốn giảm dependency phải chọn artifact riêng.** Đã kiểm bằng cả project dependency và Maven POM thực: consumer review không kéo notifications/widgets/ads/Firebase/Compose; standalone umbrella cũng không bắt buộc bộ ads, OnboardKit hay Billing. Manifest chỉ có quyền notification/boot ở notifications và umbrella, ngoài quyền bảo vệ receiver do AndroidX sinh ra.

Các thư viện đã có AAR/POM và đăng ký publication theo Gradle/JitPack hiện hữu. **Hiện chưa phát hành RetentionKit từ xa**; bản SDK 5.1.1 cũ không chứa những artifact này. Cấu hình đã kiểm là minSdk24, compileSdk36, JVM17. Xem [hướng dẫn partner](/Users/Shared/AndroidProject/Example-AdLogic-Partner-main/retentionkit/README.md) và [ma trận đóng gói](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-consumers-20260907-bf68f1e/matrix-summary.json).

## 2. Flow và mặc định chung

Marketing chỉ chạy khi bật, có nội dung/đích hợp lệ, quyền và channel cho phép, biết chắc người dùng không đăng ký trả phí, đúng trạng thái ứng dụng; đồng thời đạt cooldown, hạn mức và TTL. `UNKNOWN` chặn marketing. Marketing thông thường chờ **24 giờ sau hoàn tất setup**. Các notification chức năng do app sở hữu không bị SDK hủy toàn bộ.

| Flow | Mặc định | TTL / cooldown / tối đa mỗi ngày |
|---|---|---|
| Daily | 08:00 và 19:00 theo giờ địa phương, khi background. | 1 giờ / 1 giờ / 2 |
| Winback | 11:00 và 14:00, không hoạt động ít nhất 48 giờ. | 1 giờ / 12 giờ / 1 |
| Bỏ dở onboarding | Onboarding đang chạy, chưa hoàn tất; background được xác nhận rồi đợi 3 giây. | 5 phút / 24 giờ / 1 |
| Quay lại sau click quảng cáo | Click mới trong foreground; background rồi đợi 3 giây. | 5 phút / 15 phút / 2 |
| Reminder | Refresh foreground, im lặng, có Later. | 6 giờ / 15 phút / 4 |
| Pinned | Các ô tính năng riêng biệt; bỏ qua khi notification hiện tại còn tồn tại. | 6 giờ / 15 phút / 2 |
| Lockscreen | 11:30, 17:00, 20:00; mặc định bỏ qua nếu còn notification, có tùy chọn replace. | 1 giờ / 1 giờ / 3 |

Onboarding là **ngoại lệ có chủ đích**: grace mặc định bằng 0, yêu cầu active unfinished; không bị điều kiện “setup đã xong” chặn. Hoàn tất/abort/foreground hay chuyển UI hệ thống sẽ hủy callback đang chờ. Token click hết hạn sau 5 phút; timer ngắn không được mang sang process mới. Cohort người dùng mới mặc định là hai ngày đã trôi qua từ cài đặt.

Lịch giữ revision và PendingIntent ổn định; reconcile khi khởi động lại, reboot, cập nhật app, đổi giờ/múi giờ. Callback cũ không khôi phục campaign đã tắt; slot hết hạn không phát bù hàng loạt. Lưu quota/claim trước `notify`, chỉ ghi rotation thành công khi xác nhận được bước tương ứng. Template hỗ trợ tài nguyên local và renderer riêng, vẫn dùng action identity do SDK cấp. [Chi tiết defaults và contract](/Users/Shared/AndroidProject/Example-AdLogic-Partner-main/retention-notifications/README.md).

Widget có template đa ngôn ngữ, pin invitation, callback kiểm chứng instance, cấu hình độc lập và cập nhật theo locale. Feedback cho phép Continue mà không chọn lý do; người dùng có thể Keep hoặc thử tính năng thực. Review mặc định **5 thành công nghiệp vụ / cách 10 ngày / tối đa 3 lần thử**; lần thử được tính tại bước reserve trước gọi launch, không phải khi người dùng đánh giá. Feedback và review giữ state riêng.

## 3. Partner cần cung cấp gì?

1. **Nội dung và catalogue:** ID tính năng ổn định, nhãn/icon theo ngôn ngữ app, đích thực tế; copy và hình local nếu muốn thay template.
2. **Một Activity đầu vào:** cài trong `Application.onCreate`; capture ở `onCreate` và `onNewIntent`, chuyển tiếp extras đã được chuẩn hóa qua setup.
3. **Trạng thái đáng tin:** setup/onboarding, entitlement và chuyển UI hệ thống. App không có IAP có thể khai báo non-subscriber; app có IAP giữ Unknown đến khi xác minh.
4. **Thành công nghiệp vụ:** gọi `businessSuccess(featureId, operationId)` sau thao tác thành công; giữ cùng ID khi phát lại. Mở màn hình không được tính là thành công.
5. **Chủ sở hữu permission và UI:** app/OnboardKit xin quyền một lần, báo `permissionChanged`; cung cấp gate cho dialog/paywall đặc thù.

Ở màn hình cuối đã resumed, `dispatchPending(token)` mới trả đích được phép mở. `Unavailable` giữ token để thử lại; `SdkHandled` không đồng nghĩa đã tiêu thụ hoặc UI đã hiện. Ví dụ có retry hữu hạn 5 giây trong trạng thái resumed, xử lý trường hợp màn hình nguồn đóng scope muộn hơn callback resume của đích.

Config remote dùng document version1 với `overrides` và `removeKeys`: key thiếu giữ defaults/cache, xóa phải rõ ràng; profile được kiểm tra trước khi lưu. Cài đặt không đợi mạng. Renderer tùy chỉnh chỉ thay cách trình bày, không thay state machine. [Core contract](/Users/Shared/AndroidProject/Example-AdLogic-Partner-main/retention-core/CONTRACT.md).

## 4. Khớp với bộ SDK hiện có

`OnboardRetentionBridge` dùng luồng entry của OnboardKit, giữ consent, permission và setup, hỗ trợ route không có splash banner/interstitial hay checkpoint SPLASH_INTER. Quảng cáo ở bước onboarding khác vẫn theo chính sách host. API `SplashEntry` cũ tiếp tục tương thích.

Bridge phối hợp suppression theo từng owner/token, không thay cờ resume toàn cục hoặc listener của app. Click ads thực được chuyển đồng bộ từ điểm click chung; partner không forward thêm lần nữa. `BillingRetentionBridge` đọc entitlement có chứng cứ của BillingKit, không suy ra free từ cached `isPremium=false` hoặc `awaitReady`. Trackkit dùng sink đã cài; Firebase dùng client config chung, không tạo fetcher mặc định thứ hai.

## 5. App ví dụ và cách tiếp nhận

Trong Ad Showcase hiện hữu, chọn **Everyday tools / Công cụ hằng ngày**. Bốn đích có chức năng thật: 12 câu du lịch EN↔VI, lưu/xóa câu đã thích, chuẩn hóa văn bản và đếm từ/ký tự Unicode, đọc tài liệu kèm thời gian đọc ước tính.

Các điểm tích hợp cần đọc:

- [RetentionExample](/Users/Shared/AndroidProject/Example-AdLogic-Partner-main/app/src/main/java/com/itg/template/retention/RetentionExample.kt): cài facade/adapters, capture, kết quả setup và outbox.
- [RetentionPlaygroundActivity](/Users/Shared/AndroidProject/Example-AdLogic-Partner-main/app/src/main/java/com/itg/template/retention/RetentionPlaygroundActivity.kt): UI EN/VI, đích thực, cold/warm/pending routing và nút widget/feedback/rate.
- [Hướng dẫn app](/Users/Shared/AndroidProject/Example-AdLogic-Partner-main/app/RETENTION_EXAMPLE.md): khôi phục dữ liệu, QA và giới hạn bằng chứng.
- [Consumer độc lập](/Users/Shared/AndroidProject/Example-AdLogic-Partner-main/sample-retention-only/README.md): sáu profile và hai nguồn dependency, không phụ thuộc app demo đầy đủ.

App lưu kết quả cùng operation ID trước khi phát sự kiện; outbox chỉ xác nhận sau subscriber của app nhận được dispatch sau commit core. Boolean từ `signal` chỉ có nghĩa được nhận vào hàng đợi, không chứng minh mọi module đã lưu xong. Release dùng clock/store/defaults thật. QA chỉ có trong debug, phải kích hoạt rõ ràng; thoát QA khôi phục runtime chuẩn. Không có receiver thử nghiệm export trong release.

**Migration đề xuất:** kiểm kê owner/ID/state cũ → nối catalogue và routing → chọn artifact → tắt scheduler/prompt cũ tương ứng → bật dần campaign qua config → kiểm lại permission, subscriber, restart và mọi CTA. Chỉ hủy alarm/shortcut ID đã biết; giữ widget provider cũ hoạt động cho instance đã có. Counter review và widget binding không tự chuyển sang hệ thống mới. Giữ channel ID có cùng ý nghĩa; không xóa/tạo lại channel để vượt lựa chọn chặn của người dùng.

## 6. Bằng chứng nghiệm thu

| Kiểm tra | Kết quả cuối | Bằng chứng |
|---|---|---|
| Unit/Robolectric | **549/549**, không lỗi/bỏ qua;532 phần không đổi +17 app chạy mới. | [JUnit và đối chiếu source](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-app-20260907-2ff967a/combined-product-tests.json). Không cộng14 app cũ lần nữa. |
| Android instrumentation | **15/15 PASS**, API36 emulator, sau bản sửa routing cuối. | [JUnit thực](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/example-api36-run-6-final-verification.json); source bằng `2ff967a`, không đổi khi chạy. |
| Đóng gói | **12/12 R8**,12 graph/composition,6 publication tại SDK `bf68f1e`. | [Ma trận project/POM-only Maven](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-consumers-20260907-bf68f1e/matrix-summary.json); không thay bằng project ngầm. |
| App và hồi quy tích hợp | Debug/test/full release R8 PASS; sample-paywall-only debug PASS. | [APK và hash cuối](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-app-20260907-2ff967a/artifacts.json). Chỉ loại task upload Crashlytics mapping. |
| Consumer Maven đã minify | Cold launch → setup → feedback rescue → đếm4 từ; Store/Back/Keep PASS trên API36. | [Kết quả UI thực](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/example-api36-manual/final-bf68-maven-umbrella-verdict.json). |
| Pixel5 API34 | Đã mở khóa, ghi nhận15/15 ca Android tại4356938 và các bước manual widget/feedback; chưa nghiệm thu UI đầy đủ do thao tác máy đồng thời. | [15 ca Android thực tế tại4356938](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/physical-api34-full-20260907/instrumentation-2-final-4356938/result.json); [phạm vi manual và ca chưa hoàn tất](../.scratch/retentionkit/physical-acceptance.md). |

Kiểm tra thủ công xác nhận widget pin thật, tap qua consent/language/onboarding/PayKit đến text tool, đổi VI cập nhật widget và mở document khi app đang chạy; permission deny cho 0 post, grant sau đó có post thực, Later chỉ xóa reminder. [Ảnh luồng widget chuẩn](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/example-api36-manual/example-widget-business-after-full-setup.png). Rate thủ công mở Store đúng package, Back vẫn mở được feedback; Store báo “Item not found” phù hợp với app ví dụ chưa phát hành. Các ảnh thủ công lịch sử được ghi mốc riêng trong ledger; không thay thế lần chạy sau sửa cuối.

Kiểm tra hệ điều hành API36 thực cũng PASS: [force-stop](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/example-api36-manual/force-stop-verdict.json) làm 7 alarm về 0 rồi user relaunch khôi phục 7; [đổi múi giờ](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/example-api36-manual/timezone-verdict.json) HCM→Tokyo→HCM cập nhật/khôi phục 7 lịch; [reboot emulator](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/example-api36-manual/reboot-verdict.json) khôi phục 7 alarm trước lần mở app thủ công, widget vẫn còn. Quan sát rất sớm sau boot từng là 0 khi stack app đang khởi tạo; đã giữ bằng chứng đó, không đặt SLA boot hoặc nhận đã chứng minh alarm thực sự phát đúng giờ. Lệnh và kết quả nằm trong [ledger](/Users/Shared/AndroidProject/Example-AdLogic-Partner-main/.scratch/retentionkit/verification.md).

## 7. Giới hạn và trạng thái phát hành

- **Review đã xử lý:** Standards có 2 finding/P3, Spec có 1 finding/P2. Một implementer sửa helper scope chung và kiểm lại Activity/config/session sau callback đồng bộ trước handoff; 16 regression mới ngăn launch sau khi host bị hủy hoặc campaign tắt. [Báo cáo hai trục và xử lý](/Users/Shared/Panacea/Documents/SDKOptimize/RETENTIONKIT_CODE_REVIEW.md).
- **Lỗi tìm qua ADB đã sửa:** tại lần chạy sau review, 14/15 ca đạt; ca pinned lộ lỗi hai Intent đến dồn khiến entry cũ ghi đè entry mới. Regression trên Activity thật tái hiện lỗi hai lần; bản sửa chỉ chọn token vừa nhận/khôi phục, gom callback và cấp lại retry budget cho Intent mới. Ba regression mới; test thiết bị xác nhận token đã consume và đúng đích thay vì chỉ so tiêu đề. [Bằng chứng red/green](/Users/Shared/Panacea/Documents/SDKOptimize/RETENTIONKIT_EXAMPLE_ROUTE_RACE_FIX.md).
- **Thiết bị vật lý:** Pixel5 API34 đã mở khóa. Đã chạy15/15 ca Android và một phần manual; cần máy không bị điều khiển đồng thời để hoàn tất. Bản7cb23c3 mới chưa được cài lên máy; không gán kết quả4356938 cho bản mới hoặc cho mọi OEM.
- **Android/Play:** alarm inexact có thể trễ bởi Doze/OEM; cửa sổ yêu cầu 10 phút không phải SLA. Force-stop cần tương tác người dùng để phục hồi. Không ép bật màn hình, exact alarm, full-screen marketing hay FGS thường trực. Pin request không chứng minh widget đã thêm; review hoàn tất không chứng minh thẻ hiện/rating; mở App Info không chứng minh uninstall.
- **Độ bền:** hỗ trợ main process; không cam kết transaction nhiều process. Crash giữa consume và navigate có thể mất lần mở đó; không hứa exactly-once hiển thị. QA gọi receiver bằng envelope đã lưu chứng minh xử lý engine, không chứng minh OS wake/reboot/Doze timing.
- **PR/phát hành:** SSH có thể push nhưng tài khoản `gh` chỉ READ, thao tác tạo draft PR bị từ chối vì thiếu quyền collaborator. Cần khôi phục quyền phù hợp để hoàn tất PR. Chưa có remote release/tag RetentionKit; bản QA local không dùng làm bằng chứng đã phát hành.

Bắt đầu tích hợp từ [README partner](/Users/Shared/AndroidProject/Example-AdLogic-Partner-main/retentionkit/README.md); chạy app tại **Ad Showcase → Everyday tools / Công cụ hằng ngày**. [APK debug đã test](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-app-20260907-2ff967a/artifacts/app-debug/ITG_Base_Project_v1.0.0_v100_09.07.2026-debug.apk) có SHA256 `b639397390479de43e087101d68d309af72e837f15d803ece893e112c746806c`. Mã và tài liệu nằm trên nhánh riêng; trạng thái commit/push/cleanup cuối được ghi tại [biên bản bàn giao](/Users/Shared/Panacea/Documents/SDKOptimize/RETENTIONKIT_CLOSURE.json).
