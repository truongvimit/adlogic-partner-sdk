**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# adlogic-partner-sdk

> Bảy thư viện Android cho quảng cáo, onboarding, analytics, billing và paywall, phát hành chung từ
> một repository.

Chỉ khai báo những module bạn thực sự ship. Trang này nói về cấu hình build và thứ tự khởi tạo;
README của từng module là hướng dẫn tích hợp cho phần việc của nó.

## Modules

| Module | Làm gì | Tài liệu |
|---|---|---|
| `ads` | Load/show AdMob, ad config, UMP consent, chặn ad cho user premium | [ads/README.md](ads/README.md) |
| `onboardkitorigin` | Luồng mở app lần đầu: splash, chọn ngôn ngữ, pager onboarding, survey | [onboardkitorigin/README.md](onboardkitorigin/README.md) |
| `trackkit` | Hợp đồng analytics không phụ thuộc vendor (`Tracker`, `TrackSink`) | [trackkit/README.md](trackkit/README.md) |
| `suite-firebase` | Adapter Firebase duy nhất: GA4 sink, nguồn ad config, nguồn paywall config | [suite-firebase/README.md](suite-firebase/README.md) |
| `billingkit` | Engine Play Billing (`com.ads.module.billing`) | [billingkit/README.md](billingkit/README.md) |
| `paykit` | UI paywall chạy trên engine `billingkit` | [paykit/README.md](paykit/README.md) |
| `adtracer` | Dashboard vòng đời quảng cáo, chỉ dùng cho build debug | [adtracer/README.md](adtracer/README.md) |

## Chọn module nào để khai báo

| Bạn ship gì | Khai báo | APK chắc chắn không chứa |
|---|---|---|
| Chỉ ads (IAA) | `ads` (+ `suite-firebase`) | Không có class Play Billing nào |
| IAP + paywall dựng sẵn, không ads | `billingkit` + `paykit` | Không có class GMA/AdMob nào |
| IAP với UI paywall tự viết | `billingkit` | Không có `paykit` lẫn `ads` |
| Vừa ads vừa IAP | `ads` + `billingkit` (+ `paykit`) | — |

`onboardkitorigin` phụ thuộc `ads`, và `paykit` phụ thuộc `billingkit`, ở scope `implementation` —
hãy khai báo chúng tường minh nếu bạn gọi API của chúng. Đừng bao giờ khai báo `trackkit`: mọi module
dùng nó đều export bằng `api`.

## Yêu cầu

| | |
|---|---|
| JDK / Kotlin `jvmTarget` | 17 |
| `minSdk` | 24 |
| `compileSdk` / `targetSdk` | 36 |
| AGP / Gradle | 8.12.0 / 8.13 |

## Cài đặt

Các mediation adapter mà `ads` đóng gói không nằm trên Maven Central — thiếu ba repository cuối,
build sẽ không resolve được Pangle, ironSource và Mintegral.

```groovy
repositories {
    google()
    mavenCentral()
    maven { url 'https://jitpack.io' }
    maven { url 'https://artifact.bytedance.com/repository/pangle/' }
    maven { url 'https://android-sdk.is.com/' }
    maven { url 'https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea' }
}

// Thay <tag> bằng một tag tại https://github.com/truongvimit/adlogic-partner-sdk/tags
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

Group id là `com.github.truongvimit.adlogic-partner-sdk` — JitPack đặt namespace cho repo nhiều
module theo dạng `com.github.<user>.<repo>`. Giữ mọi module ở cùng một tag; các tổ hợp khác phiên bản
không được kiểm thử.

## App của bạn phải tự cung cấp những gì

**`AndroidManifest.xml`** — đặt bên trong `<application>`, khi bạn ship `ads`. Thiếu entry đầu tiên
thì GMA sẽ throw ngay lúc init; hai entry Facebook là bắt buộc vì `ERainAd.init` luôn khởi tạo
`FacebookSdk`:

```xml
<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="${app_id}" />
<meta-data android:name="com.facebook.sdk.ApplicationId"  android:value="@string/facebook_app_id" />
<meta-data android:name="com.facebook.sdk.ClientToken"    android:value="@string/facebook_client_token" />
```

Khai báo `manifestPlaceholders = [app_id: "ca-app-pub-XXXX~YYYY"]` cho từng build type, và trỏ
`android:name` trên thẻ `<application>` vào class `Application` của bạn.

**String resources** (`translatable="false"`). `facebook_app_id` và `facebook_client_token` là bắt
buộc khi dùng `ads`. `adjust_token`, `event_token` và `adjust_event_token_purchase` chỉ được đọc bởi
`AdjustConfig` do chính bạn dựng — `adjust_token` để trống thì Adjust tắt.

**File bạn phải tự tạo** — SDK không ship sẵn file nào trong số này:

| Đường dẫn | Cần cho | Thiếu thì sao |
|---|---|---|
| `src/main/assets/ad_config.json` | `ads` | Mọi placement bị tắt âm thầm, không crash |
| `src/main/assets/ad_config_debug.json` | build debug | Chạy debug sẽ tiêu ad unit **thật** |
| `google-services.json` + plugin `com.google.gms.google-services` | `suite-firebase` | Không có GA4 sink, không có remote ad config, không có paywall document |

## Thứ tự khởi tạo

Toàn bộ phần dưới chạy trong `Application.onCreate()`, đúng theo thứ tự này. Thứ tự có ý nghĩa:
`Tracker` phải đầu tiên vì event phát ra trước đó chỉ được buffer, và ad config phải trước
`ERainAd.init` vì đó là bước gắn ad unit id vào placement.

```kotlin
class App : AdsMultiDexApplication() {
    override fun onCreate() {
        super.onCreate()

        // 1. Analytics — xem trackkit/README.md và suite-firebase/README.md
        Tracker.install(this, TrackerConfig(appVersionCode = BuildConfig.VERSION_CODE.toLong()))
        Tracker.addSink(FirebaseSink(collectionFollowsConsent = false))

        // 2. Ads — xem ads/README.md
        AdRemoteConfig.initializeFromAssets(this)
        AdConfig.install(FirebaseAdConfigSource())
        ConsentCenter.configure(ConsentOptions(timeoutMs = 20_000L))
        ERainAd.getInstance().init(this, buildERainAdConfig())
        AppOpenManager.getInstance().disableAppResumeWithActivity(SplashActivity::class.java)

        // 3. Billing và paywall — xem billingkit/README.md và paykit/README.md
        PayKit.install(this, payKitConfig { /* … */ }.getOrThrow())
        PayKit.configSource(FirebaseConfigSource())

        // 4. Luồng mở app lần đầu — xem onboardkitorigin/README.md
        ERainTuning.install()
        OnboardingSdk.install(this) {
            adProvider = ERainAdProvider()
            paywallGate = OnboardKitPaywallGate()
            listener = OnboardingListener { context, _ ->
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        OnboardingSdk.configure(buildOnboardKitConfig())
    }
}
```

Sau đó cho launcher activity kế thừa: `class SplashActivity : ObSplashActivity()`. Consent, remote
fetch, ad splash, thời gian hiển thị tối thiểu và việc điều hướng ra ngoài đều nằm bên trong nó —
xem [onboardkitorigin/README.md](onboardkitorigin/README.md).

Bỏ bước nào ứng với module bạn không ship: app chỉ có ads dừng ở bước 2, app chỉ có IAP chỉ cần bước
1 và 3.

## Giảm dung lượng APK

`ads` đóng gói bảy mediation adapter của AdMob — phần nặng nhất trong APK. Hãy loại những mạng mà tài
khoản AdMob của bạn không mediation, và loại trên `configurations` chứ không phải trên dependency
`ads`: `onboardkitorigin` cũng phụ thuộc `ads`, nên loại trừ theo từng dependency sẽ để hở đường thứ
hai đó.

```groovy
configurations.configureEach {
    exclude group: 'com.google.ads.mediation', module: 'pangle'
    exclude group: 'com.pangle.global'
}
```

Mỗi mạng gồm một adapter cộng với SDK mà nó kéo theo; chỉ loại adapter thì SDK vẫn còn lại. Các cặp:
`applovin`→`com.applovin`, `vungle`→`com.vungle`, `pangle`→`com.pangle.global`,
`unity`→`com.unity3d.ads`, `mintegral`→`com.mbridge.msdk.oversea`,
`ironsource`→`com.unity3d.ads-mediation`. `facebook` là ngoại lệ — loại module, tuyệt đối không loại
cả group: `exclude group: 'com.facebook.android', module: 'audience-network-sdk'`. Group đó còn chứa
`facebook-core`, thứ mà `ERainAd.init` cần.

Nếu R8 báo `Missing class` cho một mạng đã loại, thêm `-dontwarn com.pangle.global.**` (và tương tự)
vào `proguard-rules.pro`. Hãy đổi mediation group trên AdMob trước khi đụng tới phần exclude của
Gradle.

## Tra cứu chi tiết

Các README này chỉ nói về tích hợp. Mọi tùy chọn, giá trị mặc định và cờ hành vi đều được ghi bằng
KDoc ngay trên kiểu dữ liệu sở hữu nó, và mọi module đều publish kèm sources jar — nên tài liệu đầy
đủ và luôn cập nhật chỉ cách bạn một thao tác **Go to definition** trong IDE. Bắt đầu từ
`AdUnitConfig` và `ConsentOptions` (ads), `OnboardKitConfig` và `ObRemoteKeys` (onboardkitorigin),
`TrackerConfig` và `TrackkitEvents` (trackkit), `PayKitConfig` (paykit), `AppPurchase` (billingkit).

## License

MIT — xem [LICENSE](LICENSE).
