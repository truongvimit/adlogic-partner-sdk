**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# Trackkit

> एक ही analytics facade जिससे हर module report करता है, और हर vendor के लिए एक sink।

app, `:ads`, `:onboardkitorigin`, `:paykit` और `:billingkit` जो कुछ भी emit करते हैं, सब
`io.trackkit.Tracker` से होकर जाता है — इसलिए consent gating, default params, GA4 name validation,
dedupe और cumulative ad revenue एक ही बार लागू होते हैं। Core vendor-free है: vendors अलग sink
modules के रूप में आते हैं।

Module layering: **[ARCHITECTURE.md](ARCHITECTURE.md)** · English: [README.md](README.md) · Tiếng Việt: [README.vi.md](README.vi.md)

## आवश्यकताएँ

| | |
|---|---|
| minSdk / compileSdk | 24 / 36 |
| JDK | 17 |
| आपकी build में क्या जुड़ता है | कोई vendor dependency नहीं — एक `compileOnly` annotation artifact भर, कोई permission नहीं, कोई R8 rule नहीं; `consumer-rules.pro` AAR के साथ ही आता है |

## Installation

```groovy
repositories { google(); mavenCentral(); maven { url 'https://jitpack.io' } }
def sdkVersion = '<tag>' // https://github.com/truongvimit/adlogic-partner-sdk/tags
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit:$sdkVersion"
    // FirebaseSink lives here.
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

`:ads`, `:onboardkitorigin`, `:paykit`, `:billingkit` और `:suite-firebase` — हर एक
`api project(':trackkit')` declare करता है, इसलिए इनमें से कोई भी हो तो `Tracker` पहले से classpath
पर है; खुद declare तभी कीजिए जब सिर्फ़ एक standalone `TrackSink` लिखना हो। सभी modules को एक ही tag
पर रखिए।

## Quick start

`Tracker.install` को `Application.onCreate()` की पहली line पर बुलाइए, फिर अपने sinks register कीजिए।

```kotlin
override fun onCreate() {
    super.onCreate()
    Tracker.install(this, TrackerConfig(
        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        strictValidation = BuildConfig.DEBUG,
        logLevel = if (BuildConfig.DEBUG) 2 else 1,
    ))
    Tracker.addSink(FirebaseSink(collectionFollowsConsent = false))
    if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
}
```

`install()` से पहले emit हुए events buffer होते हैं — 128 items, उसके बाद सबसे पुराने warning के साथ
drop हो जाते हैं। दूसरा `install()` अनदेखा कर दिया जाता है। कोई sink न हो तो हर event validate होकर
फेंक दिया जाता है।

पूरा integration इतना ही है। आप ad callbacks wrap नहीं करते और ad units को placements से map नहीं
करते: ad objects `:ads` बनाता है, इसलिए ad lifecycle और paid impressions भी `:ads` ही report करता है।

`Tracker` पर बाकी: `track(name, params)`, `track(TrackEvent)`, `screen(name, screenClass)`,
`adRevenue(impression)`, `setDefault`, `setDefaults`, `setUserProperty`, `setUserId`, `removeSink`,
`flushPending()`, `sinkIds()`, और `isInstalled` / `currentConsent` properties.

## Configuration

`TrackerConfig` — हर field optional है।

| Field | Type | Default | क्या करता है |
|---|---|---|---|
| `appVersionCode` | `Long` | `0L` | हर event के साथ `app_vc` के रूप में जुड़ता है |
| `sdkVersion` | `String` | `"1.0.0"` | `sdk_ver` के रूप में जुड़ता है |
| `reportingCurrency` | `String` | `"USD"` | जिस currency में cumulative-revenue events जोड़े जाते हैं; दूसरी currency वाले impressions sinks तक पहुँचते तो हैं पर total से बाहर रहते हैं |
| `consentPolicy` | `ConsentPolicy` | `SEND_ALWAYS` | consent `UNKNOWN` रहने तक क्या होता है |
| `strictValidation` | `Boolean` | `false` | गलत name या param key को sanitise करने के बजाय throw करता है |
| `logLevel` | `Int` | `1` | `0` बंद, `1` warnings, `2` verbose |
| `enableRevenueAccumulator` | `Boolean` | `true` | चार cumulative `ad_revenue_*` events emit करता है |
| `defaultParams` | `Map<String, Any?>` | `emptyMap()` | हर event में merge होता है, वही असर जो `Tracker.setDefaults` का है |

| `ConsentPolicy` | consent `UNKNOWN` रहते समय व्यवहार |
|---|---|
| `SEND_ALWAYS` | तुरंत dispatch; consent बाद में सिर्फ़ vendor flags बदलता है |
| `QUEUE_UNTIL_RESOLVED` | buffer करता है, consent किसी भी तरह तय होते ही flush |
| `DROP_UNTIL_GRANTED` | drop; बाद में consent मिलने पर कुछ replay नहीं होता |

## Consent

जब `:ads` classpath पर हो तो `Tracker.setConsent` मत बुलाइए।
`com.ads.module.consent.ConsentCenter` इसका इकलौता caller है, पूरे process के लिए UMP resolve करता
है, और `Tracker.setConsent(analytics = true, ads = personalized)` pass करता है।

analytics axis हमेशा `true` रहता है: UMP सिर्फ़ ads के बारे में पूछता है, इसलिए इनकार से `first_open`,
retention और funnel भी नहीं मिटने चाहिए। इसे खुद तभी बुलाइए जब app में `:ads` न हो — और एक ही जगह से।

## Event catalog

`io.trackkit.TrackkitEvents` में 37 नाम हैं; `TrackkitEvents.all()` पूरा set लौटाता है। हर event के
साथ `app_vc`, `sdk_ver`, `session_no`, `install_day` भी जाते हैं, और UMP तय होने के बाद `consent_ads`।

| समूह | Events | Event-specific params |
|---|---|---|
| Ads | `ad_request`, `ad_loaded`, `ad_load_failed`, `ad_show`, `ad_show_failed`, `ad_click`, `ad_closed`, `ad_reward_earned`, `ad_skipped` | `placement`, `ad_format`, `ad_unit_id`, साथ में `latency_ms`, `error_code` या `reason` |
| Ads | `ad_impression` — एक paid impression, `Tracker.adRevenue` के ज़रिए | `placement`, `ad_format`, `ad_unit_id`, `ad_platform`, `ad_network`, `value`, `currency`, `precision` |
| Revenue | `ad_revenue_total`, `ad_revenue_micro_flush` (हर बार reporting currency का 0.01), `ad_revenue_d3`, `ad_revenue_d7` | `value`, `currency` |
| First open | `fo_flow_start`, `fo_splash_view`, `fo_splash_complete`, `fo_language_view`, `fo_language_select`, `fo_language_complete`, `fo_language_flow_complete`, `fo_step_view`, `fo_step_complete`, `fo_question_view`, `fo_question_answer`, `fo_question_complete`, `fo_flow_complete` | `step`, `index`, `screen_index`, `language`, `variant`, `dwell_ms`, `exit_reason`, `source`, `count`, `steps_shown`, `option_id`, `selected` |
| IAP | `iap_paywall_view`, `iap_paywall_result`, `iap_click`, `iap_success`, `iap_fail`, `iap_dismiss` | `source`, `status`, `product_id`, `value`, `currency`, `error_code`, `reason` |
| Consent | `consent_request`, `consent_shown`, `consent_result` | `status`, `error_code`, `source` |
| अन्य | `app_install_referrer` | `referrer_source`, `referrer_medium`, `referrer_campaign`, `install_version`, `is_instant` |

Screen views events नहीं हैं — `Tracker.screen()` से हर sink अपना screen view खुद emit करता है। ऊपर
के params को GA4 custom dimensions के रूप में register कीजिए, वरना वे सिर्फ़ DebugView और BigQuery
में ही रह जाएँगे।

## Custom events

```kotlin
Tracker.track(SimpleEvent("app_widget_pinned", mapOf("source" to "home")))
```

इसके बजाय `TrackkitEvents` में एक class तब जोड़िए जब एक से ज़्यादा module वह event emit करते हों, कोई
dashboard या Adjust token उस पर निर्भर हो, या उसके param की spelling releases के बीच स्थिर रहनी हो।

| हर name और param key पर नियम | सीमा | उल्लंघन पर |
|---|---|---|
| Grammar | `[a-zA-Z][a-zA-Z0-9_]{0,39}` | event अस्वीकृत, param key हटा दी जाती है |
| प्रति event params | 25 | अतिरिक्त keys हट जाती हैं |
| String param value / user property value | 100 / 36 अक्षर | काट दिया जाता है |
| आरक्षित prefixes | `firebase_`, `google_`, `ga_` | अस्वीकृत |
| PII / secret keys | `purchase_token`, `purchase_token_part_1`, `purchase_token_part_2`, `email`, `phone`, `device_id`, `android_id`, `gaid`, `idfa`, `advertising_id` | अस्वीकृत |

Validator के ऊपर convention: `<domain>_<object>_<action>`, lowercase `snake_case`, domain इनमें से एक
— `ad_`, `fo_`, `iap_`, `consent_`, `app_`। नाम में कभी variable मत भरिए — एक ही `fo_step_complete`
जो `step` param रखता हो, न कि `ob1_complete` और `ob2_complete` अलग-अलग।

## अपना custom sink लिखना

सिर्फ़ `id` और `onEvent` ज़रूरी हैं — `onInstall`, `onScreen`, `onUserProperty`, `onUserId`,
`onConsent` और `onAdRevenue` के no-op defaults हैं, Java में भी।

```kotlin
class MyBackendSink(private val api: MyApi) : TrackSink {
    override val id: String = "my_backend"

    override fun onEvent(name: String, params: Map<String, Any?>) {
        api.enqueue(name, params)
    }
    // impression.value is already in impression.currency — do not convert.
    override fun onAdRevenue(impression: AdImpression) {
        api.enqueueRevenue(impression.value, impression.currency)
    }
}

Tracker.addSink(MyBackendSink(api))
```

हर callback caller के thread पर चलता है और block नहीं करना चाहिए। throw करने वाला sink पकड़ लिया
जाता है और उसके `id` के साथ log होता है; बाकी sinks को event फिर भी मिलता है। `addSink` पहले से
registered `id` को अनदेखा कर देता है। Params sanitised आते हैं: कोई null नहीं, strings 100 अक्षरों
तक सीमित, ज़्यादा से ज़्यादा 25 keys। Debug builds के लिए
`io.trackkit.sink.ConsoleSink(tag = "Trackkit/Console", ringSize = 100)` वही payload log करता है।

## Troubleshooting

| लक्षण | कारण | समाधान |
|---|---|---|
| किसी vendor तक कुछ नहीं पहुँचता; logcat में `install() ran with no sink` | कोई sink register नहीं हुआ | `Tracker.addSink(FirebaseSink())` या अपना `TrackSink` जोड़िए |
| `N events were dropped before install (buffer overflow)` | `install()` से पहले 128 से ज़्यादा events emit हुए | `Tracker.install` को `onCreate()` की पहली line पर ले जाइए |
| `install() called twice` या `sink 'x' already registered` | दोहरा `install()`, या दो sinks का एक ही `id` | एक ही `install()` रखिए; हर sink को अलग `id` दीजिए |
| launch के बाद हर event रुक जाता है | `consentPolicy = DROP_UNTIL_GRANTED` और analytics consent नहीं मिला | `:ads` के साथ analytics axis हमेशा granted रहता है; जाँचिए कि `ConsentCenter.request` चला या नहीं |
| `ad_impression` आते रहते हैं पर `ad_revenue_total` 0 पर अटका है | impressions `reportingCurrency` से अलग currency में हैं | `TrackerConfig.reportingCurrency` को account currency पर सेट कीजिए |
| production में `IllegalArgumentException: Trackkit: …` | release में `strictValidation` `true` छूट गया | इसे `BuildConfig.DEBUG` से wire कीजिए |

## License

MIT — देखिए [LICENSE](../LICENSE)।
