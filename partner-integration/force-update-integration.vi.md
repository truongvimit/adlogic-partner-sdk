# Force update / In-App Updates với AdLogic

## Đã khôi phục gì?

`com.ads.module.ump.ITGUpdateManager` và `IUpdateInstanceCallback` từng bị xóa ở commit `24ac902` (12/08/2026). Bản khôi phục giữ tên/package và constructor update cũ, hỗ trợ Play `IMMEDIATE`, `FLEXIBLE`, resume update dang dở và dọn listener theo lifecycle. Phần consent cũ trộn trong manager (`checkBelowGeoEEA`, `canRequestAds`, callback consent) được thay bằng `ConsentCenter` hiện có.

`com.ads.module.update.ForceUpdateGate` giữ navigation khi app quá cũ. Người dùng hủy Play hoặc quay lại từ Store khi chưa cập nhật vẫn ở gate. Đã bỏ điều kiện lỗi `needsUpdate || force` ở Main: bản đạt ngưỡng không còn bị chặn chỉ vì `force=true`.

## Một quyết định mỗi lần mở app; force update không gửi request quảng cáo

**UMP, Remote Config và billing vẫn chạy song song.** SDK không thêm fetch hoặc timeout cho update. Prompt notification được xử lý sau UMP và sau bước remote.

1. Splash giữ khóa request quảng cáo ngay từ lúc tạo attempt. SDK helpers, API load cũ, app-open loader và auto buffer đều tôn trọng khóa này.
2. Bước remote hiện có hoàn tất hoặc timeout: gọi `readForceUpdateConfig()` một lần để chốt policy đã activate.
3. Nếu `enabled=true`, `force=true` và version app thấp hơn `minVersionCode`: giữ khóa, hiện update khi consent/notification kết thúc; **không load banner/interstitial, không preload native/LFO/splash-native, không mở paywall hoặc màn tiếp theo**.
4. Nếu không bắt buộc update: mở khóa rồi mới bắt đầu requests và timer quảng cáo. Với update gợi ý (`force=false`), dialog vẫn ở ranh giới presentation, sau các bước load bình thường.
5. Snapshot và khóa thuộc `SplashAttempt` ViewModel, tồn tại qua recreate. Khóa được giải phóng khi attempt kết thúc/hủy vĩnh viễn. Không có observer/realtime listener hoặc kiểm tra remote lại giữa màn bên trong.

**Đánh đổi bắt buộc:** cả `SAME_TIME` và `ALTERNATE` phải đợi verdict remote trước request đầu tiên. Không thể vừa gửi request trong lúc chưa biết policy, vừa bảo đảm không phí request khi remote trả force update. UMP/billing/init không chuyển thành tuần tự; không tăng timeout fetch hay thêm thời gian chờ riêng. Sau khi được phép load, minimum/ad-budget vẫn bắt đầu và vận hành theo quy tắc cũ.

**Không cần mở app hai lần:** nếu fetch lần mở hiện tại nhận/activate `enabled=true`, policy đó áp dụng ngay lần này. Remote đổi sau snapshot chỉ được xét ở lần mở splash mới. Timeout chưa có remote thì snapshot tắt; remote đã activate hợp lệ trước đó vẫn là nguồn chính sách hiện có.

Khóa chỉ ngăn request mới qua AdLogic từ khi attempt bắt đầu; không thu hồi được request đã gửi ở lần chạy trước hoặc request host tự gọi trực tiếp Google SDK ngoài AdLogic. Standalone host phải đặt gate trước mọi lời gọi load/preload của mình; nếu có loader chạy nền, dùng `AdGate.holdRequests()` trong khi chờ remote và đóng token khi verdict cho phép. `ForceUpdateGate` tự giữ một khóa riêng trong suốt dialog bắt buộc, giải phóng khi scope bị hủy.

## Mặc định tắt, chỉ remote true mới bật

Tạo parameter Firebase Remote Config loại **String**, tên chính xác `force_update_config`, rồi **Publish changes**:

```json
{
  "enabled": true,
  "force": true,
  "minVersionCode": 101,
  "title": "Cập nhật ứng dụng",
  "description": "Vui lòng cập nhật phiên bản mới để tiếp tục sử dụng.",
  "storeLink": ""
}
```

| Field | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `enabled` | `false` | Công tắc bật toàn bộ tính năng. Phải là Boolean `true` rõ ràng. Thiếu field hoặc `false` thì tắt, kể cả `force=true`. |
| `force` | `false` | Khi tính năng bật: `true` bắt buộc, `false` gợi ý với nút Later/Back. |
| `minVersionCode` | `0` | Chỉ áp dụng cho versionCode thấp hơn ngưỡng; `0` tắt yêu cầu. Số nguyên không âm, không đặt trong dấu nháy. |
| `title`, `description` | `""` | Rỗng dùng strings mặc định trong SDK. |
| `storeLink` | `""` | Rỗng: thử Play In-App Updates, fallback trang Play đúng package app. Có URL: mở URL trực tiếp, không gọi In-App Updates. Chấp nhận HTTPS hoặc `market://details`; URL không hợp lệ fallback trang Play. |
| `icon` | `""` | Giữ để tương thích dashboard cũ; dialog startup SDK không tải icon URL. |

Parameter này độc lập với `ad_remote_config`, `ad_behavior_config`, `onboarding_config`.
`minVersionCode` là **versionCode app**, không phải `versionName` hay version AdLogic.

Với `enabled=true, minVersionCode=101`: bản 100 bị chặn nếu `force=true`, được bỏ qua nếu `force=false`; bản 101/102 không hiện. Thiếu `enabled` luôn tắt. Để tắt từ xa:

```json
{"enabled": false}
```

**Nguồn dữ liệu:** `FirebaseUpdateConfig.activated()` chỉ đọc giá trị có nguồn Firebase `VALUE_SOURCE_REMOTE`.

| Tình huống tại thời điểm xét gate | Hành vi |
| --- | --- |
| Chưa có remote / Firebase chưa sẵn sàng | Tắt |
| Firebase default local có `enabled=true` | Tắt; nguồn DEFAULT/STATIC không được phép bật |
| Thiếu enabled, enabled=false | Tắt |
| JSON blank, sai định dạng hoặc sai kiểu | Tắt |
| Remote hợp lệ enabled=true, bản app dưới ngưỡng | Bật gợi ý/bắt buộc theo force |
| Fetch lỗi/timeout, có remote đã activate trước đó | Đọc remote đã activate; chỉ bật nếu chính remote đó enabled=true |
| Key bị xóa và Firebase đã activate việc xóa | Tắt |

Không fallback sang asset hay SharedPreferences policy riêng để bật tính năng. Asset example để `enabled=false` chỉ là mẫu local; production adapter không đọc asset đó. Remote cache của chính Firebase vẫn là remote đã activate, không phải default local.

**Deadline và remote đến muộn:** SDK chốt snapshot ngay khi bước remote hiện có kết thúc. Nếu timeout khi chưa có remote thì snapshot tắt; kết quả activate đến sau đó không đổi quyết định update của flow đang chạy, chỉ áp dụng ở lần mở splash mới (settings và `ad_remote_config` đến muộn thì vẫn được áp dụng cho phần còn lại của phiên). Recreate cùng attempt không chốt lại. Publish vẫn chịu fetch/activate và cache interval của Firebase, không đồng nghĩa mọi thiết bị nhận rule ngay lập tức.

Example đặt interval debug=0, release=3600 giây và Firebase fetch timeout=10 giây trong Application. Thời gian splash chờ remote theo `SplashConfig.remoteFetchTimeoutMs`; không cộng thêm 3 giây cho update. API fetch standalone mặc định chờ 3 giây. Cấu hình interval của Firebase vẫn được tôn trọng; SDK không tự đặt interval, app không đặt thì Firebase dùng mặc định 12 giờ.

## Dependencies và Firebase

Dùng cùng phiên bản AdLogic có chứa thay đổi này cho mọi module. Source thay đổi chưa tự có trong tag/JitPack cũ.

```groovy
def sdkVersion = providers.gradleProperty("adlogicSdkVersion").get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
    // Chỉ nếu app dùng OnboardKit:
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
}
```

`:ads` export `com.google.android.play:app-update:2.1.0`, không cần khai báo trùng hoặc thêm `app-update-ktx`. Firebase cần đúng `applicationId`, `google-services.json`, plugin Google Services theo [Firebase integration](firebase-integration.vi.md).

## App dùng OnboardKit

Nối Firebase vào bước remote hiện có theo guide Firebase (example đã có `ObRemote.installFetchDelegate(RemoteConfigClient::fetchAndActivate)` và nguồn ads remote). Sau đó:

```kotlin
import io.onboardkit.ui.splash.ObSplashActivity
import io.suite.firebase.FirebaseUpdateConfig

class SplashActivity : ObSplashActivity() {
    override fun readForceUpdateConfig() = FirebaseUpdateConfig.activated()
}
```

`activated()` **không fetch lại**. SDK gọi hook một lần sau bước remote, giữ snapshot trong attempt và xử lý force update trước khi mở khóa request quảng cáo. Update gợi ý được hiện ở ranh giới presentation. Host không cần tự giữ Activity/dialog/listener cho update.

Không đặt fetch riêng trước UMP và không dùng getter phụ thuộc `RemoteConfigUtils.completed`. SDK tự chặn requests trước khi có verdict, host không cần tự quản lý khóa khi dùng OnboardKit. Default hook trả `ForceUpdateConfig()` (tắt); dependency hoặc Firebase setup đơn thuần không bật tính năng.

## Không dùng OnboardKit / Firebase

Với startup Activity riêng, chạy remote và consent song song rồi chờ cả hai trước gate. Nếu app đã fetch/activate thì dùng `activated()`. Nếu chưa có bước remote riêng:

```kotlin
lifecycleScope.launch {
    // Có thể chạy song song với coroutine consent của app.
    val config = FirebaseUpdateConfig.fetch(timeoutMs = 3_000)
    // Đảm bảo UI consent đã kết thúc trước khi hiện gate.
    ForceUpdateGate.await(this@StartupActivity, config)
    startActivity(Intent(this@StartupActivity, MainActivity::class.java))
    finish()
}
```

Dùng `lifecycleScope`, chỉ một gate trên mỗi Activity. Gate tự đọc versionCode qua PackageManager. Nếu có app-open ads, loại Activity startup khỏi resume ads bằng `disableAppResumeWithActivity(StartupActivity::class.java)` trong setup; gate cũng skip lần resume do update.

Không dùng Firebase: bỏ `suite-firebase`, tự fetch/cache backend và parse `ForceUpdateConfig.fromJson(json)`; dữ liệu thiếu/hỏng dùng `ForceUpdateConfig()` (tắt). Để chủ động bật từ code phải truyền rõ `enabled=true`:

```kotlin
val config = com.ads.module.update.ForceUpdateConfig(
    enabled = true,
    minVersionCode = 101L,
    force = true,
)
com.ads.module.update.ForceUpdateGate.await(this, config)
```

Đây là lựa chọn explicit của host, không phải default SDK. App example chỉ bật theo remote.

Đưa deep link/notification/entry point quan trọng qua startup barrier nếu muốn áp dụng cho toàn bộ đường vào app. Gate ở launcher không tự chặn Activity được mở trực tiếp.

## API update cũ / FLEXIBLE

Gate dùng Play **IMMEDIATE** sau khi bấm Update now, cả với gợi ý lẫn bắt buộc; gợi ý có Later để đi tiếp. Nếu muốn tải nền FLEXIBLE hoặc tự xây UI/policy, dùng manager trực tiếp:

```kotlin
private lateinit var updates: com.ads.module.ump.ITGUpdateManager

// onCreate:
updates = com.ads.module.ump.ITGUpdateManager(
    this, 7101,
    object : com.ads.module.ump.IUpdateInstanceCallback {
        override fun updateAvailableListener(
            updateAvailability: com.google.android.play.core.appupdate.AppUpdateInfo,
        ): Int = com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE

        override fun onUpdateUnavailable() { /* Không có / không được phép update. */ }
        override fun onUpdateFailed(error: Exception) { /* Báo lỗi hoặc mở Store. */ }
        override fun onUpdateResult(resultCode: Int) { /* Hủy / lỗi / kết quả Play UI. */ }
    },
)
updates.checkUpdateAvailable()
```

Trả `IMMEDIATE`, `FLEXIBLE` hoặc `-1` để bỏ qua. Manager không tự đọc Remote Config, không tự chặn navigation. Chỉ hỏi callback khi Play có update và kiểm tra loại update được phép trước khi mở UI.

AndroidX Activity được tự đăng ký lifecycle: resume immediate dang dở / complete flexible đã tải, dispose khi destroy. Với Activity thuần, gọi `updates.onResume()` và `updates.dispose()` từ lifecycle tương ứng. Forward `onActivityResult` nếu muốn nhận callback kết quả:

```kotlin
@Deprecated("Legacy result forwarding for ITGUpdateManager")
override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    updates.onActivityResult(requestCode, resultCode)
}
```

Giữ manager sống khi tải FLEXIBLE. Khi `DOWNLOADED`, manager tự gọi `completeUpdate()` như API cũ; app có thể restart. Nếu Activity đã hủy, tạo manager ở lần mở sau để hoàn tất. Không có snackbar xác nhận riêng trước complete.

## QA / rollout

- UMP chậm hơn remote, remote chậm hơn UMP: vẫn chạy song song; không có request ads trước verdict, không chồng update với consent/notification.
- Không có remote, remote false, local true, JSON lỗi: không bật gate. Remote true chỉ chặn version thấp.
- Fetch timeout không có remote đã activate: đi tiếp. Offline có remote true đã activate: vẫn theo policy đó.
- Force true: Back/hủy Play/quay lại từ Store chưa update/recreate không làm lọt navigation.
- SAME_TIME và ALTERNATE: force update phải có 0 request banner/interstitial/native và không có request app-open/auto-buffer mới. Khi verdict cho phép, load và timer ads mới bắt đầu.
- Tắt bằng remote `enabled=false` rồi fetch/activate ở lần mở splash mới; dialog/attempt đang chạy không tự refresh, kể cả recreate.
- Bản mới phải có sẵn cho đúng track/quốc gia/nhóm rollout trước khi nâng threshold, tránh chặn người chưa tải được update.
- App chưa có phần tích hợp này phải được phát hành bản mới trước; Remote Config không bổ sung code vào APK cũ.
- Test tải/cài thật bằng package/chữ ký/version phù hợp trên Play, theo [Test in-app updates](https://developer.android.com/guide/playcore/in-app-updates/test). APK debug sideload không chứng minh luồng Play chạy thật.

Nút Force Update ở dashboard Main là preview giao diện cũ, không phải kiểm thử policy hoặc Play end-to-end. Test production từ splash.

Tham khảo: [Google Play update API](https://developer.android.com/guide/playcore/in-app-updates/kotlin-java), [Firebase fetch/activate](https://firebase.google.com/docs/remote-config/android/get-started).
