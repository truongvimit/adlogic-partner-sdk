# Trackkit (HI)

> English: **[README.md](README.md)** · Tiếng Việt: **[README.vi.md](README.vi.md)** · Architecture: **[ARCHITECTURE.md](ARCHITECTURE.md)**

Trackkit इस project में analytics का एकमात्र fan-out point है। app, `:ads` और `:onboardkitorigin`
जो भी event बनाते हैं, सब एक ही facade से गुज़रते हैं — इसलिए consent gating, default params, name
validation, dedupe और ad-revenue accumulation **एक ही बार** लिखे जाते हैं, हर partner build में
copy नहीं होते। इसने चार hand-rolled wrappers की जगह ली जो currency, revenue units और यहाँ तक कि
"Adjust चालू है या नहीं" पर भी आपस में असहमत थे; उनमें से कोई अब मौजूद नहीं।

Core module में **कोई vendor dependency नहीं** है — इसका पूरा dependency block एक `compileOnly`
annotation artifact भर है — इसलिए `:trackkit` जोड़ने से आपकी APK में कुछ नहीं बढ़ता। Vendors अलग sink
modules में रहते हैं: जिस partner को सिर्फ़ Firebase चाहिए, वह कभी कोई दूसरा analytics SDK compile
नहीं करता।

---

## Gradle setup

```groovy
repositories {
    google()
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
```

```groovy
// <tag> की जगह https://github.com/truongvimit/adlogic-partner-sdk/tags से कोई tag डालें
def sdkVersion = '<tag>'

dependencies {
    // Contract. जो भी module event भेजता है, वह इस पर depend करे।
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit:$sdkVersion"

    // Sinks — सिर्फ़ वही vendors लें जो आप वाकई ship करते हैं।
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit-firebase:$sdkVersion"
}
```

चूँकि यह repository कई modules publish करती है, JitPack इनका group `com.github.<user>.<repo>` रखता
है — repository का नाम group id का हिस्सा है। सभी modules को एक ही tag पर रखें — ये साथ publish होते
हैं और versions के बीच cross-tested नहीं हैं। JDK 17 और `minSdk` 24 चाहिए।

इसी repository के अंदर modules project के रूप में जुड़ते हैं:

```groovy
implementation project(':trackkit')
implementation project(':trackkit-firebase')
```

---

## Quickstart

`Application.onCreate()` में तीन lines, बाकी हर SDK से **पहले** — पूरे app में यही एकमात्र जगह है
जहाँ किसी vendor का नाम आता है:

```kotlin
Tracker.install(
    this,
    TrackerConfig(
        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        strictValidation = BuildConfig.DEBUG,
    ),
)
Tracker.addSink(FirebaseSink())
if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
```

**एक भी sink register न हो तो हर event validate होकर फेंक दिया जाता है।** `install()` उस स्थिति के
लिए warning log करता है, पर असली समाधान `addSink` बुलाना है।

इसके बाद अपने placements का नाम दें, एक बार, वहीं जहाँ ad units configure होते हैं:

```kotlin
// Ad unit id -> वह screen जो उसे माँगती है। AdMob का paid-event callback सिर्फ़ ad unit id जानता है,
// इसलिए यही वह चीज़ है जो ad_impression को बताती है कि पैसा किस screen ने कमाया।
PlacementRegistry.register(interSplashConfig.id, "inter_splash")
PlacementRegistry.register(nativeLanguageConfig.id, "native_language")
```

waterfall की **हर** id register करें, सिर्फ़ top tier नहीं। जो modules अपनी ad request खुद बनाते हैं
वे खुद register करते हैं — `:onboardkitorigin` का `ERainAdProvider` load से पहले हर onboarding unit
register कर देता है।

आख़िर में, जहाँ UMP का नतीजा आता है — **पूरे app में ठीक एक जगह**:

```kotlin
Tracker.setConsent(analytics = granted, ads = granted)
```

बस इतना ही integration है। आपको ad callbacks **wrap नहीं करने** — AdMob के ad objects `:ads` बनाता
है, इसलिए paid-event listener भी `:ads` ही लगाता है और ad lifecycle भी वही report करता है। अपना
`AdCallback` हमेशा की तरह पास कीजिए, वह ज्यों-का-त्यों वापस आता है।

`install()` से पहले track किए गए events buffer होते हैं, खोते नहीं — इसलिए order की गलती कुछ नहीं
बिगाड़ती। Reference wiring: `:app` में `GlobalApp.initTracking()` और `ConsentHandler.resolveConsent()`।

---

## Event catalog

हर event के साथ default params भी जाते हैं — `app_vc`, `sdk_ver`, `session_no`, `install_day` और
`consent_ads` — ताकि किसी भी funnel को बिना join किए cohort से slice किया जा सके। नीचे सिर्फ़ हर
event के अपने params दिए हैं।

| Event | कब fire होता है | Params | किसने भेजा |
|---|---|---|---|
| `ad_request` | load सचमुच network को भेजा गया | `placement`, `ad_format`, `ad_unit_id` | `:ads` |
| `ad_loaded` | network ने fill लौटाया | + `latency_ms` | `:ads` |
| `ad_load_failed` | no-fill या load error | + `error_code` | `:ads` |
| `ad_show` | ad वाकई दिखा | `placement`, `ad_format`, `ad_unit_id` | `:ads`, `:onboardkitorigin` |
| `ad_show_failed` | show call अस्वीकार हुई | + `error_code` | `:ads` |
| `ad_click` | user ने ad पर tap किया | `placement`, `ad_format`, `ad_unit_id` | `:ads` |
| `ad_closed` | full-screen ad बंद हुआ | `placement`, `ad_format`, `ad_unit_id` | `:ads` |
| `ad_skipped` | request से पहले ही policy ने मना किया (remote flag off, purchased user, unit नहीं) | `placement`, `ad_format`, `reason` | `:ads`, `:onboardkitorigin` |
| `ad_impression` | एक **paid** impression, `Tracker.adRevenue()` से | `placement`, `ad_format`, `ad_unit_id`, `ad_platform`, `ad_network`, `value`, `currency`, `precision` | `:trackkit` |
| `ad_revenue_total` | हर paid impression — संचयी ad LTV | `value`, `currency` | `:trackkit` |
| `ad_revenue_micro_flush` | बिना-flush हुआ हिस्सा reporting currency के 0.01 को पार करता है | `value`, `currency` | `:trackkit` |
| `ad_revenue_d3` / `d7` | install के 3 / 7 दिन बाद का पहला paid impression (एक बार) | `value`, `currency` | `:trackkit` |
| `fo_flow_start` | first-open flow में प्रवेश (denominator; skip होने पर भी fire) | — | `:onboardkitorigin` |
| `fo_splash_view` / `fo_splash_complete` | splash दिखा / पूरा हुआ | — / `dwell_ms` | `:onboardkitorigin` |
| `fo_language_view` | कोई language screen दिखी | `screen_index`, `variant` | `:onboardkitorigin` |
| `fo_language_select` | किसी language row पर tap | `screen_index`, `language` | `:onboardkitorigin` |
| `fo_language_complete` | language पक्की हुई | `screen_index`, `language`, `dwell_ms` | `:onboardkitorigin` |
| `fo_language_flow_complete` | पूरा language flow पूरा हुआ | `language` | `:onboardkitorigin` |
| `fo_step_view` | कोई onboarding step दिखा | `step`, `index`, `variant` | `:onboardkitorigin` |
| `fo_step_complete` | कोई onboarding step छोड़ा गया | `step`, `index`, `dwell_ms`, `exit_reason` | `:onboardkitorigin` |
| `fo_question_view` / `_answer` / `_complete` | question screen | `source` / `option_id`+`selected` / `count` | `:onboardkitorigin` |
| `fo_flow_complete` | पूरा first-open flow ख़त्म | `steps_shown`, `dwell_ms` | `:onboardkitorigin` |
| `iap_paywall_view` | paywall दिखा | `source` | `:app`, `:onboardkitorigin` |
| `iap_paywall_result` | paywall बंद हुआ, नतीजा जो भी हो | `source`, `status` (`purchased` / `dismissed` / `continue_with_ads`) | `:onboardkitorigin` |
| `iap_success` | purchase acknowledge हुआ | `product_id`, `value`, `currency`, `source` | `:ads` |
| `app_install_referrer` | Play install referrer, MMP से प्रति install एक बार पढ़ा गया | `referrer_source`, `referrer_medium`, `referrer_campaign`, `install_version`, `is_instant` | `:ads` |
| `consent_request` / `consent_shown` | UMP form माँगा गया / वाकई दिखा (प्रति session एक बार) | — | `:app` |
| `consent_result` | UMP का नतीजा आया | `status` (`granted`/`denied`/`not_required`/`error`), `error_code` | `:app` |

Screen views **event नहीं हैं**: `Tracker.screen(name, screenClass)` हर sink को अपना native
screen-view भेजने देता है (Firebase पर `screen_view`), reporting UI यही अपेक्षा करता है।

दो नियम, जिनके लिए यह catalog बना है:

1. **Event नाम में कभी variable मत डालिए।** एक `fo_step_complete` जिसमें `step` param हो — न कि
   `ob1_complete`, `ob2_complete`, `ob3_complete`।
2. **हर ad event अपना placement साथ रखता है।** AdMob का paid-event callback सिर्फ़ ad unit जानता है,
   इसलिए `PlacementRegistry` के बिना revenue को किसी screen से नहीं जोड़ा जा सकता।

---

## अपना event जोड़ना

किसी ऐसी app-विशिष्ट चीज़ के लिए जिसे कोई और app कभी report नहीं करेगा, escape hatch इस्तेमाल करें:

```kotlin
Tracker.track(SimpleEvent("app_widget_pinned", mapOf("source" to "home")))
```

इनमें से **कोई भी** सच हो तो `TrackkitEvents` में catalog entry बनाइए:

- एक से ज़्यादा module इसे भेजते हों,
- कोई dashboard, Adjust token या Meta custom conversion इस पर निर्भर हो,
- इसके params की spelling releases के बीच स्थिर रहनी ज़रूरी हो।

Catalog entry एक class होती है, इसलिए उसके params compiler-जाँचे signature बन जाते हैं और
`TaxonomyTest` उसका नाम अपने-आप validate करता है। `SimpleEvent` की string सिर्फ़ runtime पर जाँची
जाती है।

---

## अपना sink लिखना

`TrackSink` implement करके register कीजिए। सिर्फ़ `id` और `onEvent` अनिवार्य हैं; बाकी सबका default
no-op है।

```kotlin
class MyBackendSink(private val api: MyApi) : TrackSink {

    override val id: String = "my_backend"

    override fun onInstall(context: Context) = api.warmUp(context)

    override fun onEvent(name: String, params: Map<String, Any?>) = api.enqueue(name, params)

    override fun onConsent(consent: Consent) = api.setCollectionEnabled(consent.analyticsGranted)

    override fun onAdRevenue(impression: AdImpression) {
        // impression.value पहले से impression.currency में है — convert मत कीजिए।
        api.enqueueRevenue(impression.value, impression.currency)
    }
}

Tracker.addSink(MyBackendSink(api))
```

Contract:

- हर callback caller के thread पर चलता है। **Block मत कीजिए** — अपनी queue को सौंप दीजिए।
- Trackkit हर call को `runCatching` में लपेटता है, इसलिए exception फेंकने वाला sink बाकियों को नहीं
  गिरा सकता। फिर भी आपकी `id` के साथ warning log होगी।
- `id` स्थिर और अद्वितीय होनी चाहिए; पहले से registered id वाले दूसरे sink को `addSink` अनदेखा करता है।
- Params साफ़ होकर आते हैं: कोई null नहीं, strings 100 अक्षरों पर कटे, अधिकतम 25 keys।

---

## Adjust

Adjust, Trackkit का sink **नहीं** है। वह `:ads` में रहता है, क्योंकि जो भी signal वह लेता है वह वहीं
पैदा होता है, और क्योंकि `:ads` ads दिखाने का फ़ैसला करने के लिए Adjust का attribution नतीजा वापस
पढ़ता है। पूरा तर्क और इसे दोबारा देखने की शर्तें: [ARCHITECTURE.md](ARCHITECTURE.md)।

`:ads` के बाहर के किसी module से conversion milestone भेजने के लिए:

```java
MmpTracking.trackEvent(adjustTokenForThisMilestone);
```

पहले Adjust dashboard पर token बनाइए — खाली token warning के साथ छोड़ दिया जाता है, कभी
`AdjustEvent("")` के रूप में नहीं भेजा जाता।

---

## Sink options

हर sink अपने constructor से configure होता है; कोई global settings object नहीं है।

| Sink | Parameter | Default | क्या करता है |
|---|---|---|---|
| `FirebaseSink` | `collectionFollowsConsent` | `true` | `setAnalyticsCollectionEnabled(analyticsGranted)` बुलाता है। अगर UMP form सिर्फ़ ads के बारे में पूछता है तो **`false` दीजिए** — collection का hard switch उन users के लिए `first_open`, retention और पूरा funnel भी मार देता है जो personalisation से मना करते हैं, जबकि अकेला Consent Mode कानूनी ज़रूरत पहले ही पूरी कर देता है। |

---

## Consent Mode defaults

UMP का नतीजा आते ही Trackkit consent सेट कर देता है, पर उससे *पहले* की स्थिति manifest का विषय है।
app manifest में चारों defaults घोषित कीजिए, वरना consent से पहले का traffic आपके नहीं, Firebase के
अपने defaults इस्तेमाल करेगा:

```xml
<meta-data android:name="google_analytics_default_allow_analytics_storage" android:value="true" />
<meta-data android:name="google_analytics_default_allow_ad_storage" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_user_data" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_personalization_signals" android:value="false" />
```

Library manifest इन्हें जानबूझकर merge **नहीं** करता — हर partner की कानूनी स्थिति अलग होती है।

---

## Naming rules और GA4 limits

`EventValidator` हर event नाम और हर param key पर नीचे के नियम लागू करता है, और `TaxonomyTest`
build समय पर पूरे catalog पर इनकी जाँच करता है।

| नियम | सीमा | उल्लंघन पर |
|---|---|---|
| Event नाम grammar | `[a-zA-Z][a-zA-Z0-9_]*` | अस्वीकृत |
| Event नाम लंबाई | 40 अक्षर | अस्वीकृत |
| Param key grammar | event नाम जैसा ही | key हटा दी जाती है |
| प्रति event params | 25 | अतिरिक्त keys हटती हैं |
| String param value | 100 अक्षर | काट दिया जाता है |
| आरक्षित prefixes | `firebase_`, `google_`, `ga_` | अस्वीकृत |
| PII / secret keys | `purchase_token*`, `email`, `phone`, `device_id`, `android_id`, `gaid`, `idfa`, `advertising_id` | अस्वीकृत |

Validator के ऊपर, catalog की परंपरा है `<domain>_<object>_<action>`, lowercase `snake_case` में,
जहाँ `domain` इनमें से एक हो: `ad_`, `fo_`, `iap_`, `consent_`, `app_`।

`strictValidation = true` हर उल्लंघन को exception बना देता है। इसे `BuildConfig.DEBUG` से जोड़िए
ताकि taxonomy की गलती QA में फेल हो; release builds में यह घटकर एक log line और एक साफ़ किया हुआ
event रह जाता है।

---

## जो design निर्णय आपको विरासत में मिलते हैं

ये सब जानबूझकर हैं, और हर एक इस pipeline की पिछली पीढ़ी के audit में मिली किसी ठोस खामी को ठीक करता
है। अगर कोई आँकड़ा पुराने dashboard के मुकाबले "ग़लत" लगे, तो कारण यही है।

- **Currency conversion कभी नहीं।** पुराना रास्ता AdMob revenue को `26000` और MAX revenue को `25000`
  से गुणा करता था — एक ही file में दो अलग hardcoded दरें — और नतीजा Meta को VND बताकर भेजता था। अब
  `AdImpression.currency` ठीक वही रहता है जो ad SDK ने दिया, और reporting currency से भिन्न मुद्रा
  वाले impressions संचयी योग से बाहर रखे जाते हैं, न कि एक निरर्थक आँकड़े में जोड़ दिए जाते हैं।
- **प्रति impression कोई purchase event नहीं।** Meta `logPurchase` हर एक ad impression पर fire होता
  था। अब ऐसा कुछ नहीं होता।
- **Purchase revenue को 1,000,000 से भाग नहीं दिया जाता।** पुराना helper बिना शर्त भाग देता था,
  इसलिए IAP revenue — जो पहले से मुद्रा इकाइयों में था — लगभग दस लाख गुना कम report होता था।
- **Adjust के लिए सत्य का एक ही स्रोत।** दो स्वतंत्र flags आपस में असहमत हो सकते थे, इसलिए "Adjust
  बंद" होने पर भी events रिसते थे। अब एक ही जाँच config switch और `Adjust.initSdk` की सफलता, दोनों
  को कवर करती है।
- **हर ad event placement साथ रखता है**, और `ad_show` / `ad_show_failed` अस्तित्व में ही हैं, इसलिए
  show rate और show failures अनुमान नहीं, दिखने वाले आँकड़े हैं।
- **Event नाम में कोई variable नहीं।** audit किए गए SDK ने `ob1_complete`, `ob2_complete`, … *और*
  साथ में एक समानांतर `complete_ob1`, `complete_ob2`, … परिवार भेजा, जो एक ही transition को दो अलग
  घड़ियों पर दो बार गिनता था। यहाँ सिर्फ़ एक `fo_step_complete` है जिसमें `step` param है।
- **खाली Adjust token भेजा नहीं जाता, छोड़ा जाता है।** `AdjustEvent("")` client पर स्वीकार होकर
  server पर drop हो जाता है, यानी खाली token बिना किसी संकेत के revenue खो देता है।
