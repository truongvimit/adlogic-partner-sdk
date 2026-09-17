# Tích hợp Ads + OnboardKit

**OB catalog:** `ob1..ob4` → `native_ob1..4`; `full1/full2` → `native_full1/2`. Default: `ob1, full1, ob2, full2, ob3, ob4`. All eligible OB natives preload on language selection. Remote `onboarding.order` selects/reorders app-declared steps. [Configuration and migration / Hướng dẫn chi tiết](onboarding-flow.vi.md). `native_fs` remains the separate splash native.

[← Chọn hướng dẫn](README.vi.md)

Luồng mẫu: **Splash → ngôn ngữ (LFO) → nội dung 1 → nội dung 2 → native fullscreen → nội dung 3 → inter cuối OB → MainActivity**. SDK quản lý consent, thông báo, ads và điều hướng; quảng cáo chỉ hiện khi đủ điều kiện và có fill.

Làm bước 1–6, thay **package, thông tin app, nội dung/ảnh và màn đích**. Code giữ default SDK; JSON giữ cấu hình example debug. Điền app token để bật Adjust; Firebase, app-open và mua hàng ở [bảng tùy chọn](#7-cấu-hình-chỉ-khi-app-cần).

Dùng **version SDK mới nhất** trên [JitPack](https://jitpack.io/#truongvimit/adlogic-partner-sdk) cho mọi module. Bản mới nhất có `ad_behavior_config` / `onboarding_config`, default local custom và liên kết trực tiếp `AdsConfig.fromAdConfig()`. Chỉ thêm key Firebase không nâng cấp SDK cũ đã tích hợp trong app.

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
- **[ad_config_debug.json](examples/ads-onboarding/ad_config_debug.json):** cấu hình debug; giữ ID test.

Ad unit ID chứa **`/`**. Mỗi file có **45 entry** như [example debug](../app/src/main/assets/ad_config_debug.json), đủ style, UA, app-resume delay và waterfall; interstitial dùng ID test `1033173712`, native high dùng native video test. Dưới đây là 10 slot OB; các key còn lại dành cho màn app, chưa tự tạo vị trí hiển thị. Giá trị mẫu có thể khác default parser; xem [bảng field JSON](#field-trong-json-mẫu).

| Key trong JSON | Vị trí | Ánh xạ vào `AdsConfig` ở bước 4 |
| --- | --- | --- |
| `banner_splash` | Banner splash | `splashBanner` |
| `inter_splash` | Inter khi rời splash | `splashInterstitial` |
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

`inter_after_ob3` shows after the entire configured OB list. See [migration](onboarding-flow.vi.md) for the former OB3/OB4 mapping.

SDK chọn file theo debuggable. Debug thiếu/sai JSON sẽ dùng file thật, không tự đổi live ID thành test ID. Asset debug nạp thành công mặc định chặn remote ghi đè.

ID mẫu từ [Google demo ad units](https://developers.google.com/admob/android/test-ads#demo_ad_units) và [AdMob App ID](https://developers.google.com/admob/android/quick-start). Fullscreen dùng **native ID**. Mẫu dùng chung ID test cùng format; production cần ID riêng để tách cấu hình/style/báo cáo theo placement.

### Tùy chọn: settings remote theo nhóm và local default riêng

SDK đã đóng gói thêm [ad_behavior_config.json](examples/ads-onboarding/ad_behavior_config.json) và [onboarding_config.json](examples/ads-onboarding/onboarding_config.json). Muốn điều khiển qua remote, làm theo [Firebase: publish ba parameter String](firebase-integration.vi.md#remote-json). Giữ `ad_remote_config` cho ad unit; thêm `ad_behavior_config` và `onboarding_config` là hai String riêng, value chứa object JSON tương ứng.

Muốn custom fallback, tạo file cùng tên trong `app/src/main/assets/`, copy toàn bộ default hoặc chỉ khai báo field muốn đổi, rồi build lại. Nếu dùng default SDK thì không cần tạo hai file ở app. Field remote/cache hợp lệ ưu tiên hơn local; fetch offline giữ remote cache hợp lệ cũ thay vì ép dùng local. Xem [ví dụ hai JSON local và quy tắc fallback](firebase-integration.vi.md#local-defaults), cùng [reference field/default](remote-settings.vi.md).

## 3. Chuẩn bị nội dung onboarding

Dùng `app_name`, icon launcher sẵn có. Thêm sáu string vào `app/src/main/res/values/strings.xml`, thay nội dung theo sản phẩm:

```xml
<resources>
    <string name="onboarding_title_1">Chào mừng bạn</string>
    <string name="onboarding_des_1">Giới thiệu lợi ích chính của ứng dụng.</string>
    <string name="onboarding_title_2">Bắt đầu dễ dàng</string>
    <string name="onboarding_des_2">Giới thiệu thao tác người dùng cần biết.</string>
    <string name="onboarding_title_3">Sẵn sàng trải nghiệm</string>
    <string name="onboarding_des_3">Mời người dùng bắt đầu sử dụng.</string>
</resources>
```

Thêm bản dịch vào `values-<language>/strings.xml`, gồm các key `ob_*` trong [ob_strings.xml](../onboardkitorigin/src/main/res/values/ob_strings.xml) vì SDK chỉ kèm tiếng Anh. Bước 4 thay ba `imageRes` bằng ảnh app, hoặc copy ảnh [1](../app/src/main/res/drawable-nodpi/img_onboard_sample_1.png), [2](../app/src/main/res/drawable-nodpi/img_onboard_sample_2.png), [3](../app/src/main/res/drawable-nodpi/img_onboard_sample_4.png) từ example vào `app/src/main/res/drawable-nodpi/` để thử.

SDK đã cung cấp layout/Activity cho LFO, popup, OB và native ad.

<details>
<summary>Tùy chọn: dùng layout riêng</summary>

Chỉ `SplashConfig.layoutRes` và `ContentStepDefinition.layoutRes` hỗ trợ layout riêng; cấu hình khác bị `getOrThrow()` từ chối. Copy [layout nội dung](../onboardkitorigin/src/main/res/layout/ob_fragment_content_step.xml), giữ root, ID và kiểu view; sai hợp đồng thì SDK log `OB_FLOW` và dùng layout mặc định. Với [splash](../onboardkitorigin/src/main/res/layout/ob_activity_splash.xml), ID thiếu bị bỏ qua, ID giữ lại phải đúng kiểu; banner cần `ob_splash_ad_container` chứa `<include layout="@layout/layout_banner_control" />`.

</details>

## 4. Khai báo placement và cấu hình OnboardKit

### `AppAdPlacement.kt` — danh mục placement của app

Copy [AppAdPlacement.kt](examples/ads-onboarding/AppAdPlacement.kt) vào package app, ví dụ `app/src/main/java/com/example/app/`. File có **35 key gốc**: 10 OB và các slot app. SDK tự tìm tầng `_high`, `_high1`…; không cần constant cho tầng.

`AppAdPlacement.NATIVE_HOME` là key `native_home`; cả hai JSON chứa ad unit ID và cấu hình của key đó. Slot mới cần constant và key tương ứng trong JSON.

Khai báo slot native/banner trong XML của màn, gọi API SDK trực tiếp tại màn. SDK quản lý tải, cache và vòng đời ads. Nếu dùng `AdsAppManager`, chỉ gom cấu hình, khởi tạo và chính sách riêng của app.

### `OnboardKitSetup.kt` — nối các key OB vào SDK

Copy [OnboardKitSetup.kt](examples/ads-onboarding/OnboardKitSetup.kt) cùng package, đổi `com.example.app` và giữ resource của app. Mẫu khai báo ba trang nội dung + fullscreen, dùng **`AdsConfig.fromAdConfig()`** để liên kết các placement chuẩn. Bước 5 configure một lần sau `OnboardingSdk.install`; ID, gate và template được resolve từ settings hiện hành, không setup lại sau fetch. App dùng key khác chỉ truyền các association đó vào `fromAdConfig(mapOf(...))`; [bảng mapping](remote-settings.vi.md) liệt kê key mặc định.

Placement đã khai báo nhưng bị tắt giữ unit rỗng, không lấy quảng cáo của slot khác. Chỉ khi LFO2 không có unit mới fallback LFO1; tắt hành động thay native bằng `onboarding_config.lfo.native2.enabled = false`.

Template native điều khiển qua `lfo.native_template`, `onboarding.ads.content_template`, `onboarding.steps.<id>.native_template` và `question.native.template`. Template override tường minh ưu tiên trước; khi thiếu thì từng placement đọc `positionCTA` riêng rồi template host/SDK. Fullscreen/popup giữ layout cố định. Màu, chiều cao CTA và components vẫn ở ad_config; reference resource/layout của app vẫn trong code.

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

`ERainTuning.install()` đặt thời điểm `onComplete` cho [inter màn app](#interstitial-ở-màn-app-dùng-placement-constant) và bỏ app-open sau click quảng cáo; không đổi luồng OB.

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

Bảng dưới mô tả default SDK và API local/legacy đang dùng làm fallback. Khi thử remote hoặc đổi default qua JSON local, dùng field tương ứng trong [reference settings theo nhóm](remote-settings.vi.md); override hợp lệ của JSON mới ưu tiên hơn các giá trị fallback này.

Chỉ thêm option cần đổi vào `onboardKitConfig { ... }` ở bước 4; `ERainAd`/`ConsentCenter` gọi tại nơi ghi trong bảng. Key `ob_*` thuộc Firebase Remote Config, **không nằm trong JSON ads**; chưa có Firebase dùng cache/default.

| Hành vi | Mặc định | Chỉ đổi khi / nơi đổi |
| --- | --- | --- |
| Màn splash | Layout SDK; minimum display 3000 ms tính từ pha tải ads | `onboarding_config.splash.timing.min_display_ms`; `0` hợp lệ nghĩa là không giữ minimum này. Local/legacy hiện có là fallback. |
| Mở màn sau inter splash | Show inter sau thời gian tối thiểu. LFO lần đầu: chờ đóng inter. Launcher → app/khảo sát người dùng cũ: mở dưới inter. Notification/widget/uninstall: chờ đóng | Override `SplashActivity.nextScreenTiming()`: `NextScreenTiming.AFTER_AD`/`UNDER_AD` (`io.onboardkit.ads`), hoặc `super.nextScreenTiming()` để giữ mặc định |
| Chờ quảng cáo splash | Tối đa 60 giây sau notification và khi splash có focus | Remote `ob_splash_ad_budget_ms`; không tự thêm timer |
| Fetch và tải ads | `ALTERNATE`: chờ bước remote rồi mới request ads splash | `onboarding_config.splash.load.ad_strategy`; `SAME_TIME` cho banner/interstitial splash request sớm hơn. Strategy được chọn lúc vào splash. |
| Preload native LFO đầu | Sau remote, rồi chờ interstitial splash tải xong (`SEQUENTIAL`) | `onboarding_config.splash.load.lfo1_preload_mode = "PARALLEL"` bỏ chờ tải interstitial, không bỏ chờ remote. |
| Không có mạng | Hiện yêu cầu kết nối, chưa đi tiếp | `SplashConfig.noInternetPromptEnabled = false` để mở offline qua fallback UMP bên dưới; splash lần sau hỏi UMP lại |
| Consent | Timeout mạng 20 giây; form chờ người dùng | Trong Application: `ConsentCenter.configure(ConsentOptions(timeoutMs = ...))` (`com.ads.module.consent`). Giữ hook SDK; `SplashConfig.consentTimeoutMs` không đổi timeout UMP |
| Form UMP khi QA | Debuggable: mọi máy là EEA (`setForceTesting`), không cần hashed id; release: địa lý thật | `ConsentOptions(debug = false)` để debug theo địa lý thật. `configure` thay toàn bộ option; muốn đổi cả timeout dùng một lần `ConsentOptions(timeoutMs = ..., debug = false)` |
| Thông báo | Hỏi sau consent trên Android 13+/target 33+; từ chối vẫn đi tiếp và không tự hỏi lại | `SplashConfig.notificationPermissionEnabled = false` nếu app không gửi thông báo hoặc tự hỏi |
| Ngôn ngữ | 21 ngôn ngữ, hand hint sau 3 giây, ẩn xác nhận trước chọn | `LanguageConfig.languages`: giữ ngôn ngữ đã dịch; `tapHintEnabled`, `confirmVisibleBeforeSelect` còn cần cờ remote tương ứng bật |
| Back ở LFO | Chưa chọn: bỏ qua Back. Đã chọn: hiện Save, vẫn ở màn ngôn ngữ | `LanguageConfig.saveButtonOnBackEnabled = false`: bỏ qua Back cả sau khi chọn. SETTINGS Back đóng màn |
| Thay native sau chọn ngôn ngữ | Bật; native đầu giữ nguyên đến khi ad thay thế bind được | `LanguageConfig.secondNativeOnSelectEnabled = false` để tắt |
| Popup ngôn ngữ | Chọn lại ngôn ngữ hiện tại thì mở ngay. Chọn ngôn ngữ khác chỉ mở từ tổng click thứ 4; click chọn lại vẫn được cộng count. Native request lần đầu khi mở popup | `LanguageConfig.confirmDialogOnReselectEnabled = false` để tắt; SETTINGS không hiện popup |
| Native template | SDK: LFO/question `CTA_BOTTOM`, content `CTA_TOP`; ad_config mẫu dùng `positionCTA` từng slot | Chỉnh template trong `onboarding_config`; thiếu override thì dùng `ad_config.<key>.positionCTA` rồi host/default. [Thứ tự ưu tiên](remote-settings.vi.md). |
| System bars | Hiện status/caption bar, ẩn navigation bar | `SystemBarConfig(showStatusBar, showNavigationBar, showCaptionBar)` |
| Click native rồi quay lại OB | Next bước (`BehaviorConfig.adClickReturnCompletesStep = true`); OB/OB5 tắt preload thay native khi click | `adClickReturnCompletesStep = false` để ở lại; không bật lại click preload ở provider |
| Click native ở LFO/popup hoặc màn app | Preload ngay khi click/open; quay lại bind ad sẵn có hoặc chờ request đang chạy | `NativeAdConfig.reloadOnAdClick = true` mặc định, độc lập refresh theo thời gian; [ví dụ native trong app](#native-ở-màn-app-dùng-placement-constant) |
| Mở lại khi chưa xong flow | Chạy lại Splash → LFO → OB; chỉ bỏ OB khi hoàn thành toàn bộ | Không cần tự lưu cờ first-open/checkpoint trong app |
| Trang native fullscreen | X sau 5 giây, auto-next sau 15 giây từ lúc chọn trang; thời gian background vẫn được tính. Shimmer phủ đầy khung native, media toàn khung và CTA ở đáy. | Các trường của `AdFullScreenStepDefinition`; remote `ob_skip_button_delay_sec = -1` giữ delay local |
| Inter cuối onboarding | Preload lúc vào pager, đợi fill tối đa 8 giây khi hoàn thành; mở màn dưới inter. Notification/widget/uninstall: chờ đóng | `AdsConfig.afterOnboardingInterstitialTiming = NextScreenTiming.AFTER_AD` để luôn chờ đóng; `afterOnboardingInterstitialEnabled = false` nếu app tự quản lý. Không đưa vào `InterstitialAutoBuffer` |
| Điều hướng OB | Khi bật swipe: OB1 vẫn khóa; OB2 và trang nội dung cuối được swipe. Fullscreen khóa khi đang load/bind, chỉ mở sau impression của ads; mỗi lần vào lại trang bắt đầu ở trạng thái khóa. Cờ khóa swipe toàn cục vẫn ưu tiên. | `BehaviorConfig.lockPagerSwipe`, `swipeCompletesLastStep`, `backNavigatesBack` (`false`: Back luôn thoát app), `lockPortrait`; app ngang cần sửa cả manifest |
| Khoảng cách interstitial | `ERainAdConfig.intervalInterstitialAd = 0` (không giới hạn); chỉ áp nhóm `InterstitialAutoBuffer`, không áp splash/OB/inter tự load | Đặt trước init hoặc dùng `ERainAd.getInstance().setIntervalInterstitialAd(giây)` |
| Giới hạn click interstitial | Tắt (`0`) | `ERainAd.getInstance().setMaxClickAdsPerDay(n)`: mỗi ad unit tối đa `n` click/24 giờ rồi ngừng load/show. Gọi lúc cần, thường sau fetch remote |
| OB5, khảo sát, paywall, app-open | `ob_enable_step_ob5 = false`. Bật OB5: mở dưới inter cuối nếu native đã tải, chưa có thì bỏ qua. `ob5Native` null dùng `fullScreenStepNative` (host setup). Các tính năng còn lại chưa nối | `AdsConfig.ob5Native` để đặt ID riêng; chỉ nối khảo sát/paywall/app-open khi cần |

UMP lỗi/timeout có thể cho **thử request** trong process qua [fallback AdLogic](../ads/src/main/java/com/ads/module/consent/ConsentCenter.kt), không cấp consent hay đảm bảo fill. Host tắt ads vẫn được ưu tiên; không tự suy quyền request từ timer/personalization.

### Field trong JSON mẫu

Hai JSON giữ field/giá trị example debug, chỉ chuẩn hóa interstitial sang ID test.

| Field | Giá trị trong mẫu | Cách dùng / phạm vi áp dụng |
| --- | --- | --- |
| `id` | Ad unit test đúng format | Thay ID ở file thật khi phát hành; không sửa key placement. |
| `isEnable` | Theo example: đa số `true`, welcome `false` | Bật/tắt placement. Key gốc là công tắc tổng: `false` ở key gốc tắt cả waterfall. |
| `enable_ua_check` | Có `true` và `false` | `true` yêu cầu paid/non-organic; chưa có kết quả Adjust thì mặc định organic. Liên kết chuẩn `AdsConfig.fromAdConfig()` áp gate này cho placement OB tương ứng, gồm native LFO/OB và inter cuối OB. Không dùng Adjust thì đặt `false` cho các placement muốn hiện. |
| `reloadIntervalSeconds` | Banner: `30` | Chỉ parse, helper không dùng; không đổi refresh kể cả splash. Muốn refresh xem [Banner ở màn app](#tích-hợp-bổ-sung). |
| `colorCTA` | `"default"` | Giữ màu template; thay màu khi cần tùy biến native. |
| `heightCTA` | Native thường `45`, popup `36` | Chiều cao CTA (dp); SDK dùng `40` nếu bỏ field và ép giá trị vào khoảng 36–52 khi áp dụng. |
| `positionCTA` | `"BOTTOM"` hoặc `null` | Chọn khung LFO/content/question theo từng placement khi chưa có template onboarding override tường minh. `null` giữ host/SDK fallback; fullscreen/popup dùng layout cố định. |
| `components` | `["icon_headline", "body", "media", "cta"]` | Khối thiếu bị ẩn, mảng rỗng hiện đủ. OB chỉ đổi visibility; [native màn app](#native-ở-màn-app-dùng-placement-constant) dùng cả thứ tự khi `positionCTA: null`. |
| `app_resume_load_delay_ms` | `open_resume`: `2000` | Thời gian chờ tải app-open sau khi app ra background; chỉ có tác dụng khi đã bật app-resume. |

Waterfall đọc `_high`, `_high1`… rồi key gốc; có thể dùng `ids` để khai báo nhiều tầng trong một entry. ID trùng bị loại; dùng ID riêng khi cần kiểm tra từng tầng/style.

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

**Chưa dùng Adjust:** đổi `enable_ua_check` của `native_home` thành `false` trong **cả hai JSON** để slot mẫu có thể hiện; giữ ID test khi QA.

```kotlin
import android.widget.FrameLayout
import com.ads.module.helper.adnative.NativeAdHelper

val container = findViewById<FrameLayout>(R.id.ad_slot)
NativeAdHelper.forPlacement(this, this, AppAdPlacement.NATIVE_HOME, container)
```

SDK tự đọc waterfall, `isEnable`, `enable_ua_check`, CTA style của placement. Thêm `layoutRes` để đổi template; mặc định là `com.ads.module.R.layout.custom_native_admob_medium` (không có media — cần media dùng `custom_native_admob_free_size`). Layout riêng giữ root `NativeAdView`, `ad_container`, `block_icon_headline`, asset IDs và nhãn Ad.

Giữ một helper/slot/view, gọi `show()` để hiện lại. Fragment dùng Activity + `viewLifecycleOwner`. `reloadOnAdClick` mặc định bật; chỉ tắt khi app tự điều hướng sau click-return.

Preload cho Main: `NativeAdManager.preload(applicationContext, AppAdPlacement.NATIVE_HOME, NativeAdConfig.forPlacement(AppAdPlacement.NATIVE_HOME, layoutRes))` trong `SplashActivity.onRemoteFetched()`. Helper cùng placement lấy ad khi hiện; ad quá 60 phút bị tải lại. Xem [Native preload](../ads/README.md#native-preload-repeated-show-and-refresh).

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
| Tắt một slot | `isEnable: false` ở **key gốc** là tắt cả waterfall, cho mọi format; thử asset cần khởi động lại. LFO native thứ hai có fallback ở bước 4. |
| Thêm waterfall | `<key>_high`, `<key>_high1`…`<key>_high9`, rồi key gốc. Banner splash chỉ dùng ID đầu của `banner_splash`, không đọc tầng rời. |
| CTA/native style | Giữ đủ field như example; [bảng field JSON](#field-trong-json-mẫu) giải thích giá trị và nơi áp dụng. Bước 4 đã nối `positionCTA` vào native template. |
| Banner ở màn app | `BannerAdHelper.forPlacement(this, this, "banner_home", container)`; thêm đối số `bannerType`, ví dụ `BannerType.Collapsible()` ([các loại](../ads/src/main/java/com/ads/module/helper/banner/BannerType.kt)). Đổi loại: `flagUserEnableReload = false`, `cancel()` helper cũ rồi tạo mới. SDK refresh cần tắt refresh AdMob cho mọi tầng rồi dựng `BannerAdConfig.forPlacement("banner_home", bannerType, canReloadAds = true)` và truyền vào constructor `BannerAdHelper`. |
| UA/Adjust | Điền resource và dùng wiring ở [bước 5](#adjust-token-và-kiểm-tra). `enable_ua_check` giữ giá trị example; xem phạm vi áp dụng trong bảng field JSON. |
| JSON từ Firebase | [Publish ba parameter String](firebase-integration.vi.md#remote-json): `ad_remote_config`, `ad_behavior_config`, `onboarding_config`. Cài `FirebaseAdConfigSource()` một lần sau assets; splash SDK tự refresh. [Custom fallback local](firebase-integration.vi.md#local-defaults) là tùy chọn. |
| Firebase Analytics | Cài `suite-firebase`, đăng ký `Tracker.addSink(FirebaseSink())` ngay sau `Tracker.install`; chọn consent policy theo [hướng dẫn Firebase](firebase-integration.vi.md#consent-ban-đầu). |
| App-open khi quay lại | Chưa bật; JSON có sẵn `open_resume` nhưng chỉ thêm JSON chưa bật tính năng. Làm theo [App-open khi quay lại](#app-open-khi-quay-lại). |
| App có premium / paywall | Làm [BillingKit](billing-integration.vi.md) / [PayKit](paywall-integration.vi.md) và [hook chờ billing trước ads](../onboardkitorigin/README.vi.md#tích-hợp-tùy-chọn). `onInitBilling()` mặc định trống; gọi install billing chưa có nghĩa đã khôi phục premium. |
| Đổi ngôn ngữ từ Settings | `registerForActivityResult(StartActivityForResult())`, rồi `launch(ObLanguageActivity.intentFor(activity, LanguageScreenMode.SETTINGS))` (kiểu trong `io.onboardkit.ui.language`). `RESULT_OK`: lấy `ObLanguageActivity.RESULT_LANGUAGE_CODE`, lưu như bước 5 và `recreate()`; Back không trả mã. Màn này không ads. `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)` không trả result; đọc lựa chọn qua `OnboardingSdk.selectedLanguage()`. |
| Entry từ notification/widget | Dùng `SplashEntry` và giữ passthrough trong listener; xem [OnboardKit](../onboardkitorigin/README.vi.md#tích-hợp-tùy-chọn). Không cần các entry này cho launcher thông thường. |

Với liên kết placement hiện hành ở bước 4, dùng remote JSON không cần override splash thêm:

```kotlin
package com.example.app

import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity()
```

SDK refresh các document trước điểm luồng cần dùng. Không gọi lại `OnboardKitSetup.configure()` trong `onRemoteFetched` chỉ để chép giá trị vừa fetch. Giữ hook nếu app có preload/tích hợp riêng. `ALTERNATE` chờ bước remote; LFO1 preload sau bước đó ở cả hai strategy. [Thời điểm áp dụng và QA](firebase-integration.vi.md#remote-notes).

### App-open khi quay lại

<details>
<summary>Mở các bước bật app-open</summary>

Chỉ làm khi sản phẩm dùng app-open. `AppOpenManager` thuộc `com.ads.module.admob`.

1. **Bước 4:** `AdsConfig.fromAdConfig()` đã liên kết `open_resume`. Nếu tự dựng `AdsConfig(...)`, truyền `appResume = InterstitialAdUnit(...)`; nếu thiếu, OnboardKit chặn app-open ở mọi màn.
2. **Bước 5**, trong khối `apply` của `ERainAdConfig`: `idAdResume = AdGate.adUnitIds(AppAdPlacement.OPEN_RESUME).firstOrNull().orEmpty()`.
3. **Remote JSON:** không cần thêm gì — SDK tự trỏ lại ID app-resume theo `open_resume` mỗi lần config đổi, gồm cả bật/tắt bằng `isEnable`. Hai điều kiện: app đã đặt sẵn một ID app-resume khác rỗng ở bước 2, và `open_resume` có ad unit ID. Vì bước 2 đọc lúc init (khi đó mới có config từ asset), hãy ship `open_resume` **bật kèm ID thật** trong `ad_config.json`.
4. **Intent ra ngoài** (browser/share/review): gọi `AppOpenManager.getInstance().disableAdResumeByClickAction()` ngay sau `startActivity(...)` để bỏ qua lần quay lại. `disableAppResume()`/`enableAppResume()` là công tắc cả process.

Splash/OB5/khảo sát tự loại trừ; chỉ đăng ký thêm màn nhạy cảm của app. LFO/trang nội dung OB có thể hiện app-open sẵn có khi quay lại, trừ fullscreen, lúc chuyển trang hoặc mở popup. Delay/gate xem [app-open](../onboardkitorigin/README.vi.md#app-open-khi-quay-lại-app).

</details>

## 8. Kiểm tra hoàn tất

- [ ] Nếu dùng settings mới, thử remote override, offline lần đầu dùng local và offline giữ remote cache hợp lệ theo [checklist Firebase](firebase-integration.vi.md#remote-notes).
- [ ] Debug build mở được splash, Logcat tag `AdRemoteConfig` có dòng `Loaded ad_config_debug.json with 45 placements (debug=true)`, `OB_FLOW` không báo config/provider lỗi.
- [ ] Đi hết LFO → OB → MainActivity bằng ad test; native fullscreen nằm giữa nội dung 2 và 3, inter cuối chỉ do SDK quản lý. LFO chỉ mở sau khi đóng inter splash; MainActivity đã sẵn khi đóng inter cuối.
- [ ] LFO: chọn ngôn ngữ rồi Back thì hiện Save và vẫn ở lại; chọn lại ngôn ngữ hiện tại mở popup ngay, còn chọn ngôn ngữ khác phải chờ đủ tổng số click đã cấu hình.
- [ ] Từ chối notification vẫn đi tiếp; Home/quay lại khi ở splash, LFO, popup và OB không điều hướng lặp. Click native ở trang OB rồi quay lại chuyển bước; ở LFO/popup thì ở lại và bind ad thay thế khi sẵn sàng.
- [ ] Tắt cả `native_ob2` và `native_ob2_high`: trang nội dung 2 vẫn hiện, không lấy native trang 1. Tắt cả `native_full1` và `native_full1_high`: bỏ trang chỉ quảng cáo. Tắt `inter_splash`, `inter_after_ob3` và mọi tầng `_high*` của chúng: vẫn tới màn đích.
- [ ] Thử mất mạng: mặc định hiện prompt kết nối; nếu chọn hỗ trợ offline thì luồng vẫn đi tiếp theo timeout SDK, không treo vì callback app.
- [ ] Mở lại sau khi hoàn thành: đi qua splash rồi vào app, không chạy lại OB; màn app đã sẵn khi đóng inter splash. Clear app data để kiểm tra first-open; đóng app giữa OB rồi mở lại phải bắt đầu từ LFO sau splash.
- [ ] Các màn app dùng đúng ngôn ngữ đã chọn; kiểm tra cả bản dịch và cấu hình language split khi phát hành AAB.
- [ ] Trước phát hành: thay `admob_app_id` trong `res/values/id_ads.xml` và mọi ad unit ID trong **`ad_config.json`** bằng ID app. Giữ JSON debug dùng test. Muốn debug giữ App ID test, thêm override `admob_app_id` trong `app/src/debug/res/values/id_ads.xml`.

| Hiện tượng | Kiểm tra nhanh |
| --- | --- |
| Crash ngay khi mở | Metadata AdMob/Meta, credential Meta thật và Application trong merged manifest |
| OB trống hoặc bỏ qua | `install` trước `configure`; dùng `steps(...)` ở bước 4 để có nội dung app, kiểm tra remote cache đã tắt flow/step chưa |
| Không có ads | File được nạp, key ánh xạ, `isEnable`, trạng thái consent/premium, các cờ remote; không tạo timer show bù |
| Debug dùng sai ID | Có đủ file debug hợp lệ; không bật `setAllowRemoteOverrideInDebug(true)` trong cấu hình chuẩn |
| Remote đổi ID nhưng OB còn dùng ID cũ | Cài `FirebaseAdConfigSource`, dùng `AdsConfig.fromAdConfig()` đúng key và để splash refresh. Debug mặc định pin ID ads; hai settings JSON vẫn áp dụng. |
| Native style/reporting không tách từng slot khi test | ID demo cùng format đang dùng chung; provider có chỗ tra ngược style/placement theo ID. Dùng ID riêng khi kiểm tra cấu hình thực tế |

## Các file app thực sự cần

| File | Việc làm |
| --- | --- |
| `settings.gradle`, `app/build.gradle` | Ghép cấu hình build, hai dependency |
| `app/src/main/AndroidManifest.xml` | Metadata, Application và launcher splash |
| `app/src/main/res/values/id_ads.xml` | AdMob App ID, Meta credential và Adjust token/event token |
| `app/src/main/assets/ad_config.json`, `ad_config_debug.json` | Copy hai JSON mẫu |
| `app/src/main/assets/ad_behavior_config.json`, `onboarding_config.json` (tùy chọn) | Tạo/copy khi cần đổi default local; xem [quy tắc fallback](firebase-integration.vi.md#local-defaults). |
| `strings.xml` và drawable của app | Nội dung, bản dịch, ảnh cho ba trang |
| `AppAdPlacement.kt` | Danh mục key OB và key riêng của app; không chứa ad unit ID |
| [OnboardKitSetup.kt](examples/ads-onboarding/OnboardKitSetup.kt) | Nội dung/resource app và liên kết trực tiếp các placement ad_config chuẩn |
| Application hiện có hoặc [PartnerApp.kt](examples/ads-onboarding/PartnerApp.kt) | Khởi tạo SDK một lần và chọn màn đích |
| `SplashActivity.kt` | Kế thừa splash SDK |

`MainActivity` là màn đích hiện có của app. Ngoài `AppAdPlacement.kt`, không cần file nào để dịch JSON sang config ads — mọi entry point nhận thẳng placement key. Không cần sao chép `AppConstants`, `RemoteConfigUtils`, `ResumeAdsEntryRule`, `AppLifecycleObserver`, DevConfig, Hilt hay các màn paywall/welcome/uninstall từ example cho luồng cơ bản này.

[Tra cứu Ads](../ads/README.md) · [Tra cứu OnboardKit](../onboardkitorigin/README.vi.md)
