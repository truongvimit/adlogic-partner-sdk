# Firebase — Analytics và Remote Config

[← Chọn hướng dẫn](README.md) · [API suite-firebase](../suite-firebase/README.md)

Làm bước 1, rồi chọn phần app cần: Analytics (bước 2), remote ads (bước 3), remote paywall (bước 4). `suite-firebase` đã cung cấp Firebase Analytics, Remote Config và Trackkit.

## 1. Cấu hình Firebase của app

Hoàn thành [build setup](../README.vi.md#cấu-hình-build), JDK 17 và `minSdk 24+`. Đăng ký Android app trên Firebase Console với `applicationId` thực tế, tải `google-services.json` vào `app/`. File này do Firebase cấp, không copy của example. Nếu debug có application ID khác, đăng ký app tương ứng và đặt file vào source set đó. [Firebase: thiết lập Android](https://firebase.google.com/docs/android/setup).

Giữ cấu hình Google Services đang có. Nếu chưa có plugin, khai báo `googleServicesVersion` trong `gradle.properties` theo toolchain app và ghép vào root `build.gradle`:

```groovy
buildscript {
    def googleServicesVersion = project.providers.gradleProperty('googleServicesVersion').get()
    repositories { google(); mavenCentral() }
    dependencies { classpath "com.google.gms:google-services:$googleServicesVersion" }
}
```

Trong `app/build.gradle`, giữ Android/Kotlin plugins đang dùng:

```groovy
plugins { id 'com.google.gms.google-services' }

def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

Giữ BOM đang dùng nếu app đã cấu hình Firebase; không thêm lại Analytics/Remote Config. Chỉ thêm Ads/PayKit khi dùng nguồn remote của kit đó.

## 2. Gửi analytics qua Tracker

Trong Application hiện có, install [Tracker](trackkit-integration.vi.md#2-install-và-chọn-nơi-nhận-event) rồi thêm sink **trước khi install các SDK phát event**:

```kotlin
import io.suite.firebase.FirebaseSink
import io.trackkit.Tracker

// Trong Application.onCreate, tại thứ tự nêu trên:
Tracker.addSink(FirebaseSink())
```

App gọi event qua `Tracker`; sink chuyển tiếp sang Firebase. Ads/purchase và `ad_impression` đã được SDK gửi; không log lại trong callback UI.

### Consent ban đầu

Giữ cấu hình consent mà app đã chọn. Thư viện không khai báo giá trị ban đầu; khi cả hai trục Tracker còn `UNKNOWN`, sink giữ nguyên thiết lập Firebase. Nếu app chọn khởi đầu cả bốn giá trị là denied, đặt trong `<application>`:

```xml
<meta-data android:name="google_analytics_default_allow_analytics_storage" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_storage" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_user_data" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_personalization_signals" android:value="false" />
```

Đây là lựa chọn của app, không phải default SDK. `ConsentCenter` đã nối sang Tracker và luôn map analytics thành granted, ads theo personalization; form UMP không phải dialog xin consent analytics riêng. App có flow consent riêng xem [Trackkit](trackkit-integration.vi.md#4-bảng-cấu-hình). Tùy chọn collection ở [bảng bên dưới](#5-bảng-cấu-hình).

## 3. JSON ads từ remote

Hoàn thành [Ads + OnboardKit](ads-onboarding-integration.vi.md) trước, bao gồm hai JSON local và khởi tạo assets. Thêm dependency `ads` theo guide đó. Trong Application, sau khi local ad config đã được nạp, gọi một lần:

```kotlin
import com.ads.module.config.AdConfig
import io.suite.firebase.FirebaseAdConfigSource

// Trong Application.onCreate, tại thứ tự nêu trên:
AdConfig.install(FirebaseAdConfigSource())
```

Trên Firebase Console → Remote Config, tạo parameter **String** `ad_remote_config`, dán cấu trúc [ad_config.json](examples/ads-onboarding/ad_config.json), thay ad ID phù hợp rồi **Publish**. Không cần tạo một schema hay file remote riêng.

`ObSplashActivity` đã gọi `AdConfig.refresh()`; giữ `OnboardKitSetup.configure` trong `onRemoteFetched` như guide OB để setup dùng config đã fetch. App không dùng splash SDK thì gọi `AdConfig.refresh()` trong coroutine trước điểm cần remote config.

Debug asset nạp thành công được pin mặc định nên remote không thay thế ads debug. Muốn kiểm tra remote ads, dùng cấu hình kiểm thử được mô tả trong [guide ads](ads-onboarding-integration.vi.md); vẫn giữ ad ID test trong môi trường kiểm thử. Các parameter `ob_*` của OnboardKit là key riêng trên Console, không nhét vào JSON ads.

## 4. JSON paywall từ remote

Hoàn thành [PayKit local](paywall-integration.vi.md) trước, gồm dependency và resource fallback. Trong Application, sau `PayKit.install`, gọi:

```kotlin
import io.paykit.PayKit
import io.suite.firebase.FirebaseConfigSource

// Trong Application.onCreate, tại thứ tự nêu trên:
PayKit.configSource(FirebaseConfigSource())
```

Tạo parameter **String** `paywall_config` trên Console, dán [JSON mẫu](examples/paywall/paywall_config.json), đổi catalog/copy của app rồi Publish. Có thể thêm `placements` không rỗng để override các điểm mở theo [bảng PayKit](paywall-integration.vi.md#5-bảng-json-và-config-tùy-chọn).

Tại `onCreate` của Activity đầu tiên dùng lifecycle AndroidX (kể cả splash SDK), sau khi Application đã install PayKit và source, bắt đầu sync:

```kotlin
import androidx.lifecycle.lifecycleScope
import io.paykit.PayKit
import kotlinx.coroutines.launch

// Trong Activity.onCreate, sau super.onCreate:
lifecycleScope.launch { PayKit.sync() }
```

Mở paywall như [guide PayKit](paywall-integration.vi.md), không sync lại mỗi click. Remote chỉ được dùng nếu đã sync xong; chưa xong hoặc fetch lỗi thì mở bằng local/cache đang có. Cách này cũng dùng được với splash/OB gate: splash ads không tự sync PayKit và app không launch thêm paywall sau gate.

## 5. Bảng cấu hình

| Cấu hình | Default / chỉ đổi khi cần |
| --- | --- |
| `FirebaseSink(collectionFollowsConsent)` | `true`: khi consent đã có quyết định, analytics granted thì bật collection, còn lại tắt. `false`: app tự quản lý collection; sink vẫn cập nhật Consent Mode. Cả hai trục còn `UNKNOWN` thì giữ thiết lập Firebase. |
| Mapping consent | Analytics → `ANALYTICS_STORAGE`; ads → `AD_STORAGE`, `AD_USER_DATA`, `AD_PERSONALIZATION`. Khi một trục đã có quyết định, trục còn `UNKNOWN` được gửi là denied. |
| `FirebaseSink.setDefaultEventParameters(...)` | Không có extra defaults. Dùng khi cần params cho cả event Firebase tự thu thập; `Tracker.setDefaults` chỉ áp dụng event đi qua Tracker. |
| `FirebaseAdConfigSource(key)` | `ad_remote_config`; đổi khi Console app dùng key khác. |
| `FirebaseConfigSource(key)` | `paywall_config`; đổi khi Console app dùng key khác. |
| Ads/PayKit fetch | Cài source chưa fetch. Hai source dùng chung lượt fetch và giữ kết quả thành công trong process; lỗi cho phép retry, vẫn theo minimum fetch interval của Firebase. |
| Blank / Firebase in-app defaults | Hai source bỏ qua; không dùng `setDefaultsAsync` thay cho JSON local của kit. |
| Offline / JSON sai | Kit giữ config đang có; PayKit có cache remote ưu tiên hơn fallback bundled. Không cần code fallback riêng. |
| Debug ads / paywall | Ads pin debug asset mặc định; PayKit không có cơ chế pin tương tự. Chọn Firebase project/conditions kiểm thử phù hợp. |

Chỉ gọi `AdConfig.refresh()`/`PayKit.sync()`, không cần thêm `fetchAndActivate`. Remote flags `ob_*` có luồng fetch riêng do OnboardKit quản lý.

## 6. Kiểm tra và file cần có

- [ ] Build xử lý được Google Services, application ID khớp JSON Firebase.
- [ ] `Tracker.sinkIds()` có `firebase`; event app và SDK xuất hiện một lần theo consent đã chọn.
- [ ] Remote đã Publish; thử online, offline và JSON lỗi với fallback local.
- [ ] Ads debug vẫn dùng asset đang pin; paywall mở được bằng local/cache cả khi remote chưa xong.

Để xem Firebase DebugView, chạy `adb shell setprop debug.firebase.analytics.app <applicationId>` rồi mở app; tắt bằng `adb shell setprop debug.firebase.analytics.app .none.`. [Firebase DebugView](https://firebase.google.com/docs/analytics/debugview).

| File | Cần làm |
| --- | --- |
| Root/app Gradle, `gradle.properties` hiện có | Plugin nếu chưa có và dependency. |
| `app/google-services.json` hoặc source set tương ứng | File Firebase cấp đúng app. |
| Application / manifest hiện có | Tracker sink, nguồn remote cần dùng, consent ban đầu theo app. |
| JSON local của Ads/PayKit | Giữ fallback từ guide tương ứng; không tạo bản sao chỉ để fetch remote. |
