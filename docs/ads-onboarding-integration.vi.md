# Tích hợp Ads + OnboardKit

[← Chọn hướng dẫn](README.md)

Luồng mẫu: **Splash → ngôn ngữ (LFO) → nội dung 1 → nội dung 2 → native fullscreen → nội dung 3 → inter cuối OB → MainActivity**. SDK xử lý consent, quyền thông báo, preload/show ads, chuyển màn và lưu trạng thái. Ads chỉ xuất hiện khi đủ điều kiện và có fill.

Làm bước 1–6 để tích hợp. Chỉ thay **package, thông tin app, nội dung/ảnh và màn đích**; các trường không khai báo giữ mặc định SDK. Firebase, Adjust, app-open và mua hàng có hướng dẫn ở [bảng tùy chọn](#7-cấu-hình-chỉ-khi-app-cần).

Ví dụ dependency và các mặc định trong guide áp dụng cho **5.2.10**. Xem [khác biệt phiên bản](#khác-biệt-phiên-bản) nếu nâng cấp từ 5.2.9.

## 1. Thêm dependency

Yêu cầu: JDK 17, `minSdk 24+`, `compileSdk 36+`. Repo dùng Kotlin 2.1.0, AGP 8.12.0, Gradle 8.13. Các đoạn Gradle dưới đây dùng Groovy, ghép vào các block hiện có.

Trong `settings.gradle`, bổ sung repository còn thiếu vào nơi app đang quản lý repository:

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

def sdkVersion = '5.2.10'
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
}
```

Giữ `targetSdk` theo cấu hình phát hành của app; repo đang dùng 36. Sync Gradle thành công rồi tiếp tục. Hai module đã mang theo Google Mobile Ads, mediation và Trackkit; không thêm dependency GMA/UMP riêng hay `MobileAds.initialize()` lần nữa. App Android thông thường đã có AndroidX/AppCompat; dùng `MainActivity` hiện có.

## 2. Thêm ID và hai file JSON

### `app/src/main/res/values/id_ads.xml`

```xml
<resources>
    <string name="admob_app_id" translatable="false">ca-app-pub-3940256099942544~3347511713</string>
    <string name="facebook_app_id" translatable="false">YOUR_META_APP_ID</string>
    <string name="facebook_client_token" translatable="false">YOUR_META_CLIENT_TOKEN</string>
</resources>
```

AdMob App ID phía trên là ID test của Google, chứa **`~`**. Điền Meta App ID/client token do team cung cấp trước khi chạy: `ERainAd.init()` hiện khởi tạo Facebook SDK cả khi không bật Adjust. Các chuỗi `YOUR_META_*` là chỗ phải thay, không phải credential test. Chưa dùng Adjust thì không cần `adjust_token` hay event token.

### `app/src/main/assets/`

Copy nguyên hai file sau, giữ đúng tên; tạo folder `assets` nếu app chưa có:

- **[ad_config.json](examples/ads-onboarding/ad_config.json):** cấu hình cho bản release; hiện toàn bộ ID là test.
- **[ad_config_debug.json](examples/ads-onboarding/ad_config_debug.json):** cấu hình debug; giữ ID test.

Ad unit ID trong JSON chứa **`/`**. Mỗi entry chỉ cần `id` và `isEnable`. Không copy toàn bộ JSON của app example vì còn placement của các tính năng khác.

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

`native_ob3` đếm **trang nội dung**; `StepId.OB3` đếm **vị trí trong flow**. Giữ ánh xạ trên để không gắn nhầm native vào trang fullscreen. `inter_after_ob3` là tên placement có sẵn, không có nghĩa là show ngay sau `StepId.OB3`.

SDK tự chọn file theo cờ debuggable của app. Nếu file debug thiếu hoặc parse lỗi, SDK thử dùng file thật; SDK không tự đổi live ID thành test ID. Debug nạp asset thành công sẽ chặn remote JSON ghi đè theo mặc định.

Nguồn ID mẫu: [Google demo ad units](https://developers.google.com/admob/android/test-ads#demo_ad_units) và [AdMob App ID mẫu](https://developers.google.com/admob/android/quick-start). Native fullscreen dùng **native ID**, không dùng interstitial ID. ID test cùng format được dùng chung trong mẫu; khi chạy thật nên cấp ID riêng cho từng placement để phân biệt cấu hình, style và báo cáo theo ID.

## 3. Chuẩn bị nội dung onboarding

Dùng `app_name`, icon launcher và `MainActivity` của app. Thêm sáu string sau vào `app/src/main/res/values/strings.xml` đang có, thay nội dung theo sản phẩm:

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

Thêm bản dịch vào các folder `values-<language>` app hỗ trợ. Trong code bước 4, thay ba `imageRes` bằng drawable của app. Để thử nhanh, copy [img_onboard_sample_1.png](../app/src/main/res/drawable-nodpi/img_onboard_sample_1.png), [img_onboard_sample_2.png](../app/src/main/res/drawable-nodpi/img_onboard_sample_2.png), [img_onboard_sample_4.png](../app/src/main/res/drawable-nodpi/img_onboard_sample_4.png) từ example vào `app/src/main/res/drawable-nodpi/` của app.

Không cần tạo layout hay Activity cho LFO, popup, từng trang OB hoặc native ad: SDK đã cung cấp.

## 4. Tạo `OnboardKitSetup.kt`

Đặt trong package app, ví dụ `app/src/main/java/com/example/app/OnboardKitSetup.kt`. Thay `com.example.app` trong cả ba file Kotlin của guide bằng package app; import `R`, `BuildConfig` và `MainActivity` của app nếu chúng ở package khác.

Đây là phần rút gọn từ [OnboardKitSetup của example](../app/src/main/java/com/itg/template/app/OnboardKitSetup.kt): chỉ nội dung và ánh xạ quảng cáo, không gán lại các default.

```kotlin
package com.example.app

import com.ads.module.config.AdRemoteConfig
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.*
import io.onboardkit.core.StepId

object OnboardKitSetup {
    fun configure() {
        val adConfig = AdRemoteConfig.getInstance()
        val config = onboardKitConfig {
            splash = SplashConfig(
                logoRes = R.mipmap.ic_launcher,
                appNameRes = R.string.app_name,
            )
            steps(
                ContentStepDefinition(
                    StepId.OB1,
                    titleRes = R.string.onboarding_title_1,
                    subtitleRes = R.string.onboarding_des_1,
                    imageRes = R.drawable.img_onboard_sample_1,
                ),
                ContentStepDefinition(
                    StepId.OB2,
                    titleRes = R.string.onboarding_title_2,
                    subtitleRes = R.string.onboarding_des_2,
                    imageRes = R.drawable.img_onboard_sample_2,
                ),
                AdFullScreenStepDefinition(StepId.OB3),
                ContentStepDefinition(
                    StepId.OB4,
                    titleRes = R.string.onboarding_title_3,
                    subtitleRes = R.string.onboarding_des_3,
                    imageRes = R.drawable.img_onboard_sample_4,
                ),
            )
            ads = AdsConfig(
                splashBanner = adConfig.unit("banner_splash")
                    .takeIf { it.isUsable }?.let { BannerAdUnit(it.waterfallIds.first()) },
                splashInterstitial = adConfig.interstitial("inter_splash"),
                languageNative = adConfig.native("native_lang"),
                languageDupNative = adConfig.native("native_lang_alt"),
                languageConfirmNative = adConfig.native("native_popup_lang"),
                fullScreenStepNative = adConfig.native("native_fs"),
                stepNatives = listOfNotNull(
                    adConfig.native("native_ob1")?.let { StepId.OB1 to it },
                    adConfig.native("native_ob2")?.let { StepId.OB2 to it },
                    adConfig.native("native_ob3")?.let { StepId.OB4 to it },
                ).toMap(),
                afterOnboardingInterstitial = adConfig.interstitial("inter_after_ob3"),
            )
        }.getOrThrow()
        OnboardingSdk.configure(config).getOrThrow()
    }

    private fun AdRemoteConfig.native(key: String): NativeAdUnit? =
        tiersFor(key).takeIf { it.isNotEmpty() }?.let { NativeAdUnit(tiers = it) }

    private fun AdRemoteConfig.interstitial(key: String): InterstitialAdUnit? =
        tiersFor(key).takeIf { it.isNotEmpty() }?.let { InterstitialAdUnit(tiers = it) }
}
```

Hai hàm nhỏ đọc ID đang bật, hỗ trợ thêm waterfall về sau và trả `null` khi slot tắt. Không đặt `contentStepNative` dự phòng trong mẫu này: tắt `native_ob2` thì trang 2 không tự dùng quảng cáo trang 1. Riêng `languageDupNative = null` có fallback SDK về native đầu; muốn tắt hẳn lần thay native, dùng `secondNativeOnSelectEnabled = false`.

**Có cần tạo `AdPlacement.kt` không?** Không. SDK đã có [`io.onboardkit.ads.AdPlacement`](../onboardkitorigin/src/main/java/io/onboardkit/ads/AdPlacement.kt). Các key nội bộ như `language1`, `step_ob4` khác key JSON; ánh xạ trong `AdsConfig` nối hai bên. SDK cũng tự đăng ký ID cho tracking khi nạp JSON. Không cần thêm file constants, registry hay extension cho từng placement để chạy luồng này.

## 5. Khởi tạo trong Application

Ghép vào `Application.onCreate()` hiện có; nếu chưa có, tạo `PartnerApp.kt` bên dưới. Giữ nguyên base class/annotation nếu app đang dùng Hilt hoặc base Application riêng.

```kotlin
package com.example.app

import android.app.Application
import android.content.Intent
import com.ads.module.ads.ERainAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.ERainAdConfig
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.ads.erain.ERainTuning
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome
import io.trackkit.Tracker
import io.trackkit.TrackerConfig

class PartnerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Tracker.install(this, TrackerConfig(appVersionCode = BuildConfig.VERSION_CODE.toLong()))
        AdRemoteConfig.initializeFromAssets(this)

        val environment = if (BuildConfig.DEBUG) ERainAdConfig.ENVIRONMENT_DEVELOP
                          else ERainAdConfig.ENVIRONMENT_PRODUCTION
        ERainAd.getInstance().init(this, ERainAdConfig(this, environment))
        ERainTuning.install()

        OnboardingSdk.install(this) {
            adProvider = ERainAdProvider()
            listener = OnboardingListener { context, outcome ->
                val extras = when (outcome) {
                    is OnboardingOutcome.Completed -> outcome.passthrough
                    is OnboardingOutcome.Skipped -> outcome.passthrough
                    is OnboardingOutcome.Aborted -> null
                }
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .apply { extras?.let { putExtras(it) } },
                )
            }
        }
        OnboardKitSetup.configure()
        OnboardingSdk.setFlowLogging(BuildConfig.DEBUG)
    }
}
```

Thứ tự: **Tracker → JSON → ERainAd → ERainTuning → install OnboardKit → configure**. Meta token được đọc từ manifest, không cần gán lại trong `ERainAdConfig`. `Tracker.install()` chưa gửi dữ liệu tới Firebase/backend; chỉ thêm sink nếu cần thu thập event.

Listener mẫu đưa cả `Completed`, `Skipped`, `Aborted` về màn chính; app có chính sách thoát riêng có thể đổi nhánh `Aborted`. Giữ `NEW_TASK`, không thêm `CLEAR_TASK` vì mặc định splash có thể mở màn tiếp theo dưới interstitial. Đừng tự đánh dấu hoàn thành OB hay gọi điều hướng ở một timer khác.

**Ngôn ngữ của màn app:** SDK áp dụng locale cho màn SDK. Nếu app có cơ chế locale/preferences riêng, nối `Completed.selectedLanguage` vào cơ chế đó trước khi mở màn chính; dùng `OnboardingSdk.selectedLanguage()` (suspend) để đọc lại lựa chọn đã lưu khi cần. SDK không tự dịch nội dung hay cập nhật preferences riêng của app.

## 6. Đăng ký splash làm launcher

Tạo `SplashActivity.kt` cùng package:

```kotlin
package com.example.app

import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity()
```

Ghép vào `app/src/main/AndroidManifest.xml`, thay tên class theo package thực tế. Nếu Application/MainActivity đã khai báo thì sửa entry đang có. Chuyển intent-filter launcher cũ sang splash, chỉ giữ một launcher cho luồng này.

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
        android:configChanges="orientation|screenSize|keyboardHidden"
        android:theme="@style/ob_Theme_OnboardKit">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
</application>
```

Các màn SDK, quyền mạng, `AD_ID` và `POST_NOTIFICATIONS` đã được thư viện khai báo. Kiểm tra **Merged Manifest** có đúng Application, launcher, metadata và theme. Nếu app còn khai báo AdMob ID bằng `${app_id}`, thay entry đó bằng mẫu resource trên để chỉ có một nơi cấp ID.

Splash đã quản lý UMP/notification, tải ads và điều hướng. Luồng cơ bản không cần override `onCreate`, `onDestroy`, gọi consent, `OnboardingSdk.start()`, preload/show thủ công hay thêm observer app lifecycle.

## 7. Cấu hình chỉ khi app cần

### Mặc định của luồng

Các trường local đặt trong `onboardKitConfig { ... }` ở bước 4; chỉ thêm trường muốn đổi. Các key `ob_*` thuộc Firebase Remote Config, **không đặt trong `ad_config.json`**. Chưa cấu hình Firebase thì SDK dùng cache/mặc định.

| Hành vi | Mặc định | Chỉ đổi khi / nơi đổi |
| --- | --- | --- |
| Màn splash | Layout SDK, tối thiểu 3 giây; launcher mở màn tiếp theo dưới inter | `SplashConfig.minDisplayTimeMs`; remote `ob_splash_min_display_ms` cũng có mặc định 3000, nên đồng bộ nếu đổi thời gian |
| Chờ quảng cáo splash | Tối đa 60 giây sau notification và khi splash có focus | Remote `ob_splash_ad_budget_ms`; không tự thêm timer |
| Fetch và load ads | `AdLoadStrategy.ALTERNATE`: chờ remote trước khi load | `SplashConfig.adLoadStrategy` khi app chấp nhận dùng ID trước fetch |
| Preload native LFO đầu | Sau waterfall inter splash kết thúc/hết budget | Remote `ob_splash_lfo_parallel_preload_enabled = true` nếu cần tải song song |
| Không có mạng | Hiện yêu cầu kết nối, chưa đi tiếp | `SplashConfig.noInternetPromptEnabled = false` nếu app cần mở offline |
| Consent | SDK quản lý UMP, timeout mạng mặc định 20 giây; form đang hiện chờ người dùng | Giữ hook consent mặc định; xem ghi chú dưới bảng |
| Thông báo | Hỏi sau consent trên Android 13+/target 33+; từ chối vẫn đi tiếp và không tự hỏi lại | `SplashConfig.notificationPermissionEnabled = false` nếu app không gửi thông báo hoặc tự hỏi |
| Ngôn ngữ | Danh sách SDK, hand hint sau 3 giây, ẩn nút xác nhận trước chọn | `LanguageConfig.languages`, `tapHintEnabled`, `confirmVisibleBeforeSelect`; các cờ remote liên quan cũng phải bật |
| Thay native sau chọn ngôn ngữ | Bật; native đầu giữ nguyên đến khi ad thay thế bind được | `LanguageConfig.secondNativeOnSelectEnabled = false` để tắt |
| Popup ngôn ngữ | Bật; native popup chỉ request khi mở popup | `LanguageConfig.confirmDialogOnReselectEnabled = false`; mốc mở popup tùy phiên bản bên dưới |
| Native template | Ngôn ngữ CTA dưới; trang nội dung CTA trên | `AdsConfig.languageTemplate`, `contentStepTemplate` |
| Trang native fullscreen | Nút X sau 1 giây, auto-next sau 3 giây từ lúc chọn trang | Các trường của `AdFullScreenStepDefinition`; remote `ob_skip_button_delay_sec = -1` giữ delay local |
| Inter cuối onboarding | Tự preload khi vào pager; đợi fill tối đa 8 giây khi hoàn thành | `AdsConfig.afterOnboardingInterstitialEnabled = false` nếu app tự quản lý; không đưa placement này vào AutoBuffer của màn chính |
| Điều hướng OB | Khóa swipe pager; vuốt tiến ở trang cuối vẫn có thể hoàn tất; khóa portrait | `BehaviorConfig.lockPagerSwipe`, `swipeCompletesLastStep`, `lockPortrait`; app ngang cần sửa cả manifest |
| Khoảng cách interstitial | `ERainAdConfig.intervalInterstitialAd = 0` (không giới hạn khoảng cách) | Đặt trước `ERainAd.init` nếu sản phẩm cần; có thể ảnh hưởng cả inter splash/lần mở lại |
| OB5, khảo sát, paywall, app-open | Chưa cấu hình trong mẫu | Chỉ thêm khi sản phẩm cần; không cần tạo sẵn key/Activity/helper cho chúng |

UMP lỗi hoặc timeout mạng có thể mở quyền **thử request** theo fallback riêng của AdLogic trong process hiện tại; đây không phải consent đồng ý và không đảm bảo fill. Giới hạn tắt ads từ host vẫn được ưu tiên. Giữ hành vi SDK, không tự suy ra quyền request từ timer hoặc lựa chọn cá nhân hóa. Xem [ConsentCenter](../ads/src/main/java/com/ads/module/consent/ConsentCenter.kt).

### Khác biệt phiên bản

| Hành vi | Tag 5.2.9 | Tag 5.2.10 trong dependency mẫu |
| --- | --- | --- |
| System bars mặc định | Hiện status/navigation bar | Hiện status/caption bar, ẩn navigation bar |
| Click native ở trang OB rồi quay lại | Mặc định hoàn thành bước (`adClickReturnCompletesStep = true`) | Giữ hoàn thành bước; tắt `reloadOnAdClick` ở mọi step OB. Native ngoài step preload ngay lúc click, show/chờ khi quay lại |
| Popup xác nhận ngôn ngữ | Khi chọn lại ngôn ngữ đang chọn | Từ lần click item thứ 4, kể cả item đang chọn |
| Đóng app khi OB chưa hoàn thành | Có thể tiếp tục theo checkpoint đã lưu | Mở lại đi từ Splash → LFO → OB; chỉ bỏ qua khi đã hoàn tất toàn bộ flow |

Các thay đổi này có trong **5.2.10**; xem [ghi chú OnboardKit](../onboardkitorigin/README.vi.md#phiên-bản-5210).

### JSON và tích hợp bổ sung

| Nhu cầu | Mặc định / cách cấu hình |
| --- | --- |
| Tắt một slot | Đặt `isEnable: false` ở entry tương ứng, rồi khởi động lại khi thử asset. Giữ key để dễ bật lại. Nếu có waterfall, tắt tất cả các tầng của slot. LFO native thứ hai có fallback như ghi ở bước 4. |
| Thêm waterfall | Thêm `<key>_high`, `<key>_high1`…`<key>_high9` cùng cấu trúc; các hàm ở bước 4 đọc theo thứ tự đó rồi đến key gốc. Banner mẫu chỉ dùng ID đầu của entry `banner_splash`, không đọc các key tầng rời. |
| CTA/native style | JSON mặc định `colorCTA: "default"`, `heightCTA: 40`, `components: ["icon_headline", "body", "media", "cta"]`. Thêm trường khi cần. Guide giữ native template mặc định; `positionCTA` trong JSON cần thêm ánh xạ template như example mới đổi layout OB. |
| UA/Adjust | `enable_ua_check` mặc định `false`; Adjust chưa bật. Chỉ thêm `AdjustConfig` và token của app khi cần attribution; xem [Ads — optional integrations](../ads/README.md#optional-integrations). |
| JSON từ Firebase | Làm [setup Firebase](../suite-firebase/README.md), thêm `AdConfig.install(FirebaseAdConfigSource())` sau nạp asset trong Application. Publish String `ad_remote_config` chứa **toàn bộ JSON**, vì remote thay thế cấu hình hiện tại. Trong splash thêm hook bên dưới. |
| Firebase Analytics | Cài `suite-firebase`, đăng ký `Tracker.addSink(FirebaseSink())` ngay sau `Tracker.install`; chọn consent policy theo [hướng dẫn Firebase](../suite-firebase/README.md#configure-consent). |
| App-open khi quay lại | Chưa bật. Cần key `open_resume`, `ERainAdConfig.idAdResume` trước init và `AdsConfig.appResume` cùng ID; theo [hướng dẫn app-open](../onboardkitorigin/README.vi.md#app-open-khi-quay-lại-app). Chỉ thêm JSON sẽ chưa bật tính năng. |
| App có premium / paywall | Làm [BillingKit](../billingkit/README.md) / [PayKit](../paykit/README.md) và [hook chờ billing trước ads](../onboardkitorigin/README.vi.md#tích-hợp-tùy-chọn). `onInitBilling()` mặc định trống; gọi install billing chưa có nghĩa đã khôi phục premium. |
| Đổi ngôn ngữ từ Settings | `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)`; đồng bộ locale với cơ chế app. Kiểu thuộc `io.onboardkit.ui.language`. |
| Entry từ notification/widget | Dùng `SplashEntry` và giữ passthrough trong listener; xem [OnboardKit](../onboardkitorigin/README.vi.md#tích-hợp-tùy-chọn). Không cần các entry này cho launcher thông thường. |

Chỉ khi dùng remote JSON, thay class splash ở bước 6 bằng:

```kotlin
package com.example.app

import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity() {
    override fun onRemoteFetched() {
        OnboardKitSetup.configure()
    }
}
```

SDK đã fetch trước hook; `configure()` dựng lại mapping bằng ID mới. Không cần `RemoteConfigUtils`, fetch lần hai hoặc callback listener riêng của app example. Local-only giữ splash một dòng ở bước 6.

## 8. Kiểm tra hoàn tất

- [ ] Debug build mở được splash, Logcat `AdRemoteConfig` nạp **10 placement**, `OB_FLOW` không báo config/provider lỗi.
- [ ] Đi hết LFO → OB → MainActivity bằng ad test; native fullscreen nằm giữa nội dung 2 và 3, inter cuối chỉ do SDK quản lý.
- [ ] Từ chối notification vẫn đi tiếp; Home/quay lại khi ở splash, LFO, popup và OB không điều hướng lặp. Click native test rồi quay lại đúng hành vi của phiên bản đang dùng.
- [ ] Tắt `native_ob2`: trang nội dung 2 vẫn hiện, không lấy native trang 1. Tắt `native_fs`: bỏ trang chỉ quảng cáo. Tắt cả hai inter: vẫn tới màn đích.
- [ ] Thử mất mạng: mặc định hiện prompt kết nối; nếu chọn hỗ trợ offline thì luồng vẫn đi tiếp theo timeout SDK, không treo vì callback app.
- [ ] Mở lại sau khi hoàn thành: đi qua splash rồi vào app, không chạy lại OB. Clear app data để kiểm tra first-open; thử đóng app giữa OB theo bảng phiên bản.
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
| `app/src/main/res/values/id_ads.xml` | AdMob App ID và Meta credential |
| `app/src/main/assets/ad_config.json`, `ad_config_debug.json` | Copy hai JSON mẫu |
| `strings.xml` và drawable của app | Nội dung, bản dịch, ảnh cho ba trang |
| `OnboardKitSetup.kt` | Ánh xạ nội dung và quảng cáo |
| Application hiện có hoặc `PartnerApp.kt` | Khởi tạo SDK một lần và chọn màn đích |
| `SplashActivity.kt` | Kế thừa splash SDK |

`MainActivity` là màn đích hiện có của app. Không cần sao chép `AdPlacement`, `AppConstants`, `RemoteConfigUtils`, `ResumeAdsEntryRule`, `AppLifecycleObserver`, DevConfig, Hilt hay các màn paywall/welcome/uninstall từ example cho luồng cơ bản này.

[Tra cứu Ads](../ads/README.md) · [Tra cứu OnboardKit](../onboardkitorigin/README.vi.md)
