# Tích hợp Ads + OnboardKit

**OB catalog:** `ob1..ob4` → `native_ob1..4`; `full1/full2` → `native_full1/2`. Default: `ob1, full1, ob2, full2, ob3, ob4`. All eligible OB natives preload on language selection. Remote `onboarding.order` selects/reorders app-declared steps. [Configuration / Hướng dẫn chi tiết](onboarding-flow.vi.md). `native_fs` remains the separate splash native.

[← Chọn hướng dẫn](README.vi.md)

Luồng mẫu: **Splash → ngôn ngữ (LFO) → nội dung 1 → nội dung 2 → native fullscreen → nội dung 3 → inter cuối OB (khi được bật) → MainActivity**. SDK quản lý consent, thông báo, ads và điều hướng; quảng cáo chỉ hiện khi đủ điều kiện và có fill.

Làm bước 1–6, thay **package, thông tin app, nội dung/ảnh và màn đích**. Code giữ default SDK; JSON giữ cấu hình example debug. Điền app token để bật Adjust; Firebase, ads khi quay lại và mua hàng ở [bảng tùy chọn](#7-cấu-hình-chỉ-khi-app-cần).

Dùng **version SDK mới nhất** trên [JitPack](https://jitpack.io/#truongvimit/adlogic-partner-sdk) cho mọi module. Bản mới nhất có `ad_behavior_config` / `onboarding_config`, default local custom và liên kết trực tiếp `AdsConfig.fromAdConfig()`. Chỉ thêm key Firebase không nâng cấp SDK cũ đã tích hợp trong app.

Cụm Privacy → Goal tùy chọn là phần cuối onboarding, sau pager và interstitial cuối pager (nếu được bật; mặc định tắt). Bật `privacy_goals_screen.enabled` và cung cấp lựa chọn goal; xem [Privacy → Goal](privacy-goals-screen.vi.md).

User cũ mở app từ launcher mặc định bỏ qua Welcome Back và vào app sau splash. Bật `welcome_back.enabled=true` để hiện màn này. Xem [Welcome Back](welcome-back-screen.vi.md).

Tài liệu này mô tả native flow mặc định `PER_SCREEN`. Flow thay thế `SHARED_POOL` chạy LFO → pager → Privacy → Goal trong một màn SDK, lấy ad từ một pool native dùng chung; xem [Native flow SHARED_POOL](#native-flow-shared_pool).

Native trong pager được giữ khi view của trang còn tồn tại; quay lại content hoặc Full1/Full2 hiển thị cùng ad, không xin request mới. Auto-next fullscreen bắt đầu lại mỗi lượt ghé; Skip hiện ngay khi quay lại nếu được bật hoặc cần chống kẹt. No-fill lần đầu tự đi tiếp; quay lại trang lỗi hiện fallback và Skip. Ad được giải phóng khi view bị hủy.

## 1. Thêm dependency

Yêu cầu JDK 17, `minSdk 24+`, `compileSdk 36+`; AGP/Kotlin theo [versions.gradle](../versions.gradle) và [Gradle wrapper](../gradle/wrapper/gradle-wrapper.properties). Ghép code Groovy dưới vào block hiện có.

Trong `settings.gradle`, bổ sung repository còn thiếu:

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
        maven { url 'https://artifact.bytedance.com/repository/pangle/' }
        maven { url 'https://android-sdk.is.com/' }
        maven { url 'https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea' }
    }
}
```

Đặt version JitPack mới nhất một lần trong `gradle.properties` ở root project của app; mọi module SDK dùng chung property này:

```properties
adlogicSdkVersion=NEWEST_VERSION
```

Mọi module dùng chung property này.

Trong `app/build.gradle`:

```groovy
android {
    compileSdk 36
    defaultConfig { minSdk 24 }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = '17' }
    buildFeatures { buildConfig = true }
    // Giữ các bản dịch trong app bundle để bộ chọn ngôn ngữ dùng được offline.
    bundle { language { enableSplit = false } }
}

def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
}
```

Giữ `targetSdk` theo app (repo dùng 36), dùng AndroidX/AppCompat và `MainActivity` hiện có. Sync Gradle rồi tiếp tục. SDK đã gồm GMA/UMP, mediation, Trackkit và consumer rules cho release `minifyEnabled`: không thêm lại dependency, `MobileAds.initialize()` hay `app/proguard-rules.pro` của example.

## 2. Thêm ID và hai file JSON

### `app/src/main/res/values/id_ads.xml`

```xml
<resources>
    <string name="admob_app_id" translatable="false">ca-app-pub-3940256099942544~3347511713</string>
    <string name="facebook_app_id" translatable="false">YOUR_META_APP_ID</string>
    <string name="facebook_client_token" translatable="false">YOUR_META_CLIENT_TOKEN</string>
    <!-- Điền app token để bật Adjust; để trống nếu chưa dùng. -->
    <string name="adjust_token" translatable="false"></string>
    <!-- Event token từ Adjust, chỉ điền khi dùng event doanh thu quảng cáo. -->
    <string name="event_token" translatable="false"></string>
    <!-- Event token mua hàng, chỉ điền khi app có IAP. -->
    <string name="adjust_event_token_purchase" translatable="false"></string>
</resources>
```

AdMob App ID test chứa **`~`**. Phải thay `YOUR_META_*` bằng credential team cung cấp trước khi chạy: `ERainAd.init()` luôn khởi tạo Facebook SDK, kể cả khi tắt Adjust. Giữ ba field Adjust; chưa dùng để trống, khi dùng điền token thật theo [bảng Adjust](#adjust-token-và-kiểm-tra).

### `app/src/main/assets/`

Copy hai file đúng tên vào `assets` (tạo folder nếu thiếu):

- **[ad_config.json](examples/ads-onboarding/ad_config.json):** cấu hình cho bản release; hiện toàn bộ ID là test.
- **[ad_config_debug.json](examples/ads-onboarding/ad_config_debug.json):** chỉ có `ids` test cho mỗi key placement (thường một tầng); mọi field khác lấy từ `ad_config.json`.

Ad unit ID chứa **`/`**. `ad_config.json` chứa style, UA, app-resume delay và waterfall cho mọi build; [example debug](examples/ads-onboarding/ad_config_debug.json) chỉ map mỗi key placement sang một tầng test (interstitial `1033173712`). Dưới đây là các slot OB; `fromAdConfig()` ở bước 4 còn liên kết `native_resume` (native resume), `open_resume` (app-open), `native_welcome1/2` (Welcome Back) và `native_onboarding_fullscreen_1_4` (OB5). Các key còn lại dành cho màn app, chưa tự tạo vị trí hiển thị. Giá trị mẫu có thể khác default parser; xem [bảng field JSON](#field-trong-json-mẫu).

| Key trong JSON | Vị trí | Ánh xạ vào `AdsConfig` ở bước 4 |
| --- | --- | --- |
| `banner_splash` | Banner splash | `splashBanner` |
| `native_splash` | Native splash, cùng slot với banner | `splashInlineNative` |
| `inter_splash` | Inter khi rời splash | `splashInterstitial` |
| `native_fs` | Native toàn màn hình tùy chọn sau inter splash, trước LFO | `splashNative` |
| `native_lang` | Native ngôn ngữ đầu tiên | `languageNative` |
| `native_lang_alt` | Native thay thế sau lần chọn ngôn ngữ đầu | `languageDupNative` |
| `native_popup_lang` | Native trong popup xác nhận ngôn ngữ | `languageConfirmNative` |
| `native_ob1` | Nội dung 1 — `StepId.OB1` | `stepNatives[StepId.OB1]` |
| `native_ob2` | Nội dung 2 — `StepId.OB2` | `stepNatives[StepId.OB2]` |
| `native_full1` | Full1 — `StepId.FULL1` | `stepNatives[StepId.FULL1]` |
| `native_full2` | Full2 — `StepId.FULL2` | `stepNatives[StepId.FULL2]` |
| `native_ob3` | Content 3 — `StepId.OB3` | `stepNatives[StepId.OB3]` |
| `native_ob4` | Content 4 — `StepId.OB4` | `stepNatives[StepId.OB4]` |
| `inter_after_ob3` | Sau toàn bộ onboarding, trước màn đích | `afterOnboardingInterstitial` |

`inter_after_ob3` shows after the entire configured OB list. See [the current step catalog](onboarding-flow.vi.md).

Mọi build đọc cấu hình từ `ad_config.json`. Build debuggable đọc thêm `ad_config_debug.json`, file này chỉ có `ids` cho mỗi key placement, thường một tầng test: các tầng này thay cả waterfall của vị trí, nên mỗi vị trí load ID test qua luồng load thông thường. Field khác trong file debug bị bỏ qua kèm log `WARN`; placement đang bật trong `ad_config.json` mà không có entry debug thì không có ad (cũng log `WARN`). `ad_remote_config` remote áp mọi field trừ ID; `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` nhận cả ID remote khai báo. Không có file debug thì debug dùng ID của `ad_config.json`.

ID mẫu từ [Google demo ad units](https://developers.google.com/admob/android/test-ads#demo_ad_units) và [AdMob App ID](https://developers.google.com/admob/android/quick-start). Fullscreen dùng **native ID**. Mẫu dùng chung ID test cùng format; production cần ID riêng để tách cấu hình/style/báo cáo theo placement.

### Tùy chọn: settings remote theo nhóm và local default riêng

SDK đã đóng gói thêm [ad_behavior_config.json](examples/ads-onboarding/ad_behavior_config.json) và [onboarding_config.json](examples/ads-onboarding/onboarding_config.json). Muốn điều khiển qua remote, làm theo [Firebase: publish ba parameter String](firebase-integration.vi.md#remote-json). Giữ `ad_remote_config` cho ad unit; thêm `ad_behavior_config` và `onboarding_config` là hai String riêng, value chứa object JSON tương ứng. Hai example trùng default đóng gói, trừ ba chỗ trong example onboarding: `onboarding.primary_color` để `""` để app giữ màu UI sẵn có, còn default SDK là `"#FF375E"`; `onboarding.exit_interstitial.enabled` là `true`, còn default SDK là `false`; và không có scope [`native_flow`](remote-settings.vi.md#kiểu-native-flow).

Muốn custom fallback, tạo file cùng tên trong `app/src/main/assets/`, copy toàn bộ default hoặc chỉ khai báo field muốn đổi, rồi build lại. Nếu dùng default SDK thì không cần tạo hai file ở app. Field remote/cache hợp lệ, rồi key `ob_*` cũ mà backend đã gửi, ưu tiên hơn hai file này ở mọi scope và hơn cấu hình Kotlin của app; fetch offline giữ remote cache hợp lệ cũ thay vì ép dùng local. Xem [ví dụ hai JSON local và quy tắc fallback](firebase-integration.vi.md#local-defaults), cùng [reference field/default](remote-settings.vi.md).

## 3. Chuẩn bị nội dung onboarding

Dùng `app_name`, icon launcher sẵn có. Thêm tám string vào `app/src/main/res/values/strings.xml`, thay nội dung theo sản phẩm:

```xml
<resources>
    <string name="onboarding_title_1">Chào mừng bạn</string>
    <string name="onboarding_des_1">Giới thiệu lợi ích chính của ứng dụng.</string>
    <string name="onboarding_title_2">Bắt đầu dễ dàng</string>
    <string name="onboarding_des_2">Giới thiệu thao tác người dùng cần biết.</string>
    <string name="onboarding_title_3">Sẵn sàng trải nghiệm</string>
    <string name="onboarding_des_3">Mời người dùng bắt đầu sử dụng.</string>
    <string name="onboarding_title_4">Thêm một điều nữa</string>
    <string name="onboarding_des_4">Giới thiệu thêm một tính năng đáng chú ý.</string>
</resources>
```

Thêm bản dịch vào `values-<language>/strings.xml`, gồm các key `ob_*` trong [ob_strings.xml](../onboardkitorigin/src/main/res/values/ob_strings.xml) vì SDK chỉ kèm tiếng Anh. Bước 4 thay bốn `imageRes` bằng ảnh app (trong mẫu OB3 và OB4 dùng chung một ảnh), hoặc copy ảnh [1](../app/src/main/res/drawable-nodpi/img_onboard_sample_1.png), [2](../app/src/main/res/drawable-nodpi/img_onboard_sample_2.png), [3](../app/src/main/res/drawable-nodpi/img_onboard_sample_4.png) từ example vào `app/src/main/res/drawable-nodpi/` để thử.

SDK đã cung cấp layout/Activity cho LFO, popup, OB và native ad.

<details>
<summary>Tùy chọn: dùng layout riêng</summary>

Chỉ `SplashConfig.layoutRes` và `ContentStepDefinition.layoutRes` hỗ trợ layout riêng; cấu hình khác bị `getOrThrow()` từ chối. Copy [layout nội dung](../onboardkitorigin/src/main/res/layout/ob_fragment_content_step.xml), giữ root, ID và kiểu view; sai hợp đồng thì SDK log `OB_FLOW` và dùng layout mặc định. Với [splash](../onboardkitorigin/src/main/res/layout/ob_activity_splash.xml), ID thiếu bị bỏ qua, ID giữ lại phải đúng kiểu; banner cần `ob_splash_ad_container` chứa `<include layout="@layout/layout_banner_control" />`.

</details>

## 4. Khai báo placement và cấu hình OnboardKit

### `AppAdPlacement.kt` — danh mục placement của app

Copy [AppAdPlacement.kt](examples/ads-onboarding/AppAdPlacement.kt) vào package app, ví dụ `app/src/main/java/com/example/app/`. File có một key cho mỗi placement: các slot OB và slot app. Các tầng nằm trong `ids` của key đó; không cần constant cho tầng.

`AppAdPlacement.NATIVE_HOME` là key `native_home`; `ad_config.json` chứa ad unit ID và cấu hình của key đó, `ad_config_debug.json` chỉ chứa `ids` test. Slot mới cần constant và key tương ứng trong JSON.

Khai báo slot native/banner trong XML của màn, gọi API SDK trực tiếp tại màn. SDK quản lý tải, cache và vòng đời ads. Nếu dùng `AdsAppManager`, chỉ gom cấu hình, khởi tạo và chính sách riêng của app.

### `OnboardKitSetup.kt` — nối các key OB vào SDK

Copy [OnboardKitSetup.kt](examples/ads-onboarding/OnboardKitSetup.kt) cùng package, đổi `com.example.app` và giữ resource của app. Mẫu khai báo bốn trang nội dung + hai trang fullscreen (FULL1, FULL2), dùng **`AdsConfig.fromAdConfig()`** để liên kết các placement chuẩn. Bước 5 configure một lần sau `OnboardingSdk.install`; ID, gate và template được resolve từ settings hiện hành, không setup lại sau fetch. App dùng key khác chỉ truyền các association đó vào `fromAdConfig(mapOf(...))`; [bảng mapping](remote-settings.vi.md) liệt kê key mặc định.

Placement đã khai báo nhưng bị tắt giữ unit rỗng, không lấy quảng cáo của slot khác. Chỉ khi LFO2 không có unit mới fallback LFO1; tắt hành động thay native bằng `onboarding_config.lfo.native2.enabled = false`.

Native LFO/Welcome Back/content OB lấy khung theo `templateId` của key placement (LFO1/2 trong `SHARED_POOL` mặc định dùng template `4`, LFO classic/Welcome Back/OB content dùng template `1`) và thứ tự khối theo `components` của key đó; thứ tự mặc định đặt CTA ở dưới, `["cta","media","icon_headline"]` đưa CTA lên trên. `positionCTA` đã bỏ: payload vẫn gửi field này sẽ bị bỏ qua. Host fallback `AdsConfig.languageTemplate`/`contentStepTemplate` và enum `NativeTemplate` cũng bỏ theo; hãy xoá chúng khỏi `AdsConfig` của bạn. Các cờ `native_template`/`content_template` trong `onboarding_config` đã bỏ, kể cả per-step. Fullscreen/popup giữ frame cố định. `colorCTA` áp dụng cả nền CTA và badge Ad; màu/chiều cao/components được đọc lại tại mỗi lần bind, gồm ad thay thế sau click/resume.

## 5. Khởi tạo trong Application

Copy [PartnerApp.kt](examples/ads-onboarding/PartnerApp.kt), hoặc ghép `onCreate`, listener và hai constant vào Application hiện có, giữ base class/Hilt. Đổi `MainActivity` thành màn đích.

Mẫu đã nối đủ:

| Phần | App cần thay |
| --- | --- |
| Tracker, JSON, ERainAd, ERainTuning, OnboardKit | Giữ thứ tự trong mẫu; chỉ thêm sink nếu cần analytics. |
| Môi trường ads/Adjust | Mẫu tự chọn debug/release từ `BuildConfig.DEBUG`. |
| Adjust | Điền token ở `id_ads.xml`; chưa dùng thì để trống. |
| Listener hoàn tất | Màn đích và cách lưu ngôn ngữ nếu app đã có cơ chế riêng. |

Thứ tự: **Tracker → JSON → ERainAd → ERainTuning → install OnboardKit → configure**. Meta token đọc từ manifest. Tracker cần sink để gửi event tới Firebase/backend.

`ERainTuning.install()` đặt thời điểm `onComplete` cho [inter màn app](#interstitial-ở-màn-app-dùng-placement-constant) và bỏ lần ads khi quay lại kế tiếp sau click quảng cáo; không đổi luồng OB.

Listener đưa `Completed`/`Skipped`/`Aborted` về màn chính; đổi `Aborted` nếu cần. Giữ `NEW_TASK`, không `CLEAR_TASK` để màn chính mở dưới inter splash của người dùng cũ/inter cuối OB. SDK tự lưu hoàn thành và điều hướng; không thêm timer.

**Locale màn app phải tự áp.** Mẫu lưu `Completed.selectedLanguage` (`en-US`, `es`). App chưa có cơ chế locale thì thêm vào base Activity/MainActivity, import `android.content.Context`, `android.content.res.Configuration`, `java.util.Locale`. Đổi `PartnerApp` thành tên Application thực tế:

```kotlin
override fun attachBaseContext(newBase: Context) {
    val code = newBase.getSharedPreferences(PartnerApp.PREFS, Context.MODE_PRIVATE)
        .getString(PartnerApp.LANGUAGE, null)
        ?: return super.attachBaseContext(newBase)
    val config = Configuration(newBase.resources.configuration)
        .apply { setLocale(Locale.forLanguageTag(code)) }
    super.attachBaseContext(newBase.createConfigurationContext(config))
}
```

Đọc lựa chọn đã lưu bằng `OnboardingSdk.selectedLanguage()` (suspend). SDK không tự dịch hay cập nhật preferences app.

### Adjust: token và kiểm tra

Mẫu dùng **`com.ads.module.config.AdjustConfig`**. SDK quản lý Adjust, lifecycle/attribution và doanh thu; chỉ điền resource, không thêm `Adjust.initSdk()` hay Tracker sink cho Adjust.

| Resource trong `id_ads.xml` | Gán vào SDK | Cần điền gì |
| --- | --- | --- |
| `adjust_token` | `AdjustConfig(true, token)` | App token; có thì bật, trống thì tắt Adjust. |
| `facebook_app_id` | `fbAppId` | Cùng Meta App ID trong manifest, dùng cho tích hợp Meta qua Adjust. |
| `event_token` | `eventAdImpression` | Event token 6 ký tự cho paid impression; chỉ điền khi cần. |
| `adjust_event_token_purchase` | `eventNamePurchase` | Purchase event **token** 6 ký tự, không phải tên event; chưa có IAP để trống. |

SDK đã gọi Adjust ad-revenue API. `event_token` gửi thêm cùng doanh thu dưới dạng event cho Meta/TikTok…; tránh cộng cả hai nguồn trong báo cáo. Token trống được bỏ qua, không gửi lại revenue ở callback app.

Debug dùng Adjust **sandbox**, release **production**. Kiểm tra log `ERainAdjust`: `Adjust initialised (sandbox)` hoặc lỗi token; đối chiếu session/event trên Adjust. Test purchase cần billing và giao dịch test, không chỉ event token.

## 6. Đăng ký splash làm launcher

Tạo `SplashActivity.kt` cùng package:

```kotlin
package com.example.app

import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity()
```

Ghép vào `app/src/main/AndroidManifest.xml`, sửa tên/entry class hiện có. Chuyển launcher cũ sang splash, chỉ giữ một launcher:

```xml
<application android:name=".PartnerApp">
    <meta-data
        android:name="com.google.android.gms.ads.APPLICATION_ID"
        android:value="@string/admob_app_id" />
    <meta-data
        android:name="com.facebook.sdk.ApplicationId"
        android:value="@string/facebook_app_id" />
    <meta-data
        android:name="com.facebook.sdk.ClientToken"
        android:value="@string/facebook_client_token" />

    <activity android:name=".MainActivity" android:exported="false" />
    <activity
        android:name=".SplashActivity"
        android:exported="true"
        android:screenOrientation="portrait"
        android:configChanges="orientation|screenSize|keyboardHidden|uiMode|fontScale"
        android:theme="@style/ob_Theme_OnboardKit">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
</application>
```

Giữ `configChanges` để đổi dark mode/cỡ chữ không tạo lại splash. SDK đã khai báo Activity và quyền mạng/`AD_ID`/`POST_NOTIFICATIONS`. Kiểm tra Application, launcher, metadata, theme trong **Merged Manifest**; thay entry `${app_id}` cũ bằng resource mẫu.

Splash tự xử lý UMP/notification, ads và điều hướng. Không cần override lifecycle, gọi consent/`OnboardingSdk.start()` hay tự preload/show cho OB.

Hoàn thành bước 1–6 là chạy được luồng chuẩn. Kiểm tra theo [checklist](#8-kiểm-tra-hoàn-tất); các phần dưới chỉ đọc khi cần đổi behavior hoặc thêm ads ở màn app.

## 7. Cấu hình chỉ khi app cần

### Mặc định của luồng

Bảng dưới mô tả default SDK và API local/legacy đang dùng làm fallback. Khi thử remote hoặc đổi default qua JSON local, dùng field tương ứng trong [reference settings theo nhóm](remote-settings.vi.md). Thứ tự ưu tiên cho mọi setting: field `onboarding_config` / `ad_behavior_config` remote > key `ob_*` cũ mà backend đã gửi > JSON asset của app > option Kotlin và hook bên dưới > default SDK đóng gói.

Chỉ thêm option cần đổi vào `onboardKitConfig { ... }` ở bước 4; `ERainAd`/`ConsentCenter` gọi tại nơi ghi trong bảng. Key `ob_*` thuộc Firebase Remote Config, **không nằm trong JSON ads**; chưa có Firebase thì dùng giá trị Firebase gửi lần cuối (cache), còn key chưa từng được gửi thì cấu hình của app quyết định.

| Hành vi | Mặc định | Chỉ đổi khi / nơi đổi |
| --- | --- | --- |
| Màn splash | Layout SDK; minimum display 3000 ms tính từ pha tải ads; Back và vuốt back bị bỏ qua | `onboarding_config.splash.timing.min_display_ms` (`0` hợp lệ nghĩa là không giữ minimum này) hoặc `ob_splash_min_display_ms` đã gửi (`<= 0` giữ giá trị local); khi remote không gửi thì dùng asset app, rồi `SplashConfig.minDisplayTimeMs`. |
| Mở màn sau inter splash | Show inter sau thời gian tối thiểu. LFO lần đầu và Welcome Back của người dùng cũ: chờ đóng inter. Launcher → app (tắt `welcome_back.enabled`): mở dưới inter. Notification/widget/uninstall: chờ đóng | Override `SplashActivity.nextScreenTiming()`: `NextScreenTiming.AFTER_AD`/`UNDER_AD` (`io.onboardkit.ads`), hoặc `super.nextScreenTiming()` để giữ mặc định. `splash.navigation.next_screen_timing` khác `AUTO`, từ remote hoặc asset app, ưu tiên hơn override này ở mọi lần mở, kể cả notification/widget/uninstall; `UNDER_AD` còn bỏ màn native_fs của splash ở lần mở đó |
| Chờ quảng cáo splash | Tối đa 60 giây sau notification và khi splash có focus | Remote `ob_splash_ad_budget_ms`; không tự thêm timer |
| Remote / ads | Consent → slot + interstitial; remote chạy song song trong SDK và tiếp tục sau splash. | Dùng giá trị asset/cache/remote hiện có; xem mục timing bên dưới. |
| LFO1 preload | `SEQUENTIAL`: interstitial result → LFO1. `PARALLEL`: splash requests → LFO1. | `splash.load.lfo1_preload_mode` |
| Không có mạng | Hiện yêu cầu kết nối, chưa đi tiếp | `SplashConfig.noInternetPromptEnabled = false` để mở offline qua fallback UMP bên dưới; splash lần sau hỏi UMP lại |
| Consent | Timeout mạng 10 giây; form chờ người dùng | Trong Application: `ConsentCenter.configure(ConsentOptions(timeoutMs = ...))` (`com.ads.module.consent`). Giữ hook SDK; `SplashConfig.consentTimeoutMs` không đổi timeout UMP |
| Form UMP khi QA | Debuggable: mọi máy là EEA (`setForceTesting`), không cần hashed id; release: địa lý thật | `ConsentOptions(debug = false)` để debug theo địa lý thật. `configure` thay toàn bộ option; muốn đổi cả timeout dùng một lần `ConsentOptions(timeoutMs = ..., debug = false)` |
| Thông báo | Sau consent và request ads splash, không chờ remote; Android 13+/target33+. Từ chối vẫn đi tiếp, kết quả đã ghi tránh hỏi lại. | `SplashConfig.notificationPermissionEnabled = false` nếu app không gửi thông báo hoặc tự hỏi |
| Ngôn ngữ | 21 ngôn ngữ, hand hint sau 3 giây, hiện nút tick mờ trước khi chọn | `LanguageConfig.languages`: giữ ngôn ngữ đã dịch; remote `lfo.languages.supported_codes` thu hẹp danh sách này, và `defaultCode` bị loại khỏi danh sách đó không được chọn sẵn. Giá trị remote hoặc asset app đặt `tapHintEnabled` và `confirmVisibleBeforeSelect` theo cả hai chiều |
| Back ở LFO | Chưa chọn: bỏ qua Back. Đã chọn: hiện Save, vẫn ở màn ngôn ngữ | `LanguageConfig.saveButtonOnBackEnabled = false`: bỏ qua Back cả sau khi chọn. SETTINGS Back đóng màn |
| Thay native sau chọn ngôn ngữ | Bật; native đầu giữ nguyên đến khi ad thay thế bind được. Nếu ad thay thế không hiện được (no-fill, hoặc không bind kịp), native đầu được reload thay, trừ khi popup xác nhận đã mở, còn ad thay thế fill trễ được swap vào ở tap đổi ngôn ngữ kế tiếp, trước khi native đầu request lại. Sau đó, mọi tap sang ngôn ngữ khác mà không mở popup đều reload native đang hiện qua toàn bộ waterfall, trừ khi native đó còn đang load; tắt việc thay native thì tap đầu cũng reload. Chọn lại ngôn ngữ đang chọn không bao giờ reload. `lfo.confirm_dialog.show_from_tap` từ remote dời mốc mở popup, và reload dời theo, từ tap kế tiếp | `LanguageConfig.secondNativeOnSelectEnabled = false` để tắt việc thay native; reload không có cờ tắt |
| Popup ngôn ngữ | Chọn lại ngôn ngữ hiện tại thì mở ngay. Chọn ngôn ngữ khác chỉ mở từ tổng click thứ 4; click chọn lại vẫn được cộng count. Native request lần đầu khi mở popup | `LanguageConfig.confirmDialogOnReselectEnabled = false` để tắt; SETTINGS không hiện popup |
| Native template | SDK: template `4` cho LFO1/2 trong `SHARED_POOL`, `1` cho LFO classic/Welcome Back/content OB, `2` cho splash inline và Privacy/Goal; CTA ở dưới | Đặt `ad_config.<key>.templateId` để chọn khung và `components` để chọn thứ tự. `ad_remote_config` của backend thắng asset app theo từng field. [Thứ tự ưu tiên](remote-settings.vi.md). |
| System bars | Hiện status/caption bar, ẩn navigation bar | `SystemBarConfig(showStatusBar, showNavigationBar, showCaptionBar)` |
| Click native rồi quay lại OB | Trang content và fullscreen trong pager tự chuyển khi quay lại (`auto_next`) | `"click_action": "none"` trên key của trang (`native_ob1`…) để ở lại, `"reload"`/`"reload_waterfall"` để ở lại và hiện ad thay thế; [Hành động khi click native](remote-settings.vi.md#hành-động-khi-click-native) |
| Click native ở LFO/popup, Welcome Back, OB5 hoặc màn app | Preload ngay khi click/open; quay lại bind ad sẵn có hoặc chờ request đang chạy. Ad thay thế chỉ request tầng all price (`reload`); `reload_waterfall` đi hết các tầng | `click_action` trên key của placement trong ad_config, độc lập refresh theo thời gian; [ví dụ native trong app](#native-ở-màn-app-dùng-placement-constant) |
| Mở lại khi chưa xong flow | Chạy lại Splash → LFO → OB; chỉ bỏ OB khi hoàn thành toàn bộ | Không cần tự lưu cờ first-open/checkpoint trong app |
| Trang native fullscreen | X sau 5 giây, auto-next sau 15 giây từ lúc chọn trang; thời gian background vẫn được tính, thời gian user ở trong ad của trang sau khi click thì không. Shimmer phủ đầy khung native, media toàn khung và CTA ở đáy. | Các trường của `AdFullScreenStepDefinition`; remote `ob_skip_button_delay_sec = -1` giữ delay local |
| Inter cuối onboarding | Tắt (`onboarding.exit_interstitial.enabled` là `false`). Khi được bật: preload lúc vào pager, đợi fill tối đa 8 giây khi hoàn thành; mở màn dưới inter. Notification/widget/uninstall: chờ đóng | `afterOnboardingInterstitialEnabled = true`, hoặc `onboarding.exit_interstitial.enabled: true` qua remote hay asset app, để bật; `AdsConfig.afterOnboardingInterstitialTiming = NextScreenTiming.AFTER_AD` để luôn chờ đóng; để tắt nếu app tự quản lý. Không đưa vào `InterstitialAutoBuffer` |
| Điều hướng OB | Khi bật swipe: OB1 vẫn khóa. OB2/OB3/OB4 và fullscreen khóa khi đang load/bind, chỉ mở sau impression của ads; trang content không có ad để hiện (không unit, premium, no-fill) mở ngay; mỗi lần vào lại trang bắt đầu ở trạng thái khóa. Cờ khóa swipe toàn cục vẫn ưu tiên. Khi OB1 là trang đầu, OB1 có nút back góc trên bên trái; nút, Back và vuốt back trên OB1 quay về LFO, LFO mở lại từ đầu và request lại native của nó. Không trang nào khác có nút: ở đó Back lùi một trang, từ trang đầu thì thoát app. | `BehaviorConfig.lockPagerSwipe`, `swipeCompletesLastStep`, `backNavigatesBack` (`false`: Back thoát app ở mọi trang không có nút back), `onboarding.steps.ob1.back.enabled = false` (remote hoặc asset app) để bỏ nút ở OB1, `lockPortrait`; app ngang cần sửa cả manifest |
| Khoảng cách interstitial | `ERainAdConfig.intervalInterstitialAd = 0` (không giới hạn); chỉ áp nhóm `InterstitialAutoBuffer`, không áp splash/OB/inter tự load | Đặt trước init hoặc dùng `ERainAd.getInstance().setIntervalInterstitialAd(giây)` |
| Giới hạn click interstitial | Tắt (`0`) | `ERainAd.getInstance().setMaxClickAdsPerDay(n)`: mỗi ad unit tối đa `n` click/24 giờ rồi ngừng load/show. Gọi lúc cần, thường sau fetch remote |
| OB5, paywall, resume | `ob_enable_step_ob5 = false`. Bật OB5: mở nếu native đã tải (nằm dưới inter cuối khi inter này được bật), chưa có thì bỏ qua. Với `fromAdConfig()`, OB5 liên kết `native_onboarding_fullscreen_1_4`; key này khi đã khai báo trong `ad_config.json` hoặc remote thì thắng unit đặt trong code. Khi key chưa khai báo thì dùng `ob5Native`, rồi `fullScreenStepNative` (host setup). Paywall chưa nối. [Ads khi quay lại](#ads-khi-quay-lại) theo `app_resume.type` (mặc định `native_resume`); `disableAppResume()` giữ tắt | Đặt ID dưới `native_onboarding_fullscreen_1_4` (`AdsConfig.ob5Native` chỉ áp dụng khi key đó chưa khai báo); chỉ nối paywall khi cần |

UMP lỗi/timeout có thể cho **thử request** trong process qua [fallback AdLogic](../ads/src/main/java/com/ads/module/consent/ConsentCenter.kt), không cấp consent hay đảm bảo fill. Host tắt request bằng `ConsentCenter.setHostConsent(false, false)` từ CMP riêng vẫn được ưu tiên; không tự suy quyền request từ timer/personalization.

Consent là nguồn quyết định quyền request. CMP riêng publish bằng `ConsentCenter.setHostConsent(canRequestAds, personalized)` và đọc bằng `ConsentCenter.canRequestAds()`. Timeout hook không tự cấp consent.

Cài `adProvider = ERainAdProvider()`, hoặc bỏ trống nếu onboarding không có ads. `OnboardingAdProvider` thuộc nội bộ SDK, partner không tự triển khai. Timeout mỗi tier dùng `native.load.tier_timeout_ms` và `interstitial.load.tier_timeout_ms`. Native ngôn ngữ bị gate chặn trước request có thể được request một lần ở màn ngôn ngữ; request đã no-fill không thử lại.

### Field trong JSON mẫu

`ad_config.json` dùng ID test ở mọi placement và để tắt `native_ob1..4`, `native_full1/2`, `native_fs` (bật lên để test các slot đó); `ad_config_debug.json` chỉ chứa `ids` test.

| Field | Giá trị trong mẫu | Cách dùng / phạm vi áp dụng |
| --- | --- | --- |
| `ids` | Ad unit test đúng format | Waterfall gồm các tầng `{"id", "isEnable"}`, cao nhất trước, all price cuối; placement một unit là mảng một tầng. `"id"` cấp placement không được đọc. Thay ID ở file thật khi phát hành; không sửa key placement. Xem [Waterfall](#waterfall-mỗi-placement-một-key). |
| `isEnable` | `true`, trừ `native_ob1..4`, `native_full1/2` và `native_fs` là `false` | Bật/tắt placement: `false` tắt cả waterfall. Mỗi tầng trong `ids` có `isEnable` riêng để chỉ dừng tầng đó. |
| `enable_ua_check` | Có `true` và `false` | `true` yêu cầu paid/non-organic; chưa có kết quả Adjust thì mặc định organic. Liên kết chuẩn `AdsConfig.fromAdConfig()` áp gate này cho placement OB tương ứng, gồm native LFO/OB và inter cuối OB. Không dùng Adjust thì đặt `false` cho các placement muốn hiện. |
| `reloadIntervalSeconds` | Banner: `30` | Số dương đặt nhịp auto-reload (giây) cho mọi banner gắn placement, kể cả splash, khi `banner.reload.auto_enabled` là `true` (mặc định). Thiếu, `0` hoặc sai dùng giá trị host/SDK (15000ms). Cách gắn ở màn app: [Banner ở màn app](#tích-hợp-bổ-sung). |
| `colorCTA` | `"default"` | Giữ màu template; thay màu khi cần tùy biến native. |
| `colorBackground` | không có | Tuỳ chọn, màu nền card native, ví dụ `"#FFF4E5"`. Không có, `null`, `""` hoặc `"default"` thì giữ nền sẵn có của layout; card giữ nguyên bo góc và viền. Áp dụng cho mọi khung native và skeleton lúc chờ ad. |
| `colorAdBadge` | không có | Tuỳ chọn, màu nền badge Ad, ví dụ `"#007AFF"`; trên badge thì ưu tiên hơn `colorCTA`. Không có, `null`, `""` hoặc `"default"` thì lấy theo `colorCTA`, rồi tới layout. Áp dụng cho mọi khung native và skeleton lúc chờ ad. |
| `colorAdBadgeText` | không có | Tuỳ chọn, màu chữ badge Ad, ví dụ `"#FFFFFF"`. Không có, `null`, `""` hoặc `"default"` thì giữ màu chữ của layout. Áp dụng cho mọi khung native; skeleton lúc chờ ad vẫn ẩn chữ. |
| `heightCTA` | Đa số native `45`, `native_splash` `44`, popup `36` | Chiều cao CTA (dp); SDK dùng `44` (chiều cao nút của các template) nếu bỏ field và ép giá trị vào khoảng 36–52 khi áp dụng. |
| `templateId` | không có | Tuỳ chọn, chọn template native của SDK cho placement: `1` = card LFO classic (media 1.68:1 full chiều ngang), `2` = card media-left 4:3, `3` = card 1.91:1, `4` = card LFO rộng theo DashDrama (media khoảng 3.14:1). Slot cao đúng bằng card. Không có, `null` hoặc số lạ thì giữ mặc định của slot: `4` cho LFO1/2 trong `SHARED_POOL`, `1` cho LFO classic/Welcome Back và OB content, `2` cho Privacy/Goal và slot đáy splash. Popup ngôn ngữ và native fullscreen giữ khung cố định. Xem [Template native](remote-settings.vi.md#template-và-cta-để-uamo-thử-nghiệm). |
| `components` | `["icon_headline", "body", "media", "cta"]` | Thứ tự khối từ trên xuống; khối thiếu bị ẩn, mảng rỗng giữ nguyên XML. Không có thì thứ tự mặc định đặt CTA ở dưới. Template `1`, `3`, `4` và [native màn app](#native-ở-màn-app-dùng-placement-constant) theo thứ tự này; template `2`, popup ngôn ngữ và native fullscreen luôn hiện đúng như layout vẽ sẵn. |
| `app_resume_load_delay_ms` | `open_resume`: `2000` | App phải ở background bao lâu thì ad của mọi `app_resume.type` mới được request; quay lại sớm hơn thì không hiện gì. Màn resume của `SHARED_POOL` dùng `native_flow.pool.resume.min_background_ms` thay cho giá trị này ([Native flow SHARED_POOL](#native-flow-shared_pool)). |
| `click_action` | `"auto_next"` ở `native_ob1..4`, `native_full1/2` và `native_fs`; `"reload"` ở mọi native khác | Hành động khi quay lại sau click ad native. Áp cho mọi tầng trong `ids`; chỉ đặt trong `ad_config.json` hoặc remote (`ad_config_debug.json` bỏ qua field này). Xem [Hành động khi click native](remote-settings.vi.md#hành-động-khi-click-native). |

### Waterfall: mỗi placement một key

Mỗi placement là một key và liệt kê các tầng trong `"ids"`, tầng giá cao nhất trước, tầng all price cuối; mỗi tầng là `{"id": "...", "isEnable": true|false}`, tầng không ghi `isEnable` thì bật. `isEnable` của placement là công tắc tổng. Muốn tạm dừng một tầng thì đặt `isEnable: false` cho tầng đó: ID vẫn nằm nguyên chỗ, bật lại không cần gì thêm. Placement chỉ một unit (banner, `inter_back`, `open_resume`…) là mảng một tầng, không ghi `isEnable` trong tầng, `"ids": [{ "id": "..." }]`, vì `isEnable` của placement đã bật/tắt nó. `"id"` cấp placement là field lạ, bị bỏ qua kèm cảnh báo, nên placement đó không có ad unit. Mọi field khác áp cho mọi tầng. Trong `ad_config.json`, placement không ghi `isEnable` thì tắt (khác với tầng, có `isEnable` mặc định bật) và thiếu `enable_ua_check` thì là `false`; trong document remote, field không khai báo giữ giá trị của tầng cấu hình bên dưới.

```json
"native_ob1": {
  "ids": [
    { "id": "ca-app-pub-xxx/2222222222", "isEnable": false },
    { "id": "ca-app-pub-xxx/1111111111", "isEnable": true }
  ],
  "isEnable": true,
  "click_action": "auto_next"
},
"banner_home": {
  "ids": [{ "id": "ca-app-pub-xxx/3333333333" }],
  "isEnable": true
}
```

`ids` chỉ nhận object tầng: chuỗi trần bị bỏ qua kèm cảnh báo, `ids` không rỗng mà không có tầng hợp lệ nào thì bị bỏ qua và dùng tầng cấu hình bên dưới; còn `"ids": []` ghi rõ là waterfall rỗng hợp lệ, nên placement không request. Tầng sai định dạng chỉ bị bỏ riêng tầng đó. Mọi tầng đều tắt thì placement không request. Giữa các tầng cấu hình (remote > `ad_config.json` > code), `ids` ở tầng trên thay toàn bộ mảng của tầng dưới, nên Firebase phải giữ đủ mảng kèm `isEnable` của từng tầng. ID trùng bị loại; dùng ID riêng khi cần kiểm tra từng tầng.

**Breaking change — nâng cấp từ key `_high`.** SDK cũ đọc tầng từ các key rời `<key>_high`, `<key>_high1`…`<key>_high9`. Các key đó không còn được đọc: parser log cảnh báo kết thúc bằng `not read as floors; move their ids into the base key's "ids"`, và placement chỉ request key của chính nó. `"id"` cấp placement cũng không còn được đọc. Mọi tầng giờ báo về dưới đúng một key, nên event load và revenue không còn tách giữa `<key>` và `<key>_high`.

1. Trong `ad_config.json` và value Firebase `ad_remote_config`, chuyển ID của từng `<key>_high*` vào `<key>.ids` dạng `{"id", "isEnable"}` theo thứ tự cũ (`_high`, `_high1`…`_high9`), giữ `isEnable` cũ của tầng đó; `<key>.id` cũ thành tầng cuối. Key nào đã dùng dạng chuỗi cũ `"ids": ["a", "b"]` (SDK cũ chấp nhận) thì đổi từng chuỗi thành object tầng theo đúng thứ tự, đặt trước `id` của chính key đó: SDK cũ request `<key>_high`, `<key>_high1`…`<key>_high9`, rồi `<key>`, trong mỗi key là `ids` rồi mới tới `id`, bỏ qua ID trùng. Xoá các entry `_high*`. Mọi `"id": "X"` còn lại, kể cả placement một unit và mọi key của `ad_config_debug.json`, đổi thành `"ids": [{"id": "X"}]`.
2. Trên Firebase, publish `ad_remote_config` định dạng mới dưới condition app version ≥ bản đầu tiên dùng SDK này; các bản cũ giữ value cũ. Condition này là bắt buộc. App dùng SDK 5.5.6 trở lên mà nhận phải định dạng mới thì không crash: nó bỏ qua `ids` và giữ waterfall đóng trong APK, nhưng không nhận thay đổi ID từ remote nữa. App dùng SDK 5.5.5 trở xuống thay toàn bộ cấu hình bằng document remote và mất mọi ad unit.
3. Lần mở đầu tiên sau khi cập nhật, value định dạng cũ đang cache có thể còn hiệu lực một phiên. `id` và các entry `_high*` của nó không được đọc, nên mỗi placement giữ waterfall trong `ad_config.json` của app đến lần fetch kế tiếp; các field khác của value vẫn áp dụng.

### Native ở màn app dùng placement constant

<details>
<summary>Mở ví dụ native và preload cho màn app</summary>

Khai báo slot trong XML của màn (mỗi native/banner dùng một slot riêng). OB tự quản lý slot của OB:

```xml
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/ad_slot"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

Gọi SDK tại màn sau consent, khi `AppCompatActivity` resumed:

**Chưa dùng Adjust:** giữ `enable_ua_check` của `native_home` là `false` trong `ad_config.json` (mẫu đã để sẵn) để slot mẫu có thể hiện; `ad_config_debug.json` chỉ chứa `ids` test. Giữ ID test khi QA.

```kotlin
import android.widget.FrameLayout
import com.ads.module.helper.adnative.NativeAdHelper

val container = findViewById<FrameLayout>(R.id.ad_slot)
NativeAdHelper.forPlacement(this, this, AppAdPlacement.NATIVE_HOME, container)
```

SDK tự đọc waterfall, `isEnable`, `enable_ua_check`, CTA style của placement. Thêm `layoutId` để đổi template; mặc định là `com.ads.module.R.layout.custom_native_admob_medium` (không có media — cần media dùng `custom_native_admob_free_size`). Layout riêng giữ root `NativeAdView`, `ad_container`, `block_icon_headline`, asset IDs và nhãn Ad.

Giữ một helper/slot/view, gọi `show()` để request và bind ad mới. Fragment dùng Activity + `viewLifecycleOwner`. Click reload mặc định bật; chỉ đặt `"click_action": "none"` trên key của placement khi app tự điều hướng sau click-return.

Preload cho Main: `NativeAdManager.preload(applicationContext, AppAdPlacement.NATIVE_HOME, NativeAdConfig.forPlacement(AppAdPlacement.NATIVE_HOME, layoutRes))` trong `SplashActivity.onRemoteFetched()`. Helper cùng placement lấy ad khi hiện; ad quá 60 phút bị tải lại. Xem [Native preload](../ads/README.md#native-preload-repeated-show-and-refresh).

Hành vi native helper:

- Helper có placement (`forPlacement`, hoặc tham số `placement` của constructor `NativeAdHelper`) bind và refill từ store của placement đó, nên hãy preload bằng đúng key placement như trên. Ad preload theo chuỗi unit ID ghép (`NativeAdPreload.getInstance().preload(activity, config)`) bị để lại, helper tự request ad của mình, trừ khi bạn truyền key đó như dưới đây. Key truyền vào `setEnablePreload(enabled, key)` quyết định store trước cả placement, kể cả key chuỗi unit ID ghép (`NativeAdPreload.getInstance().keyOf(config)`).
- `bindAvailable()` trả `false` và huỷ helper khi user đã mua, consent bị rút hoặc cổng UA từ chối; ad đang hiện bị gỡ. Ad preload chưa dùng vẫn nằm trong store.
- Khi quay lại sau click ad, helper bind ad thay thế đã tải lúc click hoặc chờ nó; nếu ad thay thế đó lỗi thì ad hiện tại được giữ và không có request thứ hai. Sau thay đổi cấu hình như xoay màn, helper khôi phục ad của nó, hoặc join lượt tải nó đang chờ, mà không request mới; slot đã kết thúc bằng no-fill vẫn trống.

</details>

### Interstitial ở màn app dùng placement constant

<details>
<summary>Mở ví dụ preload/show interstitial cho màn app</summary>

Preload sau consent, show tại lần điều hướng mới. Không áp mẫu/AutoBuffer cho `inter_splash`, `inter_after_ob3` vì OB tự quản lý.

```kotlin
import com.ads.module.helper.interstitial.InterstitialAdManager

InterstitialAdManager.load(applicationContext, AppAdPlacement.INTER_BACK)

InterstitialAdManager.show(this, AppAdPlacement.INTER_BACK) { goNext() }
```

Chỉ cần điều hướng thì dùng dạng lambda; cần thêm sự kiện (`onShowed`/`onClosed`/`onSkipped`/`onClicked`) thì dùng overload nhận `InterShowCallback`. App không cần file wrapper nào.

SDK tự đọc waterfall, `isEnable`, `enable_ua_check`, consent/premium, interval và readiness của placement. Chỉ điều hướng ở `onComplete`, chạy đúng một lần kể cả thiếu ad/show lỗi; không dùng `onClosed`, không tự kiểm tra `canShow()` trước. `load` không request lại nếu đang tải/đã có ad. Có ad thì chờ dialog khoảng 800 ms rồi show.

SDK mặc định `AfterDismiss`. Mẫu `PartnerApp` gọi `ERainTuning.install()` nên dùng `UnderAd`: điều hướng khi ad đang hiện. Truyền `nextAction = InterNextAction.AfterDismiss` khi cần đóng màn chứa ad hoặc mở camera/audio/video. Giữ nguyên timing đã thiết lập.

Tự giữ sẵn inter: [InterstitialAutoBuffer](../ads/README.md#automatic-interstitial-preload) `configure` sau `ERainAd.init`, `start` ở màn nội dung đầu tiên; show như trên. Khoảng cách/click cap ở [bảng mặc định](#mặc-định-của-luồng).

</details>

### Reward tại màn app

Gọi trên main thread từ Activity đang resumed, sau consent; ví dụ tại nút xem quảng cáo:

```kotlin
import com.ads.module.helper.reward.RewardAdManager

RewardAdManager.loadAndShow(
    this, AppAdPlacement.REWARD_EXAMPLE,
    onSuccess = {
        closeLoading()
        grantReward()
    },
    onFailed = { closeLoading() },
)
```

Hoặc preload trước, rồi chỉ show ad đã có khi bấm nút:

```kotlin
RewardAdManager.preload(applicationContext, AppAdPlacement.REWARD_EXAMPLE)

// Tại nút xem quảng cáo:
RewardAdManager.show(this, AppAdPlacement.REWARD_EXAMPLE) { earned ->
    if (earned) grantReward()
}
```

`preload` và `load` dùng chung cache/request theo placement. `show` lấy ad sẵn có; `loadAndShow` dùng cache, chờ request đang chạy hoặc tải khi chưa có. Không tự refill. `onSuccess` chạy sau khi đã nhận reward và ad đóng; `onFailed` xử lý các kết quả còn lại. Lấy kết quả từ callback SDK, không suy đoán bằng timer.

Mặc định: 30 giây/tầng tải, một cache/request theo placement, không tự refill. `show` thiếu ad trả `false`; `loadAndShow` gọi trùng khi placement đang chờ/đang hiển thị trả `onFailed`. Lambda/Runnable chốt kết quả theo reward nhận **trước lúc đóng**. Cần từng sự kiện, kể cả reward từ mediation đến sau khi đóng, dùng [`RewardShowCallback`](../ads/src/main/java/com/ads/module/helper/reward/RewardAdManager.kt); kết quả đã hoàn tất không bị đổi lại.

### Tích hợp bổ sung

| Nhu cầu | Mặc định / cách cấu hình |
| --- | --- |
| Tắt một slot | `isEnable: false` ở key placement là tắt cả waterfall, cho mọi format; thử asset cần khởi động lại. LFO native thứ hai có fallback ở bước 4. |
| Thêm waterfall | Các tầng `{"id", "isEnable"}` trong `ids` của key, cao nhất trước, all price cuối ([định dạng](#waterfall-mỗi-placement-một-key)). Banner splash chạy qua mọi tầng của `banner_splash` như các placement khác. |
| CTA/native style | Giữ đủ field như example; [bảng field JSON](#field-trong-json-mẫu) giải thích giá trị và nơi áp dụng. Bước 4 đã nối `templateId` và `components` vào native template. |
| Banner ở màn app | `BannerAdHelper.forPlacement(this, this, "banner_home", container)`; thêm đối số `bannerType`, ví dụ `BannerType.Collapsible()` ([các loại](../ads/src/main/java/com/ads/module/helper/banner/BannerType.kt)). Đổi loại: `flagUserEnableReload = false`, `cancel()` helper cũ rồi tạo mới. Refresh của SDK bật mặc định và `reloadIntervalSeconds` đặt nhịp của nó, nên hãy tắt refresh trên console AdMob cho ad unit của mọi tầng để tránh refresh hai lần; muốn tắt refresh của SDK, xem [`banner.reload.*`](remote-settings.vi.md#scope-của-behavior). |
| UA/Adjust | Điền resource và dùng wiring ở [bước 5](#adjust-token-và-kiểm-tra). `enable_ua_check` giữ giá trị example; xem phạm vi áp dụng trong bảng field JSON. |
| JSON từ Firebase | [Publish ba parameter String](firebase-integration.vi.md#remote-json): `ad_remote_config`, `ad_behavior_config`, `onboarding_config`. Cài `FirebaseAdConfigSource()` một lần sau assets; splash SDK tự refresh. Key khai báo trong `ad_remote_config` của backend hoặc `ad_config.json` của app ưu tiên hơn ad unit ID ghi trong code. [Custom fallback local](firebase-integration.vi.md#local-defaults) là tùy chọn. |
| Firebase Analytics | Cài `suite-firebase`, đăng ký `Tracker.addSink(FirebaseSink())` ngay sau `Tracker.install`; chọn consent policy theo [hướng dẫn Firebase](firebase-integration.vi.md#consent-ban-đầu). |
| Ads khi quay lại | `app_resume.type` chọn `native_resume` (mặc định), `inter_resume`, `open_resume` hoặc `none`; key `ad_config` của dạng đó phải bật kèm ID thật. `disableAppResume()` tắt mọi dạng. Làm theo [Ads khi quay lại](#ads-khi-quay-lại). |
| App có premium / paywall | Làm [BillingKit](billing-integration.vi.md) / [PayKit](paywall-integration.vi.md) và [hook billing chạy song song](../onboardkitorigin/README.vi.md#tích-hợp-tùy-chọn). `onInitBilling()` mặc định trống; gọi install billing chưa có nghĩa đã khôi phục premium. |
| Đổi ngôn ngữ từ Settings | `registerForActivityResult(StartActivityForResult())`, rồi `launch(ObLanguageActivity.intentFor(activity, LanguageScreenMode.SETTINGS))` (kiểu trong `io.onboardkit.ui.language`). `RESULT_OK`: lấy `ObLanguageActivity.RESULT_LANGUAGE_CODE`, lưu như bước 5 và `recreate()`; Back không trả mã. Màn này không ads. `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)` không trả result; đọc lựa chọn qua `OnboardingSdk.selectedLanguage()`. |
| Entry từ notification/widget | Dùng `SplashEntry` và giữ passthrough trong listener; xem [OnboardKit](../onboardkitorigin/README.vi.md#tích-hợp-tùy-chọn). Không cần các entry này cho launcher thông thường. |

Với liên kết placement hiện hành ở bước 4, dùng remote JSON không cần override splash thêm:

```kotlin
package com.example.app

import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity()
```

Splash chạy consent, fetch remote và billing song song. Slot banner/native và interstitial được request ngay khi consent kết thúc, dùng cấu hình và entitlement hiện có; không đợi remote hoặc billing. Remote đã cache hoặc đã về vẫn ưu tiên hơn asset. Job refresh thuộc SDK, tiếp tục sau khi splash đóng, với thời gian chờ nền ít nhất 60 giây. Các lần đọc sau nhận giá trị mới; request, timer và quyết định chuyển màn đã chốt không chạy lại. `SAME_TIME` và `ALTERNATE` cùng dùng thứ tự này. `onRemoteFetched()` chỉ chạy khi splash còn sống; tích hợp cần sống cùng process dùng `SettingsRegistry.addFetchListener`.

### Ads khi quay lại

<details>
<summary>Mở các bước bật ads khi quay lại</summary>

App ở background đủ `open_resume.app_resume_load_delay_ms` (mặc định 2000ms) thì SDK request ad của dạng đang chọn; quay lại sớm hơn thì không request, không hiện gì. Khi một lượt `SHARED_POOL` điều hướng ad khi quay lại (trong flow và sau flow trong cùng process), màn resume của pool dùng `native_flow.pool.resume.min_background_ms` (mặc định 2000ms) thay thế; xem [Native flow SHARED_POOL](#native-flow-shared_pool). `app_resume.type` trong `ad_behavior_config` chọn đúng một dạng, mặc định `native_resume`:

| `app_resume.type` | Key `ad_config` | Khi quay lại |
|---|---|---|
| `native_resume` | `native_resume` | Mở ngay màn native resume: ảnh, card chữ có nút Start, ad ở dưới; ad chưa về thì hiện skeleton. Start hiện `inter_resume` khi key đó được khai báo và bật (load-and-show kèm loading dialog, `interstitial.load_and_show.wait_timeout_ms`) rồi đóng màn khi ad tắt; key tắt hoặc không khai báo, hay ad không hiện được, thì Start đóng màn ngay. Back đóng màn không kèm ad. Cả hai đều về đúng màn user đã rời. Ad đang hiện được giữ lại: lần quay lại sau hiện lại ad đó ngay, ad request trong background về thì thay vào. |
| `inter_resume` | `inter_resume` | Load-and-show có dialog loading, chờ tối đa `interstitial.load_and_show.wait_timeout_ms` (mặc định 8000ms). |
| `open_resume` | `open_resume` | App-open. |
| `none` | — | Không hiện gì. |

Key của dạng đang chọn tắt (`isEnable: false`) hoặc chưa khai thì lần quay lại không có ad; SDK không chuyển sang dạng khác. Muốn đổi dạng thì đổi `app_resume.type`.

1. **Bước 4:** `AdsConfig.fromAdConfig()` đã liên kết `native_resume`, `inter_resume` (nút Start của màn resume) và `open_resume`. Nếu tự dựng `AdsConfig(...)`, truyền `resumeNative = NativeAdUnit(...)` / `resumeInterstitial = InterstitialAdUnit(...)` / `appResume = InterstitialAdUnit(...)` trừ khi `ad_config.json` hoặc `ad_remote_config` của backend khai báo key đó.
2. **Chỉ với `open_resume`**, bước 5, trong khối `apply` của `ERainAdConfig`: `idAdResume = AdGate.adUnitIds(AppAdPlacement.OPEN_RESUME).firstOrNull().orEmpty()` (`AdGate` là `com.ads.module.helper.AdGate`). `open_resume` khai trong `ad_config.json` hoặc `ad_remote_config` của backend thì không cần seed.
3. **`ad_config`:** khai key của mọi dạng có thể chọn, **bật kèm ID thật** trong `ids`. `native_resume` nhận các field native (`templateId`, `components`, `click_action`, màu CTA; mặc định template 1 và `reload`).
4. **Intent ra ngoài** (browser/share/review): gọi `AppOpenManager.getInstance().disableAdResumeByClickAction()` ngay sau `startActivity(...)` để bỏ qua lần quay lại đó, với mọi dạng. `disableAppResume()`/`enableAppResume()` là công tắc cả process cho mọi dạng.

Màn bị loại trừ áp dụng cho mọi dạng. Splash/OB5/Welcome Back tự loại trừ; chỉ đăng ký thêm màn nhạy cảm của app bằng `disableAppResumeWithActivity`. LFO/trang nội dung OB có thể hiện ads khi quay lại, trừ fullscreen, lúc chuyển trang, lúc mở popup và sau khi click vào ad onboarding (trừ khi remote đặt `app_open.presentation.skip_after_ad_click` là `false`).

**Màn native resume.** Khai ảnh và chữ trong `onboardKitConfig { resumeScreen = ResumeScreenConfig(titleRes = …, subtitleRes = …, imageRes = …) }`, giống cách khai các bước OB; `title`/`subtitle` nhận `CharSequence` và ưu tiên hơn resource, field nào không khai thì giữ default SDK. Màu chữ nút Start lấy từ `native_resume.start_button.color` trong `onboarding_config` (`""` giữ màu của layout); đổi chữ nút bằng cách override string `ob_start`. Muốn đổi layout thì override `res/layout/ob_resume_screen.xml`. Chỉ bắt buộc `ob_resume_ad` (`FrameLayout`) và một Button/TextView cho Start (nên dùng `@id/ob_resume_start`); `ob_resume_image`, `ob_resume_title`, `ob_resume_subtitle`, `ob_resume_ad_block` là tùy chọn. SDK giữ logic request, bind và đóng màn. `native_resume.start_button.delay_ms` điều khiển lúc Start hiện ở cả `PER_SCREEN` và `SHARED_POOL`.

Delay/gate của app-open xem [app-open](../onboardkitorigin/README.vi.md#app-open-khi-quay-lại-app).

</details>

### Native flow SHARED_POOL

<details>
<summary>Mở hướng dẫn SHARED_POOL</summary>

`native_flow.type` chọn flow chạy sau splash. `PER_SCREEN`, mặc định, là flow mà phần còn lại của tài liệu này mô tả. `SHARED_POOL` tái hiện flow mở lần đầu của DashDrama 1.0.6, app tham chiếu mà pool được mô phỏng theo: LFO → pager onboarding → Goal, với bước Privacy của SDK trước Goal (Privacy và Goal chỉ khi được bật). Các chặng này chạy trong một activity SDK, và mọi slot native trong đó lấy ad từ một pool dùng chung cho cả process. Splash giống nhau ở cả hai flow, trừ việc splash không preload LFO1 cho lượt `SHARED_POOL` (xem LFO bên dưới). Key, thứ tự ưu tiên, alias `ob_native_reuse_enabled`, user property `ob_native_flow` và mọi default của `native_flow.pool` nằm ở [Kiểu native flow](remote-settings.vi.md#kiểu-native-flow); phần này mô tả flow làm gì.

**Pool quyết định thế nào.** Pool giữ tối đa một ad cho mỗi placement và giữ nó qua các màn.

- **Màn owner.** Màn đang ở trước xin pool placement của nó và trở thành owner, trừ khi placement bị từ chối cho cả lượt chạy (premium, ads hoặc placement bị tắt, không có ad unit, UA gate). Từ chối tạm thời (offline, consent chưa trả lời, request đang bị giữ) vẫn đưa màn thành owner.
- **Load POLITE và FORCED.** Mở màn, chọn trang và quay lại màn là load POLITE: bị bỏ qua khi pool đang có request chạy, và load bị bỏ qua thì bỏ luôn, không xếp hàng (`parity.retry_skipped_polite`). Tap ngôn ngữ, lựa chọn ở Privacy hoặc Goal và preload trang full kế tiếp là load FORCED: chúng bỏ qua gate. Preload trang full là FORCED vì nó được gọi cùng lúc với request của chính trang hiện tại, nên nếu là POLITE thì lần nào cũng bị gate chặn; nó không bao giờ đổi owner. Cả hai loại đều không request gì khi placement đã giữ ad hoặc đang load.
- **Gate một request.** Gate chỉ mở khi không placement nào có request đang chạy, nên load POLITE không bắt đầu khi đang có request khác chạy. Việc kiểm tra chạy ngoài main thread và có thể trễ một frame (`parity.async_gate`). Load FORCED, refresh khi quay lại và load splash mà pool nhận lại bỏ qua gate; riêng refresh của màn resume là ngoại lệ, chờ gate như một load POLITE. Refresh được tính là request đang chạy.
- **Bộ chọn.** Mọi slot hiện cùng một lựa chọn: (1) ad của owner, khi pool có ad cho nó; (2) nếu không, ad mới nhất chưa màn nào hiển thị; (3) nếu không, ad được hiển thị gần nhất; (4) nếu không, shimmer khi có request đang chạy, và ẩn slot khi không có. Màn chưa có ad của mình thì mượn ad của placement khác, và ad của chính nó thay ad mượn ngay khi fill.
- **Refresh khi quay lại.** Khi một màn trở lại phía trước (từ background, từ click ad hoặc từ màn resume), nó request lại ad mình đang sở hữu từ tầng đầu trong khi ad đó vẫn hiện: fill thì thay, no-fill thì giữ. Đây là mô hình refresh của SDK, không phải của DashDrama, nơi refresh không được tính là request đang chạy (`parity.refresh_marks_in_flight`).
- **Hết hạn và thời gian chờ mỗi tầng.** Cả hai là giá trị của SDK, không phải key của pool: ad cũ hơn `native.cache.max_age_ms` (một giờ) bị bỏ và lần kế tiếp một màn xin placement đó sẽ request lại; tầng không trả lời trong `native.load.tier_timeout_ms` (30 giây) được tính là lỗi, nên tầng kế tiếp được request.
- **Các tầng.** Một request đi lần lượt các tầng đang bật trong `ids` của key. ID lặp lại được request lại sau no-fill (`parity.keep_duplicate_ids`), khác `PER_SCREEN` là bỏ ID trùng.

**Bật flow.**

- Đặt `native_flow.type` thành `"SHARED_POOL"` trong `onboarding_config`, qua remote hoặc asset app, hoặc gửi key phẳng `ob_native_reuse_enabled = true`. [Kiểu native flow](remote-settings.vi.md#kiểu-native-flow) giải thích thứ tự ưu tiên và vì sao bản cài mới mà lượt fetch đầu chưa về chạy theo asset hoặc default SDK.
- Giữ provider có sẵn `adProvider = ERainAdProvider()`; không có nó thì mọi lượt chạy là `PER_SCREEN`.
- Kiểu được quyết định một lần mỗi lượt chạy: splash hỏi trước, để biết có preload LFO1 hay không, và bước chuyển sang màn ngôn ngữ dùng đúng câu trả lời đó, kể cả khi một lượt fetch remote về giữa chừng. Mọi giá trị `native_flow.pool` được đọc lúc splash mở màn ngôn ngữ. Cả hai giữ nguyên trong lượt đó; giá trị fetch về sau áp dụng từ lượt chạy kế tiếp. Mỗi lượt chạy có mở màn ngôn ngữ đặt user property `ob_native_flow` và ghi `native_flow_type=…` vào `OB_FLOW`.
- Không cần sửa code: flow dùng cùng key `ad_config`, `OnboardKitSetup`, layout nội dung và layout Privacy/Goal như `PER_SCREEN`. Welcome Back và màn chọn ngôn ngữ trong app vẫn là `PER_SCREEN`.

**Từng màn.** Placement được gọi theo key `ad_config` ở [bước 2](#2-thêm-id-và-hai-file-json).

| Màn | Placement | Khi nào request | Hành vi |
| --- | --- | --- | --- |
| LFO | `native_lang` tới lần tap đầu, sau đó `native_lang_alt`; `native_popup_lang` khi popup xác nhận đang mở | Khi mở: POLITE `native_lang`. Splash không preload LFO1 cho lượt `SHARED_POOL`, nên LFO tự request nó, như DashDrama; nếu LFO1 vẫn được preload, LFO nhận preload đó trước (ad đã sẵn thì hiện mà không request mới, load đang chạy thì join, load còn trong queue thì bị huỷ), và lần resume ngay sau khi mở không refresh ad splash đã nhận mà chưa hiển thị. Mỗi tap ngôn ngữ: FORCED `native_lang_alt`. Khi quay lại: refresh key hiện tại, rồi POLITE trên key đó. | Ngôn ngữ đã lưu được chọn sẵn; không có thì không chọn gì. Nút xác nhận ẩn tới `native_flow.pool.lfo.confirm_button.reveal_delay_ms` (3 giây) sau lần tap đầu. Done hiện interstitial splash còn lại khi `lfo.exit.reuse_splash_inter` là `true`; sau khi ad đóng (`native_flow.pool.exit.next_screen_timing`, `AFTER_AD`) ngôn ngữ được lưu và áp dụng (`native_flow.pool.locale_apply`, mặc định áp ngay trên màn), ad của trang đầu được preload FORCED nếu trang đó là trang full, rồi chặng kế tiếp mở; trang nội dung, Privacy hoặc Goal tự xin ad của mình khi mở. `native_lang` bị từ chối cho cả lượt chạy thì ẩn slot. Khi mở mới, `native_flow.pool.lfo.loading_dialog.duration_ms` (mặc định `0`, tắt) giữ một card "Loading languages" có thanh tiến trình đè lên header và danh sách, không bao giờ đè ad; danh sách không nhận tap tới khi hết giờ. Thời gian đứng yên khi app ở background và giữ nguyên qua recreate; back từ OB1 không hiện lại. |
| Trang nội dung của pager | `native_obN` của trang; trang có key tắt thì không có slot | Khi chọn trang: POLITE trên key của trang, kèm preload FORCED key của trang kế tiếp nếu trang kế tiếp là trang full. Khi pager trở lại phía trước: refresh key của owner, rồi POLITE trên key của trang hiện tại. | Khi trang được dựng sẵn ngoài màn hình, slot hiện skeleton tĩnh (không chạy hiệu ứng) của template ad, nên trang trượt vào với layout cuối cùng thay vì khối ad cao dần từ 0 sau khi trượt; ad thật bind khi trang đã resume. Chỉ chuyển trang bằng điều khiển của chính trang; vuốt bị khoá. |
| Trang full | `native_fullN` của trang | Như trang nội dung. Chỉ trang full được preload trước một màn, dạng FORCED (vì được gọi cùng lúc với request của chính trang trước, nên preload POLITE sẽ luôn bị gate bỏ qua; nó không request gì nếu trang full đã giữ ad hoặc đang load, và không bao giờ đổi owner): khi trang trước nó được chọn, hoặc lúc Done ở LFO nếu trang full đứng đầu. Tới trang khi preload đó vẫn đang load thì trang không gửi request thêm (lần xin ad của nó bị gate bỏ qua) và hiện ad mới nhất của pool tới khi ad của chính nó fill. | X hiện sau `native_flow.pool.onboarding.fullscreen.skip.delay_ms` (2 giây) kể từ lần resume đầu của trang; trang tự đi tiếp sau `native_flow.pool.onboarding.fullscreen.auto_next.delay_ms` (10 giây) kể từ lúc được chọn, tính cả thời gian ở background. Không có gì để hiện và không có request đang chạy, hoặc key của trang bị từ chối cho cả lượt chạy, thì trang đi tiếp ngay. Ad khi quay lại không bao giờ mở đè lên trang này. |
| Privacy | `native_select` khi mở, `native_select_alt` ở mỗi lần đổi consent | Khi mở: POLITE `native_select`. Mỗi lần tick hoặc bỏ tick: FORCED `native_select_alt`. Khi quay lại: refresh key đang sở hữu, rồi POLITE trên key xin gần nhất. | Continue ẩn tới `native_flow.pool.select.next_reveal_delay_ms` (3 giây) sau lần đổi consent đầu và chỉ dùng được khi consent đang được tick. Continue mở Goal, và Goal tự xin ad của mình bằng POLITE khi mở. Chi tiết: [Privacy → Goal](privacy-goals-screen.vi.md#khi-chạy-shared_pool). |
| Goal | `native_select` khi mở, `native_select_alt` ở mỗi lần tap lựa chọn | Như Privacy, lựa chọn là tap option. | Next ẩn tới cùng khoảng trễ sau lần tap đầu và dùng được khi đã chọn ít nhất `minSelection` option. Next ghi lại goal, hiện interstitial splash còn lại khi `native_flow.pool.goals.exit_reuse_splash_inter` là `true`, rồi hoàn tất onboarding, không có paywall. |
| Màn resume | `native_resume` | Mọi request đều POLITE, không bao giờ vượt gate. Khi mở và mỗi lần quay lại: refresh `native_resume` đang giữ, chỉ khi không có request nào khác của pool đang chạy (nếu có thì log `gate_skip refresh key=native_resume`); không refresh thì POLITE `native_resume`. | Mở khi quay lại app nếu `app_resume.type` là `native_resume` và app đã ở background ít nhất `native_flow.pool.resume.min_background_ms` (2 giây), trong flow và sau flow trong cùng process. Màn này không bao giờ thành owner, nên hiện cùng lựa chọn với mọi slot khác: trong flow thường là ad của màn hiện tại, sau flow là ad còn lại mới nhất chưa được hiển thị, nếu không có thì ad được hiển thị gần nhất. Trong lúc người dùng ở trang đích của ad, ad đang giữ được pause; quay lại sẽ vẽ lại chính ad đó và không request ad thay thế. Start đi qua `inter_resume` như màn classic; Back đóng màn. |

Không có Privacy → Goal thì trang cuối của pager kết thúc flow, có paywall nếu app đã nối paywall, và không bao giờ hiện interstitial cuối pager. Back hệ thống không làm gì ở mọi chặng.

**Không mang sang từ `PER_SCREEN`.** Preload LFO1 ở splash, bàn tay gợi ý ở LFO và việc đưa ngôn ngữ thiết bị lên đầu, swap LFO2 và các timer của nó, `click_action` (click native không chuyển trang cũng không reload), OB5, vuốt pager, interstitial cuối pager (`inter_after_ob3`, bất kể `onboarding.exit_interstitial.enabled`) và các khoảng trễ fullscreen classic (`onboarding.fullscreen.skip.delay_ms`, `onboarding.fullscreen.auto_next.delay_ms`). Hai tính năng SDK được giữ, cùng key classic:

- Popup xác nhận ngôn ngữ (`lfo.confirm_dialog.enabled`, `lfo.confirm_dialog.show_from_tap`, tap lại hàng đang chọn). Khi mở, popup sở hữu pool với `native_popup_lang`; đóng popup thì trả quyền owner về LFO.
- Nút back OB1 (`onboarding.steps.ob1.back.enabled`, OB1 là trang đầu). Nút quay về LFO, giữ ngôn ngữ đã chọn, hiện ngay nút xác nhận và slot ở `native_lang_alt`.

**Privacy → Goal.** Mặc định tắt (`privacy_goals_screen.enabled = false`) ở cả hai flow, và cần `goal.options`. Flow mở lần đầu của DashDrama kết thúc ở màn goal, nên muốn giống DashDrama thì phải bật; không bật thì flow pool kết thúc sau pager. Privacy đi cùng Goal như ở `PER_SCREEN`, dù flow đó không có bước nào giữa pager và màn goal. Xem [Privacy → Goal](privacy-goals-screen.vi.md#khi-chạy-shared_pool).

**Ads khi quay lại.** Màn resume của pool cần `app_resume.type = native_resume` (mặc định) và `native_resume` được khai và bật trong `ad_config`; với dạng khác, ad khi quay lại theo dạng đó như mô tả ở [Ads khi quay lại](#ads-khi-quay-lại). Màn chỉ mở khi app đã ở background ít nhất `native_flow.pool.resume.min_background_ms` (mặc định 2000ms); màn native resume classic vẫn dùng `open_resume.app_resume_load_delay_ms` (2000ms trong bản mẫu). Sau flow, pool giữ ad tới khi process kết thúc (`parity.keep_after_flow`), nên màn resume, tức màn welcome back của DashDrama, hiện ad onboarding còn lại, mỗi ad tối đa một giờ kể từ lúc fill, và chỉ request `native_resume` khi màn đang mở, luôn ở dạng POLITE. Việc này kéo dài tới khi process chết hoặc một lượt `PER_SCREEN` sau đó chạy trong cùng process; lần mở nguội kế tiếp dùng màn resume thường. Màn này không liên quan tới màn Welcome Back của SDK.

**Mua hàng.** Khi user thành premium, mọi slot của pool ẩn và pool ngừng request, kể cả tầng kế tiếp của chuỗi đang chạy. Ad pool đang giữ chỉ được giải phóng ngay lúc mua khi host gọi `AdGate.installPremiumObserver(appScope, Billing.isPremium)` như trong [BillingKit](billing-integration.vi.md). Không có observer này, ad nằm trong bộ nhớ, không bao giờ hiện, tới khi flow kết thúc; nếu user mua sau flow thì tới khi ad hết hạn một giờ sau lúc fill, một lượt `PER_SCREEN` giải phóng pool, hoặc process kết thúc.

**Doanh thu và báo cáo.** Doanh thu được gán theo ad unit: trước mỗi tầng, pool đăng ký ad unit ID đó dưới placement đang request, và paid event tra placement theo ID. Vì vậy ad được mượn báo cáo dưới placement đã load nó, không phải màn đã hiển thị nó. ID dùng chung cho nhiều placement (Privacy và Goal cùng dùng `native_select`; bản mẫu dùng chung một ID test cho mỗi định dạng) báo cáo dưới placement request nó gần nhất, làm lệch báo cáo theo placement; hãy dùng ID riêng cho mỗi placement. `ad_request` được gửi một lần cho mỗi chuỗi load mới (refresh khi quay lại không gửi), không phải mỗi slot. Chuỗi kết thúc mà không có ad gửi một `ad_load_failed` không kèm ad unit ID; refresh thất bại không gửi gì vì ad cũ vẫn được giữ. Load POLITE bị gate bỏ qua không gửi `ad_skipped`. Chia dashboard theo `ob_native_flow`.

**QA với dạng 4 trang của DashDrama.**

1. Đặt nội dung này vào `app/src/main/assets/onboarding_config.json` và cung cấp `goal.options` trong code ([Privacy → Goal](privacy-goals-screen.vi.md)):

   ```json
   {
     "native_flow": { "type": "SHARED_POOL" },
     "onboarding": { "order": ["ob1", "ob2", "full1", "ob3"] },
     "privacy_goals_screen": { "enabled": true }
   }
   ```

   Giữ `privacy_goals_screen.enabled` là `true`: default SDK là `false`. Nếu chỉ muốn thử pool trên các trang của app, asset chỉ cần `{"native_flow":{"type":"SHARED_POOL"}}`. `native_flow.type` remote thắng asset.
2. Trong `ad_config.json`, bật `native_ob1` và `native_full1` (bản mẫu để tắt) và đặt `"isEnable": false` cho `native_ob2` và `native_ob3`. Đừng xoá hai key đó: key bị tắt khiến trang của nó không có slot, còn key bị xoá rơi về unit mà `AdsConfig` của bạn khai trong code (ví dụ `contentStepNative`). Giữ `native_lang`, `native_lang_alt` và `native_resume` bật, và thêm `native_select`, `native_select_alt` như trong [Privacy → Goal](privacy-goals-screen.vi.md) (bản mẫu không khai hai key này), mỗi key một tầng test trong `ids` của `ad_config_debug.json`.
3. Clear app data và khởi động lại process, rồi đi LFO → OB1 → OB2 → Full1 → OB3 → Privacy → Goal → app. OB2 và OB3 không có ad.

**Đọc log.** Gọi `OnboardingSdk.setFlowLogging(true)` và lọc Logcat theo `OB_FLOW`. Dòng của pool có dạng `[   +1234] LOAD   | pool | request key=language1 tier=0 refresh=false`: `key` là key của pool (`language1`, `language2`, `language_confirm`, `step_ob1`, `fullscreen_full1`, `step_partner_privacy`, `step_partner_goal_alt`, `native_resume`…) và `tier` là vị trí (tính từ 0) trong `ids` của key.

| Dòng sau `pool \|` | Ý nghĩa |
| --- | --- |
| `activate keep_after_flow=… reset_on_new_run=…` | Một lượt `SHARED_POOL` bắt đầu. `classic_run` nghĩa là một lượt `PER_SCREEN` đã giải phóng pool; `deactivate` nghĩa là `OnboardingSdk.reset()` đã giải phóng pool. |
| `flow stage=…`, `stage -> …`, `ob1 back -> language` | Chặng đang hiện, chuyển chặng, nút back OB1. |
| `adopt language1 status=…` | LFO có nhận preload LFO1 của splash hay không. Thường là `none`, vì splash của SDK không preload LFO1 cho lượt `SHARED_POOL`. Nếu LFO1 vẫn được preload: `ready` (ad đã được nhận), `pending` (load đang chạy đã được join), `cancelled_queue` (load trong queue đã bị huỷ và LFO tự request), `none` (load của splash fail hoặc chưa bắt đầu), `off` (`adopt_splash_language1` là `false`). |
| `request key=… tier=… refresh=…` | Request một tầng; `refresh=true` khi là refresh lúc quay lại. |
| `fill key=… refresh=…` | Placement vừa giữ ad mới. |
| `fail key=… tier=…`, `tier_timeout key=… tier=…` | Tầng đó không fill hoặc không trả lời kịp; tầng kế tiếp được request. |
| `exhausted key=… refresh=… why=…` | Chuỗi kết thúc không có fill: `exhausted` (đã thử mọi tầng), `refused reason=…` (một guard đóng giữa chuỗi), `adoption_failed`. Refresh giữ ad đang có. |
| `gate_skip key=…` | Load POLITE bị bỏ qua vì request khác đang chạy; không gửi event analytics. |
| `gate_skip refresh key=native_resume` | Màn resume không refresh ad đang giữ vì một request khác của pool đang chạy; request POLITE `native_resume` theo sau cũng chịu cùng gate. Không gửi event analytics. |
| `window_wait key=… op=…` | Khi `parity.respect_focus_window` là `true`, load hoặc refresh được gọi lúc màn chưa ở trước với window focus; request đi khi màn có focus. Việc chờ không gửi event analytics. |
| `config_skip key=… reason=…` | Bị từ chối cho cả lượt chạy (`premium`, `no_provider`, `ads_off_remote`, `ads_off_config`, `placement_off_remote`, `no_ad_unit`, `ua_gate`); màn không thành owner. |
| `transient_skip key=… reason=…` | Bị từ chối tạm thời (`requests_held`, `offline`, `consent_not_granted`); màn vẫn thành owner. |
| `refresh_skip key=… reason=…` | Refresh bị cùng các guard từ chối; ad đang giữ vẫn ở lại. |
| `expired key=…` | Ad đang giữ đã quá `native.cache.max_age_ms` và bị bỏ. |
| `stale_fill key=…` | Fill về sau khi tầng đã timeout hoặc pool đã được giải phóng; bị huỷ mà không hiển thị. |
| `stuck_loading key=… refresh=… ms=…` | Request vẫn chạy sau `stuck_loading_log_ms`; chỉ log. |
| `release_all loaders=…` | Mọi ad của pool đã được giải phóng: mua hàng, kết thúc flow không giữ pool, một lượt `PER_SCREEN`, `OnboardingSdk.reset()`, hoặc một lượt `SHARED_POOL` mới khi `parity.reset_on_new_run` là `true`. |

`OnboardingSdk.dumpState()` là hàm suspend; ghi log nó từ coroutine, ví dụ `lifecycleScope.launch { Log.d("OB_STATE", OnboardingSdk.dumpState()) }`. Kết quả gồm native flow type của lượt chạy, pool có đang hoạt động và nhận ad khi quay lại không, số lần gate skip của lượt chạy theo từng key, owner, gate đang mở hay đóng, và một dòng cho mỗi placement, ví dụ `language1 #0 state=Showing(ts=…) ad=held inFlight=false refreshing=false adopted=false fillAtMs=… saved=2` (`saved` là số tầng trong request đã lưu).

**Khác biệt so với DashDrama 1.0.6.**

- Native riêng của splash (slot dưới, `native_fs`) nằm ngoài pool và gate của nó.
- Guard ads của SDK (premium, consent, mạng, giữ request, UA gate, công tắc placement) được kiểm trước mỗi request và mỗi tầng; LFO không có ad unit thì ẩn slot.
- Mua hàng ẩn mọi slot, dừng request của pool và giải phóng ad của pool (ngay lúc mua khi có `AdGate.installPremiumObserver`, nếu không thì khi flow kết thúc; xem Mua hàng ở trên).
- Privacy là bước của SDK, chạy như một màn chọn.
- `click_action` bị bỏ qua.
- Ad được xếp theo một timestamp chung tăng ngặt nên không bao giờ bằng nhau; DashDrama phân định bằng thứ tự map.
- Các event hoàn tất ngôn ngữ được gửi lúc tap Done; ngôn ngữ được lưu sau khi ad thoát của LFO (interstitial splash còn lại) đóng.
- View ad của slot bị huỷ khi màn của nó đóng (`view.destroy_on_teardown`); ad vẫn ở trong pool.
- Refresh được tính là request đang chạy (`parity.refresh_marks_in_flight`).
- Slot của LFO, popup xác nhận ngôn ngữ, Privacy, Goal và màn resume chỉ bind ad mới khi màn đang resume: ad về trong lúc interstitial hoặc trang đích của ad che màn sẽ chờ, không hiện và không tính impression, tới khi màn quay lại phía trước. DashDrama bind ngay khi màn start; `parity.screen_slots_bind_started` `true` khôi phục cách đó.
- Thời hạn ad (một giờ) và thời gian chờ mỗi tầng (30 giây) là giá trị của SDK.
- Giữ popup xác nhận ngôn ngữ và nút back OB1.

</details>

## 8. Kiểm tra hoàn tất

- [ ] Nếu dùng settings mới, thử remote override, offline lần đầu dùng local và offline giữ remote cache hợp lệ theo [checklist Firebase](firebase-integration.vi.md#remote-notes).
- [ ] Debug build mở được splash, Logcat tag `AdRemoteConfig` có dòng `Loaded ad_config.json with <n> placements (test ids=true)`, `<n>` khớp với example bạn ship, `OB_FLOW` không báo config/provider lỗi.
- [ ] Sau khi fetch remote, `OB_FLOW` không có dòng `ad_config remote omits …` cho placement bạn sửa trên console. Key có trong danh sách giữ giá trị của app, dù console đặt gì dưới tên key khác: publish field đó dưới đúng key, hoặc bind key của bạn bằng `fromAdConfig(mapOf(...))`.
- [ ] Đi hết LFO → OB → MainActivity bằng ad test; sau khi bật `native_full1/2`, FULL1 nằm sau OB1 và FULL2 sau OB2, inter cuối (khi đã bật `onboarding.exit_interstitial.enabled`, mặc định tắt) chỉ do SDK quản lý. LFO chỉ mở sau khi đóng inter splash; khi inter cuối được bật, MainActivity đã sẵn lúc nó đóng.
- [ ] LFO: chọn ngôn ngữ rồi Back thì hiện Save và vẫn ở lại; chọn lại ngôn ngữ hiện tại mở popup ngay, còn chọn ngôn ngữ khác phải chờ đủ tổng số click đã cấu hình.
- [ ] Từ chối notification vẫn đi tiếp; Home/quay lại khi ở splash, LFO, popup và OB không điều hướng lặp. Sau khi bật `native_ob1..4`, click native ở trang OB rồi quay lại thì chuyển bước; ở LFO/popup thì ở lại và bind ad thay thế khi sẵn sàng.
- [ ] Bật `native_ob1`, `native_ob2` và `native_full1`, rồi tắt `native_ob2`: trang nội dung 2 vẫn hiện, không lấy native trang 1. Tắt lại `native_full1`: bỏ trang chỉ quảng cáo. Tắt `inter_splash` và `inter_after_ob3`: vẫn tới màn đích.
- [ ] Nếu dùng `SHARED_POOL`, đi [dạng 4 trang để QA](#native-flow-shared_pool) với `OB_FLOW` bật: log có `pool | adopt language1 status=none` rồi `pool | request key=language1` do chính LFO gửi, vì splash không preload LFO1 cho lượt `SHARED_POOL`; OB2 và OB3 không có ad, và Next ở Goal hoàn tất onboarding.
- [ ] Thử mất mạng: mặc định hiện prompt kết nối; nếu chọn hỗ trợ offline thì luồng vẫn đi tiếp theo timeout SDK, không treo vì callback app.
- [ ] Mở lại sau khi hoàn thành: đi qua splash rồi vào app, không chạy lại OB; màn app đã sẵn khi đóng inter splash. Clear app data để kiểm tra first-open; đóng app giữa OB rồi mở lại phải bắt đầu từ LFO sau splash.
- [ ] Các màn app dùng đúng ngôn ngữ đã chọn; kiểm tra cả bản dịch và cấu hình language split khi phát hành AAB.
- [ ] Trước phát hành: thay `admob_app_id` trong `res/values/id_ads.xml` và mọi ad unit ID trong **`ad_config.json`** bằng ID app. Giữ JSON debug dùng test. Muốn debug giữ App ID test, thêm override `admob_app_id` trong `app/src/debug/res/values/id_ads.xml`.

| Hiện tượng | Kiểm tra nhanh |
| --- | --- |
| Crash ngay khi mở | Metadata AdMob/Meta, credential Meta thật và Application trong merged manifest |
| OB trống hoặc bỏ qua | `install` trước `configure`; dùng `steps(...)` ở bước 4 để có nội dung app, kiểm tra remote cache đã tắt flow/step chưa |
| Không có ads | File được nạp, key ánh xạ, `isEnable`, trạng thái consent/premium, các cờ remote; không tạo timer show bù |
| Debug dùng sai ID | `ad_config_debug.json` có `ids` test cho mọi key placement đang bật (log `WARN` của `AdRemoteConfig` liệt kê key đang bật mà thiếu); không bật `setAllowRemoteOverrideInDebug(true)` trong cấu hình chuẩn |
| `ad_remote_config` remote không bao giờ áp dụng (`AdConfig` log `cleared`) | Tên parameter trên Console: SDK đọc `ad_remote_config`, rồi `ads_remote_config`; tên khác cần `FirebaseAdConfigSource("<tên>")` |
| Remote đổi ID nhưng OB còn dùng ID cũ | Cài `FirebaseAdConfigSource`, dùng `AdsConfig.fromAdConfig()` đúng key và để splash refresh. Minimum fetch interval của Firebase là 12 giờ nếu app không hạ xuống. Debug dùng ID test của `ad_config_debug.json`; mọi field khác của `ad_remote_config` và hai settings JSON vẫn áp dụng. |
| Native style/reporting không tách từng slot khi test | ID demo cùng format đang dùng chung; provider có chỗ tra ngược style/placement theo ID. Dùng ID riêng khi kiểm tra cấu hình thực tế |

## Các file app thực sự cần

| File | Việc làm |
| --- | --- |
| `settings.gradle`, `app/build.gradle` | Ghép cấu hình build, hai dependency |
| `app/src/main/AndroidManifest.xml` | Metadata, Application và launcher splash |
| `app/src/main/res/values/id_ads.xml` | AdMob App ID, Meta credential và Adjust token/event token |
| `app/src/main/assets/ad_config.json`, `ad_config_debug.json` | Copy hai JSON mẫu |
| `app/src/main/assets/ad_behavior_config.json`, `onboarding_config.json` (tùy chọn) | Tạo/copy khi cần đổi default local; xem [quy tắc fallback](firebase-integration.vi.md#local-defaults). |
| `strings.xml` và drawable của app | Nội dung, bản dịch, ảnh cho bốn trang nội dung |
| `AppAdPlacement.kt` | Danh mục key OB và key riêng của app; không chứa ad unit ID |
| [OnboardKitSetup.kt](examples/ads-onboarding/OnboardKitSetup.kt) | Nội dung/resource app và liên kết trực tiếp các placement ad_config chuẩn |
| Application hiện có hoặc [PartnerApp.kt](examples/ads-onboarding/PartnerApp.kt) | Khởi tạo SDK một lần và chọn màn đích |
| `SplashActivity.kt` | Kế thừa splash SDK |

`MainActivity` là màn đích hiện có của app. Ngoài `AppAdPlacement.kt`, không cần file nào để dịch JSON sang config ads — mọi entry point nhận thẳng placement key. Không cần sao chép `AppConstants`, `RemoteConfigUtils`, DevConfig, Hilt hay các màn paywall/uninstall từ example cho luồng cơ bản này.

[Tra cứu Ads](../ads/README.md) · [Tra cứu OnboardKit](../onboardkitorigin/README.vi.md)
