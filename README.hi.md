**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# adlogic-partner-sdk

> Ads, onboarding, analytics, billing और paywalls के लिए सात Android libraries — एक ही repository से एक साथ publish होती हैं।

## Modules

| Module | यह क्या करता है | Guide |
|---|---|---|
| `ads` | AdMob load/show, ad config, UMP consent, premium gating | [ads/README.md](ads/README.md) |
| `onboardkitorigin` | First-open flow: splash, language, onboarding pager, survey | [onboardkitorigin/README.md](onboardkitorigin/README.md) |
| `trackkit` | Vendor-free analytics contract (`Tracker`, `TrackSink`, taxonomy) | [trackkit/README.md](trackkit/README.md) |
| `suite-firebase` | एकमात्र Firebase adapter: GA4 sink, ad config source, paywall config source | [suite-firebase/README.md](suite-firebase/README.md) |
| `billingkit` | Play Billing engine (`com.ads.module.billing`) | [billingkit/README.md](billingkit/README.md) |
| `paykit` | `billingkit` engine के ऊपर paywall UI | [paykit/README.md](paykit/README.md) |
| `adtracer` | Debug-only ad lifecycle dashboard | [adtracer/README.md](adtracer/README.md) |

## कौन-से modules declare करें

| Partner | क्या declare करें | APK में निश्चित रूप से क्या नहीं होगा |
|---|---|---|
| सिर्फ ads, IAP नहीं | `ads` (+ `suite-firebase`) | एक भी Play Billing class नहीं |
| IAP + prebuilt paywall, ads नहीं | `billingkit` + `paykit` | एक भी GMA/AdMob class नहीं |
| IAP, paywall UI अपनी | `billingkit` | न `paykit`, न `ads` |
| Ads और IAP दोनों | `ads` + `billingkit` (+ `paykit`) | — |

`onboardkitorigin`, `ads` पर निर्भर है और `paykit`, `billingkit` पर — सिर्फ runtime scope में। अगर आप उनकी
APIs call करते हैं तो उन्हें अलग से declare करें। `trackkit` कभी declare न करें: उसे इस्तेमाल करने वाला हर module उसे `api` से export करता है।

## Requirements

| | |
|---|---|
| JDK / Kotlin `jvmTarget` | 17 |
| `minSdk` | 24 |
| `compileSdk` / `targetSdk` | 36 |
| AGP / Gradle | 8.12.0 / 8.13 |

## Installation

`ads` जो mediation adapters bundle करता है वे Maven Central पर नहीं हैं — आखिरी तीन repositories के बिना
build, Pangle, ironSource और Mintegral को resolve नहीं कर पाएगा:

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

Group id `com.github.truongvimit.adlogic-partner-sdk` है — JitPack एक multi-module repo को
`com.github.<user>.<repo>` के रूप में namespace करता है। हर module को एक ही tag पर रखें; अलग-अलग versions के
combination test नहीं किए गए हैं।

## Quick start

**1. आपका `Application`.** यह क्रम मायने रखता है — देखें [ads/README.md](ads/README.md).

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

**2. आपका splash.** `class SplashActivity : ObSplashActivity()`, जिसे AppCompat theme के साथ `MAIN`/`LAUNCHER`
activity के रूप में declare करें। Consent, remote fetch, splash ads, minimum display time और उसके बाद का
navigation — सब उसी के अंदर चलता है; यहाँ से `OnboardingSdk.start()` कभी call न करें।

**3. Flow configure करें.** `install(...)` के बाद `onboardKitConfig { ... }.onSuccess { OnboardingSdk.configure(it) }`
call करें — builder एक `Result` लौटाता है। Config के बिना flow skip हो जाता है। देखें [onboardkitorigin/README.md](onboardkitorigin/README.md).

## आपकी app को क्या declare करना है

**`AndroidManifest.xml`** — `<application>` के अंदर। पहली entry के बिना GMA init पर throw करता है; दोनों
Facebook entries इसलिए ज़रूरी हैं क्योंकि `ERainAd.init` बिना शर्त `FacebookSdk` initialize करता है:

```xml
<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="${app_id}" />
<meta-data android:name="com.facebook.sdk.ApplicationId"  android:value="@string/facebook_app_id" />
<meta-data android:name="com.facebook.sdk.ClientToken"    android:value="@string/facebook_client_token" />
```

हर build type पर `manifestPlaceholders = [app_id: "ca-app-pub-XXXX~YYYY"]` set करें, और `<application>` tag के
`android:name` में अपनी `Application` class डालें।

**String resources** — `translatable="false"`:

| Name | कौन इस्तेमाल करता है | खाली छोड़ने पर |
|---|---|---|
| `adjust_token` | Adjust app token | Adjust बंद रहता है, error के रूप में log होता है |
| `event_token` | Adjust ad-impression event | Impressions warning के साथ skip होती हैं |
| `adjust_event_token_purchase` | Adjust purchase event | Purchases warning के साथ skip होती हैं |
| `facebook_app_id` | Meta adapter + Adjust `fbAppId` | `Application.onCreate` में `FacebookSdk.sdkInitialize` throw करता है |
| `facebook_client_token` | Meta adapter | Facebook requests server-side fail होती हैं |

**जो files आप बनाते हैं** — SDKs इनमें से कोई भी ship नहीं करतीं:

| Path | ज़रूरी | न होने पर |
|---|:---:|---|
| `src/main/assets/ad_config.json` | हाँ | हर placement चुपचाप disable, कोई crash नहीं |
| `src/main/assets/ad_config_debug.json` | ज़ोरदार सलाह | Debug run आपके **live** ad units खर्च करता है |
| `google-services.json` | `suite-firebase` के साथ (और `com.google.gms.google-services` plugin) | न GA4 sink, न remote ad config, न paywall document |

## APK size घटाना

`ads` सात AdMob mediation adapters bundle करता है — APK की सबसे बड़ी चीज़। जिन networks को आपका AdMob account
mediate नहीं करता, उन्हें हटा दें — `configurations` पर, `ads` dependency पर नहीं, क्योंकि `onboardkitorigin`
भी `ads` पर निर्भर है और per-dependency exclude उस दूसरे रास्ते को खुला छोड़ देगा:

```groovy
configurations.configureEach {
    exclude group: 'com.google.ads.mediation', module: 'pangle'
    exclude group: 'com.pangle.global'
}
```

हर network एक adapter है और साथ में वह SDK जिसे वह खींचता है; सिर्फ adapter हटाने पर SDK पीछे रह जाता है। जोड़े:
`applovin`→`com.applovin`, `vungle`→`com.vungle`, `pangle`→`com.pangle.global`, `unity`→`com.unity3d.ads`,
`mintegral`→`com.mbridge.msdk.oversea`, `ironsource`→`com.unity3d.ads-mediation`. `facebook` अपवाद है — module
exclude करें, group कभी नहीं: `exclude group: 'com.facebook.android', module: 'audience-network-sdk'`. उसी group
में `facebook-core` भी है, जिसकी ज़रूरत `ERainAd.init` को पड़ती है।

अगर R8 किसी हटाए गए network के लिए `Missing class` बताए, तो `proguard-rules.pro` में `-dontwarn com.pangle.global.**`
(और उसी तरह बाकी) जोड़ें। Gradle exclusions से पहले अपने AdMob mediation groups बदलें।

## License

MIT — देखें [LICENSE](LICENSE).
