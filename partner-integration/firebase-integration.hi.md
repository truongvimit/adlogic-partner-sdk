# Firebase — Analytics और Remote Config

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

## 3. Remote ads JSON

पहले [Ads + OnboardKit](ads-onboarding-integration.hi.md) पूरा करें, दोनों local JSON files और asset initialization सहित। `ads` dependency उसी guide के अनुसार जोड़ें। अपनी Application में, local ad config load होने के बाद, एक बार बुलाएँ:

```kotlin
import com.ads.module.config.AdConfig
import io.suite.firebase.FirebaseAdConfigSource

// Application.onCreate में, ऊपर दिए क्रम में:
AdConfig.install(FirebaseAdConfigSource())
```

Firebase Console → Remote Config पर **String** parameter `ad_remote_config` बनाएँ, [ad_config.json](examples/ads-onboarding/ad_config.json) की संरचना paste करें, अपने ad IDs डालें, फिर **Publish** करें। अलग schema या remote file की ज़रूरत नहीं।

`ObSplashActivity` पहले ही `AdConfig.refresh()` बुलाता है; OB guide के अनुसार `OnboardKitSetup.configure` को `onRemoteFetched` के अंदर रखें, ताकि setup fetch की गई config इस्तेमाल करे। SDK splash न इस्तेमाल करने वाली app `AdConfig.refresh()` को coroutine से उस बिंदु से पहले बुलाती है जहाँ उसे remote config चाहिए।

सफलतापूर्वक load हुआ debug asset default रूप से pin रहता है, इसलिए remote debug ads को नहीं बदलता। Remote ads जाँचने के लिए [ads गाइड](ads-onboarding-integration.hi.md) में बताई गई test configuration इस्तेमाल करें; test environments में test ad IDs ही रखें। OnboardKit के `ob_*` parameters Console पर अपनी अलग keys हैं; उन्हें ads JSON में न डालें।

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
| `FirebaseAdConfigSource(key)` | `ad_remote_config`; आपका Console अलग key इस्तेमाल करे तो इसे बदलें। |
| `FirebaseConfigSource(key)` | `paywall_config`; आपका Console अलग key इस्तेमाल करे तो इसे बदलें। |
| Ads/PayKit fetch | Source install करने से fetch नहीं होता। दोनों sources एक ही fetch साझा करते हैं और सफल परिणाम process भर रखते हैं; fail होने पर retry की अनुमति है, फिर भी Firebase के minimum fetch interval के अधीन। |
| Blank / Firebase in-app defaults | दोनों sources इन्हें अनदेखा करते हैं; kit के local JSON की जगह `setDefaultsAsync` इस्तेमाल न करें। |
| Offline / invalid JSON | Kit अपनी मौजूदा config रखता है; PayKit का remote cache bundled fallback से प्राथमिकता पाता है। अलग fallback code की ज़रूरत नहीं। |
| Debug ads / paywall | Ads default रूप से debug asset pin करते हैं; PayKit में ऐसी pinning नहीं है। उपयुक्त test Firebase project/conditions चुनें। |

सिर्फ `AdConfig.refresh()`/`PayKit.sync()` बुलाएँ; अलग से `fetchAndActivate` की ज़रूरत नहीं। `ob_*` remote flags का अपना fetch flow है, जिसे OnboardKit संभालता है।

## 6. जाँच और ज़रूरी files

- [ ] Build, Google Services resolve करता है और application ID, Firebase JSON से मेल खाता है।
- [ ] `Tracker.sinkIds()` में `firebase` है; app और SDK events चुनी गई consent के अनुसार एक बार दिखते हैं।
- [ ] Remote publish हो चुका है; online, offline और invalid JSON को local fallback के साथ test करें।
- [ ] Debug ads अब भी pin किया गया asset इस्तेमाल करते हैं; remote पूरा होने से पहले भी paywall local/cache से खुलता है।

Firebase DebugView देखने के लिए `adb shell setprop debug.firebase.analytics.app <applicationId>` चलाएँ और app खोलें; बंद करने के लिए `adb shell setprop debug.firebase.analytics.app .none.`। [Firebase DebugView](https://firebase.google.com/docs/analytics/debugview)।

| File | क्या करें |
| --- | --- |
| मौजूदा root/app Gradle, `gradle.properties` | Plugin अगर न हो तो वह, और dependency। |
| `app/google-services.json` या उससे मेल खाता source set | उसी app के लिए Firebase द्वारा जारी की गई file। |
| मौजूदा Application / manifest | Tracker sink, जो remote sources इस्तेमाल करते हैं वे, अपनी app के अनुसार शुरुआती consent। |
| Ads/PayKit का local JSON | संबंधित guide का fallback रखें; सिर्फ remote fetch करने के लिए copy न बनाएँ। |
