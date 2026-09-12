**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# adlogic-partner-sdk

विज्ञापन, onboarding, analytics, billing और paywall के लिए Android SDK। पहले ज़रूरी सुविधा चुनें, साझा build setup करें और फिर उस module की quickstart पढ़ें।

**Partner integration गाइड: [यहाँ शुरू करें](partner-integration/README.hi.md)** — Ads + OnboardKit, BillingKit, PayKit, Firebase, Trackkit और AdTracer; copy करने लायक नमूने और वैकल्पिक configuration tables के साथ।

## अपना मॉड्यूल चुनें

| ऐप को क्या चाहिए | Dependency | गाइड |
| --- | --- | --- |
| AdMob विज्ञापन | `ads` | [Ads](ads/README.md) |
| विज्ञापनों के साथ splash, भाषा चयन और onboarding | `ads + onboardkitorigin` | [OnboardKit](onboardkitorigin/README.hi.md) |
| अपने UI के साथ खरीदारी | `billingkit` | [BillingKit](billingkit/README.md) |
| तैयार paywall UI | `paykit` | [PayKit](paykit/README.md) |
| Firebase Analytics या ads/paywall का remote config | `suite-firebase` (config sources के लिए `ads`/`paykit` जोड़ें) | [Firebase](suite-firebase/README.md) |
| अपने backend को analytics भेजना | `trackkit` | [Trackkit](trackkit/README.hi.md) |
| विज्ञापन debug dashboard | `adtracer (debugImplementation)` | [AdTracer](adtracer/README.md) |

केवल इस्तेमाल होने वाले modules जोड़ें। Ads, onboarding, billing, paywall और Firebase पहले से Trackkit उपलब्ध कराते हैं। PayKit billing engine को runtime पर लाता है; उसके API सीधे बुलाने पर ही `billingkit` अलग से जोड़ें। Firebase वैकल्पिक है। केवल billing/paywall वाला ऐप ads stack नहीं लाता।

## Build setup

JDK 17, `minSdk 24+` और `compileSdk 36+` इस्तेमाल करें। Repo की build configuration के लिए [versions.gradle](versions.gradle) और [Gradle wrapper](gradle/wrapper/gradle-wrapper.properties) देखें।

इन repositories को मौजूदा Gradle configuration में मिलाएँ; दूसरा `dependencyResolutionManagement` block न बनाएँ। अंतिम तीन mediation repositories केवल ads/onboarding के लिए चाहिए।

```groovy
// settings.gradle
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }

        // Only when ads or onboardkitorigin is included.
        maven { url 'https://artifact.bytedance.com/repository/pangle/' }
        maven { url 'https://android-sdk.is.com/' }
        maven { url 'https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea' }
    }
}
```

एक [published tag](https://github.com/truongvimit/adlogic-partner-sdk/tags) चुनें और app project की root `gradle.properties` में एक बार सेट करें। `<published-tag>` को चुने हुए tag से बदलें; सभी module examples यही property पढ़ते हैं।

```properties
adlogicSdkVersion=<published-tag>
```

SDK upgrade करते समय यह property बदलें और चुने हुए tag के docs पढ़ें।

उदाहरण: ads और onboarding वाला ऐप। दूसरी ज़रूरत के लिए ऊपर की तालिका के अनुसार artifact नाम बदलें।

```groovy
// app/build.gradle
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
}
```

## Integration order

Manifest में अपनी `Application` class दर्ज करें। `Application.onCreate()` में `super.onCreate()` के बाद केवल चुने गए modules के चरण पूरे करें:

| चरण | क्या करें | गाइड |
| --- | --- | --- |
| 1 · Analytics | SDK events लेने हों तो दूसरे kits से events आने से पहले `Tracker` install करें और destination जोड़ें। | [Trackkit](trackkit/README.hi.md) |
| 2 · Ads | AdMob/Meta metadata और अपनी ad JSON दें, फिर `ERainAd` initialize करें। | [Ads quickstart](ads/README.md) |
| 3 · खरीदारी | `PayKit` install करें, या अपने UI के लिए `BillingKit` initialize करें। PayKit इस्तेमाल होने पर billing initialization उसी को करने दें। | [PayKit](paykit/README.md) / [BillingKit](billingkit/README.md) |
| 4 · Onboarding | `OnboardingSdk` install/configure करें और `ObSplashActivity` की अपनी subclass दर्ज करें। | [OnboardKit](onboardkitorigin/README.hi.md) |

OnboardKit का splash consent और notification चरण चलाता है। केवल ads इस्तेमाल करने पर ad request से पहले Activity से `ConsentCenter.request(...)` बुलाएँ। Firebase के लिए ऐप की `google-services.json` और Google Services plugin भी चाहिए; [Firebase गाइड](suite-firebase/README.md) देखें।

UMP error देने पर या network timeout पूरा होने पर (default 20 सेकंड) AdLogic ad request की कोशिश करने देता है, पहली बार खोलने पर भी। यह SDK का अपना fallback है: consent को सहमति के रूप में दर्ज नहीं करता, `npa` नहीं जोड़ता, और fill की गारंटी नहीं देता। दिख रहा form फिर भी user के जवाब का इंतज़ार करता है। Fallback नए process में सहेजा नहीं जाता; UMP पहले से request की अनुमति न दे चुका हो तो हर नई call नया इंतज़ार शुरू करती है। Host का जानबूझकर ads बंद करना फिर भी प्राथमिकता रखता है। यह व्यवहार केवल `ConsentInformation.canRequestAds()` को gate मानने वाली Google गाइड से अलग है।

## ऐप की अपनी जानकारी दें

| हिस्सा | क्या करें |
| --- | --- |
| Ads | AdMob app ID, Meta app ID/client token, `assets/ad_config.json` में placement IDs और `ad_config_debug.json` में test IDs। Debug file न होने पर सामान्य file इस्तेमाल होती है। |
| Onboarding | Destination Activity, भाषा/पेज content और ad placements। |
| खरीदारी | Play product IDs और premium entitlement mapping। PayKit को terms/privacy URLs और अपना catalog JSON भी दें। |
| Firebase · वैकल्पिक | ऐप का Firebase configuration और इस्तेमाल होने वाले sources के published Remote Config parameters। |

## Integration की जाँच

- Test ad IDs वाला debug build खोलें; ad requests से पहले initialization और consent result की जाँच करें।
- Ad तैयार न होने पर भी feature navigation पूरा होना चाहिए। Notification denial और Home/return जाँचें।
- खरीदारी होने पर ads दिखाने से पहले premium restore जाँचें और अपने Play catalog से paywall test करें।

अतिरिक्त विकल्पों के लिए module गाइड के source links या IDE में Go to Definition इस्तेमाल करें। पहले quickstart पूरा करें; हर विकल्प configure करना ज़रूरी नहीं है।

## License

MIT — [LICENSE](LICENSE) देखें।
