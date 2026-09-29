# Chỉ preload interstitial ở màn có cơ hội show

API này bổ sung điều kiện màn hình cho `InterstitialAutoBuffer`. App báo màn đang hoạt động;
SDK so whitelist rồi dùng lại cooldown, tap, consent, premium, foreground và cache hiện tại.
Không cần thêm Compose hoặc Navigation vào SDK. Xem [Ads README](../ads/README.md#automatic-interstitial-preload)
để tích hợp buffer trước.

## 1. Khai báo whitelist một lần

```kotlin
import com.ads.module.helper.interstitial.InterstitialAutoBuffer

object AdScreen {
    const val HOME = "home"
    const val TRANSLATE = "translate"
    const val CAMERA = "camera"
    const val CONVERSATION = "conversation"
}

// Sau configure(...) hiện tại, trước khi start buffer.
InterstitialAutoBuffer.setPreloadScreens(
    mapOf(
        AppAdPlacement.INTER_BACK to setOf(
            AdScreen.TRANSLATE,
            AdScreen.CAMERA,
            AdScreen.CONVERSATION,
        ),
    ),
)
```

`AppAdPlacement` là catalog placement của app. Dùng ID màn ổn định, không dùng tên lớp, title
hiển thị hoặc route có tham số người dùng làm ID.

| Cấu hình | Hành vi |
| --- | --- |
| Placement không có trong map | Giữ auto preload như trước |
| Placement có set ID | Chỉ auto preload khi màn hiện tại khớp một ID |
| Placement có `emptySet()` | Tắt auto preload của placement đó |
| `setPreloadScreens(emptyMap())` | Bỏ toàn bộ giới hạn màn hình |
| Màn hiện tại là `null` | Placement có whitelist không được auto preload |

Mỗi lần gọi `setPreloadScreens` thay thế toàn bộ map. Giữ `configure(...)` và các threshold hiện
tại; API màn hình không tự sửa threshold. Đừng dùng `isPlacementEnabled` làm whitelist vì gate đó
còn ảnh hưởng quyền show.

## 2. Bắt đầu đồng hồ ngay khi vào nội dung, kể cả Home

Giữ `InterstitialAutoBuffer.start(applicationContext)` ở điểm vào nội dung đầu tiên sau
onboarding/consent, bao gồm Home và các đường deep link/restore. Gọi lại `start()` được phép và
không reset delay đang chạy. Không dời `start()` sang lúc vào function; làm vậy khiến lần đầu
ở Home chưa bắt đầu tính thời gian.

Giả sử placement dùng interval 30 giây, preload lead 2 giây, tap threshold **0**, không có ad
sẵn/request đang chạy, các gate còn lại cho phép và `t = 0` là mốc đồng hồ hiện tại:

| Tình huống | Kết quả |
| --- | --- |
| Vào function ở giây 10 | Chờ 18 giây còn lại, request ở giây 28 |
| Ở Home đến giây 35 rồi vào function | Home không request; vào function sẽ kiểm tra để request ngay |
| Vào function giây 10, về Home giây 20 | Không auto request ở giây 28 |
| Về Home khi request đã gửi | Giữ request/cache; không tự show khi request hoàn tất |
| Vào function trong thời gian chờ retry sau lỗi | Chờ đúng phần cooldown retry còn lại |

Screen API đánh thức buffer; không đợi tick chu kỳ tiếp theo. “Ngay” là đánh giá để bắt đầu
request trên main loop, không đảm bảo ad đã tải xong. Điều kiện thời gian đã đủ tiếp tục đủ
khi chờ ở Home; chuyển màn không reset đồng hồ hoặc tap. Các gate động vẫn được kiểm tra lại,
chẳng hạn người dùng đã mua premium thì không request.

**Chu kỳ cũ vẫn giữ nguyên:** đóng interstitial hoặc waterfall thất bại cuối cùng cập nhật mốc
tương ứng như trước. Chuyển màn hoặc bắt đầu tải thành công không tự tạo mốc cooldown mới.
Show vẫn cần đủ interval đầy đủ và một hành động show hợp lệ; vào function không tự show.

Bundled config hiện có BACK 30 giây / **1 tap**, ALL 30 giây / 2 taps, lead 2 giây. Ví dụ ở trên
chỉ đúng với threshold hiệu lực bằng 0; nếu vẫn là 1 thì đủ thời gian và đúng màn vẫn phải chờ
tap theo chính sách cũ. Chỉ đổi `interstitial_auto_buffer.rules.inter_back.tap_threshold` sang
0 khi chủ đích muốn preload trước lần Back đầu tiên. Remote/app asset có thể ghi đè cấu hình
Kotlin; xem [thứ tự cấu hình](../ads/README.md#opt-in-content-wait-and-independent-placement-clocks).

## 3. App báo màn đang hoạt động

```kotlin
val screen = InterstitialAutoBuffer.setCurrentScreen(AdScreen.TRANSLATE)
// Khi màn này rời trạng thái active:
screen.close()
```

Gọi setters và `close()` trên **main thread**. Handle chỉ sở hữu lần đăng ký của mình:

- Nếu B đã đăng ký sau A, `A.close()` không xóa B.
- Đăng ký lại cùng ID đổi chủ sở hữu, không đánh thức buffer thêm hoặc reset timer.
- Đóng handle hiện tại đặt màn về `null`; SDK không tự quay lại màn trước đó.
- Báo mọi destination, kể cả Home/màn ngoài whitelist. Khi quay lại màn cũ, đăng ký lại màn đó.
- Chỉ dùng **một nguồn báo màn** trong mỗi hierarchy. Activity chứa Fragment/Compose không
  đồng thời báo ID cha và để màn con báo ID riêng; hãy để selected destination quyết định.

SDK không tự đoán màn dựa vào class name. Partner chọn một trong các cách bên dưới theo cơ chế
điều hướng của app. Chỉ báo màn đã được chọn và resumed, kể cả khi inter ALL trước đó skip/no-fill.

### Activity riêng

Tích hợp vào Activity đại diện cho một màn, hoặc gom logic vào base Activity/lifecycle callback
của app. Ví dụ này giả định buffer đã được start ở content entry:

```kotlin
private var adScreen: AutoCloseable? = null

override fun onResume() {
    super.onResume()
    adScreen = InterstitialAutoBuffer.setCurrentScreen(AdScreen.TRANSLATE)
}

override fun onPause() {
    adScreen?.close()
    adScreen = null
    super.onPause()
}
```

Home báo `AdScreen.HOME` theo cùng cách. Activity có các tab nội bộ dùng nguồn selected tab
bên dưới thay vì coi cả Activity là một function.

### Fragment theo view lifecycle

Với điều hướng chỉ cho Fragment được chọn ở trạng thái `RESUMED`, gắn observer trong
`onViewCreated`. View lifecycle đảm bảo đăng ký được dọn khi view bị hủy.
[Tài liệu Fragment lifecycle](https://developer.android.com/guide/fragments/lifecycle).

```kotlin
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

// Trong onViewCreated(view, savedInstanceState):
viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
    private var screen: AutoCloseable? = null

    override fun onResume(owner: LifecycleOwner) {
        screen = InterstitialAutoBuffer.setCurrentScreen(AdScreen.CAMERA)
    }

    override fun onPause(owner: LifecycleOwner) = clear()
    override fun onDestroy(owner: LifecycleOwner) = clear()

    private fun clear() {
        screen?.close()
        screen = null
    }
})
```

Nếu app giữ nhiều Fragment resumed bằng `show/hide`, pager hoặc custom tabs, lifecycle riêng
không đủ để xác định màn được chọn. Khi đó host/navigation báo selected screen; không gắn
observer trên tất cả Fragment rồi để chúng tranh quyền báo màn.

### Điều hướng tập trung hoặc custom tabs

Đặt adapter này tại host đang quản lý selected destination. Gọi `publishAdScreen()` sau khi
selected destination thực sự cập nhật và khi host resume; đóng handle khi host pause/dispose.
Nếu host đang pause, chỉ cập nhật `selectedScreenId`, chờ resume mới publish.

```kotlin
private var adScreen: AutoCloseable? = null
private var selectedScreenId = AdScreen.HOME

private fun publishAdScreen() {
    val previous = adScreen
    adScreen = InterstitialAutoBuffer.setCurrentScreen(selectedScreenId)
    previous?.close() // Handle cũ không xóa đăng ký mới.
}

private fun releaseAdScreen() {
    adScreen?.close()
    adScreen = null
}
```

Với Navigation, ánh xạ destination thành ID ổn định rồi kết hợp destination được chọn với
lifecycle của entry. `NavBackStackEntry` có lifecycle riêng; không dùng mỗi lifecycle Activity
cho toàn bộ back stack. [Tài liệu Navigation](https://developer.android.com/guide/navigation/use-graph/programmatic).

### Compose: helper nằm trong app partner

Copy helper sau vào app đã dùng Compose. Không thêm dependency Compose vào SDK. Truyền
`LifecycleOwner` của màn, chẳng hạn `NavBackStackEntry` từ Navigation Compose. Helper theo dõi
`RESUMED`, dọn đăng ký khi pause/dispose và không phát side effect trực tiếp trong thân Composable.
[Tài liệu Compose effects](https://developer.android.com/develop/ui/compose/side-effects).

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.ads.module.helper.interstitial.InterstitialAutoBuffer

@Composable
fun TrackAdScreen(
    screenId: String,
    lifecycleOwner: LifecycleOwner,
    selected: Boolean = true,
) {
    DisposableEffect(screenId, lifecycleOwner, selected) {
        var screen: AutoCloseable? = null
        fun clear() {
            screen?.close()
            screen = null
        }
        fun sync() {
            if (selected && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                if (screen == null) {
                    screen = InterstitialAutoBuffer.setCurrentScreen(screenId)
                }
            } else {
                clear()
            }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycleOwner.lifecycle.addObserver(observer)
        sync()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            clear()
        }
    }
}
```

Ví dụ trong graph Navigation Compose của app:

```kotlin
composable("home") { entry ->
    TrackAdScreen(AdScreen.HOME, lifecycleOwner = entry)
    HomeScreen()
}
composable("translate") { entry ->
    TrackAdScreen(AdScreen.TRANSLATE, lifecycleOwner = entry)
    TranslateScreen()
}
```

Nếu app tự điều hướng bằng state, gọi helper một lần ở navigation root với ID đang được chọn
và lifecycle owner của host. Nếu giữ nhiều page trong composition, truyền `selected` đúng cho
từng page hoặc báo tập trung tại root; không coi “có trong composition” là “đang được chọn”.

## 4. Phạm vi của gate và ví dụ TranslatorGuru

Whitelist chỉ áp dụng cho **auto preload/refill và `topUpNow()`**. Các lệnh chủ động
`InterstitialAdManager.load`, `loadAndShow`, `show` tiếp tục dùng điều kiện cũ. Vì vậy, nếu muốn
Home không request BACK tự động, bỏ các lệnh explicit preload BACK ở Home/splash hoặc timer app
đang trùng với buffer. Không cần gọi `topUpNow()` sau `setCurrentScreen`.

Với TranslatorGuru, dashboard có Home và các tab chức năng trong cùng
`FragmentsDashboardActivity`: ID màn phải theo selected tab. `TranslateActivity` xử lý Back
trước khi finish; một số function khác trả về dashboard rồi mới show BACK. Dashboard có thể
đóng quyền preload mới nhưng vẫn dùng ad đã cache cho lượt Back đó. Không chặn `show` dựa trên
whitelist và không xóa cache lúc rời function.

Khi test, kiểm tra Home quá deadline không request; entry muộn request ngay; entry sớm chờ đúng
phần thời gian còn lại; rời màn trước deadline ngăn auto request; request đang chạy không bị
nhân đôi; handle cũ không xóa màn mới; Back vẫn show được sau khi về Home. Đo request/session,
tỷ lệ Back có ad sẵn, impression/session và show rate. Giảm request thừa không tự đảm bảo tăng
impression hoặc doanh thu.
