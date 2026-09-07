**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# Trackkit

SDK और ऐप के events को `Tracker` से Firebase या अपने analytics backend तक भेजें।

[Build setup और module चयन](../README.hi.md) · minSdk 24+ · compileSdk 36+ · JDK 17

## 1. डेटा का destination जोड़ें

Firebase Analytics के लिए `suite-firebase` जोड़ें; यह Trackkit पहले से उपलब्ध कराता है। ऐप की `google-services.json` और Google Services plugin के साथ [Firebase setup](../suite-firebase/README.md) पूरा करें।

```groovy
// app/build.gradle
def sdkVersion = '5.1.2'
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

अपने backend के लिए `com.github.truongvimit.adlogic-partner-sdk:trackkit:$sdkVersion` declare करें और `TrackSink` implement करें। Ads, onboarding, billing और paywall पहले से Trackkit उपलब्ध कराते हैं, इसलिए अलग Trackkit dependency नहीं चाहिए।

## 2. एक बार initialize करें

इसे मौजूदा `Application.onCreate()` में, दूसरे kits से events आने से पहले जोड़ें। नीचे का उदाहरण केवल analytics वाले ऐप के लिए है; ads होने पर अपनी मौजूदा Application base class रखें। `BuildConfig` आपके ऐप की class है।

```kotlin
import android.app.Application
import io.suite.firebase.FirebaseSink
import io.trackkit.Tracker
import io.trackkit.TrackerConfig

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Tracker.install(this, TrackerConfig(
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            strictValidation = BuildConfig.DEBUG,
        ))
        Tracker.addSink(FirebaseSink())
    }
}
```

Manifest के `<application>` में `android:name` को `App` पर सेट करें। Application में Tracker पहले से install हो तो केवल missing sink जोड़ें। हर sink ID एक बार register करें।

## 3. ऐप के events भेजें

```kotlin
Tracker.track("app_document_open", mapOf("file_type" to "pdf"))
Tracker.screen("document_reader")
```

Event के नाम स्थिर रखें और बदलने वाली जानकारी parameters में दें; file names या user data को event नाम में न डालें। नाम अक्षर से शुरू हो, केवल अक्षर, अंक और underscore हों, और अधिकतम 40 characters हों।

Ads, onboarding, billing और PayKit अपने SDK events खुद भेजते हैं। ऐप callbacks से उनकी दूसरी copy न भेजें। नाम और parameters [TrackkitEvents](src/main/java/io/trackkit/TrackkitEvents.kt) में हैं।

## Consent जोड़ें

Ads module का `ConsentCenter` इस्तेमाल करने पर consent Tracker तक अपने आप पहुँचता है। वर्तमान mapping `analytics = true` और `ads = personalized` है; यह ad request की अनुमति नहीं है। Request की अनुमति `ConsentCenter.canRequestAds()` से पढ़ें।

अपना consent flow हो और `ConsentCenter` इस्तेमाल न होता हो, तो वास्तविक परिणाम भेजें:

```kotlin
fun onConsentResolved(analyticsAllowed: Boolean, adsPersonalizationAllowed: Boolean) {
    Tracker.setConsent(analytics = analyticsAllowed, ads = adsPersonalizationAllowed)
}
```

दो consent integrations को एक-दूसरे के परिणाम overwrite न करने दें। `ConsentCenter` का अगला update सीधे सेट किए गए Tracker consent को बदल देता है।

`TrackerConfig.consentPolicy` का default `SEND_ALWAYS` है। Consent resolve होने तक events रोकने हों तो Tracker install करने से पहले `QUEUE_UNTIL_RESOLVED` configure करें। [TrackerConfig और ConsentPolicy](src/main/java/io/trackkit/TrackkitApi.kt) तथा [Firebase consent विकल्प](../suite-firebase/README.md) देखें।

## Integration जाँचें

Logcat खोलकर `Trackkit` filter करें। Debug builds में events देखने के लिए console sink जोड़ें:

```kotlin
import io.trackkit.sink.ConsoleSink

// Application.onCreate(), after Tracker.install(...)
if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
```

| समस्या | क्या जाँचें |
| --- | --- |
| Events नहीं आते | Events भेजने से पहले `Tracker.install` और sink registration की पुष्टि करें; `Tracker.sinkIds()` देखें। |
| Events रुके या drop हुए | `Tracker.currentConsent` और चुनी हुई `consentPolicy` देखें। |
| SDK events दो बार आते हैं | Ads/onboarding callbacks से manual copies हटाएँ; हर destination एक बार register करें। |
| Validation exception | `strictValidation = BuildConfig.DEBUG` रखें, release में `true` नहीं। |

## 5.0.0 से अपग्रेड

Initialization नहीं बदला है। Native bind अब `fo_ad_bound` है, vendor-confirmed `ad_show` से अलग। जिन reports में onboarding bind callbacks को वास्तविक impressions माना गया था, उन्हें अपडेट करें।

## अपना backend और अन्य विकल्प

Custom destination के लिए `TrackSink.id` और `onEvent` implement करें; `Tracker.screen` इस्तेमाल करने पर `onScreen` भी जोड़ें। Sink callbacks caller के thread पर चलते हैं: network work queue में डालें, thread block न करें। Default parameters, user properties और revenue APIs के लिए [TrackSink](src/main/java/io/trackkit/TrackkitApi.kt), [ConsoleSink](src/main/java/io/trackkit/sink/ConsoleSink.kt) और [Tracker](src/main/java/io/trackkit/Tracker.kt) देखें।

## License

MIT — [LICENSE](../LICENSE) देखें।
