# Trackkit

> एक ही analytics facade जिससे हर module रिपोर्ट करता है, और हर vendor के लिए एक sink।

App, `:ads`, `:onboardkitorigin`, `:paykit` और `:billingkit` जो कुछ भी emit करते हैं, वह सब
`io.trackkit.Tracker` से होकर जाता है — इसलिए consent gating, default params, GA4 name validation, dedupe
और cumulative ad revenue सिर्फ़ एक बार implement होते हैं। Core vendor-free है: vendors अलग sink modules के
रूप में आते हैं।

Module layering: **[ARCHITECTURE.md](ARCHITECTURE.md)** · English: [README.md](README.md) ·
Tiếng Việt: [README.vi.md](README.vi.md)

## आवश्यकताएँ

| | |
|---|---|
| minSdk / compileSdk / JDK | 24 / 36 / 17 |
| आपके build में क्या जुड़ता है | कोई vendor dependency नहीं, कोई permission नहीं, कोई R8 rule नहीं; `consumer-rules.pro` AAR के साथ आता है |

## Installation

```groovy
repositories { google(); mavenCentral(); maven { url 'https://jitpack.io' } }
def sdkVersion = '<tag>' // https://github.com/truongvimit/adlogic-partner-sdk/tags
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit:$sdkVersion"
    // FirebaseSink यहाँ रहता है।
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

`:ads`, `:onboardkitorigin`, `:paykit`, `:billingkit` और `:suite-firebase` सभी
`api project(':trackkit')` declare करते हैं, इसलिए इनमें से कोई एक होने पर भी `Tracker` classpath पर आ जाता
है; इसे खुद सिर्फ़ तब declare करें जब आप कोई standalone `TrackSink` लिख रहे हों।

## Integration

`Tracker.install` को `Application.onCreate()` की पहली लाइन पर बुलाएँ, फिर अपने sinks register करें।

```kotlin
override fun onCreate() {
    super.onCreate()
    Tracker.install(this, TrackerConfig(
        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        strictValidation = BuildConfig.DEBUG,
    ))
    Tracker.addSink(FirebaseSink(collectionFollowsConsent = false))
    if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
}
```

बस इतनी ही integration है। आपको ad callbacks wrap नहीं करने हैं और ad units को placements से map भी नहीं
करना है: ad objects `:ads` बनाता है, इसलिए ad lifecycle और paid impressions `:ads` ही रिपोर्ट करता है।

`install()` से पहले निकले events buffer होते हैं — 128 items, उसके बाद सबसे पुराने एक warning के साथ गिरा
दिए जाते हैं। दूसरा `install()` अनदेखा कर दिया जाता है। कोई sink न हो तो हर event validate होकर हटा दिया
जाता है।

`TrackerConfig` में बाकी सब है — reporting currency, consent policy, log level, revenue accumulator,
default params — हर एक KDoc में documented। Defaults काम करते हैं; सिर्फ़ वही बदलें जो अलग चाहिए।

`Tracker` पर और भी: `track(name, params)`, `track(TrackEvent)`, `screen(name, screenClass)`,
`adRevenue(impression)`, `setDefault`, `setDefaults`, `setUserProperty`, `setUserId`, `removeSink`,
`flushPending()`, `sinkIds()`, तथा `isInstalled` / `currentConsent` properties।

## Consent

जब `:ads` classpath पर हो तो `Tracker.setConsent` न बुलाएँ।
`com.ads.module.consent.ConsentCenter` ही उसका एकमात्र caller है, पूरे process के लिए UMP सुलझाता है, और
`Tracker.setConsent(analytics = true, ads = personalized)` पास करता है।

Analytics axis हमेशा `true` रहता है: UMP सिर्फ़ ads के बारे में पूछता है, इसलिए एक इनकार से `first_open`,
retention और funnel भी मिट नहीं जाने चाहिए। इसे खुद सिर्फ़ उस app में बुलाएँ जिसमें `:ads` है ही नहीं, और एक
ही जगह से।

## Events

`io.trackkit.TrackkitEvents` में वे सारे नाम हैं जो यह suite emit करती है, domain के हिसाब से समूहबद्ध —
ads, revenue, first-open funnel, IAP, consent — और `TrackkitEvents.all()` runtime पर पूरा set लौटाता है।
ऐसी सूची कॉपी करने के बजाय जो पुरानी पड़ जाएगी, उसे IDE में खोलें; हर event class अपना अर्थ खुद बताती है।

हर event `app_vc`, `sdk_ver`, `session_no`, `install_day` भी साथ लाती है, और UMP सुलझते ही `consent_ads`।

**आपकी तरफ़ का एक setup step:** GA4 custom parameters सहेजता तो है, पर जब तक उन्हें custom dimensions के
रूप में register न किया जाए, वह उन पर रिपोर्ट नहीं करता। अपने dashboards के लिए ज़रूरी params register करें —
constants `TrackkitEvents` पर `PARAM_*` के रूप में हैं — वरना वे सिर्फ़ DebugView और BigQuery में रह जाएँगे।
Screen views events नहीं हैं: `Tracker.screen()` हर sink को अपना तरीक़ा चुनने देता है।

## अपने custom events

```kotlin
Tracker.track(SimpleEvent("app_widget_pinned", mapOf("source" to "home")))
```

`TrackkitEvents` में class तब जोड़ें जब एक से ज़्यादा module वह event emit करते हों, कोई dashboard या Adjust
token उस पर निर्भर हो, या उसके param की वर्तनी releases के आर-पार स्थिर रहनी हो।

| हर name और param key पर नियम | सीमा | उल्लंघन पर |
|---|---|---|
| व्याकरण | `[a-zA-Z][a-zA-Z0-9_]{0,39}` | event अस्वीकृत, param key हटा दी जाती है |
| प्रति event params | 25 | अतिरिक्त keys हटा दी जाती हैं |
| String param value / user property value | 100 / 36 अक्षर | काट दिए जाते हैं |
| आरक्षित prefixes | `firebase_`, `google_`, `ga_` | अस्वीकृत |
| PII / गोपनीय keys | purchase tokens, `email`, `phone`, `device_id`, `android_id`, `gaid`, `idfa`, `advertising_id` | अस्वीकृत |

Validator के ऊपर की convention: `<domain>_<object>_<action>`, lowercase `snake_case`, domain इनमें से एक —
`ad_`, `fo_`, `iap_`, `consent_`, `app_`। नाम में कभी variable न घुसाएँ — एक ही `fo_step_complete` जो `step`
param साथ ले, न कि `ob1_complete` और `ob2_complete` अलग-अलग।

## अपना sink लिखना

सिर्फ़ `id` और `onEvent` अनिवार्य हैं — `onInstall`, `onScreen`, `onUserProperty`, `onUserId`, `onConsent`
और `onAdRevenue` के no-op defaults हैं, Java में भी।

```kotlin
class MyBackendSink(private val api: MyApi) : TrackSink {
    override val id: String = "my_backend"

    override fun onEvent(name: String, params: Map<String, Any?>) {
        api.enqueue(name, params)
    }
    // impression.value पहले से impression.currency में है — convert न करें।
    override fun onAdRevenue(impression: AdImpression) {
        api.enqueueRevenue(impression.value, impression.currency)
    }
}

Tracker.addSink(MyBackendSink(api))
```

हर callback caller के thread पर चलता है और उसे block नहीं करना चाहिए। Exception फेंकने वाला sink पकड़ा
जाता है और उसकी `id` के साथ log होता है; बाकी sinks को event फिर भी मिलता है। `addSink` पहले से registered
`id` को अनदेखा कर देता है। Params साफ़ होकर पहुँचते हैं: कोई null नहीं, strings 100 अक्षरों पर सीमित, अधिकतम
25 keys। Debug builds के लिए `io.trackkit.sink.ConsoleSink` वही payload log करता है।

## समस्या-निवारण

| लक्षण | कारण | समाधान |
|---|---|---|
| किसी vendor तक कुछ नहीं पहुँचता; logcat कहता है `install() ran with no sink` | कोई sink register नहीं हुआ | `Tracker.addSink(FirebaseSink())` या अपना `TrackSink` |
| `N events were dropped before install (buffer overflow)` | `install()` से पहले 128 से ज़्यादा events निकले | `Tracker.install` को `onCreate()` की पहली लाइन पर ले जाएँ |
| `install() called twice` या `sink 'x' already registered` | दो बार `install()`, या दो sinks की एक ही `id` | एक `install()` रखें; हर sink को अलग `id` दें |
| Launch के बाद सारे events रुक जाते हैं | `consentPolicy = DROP_UNTIL_GRANTED` और analytics consent नहीं मिला | `:ads` के साथ analytics axis हमेशा मिलता है; जाँचें कि `ConsentCenter.request` चला या नहीं |
| `ad_impression` आते रहने पर भी `ad_revenue_total` 0 पर अटका है | impressions `reportingCurrency` से अलग currency में हैं | `TrackerConfig.reportingCurrency` को account की currency पर सेट करें |
| Production में `IllegalArgumentException: Trackkit: …` | release में `strictValidation` `true` छूट गया | उसे `BuildConfig.DEBUG` से जोड़ें |

## License

MIT — देखें [LICENSE](../LICENSE)।
