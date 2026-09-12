# AdTracer — ad debugging dashboard

[← एक गाइड चुनें](README.hi.md) · [AdTracer API](../adtracer/README.md)

QA के दौरान requests, load/show, failures और ads/OB flow की timeline देखने के लिए इसे इस्तेमाल करें। तीन कदम: debug dependency जोड़ें, bridge copy करें, dashboard खोलें।

## 1. Debug dependency जोड़ें

[Ads + OnboardKit](ads-onboarding-integration.hi.md) पूरा करें और अपनी Application में Tracker installed रखें। [build setup](../README.hi.md#build-setup) के अनुसार app में जोड़ें:

```groovy
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    debugImplementation "com.github.truongvimit.adlogic-partner-sdk:adtracer:$sdkVersion"
}
```

Trackkit इन SDKs के साथ पहले से आता है; dashboard खोलने के लिए Firebase की ज़रूरत नहीं। AdTracer के लिए `implementation` न इस्तेमाल करें: dashboard केवल debug builds में चाहिए।

## 2. Source set के अनुसार bridge copy करें

नीचे दिए तीनों files example की wiring से हैं; इनका package अपने app package में बदलें। दोनों `DebugSinks.kt` files का package और function signature एक ही होना चाहिए:

| उदाहरण file | कहाँ copy करें |
| --- | --- |
| [debug/AdTracerSink.kt](examples/adtracer/debug/AdTracerSink.kt) | `app/src/debug/java/<package>/tracking/AdTracerSink.kt` |
| [debug/DebugSinks.kt](examples/adtracer/debug/DebugSinks.kt) | `app/src/debug/java/<package>/tracking/DebugSinks.kt` |
| [release/DebugSinks.kt](examples/adtracer/release/DebugSinks.kt) | `app/src/release/java/<package>/tracking/DebugSinks.kt` |

`<package>` आपके app का package path है, उदाहरण के लिए `com/example/app`। हर file सही source set में रखें; किसी और build type को भी function का अपना version चाहिए — dashboard न चाहिए तो no-op इस्तेमाल करें।

अपनी मौजूदा Application में, **`Tracker.install` के बाद और SDK के ads events भेजने से पहले**, बुलाएँ:

```kotlin
import com.example.app.tracking.installDebugSinks

// Trong Application.onCreate, sau Tracker.install:
installDebugSinks()
```

Sink खुद `AdTracer.start(context)` बुलाता है। Release no-op function बुलाता है; `main` में `io.adtracer` import न करें, भले ही call `if (BuildConfig.DEBUG)` के अंदर हो।

## 3. Dashboard खोलें

Debug build को device/emulator पर चलाएँ, `AdTracer` पर filter किया Logcat खोलें और `Dashboard ready` line ढूँढें। Default port 8686 है; busy हो तो SDK क्रम से 8695 तक अगले ports आज़माता है। दोनों तरफ log में दिखे असली port का इस्तेमाल करें:

```bash
adb forward tcp:8686 tcp:8686
```

अपने computer पर [localhost dashboard](http://localhost:8686) खोलें, फिर splash/OB flow और app के ad placements चलाएँ। कई devices हों तो `adb -s <serial> forward ...` इस्तेमाल करें। `AdTracer.dashboardPort` चल रहा port है, server शुरू न हो पाए तो `-1`।

Timeline को placement के अनुसार पढ़ें। [उदाहरण JSON](examples/ads-onboarding/ad_config_debug.json) के test IDs कई placements में साझा हैं, इसलिए ad unit ID से resolve हुए clicks/impressions किसी दूसरे placement के नीचे दिख सकते हैं। QA test IDs पर चले तो load/show और `OB_FLOW` Logcat से भी मिलान करें; सिर्फ impression के placement से यह निष्कर्ष न निकालें कि flow गलत है। Dashboard पर preview/mock entries असली serve हुए ads नहीं हैं।

## 4. व्यवहार और विकल्पों की तालिका

| विषय | Default / कब बदलें |
| --- | --- |
| शुरुआत | `AdTracerSink.onInstall` से एक बार; start से पहले AdTracer बुलाने पर कोई event दर्ज नहीं होता। |
| Retention | अधिकतम 10 session journals रखता है; यह QA के लिए है, पूरा analytics store नहीं। |
| App screen का placement | helper/manager को `placement = AppAdPlacement.…` दें; छोड़ने पर `ad_request`/`ad_skipped` छूट सकते हैं। SDK clicks/impressions को ad unit ID से resolve करता है; unregistered ID `unknown` दिखता है। |
| OB placement | OnboardKit की internal keys दिखाता है; JSON से मिलान करना हो तो नीचे की lookup तालिका देखें। |
| Placement के बिना event | Timeline में `_tracer` के नीचे दिखता है और ad statistics में नहीं गिना जाता। |
| Ad revenue | Sink `ad_impression` event संभालता है; दो impressions से बचने के लिए `onAdRevenue` में दोबारा न भेजें। |
| Format | Bridge banner/collapsible, native/fullscreen, interstitial, rewarded और app-open को पहले से map करता है; हर screen पर format hardcode न करें। |
| SDK के बाहर अपना loader | Events तभी जोड़ें जब loader पहले से Tracker events नहीं भेजता। [AdTracer API](../adtracer/README.md) देखें; sink से गुजर रहे event के लिए सीधा call न जोड़ें। |

`adtracer_config.json`, अलग manifest Activity या example की preview screens की copy — किसी की ज़रूरत नहीं। App के मौजूदा placement catalog का इस्तेमाल करें; dashboard न ad IDs तय करता है और न ads enable करता है।

<details>
<summary>Dashboard पर OB flow की JSON key lookup</summary>

| JSON key | Dashboard पर placement |
| --- | --- |
| `banner_splash` | `splash_banner` |
| `inter_splash` | `splash_inter` |
| `native_lang` / `native_lang_alt` | `language1` / `language2` |
| `native_popup_lang` | `language_confirm` |
| `native_ob1` / `native_ob2` / `native_ob3` | `step_ob1` / `step_ob2` / `step_ob4` |
| `native_fs` | `fullscreen_ob3` |
| `inter_after_ob3` / `open_resume` | Key वही रहती है। |

</details>

## 5. सौंपने से पहले जाँच

- [ ] Debug में `Tracker.sinkIds()` के अंदर `adtracer` sink है और port log होता है; dashboard ADB से खुलता है।
- [ ] एक असली request सही placement के नीचे एक ही request/load/show chain बनाती है; impressions दोगुने नहीं होते।
- [ ] OB flow और app screens के ads दोनों दिखते हैं; load/show failures के साथ मिलती-जुलती जानकारी आती है।
- [ ] Release build no-op function के साथ सफल होता है; release runtime classpath में AdTracer नहीं है।

Dashboard खाली हो तो install का क्रम, sink ID, Tracker की consent policy और यह जाँचें कि app ने वाकई ad request किया। URL न खुले तो ads config बदलने से पहले log के अनुसार ADB device/port जाँचें।
