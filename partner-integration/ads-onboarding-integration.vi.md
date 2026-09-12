# Tích hợp Ads + OnboardKit

[← Chọn hướng dẫn](README.vi.md)

Luồng mẫu: **Splash → ngôn ngữ (LFO) → nội dung 1 → nội dung 2 → native fullscreen → nội dung 3 → inter cuối OB → MainActivity**. SDK quản lý consent, thông báo, ads và điều hướng; quảng cáo chỉ hiện khi đủ điều kiện và có fill.

Làm bước 1–6, thay **package, thông tin app, nội dung/ảnh và màn đích**. Code giữ default SDK; JSON giữ cấu hình example debug. Điền app token để bật Adjust; Firebase, app-open và mua hàng ở [bảng tùy chọn](#7-cấu-hình-chỉ-khi-app-cần).

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

Thay `<published-tag>` bằng [tag đã phát hành](https://github.com/truongvimit/adlogic-partner-sdk/tags) trong `gradle.properties` ở root app:

```properties
adlogicSdkVersion=<published-tag>
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
| `native_fs` | Trang chỉ quảng cáo — `StepId.OB3` | `fullScreenStepNative` |
| `native_ob3` | Nội dung 3 — **`StepId.OB4`** | `stepNatives[StepId.OB4]` |
| `inter_after_ob3` | Sau toàn bộ onboarding, trước màn đích | `afterOnboardingInterstitial` |

Giữ ánh xạ: `native_ob3` là nội dung 3 ở **OB4**; **OB3** là fullscreen. `inter_after_ob3` hiện sau toàn bộ OB.

SDK chọn file theo debuggable. Debug thiếu/sai JSON sẽ dùng file thật, không tự đổi live ID thành test ID. Asset debug nạp thành công mặc định chặn remote ghi đè.

ID mẫu từ [Google demo ad units](https://developers.google.com/admob/android/test-ads#demo_ad_units) và [AdMob App ID](https://developers.google.com/admob/android/quick-start). Fullscreen dùng **native ID**. Mẫu dùng chung ID test cùng format; production cần ID riêng để tách cấu hình/style/báo cáo theo placement.

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

**Placement key là danh tính duy nhất của một vị trí ads.** Ad unit ID không phân biệt được: JSON mẫu khai 45 placement mà chỉ có **8 ad unit ID** — một ID native test dùng cho 25 placement — và payload production cũng thường dùng lại một unit cho nhiều màn. Mọi thứ SDK đánh khoá theo placement: cache interstitial, đồng hồ tần suất, nhóm AutoBuffer, preload native, và mọi `ad_request` / `ad_impression` / `ad_skipped` dashboard cắt theo. Vì vậy key phải được viết đúng một chỗ.

Gõ sai một chuỗi thô không báo lỗi: `AdRemoteConfig.unit()` log warning rồi trả placeholder đã tắt, slot im lặng không bao giờ hiện. Dùng constant thì lỗi đó thành lỗi biên dịch.

- `AppAdPlacement.NATIVE_HOME` là **key** `native_home`, dùng khi load/show.
- JSON chứa **ad unit ID/config**; constants chỉ chứa key.
- `io.onboardkit.ads.AdPlacement` cố định trong SDK; app thêm slot vào `AppAdPlacement`.

Slot mới cần constant, cùng key trong hai JSON và code load/show tại màn app. Không cần file adapter nào khác cho ads: entry point nhận constant rồi tự đọc config.

### `OnboardKitSetup.kt` — nối các key OB vào SDK

Copy [OnboardKitSetup.kt](examples/ads-onboarding/OnboardKitSetup.kt) cùng package. Đổi `com.example.app` trong mọi file Kotlin; import `R`, `BuildConfig`, `MainActivity` nếu ở package khác. Mẫu đã nối ba trang nội dung + fullscreen, tầng ad đang bật và template theo JSON. Bước 5 gọi `configure()` sau `OnboardingSdk.install`.

Mẫu không đặt `contentStepNative`: tắt mọi tầng `native_ob2` thì trang 2 không lấy ad trang 1. Riêng `languageDupNative = null` fallback về native đầu; tắt thay native bằng `secondNativeOnSelectEnabled = false`.

Template theo `positionCTA`: `languageTemplate` đọc `native_lang`; `contentStepTemplate` đọc `native_ob1` cho mọi trang nội dung. Fullscreen/popup dùng layout cố định. Provider áp màu, chiều cao CTA và visibility từ ad config.

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

Chỉ thêm option cần đổi vào `onboardKitConfig { ... }` ở bước 4; `ERainAd`/`ConsentCenter` gọi tại nơi ghi trong bảng. Key `ob_*` thuộc Firebase Remote Config, **không nằm trong JSON ads**; chưa có Firebase dùng cache/default.

| Hành vi | Mặc định | Chỉ đổi khi / nơi đổi |
| --- | --- | --- |
| Màn splash | Layout SDK, tối thiểu 3 giây từ lúc tải ads | `ob_splash_min_display_ms` (3000) > 0 ghi đè `SplashConfig.minDisplayTimeMs`; remote = `0` mới dùng local. Chưa có Firebase luôn 3000 ms |
| Mở màn sau inter splash | Show inter sau thời gian tối thiểu. LFO lần đầu: chờ đóng inter. Launcher → app/khảo sát người dùng cũ: mở dưới inter. Notification/widget/uninstall: chờ đóng | Override `SplashActivity.nextScreenTiming()`: `NextScreenTiming.AFTER_AD`/`UNDER_AD` (`io.onboardkit.ads`), hoặc `super.nextScreenTiming()` để giữ mặc định |
| Chờ quảng cáo splash | Tối đa 60 giây sau notification và khi splash có focus | Remote `ob_splash_ad_budget_ms`; không tự thêm timer |
| Fetch và load ads | `AdLoadStrategy.ALTERNATE`: chờ remote trước khi load | `SplashConfig.adLoadStrategy` khi app chấp nhận dùng ID trước fetch |
| Preload native LFO đầu | Sau waterfall inter splash kết thúc/hết budget | Remote `ob_splash_lfo_parallel_preload_enabled = true` nếu cần tải song song |
| Không có mạng | Hiện yêu cầu kết nối, chưa đi tiếp | `SplashConfig.noInternetPromptEnabled = false` để mở offline qua fallback UMP bên dưới; splash lần sau hỏi UMP lại |
| Consent | Timeout mạng 20 giây; form chờ người dùng | Trong Application: `ConsentCenter.configure(ConsentOptions(timeoutMs = ...))` (`com.ads.module.consent`). Giữ hook SDK; `SplashConfig.consentTimeoutMs` không đổi timeout UMP |
| Form UMP khi QA | Debuggable: mọi máy là EEA (`setForceTesting`), không cần hashed id; release: địa lý thật | `ConsentOptions(debug = false)` để debug theo địa lý thật. `configure` thay toàn bộ option; muốn đổi cả timeout dùng một lần `ConsentOptions(timeoutMs = ..., debug = false)` |
| Thông báo | Hỏi sau consent trên Android 13+/target 33+; từ chối vẫn đi tiếp và không tự hỏi lại | `SplashConfig.notificationPermissionEnabled = false` nếu app không gửi thông báo hoặc tự hỏi |
| Ngôn ngữ | 21 ngôn ngữ, hand hint sau 3 giây, ẩn xác nhận trước chọn | `LanguageConfig.languages`: giữ ngôn ngữ đã dịch; `tapHintEnabled`, `confirmVisibleBeforeSelect` còn cần cờ remote tương ứng bật |
| Back ở LFO | Chưa chọn: bỏ qua Back. Đã chọn: hiện Save, vẫn ở màn ngôn ngữ | `LanguageConfig.saveButtonOnBackEnabled = false`: bỏ qua Back cả sau khi chọn. SETTINGS Back đóng màn |
| Thay native sau chọn ngôn ngữ | Bật; native đầu giữ nguyên đến khi ad thay thế bind được | `LanguageConfig.secondNativeOnSelectEnabled = false` để tắt |
| Popup ngôn ngữ | Từ click thứ 4, kể cả chọn lại item; native request lần đầu khi mở popup | `LanguageConfig.confirmDialogOnReselectEnabled = false` để tắt; SETTINGS không hiện popup |
| Native template | SDK: LFO CTA dưới, nội dung CTA trên; JSON mẫu chọn `BOTTOM` cho cả hai qua mapping bước 4 | Đổi `positionCTA` của `native_lang` / `native_ob1` hoặc fallback trong setup |
| System bars | Hiện status/caption bar, ẩn navigation bar | `SystemBarConfig(showStatusBar, showNavigationBar, showCaptionBar)` |
| Click native rồi quay lại OB | Next bước (`BehaviorConfig.adClickReturnCompletesStep = true`); OB/OB5 tắt preload thay native khi click | `adClickReturnCompletesStep = false` để ở lại; không bật lại click preload ở provider |
| Click native ở LFO/popup hoặc màn app | Preload ngay khi click/open; quay lại bind ad sẵn có hoặc chờ request đang chạy | `NativeAdConfig.reloadOnAdClick = true` mặc định, độc lập refresh theo thời gian; [ví dụ native trong app](#native-ở-màn-app-dùng-placement-constant) |
| Mở lại khi chưa xong flow | Chạy lại Splash → LFO → OB; chỉ bỏ OB khi hoàn thành toàn bộ | Không cần tự lưu cờ first-open/checkpoint trong app |
| Trang native fullscreen | Nút X sau 1 giây, auto-next sau 3 giây từ lúc chọn trang; thời gian background vẫn được tính | Các trường của `AdFullScreenStepDefinition`; remote `ob_skip_button_delay_sec = -1` giữ delay local |
| Inter cuối onboarding | Preload lúc vào pager, đợi fill tối đa 8 giây khi hoàn thành; mở màn dưới inter. Notification/widget/uninstall: chờ đóng | `AdsConfig.afterOnboardingInterstitialTiming = NextScreenTiming.AFTER_AD` để luôn chờ đóng; `afterOnboardingInterstitialEnabled = false` nếu app tự quản lý. Không đưa vào `InterstitialAutoBuffer` |
| Điều hướng OB | Khóa swipe pager; vuốt tiến ở trang cuối vẫn có thể hoàn tất; Back lùi một trang, ở trang đầu thì thoát app; khóa portrait | `BehaviorConfig.lockPagerSwipe`, `swipeCompletesLastStep`, `backNavigatesBack` (`false`: Back luôn thoát app), `lockPortrait`; app ngang cần sửa cả manifest |
| Khoảng cách interstitial | `ERainAdConfig.intervalInterstitialAd = 0` (không giới hạn); chỉ áp nhóm `InterstitialAutoBuffer`, không áp splash/OB/inter tự load | Đặt trước init hoặc dùng `ERainAd.getInstance().setIntervalInterstitialAd(giây)` |
| Giới hạn click interstitial | Tắt (`0`) | `ERainAd.getInstance().setMaxClickAdsPerDay(n)`: mỗi ad unit tối đa `n` click/24 giờ rồi ngừng load/show. Gọi lúc cần, thường sau fetch remote |
| OB5, khảo sát, paywall, app-open | `ob_enable_step_ob5 = false`. Bật OB5: mở dưới inter cuối nếu native đã tải, chưa có thì bỏ qua. `ob5Native` null dùng `fullScreenStepNative` (`native_fs`). Các tính năng còn lại chưa nối | `AdsConfig.ob5Native` để đặt ID riêng; chỉ nối khảo sát/paywall/app-open khi cần |

UMP lỗi/timeout có thể cho **thử request** trong process qua [fallback AdLogic](../ads/src/main/java/com/ads/module/consent/ConsentCenter.kt), không cấp consent hay đảm bảo fill. Host tắt ads vẫn được ưu tiên; không tự suy quyền request từ timer/personalization.

### Field trong JSON mẫu

Hai JSON giữ field/giá trị example debug, chỉ chuẩn hóa interstitial sang ID test.

| Field | Giá trị trong mẫu | Cách dùng / phạm vi áp dụng |
| --- | --- | --- |
| `id` | Ad unit test đúng format | Thay ID ở file thật khi phát hành; không sửa key placement. |
| `isEnable` | Theo example: đa số `true`, welcome `false` | Bật/tắt placement. Key gốc là công tắc tổng: `false` ở key gốc tắt cả waterfall. |
| `enable_ua_check` | Có `true` và `false` | `true` chỉ cho paid/non-organic; SDK tự áp khi load và show, gồm cả app-resume. Mặc định organic tới khi Adjust trả attribution: chưa dùng Adjust phải đặt `false` cho slot app cần hiện. Provider OB không áp UA gate từ JSON, **trừ `inter_after_ob3`** — key này trùng key JSON nên SDK áp cả `isEnable` lẫn `enable_ua_check` cho nó; đặt `true` là tắt inter cuối OB với install organic. |
| `reloadIntervalSeconds` | Banner: `30` | Chỉ parse, helper không dùng; không đổi refresh kể cả splash. Muốn refresh xem [Banner ở màn app](#tích-hợp-bổ-sung). |
| `colorCTA` | `"default"` | Giữ màu template; thay màu khi cần tùy biến native. |
| `heightCTA` | Native thường `45`, popup `36` | Chiều cao CTA (dp); SDK dùng `40` nếu bỏ field và ép giá trị vào khoảng 36–52 khi áp dụng. |
| `positionCTA` | `"BOTTOM"` hoặc `null` | Mapping bước 4 chọn template LFO/nội dung từ key gốc. `null` dùng fallback SDK; fullscreen/popup vẫn dùng layout riêng. |
| `components` | `["icon_headline", "body", "media", "cta"]` | Khối thiếu bị ẩn, mảng rỗng hiện đủ. OB chỉ đổi visibility; [native màn app](#native-ở-màn-app-dùng-placement-constant) dùng cả thứ tự khi `positionCTA: null`. |
| `app_resume_load_delay_ms` | `open_resume`: `2000` | Thời gian chờ tải app-open sau khi app ra background; chỉ có tác dụng khi đã bật app-resume. |

Waterfall đọc `_high`, `_high1`… rồi key gốc; có thể dùng `ids` để khai báo nhiều tầng trong một entry. ID trùng bị loại; dùng ID riêng khi cần kiểm tra từng tầng/style.

### Native ở màn app dùng placement constant

<details>
<summary>Mở ví dụ native và preload cho màn app</summary>

Gọi ở màn app sau consent, khi `AppCompatActivity` resumed; `container` là `FrameLayout`. OB đã tự xử lý native.

**Chưa dùng Adjust:** đổi `enable_ua_check` của `native_home` thành `false` trong **cả hai JSON** để slot mẫu có thể hiện; giữ ID test khi QA.

```kotlin
import com.ads.module.helper.adnative.NativeAdHelper

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
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager

InterstitialAdManager.load(applicationContext, AppAdPlacement.INTER_BACK)

InterstitialAdManager.show(this, AppAdPlacement.INTER_BACK, object : InterShowCallback() {
    override fun onComplete() = goNext()
})
```

SDK tự đọc waterfall, `isEnable`, `enable_ua_check`, consent/premium, interval và readiness của placement. Chỉ điều hướng ở `onComplete`, chạy đúng một lần kể cả thiếu ad/show lỗi; không dùng `onClosed`, không tự kiểm tra `canShow()` trước. `load` không request lại nếu đang tải/đã có ad. Có ad thì chờ dialog khoảng 800 ms rồi show.

Mặc định callback chạy sau khi ad đóng. Thêm `nextAction = InterNextAction.UnderAd` để callback chạy ngay khi ad hiện — chỉ dùng cho `startActivity` mở màn thường, không `finish()` hay mở camera/audio/video dưới ad.

Tự giữ sẵn inter: [InterstitialAutoBuffer](../ads/README.md#automatic-interstitial-preload) `configure` sau `ERainAd.init`, `start` ở màn nội dung đầu tiên; show như trên. Khoảng cách/click cap ở [bảng mặc định](#mặc-định-của-luồng).

</details>

### Tích hợp bổ sung

| Nhu cầu | Mặc định / cách cấu hình |
| --- | --- |
| Tắt một slot | `isEnable: false` ở **key gốc** là tắt cả waterfall, cho mọi format; thử asset cần khởi động lại. LFO native thứ hai có fallback ở bước 4. |
| Thêm waterfall | `<key>_high`, `<key>_high1`…`<key>_high9`, rồi key gốc. Banner splash chỉ dùng ID đầu của `banner_splash`, không đọc tầng rời. |
| CTA/native style | Giữ đủ field như example; [bảng field JSON](#field-trong-json-mẫu) giải thích giá trị và nơi áp dụng. Bước 4 đã nối `positionCTA` vào native template. |
| Banner ở màn app | `BannerAdHelper.forPlacement(this, this, "banner_home", container)`; thêm đối số `bannerType`, ví dụ `BannerType.Collapsible()` ([các loại](../ads/src/main/java/com/ads/module/helper/banner/BannerType.kt)). Đổi loại: `flagUserEnableReload = false`, `cancel()` helper cũ rồi tạo mới. SDK refresh cần tắt refresh AdMob cho mọi tầng rồi dựng `BannerAdConfig.forPlacement("banner_home", bannerType, canReloadAds = true)` và truyền vào constructor `BannerAdHelper`. |
| UA/Adjust | Điền resource và dùng wiring ở [bước 5](#adjust-token-và-kiểm-tra). `enable_ua_check` giữ giá trị example; xem phạm vi áp dụng trong bảng field JSON. |
| JSON từ Firebase | [Setup Firebase](firebase-integration.vi.md), `AdConfig.install(FirebaseAdConfigSource())` sau asset trong Application. Publish String `ad_remote_config` chứa **toàn bộ JSON** để thay config hiện tại; thêm hook splash bên dưới. |
| Firebase Analytics | Cài `suite-firebase`, đăng ký `Tracker.addSink(FirebaseSink())` ngay sau `Tracker.install`; chọn consent policy theo [hướng dẫn Firebase](firebase-integration.vi.md#consent-ban-đầu). |
| App-open khi quay lại | Chưa bật; JSON có sẵn `open_resume` nhưng chỉ thêm JSON chưa bật tính năng. Làm theo [App-open khi quay lại](#app-open-khi-quay-lại). |
| App có premium / paywall | Làm [BillingKit](billing-integration.vi.md) / [PayKit](paywall-integration.vi.md) và [hook chờ billing trước ads](../onboardkitorigin/README.vi.md#tích-hợp-tùy-chọn). `onInitBilling()` mặc định trống; gọi install billing chưa có nghĩa đã khôi phục premium. |
| Đổi ngôn ngữ từ Settings | `registerForActivityResult(StartActivityForResult())`, rồi `launch(ObLanguageActivity.intentFor(activity, LanguageScreenMode.SETTINGS))` (kiểu trong `io.onboardkit.ui.language`). `RESULT_OK`: lấy `ObLanguageActivity.RESULT_LANGUAGE_CODE`, lưu như bước 5 và `recreate()`; Back không trả mã. Màn này không ads. `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)` không trả result; đọc lựa chọn qua `OnboardingSdk.selectedLanguage()`. |
| Entry từ notification/widget | Dùng `SplashEntry` và giữ passthrough trong listener; xem [OnboardKit](../onboardkitorigin/README.vi.md#tích-hợp-tùy-chọn). Không cần các entry này cho launcher thông thường. |

Khi dùng remote JSON, thay class splash ở bước 6 bằng:

```kotlin
package com.example.app

import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity() {
    override fun onRemoteFetched() {
        OnboardKitSetup.configure()
    }
}
```

SDK fetch trước hook; `configure()` dựng lại mapping. Không fetch lần hai hay copy `RemoteConfigUtils`. Không dùng remote/preload/app-open thì giữ splash một dòng.

### App-open khi quay lại

<details>
<summary>Mở các bước bật app-open</summary>

Chỉ làm khi sản phẩm dùng app-open. `AppOpenManager` thuộc `com.ads.module.admob`.

1. **Bước 4**, trong `AdsConfig`: `appResume = adConfig.interstitial(AppAdPlacement.OPEN_RESUME)`. Thiếu trường này, OnboardKit chặn app-open ở **mọi màn**, kể cả màn app.
2. **Bước 5**, trong khối `apply` của `ERainAdConfig`: `idAdResume = AdGate.adUnitIds(AppAdPlacement.OPEN_RESUME).firstOrNull().orEmpty()`.
3. **Remote JSON:** không cần thêm gì — SDK tự trỏ lại ID app-resume theo `open_resume` mỗi lần config đổi, gồm cả bật/tắt bằng `isEnable`. Hai điều kiện: app đã đặt sẵn một ID app-resume khác rỗng ở bước 2, và `open_resume` có ad unit ID. Vì bước 2 đọc lúc init (khi đó mới có config từ asset), hãy ship `open_resume` **bật kèm ID thật** trong `ad_config.json`.
4. **Intent ra ngoài** (browser/share/review): gọi `AppOpenManager.getInstance().disableAdResumeByClickAction()` ngay sau `startActivity(...)` để bỏ qua lần quay lại. `disableAppResume()`/`enableAppResume()` là công tắc cả process.

Splash/OB5/khảo sát tự loại trừ; chỉ đăng ký thêm màn nhạy cảm của app. LFO/trang nội dung OB có thể hiện app-open sẵn có khi quay lại, trừ fullscreen, lúc chuyển trang hoặc mở popup. Delay/gate xem [app-open](../onboardkitorigin/README.vi.md#app-open-khi-quay-lại-app).

</details>

## 8. Kiểm tra hoàn tất

- [ ] Debug build mở được splash, Logcat tag `AdRemoteConfig` có dòng `Loaded ad_config_debug.json with 45 placements (debug=true)`, `OB_FLOW` không báo config/provider lỗi.
- [ ] Đi hết LFO → OB → MainActivity bằng ad test; native fullscreen nằm giữa nội dung 2 và 3, inter cuối chỉ do SDK quản lý. LFO chỉ mở sau khi đóng inter splash; MainActivity đã sẵn khi đóng inter cuối.
- [ ] LFO: chọn ngôn ngữ rồi Back thì hiện Save và vẫn ở lại; chọn item lần thứ 4 mở popup.
- [ ] Từ chối notification vẫn đi tiếp; Home/quay lại khi ở splash, LFO, popup và OB không điều hướng lặp. Click native ở trang OB rồi quay lại chuyển bước; ở LFO/popup thì ở lại và bind ad thay thế khi sẵn sàng.
- [ ] Tắt cả `native_ob2` và `native_ob2_high`: trang nội dung 2 vẫn hiện, không lấy native trang 1. Tắt cả `native_fs` và `native_fs_high`: bỏ trang chỉ quảng cáo. Tắt `inter_splash`, `inter_after_ob3` và mọi tầng `_high*` của chúng: vẫn tới màn đích.
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
| Remote đổi ID nhưng OB vẫn dùng ID cũ | Đã cài `FirebaseAdConfigSource` và gọi lại setup trong `onRemoteFetched()`; debug mặc định cố ý giữ asset |
| Native style/reporting không tách từng slot khi test | ID demo cùng format đang dùng chung; provider có chỗ tra ngược style/placement theo ID. Dùng ID riêng khi kiểm tra cấu hình thực tế |

## Các file app thực sự cần

| File | Việc làm |
| --- | --- |
| `settings.gradle`, `app/build.gradle` | Ghép cấu hình build, hai dependency |
| `app/src/main/AndroidManifest.xml` | Metadata, Application và launcher splash |
| `app/src/main/res/values/id_ads.xml` | AdMob App ID, Meta credential và Adjust token/event token |
| `app/src/main/assets/ad_config.json`, `ad_config_debug.json` | Copy hai JSON mẫu |
| `strings.xml` và drawable của app | Nội dung, bản dịch, ảnh cho ba trang |
| `AppAdPlacement.kt` | Danh mục key OB và key riêng của app; không chứa ad unit ID |
| [OnboardKitSetup.kt](examples/ads-onboarding/OnboardKitSetup.kt) | Ánh xạ nội dung, placement constants và native template |
| Application hiện có hoặc [PartnerApp.kt](examples/ads-onboarding/PartnerApp.kt) | Khởi tạo SDK một lần và chọn màn đích |
| `SplashActivity.kt` | Kế thừa splash SDK |

`MainActivity` là màn đích hiện có của app. Ngoài `AppAdPlacement.kt`, không cần file nào để dịch JSON sang config ads — mọi entry point nhận thẳng placement key. Không cần sao chép `AppConstants`, `RemoteConfigUtils`, `ResumeAdsEntryRule`, `AppLifecycleObserver`, DevConfig, Hilt hay các màn paywall/welcome/uninstall từ example cho luồng cơ bản này.

[Tra cứu Ads](../ads/README.md) · [Tra cứu OnboardKit](../onboardkitorigin/README.vi.md)
