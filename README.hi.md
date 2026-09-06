**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# adlogic-partner-sdk

विज्ञापन, onboarding, analytics, billing और paywall के लिए Android SDK। पहले ज़रूरी सुविधा चुनें, साझा build setup करें और फिर उस module की quickstart पढ़ें।

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

JDK 17, `minSdk 24+` और `compileSdk 36+` इस्तेमाल करें। यह repo Kotlin 2.1.0, AGP 8.12.0, Gradle 8.13 और targetSdk 36 से build होता है; इस branch में ये toolchain versions नहीं बदले हैं।

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

यह गाइड **5.1.0 के बदलाव बताती है; यह version अभी प्रकाशित नहीं है**। Published dependency के लिए `<tag>` को उपलब्ध [release tag](https://github.com/truongvimit/adlogic-partner-sdk/tags) से बदलें और उसी tag की README पढ़ें। सभी modules का version एक रखें।

उदाहरण: ads और onboarding वाला ऐप। दूसरी ज़रूरत के लिए ऊपर की तालिका के अनुसार artifact नाम बदलें।

```groovy
// app/build.gradle
def sdkVersion = '<tag>'
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

## ऐप की अपनी जानकारी दें

| हिस्सा | क्या करें |
| --- | --- |
| Ads | AdMob app ID, Meta app ID/client token, `assets/ad_config.json` में placement IDs और `ad_config_debug.json` में test IDs। Debug file न होने पर सामान्य file इस्तेमाल होती है। |
| Onboarding | Destination Activity, भाषा/पेज content और ad placements। |
| खरीदारी | Play product IDs और premium entitlement mapping। PayKit को terms/privacy URLs और अपना catalog JSON भी दें। |
| Firebase · वैकल्पिक | ऐप का Firebase configuration और इस्तेमाल होने वाले sources के published Remote Config parameters। |

## 5.0.0 से अपग्रेड

Dependencies और मुख्य install APIs नहीं बदले हैं। इस branch को अपनाते समय ये integration बदलाव जाँचें:

| हिस्सा | Partner को क्या करना है |
| --- | --- |
| Custom consent | `onConsentRequired()` से `true` लौटाना या `setCanRequestAds(true)` बुलाना अब consent नहीं देता। CMP का वास्तविक निर्णय `ConsentCenter.setHostConsent(...)` से दें। `ObSplashActivity` के सामान्य UMP flow में नई wiring नहीं चाहिए। |
| Notification permission | `SplashConfig.notificationPermissionEnabled` का default `true` है; OnboardKit `POST_NOTIFICATIONS` merge करता है। ऐप खुद permission माँगता हो या notifications न भेजता हो तो `false` रखें। Splash ads consent/permission पूरा होने और focus लौटने के बाद load होते हैं। |
| Portrait | `BehaviorConfig.lockPortrait` का default `true` है, ऐप के splash पर भी। Landscape/tablet flow के लिए `false` करें; OnboardKit गाइड का splash manifest उदाहरण देखें। |
| Banner refresh | Refresh का मालिक AdMob या SDK में से एक रखें। SDK का `Reload` सामान्य banner लाता है, भले पहली request collapsible हो। अपना reload timer रखने से पहले ads गाइड देखें। |
| Skip callbacks | Kotlin के exhaustive `when` में नए `AdSkipReason` cases जोड़ें: ads में consent reasons और ads/onboarding में `SHOW_IN_BACKGROUND`। |
| Analytics | Native bind अब `fo_ad_bound` भेजता है। Dashboard में इसे vendor-confirmed `ad_show` से अलग रखें; bind callback से अतिरिक्त show event न भेजें। |

Remote cache और ad lifecycle fixes के लिए नई configuration नहीं चाहिए। सामान्य feature navigation में preload + show रखें; नया interstitial `loadAndShow` API वैकल्पिक है और built-in onboarding इसे नहीं बुलाता।

## Integration की जाँच

- Test ad IDs वाला debug build खोलें; ad requests से पहले initialization और consent result की जाँच करें।
- Ad तैयार न होने पर भी feature navigation पूरा होना चाहिए। Notification denial और Home/return जाँचें।
- खरीदारी होने पर ads दिखाने से पहले premium restore जाँचें और अपने Play catalog से paywall test करें।

अतिरिक्त विकल्पों के लिए module गाइड के source links या IDE में Go to Definition इस्तेमाल करें। पहले quickstart पूरा करें; हर विकल्प configure करना ज़रूरी नहीं है।

## License

MIT — [LICENSE](LICENSE) देखें।
