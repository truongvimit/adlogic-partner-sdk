# Trackkit — app और SDK के events

[← गाइड चुनें](README.hi.md) · [Trackkit API](../trackkit/README.hi.md)

तीन काम करने हैं: Tracker install करें, events के लिए एक destination (sink) जोड़ें, फिर अपनी app के अपने events track करें। Ads, OnboardKit, BillingKit और PayKit अपने events पहले से खुद भेजते हैं।

## 1. Dependency और event catalog

[Build setup](../README.hi.md#build-setup) के अनुसार JDK 17 और `minSdk 24+`। Ads, OnboardKit, BillingKit, PayKit और suite-firebase पहले से Trackkit को API dependency के रूप में expose करते हैं; इसे दोबारा declare करने की ज़रूरत नहीं। सिर्फ Trackkit इस्तेमाल करने वाली app यह declare करती है:

```groovy
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit:$sdkVersion"
}
```

अपने events हों तो [AppEvents.kt](examples/trackkit/AppEvents.kt) को अपने app package में copy करें और keys अपनी असली features के अनुसार बदलें। SDK events या analytics JSON के लिए catalog की ज़रूरत नहीं।

## 2. Install करें और event destinations चुनें

इसे अपनी मौजूदा Application में मिलाएँ, Ads/OnboardKit/BillingKit/PayKit install करने से पहले। Ads गाइड पहले ही पूरा किया हो तो दूसरी Application न बनाएँ।

```kotlin
package com.example.app

import android.app.Application
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import io.trackkit.sink.ConsoleSink

class AnalyticsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Tracker.install(this, TrackerConfig(appVersionCode = BuildConfig.VERSION_CODE.toLong()))
        if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
        // यहाँ ज़रूरी production sinks जोड़ें, किसी भी SDK के event भेजने से पहले.
    }
}
```

App में अभी कोई Application न हो तो `android:name` register करें। अपने app module का BuildConfig रखें; library का BuildConfig import न करें। App पहले से ads गाइड के अनुसार Tracker install करती हो, तो उसी जगह सिर्फ छूटा हुआ sink जोड़ें।

| आपकी app events कहाँ भेजना चाहती है | अतिरिक्त wiring कदम |
| --- | --- |
| Debug करते समय Logcat | उदाहरण में `ConsoleSink()`; कोई बाहरी service नहीं चाहिए। |
| Firebase Analytics | [Firebase गाइड, चरण 2](firebase-integration.hi.md#2-tracker-के-ज़रिए-analytics-भेजें)। |
| Ads जाँचने वाला dashboard | [AdTracer गाइड](adtracer-integration.hi.md), bridge सिर्फ debug में। |
| Ads/OB flow में Adjust | यह Tracker sink नहीं है; ads SDK खुद Adjust initialize करता है और revenue भेजता है। सिर्फ [Adjust setup](ads-onboarding-integration.hi.md#adjust-token-और-verification) के अनुसार token भरें। |
| अपना analytics backend | `TrackSink` implement करें और `Tracker.addSink` इस्तेमाल करें; `id` और `onEvent` ज़रूरी हैं, screen/identity/consent callbacks इस्तेमाल करते हों तो जोड़ें। Network काम अपनी app में queue करें, क्योंकि callbacks calling thread पर चलते हैं। |

**कोई भी event भेजे जाने से पहले sinks register करें।** बिना sink dispatch हुए events खो जाते हैं; Tracker disk पर कोई queue नहीं रखता। एक ही `id` वाला sink अनदेखा होता है; `Tracker.sinkIds()` से जाँचें।

## 3. असली interaction point से track करें

उदाहरण calls, जो उपयोगकर्ता के किसी feature को सच में खोलने के बाद होती हैं, feature का नाम आपकी app तय करती है:

```kotlin
package com.example.app

import io.trackkit.Tracker

fun trackFeatureOpened(featureName: String) {
    Tracker.track(AppEvents.FEATURE_OPEN, mapOf(AppEvents.FEATURE_NAME to featureName))
}

fun trackHomeVisible() {
    Tracker.screen(AppEvents.HOME_SCREEN, "HomeActivity")
}
```

हर event सिर्फ एक जगह बुलाएँ: navigation के बाद या screen दिखने पर। Compose में lifecycle/effect इस्तेमाल करें, हर recomposition पर नहीं। Firebase खुद Activities track कर सकता है; अलग screen name चाहिए तभी manual screen जोड़ें, और double counting से बचें।

Event/param नाम अक्षर से शुरू हों, सिर्फ अक्षर, अंक और `_` इस्तेमाल करें, अधिकतम 40 characters; `firebase_`, `google_` या `ga_` prefix न लें। हर event अधिकतम 25 params लेता है **defaults सहित**; String (अधिकतम 100 characters), number या Boolean इस्तेमाल करें, और संवेदनशील डेटा न भेजें। गलत keys, ज़्यादा params या बहुत लंबी strings पकड़ने के लिए QA में `strictValidation` चालू करें।

**Ad request/show/impression/revenue, OB funnel या purchase/paywall को UI callbacks में दोबारा log न करें।** SDK ये events पहले ही भेजता है, `Tracker.adRevenue` से paid impressions सहित।

## 4. Configuration table

| Option | SDK default / कब बदलें |
| --- | --- |
| `appVersionCode` | 0; उदाहरण आपकी app का `BuildConfig.VERSION_CODE` पढ़ता है। |
| `sdkVersion` | यह metadata SDK खुद देता है; अपनी app में इसे set या hardcode न करें। |
| `reportingCurrency` | `USD`; cumulative revenue के लिए आपकी app को दूसरी इकाई चाहिए तो बदलें। दूसरी currency वाले impressions sinks तक पहुँचते तो हैं, पर इस accumulator में नहीं जुड़ते; कोई automatic FX conversion नहीं है। |
| `consentPolicy` | `SEND_ALWAYS`: तुरंत dispatch, sinks consent/collection संभालते हैं। इसका मतलब यह नहीं कि उपयोगकर्ता ने consent दे दी है। |
| `consentPolicy = ConsentPolicy.QUEUE_UNTIL_RESOLVED` | Analytics के `UNKNOWN` से बाहर आने तक इंतज़ार करता है, फिर `DENIED` होने पर भी dispatch करता है; sinks को consent मिलती है और वे उसे संभालते हैं। यह सिर्फ granted होने पर भेजने वाला mode नहीं है। |
| `consentPolicy = ConsentPolicy.DROP_UNTIL_GRANTED` | Analytics granted न होने तक events drop करता है; drop हुए events दोबारा नहीं भेजे जाते। |
| `strictValidation` | `false`: error log करता है, गलत names/keys और ज़्यादा params हटाता है, लंबी strings काटता है। QA में exception फेंकने के लिए `BuildConfig.DEBUG` सेट करें। |
| `logLevel` | 1 warnings; 0 off, 2 verbose। |
| `enableRevenueAccumulator` | `true`; सिर्फ तब बंद करें जब आपकी app Tracker के भेजे cumulative revenue/milestone events इस्तेमाल न करती हो। |
| `defaultParams` / `Tracker.setDefaults(...)` | खाली; SDK पहले से `app_vc`, `sdk_ver`, `session_no`, `install_day`, `consent_ads` (consent के बाद) जोड़ता है। सिर्फ कुछ keys जोड़ें: ज़्यादा params काटे जाने से पहले defaults रखे जाते हैं। |
| `Tracker.setUserId(...)`, `setUserProperty(...)` | इन्हें तभी set करें जब आपकी app को identity चाहिए; logout पर हटाने के लिए `null` इस्तेमाल करें। User property की value 36 characters पर काटी जाती है और warning log होती है। |
| `ConsoleSink(tag, ringSize)` | `Trackkit/Console`, 100; debug log और memory में history, analytics storage नहीं। |

`ConsentCenter` इस्तेमाल करने वाली app में analytics पहले से granted पर और ads personalization पर map हैं; अलग call की ज़रूरत नहीं। आपकी app इस bridge की जगह अपना consent flow इस्तेमाल करती हो, तो निर्णय एक ही जगह wire करें:

```kotlin
package com.example.app

import io.trackkit.Tracker

fun onAppConsentResolved(analyticsAllowed: Boolean, adsPersonalizationAllowed: Boolean) {
    Tracker.setConsent(analytics = analyticsAllowed, ads = adsPersonalizationAllowed)
}
```

Consent का एक ही owner चुनें, ताकि आपका app flow और `ConsentCenter` एक-दूसरे को overwrite न करें। Mapping जाँचने के लिए `Tracker.currentConsent` इस्तेमाल करें; Tracker की consent, ads SDK के request करने की अनुमति वाली शर्त की जगह नहीं लेती।

## 5. जाँच और ज़रूरी files

- [ ] SDK events से पहले install, `sinkIds()` सही destinations दिखाता है; ConsoleSink सिर्फ debug में चालू।
- [ ] App events की keys एक जगह हैं और owning point पर ठीक एक बार भेजी जाती हैं; screens दो बार नहीं गिने जाते।
- [ ] अपनी app की चुनी policy के साथ consent unknown/granted/denied test करें; identity सेट की हो तो logout उसे साफ़ करता है।
- [ ] SDK के revenue/purchase events UI callbacks से दोबारा नहीं भेजे जाते।

आपको बस Gradle, अपनी मौजूदा Application, [AppEvents.kt](examples/trackkit/AppEvents.kt) और अपनी app screens के track points चाहिए। अपना `TrackSink` सिर्फ तभी बनाएँ जब सच में कोई ऐसा backend हो जो अभी support नहीं है; हर SDK event के चारों ओर wrapper layer की ज़रूरत नहीं।
