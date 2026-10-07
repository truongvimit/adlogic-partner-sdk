# Firebase — Analytics और Remote Config

[English](firebase-integration.md) · [Tiếng Việt](firebase-integration.vi.md) · [हिन्दी](firebase-integration.hi.md)

[← गाइड चुनें](README.hi.md) · [suite-firebase API](../suite-firebase/README.md)

पहले step 1 करें, फिर अपनी app को जो हिस्से चाहिए वही चुनें: Analytics (step 2), remote ads (step 3), remote paywall (step 4). `suite-firebase` में Firebase Analytics, Remote Config और Trackkit पहले से हैं।

## 1. अपनी app के लिए Firebase configure करें

[build setup](../README.hi.md#build-setup), JDK 17 और `minSdk 24+` पूरा करें। Firebase Console पर अपने असली `applicationId` के साथ Android app register करें और `google-services.json` को `app/` में download करें। यह file Firebase जारी करता है; example की file copy न करें। Debug का application ID अलग हो तो उससे मेल खाती app register करें और उसकी file उसी source set में रखें। [Firebase: Android setup](https://firebase.google.com/docs/android/setup)।

अपनी मौजूदा Google Services configuration रखें। Plugin अभी न हो तो `gradle.properties` में अपने app toolchain के अनुसार `googleServicesVersion` घोषित करें और इसे root `build.gradle` में मिलाएँ:

```groovy
buildscript {
    def googleServicesVersion = project.providers.gradleProperty('googleServicesVersion').get()
    repositories { google(); mavenCentral() }
    dependencies { classpath "com.google.gms:google-services:$googleServicesVersion" }
}
```

`app/build.gradle` में, जो Android/Kotlin plugins पहले से इस्तेमाल कर रहे हैं उन्हें रखें:

```groovy
plugins { id 'com.google.gms.google-services' }

def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

App पहले से Firebase configure करती हो तो मौजूदा BOM रखें; Analytics/Remote Config दोबारा न जोड़ें। Ads/PayKit सिर्फ तब जोड़ें जब उस kit का remote source इस्तेमाल कर रहे हों।

## 2. Tracker के ज़रिए analytics भेजें

अपनी मौजूदा Application में [Tracker](trackkit-integration.hi.md#2-install-करें-और-event-destinations-चुनें) install करें, फिर sink को **event भेजने वाले SDKs install करने से पहले** जोड़ें:

```kotlin
import io.suite.firebase.FirebaseSink
import io.trackkit.Tracker

// Application.onCreate में, ऊपर दिए क्रम में:
Tracker.addSink(FirebaseSink())
```

App events `Tracker` के ज़रिए भेजती है; sink उन्हें Firebase तक पहुँचाता है। Ads/purchase events और `ad_impression` SDK पहले ही भेजता है; उन्हें UI callbacks में दोबारा log न करें।

### शुरुआती consent

आपकी app ने जो consent configuration चुनी है वही रखें। Library कोई शुरुआती value घोषित नहीं करती; जब तक Tracker के दोनों axes `UNKNOWN` हैं, sink आपकी Firebase settings नहीं बदलता। App चारों values denied से शुरू करना चुने तो इसे `<application>` के अंदर रखें:

```xml
<meta-data android:name="google_analytics_default_allow_analytics_storage" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_storage" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_user_data" android:value="false" />
<meta-data android:name="google_analytics_default_allow_ad_personalization_signals" android:value="false" />
```

यह आपकी app का चुनाव है, SDK का default नहीं। `ConsentCenter` पहले से Tracker से जुड़ा है और हमेशा analytics को granted तथा ads को personalization के जवाब पर map करता है; UMP form अलग से analytics consent dialog नहीं है। अपना consent flow रखने वाली app के लिए [Trackkit](trackkit-integration.hi.md#4-configuration-table) देखें। Collection के विकल्प [नीचे की तालिका](#5-configuration-तालिका) में हैं।

सभी modules के लिए [JitPack](https://jitpack.io/#truongvimit/adlogic-partner-sdk) का **newest SDK version** इस्तेमाल करें। केवल Firebase keys जोड़ने से app में integrated पुराना SDK update नहीं होता।

## 3. Remote ads और onboarding JSON

<a id="remote-json"></a>

### 3.1. Source install करें और तीन String parameters publish करें

पहले [Ads + OnboardKit](ads-onboarding-integration.hi.md) पूरा करें। Application में `AdRemoteConfig.initializeFromAssets(this)` के बाद source एक बार install करें। Splash खोलने से पहले `OnboardingSdk.install` और `OnboardKitSetup.configure()` पूरा रखें:

```kotlin
import com.ads.module.config.AdConfig
import io.suite.firebase.FirebaseAdConfigSource

AdConfig.install(FirebaseAdConfigSource())
```

`FirebaseAdConfigSource` ad-unit document और दोनों नए settings documents पढ़ता है। अलग source, manual `getString`, हर field का setter या अतिरिक्त Firebase fetch नहीं चाहिए। `AdConfig.install` Firebase द्वारा आखिरी बार activate की गई `ad_remote_config` value (या Firebase उसे न दे सके तो SDK की persisted आखिरी valid copy) background में लागू करता है, इसलिए धीमा या failed fetch asset पर नहीं, अंतिम remote document पर चलता है।

1. अपनी app का Firebase project → **Remote Config → Parameters** खोलें।
2. मौजूदा `ad_remote_config` key रखें। **`ad_behavior_config`** और **`onboarding_config`** जोड़ें; type **String** चुनें।
3. नीचे वाली file से संबंधित JSON object का पूरा content parameter की default value में paste करें। सीधे `{ ... }` paste करें: Markdown fences, बाहरी quotes, escaped JSON string या parameter के नाम वाला wrapper न जोड़ें। यह parameter की value है, Firebase के पूरे template को import करने की file नहीं।
4. आवश्यक values बदलें, save करें और **Publish changes** करें। Condition/A/B test में हर variant की value भी JSON object हो। SDK valid fields को local defaults से मिलाता है; नया remote object पिछले remote overrides की जगह लेता है, इसलिए जिन overrides को रखना है उन्हें नए object में भी रखें।

| Firebase parameter (String) | Paste करने वाला content | काम |
| --- | --- | --- |
| `ad_remote_config` | [ad_config.json](examples/ads-onboarding/ad_config.json), app की production IDs के साथ | हर placement की एक key: `{"id", "isEnable"}` floors वाला `ids` waterfall (सबसे ऊँचा पहले, all-price आख़िर में; एक unit के लिए एक floor), switches और CTA fields। किसी key का remote `ids` पूरा array replace करता है, इसलिए हर floor उसके `isEnable` के साथ publish करें। यहाँ declare की गई key आपकी `ad_config.json` से ऊपर है, और वह file code में लिखी ad unit IDs से ऊपर है; ID वाला `open_resume` app-open चालू करता है। Parameter का नाम न बदलें। |
| `ad_behavior_config` | [ad_behavior_config.json](examples/ads-onboarding/ad_behavior_config.json) | Ad format behavior, timeout, reload/cache और native CTA radius। |
| `onboarding_config` | [onboarding_config.json](examples/ads-onboarding/onboarding_config.json) | Splash/LFO/OB behavior, X/Skip, swipe और preload। |

`ad_config.json` और `ad_config_debug.json` local asset filenames हैं; default Firebase source ad units के लिए **एक** key `ad_remote_config` पढ़ता है (Console में `ad_remote_config` न हो तो `ads_remote_config`)। इस setup में अलग `ad_config`, `ad_config_debug`, `ad_behavior_config_debug` या `onboarding_config_debug` parameters न बनाएँ। हर build अपनी settings `ad_config.json` से पढ़ता है। Debuggable build `ad_config_debug.json` भी पढ़ता है, जिसमें हर placement key के लिए सिर्फ `ids` होता है, आम तौर पर एक test floor: ये floors placement के पूरे waterfall की जगह लेते हैं, इसलिए हर placement सामान्य load path से अपनी test ID load करता है। Debug file का कोई भी दूसरा field `WARN` के साथ ignore होता है, और `ad_config.json` में चालू जिस placement की debug entry नहीं है उसे ad नहीं मिलता (यह भी `WARN`)। Remote `ad_remote_config` IDs को छोड़कर हर field लागू करता है; `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` remote की declare की गई IDs भी ले लेता है। Debug file न हो तो debug `ad_config.json` की IDs इस्तेमाल करता है। दोनों settings parameters release की तरह लागू होते हैं। प्रयोग के लिए test project/conditions इस्तेमाल करें। पुराने `ob_*` keys compatible रहते हैं; उन्हें इन JSON objects के अंदर न डालें। Backend द्वारा भेजी गई `ob_*` key इन documents से नीचे और आपके app assets व Kotlin config से ऊपर रहती है। [Firebase parameter types और conditions](https://firebase.google.com/docs/remote-config/parameters)।

`ObSplashActivity` पहले ही `AdConfig.refresh()` बुलाता है। Sample के `AdsConfig.fromAdConfig()` से IDs/settings fetch के बाद resolve होते हैं; `onRemoteFetched` में `OnboardKitSetup.configure()` दोबारा बुलाना आवश्यक नहीं। Hook सिर्फ app के अपने काम के लिए रखें। SDK splash न हो तो kits initialize करने के बाद संबंधित screen/request से पहले coroutine में `AdConfig.refresh()` await करें; वह refresh पुराने `ob_*` keys भी दोबारा पढ़ता है।

<a id="local-defaults"></a>

### 3.2. Remote उपलब्ध न होने पर अपने local defaults बनाएँ

SDK में दोनों JSON defaults पहले से bundled हैं। **SDK defaults सही हों तो app में कोई अतिरिक्त file आवश्यक नहीं।** Offline/default behavior बदलने के लिए:

1. आवश्यकता हो तो `app/src/main/assets/` directory बनाएँ।
2. ठीक इन्हीं नामों से `ad_behavior_config.json` और/या `onboarding_config.json` बनाएँ। पूरी sample file copy करें, या नीचे की तरह सिर्फ बदलने वाले fields लिखें। App का asset उसी नाम के SDK asset की जगह लेता है, और file का हर field आपकी Kotlin config से ऊपर रहता है।
3. `schema_version: 1`, सही types और enums रखें। Unused field को छोड़ दें, `null` न दें। Missing fields SDK defaults लेते हैं, जब तक मौजूदा host configuration अपना fallback न दे।
4. App rebuild करके process restart करें। SDK initialization पर assets पढ़ता है; file edit करना runtime remote update नहीं है। नया parser या Firebase `setDefaultsAsync` में इन values की copy न जोड़ें।

`app/src/main/assets/ad_behavior_config.json`:

```json
{
  "schema_version": 1,
  "native": {
    "load": { "tier_timeout_ms": 25000 }
  }
}
```

`app/src/main/assets/onboarding_config.json`:

```json
{
  "schema_version": 1,
  "splash": { "permissions": { "no_internet_prompt_enabled": false } },
  "onboarding": {
    "navigation": { "lock_pager_swipe": false },
    "fullscreen": { "skip": { "delay_ms": 1500 } }
  }
}
```

यह local उदाहरण SDK connection prompt बंद करता है ताकि offline flow जाँचा जा सके। Offline में ad fill नहीं होता; resolved config और navigation जाँचें, तथा visual template को online test ad IDs के साथ जाँचें।

ये **custom उदाहरण** हैं, SDK defaults में बदलाव नहीं। पूरी sample files SDK के वास्तविक defaults से मेल खाती हैं, सिवाय `onboarding.primary_color` के: sample का `""` remote publish होने पर SDK के `#FF375E` की जगह आपकी app का मौजूदा UI color रखता है (app asset में `""` वापस `#FF375E` पर जाता है)। JSON field names और enum values का अनुवाद न करें।

हर setting की precedence: remote grouped-document field > backend द्वारा भेजी गई legacy `ob_*` key > custom local JSON > host Kotlin config/hooks > bundled SDK defaults। जो keys backend ने कभी नहीं भेजीं वे गिनी नहीं जातीं।

| स्थिति | SDK की चुनी हुई values |
| --- | --- |
| Successful fetch, valid fields | Remote fields app के local JSON को किसी भी scope पर override करते हैं। |
| पहली run, fetch failure/timeout, valid remote cache नहीं | Custom local JSON → मौजूदा host fallback → bundled SDK defaults। |
| पहले successful fetch के बाद failure/timeout | अंतिम valid remote snapshot/cache रखें; उसमें न आने वाले fields local fallback लेते हैं। **Fetch failure valid remote cache के ऊपर local values लागू नहीं करता।** |
| Successful fetch में field/parameter missing, या field का type/enum/range गलत अथवा `null` | उस field का पुराना remote override हटाएँ और उसके लिए अगला नीचे वाला source लें। Invalid fields logcat tag `AdLogicSettings` में log होते हैं। Valid `false`/`0` बने रहते हैं। `1` के अलावा कोई `schema_version` भी इसी तरह सिर्फ खुद drop होता है; document के बाकी fields लागू रहते हैं (`ad_remote_config` में पूरा document reject होता है)। |
| पूरा JSON malformed/blank | उस document का अंतिम valid snapshot रखें; valid remote न हो तो local/defaults रखें। Rejection `AdLogicSettings` में log होता है। |

किसी नए document के सभी remote overrides हटाने के लिए `{}` या `{"schema_version":1}` publish करके successful fetch करें। Blank String malformed JSON है और पिछला valid snapshot रखती है। Valid remote process restart के बाद भी persisted रहता है; सिर्फ local file बदलना cached remote fields को override नहीं करता। पहली-run local fallback को remote cache रहित test installation पर जाँचें, या offline test से पहले overrides सफलतापूर्वक हटाएँ। ऊपर के उदाहरणों में uncached offline run native timeout **25000 ms**, unlocked swipe और Skip delay **1500 ms** इस्तेमाल करती है।

किसी भी app asset में मौजूद हर valid field explicit assignment है, `false`/`0` समेत, और आपकी Kotlin constructor/setter values से ऊपर रहता है, चाहे वह SDK default की बिना बदली copy ही हो। Kotlin values लागू रहनी चाहिए तो सिर्फ बदले हुए fields ship करें। Firebase पर published default value **remote** value है; वह SDK की bundled local default से अलग है। यह source Firebase in-app defaults को fetched remote data नहीं मानता।

<a id="remote-notes"></a>

### 3.3. Timing, version और QA

- ऐसा SDK build इस्तेमाल करें जिसमें grouped settings और `AdsConfig.fromAdConfig()` हों; सभी modules की versions समान रखें। Firebase keys जोड़ने भर से पुराने SDK में यह सुविधा नहीं आती।
- **Breaking:** `ad_remote_config` अब हर placement के लिए एक key लेता है, floors `"ids"` में; अलग `<key>_high*` keys अब नहीं पढ़ी जातीं। हर `_high*` ID को `{"id", "isEnable"}` के रूप में `<key>.ids` में ले जाएँ (पुराना `isEnable` रखते हुए), पुरानी `<key>.id` को आख़िरी floor बनाएँ और `_high*` entries हटा दें। बाकी हर `"id": "X"` `"ids": [{"id": "X"}]` बनता है; placement-level `"id"` अब नहीं पढ़ा जाता। नए format वाली value को app version ≥ इस SDK वाले पहले version की condition के तहत publish करें; यह condition अनिवार्य है। SDK 5.5.6 या नए version वाली app इसे पाए तो crash नहीं होती, लेकिन `ids` ignore करके अपने APK में बना waterfall रखती है; SDK 5.5.5 या पुराने version वाली app अपनी पूरी configuration को remote document से बदल देती है और हर ad unit खो देती है। Update के बाद पहले launch पर cache की हुई पुराने format वाली value एक session तक active रह सकती है; उसका `id` और `_high*` entries नहीं पढ़ी जातीं, इसलिए अगले fetch तक हर placement `ad_config.json` का waterfall रखता है। देखें [Waterfall: हर placement की एक key](ads-onboarding-integration.hi.md#waterfall-हर-placement-की-एक-key)।
- Splash consent, remote refresh और billing साथ शुरू करता है। Consent पूरा होते ही banner/native slot और interstitial मौजूदा configuration और entitlement से request होते हैं; remote या billing का इंतज़ार नहीं होता। Cache या fetch से मिले remote values asset से ऊपर रहते हैं। SDK-owned refresh splash बंद होने के बाद भी चलता है, background wait कम-से-कम 60 सेकंड है। बाद के reads नई values लेते हैं; पहले भेजे requests, timers और तय navigation दोबारा नहीं चलते। `SAME_TIME` और `ALTERNATE` दोनों यही क्रम अपनाते हैं। `onRemoteFetched()` केवल जीवित splash पर चलता है; process-owned integration के लिए `SettingsRegistry.addFetchListener` इस्तेमाल करें।
- LFO/OB natives अपना frame `ad_remote_config` में placement key के `templateId` से और blocks का क्रम `components` से लेते हैं; remote app asset को override करता है। `positionCTA` हटा दिया गया है और अनदेखा होता है। `colorCTA` CTA और Ad badge दोनों को रंग देता है। JSON `native_template`/`content_template` overrides हटा दिए गए हैं। `R.layout`, resource references, system bars, orientation और progress indicators app code में रखें। Consent और premium लागू रहते हैं। एकमात्र global gate `ad_behavior_config.global.ads_enabled` है। हर LFO/onboarding placement को उसके `isEnable` और placement flag से बंद करें; duplicate flow-wide controls हटाए गए हैं।
- Firebase fetch साझा है: एक साथ आए requests एक ही fetch से जुड़ते हैं। हर `AdConfig.refresh()` नया fetch माँगता है; सिर्फ PayKit process में successful fetch reuse करता है। Firebase का default minimum fetch interval 12 घंटे है और SDK उसे नहीं बदलता, इसलिए Console edit पहुँचने में इतना समय लग सकता है। Edits अगले launch तक पहुँचें, इसके लिए पहले fetch से पहले अपनी Application में इसे set करें, उदाहरण: `FirebaseRemoteConfig.getInstance().setConfigSettingsAsync(remoteConfigSettings { minimumFetchIntervalInSeconds = if (BuildConfig.DEBUG) 0 else 3600 })`। Console QA में process restart करें; केवल Activity दोबारा खोलना fresh network fetch की गारंटी नहीं।
- `AdConfig.refresh()` बताता है कि कोई `ad_remote_config` document लागू हुआ या नहीं। इस Boolean को नए documents की success flag न मानें।
- Valid remote override, missing/invalid fields, पहली-run offline local fallback और पुराने valid remote का offline cache reuse जाँचें। Local JSON को app में rebuild करना ज़रूरी है।

UA/MO प्रयोग से पहले [settings reference, field ownership और सभी defaults](remote-settings.hi.md) देखें।

## 4. Remote paywall JSON

पहले [PayKit local](paywall-integration.hi.md) पूरा करें, dependency और resource fallback सहित। अपनी Application में, `PayKit.install` के बाद बुलाएँ:

```kotlin
import io.paykit.PayKit
import io.suite.firebase.FirebaseConfigSource

// Application.onCreate में, ऊपर दिए क्रम में:
PayKit.configSource(FirebaseConfigSource())
```

Console पर **String** parameter `paywall_config` बनाएँ, [नमूना JSON](examples/paywall/paywall_config.json) paste करें, catalog/copy अपनी app के अनुसार बदलें, फिर Publish करें। [PayKit तालिका](paywall-integration.hi.md#5-json-और-वैकल्पिक-configuration-की-तालिकाएँ) के अनुसार खुलने वाले points override करने के लिए खाली न रहने वाला `placements` जोड़ सकते हैं।

AndroidX lifecycle इस्तेमाल करने वाली पहली Activity के `onCreate` में (SDK splash सहित), Application द्वारा PayKit और source install कर लेने के बाद, sync शुरू करें:

```kotlin
import androidx.lifecycle.lifecycleScope
import io.paykit.PayKit
import kotlinx.coroutines.launch

// Trong Activity.onCreate, sau super.onCreate:
lifecycleScope.launch { PayKit.sync() }
```

Paywall को [PayKit गाइड](paywall-integration.hi.md) के अनुसार खोलें; हर click पर दोबारा sync न करें। Remote तभी इस्तेमाल होता है जब sync पूरा हो चुका हो; उससे पहले, या fetch fail होने पर, paywall मौजूदा local/cache से खुलता है। यह splash/OB gate के साथ भी चलता है: splash ads खुद PayKit sync नहीं करते और app gate के बाद दूसरा paywall launch नहीं करती।

## 5. Configuration तालिका

| Configuration | Default / सिर्फ ज़रूरत पर बदलें |
| --- | --- |
| `FirebaseSink(collectionFollowsConsent)` | `true`: consent तय हो जाने पर, analytics granted हो तो collection चालू, वरना बंद। `false`: app खुद collection संभालती है; sink फिर भी Consent Mode update करता है। जब तक दोनों axes `UNKNOWN` हैं, आपकी Firebase settings जैसी हैं वैसी रहती हैं। |
| Consent mapping | Analytics → `ANALYTICS_STORAGE`; ads → `AD_STORAGE`, `AD_USER_DATA`, `AD_PERSONALIZATION`. एक axis तय हो जाने के बाद, `UNKNOWN` रह गया axis denied भेजा जाता है। |
| `FirebaseSink.setDefaultEventParameters(...)` | कोई extra defaults नहीं। जब Firebase खुद जो events इकट्ठा करता है उन पर भी params चाहिए तब इसे इस्तेमाल करें; `Tracker.setDefaults` सिर्फ Tracker से जाने वाले events पर लागू होता है। |
| `FirebaseAdConfigSource(key, aliases)` | `ad_remote_config`, फिर Console में `ad_remote_config` न हो तो `ads_remote_config`; Console कोई और नाम इस्तेमाल करे तो `key` दें। |
| `FirebaseConfigSource(key)` | `paywall_config`; आपका Console अलग key इस्तेमाल करे तो इसे बदलें। |
| Ads/PayKit fetch | Source install करने से fetch नहीं होता; `AdConfig.install` Firebase द्वारा आखिरी बार activate की गई `ad_remote_config` value (या SDK की persisted आखिरी valid copy) background में लागू करता है। दोनों sources चल रहा fetch साझा करते हैं; `AdConfig.refresh()` हमेशा नया fetch माँगता है, जबकि PayKit सफल परिणाम process भर रखता है। Fail होने पर retry की अनुमति है; हर fetch फिर भी Firebase के minimum fetch interval के अधीन (12 घंटे, जब तक आपकी app `minimumFetchIntervalInSeconds` set न करे)। |
| Blank / Firebase in-app defaults | दोनों sources in-app defaults अनदेखा करते हैं; kit के local JSON की जगह `setDefaultsAsync` इस्तेमाल न करें। Blank `paywall_config` अनदेखा होता है; blank `ad_remote_config` remote ad document clear करता है, इसलिए ads आपकी `ad_config.json` पर लौटते हैं; blank settings document अपना आखिरी valid snapshot रखता है। |
| Offline / invalid JSON | Kit अपनी मौजूदा config रखता है; PayKit का remote cache bundled fallback से प्राथमिकता पाता है। अलग fallback code की ज़रूरत नहीं। |
| Debug ads / paywall | Debug `ad_config_debug.json` की test IDs request करता है, लेकिन `ad_remote_config` का बाकी हर field और दोनों settings documents फिर भी लागू होते हैं। PayKit में debug के लिए अलग IDs नहीं हैं। उपयुक्त test project/conditions इस्तेमाल करें। |

सिर्फ `AdConfig.refresh()`/`PayKit.sync()` बुलाएँ; अलग से `fetchAndActivate` की ज़रूरत नहीं। `ob_*` remote flags का अपना fetch flow है, जिसे OnboardKit संभालता है।

## 6. जाँच और ज़रूरी files

- [ ] Build, Google Services resolve करता है और application ID, Firebase JSON से मेल खाता है।
- [ ] `Tracker.sinkIds()` में `firebase` है; app और SDK events चुनी गई consent के अनुसार एक बार दिखते हैं।
- [ ] Remote publish हो चुका है; online, offline और invalid JSON को local fallback के साथ test करें।
- [ ] Debug ads `ad_config_debug.json` की test IDs इस्तेमाल करते हैं; remote पूरा होने से पहले भी paywall local/cache से खुलता है।

Firebase DebugView देखने के लिए `adb shell setprop debug.firebase.analytics.app <applicationId>` चलाएँ और app खोलें; बंद करने के लिए `adb shell setprop debug.firebase.analytics.app .none.`। [Firebase DebugView](https://firebase.google.com/docs/analytics/debugview)।

| File | क्या करें |
| --- | --- |
| मौजूदा root/app Gradle, `gradle.properties` | Plugin अगर न हो तो वह, और dependency। |
| `app/google-services.json` या उससे मेल खाता source set | उसी app के लिए Firebase द्वारा जारी की गई file। |
| मौजूदा Application / manifest | Tracker sink, जो remote sources इस्तेमाल करते हैं वे, अपनी app के अनुसार शुरुआती consent। |
| Ads/PayKit का local JSON | संबंधित guide का fallback रखें; सिर्फ remote fetch करने के लिए copy न बनाएँ। |
