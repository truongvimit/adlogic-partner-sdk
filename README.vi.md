**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# adlogic-partner-sdk

> Bảy thư viện Android cho quảng cáo, onboarding, analytics, billing và paywall, phát hành chung từ một repository.

## Modules

| Module | Làm gì | Tài liệu |
|---|---|---|
| `ads` | Load/show AdMob, ad config, UMP consent, chặn ad cho user premium | [ads/README.md](ads/README.md) |
| `onboardkitorigin` | Luồng mở app lần đầu: splash, chọn ngôn ngữ, pager onboarding, survey | [onboardkitorigin/README.md](onboardkitorigin/README.md) |
| `trackkit` | Hợp đồng analytics không phụ thuộc vendor (`Tracker`, `TrackSink`, taxonomy) | [trackkit/README.md](trackkit/README.md) |
| `suite-firebase` | Adapter Firebase duy nhất: GA4 sink, nguồn ad config, nguồn paywall config | [suite-firebase/README.md](suite-firebase/README.md) |
| `billingkit` | Engine Play Billing (`com.ads.module.billing`) | [billingkit/README.md](billingkit/README.md) |
| `paykit` | UI paywall chạy trên engine `billingkit` | [paykit/README.md](paykit/README.md) |
| `adtracer` | Dashboard vòng đời quảng cáo, chỉ dùng cho build debug | [adtracer/README.md](adtracer/README.md) |

## Chọn module nào để khai báo

| Partner | Khai báo | APK chắc chắn không chứa |
|---|---|---|
| Chỉ ads, không IAP | `ads` (+ `suite-firebase`) | Không có class Play Billing nào |
| IAP + paywall dựng sẵn, không ads | `billingkit` + `paykit` | Không có class GMA/AdMob nào |
| IAP với UI paywall tự viết | `billingkit` | Không có `paykit` lẫn `ads` |
| Vừa ads vừa IAP | `ads` + `billingkit` (+ `paykit`) | — |

`onboardkitorigin` phụ thuộc `ads`, và `paykit` phụ thuộc `billingkit`, nhưng chỉ ở runtime scope — khai báo
chúng tường minh nếu bạn gọi API của chúng. Đừng bao giờ khai báo `trackkit`: mọi module dùng nó đều export bằng `api`.

## Requirements

| | |
|---|---|
| JDK / Kotlin `jvmTarget` | 17 |
| `minSdk` | 24 |
| `compileSdk` / `targetSdk` | 36 |
| AGP / Gradle | 8.12.0 / 8.13 |

## Installation

Các mediation adapter mà `ads` đóng gói không nằm trên Maven Central — thiếu ba repository cuối
thì build không resolve được Pangle, ironSource và Mintegral:

```groovy
repositories {
    google()
    mavenCentral()
    maven { url 'https://jitpack.io' }
    maven { url 'https://artifact.bytedance.com/repository/pangle/' }
    maven { url 'https://android-sdk.is.com/' }
    maven { url 'https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea' }
}

// Replace <tag> with a tag from https://github.com/truongvimit/adlogic-partner-sdk/tags
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:paykit:$sdkVersion"
    debugImplementation "com.github.truongvimit.adlogic-partner-sdk:adtracer:$sdkVersion"
}
```

Group id là `com.github.truongvimit.adlogic-partner-sdk` — JitPack đặt namespace cho repo nhiều module theo dạng
`com.github.<user>.<repo>`. Giữ tất cả module trên cùng một tag; tổ hợp khác version không được kiểm thử.

## Quick start

**1. `Application` của bạn.** Thứ tự này có ý nghĩa — xem [ads/README.md](ads/README.md).

```kotlin
class App : AdsMultiDexApplication() {
    override fun onCreate() {
        super.onCreate()

        Tracker.install(this, TrackerConfig(appVersionCode = BuildConfig.VERSION_CODE.toLong()))
        Tracker.addSink(FirebaseSink(collectionFollowsConsent = false))
        AdRemoteConfig.initializeFromAssets(this)
        AdConfig.install(FirebaseAdConfigSource())
        ConsentCenter.configure(ConsentOptions(timeoutMs = 20_000L))

        mERainAdConfig = ERainAdConfig(this, ERainAdConfig.ENVIRONMENT_PRODUCTION)
        mERainAdConfig.adjustConfig = AdjustConfig(true, getString(R.string.adjust_token)).apply {
            eventAdImpression = getString(R.string.event_token)
            fbAppId = getString(R.string.facebook_app_id)
        }
        mERainAdConfig.facebookClientToken = getString(R.string.facebook_client_token)
        ERainAd.getInstance().init(this, mERainAdConfig)
        ERainTuning.install()
        AppOpenManager.getInstance().disableAppResumeWithActivity(SplashActivity::class.java)

        OnboardingSdk.install(this) {
            adProvider = ERainAdProvider()
            listener = OnboardingListener { context, _ ->
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
```

**2. Splash của bạn.** `class SplashActivity : ObSplashActivity()`, khai báo là activity `MAIN`/`LAUNCHER`
với theme AppCompat. Consent, remote fetch, splash ads, thời gian hiển thị tối thiểu và việc điều hướng
đi tiếp đều chạy bên trong nó — đừng bao giờ gọi `OnboardingSdk.start()` từ đây.

**3. Cấu hình luồng.** Sau `install(...)`, gọi `onboardKitConfig { ... }.onSuccess { OnboardingSdk.configure(it) }`
— builder trả về một `Result`. Không có config thì luồng bị bỏ qua. Xem [onboardkitorigin/README.md](onboardkitorigin/README.md).

## App của bạn phải khai báo những gì

**`AndroidManifest.xml`** — đặt bên trong `<application>`. GMA ném exception lúc init nếu thiếu entry đầu tiên;
hai entry Facebook là bắt buộc vì `ERainAd.init` khởi tạo `FacebookSdk` vô điều kiện:

```xml
<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="${app_id}" />
<meta-data android:name="com.facebook.sdk.ApplicationId"  android:value="@string/facebook_app_id" />
<meta-data android:name="com.facebook.sdk.ClientToken"    android:value="@string/facebook_client_token" />
```

Đặt `manifestPlaceholders = [app_id: "ca-app-pub-XXXX~YYYY"]` cho từng build type, và đặt `android:name` trên
thẻ `<application>` trỏ tới class `Application` của bạn.

**String resources** — `translatable="false"`:

| Tên | Dùng cho | Để trống nghĩa là |
|---|---|---|
| `adjust_token` | App token của Adjust | Adjust tắt, có log lỗi |
| `event_token` | Event ad-impression của Adjust | Impression bị bỏ qua kèm cảnh báo |
| `adjust_event_token_purchase` | Event purchase của Adjust | Purchase bị bỏ qua kèm cảnh báo |
| `facebook_app_id` | Adapter Meta + `fbAppId` của Adjust | `FacebookSdk.sdkInitialize` ném exception trong `Application.onCreate` |
| `facebook_client_token` | Adapter Meta | Request Facebook fail ở phía server |

**File bạn phải tự tạo** — SDK không ship sẵn file nào trong số này:

| Đường dẫn | Bắt buộc | Thiếu nó nghĩa là |
|---|:---:|---|
| `src/main/assets/ad_config.json` | có | Mọi placement bị tắt im lặng, không crash |
| `src/main/assets/ad_config_debug.json` | rất nên có | Chạy debug sẽ tiêu ad unit **thật** của bạn |
| `google-services.json` | khi dùng `suite-firebase` (kèm plugin `com.google.gms.google-services`) | Không có GA4 sink, không có remote ad config, không có paywall document |

## Giảm dung lượng APK

`ads` đóng gói bảy mediation adapter của AdMob, phần nặng nhất trong APK. Loại bỏ những mạng mà tài khoản
AdMob của bạn không mediate — khai báo trên `configurations`, không phải trên dependency `ads`, vì
`onboardkitorigin` cũng phụ thuộc `ads` và exclude theo từng dependency sẽ để hở đường thứ hai đó:

```groovy
configurations.configureEach {
    exclude group: 'com.google.ads.mediation', module: 'pangle'
    exclude group: 'com.pangle.global'
}
```

Mỗi mạng gồm một adapter cộng với SDK mà nó kéo theo; exclude mỗi adapter thì SDK vẫn còn lại. Các cặp:
`applovin`→`com.applovin`, `vungle`→`com.vungle`, `pangle`→`com.pangle.global`, `unity`→`com.unity3d.ads`,
`mintegral`→`com.mbridge.msdk.oversea`, `ironsource`→`com.unity3d.ads-mediation`. `facebook` là ngoại lệ —
exclude module, đừng bao giờ exclude group: `exclude group: 'com.facebook.android', module:
'audience-network-sdk'`. Group đó còn chứa `facebook-core`, thứ mà `ERainAd.init` cần.

Nếu R8 báo `Missing class` cho một mạng đã exclude, thêm `-dontwarn com.pangle.global.**` (và tương tự)
vào `proguard-rules.pro`. Hãy đổi mediation group trên AdMob trước khi exclude ở Gradle.

## License

MIT — xem [LICENSE](LICENSE).
