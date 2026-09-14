# Firebase — Analytics và Remote Config

[English](firebase-integration.md) · [Tiếng Việt](firebase-integration.vi.md) · [हिन्दी](firebase-integration.hi.md)

[← Chọn hướng dẫn](README.vi.md) · [API suite-firebase](../suite-firebase/README.md)

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

**Yêu cầu phiên bản:** dùng SDK `5.3.6` trở lên cho settings theo nhóm và `AdsConfig.fromAdConfig()`, đồng bộ version các module. Chỉ thêm key Firebase không nâng cấp SDK cũ.

## 3. JSON ads và onboarding từ remote

<a id="remote-json"></a>

### 3.1. Cài source và publish ba parameter String

Hoàn thành [Ads + OnboardKit](ads-onboarding-integration.vi.md) trước. Trong Application, sau `AdRemoteConfig.initializeFromAssets(this)`, cài source một lần. Giữ `OnboardingSdk.install` và `OnboardKitSetup.configure()` trước khi mở splash:

```kotlin
import com.ads.module.config.AdConfig
import io.suite.firebase.FirebaseAdConfigSource

AdConfig.install(FirebaseAdConfigSource())
```

`FirebaseAdConfigSource` đọc cả document ad unit và hai document settings mới. Không cần source riêng, tự gọi `getString`, gán từng field bằng setter hoặc thêm lượt fetch Firebase.

1. Mở Firebase project của app → **Remote Config → Parameters**.
2. Giữ key `ad_remote_config` hiện có. Thêm **`ad_behavior_config`** và **`onboarding_config`**, chọn kiểu **String**.
3. Dán nội dung object JSON tương ứng vào default value của mỗi parameter, bắt đầu từ file mẫu bên dưới. Dán nguyên `{ ... }`: không kèm Markdown, không bọc thêm dấu nháy, không escape thành chuỗi JSON và không bọc bằng tên parameter. Đây là value của parameter, không phải file import toàn bộ Firebase template.
4. Chỉnh các giá trị cần dùng, lưu rồi **Publish changes**. Nếu dùng condition/A/B test, value của từng phương án cũng là một object JSON. SDK ghép các field hợp lệ với default local; object remote mới thay bộ override remote trước đó, nên phải giữ lại những override vẫn muốn áp dụng.

| Firebase parameter (String) | Nội dung cần dán | Dùng cho |
| --- | --- | --- |
| `ad_remote_config` | [ad_config.json](examples/ads-onboarding/ad_config.json), thay ID production của app | ID, tầng, bật/tắt và các field CTA hiện có. Giữ nguyên tên parameter. |
| `ad_behavior_config` | [ad_behavior_config.json](examples/ads-onboarding/ad_behavior_config.json) | Hành vi theo dạng ads, timeout, reload/cache và bo góc CTA native. |
| `onboarding_config` | [onboarding_config.json](examples/ads-onboarding/onboarding_config.json) | Splash/LFO/OB, template native, X/Skip, swipe và preload. |

`ad_config.json` và `ad_config_debug.json` là tên asset local; source Firebase mặc định đọc **một** key ad unit là `ad_remote_config`. Không tạo thêm parameter `ad_config`, `ad_config_debug`, `ad_behavior_config_debug` hoặc `onboarding_config_debug` cho cách tích hợp này. Debug mặc định pin ID test **nhưng vẫn đọc hai parameter settings mới**. Dùng project/condition kiểm thử khi thử nghiệm. Các key `ob_*` cũ vẫn tương thích, không lồng vào các object JSON này. [Kiểu parameter và condition của Firebase](https://firebase.google.com/docs/remote-config/parameters).

`ObSplashActivity` đã gọi `AdConfig.refresh()`. Với `AdsConfig.fromAdConfig()` trong mẫu, SDK resolve ID/settings sau fetch mà không cần gọi lại `OnboardKitSetup.configure()` trong `onRemoteFetched`. Chỉ giữ hook đó nếu app có công việc riêng. App không dùng splash SDK thì await `AdConfig.refresh()` trong coroutine trước màn/request cần config, sau khi đã khởi tạo các kit.

<a id="local-defaults"></a>

### 3.2. Tự tạo local default khi remote không khả dụng

SDK đã đóng gói default của hai JSON. **Nếu dùng đúng default SDK thì app không cần tạo file.** Khi muốn chỉnh hành vi offline/default:

1. Tạo thư mục `app/src/main/assets/` nếu chưa có.
2. Tạo `ad_behavior_config.json` và/hoặc `onboarding_config.json` đúng tên trên. Có thể copy toàn bộ file mẫu tương ứng, hoặc chỉ khai báo các field cần đổi như ví dụ dưới. Asset của app thay asset SDK cùng tên.
3. Giữ `schema_version: 1`, đúng type/enum đã hướng dẫn; field không dùng thì bỏ đi, không gán `null`. Field thiếu dùng default SDK, trừ khi cấu hình host hiện có cung cấp fallback.
4. Build lại và khởi động lại process app. SDK đọc asset lúc khởi tạo; sửa file không phải cập nhật remote lúc chạy. Không cần thêm parser hay chép các giá trị này vào Firebase `setDefaultsAsync`.

`app/src/main/assets/ad_behavior_config.json`:

```json
{
  "schema_version": 1,
  "native": {
    "load": { "tier_timeout_ms": 25000 }
  }
}
```

`app/src/main/assets/onboarding_config.json`:

```json
{
  "schema_version": 1,
  "splash": { "permissions": { "no_internet_prompt_enabled": false } },
  "lfo": { "native_template": "COMPACT" },
  "onboarding": {
    "navigation": { "lock_pager_swipe": false },
    "fullscreen": { "skip": { "delay_ms": 1500 } }
  }
}
```

Ví dụ local này tắt yêu cầu kết nối của SDK để có thể kiểm tra luồng offline. Offline không tạo ad fill; kiểm tra config đã resolve và điều hướng, còn giao diện template cần thử online với ID test.

Đây là **ví dụ custom**, không phải thay đổi default SDK. Các file mẫu đầy đủ khớp default thực tế của SDK. Không dịch tên field JSON hoặc giá trị enum.

| Tình huống | Giá trị SDK sử dụng |
| --- | --- |
| Fetch thành công, field hợp lệ | Field remote ghi đè JSON local của app. |
| Lần đầu chạy, fetch lỗi/timeout, chưa có remote cache hợp lệ | JSON local custom → fallback host hiện có → default SDK. |
| Fetch lỗi/timeout sau khi từng thành công | Giữ snapshot/cache remote hợp lệ gần nhất; field không có trong đó vẫn dùng fallback local. **Fetch lỗi không ép local ghi đè remote cache hợp lệ.** |
| Fetch thành công nhưng thiếu field/parameter, hoặc field sai type/enum/range hay `null` | Bỏ override remote cũ của field đó, dùng local/host/default. Giá trị `false`/`0` hợp lệ vẫn được giữ. |
| Toàn bộ JSON hỏng/rỗng hoặc schema chưa hỗ trợ | Giữ snapshot hợp lệ gần nhất của document; nếu chưa có remote hợp lệ thì giữ local/default. |

Muốn bỏ toàn bộ override remote của một document mới, publish `{}` hoặc `{"schema_version":1}` rồi fetch thành công. String rỗng là JSON lỗi nên giữ snapshot cũ. Remote hợp lệ được lưu qua các lần khởi động; chỉ sửa local không làm nó ưu tiên hơn field remote đã cache. Để thử fallback local lần đầu, dùng bản cài trên thiết bị test chưa có remote cache, hoặc bỏ override remote thành công trước khi thử offline. Với hai ví dụ trên, lần chạy offline chưa có cache dùng timeout native **25000 ms**, LFO **COMPACT**, mở swipe và delay Skip **1500 ms**.

Asset app custom/khai báo một phần gán tường minh mọi field hợp lệ có mặt, kể cả `false`/`0`. Copy nguyên bản asset default SDK không sửa thì vẫn giữ fallback constructor/setter hiện có. Default value publish trên Firebase là giá trị **remote**, khác default local đóng gói trong SDK. Source không xem Firebase in-app defaults là dữ liệu remote đã fetch.

<a id="remote-notes"></a>

### 3.3. Thời điểm áp dụng, phiên bản và QA

- Dùng bản SDK đã có grouped settings và `AdsConfig.fromAdConfig()`, đồng bộ version các module. Chỉ thêm key trên Firebase không bổ sung tính năng này cho SDK cũ.
- `ALTERNATE` chờ bước remote kết thúc (hoặc timeout/fallback) rồi mới request ads splash. `SAME_TIME` có thể request **banner/interstitial splash** sớm hơn. **LFO1 được lên lịch preload sau bước remote ở cả hai strategy**; LFO `PARALLEL` nghĩa là không đợi interstitial splash tải xong. Strategy được đọc lúc bắt đầu splash, nên thay đổi vừa fetch cho strategy sẽ áp dụng ở lượt splash sau.
- Template SDK được override tường minh sẽ chọn khung native trước `positionCTA`; màu/chiều cao/components CTA vẫn ở `ad_remote_config`. Giữ `R.layout`, reference resource, system bars, orientation và progress indicator trong app. Consent/premium và quyền cho phép request của app vẫn có hiệu lực.
- Firebase dùng chung fetch; kết quả thành công được dùng lại trong process và vẫn chịu fetch interval của Firebase. Khi QA thay đổi trên Console, khởi động lại process và tính đến interval; mở lại Activity không bảo đảm có lượt network fetch mới.
- Ở debug, `AdConfig.refresh()` có thể trả `false` vì ID ads bị pin dù hai settings document đã được áp dụng. Không dùng Boolean này làm cờ thành công của hai document mới.
- Kiểm tra remote hợp lệ, field thiếu/sai, offline lần đầu dùng local và offline sau một lần remote thành công dùng cache. JSON local phải được build vào app.

Xem [reference settings, nơi quản lý field và toàn bộ default](remote-settings.vi.md) trước khi chỉnh phương án UA/MO.

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
| Debug ads / paywall | ID ad unit debug mặc định được pin, nhưng hai document settings vẫn áp dụng. PayKit không pin tương tự. Dùng project/condition kiểm thử phù hợp. |

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
