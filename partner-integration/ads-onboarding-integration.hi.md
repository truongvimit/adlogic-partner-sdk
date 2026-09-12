# Ads + OnboardKit integration

[← गाइड चुनें](README.hi.md)

नमूना flow: **Splash → भाषा (LFO) → सामग्री 1 → सामग्री 2 → fullscreen native → सामग्री 3 → onboarding के अंत का interstitial → MainActivity**। SDK consent, notifications, ads और navigation संभालता है; ad तभी दिखता है जब वह eligible हो और fill मिले।

कदम 1–6 करें और **package, app की जानकारी, सामग्री/images और destination स्क्रीन** बदलें। Code SDK के defaults रखता है; JSON example का debug configuration रखता है। Adjust चालू करने के लिए app token भरें; Firebase, app-open और purchases [वैकल्पिक tables](#7-सिर्फ-वही-configure-करें-जो-app-को-चाहिए) में हैं।

## 1. Dependencies जोड़ें

JDK 17, `minSdk 24+` और `compileSdk 36+` चाहिए; AGP/Kotlin [versions.gradle](../versions.gradle) और [Gradle wrapper](../gradle/wrapper/gradle-wrapper.properties) के अनुसार। नीचे दिया Groovy अपने मौजूदा blocks में मिलाएँ।

`settings.gradle` में छूटी repositories जोड़ें:

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
        maven { url 'https://artifact.bytedance.com/repository/pangle/' }
        maven { url 'https://android-sdk.is.com/' }
        maven { url 'https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea' }
    }
}
```

अपने app project की root `gradle.properties` में `<published-tag>` को [प्रकाशित tag](https://github.com/truongvimit/adlogic-partner-sdk/tags) से बदलें:

```properties
adlogicSdkVersion=<published-tag>
```

हर module यही property पढ़ता है।

`app/build.gradle` में:

```groovy
android {
    compileSdk 36
    defaultConfig { minSdk 24 }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = '17' }
    buildFeatures { buildConfig = true }
    // Translations को app bundle में रखें ताकि language picker offline काम करे.
    bundle { language { enableSplit = false } }
}

def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
}
```

अपनी app का `targetSdk` रखें (repo 36 इस्तेमाल करता है), और मौजूदा AndroidX/AppCompat setup तथा `MainActivity` इस्तेमाल करें। Gradle sync करें, फिर आगे बढ़ें। SDK में GMA/UMP, mediation, Trackkit और `minifyEnabled` release के लिए consumer rules पहले से हैं: ये dependencies, `MobileAds.initialize()` या example की `app/proguard-rules.pro` दोबारा न जोड़ें।

## 2. IDs और दो JSON files जोड़ें

### `app/src/main/res/values/id_ads.xml`

```xml
<resources>
    <string name="admob_app_id" translatable="false">ca-app-pub-3940256099942544~3347511713</string>
    <string name="facebook_app_id" translatable="false">YOUR_META_APP_ID</string>
    <string name="facebook_client_token" translatable="false">YOUR_META_CLIENT_TOKEN</string>
    <!-- Adjust चालू करने के लिए app token भरें; इस्तेमाल न हो तो खाली छोड़ें. -->
    <string name="adjust_token" translatable="false"></string>
    <!-- Adjust से मिला event token; सिर्फ ad-revenue event के लिए चाहिए. -->
    <string name="event_token" translatable="false"></string>
    <!-- Purchase event token; सिर्फ तब चाहिए जब app में IAP हो. -->
    <string name="adjust_event_token_purchase" translatable="false"></string>
</resources>
```

Test AdMob App ID में **`~`** होता है। चलाने से पहले `YOUR_META_*` को अपनी team के दिए credentials से बदलें: `ERainAd.init()` हमेशा Facebook SDK initialize करता है, Adjust बंद होने पर भी। तीनों Adjust fields रखें; इस्तेमाल न होने तक खाली छोड़ें, और इस्तेमाल करते समय [Adjust तालिका](#adjust-token-और-verification) के अनुसार असली tokens भरें।

### `app/src/main/assets/`

दोनों files को ठीक इन्हीं नामों से `assets` में copy करें (folder न हो तो बनाएँ):

- **[ad_config.json](examples/ads-onboarding/ad_config.json):** release का configuration; अभी हर ID test ID है।
- **[ad_config_debug.json](examples/ads-onboarding/ad_config_debug.json):** debug configuration; test IDs रखें।

Ad unit IDs में **`/`** होता है। हर file में [debug example](../app/src/main/assets/ad_config_debug.json) की तरह **45 entries** हैं, जो style, UA, app-resume delay और waterfalls को कवर करती हैं; interstitials test ID `1033173712` इस्तेमाल करते हैं और native high tier native video test unit। नीचे 10 OB slots दिए हैं; बाकी keys आपकी app screens के लिए हैं और खुद से कोई display position नहीं बनातीं। नमूने की values parser के defaults से अलग हो सकती हैं; [JSON field तालिका](#नमूना-json-के-fields) देखें।

| JSON key | जगह | कदम 4 में `AdsConfig` में mapping |
| --- | --- | --- |
| `banner_splash` | Splash banner | `splashBanner` |
| `inter_splash` | Splash छोड़ते समय का interstitial | `splashInterstitial` |
| `native_lang` | भाषा का पहला native | `languageNative` |
| `native_lang_alt` | पहली बार भाषा चुनने के बाद का replacement native | `languageDupNative` |
| `native_popup_lang` | भाषा confirmation popup का native | `languageConfirmNative` |
| `native_ob1` | सामग्री 1 — `StepId.OB1` | `stepNatives[StepId.OB1]` |
| `native_ob2` | सामग्री 2 — `StepId.OB2` | `stepNatives[StepId.OB2]` |
| `native_fs` | सिर्फ ad वाला page — `StepId.OB3` | `fullScreenStepNative` |
| `native_ob3` | सामग्री 3 — **`StepId.OB4`** | `stepNatives[StepId.OB4]` |
| `inter_after_ob3` | पूरे onboarding के बाद, destination स्क्रीन से पहले | `afterOnboardingInterstitial` |

यह mapping बनाए रखें: `native_ob3` **OB4** पर सामग्री 3 है; **OB3** fullscreen page है। `inter_after_ob3` पूरे onboarding के बाद दिखता है।

SDK debuggable के अनुसार file चुनता है। Debug build में JSON गायब या गलत हो तो असली file इस्तेमाल होती है; live IDs की जगह test IDs नहीं आते। सफलतापूर्वक load हुआ debug asset default रूप से remote overrides रोकता है।

नमूने के IDs [Google demo ad units](https://developers.google.com/admob/android/test-ads#demo_ad_units) और [AdMob App ID](https://developers.google.com/admob/android/quick-start) से हैं। Fullscreen page **native ID** इस्तेमाल करता है। नमूना हर format के लिए एक ही test ID साझा करता है; production में placement के अनुसार configuration, style और reporting अलग करने के लिए अलग IDs चाहिए।

## 3. Onboarding की सामग्री तैयार करें

अपना मौजूदा `app_name` और launcher icon इस्तेमाल करें। ये छह strings `app/src/main/res/values/strings.xml` में जोड़ें और content अपने product के अनुसार बदलें:

```xml
<resources>
    <string name="onboarding_title_1">स्वागत है</string>
    <string name="onboarding_des_1">App का मुख्य फ़ायदा बताएँ.</string>
    <string name="onboarding_title_2">शुरू करना आसान</string>
    <string name="onboarding_des_2">User को जो क्रिया जाननी है वह दिखाएँ.</string>
    <string name="onboarding_title_3">शुरू करने के लिए तैयार</string>
    <string name="onboarding_des_3">User को शुरू करने का न्योता दें.</string>
</resources>
```

`values-<language>/strings.xml` में अनुवाद जोड़ें, [ob_strings.xml](../onboardkitorigin/src/main/res/values/ob_strings.xml) की `ob_*` keys समेत, क्योंकि SDK सिर्फ अंग्रेजी देता है। कदम 4 में तीनों `imageRes` values अपनी app की images से बदलें, या आजमाने के लिए example से images [1](../app/src/main/res/drawable-nodpi/img_onboard_sample_1.png), [2](../app/src/main/res/drawable-nodpi/img_onboard_sample_2.png), [3](../app/src/main/res/drawable-nodpi/img_onboard_sample_4.png) को `app/src/main/res/drawable-nodpi/` में copy करें।

LFO, popup, OB और native ads के layouts/Activities SDK पहले से देता है।

<details>
<summary>वैकल्पिक: अपने layouts इस्तेमाल करें</summary>

सिर्फ `SplashConfig.layoutRes` और `ContentStepDefinition.layoutRes` custom layouts लेते हैं; बाकी layout fields `getOrThrow()` में fail होते हैं। [content layout](../onboardkitorigin/src/main/res/layout/ob_fragment_content_step.xml) copy करें और उसका root, IDs तथा view types बनाए रखें; contract टूटे तो `OB_FLOW` में log होता है और SDK default layout पर लौटता है। [splash](../onboardkitorigin/src/main/res/layout/ob_activity_splash.xml) में गायब IDs नजरअंदाज होते हैं, पर जो IDs रखें उनका type सही होना चाहिए; banner के लिए `ob_splash_ad_container` चाहिए जिसमें `<include layout="@layout/layout_banner_control" />` हो।

</details>

## 4. Placements घोषित करें और OnboardKit configure करें

### `AppAdPlacement.kt` — आपकी app का placement catalog

[AppAdPlacement.kt](examples/ads-onboarding/AppAdPlacement.kt) को अपने app package में copy करें, उदाहरण के लिए `app/src/main/java/com/example/app/`। File में **35 base keys** हैं: 10 OB slots और आपकी app के slots। `_high`, `_high1`… floors SDK खुद ढूँढता है; floors के लिए constants नहीं चाहिए।

**किसी ad position की एकमात्र पहचान placement key है।** Ad unit IDs positions में फर्क नहीं कर सकते: नमूना JSON 45 placements घोषित करता है पर उनमें सिर्फ **8 ad unit IDs** हैं — एक native test ID 25 placements के काम आता है — और production payloads भी आम तौर पर एक ही unit कई screens पर दोबारा इस्तेमाल करते हैं। SDK हर चीज placement से key करता है: interstitial cache, frequency clock, AutoBuffer group, native preload, और हर `ad_request` / `ad_impression` / `ad_skipped` जिनसे आपका dashboard slice करता है। इसलिए key ठीक एक ही जगह लिखी होनी चाहिए।

गलत टाइप हुई raw string भी fail नहीं होती: `AdRemoteConfig.unit()` warning log करता है और बंद placeholder लौटाता है, और slot चुपचाप कभी fill नहीं होता। Constant उसे compile error बना देता है।

- `AppAdPlacement.NATIVE_HOME` यानी **key** `native_home`; load और show करते समय इसी का इस्तेमाल करें।
- JSON में **ad unit IDs और config** रहते हैं; constants में सिर्फ keys।
- `io.onboardkit.ads.AdPlacement` SDK के अंदर तय है; आपकी app अपने slots `AppAdPlacement` में जोड़ती है।

नए slot के लिए एक constant, दोनों JSON files में वही key, और app स्क्रीन पर load/show code चाहिए। ads के लिए कोई और adapter file नहीं चाहिए: entry points constant लेते हैं और config खुद पढ़ते हैं।

### `OnboardKitSetup.kt` — OB keys को SDK से जोड़ें

[OnboardKitSetup.kt](examples/ads-onboarding/OnboardKitSetup.kt) उसी package में copy करें। हर Kotlin file में `com.example.app` बदलें; `R`, `BuildConfig` और `MainActivity` दूसरे package में हों तो उन्हें import करें। नमूना तीनों content pages और fullscreen page, चालू ad floors तथा JSON के templates पहले से जोड़ चुका है। कदम 5 `OnboardingSdk.install` के बाद `configure()` बुलाता है।

नमूना `contentStepNative` सेट नहीं करता: `native_ob2` के सभी floors बंद होने पर page 2 page 1 का ad उधार नहीं लेता। `languageDupNative = null` अपवाद है और पहले native पर fallback करता है; replacement बंद करने के लिए `secondNativeOnSelectEnabled = false` रखें।

Templates `positionCTA` के अनुसार चलते हैं: `languageTemplate` `native_lang` पढ़ता है, और `contentStepTemplate` हर content page के लिए `native_ob1`। Fullscreen page और popup तय layouts इस्तेमाल करते हैं। Provider ad config से CTA color, CTA height और component visibility लागू करता है।

## 5. अपनी Application में initialize करें

[PartnerApp.kt](examples/ads-onboarding/PartnerApp.kt) copy करें, या उसका `onCreate`, listener और दो constants अपनी मौजूदा Application में मिलाएँ, अपनी base class/Hilt रखते हुए। `MainActivity` को अपनी destination स्क्रीन से बदलें।

नमूना सब कुछ पहले से जोड़ चुका है:

| हिस्सा | आपकी app क्या बदलती है |
| --- | --- |
| Tracker, JSON, ERainAd, ERainTuning, OnboardKit | नमूने का क्रम बनाए रखें; analytics चाहिए तभी sink जोड़ें। |
| Ads/Adjust environment | नमूना `BuildConfig.DEBUG` से खुद debug/release चुनता है। |
| Adjust | `id_ads.xml` में tokens भरें; इस्तेमाल न होने तक खाली छोड़ें। |
| पूरा होने का listener | Destination स्क्रीन, और आपकी app के पास अपनी व्यवस्था हो तो भाषा कैसे सहेजी जाए। |

क्रम: **Tracker → JSON → ERainAd → ERainTuning → install OnboardKit → configure**। Meta token manifest से पढ़ा जाता है। Firebase या अपने backend पर events भेजने के लिए Tracker को sink चाहिए।

`ERainTuning.install()` [app screens के interstitials](#app-की-अपनी-screens-में-interstitial-placement-constant-के-साथ) के लिए `onComplete` का समय तय करता है और ad click के बाद app-open छोड़ देता है; OB flow नहीं बदलता।

Listener `Completed`/`Skipped`/`Aborted` को आपकी मुख्य स्क्रीन पर भेजता है; जरूरत हो तो `Aborted` बदलें। `NEW_TASK` रखें और `CLEAR_TASK` न जोड़ें, ताकि मुख्य स्क्रीन लौटे हुए उपयोगकर्ता के splash interstitial या onboarding के अंत वाले interstitial के नीचे खुल सके। SDK पूरा होना खुद सहेजता है और खुद navigate करता है; कोई timer न जोड़ें।

**locale आपकी app की screens को खुद लागू करना होगा।** नमूना `Completed.selectedLanguage` (`en-US`, `es`) सहेजता है। आपकी app में अभी locale की व्यवस्था न हो तो यह अपनी base Activity/MainActivity में जोड़ें और `android.content.Context`, `android.content.res.Configuration` तथा `java.util.Locale` import करें। `PartnerApp` को अपनी असली Application के नाम से बदलें:

```kotlin
override fun attachBaseContext(newBase: Context) {
    val code = newBase.getSharedPreferences(PartnerApp.PREFS, Context.MODE_PRIVATE)
        .getString(PartnerApp.LANGUAGE, null)
        ?: return super.attachBaseContext(newBase)
    val config = Configuration(newBase.resources.configuration)
        .apply { setLocale(Locale.forLanguageTag(code)) }
    super.attachBaseContext(newBase.createConfigurationContext(config))
}
```

सहेजी गई पसंद `OnboardingSdk.selectedLanguage()` (suspend) से पढ़ें। SDK न आपकी app का अनुवाद करता है, न उसकी preferences अपडेट करता है।

### Adjust: token और verification

नमूना **`com.ads.module.config.AdjustConfig`** इस्तेमाल करता है। Adjust, उसका lifecycle/attribution और revenue SDK संभालता है; सिर्फ resources भरें — `Adjust.initSdk()` या Adjust के लिए Tracker sink न जोड़ें।

| `id_ads.xml` का resource | कहाँ assign होता है | क्या भरें |
| --- | --- | --- |
| `adjust_token` | `AdjustConfig(true, token)` | App token; value होने पर Adjust चालू, खाली होने पर बंद। |
| `facebook_app_id` | `fbAppId` | manifest वाला वही Meta App ID, Adjust के जरिए Meta integration के लिए। |
| `event_token` | `eventAdImpression` | Paid impressions के लिए 6 अक्षरों का event token; जरूरत हो तभी भरें। |
| `adjust_event_token_purchase` | `eventNamePurchase` | 6 अक्षरों का purchase event **token**, event का नाम नहीं; IAP आने तक खाली छोड़ें। |

SDK Adjust ad-revenue API पहले से बुलाता है। `event_token` उसी revenue को Meta/TikTok… के लिए event के रूप में अतिरिक्त भेजता है; reporting में दोनों स्रोत साथ न जोड़ें। खाली token छोड़ दिया जाता है, और app callback से revenue दोबारा नहीं भेजा जाता।

Debug Adjust **sandbox** इस्तेमाल करता है, release **production**। `ERainAdjust` log में `Adjust initialised (sandbox)` या token error देखें, फिर Adjust में sessions/events मिलाएँ। Purchases टेस्ट करने के लिए billing और एक test transaction चाहिए, सिर्फ event token नहीं।

## 6. Splash को launcher के रूप में register करें

उसी package में `SplashActivity.kt` बनाएँ:

```kotlin
package com.example.app

import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity()
```

इसे `app/src/main/AndroidManifest.xml` में मिलाएँ, अपने मौजूदा class नामों/entries के अनुसार बदलते हुए। पुराना launcher splash पर ले जाएँ और सिर्फ एक launcher रखें:

```xml
<application android:name=".PartnerApp">
    <meta-data
        android:name="com.google.android.gms.ads.APPLICATION_ID"
        android:value="@string/admob_app_id" />
    <meta-data
        android:name="com.facebook.sdk.ApplicationId"
        android:value="@string/facebook_app_id" />
    <meta-data
        android:name="com.facebook.sdk.ClientToken"
        android:value="@string/facebook_client_token" />

    <activity android:name=".MainActivity" android:exported="false" />
    <activity
        android:name=".SplashActivity"
        android:exported="true"
        android:screenOrientation="portrait"
        android:configChanges="orientation|screenSize|keyboardHidden|uiMode|fontScale"
        android:theme="@style/ob_Theme_OnboardKit">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
</application>
```

`configChanges` बनाए रखें ताकि dark mode या font size बदलने पर splash दोबारा न बने। SDK अपनी Activities और network/`AD_ID`/`POST_NOTIFICATIONS` permissions पहले से घोषित करता है। **Merged Manifest** में Application, launcher, metadata और theme जाँचें; पुरानी `${app_id}` entry को नमूने के resource से बदलें।

Splash खुद UMP/notifications, ads और navigation संभालता है। lifecycle callbacks override करने, consent या `OnboardingSdk.start()` बुलाने, या OB के लिए खुद कुछ preload/show करने की जरूरत नहीं।

मानक flow चलाने के लिए कदम 1–6 काफी हैं। इसे [checklist](#8-आखिरी-जाँच) से जाँचें; नीचे के हिस्से तभी पढ़ें जब behavior बदलना हो या अपनी app screens में ads जोड़ने हों।

## 7. सिर्फ वही configure करें जो app को चाहिए

### Flow के defaults

कदम 4 के `onboardKitConfig { ... }` block में सिर्फ वही options जोड़ें जो बदलने हैं; `ERainAd`/`ConsentCenter` वहीं बुलाएँ जहाँ तालिका कहती है। `ob_*` keys Firebase Remote Config की हैं और **ad JSON में नहीं** हैं; Firebase न हो तो cache/default values लागू होती हैं।

| व्यवहार | Default | कब बदलें / कहाँ बदलें |
| --- | --- | --- |
| Splash स्क्रीन | SDK layout, ads लोड होना शुरू होने से कम से कम 3 सेकंड | `ob_splash_min_display_ms` (3000) 0 से बड़ा हो तो `SplashConfig.minDisplayTimeMs` को override करता है; remote `0` होने पर ही local value लागू होती है। Firebase के बिना हमेशा 3000 ms |
| Splash interstitial के बाद अगली स्क्रीन खोलना | Interstitial न्यूनतम display समय के बाद दिखता है। पहली बार का LFO: बंद होने का इंतज़ार। Launcher → app / पुराने उपयोगकर्ता का प्रश्न: ad के नीचे खोलें। Notification/widget/uninstall: बंद होने का इंतज़ार | `SplashActivity.nextScreenTiming()` override करें: `NextScreenTiming.AFTER_AD`/`UNDER_AD` (`io.onboardkit.ads`), या default रखने के लिए `super.nextScreenTiming()` |
| Splash ad का इंतज़ार | notification कदम के बाद और splash को focus मिलने पर अधिकतम 60 सेकंड | Remote `ob_splash_ad_budget_ms`; अपना timer न जोड़ें |
| Ads fetch और load करना | `AdLoadStrategy.ALTERNATE`: load से पहले remote का इंतज़ार | `SplashConfig.adLoadStrategy`, जब आपकी app fetch से पहले मौजूदा IDs इस्तेमाल करना स्वीकार करे |
| पहला LFO native preload करना | splash interstitial का waterfall पूरा होने या उसका budget खत्म होने के बाद | समानांतर loading चाहिए तो remote `ob_splash_lfo_parallel_preload_enabled = true` |
| नेटवर्क नहीं | उपयोगकर्ता से कनेक्ट करने को कहता है और आगे नहीं बढ़ता | नीचे दिए UMP fallback से offline शुरू करने के लिए `SplashConfig.noInternetPromptEnabled = false`; अगली splash फिर से UMP माँगती है |
| Consent | 20 सेकंड का network timeout; form खुद उपयोगकर्ता का इंतज़ार करता है | अपनी Application में: `ConsentCenter.configure(ConsentOptions(timeoutMs = ...))` (`com.ads.module.consent`)। SDK का hook बनाए रखें; `SplashConfig.consentTimeoutMs` UMP timeout नहीं बदलता |
| QA के दौरान UMP form | Debuggable: हर device EEA गिना जाता है (`setForceTesting`), hashed ID नहीं चाहिए; release: असली भूगोल | असली भूगोल के अनुसार debug करने के लिए `ConsentOptions(debug = false)`। `configure` हर option बदल देता है, इसलिए timeout भी बदलना हो तो एक ही `ConsentOptions(timeoutMs = ..., debug = false)` दें |
| Notifications | Android 13+ / target 33+ पर consent के बाद माँगी जाती है; मना करने पर भी flow चलता है और दोबारा नहीं पूछा जाता | आपकी app notifications न भेजती हो या prompt खुद संभालती हो तो `SplashConfig.notificationPermissionEnabled = false` |
| भाषा | 21 भाषाएँ, 3 सेकंड बाद hand hint, चुनने से पहले confirmation छिपा | `LanguageConfig.languages`: जिन भाषाओं का अनुवाद किया है वही रखें; `tapHintEnabled` और `confirmVisibleBeforeSelect` के लिए उनके remote flags भी enabled चाहिए |
| LFO पर Back | कुछ न चुना हो: Back नजरअंदाज होता है। चुनने के बाद: Save दिखता है और भाषा स्क्रीन बनी रहती है | `LanguageConfig.saveButtonOnBackEnabled = false`: चुनने के बाद भी Back नजरअंदाज करें। SETTINGS में Back स्क्रीन बंद करता है |
| भाषा चुनने के बाद native बदलना | चालू; replacement ad bind हो पाने तक पहला native बना रहता है | बंद करने के लिए `LanguageConfig.secondNativeOnSelectEnabled = false` |
| भाषा popup | चौथे tap से आगे, item दोबारा चुनने पर भी; इसका native पहली बार popup खुलने पर request होता है | बंद करने के लिए `LanguageConfig.confirmDialogOnReselectEnabled = false`; SETTINGS में popup नहीं दिखता |
| Native template | SDK: LFO पर CTA नीचे, content पर CTA ऊपर; नमूना JSON कदम 4 की mapping से दोनों के लिए `BOTTOM` चुनता है | `native_lang` / `native_ob1` का `positionCTA` बदलें, या setup वाला fallback |
| System bars | Status/caption bars दिखते हैं, navigation bar छिपा | `SystemBarConfig(showStatusBar, showNavigationBar, showCaptionBar)` |
| OB पर native click और वापसी | step आगे बढ़ता है (`BehaviorConfig.adClickReturnCompletesStep = true`); OB/OB5 click पर replacement preload बंद रखते हैं | page पर बने रहने के लिए `adClickReturnCompletesStep = false`; provider में click preload दोबारा चालू न करें |
| LFO/popup या app स्क्रीन पर native click | ad click/open होते ही preload करता है; वापस आने पर तैयार ad bind करता है या चल रही request का इंतज़ार करता है | `NativeAdConfig.reloadOnAdClick = true` default है, समय-आधारित refresh से स्वतंत्र; [app screen के native का उदाहरण](#app-की-अपनी-screens-में-native-placement-constant-के-साथ) |
| flow अधूरा रहते दोबारा खोलना | Splash → LFO → OB फिर से चलता है; पूरा flow पूरा होने पर ही OB छूटता है | app की ओर से first-open flag या checkpoint नहीं चाहिए |
| Fullscreen native page | 1 सेकंड बाद X button, page चुने जाने के 3 सेकंड बाद auto-next; background में बीता समय भी गिना जाता है | `AdFullScreenStepDefinition` के fields; remote `ob_skip_button_delay_sec = -1` local delay बनाए रखता है |
| Onboarding के अंत का interstitial | pager में आते ही preload, पूरा होने पर fill के लिए अधिकतम 8 सेकंड इंतज़ार; अगली स्क्रीन ad के नीचे खुलती है। Notification/widget/uninstall: बंद होने का इंतज़ार | हमेशा बंद होने का इंतज़ार करने के लिए `AdsConfig.afterOnboardingInterstitialTiming = NextScreenTiming.AFTER_AD`; आपकी app खुद संभालती हो तो `afterOnboardingInterstitialEnabled = false`। इसे `InterstitialAutoBuffer` से बाहर रखें |
| OB navigation | Pager swipe locked; आखिरी page पर आगे swipe करने से वह फिर भी पूरा हो सकता है; Back एक page पीछे जाता है और पहले page पर app से बाहर निकलता है; portrait locked | `BehaviorConfig.lockPagerSwipe`, `swipeCompletesLastStep`, `backNavigatesBack` (`false`: Back हमेशा app से बाहर निकालता है), `lockPortrait`; landscape app को manifest भी बदलना होगा |
| Interstitial का अंतराल | `ERainAdConfig.intervalInterstitialAd = 0` (कोई सीमा नहीं); यह सिर्फ `InterstitialAutoBuffer` group पर लागू होता है, splash/OB या खुद load किए interstitials पर नहीं | init से पहले सेट करें, या `ERainAd.getInstance().setIntervalInterstitialAd(seconds)` इस्तेमाल करें |
| Interstitial click cap | बंद (`0`) | `ERainAd.getInstance().setMaxClickAdsPerDay(n)`: हर ad unit पर 24 घंटे में ज्यादा से ज्यादा `n` clicks, उसके बाद load/show रुक जाता है। जरूरत पर बुलाएँ, आम तौर पर remote fetch के बाद |
| OB5, प्रश्न, paywall, app-open | `ob_enable_step_ob5 = false`। OB5 चालू हो: इसका native लोड हो तो यह आखिरी interstitial के नीचे खुलता है, वरना छूट जाता है। `ob5Native` null हो तो `fullScreenStepNative` (`native_fs`) इस्तेमाल होता है। बाकी features जुड़े नहीं हैं | अपनी अलग ID देने के लिए `AdsConfig.ob5Native`; प्रश्न/paywall/app-open तभी जोड़ें जब जरूरत हो |

UMP की error या timeout [AdLogic fallback](../ads/src/main/java/com/ads/module/consent/ConsentCenter.kt) के जरिए process में **request की कोशिश** की अनुमति दे सकती है; यह न consent देता है, न fill की गारंटी। ads बंद करने वाला host फिर भी जीतता है; request की अनुमति timer या personalization से न मानें।

### नमूना JSON के fields

दोनों JSON files example के debug fields और values रखती हैं; सिर्फ interstitials को test ID पर सामान्य किया गया है।

| Field | नमूने में value | कैसे इस्तेमाल होता है / कहाँ लागू होता है |
| --- | --- | --- |
| `id` | सही format का test ad unit | Release से पहले असली file के IDs बदलें; placement keys न बदलें। |
| `isEnable` | Example जैसा: ज्यादातर `true`, welcome `false` | Placement चालू या बंद करता है। Base key master switch है: base key पर `false` पूरा waterfall बंद कर देता है। |
| `enable_ua_check` | `true` और `false` दोनों आते हैं | `true` सिर्फ paid/non-organic को अनुमति देता है; SDK इसे load और show पर लागू करता है, app-resume समेत। Adjust के attribution लौटाने तक install organic गिना जाता है, इसलिए Adjust के बिना जिस app slot को दिखना ही है उस पर `false` रखना होगा। OB provider JSON वाला UA gate लागू नहीं करता, **सिवाय `inter_after_ob3` के** — यह key JSON की भी key है, इसलिए SDK उस पर `isEnable` और `enable_ua_check` दोनों लागू करता है; `true` रखना organic installs के लिए onboarding के अंत का interstitial बंद कर देता है। |
| `reloadIntervalSeconds` | Banner: `30` | सिर्फ parse होता है; helpers इसे नजरअंदाज करते हैं और यह कोई refresh नहीं बदलता, splash समेत। Refresh के लिए [App स्क्रीन का banner](#अतिरिक्त-integrations) देखें। |
| `colorCTA` | `"default"` | Template का रंग बनाए रखता है; custom native चाहिए तो रंग सेट करें। |
| `heightCTA` | सामान्य natives के लिए `45`, popup के लिए `36` | dp में CTA height; field न हो तो SDK `40` इस्तेमाल करता है और लागू करते समय value को 36–52 के बीच सीमित करता है। |
| `positionCTA` | `"BOTTOM"` या `null` | कदम 4 की mapping base key से LFO/content का template चुनती है। `null` SDK fallback इस्तेमाल करता है; fullscreen page और popup अपने layouts रखते हैं। |
| `components` | `["icon_headline", "body", "media", "cta"]` | गायब block छिपा रहता है; खाली array सब दिखाता है। OB सिर्फ visibility बदलता है; [app स्क्रीन का native](#app-की-अपनी-screens-में-native-placement-constant-के-साथ) `positionCTA: null` होने पर क्रम भी इस्तेमाल करता है। |
| `app_resume_load_delay_ms` | `open_resume`: `2000` | app के background जाने के बाद app-open ad लोड करने से पहले कितना इंतज़ार; यह तभी असर करता है जब app-resume चालू हो। |

Waterfall `_high`, `_high1`… और फिर base key पढ़ता है; एक ही entry में कई floors घोषित करने के लिए `ids` भी इस्तेमाल कर सकते हैं। दोहराए गए IDs हटा दिए जाते हैं; किसी एक floor या style की जाँच करनी हो तो अलग IDs इस्तेमाल करें।

### App की अपनी screens में native, placement constant के साथ

<details>
<summary>App screens के लिए native और preload का उदाहरण खोलें</summary>

इसे consent के बाद अपनी app स्क्रीन पर बुलाएँ, जब `AppCompatActivity` resumed हो; `container` एक `FrameLayout` है। OB अपने natives खुद संभालता है।

**Adjust के बिना:** नमूना slot दिख सके इसके लिए **दोनों JSON files** में `native_home` का `enable_ua_check` `false` करें; QA के दौरान test IDs रखें।

```kotlin
import com.ads.module.helper.adnative.NativeAdHelper

NativeAdHelper.forPlacement(this, this, AppAdPlacement.NATIVE_HOME, container)
```

placement का waterfall, `isEnable`, `enable_ua_check` और CTA style SDK खुद तय करता है। Template बदलने के लिए `layoutRes` दें; default `com.ads.module.R.layout.custom_native_admob_medium` है (media नहीं — media चाहिए तो `custom_native_admob_free_size` इस्तेमाल करें)। Custom layout में `NativeAdView` root, `ad_container`, `block_icon_headline`, asset IDs और Ad badge बने रहने चाहिए।

हर slot/view के लिए एक helper रखें और दोबारा दिखाने के लिए `show()` बुलाएँ। Fragment अपनी Activity के साथ `viewLifecycleOwner` देता है। `reloadOnAdClick` default रूप से चालू है; इसे तभी बंद करें जब click-return पर आपकी app खुद कहीं और navigate करती हो।

Main के लिए preload: `SplashActivity.onRemoteFetched()` में `NativeAdManager.preload(applicationContext, AppAdPlacement.NATIVE_HOME, NativeAdConfig.forPlacement(AppAdPlacement.NATIVE_HOME, layoutRes))`। उसी placement का helper दिखाते समय वह ad उठा लेता है; 60 मिनट से पुराना ad दोबारा लोड होता है। [Native preload](../ads/README.md#native-preload-repeated-show-and-refresh) देखें।

</details>

### App की अपनी screens में interstitial, placement constant के साथ

<details>
<summary>App screens के लिए interstitial preload/show का उदाहरण खोलें</summary>

consent के बाद preload करें और नए navigation के मौके पर show करें। यह नमूना या AutoBuffer `inter_splash` या `inter_after_ob3` पर लागू न करें; वे OB के हैं।

```kotlin
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager

InterstitialAdManager.load(applicationContext, AppAdPlacement.INTER_BACK)

InterstitialAdManager.show(this, AppAdPlacement.INTER_BACK, object : InterShowCallback() {
    override fun onComplete() = goNext()
})
```

placement का waterfall, `isEnable`, `enable_ua_check`, consent/premium, interval और readiness SDK खुद तय करता है। Navigate सिर्फ `onComplete` से करें; यह ठीक एक बार चलता है, ad न होने या show fail होने पर भी। `onClosed` इस्तेमाल न करें और पहले से `canShow()` न जाँचें। load चल रहा हो या ad cache में हो तो `load` दोबारा request नहीं करता। Ad तैयार हो तो show से पहले लगभग 800 ms का dialog चलता है।

Default रूप से callback ad बंद होने के बाद चलता है। Ad दिखते ही callback चलाने के लिए `nextAction = InterNextAction.UnderAd` जोड़ें — इसे सिर्फ सामान्य स्क्रीन खोलने वाले `startActivity` के लिए इस्तेमाल करें, `finish()` के साथ या ad के नीचे camera/audio/video शुरू करने के लिए कभी नहीं।

Interstitial अपने आप तैयार रखने के लिए: [InterstitialAutoBuffer](../ads/README.md#automatic-interstitial-preload) — `ERainAd.init` के बाद `configure`, पहली content स्क्रीन पर `start`; show ऊपर की तरह। अंतराल और click cap [defaults तालिका](#flow-के-defaults) में हैं।

</details>

### अतिरिक्त integrations

| जरूरत | Default / कैसे configure करें |
| --- | --- |
| कोई slot बंद करना | **base key** पर `isEnable: false` हर format के लिए पूरा waterfall बंद करता है; asset बदलकर टेस्ट करने के लिए restart चाहिए। दूसरे LFO native का fallback कदम 4 से आता है। |
| Waterfall floors जोड़ना | `<key>_high`, `<key>_high1`…`<key>_high9`, फिर base key। Splash banner सिर्फ `banner_splash` की पहली ID इस्तेमाल करता है और अलग floors नहीं पढ़ता। |
| CTA / native style | हर field example जैसी रखें; [JSON field तालिका](#नमूना-json-के-fields) values और उनके लागू होने की जगह बताती है। कदम 4 `positionCTA` को native templates से पहले ही जोड़ चुका है। |
| App स्क्रीन का banner | `BannerAdHelper.forPlacement(this, this, "banner_home", container)`; `bannerType` argument जोड़ें, उदाहरण के लिए `BannerType.Collapsible()` ([types](../ads/src/main/java/com/ads/module/helper/banner/BannerType.kt))। Type बदलने के लिए: `flagUserEnableReload = false`, पुराना helper `cancel()` करें, फिर नया बनाएँ। SDK refresh के लिए हर floor पर AdMob refresh बंद करना होगा, फिर `BannerAdConfig.forPlacement("banner_home", bannerType, canReloadAds = true)` बनाकर `BannerAdHelper` constructor को देना होगा। |
| UA / Adjust | resources भरें और [कदम 5](#adjust-token-और-verification) वाली wiring इस्तेमाल करें। `enable_ua_check` example की values रखता है; कहाँ लागू होता है यह JSON field तालिका में देखें। |
| Firebase से JSON | [Firebase सेट करें](firebase-integration.hi.md), फिर अपनी Application में assets के बाद `AdConfig.install(FirebaseAdConfigSource())`। मौजूदा config बदलने के लिए **पूरा JSON** रखने वाली String `ad_remote_config` publish करें; नीचे दिया splash hook जोड़ें। |
| Firebase Analytics | `suite-firebase` जोड़ें और `Tracker.install` के तुरंत बाद `Tracker.addSink(FirebaseSink())` register करें; consent policy [Firebase गाइड](firebase-integration.hi.md#शुरुआती-consent) से चुनें। |
| लौटने पर app-open | चालू नहीं; JSON में `open_resume` पहले से है, पर सिर्फ JSON जोड़ने से feature चालू नहीं होता। [App-open on return](#app-open-on-return) करें। |
| premium / paywall वाली app | [BillingKit](billing-integration.hi.md) / [PayKit](paywall-integration.hi.md) और [ads से पहले billing का इंतज़ार करने वाला hook](../onboardkitorigin/README.hi.md#वैकल्पिक-integrations) करें। `onInitBilling()` default रूप से खाली है; billing install बुलाने का मतलब यह नहीं कि premium restore हो चुका है। |
| Settings से भाषा बदलना | `registerForActivityResult(StartActivityForResult())`, फिर `launch(ObLanguageActivity.intentFor(activity, LanguageScreenMode.SETTINGS))` (types `io.onboardkit.ui.language` में हैं)। `RESULT_OK` पर: `ObLanguageActivity.RESULT_LANGUAGE_CODE` पढ़ें, कदम 5 की तरह सहेजें और `recreate()` बुलाएँ; Back कोई code नहीं लौटाता। इस स्क्रीन पर ads नहीं हैं। `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)` कोई result नहीं लौटाता; पसंद `OnboardingSdk.selectedLanguage()` से पढ़ें। |
| Notification/widget entries | `SplashEntry` इस्तेमाल करें और अपने listener में passthrough बनाए रखें; [OnboardKit](../onboardkitorigin/README.hi.md#वैकल्पिक-integrations) देखें। सामान्य launcher start के लिए इनमें से कोई entry नहीं चाहिए। |

Remote JSON इस्तेमाल करें तो कदम 6 की splash class को इससे बदलें:

```kotlin
package com.example.app

import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity() {
    override fun onRemoteFetched() {
        OnboardKitSetup.configure()
    }
}
```

SDK hook से पहले fetch करता है; `configure()` mapping दोबारा बनाता है। दूसरी बार fetch न करें और `RemoteConfigUtils` copy न करें। Remote, preload या app-open न हों तो एक लाइन वाली splash रखें।

### App-open on return

<details>
<summary>App-open चालू करने के कदम खोलें</summary>

यह तभी करें जब आपका product app-open इस्तेमाल करता हो। `AppOpenManager` `com.ads.module.admob` में है।

1. **कदम 4**, `AdsConfig` में: `appResume = adConfig.interstitial(AppAdPlacement.OPEN_RESUME)`। यह field न हो तो OnboardKit **हर स्क्रीन** पर app-open रोकता है, आपकी app की screens समेत।
2. **कदम 5**, `ERainAdConfig` के `apply` block में: `idAdResume = AdGate.adUnitIds(AppAdPlacement.OPEN_RESUME).firstOrNull().orEmpty()`।
3. **Remote JSON:** कुछ जोड़ना नहीं — हर config बदलाव पर SDK app-resume unit को `open_resume` पर दोबारा point करता है, `isEnable` से चालू/बंद करना भी इसमें शामिल है। दो शर्तें: आपकी app कदम 2 में खाली न रहने वाली app-resume ID पहले ही दे चुकी हो, और `open_resume` में ad unit ID हो। कदम 2 init पर पढ़ा जाता है (तब सिर्फ asset config होता है), इसलिए `ad_config.json` में `open_resume` **असली ID के साथ चालू** ship करें।
4. **बाहर जाने वाले intents** (browser/share/review): उस वापसी को छोड़ने के लिए `startActivity(...)` के तुरंत बाद `AppOpenManager.getInstance().disableAdResumeByClickAction()` बुलाएँ। `disableAppResume()`/`enableAppResume()` पूरे process के switches हैं।

Splash, OB5 और प्रश्न स्क्रीन खुद को बाहर रखते हैं; सिर्फ अपनी app की संवेदनशील screens register करें। LFO और OB की content pages लौटने पर तैयार app-open ad दिखा सकती हैं, सिवाय fullscreen page, page transitions के दौरान और popup खुले रहने पर। Delay और gating के लिए [app-open](../onboardkitorigin/README.hi.md#app-open-on-return) देखें।

</details>

## 8. आखिरी जाँच

- [ ] Debug build splash खोलता है, Logcat tag `AdRemoteConfig` में `Loaded ad_config_debug.json with 45 placements (debug=true)` दिखता है, और `OB_FLOW` कोई config या provider error नहीं बताता।
- [ ] Test ads पर LFO → OB → MainActivity तक पूरा चलें; fullscreen native सामग्री 2 और 3 के बीच रहता है, और आखिरी interstitial सिर्फ SDK संभालता है। LFO splash interstitial बंद होने के बाद ही खुलता है; आखिरी interstitial बंद होने पर MainActivity पहले से मौजूद होती है।
- [ ] LFO: भाषा चुनकर Back दबाने पर Save दिखता है और स्क्रीन बनी रहती है; item पर चौथा tap popup खोलता है।
- [ ] Notifications मना करने पर भी flow चलता है; splash, LFO, popup और OB से Home जाकर लौटने पर दो बार navigation नहीं होता। OB page पर native click करने से लौटते समय step आगे बढ़ता है; LFO/popup पर स्क्रीन बनी रहती है और तैयार होते ही replacement ad bind होता है।
- [ ] `native_ob2` और `native_ob2_high` दोनों बंद करें: सामग्री page 2 फिर भी दिखता है और page 1 का native उधार नहीं लेता। `native_fs` और `native_fs_high` दोनों बंद करें: सिर्फ ad वाला page छूट जाता है। `inter_splash`, `inter_after_ob3` और उनके सभी `_high*` floors बंद करें: destination स्क्रीन फिर भी मिलती है।
- [ ] बिना नेटवर्क टेस्ट करें: default रूप से connection prompt दिखता है; offline support चुना हो तो flow SDK के timeout पर फिर भी आगे बढ़ता है और किसी app callback पर अटकता नहीं।
- [ ] पूरा होने के बाद दोबारा खोलें: splash से होते हुए आपकी app में, OB दोबारा नहीं चलता; splash interstitial बंद होने पर आपकी स्क्रीन पहले से मौजूद होती है। First-open टेस्ट करने के लिए app data clear करें; OB के बीच app बंद करके दोबारा खोलने पर splash के बाद LFO से शुरू होना चाहिए।
- [ ] आपकी app की screens चुनी हुई भाषा इस्तेमाल करती हैं; AAB भेजते समय अनुवाद और language split configuration दोनों जाँचें।
- [ ] Release से पहले: `res/values/id_ads.xml` का `admob_app_id` और **`ad_config.json`** की हर ad unit ID अपनी app के IDs से बदलें। Debug JSON को test IDs पर ही रखें। Debug में test App ID रखना हो तो `app/src/debug/res/values/id_ads.xml` में `admob_app_id` का override जोड़ें।

| लक्षण | तुरंत जाँचें |
| --- | --- |
| खुलते ही crash | merged manifest में AdMob/Meta metadata, असली Meta credentials और आपकी Application |
| OB खाली है या छूट जाता है | `configure` से पहले `install`; अपनी app की सामग्री के लिए कदम 4 में `steps(...)` इस्तेमाल करें, और देखें कि remote cache ने flow या कोई step बंद तो नहीं किया |
| ads नहीं आते | file load हुई, key mapping, `isEnable`, consent/premium की स्थिति, remote flags; फिर भी दिखाने के लिए timer न जोड़ें |
| Debug गलत IDs इस्तेमाल करता है | मान्य debug file मौजूद है; मानक setup में `setAllowRemoteOverrideInDebug(true)` चालू नहीं है |
| Remote ने IDs बदले पर OB अब भी पुराने इस्तेमाल करता है | `FirebaseAdConfigSource` installed है और `onRemoteFetched()` में setup दोबारा बुलाया गया है; debug default रूप से जान-बूझकर assets पर टिका रहता है |
| टेस्ट में native style/reporting हर slot के लिए अलग नहीं होती | एक ही format के demo IDs साझा हैं; provider कहीं-कहीं ID से style/placement वापस ढूँढता है। असली configuration जाँचते समय अलग IDs इस्तेमाल करें |

## आपकी app को वाकई जो files चाहिए

| File | क्या करना है |
| --- | --- |
| `settings.gradle`, `app/build.gradle` | build configuration और दोनों dependencies मिलाएँ |
| `app/src/main/AndroidManifest.xml` | Metadata, आपकी Application और splash launcher |
| `app/src/main/res/values/id_ads.xml` | AdMob App ID, Meta credentials और Adjust token/event tokens |
| `app/src/main/assets/ad_config.json`, `ad_config_debug.json` | दोनों नमूना JSON files copy करें |
| आपकी app की `strings.xml` और drawables | तीनों pages के लिए content, अनुवाद और images |
| `AppAdPlacement.kt` | OB keys और आपकी app की अपनी keys का catalog; ad unit IDs नहीं |
| [OnboardKitSetup.kt](examples/ads-onboarding/OnboardKitSetup.kt) | सामग्री, placement constants और native templates की mapping |
| आपकी मौजूदा Application या [PartnerApp.kt](examples/ads-onboarding/PartnerApp.kt) | SDK को एक बार initialize करती है और destination स्क्रीन चुनती है |
| `SplashActivity.kt` | SDK की splash को extend करता है |

`MainActivity` आपकी app की मौजूदा destination स्क्रीन है। `AppAdPlacement.kt` के अलावा JSON को ad config में बदलने के लिए कोई file नहीं चाहिए — हर entry point सीधे placement key लेता है। इस बुनियादी flow के लिए `AppConstants`, `RemoteConfigUtils`, `ResumeAdsEntryRule`, `AppLifecycleObserver`, DevConfig, Hilt या example की paywall/welcome/uninstall screens copy करने की जरूरत नहीं।

[Ads संदर्भ](../ads/README.md) · [OnboardKit संदर्भ](../onboardkitorigin/README.hi.md)
