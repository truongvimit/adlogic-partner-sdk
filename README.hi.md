**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# adlogic-partner-sdk

> Ads, onboarding, analytics, billing और paywalls के लिए सात Android libraries — एक ही repository से एक साथ
> publish होती हैं।

सिर्फ़ वही modules declare करें जो आप वाकई ship कर रहे हैं। यह पेज build setup और initialization क्रम बताता है;
हर module का README उसकी अपनी integration guide है।

## Modules

| Module | यह क्या करता है | Guide |
|---|---|---|
| `ads` | AdMob load/show, ad config, UMP consent, premium gating | [ads/README.md](ads/README.md) |
| `onboardkitorigin` | First-open flow: splash, language, onboarding pager, survey | [onboardkitorigin/README.hi.md](onboardkitorigin/README.hi.md) |
| `trackkit` | Vendor-free analytics contract (`Tracker`, `TrackSink`) | [trackkit/README.hi.md](trackkit/README.hi.md) |
| `suite-firebase` | एकमात्र Firebase adapter: GA4 sink, ad config source, paywall config source | [suite-firebase/README.md](suite-firebase/README.md) |
| `billingkit` | Play Billing engine (`com.ads.module.billing`) | [billingkit/README.md](billingkit/README.md) |
| `paykit` | `billingkit` engine के ऊपर paywall UI | [paykit/README.md](paykit/README.md) |
| `adtracer` | Debug-only ad lifecycle dashboard | [adtracer/README.md](adtracer/README.md) |

## कौन-से modules declare करें

| आप क्या ship कर रहे हैं | क्या declare करें | ये modules कौन-सी dependencies नहीं लाते |
|---|---|---|
| सिर्फ़ ads (IAA) | `ads` (+ `suite-firebase`) | Play Billing |
| IAP + prebuilt paywall, ads नहीं | `billingkit` + `paykit` | GMA/AdMob |
| IAP, paywall UI अपनी | `billingkit` | न `paykit`, न `ads` |
| ads और IAP दोनों | `ads` + `billingkit` (+ `paykit`) | — |
| सिर्फ़ analytics | `trackkit` (+ GA4 के लिए `suite-firebase`) | `ads`, `billingkit`, `paykit` |

`onboardkitorigin` `ads` पर और `paykit` `billingkit` पर निर्भर है, पर `implementation` scope में — अगर आप
उनकी API कॉल करते हैं तो उन्हें अलग से declare करें। अगर चुना हुआ module `trackkit` को `api` से export
करता है तो उसे अलग से declare करने की ज़रूरत नहीं है; केवल analytics वाली app उसे सीधे declare कर सकती है।

## आवश्यकताएँ

| | |
|---|---|
| Build के लिए न्यूनतम JDK | 17 |
| `minSdk` | 24 |
| `compileSdk` / `targetSdk` | 36 |
| AGP / Gradle | 8.12.0 / 8.13 |

## Installation

Version **5.1.0 अभी publish नहीं हुआ है**;
नीचे dependency examples में कोई प्रकाशित tag इस्तेमाल करें।

`ads` जिन mediation adapters को bundle करता है वे Maven Central पर नहीं हैं — आख़िरी तीन repositories के
बिना build Pangle, ironSource और Mintegral को resolve नहीं कर पाएगा।

```groovy
repositories {
    google()
    mavenCentral()
    maven { url 'https://jitpack.io' }
    maven { url 'https://artifact.bytedance.com/repository/pangle/' }
    maven { url 'https://android-sdk.is.com/' }
    maven { url 'https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea' }
}

// <tag> की जगह https://github.com/truongvimit/adlogic-partner-sdk/tags से कोई tag रखें
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

Group id `com.github.truongvimit.adlogic-partner-sdk` है — JitPack multi-module repo को
`com.github.<user>.<repo>` के रूप में namespace करता है। सभी modules एक ही tag पर रखें; अलग-अलग versions के
combination test नहीं किए गए हैं।

## आपकी app को क्या देना होगा

**`AndroidManifest.xml`** — `<application>` के अंदर, जब आप `ads` ship करें। पहली entry के बिना GMA init पर ही
throw करता है; दोनों Facebook entries ज़रूरी हैं क्योंकि `ERainAd.init` हमेशा `FacebookSdk` initialize करता है:

```xml
<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="${app_id}" />
<meta-data android:name="com.facebook.sdk.ApplicationId"  android:value="@string/facebook_app_id" />
<meta-data android:name="com.facebook.sdk.ClientToken"    android:value="@string/facebook_client_token" />
```

हर build type के लिए `manifestPlaceholders = [app_id: "ca-app-pub-XXXX~YYYY"]` सेट करें, और
`<application>` tag के `android:name` को अपनी `Application` class पर point करें।

**String resources** (`translatable="false"`)। `ads` के साथ `facebook_app_id` और `facebook_client_token`
अनिवार्य हैं। `adjust_token`, `event_token` और `adjust_event_token_purchase` सिर्फ़ उस `AdjustConfig` से पढ़े
जाते हैं जो आप खुद बनाते हैं — `adjust_token` खाली हो तो Adjust बंद रहता है।

**जो files आपको बनानी हैं** — इनमें से कोई भी SDK के साथ नहीं आती:

| Path | किसके लिए ज़रूरी | न होने पर |
|---|---|---|
| `src/main/assets/ad_config.json` | स्थानीय ad placement configuration | कोई स्थानीय placement configuration load नहीं होता |
| `src/main/assets/ad_config_debug.json` | debug builds के लिए अलग test ad units | `ad_config.json` इस्तेमाल होगा, जिसमें live ad units हो सकते हैं |
| `google-services.json` + `com.google.gms.google-services` plugin | `suite-firebase` | न GA4 sink, न remote ad config, न paywall document |

## Initialization क्रम

नीचे का सब कुछ `Application.onCreate()` में, इसी क्रम में चलता है। क्रम मायने रखता है: `Tracker` सबसे पहले,
क्योंकि उससे पहले निकले events सिर्फ़ buffer होते हैं; और ad config `ERainAd.init` से पहले, क्योंकि वही ad unit
ids को placements से जोड़ता है।

```kotlin
class App : AdsMultiDexApplication() {
    override fun onCreate() {
        super.onCreate()

        // 1. Analytics — देखें trackkit/README.md और suite-firebase/README.md
        Tracker.install(this, TrackerConfig(appVersionCode = BuildConfig.VERSION_CODE.toLong()))
        Tracker.addSink(FirebaseSink(collectionFollowsConsent = false))

        // 2. Ads — देखें ads/README.md
        AdRemoteConfig.initializeFromAssets(this)
        AdConfig.install(FirebaseAdConfigSource())
        ConsentCenter.configure(ConsentOptions(timeoutMs = 20_000L))
        ERainAd.getInstance().init(this, buildERainAdConfig())
        AppOpenManager.getInstance().disableAppResumeWithActivity(SplashActivity::class.java)

        // 3. Billing और paywall — देखें billingkit/README.md और paykit/README.md
        PayKit.install(this, payKitConfig { /* … */ }.getOrThrow())
        PayKit.configSource(FirebaseConfigSource())

        // 4. First-open flow — देखें onboardkitorigin/README.md
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

फिर अपनी launcher activity को `class SplashActivity : ObSplashActivity()` बनाएँ। Consent, वैकल्पिक notification
permission prompt, remote fetch, splash ads, न्यूनतम display समय और navigation उसी के अंदर हैं; देखें
[onboardkitorigin/README.hi.md](onboardkitorigin/README.hi.md)।

अपनी app में इस्तेमाल होने वाले modules के steps रखें। केवल ads वाली app steps 1 और 2 इस्तेमाल करती है।
केवल IAP वाली app steps 1 और 3 रखती है और `AdsMultiDexApplication` की जगह `Application` या अपनी
मौजूदा application base class से inherit करती है।

## APK का आकार घटाना

`ads` सात AdMob mediation adapters bundle करता है, जो APK का आकार बढ़ाते हैं। जिन networks को आपका AdMob
account mediate नहीं करता, उन्हें हटा दें — और `ads` dependency पर नहीं, `configurations` पर, क्योंकि
`onboardkitorigin` भी `ads` पर निर्भर है और per-dependency exclude वह दूसरा रास्ता खुला छोड़ देता:

```groovy
configurations.configureEach {
    exclude group: 'com.google.ads.mediation', module: 'pangle'
    exclude group: 'com.pangle.global'
}
```

हर network = एक adapter + वह SDK जो वह खींचता है; सिर्फ़ adapter हटाने पर SDK रह जाता है। जोड़े:
`applovin`→`com.applovin`, `vungle`→`com.vungle`, `pangle`→`com.pangle.global`,
`unity`→`com.unity3d.ads`, `mintegral`→`com.mbridge.msdk.oversea`,
`ironsource`→`com.unity3d.ads-mediation`। `facebook` अपवाद है — module हटाएँ, पूरा group कभी नहीं:
`exclude group: 'com.facebook.android', module: 'audience-network-sdk'`। उसी group में `facebook-core` भी
है, जिसकी `ERainAd.init` को ज़रूरत होती है।

अगर R8 किसी हटाए गए network के लिए `Missing class` बताए, तो `proguard-rules.pro` में
`-dontwarn com.pangle.global.**` (और वैसे ही बाकी) जोड़ें। Gradle exclusions से पहले अपने AdMob mediation
groups बदलें।

## विस्तृत जानकारी कहाँ है

ये READMEs integration समझाते हैं। Defaults और व्यवहार के लिए संबंधित type की KDoc देखें। हर module
sources jar के साथ publish होता है; IDE में **Go to definition** से अपनी app के उपयोग वाले version की
जानकारी देखें। शुरुआत करें `AdUnitConfig` और `ConsentOptions`
(ads), `OnboardKitConfig` और `ObRemoteKeys` (onboardkitorigin), `TrackerConfig` और `TrackkitEvents`
(trackkit), `PayKitConfig` (paykit), `AppPurchase` (billingkit) से।

## License

MIT — देखें [LICENSE](LICENSE)।
