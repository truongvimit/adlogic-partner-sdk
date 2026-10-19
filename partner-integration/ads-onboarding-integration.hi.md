# Ads + OnboardKit integration

**OB catalog:** `ob1..ob4` → `native_ob1..4`; `full1/full2` → `native_full1/2`. Default: `ob1, full1, ob2, full2, ob3, ob4`. All eligible OB natives preload on language selection. Remote `onboarding.order` selects/reorders app-declared steps. [Configuration / Hướng dẫn chi tiết](onboarding-flow.vi.md). `native_fs` remains the separate splash native.

[← गाइड चुनें](README.hi.md)

नमूना flow: **Splash → भाषा (LFO) → सामग्री 1 → सामग्री 2 → fullscreen native → सामग्री 3 → onboarding के अंत का interstitial (चालू होने पर) → MainActivity**। SDK consent, notifications, ads और navigation संभालता है; ad तभी दिखता है जब वह eligible हो और fill मिले।

कदम 1–6 करें और **package, app की जानकारी, सामग्री/images और destination स्क्रीन** बदलें। Code SDK के defaults रखता है; JSON example का debug configuration रखता है। Adjust चालू करने के लिए app token भरें; Firebase, लौटने पर ads और purchases [वैकल्पिक tables](#7-सिर्फ-वही-configure-करें-जो-app-को-चाहिए) में हैं।

सभी modules के लिए [JitPack](https://jitpack.io/#truongvimit/adlogic-partner-sdk) का **newest SDK version** इस्तेमाल करें। इसमें grouped `ad_behavior_config` / `onboarding_config`, custom local defaults और live `AdsConfig.fromAdConfig()` bindings शामिल हैं। केवल Firebase keys जोड़ने से app में पहले से integrated पुराना SDK update नहीं होता।

वैकल्पिक Privacy → Goal, pager और उसके exit interstitial (चालू होने पर; default रूप से बंद) के बाद onboarding का अंतिम भाग है। `privacy_goals_screen.enabled` चालू करें और goal options दें; [Privacy → Goal](privacy-goals-screen.hi.md) देखें।

Launcher से app खोलने वाला लौटता user splash के बाद डिफ़ॉल्ट रूप से Welcome Back छोड़कर app में जाता है। इसे दिखाने के लिए `welcome_back.enabled=true` करें। [Welcome Back](welcome-back-screen.hi.md) देखें।

यह guide default `PER_SCREEN` native flow बताती है। वैकल्पिक `SHARED_POOL` flow LFO → pager → Privacy → Goal को SDK की एक ही screen में चलाता है, जिसे एक साझा native pool ads देता है; [SHARED_POOL native flow](#shared_pool-native-flow) देखें।

Pager page का view बने रहने तक native bound रहता है; content या Full1/Full2 पर लौटने पर वही ad दिखता है, नया request नहीं होता। Fullscreen auto-next हर visit पर फिर शुरू होता है; लौटने पर enabled Skip तुरंत दिखता है, और exit न होने पर सुरक्षा के लिए भी दिखता है। पहली no-fill पर अगला page आता है; failed page पर वापस आने पर fallback और Skip दिखते हैं। View नष्ट होने पर ad release होता है।

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

App project की root `gradle.properties` में newest JitPack version एक बार सेट करें; सभी SDK modules यही property पढ़ते हैं:

```properties
adlogicSdkVersion=NEWEST_VERSION
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
- **[ad_config_debug.json](examples/ads-onboarding/ad_config_debug.json):** हर placement key के लिए सिर्फ test `ids` (आम तौर पर एक floor); बाकी हर field `ad_config.json` से आता है।

Ad unit IDs में **`/`** होता है। `ad_config.json` हर build के लिए style, UA, app-resume delay और waterfalls रखती है; [debug example](examples/ads-onboarding/ad_config_debug.json) हर placement key को सिर्फ एक test floor से map करता है (interstitials `1033173712`)। नीचे OB slots दिए हैं; कदम 4 का `fromAdConfig()` `native_resume` (native resume), `open_resume` (app-open), `native_welcome1/2` (Welcome Back) और `native_onboarding_fullscreen_1_4` (OB5) भी bind करता है। बाकी keys आपकी app screens के लिए हैं और खुद से कोई display position नहीं बनातीं। नमूने की values parser के defaults से अलग हो सकती हैं; [JSON field तालिका](#नमूना-json-के-fields) देखें।

| JSON key | जगह | कदम 4 में `AdsConfig` में mapping |
| --- | --- | --- |
| `banner_splash` | Splash banner | `splashBanner` |
| `native_splash` | Splash native, banner वाला ही slot | `splashInlineNative` |
| `inter_splash` | Splash छोड़ते समय का interstitial | `splashInterstitial` |
| `native_fs` | Splash interstitial के बाद, LFO से पहले का वैकल्पिक full-screen native | `splashNative` |
| `native_lang` | भाषा का पहला native | `languageNative` |
| `native_lang_alt` | पहली बार भाषा चुनने के बाद का replacement native | `languageDupNative` |
| `native_popup_lang` | भाषा confirmation popup का native | `languageConfirmNative` |
| `native_ob1` | सामग्री 1 — `StepId.OB1` | `stepNatives[StepId.OB1]` |
| `native_ob2` | सामग्री 2 — `StepId.OB2` | `stepNatives[StepId.OB2]` |
| `native_full1` | Full1 — `StepId.FULL1` | `stepNatives[StepId.FULL1]` |
| `native_full2` | Full2 — `StepId.FULL2` | `stepNatives[StepId.FULL2]` |
| `native_ob3` | Content 3 — `StepId.OB3` | `stepNatives[StepId.OB3]` |
| `native_ob4` | Content 4 — `StepId.OB4` | `stepNatives[StepId.OB4]` |
| `inter_after_ob3` | पूरे onboarding के बाद, destination स्क्रीन से पहले | `afterOnboardingInterstitial` |

`native_ob3` → **OB3**, `native_ob4` → **OB4**; `native_full1/2` → **Full1/Full2**. `inter_after_ob3` पूरे onboarding के बाद दिखता है।

हर build अपनी settings `ad_config.json` से पढ़ता है। Debuggable build `ad_config_debug.json` भी पढ़ता है, जिसमें हर placement key के लिए सिर्फ `ids` होता है, आम तौर पर एक test floor: ये floors placement के पूरे waterfall की जगह लेते हैं, इसलिए हर placement सामान्य load path से अपनी test ID load करता है। Debug file का कोई भी दूसरा field `WARN` के साथ ignore होता है, और `ad_config.json` में चालू जिस placement की debug entry नहीं है उसे ad नहीं मिलता (यह भी `WARN`)। Remote `ad_remote_config` IDs को छोड़कर हर field लागू करता है; `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` remote की declare की गई IDs भी ले लेता है। Debug file न हो तो debug `ad_config.json` की IDs इस्तेमाल करता है।

नमूने के IDs [Google demo ad units](https://developers.google.com/admob/android/test-ads#demo_ad_units) और [AdMob App ID](https://developers.google.com/admob/android/quick-start) से हैं। Fullscreen page **native ID** इस्तेमाल करता है। नमूना हर format के लिए एक ही test ID साझा करता है; production में placement के अनुसार configuration, style और reporting अलग करने के लिए अलग IDs चाहिए।

### वैकल्पिक: grouped remote settings और custom local defaults

SDK में [ad_behavior_config.json](examples/ads-onboarding/ad_behavior_config.json) और [onboarding_config.json](examples/ads-onboarding/onboarding_config.json) भी bundled हैं। Remote control के लिए [Firebase: तीन String parameters publish करें](firebase-integration.hi.md#remote-json)। Ad units के लिए `ad_remote_config` रखें; `ad_behavior_config` और `onboarding_config` अलग String values हों, जिनमें संबंधित JSON objects हों। दोनों examples bundled defaults जैसे ही हैं, सिवाय onboarding example की तीन बातों के: `onboarding.primary_color` `""` है ताकि app का मौजूदा UI रंग बना रहे, जबकि SDK default `"#FF375E"` है; `onboarding.exit_interstitial.enabled` `true` है, जबकि SDK default `false` है; और [`native_flow`](remote-settings.hi.md#native-flow-type) scope शामिल नहीं है।

Fallback बदलने के लिए `app/src/main/assets/` में इन्हीं नामों की files बनाएँ, पूरा default copy करें या सिर्फ बदलने वाले fields रखें, फिर rebuild करें। SDK defaults सही हों तो app में अतिरिक्त files आवश्यक नहीं। Valid remote/cache fields, फिर backend द्वारा भेजी गई legacy `ob_*` keys, इन files से किसी भी scope पर और आपके Kotlin config से पहले लागू होते हैं; offline fetch पुराने valid remote cache को रखता है, local को उस पर लागू नहीं करता। [दो local JSON उदाहरण और fallback नियम](firebase-integration.hi.md#local-defaults), तथा [field/default reference](remote-settings.hi.md) देखें।

## 3. Onboarding की सामग्री तैयार करें

अपना मौजूदा `app_name` और launcher icon इस्तेमाल करें। ये आठ strings `app/src/main/res/values/strings.xml` में जोड़ें और content अपने product के अनुसार बदलें:

```xml
<resources>
    <string name="onboarding_title_1">स्वागत है</string>
    <string name="onboarding_des_1">App का मुख्य फ़ायदा बताएँ.</string>
    <string name="onboarding_title_2">शुरू करना आसान</string>
    <string name="onboarding_des_2">User को जो क्रिया जाननी है वह दिखाएँ.</string>
    <string name="onboarding_title_3">शुरू करने के लिए तैयार</string>
    <string name="onboarding_des_3">User को शुरू करने का न्योता दें.</string>
    <string name="onboarding_title_4">एक और बात</string>
    <string name="onboarding_des_4">जानने लायक एक और feature दिखाएँ.</string>
</resources>
```

`values-<language>/strings.xml` में अनुवाद जोड़ें, [ob_strings.xml](../onboardkitorigin/src/main/res/values/ob_strings.xml) की `ob_*` keys समेत, क्योंकि SDK सिर्फ अंग्रेजी देता है। कदम 4 में चारों `imageRes` values अपनी app की images से बदलें (sample में OB3 और OB4 एक ही image साझा करते हैं), या आजमाने के लिए example से images [1](../app/src/main/res/drawable-nodpi/img_onboard_sample_1.png), [2](../app/src/main/res/drawable-nodpi/img_onboard_sample_2.png), [3](../app/src/main/res/drawable-nodpi/img_onboard_sample_4.png) को `app/src/main/res/drawable-nodpi/` में copy करें।

LFO, popup, OB और native ads के layouts/Activities SDK पहले से देता है।

<details>
<summary>वैकल्पिक: अपने layouts इस्तेमाल करें</summary>

सिर्फ `SplashConfig.layoutRes` और `ContentStepDefinition.layoutRes` custom layouts लेते हैं; बाकी layout fields `getOrThrow()` में fail होते हैं। [content layout](../onboardkitorigin/src/main/res/layout/ob_fragment_content_step.xml) copy करें और उसका root, IDs तथा view types बनाए रखें; contract टूटे तो `OB_FLOW` में log होता है और SDK default layout पर लौटता है। [splash](../onboardkitorigin/src/main/res/layout/ob_activity_splash.xml) में गायब IDs नजरअंदाज होते हैं, पर जो IDs रखें उनका type सही होना चाहिए; banner के लिए `ob_splash_ad_container` चाहिए जिसमें `<include layout="@layout/layout_banner_control" />` हो।

</details>

## 4. Placements घोषित करें और OnboardKit configure करें

### `AppAdPlacement.kt` — आपकी app का placement catalog

[AppAdPlacement.kt](examples/ads-onboarding/AppAdPlacement.kt) को अपने app package में copy करें, उदाहरण के लिए `app/src/main/java/com/example/app/`। File में हर placement के लिए एक key है: OB slots और आपकी app के slots। Floors उसी key के `ids` में रहते हैं; उनके लिए constants नहीं चाहिए।

`AppAdPlacement.NATIVE_HOME` की key `native_home` है; `ad_config.json` में उसके ad unit IDs और configuration रहते हैं, `ad_config_debug.json` में सिर्फ उसके test `ids`। हर नए placement के लिए constant और वही JSON key जोड़ें।

स्क्रीन के XML में native/banner slot घोषित करें और उसी स्क्रीन से SDK API सीधे बुलाएँ। Loading, cache और ad lifecycle SDK संभालता है। `AdsAppManager` इस्तेमाल करें तो उसमें सिर्फ configuration, initialization और app की अपनी policy रखें।

### `OnboardKitSetup.kt` — OB keys को SDK से जोड़ें

[OnboardKitSetup.kt](examples/ads-onboarding/OnboardKitSetup.kt) उसी package में copy करें, `com.example.app` बदलें और app के अपने resources रखें। Sample चार content pages और दो fullscreen pages (FULL1, FULL2) घोषित करता है और standard placement bindings के लिए **`AdsConfig.fromAdConfig()`** इस्तेमाल करता है। कदम 5 में `OnboardingSdk.install` के बाद एक बार configure करें; ID, gate और template current settings से resolve होते हैं, fetch के बाद setup दोहराना नहीं पड़ता। अलग keys वाली app सिर्फ बदली associations `fromAdConfig(mapOf(...))` को दे; [mapping table](remote-settings.hi.md) में defaults हैं।

Declared लेकिन disabled placement खाली unit रखता है, इसलिए दूसरे slot का ad नहीं लेता। केवल LFO2 unit absent होने पर LFO1 fallback है; replacement action बंद करने के लिए `onboarding_config.lfo.native2.enabled = false` रखें।

LFO/Welcome Back/content OB natives अपना frame placement key के `templateId` (SHARED_POOL में LFO1/2 के लिए template `4`, classic LFO/Welcome Back/content OB के लिए template `1`) से और blocks का क्रम उसी key के `components` से लेते हैं; default क्रम CTA को नीचे रखता है, `["cta","media","icon_headline"]` उसे ऊपर रखता है। `positionCTA` हटा दिया गया है: जो payload अब भी इसे भेजता है, उसे अनदेखा किया जाता है। Host fallbacks `AdsConfig.languageTemplate`/`contentStepTemplate` और `NativeTemplate` enum भी इसके साथ हटा दिए गए हैं; इन्हें अपने `AdsConfig` से हटा दें। `onboarding_config` के `native_template`/`content_template`, per-step overrides समेत, हटा दिए गए हैं। Fullscreen/popup frame स्थिर रहते हैं। `colorCTA` CTA और Ad badge दोनों को रंग देता है; color/height/components हर bind पर फिर पढ़े जाते हैं, click/resume replacement पर भी।

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

`ERainTuning.install()` [app screens के interstitials](#app-की-अपनी-screens-में-interstitial-placement-constant-के-साथ) के लिए `onComplete` का समय तय करता है और ad click के बाद अगला resume ad छोड़ देता है; OB flow नहीं बदलता।

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

नीचे की table SDK defaults और मौजूदा local/legacy fallback APIs बताती है। Remote experiments या app-side JSON defaults के लिए [grouped settings reference](remote-settings.hi.md) वाले fields इस्तेमाल करें। हर setting की precedence: remote `onboarding_config` / `ad_behavior_config` field > backend द्वारा भेजी गई legacy `ob_*` key > आपकी app-asset JSON > नीचे के Kotlin options और hooks > bundled SDK default।

कदम 4 के `onboardKitConfig { ... }` block में सिर्फ वही options जोड़ें जो बदलने हैं; `ERainAd`/`ConsentCenter` वहीं बुलाएँ जहाँ तालिका कहती है। `ob_*` keys Firebase Remote Config की हैं और **ad JSON में नहीं** हैं; Firebase न हो तो उसकी आखिरी बार भेजी (cached) values लागू होती हैं, और जो key उसने कभी नहीं भेजी वहाँ आपकी configuration ही लागू रहती है।

| व्यवहार | Default | कब बदलें / कहाँ बदलें |
| --- | --- | --- |
| Splash स्क्रीन | SDK layout; ad-loading phase से minimum display 3000 ms; Back और back gesture नज़रअंदाज़ होते हैं | `onboarding_config.splash.timing.min_display_ms` (valid `0` यह minimum हटाता है) या backend द्वारा भेजा गया `ob_splash_min_display_ms` (`<= 0` local value रखता है); remote चुप हो तो आपका app asset, फिर `SplashConfig.minDisplayTimeMs`। |
| Splash interstitial के बाद अगली स्क्रीन खोलना | Interstitial न्यूनतम display समय के बाद दिखता है। पहली बार का LFO और पुराने उपयोगकर्ता का Welcome Back: बंद होने का इंतज़ार। Launcher → app (`welcome_back.enabled` बंद): ad के नीचे खोलें। Notification/widget/uninstall: बंद होने का इंतज़ार | `SplashActivity.nextScreenTiming()` override करें: `NextScreenTiming.AFTER_AD`/`UNDER_AD` (`io.onboardkit.ads`), या default रखने के लिए `super.nextScreenTiming()`। `splash.navigation.next_screen_timing` की `AUTO` के अलावा कोई भी value, remote या आपकी app asset से, हर launch पर override से ऊपर है, notification/widget/uninstall समेत; `UNDER_AD` उस launch में splash की native_fs screen भी छोड़ देता है |
| Splash ad का इंतज़ार | notification कदम के बाद और splash को focus मिलने पर अधिकतम 60 सेकंड | Remote `ob_splash_ad_budget_ms`; अपना timer न जोड़ें |
| Remote / ads | Consent → slot + interstitial; SDK remote साथ चलता है और splash के बाद भी जारी रहता है। | मौजूदा asset/cache/remote values; नीचे fetch timing देखें। |
| LFO1 preload | `SEQUENTIAL`: interstitial result → LFO1. `PARALLEL`: splash requests → LFO1. | `splash.load.lfo1_preload_mode` |
| नेटवर्क नहीं | उपयोगकर्ता से कनेक्ट करने को कहता है और आगे नहीं बढ़ता | नीचे दिए UMP fallback से offline शुरू करने के लिए `SplashConfig.noInternetPromptEnabled = false`; अगली splash फिर से UMP माँगती है |
| Consent | 10 सेकंड का network timeout; form खुद उपयोगकर्ता का इंतज़ार करता है | अपनी Application में: `ConsentCenter.configure(ConsentOptions(timeoutMs = ...))` (`com.ads.module.consent`)। SDK का hook बनाए रखें; `SplashConfig.consentTimeoutMs` UMP timeout नहीं बदलता |
| QA के दौरान UMP form | Debuggable: हर device EEA गिना जाता है (`setForceTesting`), hashed ID नहीं चाहिए; release: असली भूगोल | असली भूगोल के अनुसार debug करने के लिए `ConsentOptions(debug = false)`। `configure` हर option बदल देता है, इसलिए timeout भी बदलना हो तो एक ही `ConsentOptions(timeoutMs = ..., debug = false)` दें |
| Notifications | Consent और splash ad requests के बाद, remote का इंतज़ार नहीं; Android13+/target33+। Denial पर flow जारी, दर्ज result अगली automatic prompt रोकता है। | आपकी app notifications न भेजती हो या prompt खुद संभालती हो तो `SplashConfig.notificationPermissionEnabled = false` |
| भाषा | 21 भाषाएँ, 3 सेकंड बाद hand hint, चुनने से पहले confirmation हल्का दिखता है | `LanguageConfig.languages`: जिन भाषाओं का अनुवाद किया है वही रखें; remote `lfo.languages.supported_codes` इस list को छोटा करता है, और जो `defaultCode` उससे बाहर रहे वह preselect नहीं होता। Remote या app-asset value `tapHintEnabled` और `confirmVisibleBeforeSelect` को दोनों दिशाओं में set करती है |
| LFO पर Back | कुछ न चुना हो: Back नजरअंदाज होता है। चुनने के बाद: Save दिखता है और भाषा स्क्रीन बनी रहती है | `LanguageConfig.saveButtonOnBackEnabled = false`: चुनने के बाद भी Back नजरअंदाज करें। SETTINGS में Back स्क्रीन बंद करता है |
| भाषा चुनने के बाद native बदलना | चालू; replacement ad bind हो पाने तक पहला native बना रहता है। Replacement न दिख पाए (no fill, या समय पर bind न हो) तो उसकी जगह पहला native reload होता है (जब तक confirmation popup पहले से खुला न हो), और देर से fill हुआ replacement अगली भाषा tap पर swap होकर आता है, पहले native के दोबारा request करने से पहले। उसके बाद दूसरी भाषा पर हर tap जो popup नहीं खोलता, स्क्रीन वाले native को उसके पूरे waterfall पर reload करता है, सिवाय जब उसका load अभी चल रहा हो; replacement बंद हो तो पहला tap भी reload करता है। मौजूदा भाषा दोबारा चुनना कभी reload नहीं करता। Remote `lfo.confirm_dialog.show_from_tap` popup की सीमा, और उसके साथ reload, अगले tap से खिसकाता है | Replacement बंद करने के लिए `LanguageConfig.secondNativeOnSelectEnabled = false`; reload की कोई setting नहीं |
| भाषा popup | मौजूदा भाषा दोबारा चुनने पर तुरंत खुलता है। दूसरी भाषा चुनने पर कुल चौथे tap से खुलता है; re-select tap भी count होता है। इसका native पहली बार popup खुलने पर request होता है | बंद करने के लिए `LanguageConfig.confirmDialogOnReselectEnabled = false`; SETTINGS में popup नहीं दिखता |
| Native template | SDK: SHARED_POOL में LFO1/2 पर template `4`, classic LFO/Welcome Back/content OB पर `1`, splash inline और Privacy/Goal पर `2`; CTA नीचे | Frame के लिए `ad_config.<key>.templateId` और क्रम के लिए `components` सेट करें। Backend का `ad_remote_config` आपके app asset से field-दर-field ऊपर है। [Priority](remote-settings.hi.md)। |
| System bars | Status/caption bars दिखते हैं, navigation bar छिपा | `SystemBarConfig(showStatusBar, showNavigationBar, showCaptionBar)` |
| OB पर native click और वापसी | pager के content और fullscreen pages वापसी पर आगे बढ़ते हैं (`auto_next`) | page पर बने रहने के लिए page की key (`native_ob1`…) पर `"click_action": "none"`, बने रहकर replacement दिखाने के लिए `"reload"`/`"reload_waterfall"`; [Native click actions](remote-settings.hi.md#native-click-actions) |
| LFO/popup, Welcome Back, OB5 या app स्क्रीन पर native click | ad click/open होते ही preload करता है; वापस आने पर तैयार ad bind करता है या चल रही request का इंतज़ार करता है। Replacement केवल all-price floor request करता है (`reload`); `reload_waterfall` हर floor चलता है | ad_config में placement की key पर `click_action`, समय-आधारित refresh से स्वतंत्र; [app screen के native का उदाहरण](#app-की-अपनी-screens-में-native-placement-constant-के-साथ) |
| flow अधूरा रहते दोबारा खोलना | Splash → LFO → OB फिर से चलता है; पूरा flow पूरा होने पर ही OB छूटता है | app की ओर से first-open flag या checkpoint नहीं चाहिए |
| Fullscreen native page | Page select होने के 5 सेकंड बाद X और 15 सेकंड बाद auto-next; background time भी गिना जाता है, पर click के बाद page के ad में बिताया समय नहीं। Shimmer पूरे native host को भरता है, media पूरे viewport में और CTA नीचे रहता है. | `AdFullScreenStepDefinition` के fields; remote `ob_skip_button_delay_sec = -1` local delay बनाए रखता है |
| Onboarding के अंत का interstitial | बंद (`onboarding.exit_interstitial.enabled` `false` है)। चालू होने पर: pager में आते ही preload, पूरा होने पर fill के लिए अधिकतम 8 सेकंड इंतज़ार; अगली स्क्रीन ad के नीचे खुलती है। Notification/widget/uninstall: बंद होने का इंतज़ार | चालू करने के लिए `afterOnboardingInterstitialEnabled = true`, या remote अथवा app asset में `onboarding.exit_interstitial.enabled: true`; हमेशा बंद होने का इंतज़ार करने के लिए `AdsConfig.afterOnboardingInterstitialTiming = NextScreenTiming.AFTER_AD`; आपकी app खुद संभालती हो तो इसे बंद ही रखें। इसे `InterstitialAutoBuffer` से बाहर रखें |
| OB navigation | Swipe चालू होने पर OB1 locked रहता है। OB2/OB3/OB4 और fullscreen पर load/bind के दौरान swipe बंद रहता है और ad impression के बाद खुलता है; जिस content page पर कोई ad नहीं है (unit नहीं, premium, no fill) वहाँ तुरंत खुलता है। हर नए visit पर फिर lock लगता है; global swipe lock हमेशा लागू रहता है. OB1 पहला page हो तो वह ऊपर-बाएँ back button दिखाता है; यह, Back और OB1 पर back gesture LFO पर लौटाते हैं, जो नए सिरे से खुलकर अपने natives फिर request करता है। किसी और page पर button नहीं है: वहाँ Back एक page पीछे जाता है और पहले page से app बंद करता है। | `BehaviorConfig.lockPagerSwipe`, `swipeCompletesLastStep`, `backNavigatesBack` (`false`: back button के बिना हर page से Back app से बाहर निकालता है), OB1 से button हटाने के लिए `onboarding.steps.ob1.back.enabled = false` (remote या app asset), `lockPortrait`; landscape app को manifest भी बदलना होगा |
| Interstitial का अंतराल | `ERainAdConfig.intervalInterstitialAd = 0` (कोई सीमा नहीं); यह सिर्फ `InterstitialAutoBuffer` group पर लागू होता है, splash/OB या खुद load किए interstitials पर नहीं | init से पहले सेट करें, या `ERainAd.getInstance().setIntervalInterstitialAd(seconds)` इस्तेमाल करें |
| Interstitial click cap | बंद (`0`) | `ERainAd.getInstance().setMaxClickAdsPerDay(n)`: हर ad unit पर 24 घंटे में ज्यादा से ज्यादा `n` clicks, उसके बाद load/show रुक जाता है। जरूरत पर बुलाएँ, आम तौर पर remote fetch के बाद |
| OB5, paywall, resume | `ob_enable_step_ob5 = false`। OB5 चालू हो: इसका native लोड हो तो यह खुलता है (आखिरी interstitial चालू होने पर उसके नीचे), वरना छूट जाता है। `fromAdConfig()` के साथ OB5 `native_onboarding_fullscreen_1_4` bind करता है; `ad_config.json` या remote में declare होने पर यह key code में दिए unit से ऊपर रहती है। Key declare न हो तो `ob5Native`, फिर `fullScreenStepNative` (host setup) लागू होता है। Paywall जुड़ा नहीं है। [लौटने पर ads](#ads-on-return) `app_resume.type` (default `native_resume`) के अनुसार चलते हैं; `disableAppResume()` उन्हें बंद रखता है | इसकी ID `native_onboarding_fullscreen_1_4` के नीचे रखें (`AdsConfig.ob5Native` तभी लागू होता है जब वह key declare न हो); paywall तभी जोड़ें जब जरूरत हो |

UMP की error या timeout [AdLogic fallback](../ads/src/main/java/com/ads/module/consent/ConsentCenter.kt) के जरिए process में **request की कोशिश** की अनुमति दे सकती है; यह न consent देता है, न fill की गारंटी। अपने CMP से `ConsentCenter.setHostConsent(false, false)` देकर requests बंद करने वाला host फिर भी जीतता है; request की अनुमति timer या personalization से न मानें।

Consent request की authority है। Custom CMP का परिणाम `ConsentCenter.setHostConsent(canRequestAds, personalized)` से publish करें और `ConsentCenter.canRequestAds()` से पढ़ें। Hook timeout consent नहीं देता।

`adProvider = ERainAdProvider()` install करें, या ads के बिना onboarding के लिए unset रखें। `OnboardingAdProvider` SDK-owned है, partner इसे implement नहीं कर सकता। Per-tier timeout `native.load.tier_timeout_ms` और `interstitial.load.tier_timeout_ms` से तय करें। Request से पहले gate द्वारा रोका गया language native language screen पर एक बार request हो सकता है; पूरा हुआ no-fill फिर request नहीं होता।

### नमूना JSON के fields

`ad_config.json` हर placement में test IDs इस्तेमाल करती है और `native_ob1..4`, `native_full1/2` और `native_fs` को बंद रखती है (उन slots को test करने के लिए इन्हें चालू करें); `ad_config_debug.json` में सिर्फ test `ids` हैं।

| Field | नमूने में value | कैसे इस्तेमाल होता है / कहाँ लागू होता है |
| --- | --- | --- |
| `ids` | सही format का test ad unit | `{"id", "isEnable"}` floors का waterfall, सबसे ऊँचा पहले, all-price आख़िर में; एक unit वाला placement एक-floor array है। Placement-level `"id"` नहीं पढ़ा जाता। Release से पहले असली file के IDs बदलें; placement keys न बदलें। देखें [Waterfall](#waterfall-हर-placement-की-एक-key)। |
| `isEnable` | `true`, सिवाय `native_ob1..4`, `native_full1/2` और `native_fs` के, जो `false` हैं | Placement चालू या बंद करता है: `false` पूरा waterfall बंद करता है। `ids` के हर floor का अपना `isEnable` है, जो सिर्फ उसी floor को रोकता है। |
| `enable_ua_check` | उदाहरण में `true` और `false` दोनों | `true` के लिए paid/non-organic attribution चाहिए; Adjust उत्तर आने तक default organic है। Standard `AdsConfig.fromAdConfig()` bindings संबंधित OB placements, native LFO/OB और exit interstitial पर भी यह gate लागू करती हैं। Adjust न हो तो दिखाने वाले placements पर इसे `false` रखें। |
| `reloadIntervalSeconds` | Banner: `30` | Positive value हर placement-bound banner (splash समेत) की auto-reload cadence सेकंड में तय करती है, जब तक `banner.reload.auto_enabled` `true` है (default)। Absent, `0` या invalid होने पर host/SDK value (15000 ms) लगती है। App स्क्रीन setup: [App स्क्रीन का banner](#अतिरिक्त-integrations)। |
| `colorCTA` | `"default"` | Template का रंग बनाए रखता है; custom native चाहिए तो रंग सेट करें। |
| `colorBackground` | नहीं | Optional: native card का background रंग, जैसे `"#FFF4E5"`। न हो, `null`, `""` या `"default"` हो तो layout का अपना background रहता है; card के corners और border वैसे ही रहते हैं। हर native frame और उसके loading skeleton पर लागू होता है। |
| `colorAdBadge` | मौजूद नहीं | Optional: Ad badge का background रंग, जैसे `"#007AFF"`; badge पर यह `colorCTA` से ऊपर है। न हो, `null`, `""` या `"default"` हो तो `colorCTA`, फिर layout का रंग लगता है। हर native frame और उसके loading skeleton पर लागू होता है। |
| `colorAdBadgeText` | मौजूद नहीं | Optional: Ad badge के text का रंग, जैसे `"#FFFFFF"`। न हो, `null`, `""` या `"default"` हो तो layout का text रंग रहता है। हर native frame पर लागू होता है; loading skeleton में text छिपा रहता है। |
| `heightCTA` | ज्यादातर natives के लिए `45`, `native_splash` पर `44`, popup के लिए `36` | dp में CTA height; field न हो तो SDK `44` (templates की button height) इस्तेमाल करता है और लागू करते समय value को 36–52 के बीच सीमित करता है। |
| `templateId` | नहीं | Optional: placement का SDK native template चुनता है — `1` = classic LFO card (पूरी चौड़ाई में 1.68:1 media), `2` = 4:3 media-left card, `3` = 1.91:1 card, `4` = DashDrama-shaped wide LFO card (लगभग 3.14:1 media)। Slot की ऊँचाई card के बराबर होती है। न हो, `null` हो या अनजान number हो तो slot का default रहता है: `SHARED_POOL` में LFO1/2 पर `4`, classic LFO/Welcome Back और OB content पर `1`, Privacy/Goal और splash bottom slot पर `2`। Language popup और fullscreen natives अपने तय frame रखते हैं। देखें [Native templates](remote-settings.hi.md)। |
| `components` | `["icon_headline", "body", "media", "cta"]` | Blocks का क्रम, ऊपर से नीचे; गायब block छिपा रहता है और खाली array XML रखता है। न हो तो default क्रम CTA को नीचे रखता है। Templates `1`, `3` और `4` तथा [app स्क्रीन के natives](#app-की-अपनी-screens-में-native-placement-constant-के-साथ) इस क्रम का पालन करते हैं; template `2`, language popup और fullscreen natives हमेशा वैसे ही दिखते हैं जैसे बनाए गए हैं। |
| `app_resume_load_delay_ms` | `open_resume`: `2000` | किसी भी `app_resume.type` का ad request होने से पहले app को कितनी देर background में रहना है; इससे जल्दी लौटने पर कुछ नहीं दिखता। `SHARED_POOL` की resume screen इसकी जगह `native_flow.pool.resume.min_background_ms` इस्तेमाल करती है ([SHARED_POOL native flow](#shared_pool-native-flow))। |
| `click_action` | `native_ob1..4`, `native_full1/2` और `native_fs` पर `"auto_next"`; बाकी हर native पर `"reload"` | native ad click के बाद वापसी पर क्या हो। `ids` के हर floor पर लागू होता है; इसे सिर्फ `ad_config.json` या remote में रखें (`ad_config_debug.json` इसे ignore करती है)। [Native click actions](remote-settings.hi.md#native-click-actions) देखें। |

### Waterfall: हर placement की एक key

हर placement एक key है और अपने floors `"ids"` में लिखता है, सबसे ऊँचा floor पहले और all-price floor आख़िर में; हर floor `{"id": "...", "isEnable": true|false}` है, और जिस floor में `isEnable` न हो वह चालू है। Placement का `isEnable` master switch है। किसी एक floor को रोकना हो तो उसी floor पर `isEnable: false` रखें: उसकी ID अपनी जगह रहती है, इसलिए दोबारा चालू करने के लिए और कुछ नहीं चाहिए। एक ही unit वाला placement (banner, `inter_back`, `open_resume`…) एक-floor array है, floor में `isEnable` के बिना, `"ids": [{ "id": "..." }]`, क्योंकि placement का `isEnable` ही उसे चालू/बंद करता है। Placement-level `"id"` अनजान field है, warning के साथ ignore होता है, इसलिए उस placement का कोई ad unit नहीं रहता। बाकी हर field सभी floors पर लागू होता है। `ad_config.json` में जो placement `isEnable` छोड़ देता है वह बंद रहता है (floor से अलग, जिसका `isEnable` default में चालू है), और न दिया गया `enable_ua_check` `false` माना जाता है; remote document में छोड़ा गया field नीचे की layer की value रखता है।

```json
"native_ob1": {
  "ids": [
    { "id": "ca-app-pub-xxx/2222222222", "isEnable": false },
    { "id": "ca-app-pub-xxx/1111111111", "isEnable": true }
  ],
  "isEnable": true,
  "click_action": "auto_next"
},
"banner_home": {
  "ids": [{ "id": "ca-app-pub-xxx/3333333333" }],
  "isEnable": true
}
```

`ids` सिर्फ floor objects स्वीकार करता है: bare string warning के साथ skip होती है, और जिस non-empty `ids` में कोई valid floor न हो उसे ignore किया जाता है, तब नीचे की layer लागू होती है; पर साफ़ लिखा `"ids": []` एक valid खाली waterfall है, इसलिए placement कुछ request नहीं करता। गलत format वाला floor अकेले skip होता है। सभी floors बंद हों तो placement कुछ request नहीं करता। Layers के बीच (remote > `ad_config.json` > code) ऊपर की layer का `ids` नीचे की layer का पूरा array replace करता है, इसलिए Firebase में हर floor के `isEnable` के साथ पूरा array रखें। दोहराए गए IDs हटा दिए जाते हैं; किसी एक floor की जाँच करनी हो तो अलग IDs इस्तेमाल करें।

**Breaking change — `_high` keys से upgrade करना।** पुराने SDK versions floors को अलग `<key>_high`, `<key>_high1`…`<key>_high9` keys से पढ़ते थे। अब वे keys नहीं पढ़ी जातीं: parser एक warning log करता है जो `not read as floors; move their ids into the base key's "ids"` पर ख़त्म होती है, और placement सिर्फ अपनी key request करता है। Placement-level `"id"` भी अब नहीं पढ़ा जाता। हर floor अब उसी एक key के तहत report होता है, इसलिए load और revenue events `<key>` और `<key>_high` में नहीं बँटते।

1. `ad_config.json` और Firebase की `ad_remote_config` value में हर `<key>_high*` ID को पुराने क्रम (`_high`, `_high1`…`_high9`) में `{"id", "isEnable"}` के रूप में `<key>.ids` में ले जाएँ, उस floor का पुराना `isEnable` रखते हुए; पुरानी `<key>.id` आख़िरी floor बनती है। जिस key में पुराना string form `"ids": ["a", "b"]` था (पुराने SDK इसे स्वीकार करते थे), उसकी हर string उसी क्रम में floor object बनती है और उस key की अपनी `id` से पहले आती है: पुराना SDK `<key>_high`, `<key>_high1`…`<key>_high9`, फिर `<key>` request करता था, और हर key के भीतर पहले उसका `ids` फिर उसकी `id`, दोहराई गई IDs छोड़ते हुए। `_high*` entries हटा दें। बाकी हर `"id": "X"`, एक unit वाले placements और `ad_config_debug.json` की हर key समेत, `"ids": [{"id": "X"}]` बनता है।
2. Firebase में नए format वाली `ad_remote_config` को app version ≥ इस SDK वाले पहले version की condition के तहत publish करें; पुराने versions पुरानी value रखते हैं। यह condition अनिवार्य है। SDK 5.5.6 या नए version वाली app को नया format मिले तो वह crash नहीं होती: वह `ids` ignore करके अपने APK में बना waterfall रखती है, लेकिन remote ID बदलाव नहीं लेती। SDK 5.5.5 या पुराने version वाली app अपनी पूरी configuration को remote document से बदल देती है और हर ad unit खो देती है।
3. Update के बाद पहले launch पर cache की हुई पुराने format वाली value एक session तक active रह सकती है। उसका `id` और `_high*` entries नहीं पढ़ी जातीं, इसलिए अगले fetch तक हर placement आपकी `ad_config.json` का waterfall रखता है; value के बाकी fields लागू रहते हैं।

### App की अपनी screens में native, placement constant के साथ

<details>
<summary>App screens के लिए native और preload का उदाहरण खोलें</summary>

स्क्रीन के XML में slot घोषित करें (हर native/banner का अलग slot रखें)। OB अपने slots खुद संभालता है:

```xml
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/ad_slot"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

consent के बाद, `AppCompatActivity` resumed होने पर स्क्रीन से SDK बुलाएँ:

**Adjust के बिना:** नमूना slot दिख सके इसके लिए `ad_config.json` में `native_home` का `enable_ua_check` `false` ही रखें (sample में पहले से है); `ad_config_debug.json` में सिर्फ test `ids` होते हैं। QA के दौरान test IDs रखें।

```kotlin
import android.widget.FrameLayout
import com.ads.module.helper.adnative.NativeAdHelper

val container = findViewById<FrameLayout>(R.id.ad_slot)
NativeAdHelper.forPlacement(this, this, AppAdPlacement.NATIVE_HOME, container)
```

placement का waterfall, `isEnable`, `enable_ua_check` और CTA style SDK खुद तय करता है। Template बदलने के लिए `layoutId` दें; default `com.ads.module.R.layout.custom_native_admob_medium` है (media नहीं — media चाहिए तो `custom_native_admob_free_size` इस्तेमाल करें)। Custom layout में `NativeAdView` root, `ad_container`, `block_icon_headline`, asset IDs और Ad badge बने रहने चाहिए।

हर slot/view के लिए एक helper रखें और नया ad request व bind करने के लिए `show()` बुलाएँ। Fragment अपनी Activity के साथ `viewLifecycleOwner` देता है। Click reload default रूप से चालू है; placement की key पर `"click_action": "none"` तभी रखें जब click-return पर आपकी app खुद कहीं और navigate करती हो।

Main के लिए preload: `SplashActivity.onRemoteFetched()` में `NativeAdManager.preload(applicationContext, AppAdPlacement.NATIVE_HOME, NativeAdConfig.forPlacement(AppAdPlacement.NATIVE_HOME, layoutRes))`। उसी placement का helper दिखाते समय वह ad उठा लेता है; 60 मिनट से पुराना ad दोबारा लोड होता है। [Native preload](../ads/README.md#native-preload-repeated-show-and-refresh) देखें।

Native helper व्यवहार:

- Placement वाला helper (`forPlacement`, या `NativeAdHelper` constructor का `placement` argument) उसी placement के store से bind और refill करता है, इसलिए ऊपर की तरह उसी placement key से preload करें। जोड़े गए unit IDs के तहत preload किया गया ad (`NativeAdPreload.getInstance().preload(activity, config)`) इस्तेमाल नहीं होता और helper अपना request करता है, जब तक आप नीचे बताए अनुसार वह key न दें। `setEnablePreload(enabled, key)` में दी गई key placement से पहले store तय करती है, जोड़े गए unit IDs की key (`NativeAdPreload.getInstance().keyOf(config)`) भी।
- User के खरीदने, consent वापस लेने या UA gate के मना करने पर `bindAvailable()` `false` लौटाता है और helper को cancel करता है; screen पर दिख रहा ad हटा दिया जाता है। बिना इस्तेमाल हुआ preloaded ad store में रहता है।
- Ad click से लौटने पर helper click के समय लोड हुआ replacement bind करता है या उसका इंतज़ार करता है; अगर वह replacement fail हुआ तो मौजूदा ad रहता है और दूसरा request नहीं होता। Rotation जैसे configuration change के बाद helper नया request किए बिना अपना ad वापस लाता है, या जिस load का इंतज़ार कर रहा था उसमें join करता है; no fill पर खत्म हुआ slot खाली ही रहता है।

</details>

### App की अपनी screens में interstitial, placement constant के साथ

<details>
<summary>App screens के लिए interstitial preload/show का उदाहरण खोलें</summary>

consent के बाद preload करें और नए navigation के मौके पर show करें। यह नमूना या AutoBuffer `inter_splash` या `inter_after_ob3` पर लागू न करें; वे OB के हैं।

```kotlin
import com.ads.module.helper.interstitial.InterstitialAdManager

InterstitialAdManager.load(applicationContext, AppAdPlacement.INTER_BACK)

InterstitialAdManager.show(this, AppAdPlacement.INTER_BACK) { goNext() }
```

सिर्फ navigation चाहिए तो lambda रूप इस्तेमाल करें; साथ में `onShowed`/`onClosed`/`onSkipped`/`onClicked` भी चाहिए तो `InterShowCallback` वाला overload लें। App को कोई wrapper file नहीं चाहिए।

placement का waterfall, `isEnable`, `enable_ua_check`, consent/premium, interval और readiness SDK खुद तय करता है। Navigate सिर्फ `onComplete` से करें; यह ठीक एक बार चलता है, ad न होने या show fail होने पर भी। `onClosed` इस्तेमाल न करें और पहले से `canShow()` न जाँचें। load चल रहा हो या ad cache में हो तो `load` दोबारा request नहीं करता। Ad तैयार हो तो show से पहले लगभग 800 ms का dialog चलता है।

SDK का default `AfterDismiss` है। कॉपी किया गया `PartnerApp`, `ERainTuning.install()` से `UnderAd` चुनता है: ad दिखते समय navigation चलता है। Host बंद करना हो या camera/audio/video शुरू करना हो तो `nextAction = InterNextAction.AfterDismiss` दें। यह स्थापित timing बनाए रखता है।

Interstitial अपने आप तैयार रखने के लिए: [InterstitialAutoBuffer](../ads/README.md#automatic-interstitial-preload) — `ERainAd.init` के बाद `configure`, पहली content स्क्रीन पर `start`; show ऊपर की तरह। अंतराल और click cap [defaults तालिका](#flow-के-defaults) में हैं।

</details>

### App स्क्रीन पर reward

Consent के बाद resumed Activity से main thread पर बुलाएँ; जैसे ad देखने वाले button पर:

```kotlin
import com.ads.module.helper.reward.RewardAdManager

RewardAdManager.loadAndShow(
    this, AppAdPlacement.REWARD_EXAMPLE,
    onSuccess = {
        closeLoading()
        grantReward()
    },
    onFailed = { closeLoading() },
)
```

या पहले preload करें और button click पर सिर्फ तैयार ad दिखाएँ:

```kotlin
RewardAdManager.preload(applicationContext, AppAdPlacement.REWARD_EXAMPLE)

// Ad देखने वाले button पर:
RewardAdManager.show(this, AppAdPlacement.REWARD_EXAMPLE) { earned ->
    if (earned) grantReward()
}
```

`preload` और `load` हर placement के लिए एक ही cache/request इस्तेमाल करते हैं। `show` तैयार ad लेता है; `loadAndShow` cache इस्तेमाल करता है, चल रही request का इंतज़ार करता है, या दोनों न होने पर load शुरू करता है। Automatic refill नहीं होता। Reward मिलने और ad बंद होने के बाद `onSuccess` चलता है; बाकी परिणाम `onFailed` में आते हैं। परिणाम SDK callback से लें, timer से अनुमान न लगाएँ।

Defaults: हर load tier के लिए 30 सेकंड, हर placement पर एक cache/request, automatic refill नहीं। तैयार ad न हो तो `show` में `false` मिलता है; उसी placement का `loadAndShow` waiting/showing के दौरान दोबारा बुलाने पर `onFailed` चलता है। Lambda/Runnable का परिणाम **close से पहले** मिले reward पर तय होता है। Close के बाद आने वाले mediation reward सहित अलग-अलग events के लिए [`RewardShowCallback`](../ads/src/main/java/com/ads/module/helper/reward/RewardAdManager.kt) इस्तेमाल करें; पूरा हो चुका परिणाम बदला नहीं जाता।

### अतिरिक्त integrations

| जरूरत | Default / कैसे configure करें |
| --- | --- |
| कोई slot बंद करना | placement key पर `isEnable: false` हर format के लिए उसका पूरा waterfall बंद करता है; asset बदलकर टेस्ट करने के लिए restart चाहिए। दूसरे LFO native का fallback कदम 4 से आता है। |
| Waterfall floors जोड़ना | Key के `ids` में `{"id", "isEnable"}` floors, सबसे ऊँचा पहले, all-price आख़िर में ([format](#waterfall-हर-placement-की-एक-key))। Splash banner बाकी placements की तरह `banner_splash` के हर floor से गुज़रता है। |
| CTA / native style | हर field example जैसी रखें; [JSON field तालिका](#नमूना-json-के-fields) values और उनके लागू होने की जगह बताती है। कदम 4 `templateId` और `components` को native templates से पहले ही जोड़ चुका है। |
| App स्क्रीन का banner | `BannerAdHelper.forPlacement(this, this, "banner_home", container)`; `bannerType` argument जोड़ें, उदाहरण के लिए `BannerType.Collapsible()` ([types](../ads/src/main/java/com/ads/module/helper/banner/BannerType.kt))। Type बदलने के लिए: `flagUserEnableReload = false`, पुराना helper `cancel()` करें, फिर नया बनाएँ। SDK refresh default में चालू है और `reloadIntervalSeconds` उसका interval तय करता है, इसलिए double refresh से बचने के लिए हर floor के ad unit पर AdMob console refresh बंद करें; SDK refresh बंद करने के लिए [`banner.reload.*`](remote-settings.hi.md#behavior-scopes) देखें। |
| UA / Adjust | resources भरें और [कदम 5](#adjust-token-और-verification) वाली wiring इस्तेमाल करें। `enable_ua_check` example की values रखता है; कहाँ लागू होता है यह JSON field तालिका में देखें। |
| Firebase से JSON | [तीन String parameters publish करें](firebase-integration.hi.md#remote-json): `ad_remote_config`, `ad_behavior_config`, `onboarding_config`। Assets के बाद एक बार `FirebaseAdConfigSource()` install करें; SDK splash refresh करता है। Backend के `ad_remote_config` या आपकी `ad_config.json` में declare हुई key code में लिखी ad unit IDs से ऊपर है। [Custom local fallback](firebase-integration.hi.md#local-defaults) वैकल्पिक है। |
| Firebase Analytics | `suite-firebase` जोड़ें और `Tracker.install` के तुरंत बाद `Tracker.addSink(FirebaseSink())` register करें; consent policy [Firebase गाइड](firebase-integration.hi.md#शुरुआती-consent) से चुनें। |
| लौटने पर ads | `app_resume.type` `native_resume` (default), `inter_resume`, `open_resume` या `none` चुनता है; उस type की `ad_config` key असली IDs के साथ चालू होनी चाहिए। `disableAppResume()` हर type बंद करता है। [लौटने पर ads](#ads-on-return) देखें। |
| premium / paywall वाली app | [BillingKit](billing-integration.hi.md) / [PayKit](paywall-integration.hi.md) और [साथ चलने वाला billing hook](../onboardkitorigin/README.hi.md#वैकल्पिक-integrations) करें। `onInitBilling()` default रूप से खाली है; billing install बुलाने का मतलब यह नहीं कि premium restore हो चुका है। |
| Settings से भाषा बदलना | `registerForActivityResult(StartActivityForResult())`, फिर `launch(ObLanguageActivity.intentFor(activity, LanguageScreenMode.SETTINGS))` (types `io.onboardkit.ui.language` में हैं)। `RESULT_OK` पर: `ObLanguageActivity.RESULT_LANGUAGE_CODE` पढ़ें, कदम 5 की तरह सहेजें और `recreate()` बुलाएँ; Back कोई code नहीं लौटाता। इस स्क्रीन पर ads नहीं हैं। `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)` कोई result नहीं लौटाता; पसंद `OnboardingSdk.selectedLanguage()` से पढ़ें। |
| Notification/widget entries | `SplashEntry` इस्तेमाल करें और अपने listener में passthrough बनाए रखें; [OnboardKit](../onboardkitorigin/README.hi.md#वैकल्पिक-integrations) देखें। सामान्य launcher start के लिए इनमें से कोई entry नहीं चाहिए। |

कदम 4 की live placement bindings के साथ remote JSON के लिए अतिरिक्त splash override आवश्यक नहीं:

```kotlin
package com.example.app

import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity()
```

Splash consent, remote refresh और billing साथ शुरू करता है। Consent पूरा होते ही banner/native slot और interstitial मौजूदा configuration और entitlement से request होते हैं; remote या billing का इंतज़ार नहीं होता। Cache या fetch से मिले remote values asset से ऊपर रहते हैं। SDK-owned refresh splash बंद होने के बाद भी चलता है, background wait कम-से-कम 60 सेकंड है। बाद के reads नई values लेते हैं; पहले भेजे requests, timers और तय navigation दोबारा नहीं चलते। `SAME_TIME` और `ALTERNATE` दोनों यही क्रम अपनाते हैं। `onRemoteFetched()` केवल जीवित splash पर चलता है; process-owned integration के लिए `SettingsRegistry.addFetchListener` इस्तेमाल करें।

### Ads on return

<details>
<summary>लौटने पर ads चालू करने के कदम खोलें</summary>

App के `open_resume.app_resume_load_delay_ms` (default 2000 ms) तक background में रहने पर SDK चुने गए type का ad request करता है; इससे जल्दी लौटने पर न request होता है, न कुछ दिखता है। जब कोई `SHARED_POOL` run return ads को route करता है (flow के दौरान और उसी process में flow के बाद), तब pool की resume screen इसकी जगह `native_flow.pool.resume.min_background_ms` (default 2000 ms) इस्तेमाल करती है; देखें [SHARED_POOL native flow](#shared_pool-native-flow)। `ad_behavior_config` में `app_resume.type` ठीक एक type चुनता है, default `native_resume`:

| `app_resume.type` | `ad_config` key | लौटने पर |
|---|---|---|
| `native_resume` | `native_resume` | तुरंत native resume screen खुलती है: एक image, Start button वाला text card, और नीचे ad; ad आने तक skeleton दिखता है। `inter_resume` declare और चालू हो तो Start उसे दिखाता है (loading dialog के साथ load-and-show, `interstitial.load_and_show.wait_timeout_ms`) और ad बंद होने पर screen बंद करता है; key बंद या undeclared हो, या ad न दिखे, तो Start screen तुरंत बंद करता है। Back बिना ad के बंद करता है। दोनों user को उसी screen पर लौटाते हैं जहाँ वह था। दिखाया गया ad रखा जाता है: अगली वापसी पर वही ad तुरंत दिखता है, और background में request हुआ ad आते ही उसकी जगह ले लेता है। |
| `inter_resume` | `inter_resume` | Loading dialog के साथ load-and-show, ज़्यादा से ज़्यादा `interstitial.load_and_show.wait_timeout_ms` (default 8000 ms) तक इंतज़ार। |
| `open_resume` | `open_resume` | App-open। |
| `none` | — | कुछ नहीं। |

चुने गए type की key बंद (`isEnable: false`) या undeclared हो तो वापसी पर कोई ad नहीं दिखता; SDK किसी दूसरे type पर नहीं जाता। Type बदलने के लिए `app_resume.type` बदलें।

1. **कदम 4:** `AdsConfig.fromAdConfig()` पहले ही `native_resume`, `inter_resume` (resume screen का Start) और `open_resume` bind करता है। Manual `AdsConfig(...)` में `resumeNative = NativeAdUnit(...)` / `resumeInterstitial = InterstitialAdUnit(...)` / `appResume = InterstitialAdUnit(...)` खुद दें, जब तक `ad_config.json` या backend का `ad_remote_config` वह key declare न करे।
2. **सिर्फ `open_resume` के लिए**, कदम 5, `ERainAdConfig` के `apply` block में: `idAdResume = AdGate.adUnitIds(AppAdPlacement.OPEN_RESUME).firstOrNull().orEmpty()` (`AdGate` है `com.ads.module.helper.AdGate`)। `ad_config.json` या backend के `ad_remote_config` में declare हुए `open_resume` को seed नहीं चाहिए।
3. **`ad_config`:** हर उस type की key declare करें जिसे आप चुन सकते हैं, `ids` में **असली IDs के साथ चालू**। `native_resume` native fields लेता है (`templateId`, `components`, `click_action`, CTA रंग; default template 1 और `reload`)।
4. **बाहर जाने वाले intents** (browser/share/review): उस वापसी को छोड़ने के लिए `startActivity(...)` के तुरंत बाद `AppOpenManager.getInstance().disableAdResumeByClickAction()` बुलाएँ, type कोई भी हो। `disableAppResume()`/`enableAppResume()` हर type के लिए पूरे process के switches हैं।

Screen exclusions हर type पर लागू होते हैं। Splash, OB5, Welcome Back और native resume screen खुद को बाहर रखते हैं, इसलिए resume screen पर लौटने से कुछ नहीं खुलता; सिर्फ अपनी app की संवेदनशील screens `disableAppResumeWithActivity` से register करें। LFO और OB की content pages लौटने पर resume ads दिखा सकती हैं, सिवाय fullscreen page और onboarding के आखिरी page के, page transitions के दौरान, popup खुले रहने पर और onboarding ad पर click के बाद (जब तक remote `app_open.presentation.skip_after_ad_click` को `false` न करे)।

**Native resume screen.** इसकी image और text `onboardKitConfig { resumeScreen = ResumeScreenConfig(titleRes = …, subtitleRes = …, imageRes = …) }` में दें, जैसे OB steps declare होते हैं; `title`/`subtitle` एक `CharSequence` लेते हैं जो resource से ऊपर है, और जो field न दें वह SDK default रखता है। Start का text color `onboarding_config` में `native_resume.start_button.color` है (`""` layout का color रखता है); नाम बदलने के लिए `ob_start` string override करें। दूसरे layout के लिए `res/layout/ob_resume_screen.xml` override करें। केवल `ob_resume_ad` (`FrameLayout`) और एक Start Button/TextView जरूरी हैं (बेहतर है `@id/ob_resume_start` रखें); image, title, subtitle और `ob_resume_ad_block` वैकल्पिक हैं। Request, bind और close logic SDK रखता है। `native_resume.start_button.delay_ms` दोनों flows में Start दिखने का समय तय करता है।

App-open के delay और gating के लिए [app-open](../onboardkitorigin/README.hi.md#app-open-on-return) देखें।

</details>

### SHARED_POOL native flow

<details>
<summary>SHARED_POOL guide खोलें</summary>

`native_flow.type` तय करता है कि splash के बाद कौन सा flow चले। `PER_SCREEN`, default, वही flow है जो इस guide का बाकी हिस्सा बताता है। `SHARED_POOL` DashDrama 1.0.6 का first-open flow दोहराता है, यानी उस reference app का जिस पर pool बना है: LFO → onboarding pager → Goal, Goal से पहले SDK के Privacy step के साथ (Privacy और Goal केवल चालू होने पर)। ये stages SDK की एक activity में चलते हैं, और उनका हर native slot पूरे process में साझा एक pool से ad लेता है। Splash दोनों flows में एक जैसा है, बस `SHARED_POOL` run के लिए वह LFO1 preload नहीं करता (नीचे LFO देखें)। Keys, precedence, `ob_native_reuse_enabled` alias, `ob_native_flow` user property और हर `native_flow.pool` default [Native flow type](remote-settings.hi.md#native-flow-type) में हैं; यह section बताता है कि flow क्या करता है।

**Pool कैसे तय करता है।** Pool हर placement के लिए अधिकतम एक ad रखता है और उसे एक screen से दूसरी screen तक रखता है।

- **Owner screen.** सामने वाली screen pool से अपनी placement माँगती है और owner बन जाती है, जब तक placement पूरे run के लिए refuse न हो (premium, ads या placement बंद, ad unit नहीं, UA gate)। अस्थायी refusal (offline, consent का जवाब अभी नहीं, requests रुकी हुई) पर भी screen owner बनती है।
- **POLITE और FORCED loads.** Screen खोलना, page चुनना और screen पर लौटना POLITE loads हैं: pool में कोई request चल रही हो तो ये छोड़ दिए जाते हैं, और छोड़ा गया load queue नहीं होता, हट जाता है (`parity.retry_skipped_polite`)। Language tap, Privacy या Goal पर selection और अगले full page का preload FORCED loads हैं: ये gate को नज़रअंदाज़ करते हैं। Full page का preload FORCED है क्योंकि वह मौजूदा page की अपनी request के साथ उसी पल माँगा जाता है, जो POLITE होने पर हर बार उसके लिए gate बंद कर देती; यह owner कभी नहीं बदलता। दोनों में, जिस placement के पास पहले से ad है या जो load हो रही है, वह कुछ request नहीं करती।
- **Single-flight gate.** Gate तभी खुला रहता है जब किसी placement की request न चल रही हो, इसलिए दूसरी request चलते समय POLITE load शुरू नहीं होता। यह जाँच main thread से बाहर होती है और एक frame पीछे रह सकती है (`parity.async_gate`)। FORCED loads, लौटने पर होने वाले refresh और splash से लिया गया load gate को नज़रअंदाज़ करते हैं; resume screen का refresh अपवाद है और POLITE load की तरह gate का इंतज़ार करता है। Refresh चल रही request गिना जाता है।
- **Selector.** हर slot एक ही चुनाव दिखाता है: (1) owner का ad, जब भी pool के पास उसके लिए ad हो; (2) नहीं तो सबसे नया ad जो किसी screen ने अभी तक नहीं दिखाया; (3) नहीं तो सबसे हाल में दिखाया गया ad; (4) नहीं तो request चलते समय shimmer, और कोई request न हो तो slot छिपा रहता है। जिस screen का अपना ad अभी नहीं आया, वह किसी दूसरी placement का ad उधार लेती है, और उसका अपना ad fill होते ही उधार वाले की जगह ले लेता है।
- **लौटने पर refresh.** जब कोई screen फिर सामने आती है (background, ad click या resume screen से), वह अपने owned ad को पहले floor से फिर request करती है जबकि वह ad दिखता रहता है: fill उसे बदल देता है, no-fill उसे रखता है। यह SDK का refresh model है, DashDrama का नहीं, जिसमें refresh चल रही request नहीं गिना जाता था।
- **Expiry और floor का wait.** दोनों SDK की values हैं, pool keys नहीं: `native.cache.max_age_ms` (एक घंटा) से पुराना ad हटा दिया जाता है और अगली बार कोई screen वह placement माँगे तो फिर request होता है, और जो floor `native.load.tier_timeout_ms` (30 सेकंड) में जवाब न दे वह failure गिना जाता है, इसलिए अगला floor request होता है।
- **Floors.** Request key के `ids` के चालू floors को क्रम से चलती है। दोहराई गई ID no-fill के बाद फिर request होती है, `PER_SCREEN` से अलग, जो duplicates हटा देता है।

**इसे चालू करें।**

- `onboarding_config` में, remote या app asset में, `native_flow.type` को `"SHARED_POOL"` करें, या flat key `ob_native_reuse_enabled = true` भेजें। [Native flow type](remote-settings.hi.md#native-flow-type) precedence समझाता है और यह भी कि जिस नए install का पहला fetch नहीं पहुँचा वह asset या SDK default क्यों चलाता है।
- Built-in `adProvider = ERainAdProvider()` रखें; इसके बिना हर run `PER_SCREEN` होता है।
- Type हर run में एक बार तय होता है: splash पहले पूछता है, यह जानने के लिए कि LFO1 preload करना है या नहीं, और language screen पर handoff वही जवाब इस्तेमाल करता है, भले ही बीच में कोई remote fetch आ जाए। हर `native_flow.pool` value तब पढ़ी जाती है जब splash language screen खोलता है। दोनों उस run के लिए बने रहते हैं; बाद में fetch हुई value अगले run से लागू होती है। Language screen खोलने वाला हर run user property `ob_native_flow` set करता है और `OB_FLOW` में `native_flow_type=…` log करता है।
- Code बदलने की ज़रूरत नहीं: flow वही `ad_config` keys, `OnboardKitSetup`, content layouts और Privacy/Goal layouts इस्तेमाल करता है जो `PER_SCREEN` करता है। Welcome Back और app के अंदर का language picker `PER_SCREEN` ही रहते हैं।

**हर screen पर।** Placements को [कदम 2](#2-ids-और-दो-json-files-जोड़ें) की `ad_config` key से नाम दिया गया है।

| Screen | Placement | कब request करती है | व्यवहार |
| --- | --- | --- | --- |
| LFO | पहले tap तक `native_lang`, फिर `native_lang_alt`; confirmation popup खुला रहने पर `native_popup_lang` | खुलने पर: POLITE `native_lang`। `SHARED_POOL` run के लिए splash LFO1 preload नहीं करता, इसलिए LFO उसे खुद request करती है, जैसे DashDrama करता है; अगर LFO1 फिर भी preload हुआ हो, तो LFO पहले वह preload ले लेती है (तैयार ad बिना नई request के दिखता है, चल रहा load join होता है, queue में रुका load cancel होता है), और खुलने के ठीक बाद का resume splash से लिया ad refresh नहीं करता जब तक वह दिखा न हो। हर language tap: FORCED `native_lang_alt`। लौटने पर: मौजूदा key का refresh, फिर उसी key पर POLITE। | सहेजी गई भाषा पहले से चुनी रहती है; नहीं तो कुछ नहीं चुना जाता। Confirm button पहले tap के `native_flow.pool.lfo.confirm_button.reveal_delay_ms` (3 सेकंड) बाद तक छिपा रहता है। `lfo.exit.reuse_splash_inter` `true` हो तो Done splash interstitial का बचा हुआ ad दिखाता है; उसके बंद होने के बाद (`native_flow.pool.exit.next_screen_timing`, `AFTER_AD`) भाषा सहेजी और लागू होती है (`native_flow.pool.locale_apply`, default रूप से उसी screen पर), पहला page full page हो तो उसका ad FORCED preload होता है, और अगला stage खुलता है; content page, Privacy या Goal खुलने पर अपना ad खुद माँगते हैं। पूरे run के लिए refuse हुआ `native_lang` slot छिपाता है। नई बार खुलने पर `native_flow.pool.lfo.loading_dialog.duration_ms` (default `0`, बंद) header और rows पर progress bar वाला "Loading languages" card रखता है, ad पर कभी नहीं; उसके खत्म होने तक rows tap नहीं लेतीं। App के background में रहने पर countdown रुका रहता है और recreate के बाद भी बना रहता है; OB1 back पर यह फिर नहीं दिखता। |
| Pager content page | Page का `native_obN`; जिस page की key बंद है उसका slot नहीं होता | Page चुनने पर: page की key पर POLITE, साथ में अगले page की key का FORCED preload, अगर अगला page full page हो। Pager फिर सामने आने पर: owner की key का refresh, फिर मौजूदा page की key पर POLITE। | Page जब screen से बाहर lay out होता है, तब उसका slot ad template का स्थिर (बिना animation) skeleton दिखाता है, ताकि page अपने अंतिम layout में slide होकर आए, न कि slide के बाद ad block शून्य ऊँचाई से बढ़े; असली ad page resume होने पर bind होता है। Pages केवल page के अपने controls से बदलते हैं; swipe locked है। |
| Full page | Page का `native_fullN` | Content page की तरह। केवल full pages एक screen पहले preload होते हैं, FORCED के रूप में (पिछले page की अपनी request के साथ ही माँगे जाने के कारण POLITE preload हमेशा gate से छूट जाता; full page के पास पहले से ad हो या load हो रहा हो तो यह कुछ request नहीं करता, और owner कभी नहीं बदलता): जब उससे पहले वाला page चुना जाता है, या LFO के Done पर जब full page सबसे पहले हो। वह preload अभी load हो रहा हो और page पर पहुँचें, तो page कोई अतिरिक्त request नहीं भेजता (उसका claim gate के कारण छूट जाता है) और अपना fill आने तक pool का सबसे नया ad दिखाता है। | Page के पहले resume से `native_flow.pool.onboarding.fullscreen.skip.delay_ms` (2 सेकंड) बाद X दिखता है; page चुने जाने के `native_flow.pool.onboarding.fullscreen.auto_next.delay_ms` (10 सेकंड) बाद खुद आगे बढ़ता है, background का समय भी गिना जाता है। दिखाने को कुछ न हो और कोई request न चल रही हो, या इसकी key पूरे run के लिए refuse हो, तो page तुरंत आगे बढ़ता है। लौटने पर ads इसके ऊपर कभी नहीं खुलते। |
| Privacy | खुलने पर `native_select`, हर consent बदलाव पर `native_select_alt` | खुलने पर: POLITE `native_select`। हर check या uncheck: FORCED `native_select_alt`। लौटने पर: owned key का refresh, फिर आखिरी माँगी गई key पर POLITE। | Continue पहले consent बदलाव के `native_flow.pool.select.next_reveal_delay_ms` (3 सेकंड) बाद तक छिपा रहता है और केवल consent checked रहने पर काम करता है। यह Goal खोलता है, जो खुलने पर अपना ad खुद POLITE माँगता है। विवरण: [Privacy → Goal](privacy-goals-screen.hi.md#shared_pool-में)। |
| Goal | खुलने पर `native_select`, हर option tap पर `native_select_alt` | Privacy की तरह, selection यहाँ option tap है। | Next पहले tap के बाद उसी delay तक छिपा रहता है और कम से कम `minSelection` options चुने रहने पर काम करता है। यह goals दर्ज करता है, `native_flow.pool.goals.exit_reuse_splash_inter` `true` हो तो splash interstitial का बचा हुआ ad दिखाता है, फिर बिना paywall के onboarding पूरा करता है। |
| Resume screen | `native_resume` | हर request POLITE है और कभी gate नहीं लांघती। खुलने पर और हर बार लौटने पर: रखे हुए `native_resume` का refresh, केवल तब जब pool की कोई दूसरी request न चल रही हो (वरना `gate_skip refresh key=native_resume` log होता है); refresh न हो तो POLITE `native_resume`। | `app_resume.type` `native_resume` हो और app कम से कम `native_flow.pool.resume.min_background_ms` (2 सेकंड) background में रहा हो, तो लौटने पर खुलती है, flow के दौरान और उसी process में flow के बाद भी। यह कभी owner नहीं बनती, इसलिए बाकी हर slot जैसा ही चुनाव दिखाती है: flow के दौरान आमतौर पर मौजूदा screen का ad, flow के बाद सबसे नया बचा हुआ ad जो अभी तक नहीं दिखा, नहीं तो सबसे हाल में दिखाया गया ad। Ad landing page पर रहते समय रखा हुआ ad pause रहता है; लौटने पर वही ad फिर draw होता है और replacement request शुरू नहीं होती। Start classic screen की तरह `inter_resume` से होकर जाता है; Back इसे बंद करता है। |

Privacy → Goal के बिना pager का आखिरी page flow खत्म करता है, paywall wired हो तो उसके साथ, और कभी exit interstitial नहीं दिखाता। किसी भी stage पर system Back कुछ नहीं करता।

**`PER_SCREEN` से जो नहीं लिया गया।** Splash का LFO1 preload, LFO tap hint और device-language promotion, LFO2 swap और उसके timers, `click_action` (native click न navigate करता है न reload), OB5, pager swipe, pager के बाद का exit interstitial (`inter_after_ob3`, `onboarding.exit_interstitial.enabled` कुछ भी कहे) और classic fullscreen delays (`onboarding.fullscreen.skip.delay_ms`, `onboarding.fullscreen.auto_next.delay_ms`)। SDK की दो features अपनी classic keys के साथ रखी गई हैं:

- भाषा confirmation popup (`lfo.confirm_dialog.enabled`, `lfo.confirm_dialog.show_from_tap`, चुनी हुई row पर दोबारा tap)। खुला रहने पर popup `native_popup_lang` के साथ pool का owner होता है; बंद होने पर ownership LFO को लौटती है।
- OB1 back button (`onboarding.steps.ob1.back.enabled`, OB1 पहला page)। यह चुनी हुई भाषा रखते हुए LFO पर लौटाता है, confirm button तुरंत दिखता है और slot `native_lang_alt` पर रहता है।

**Privacy → Goal.** दोनों flows में default रूप से बंद (`privacy_goals_screen.enabled = false`), और इसके लिए `goal.options` चाहिए। DashDrama का first-open flow उसकी goal screen पर खत्म होता है, इसलिए parity के लिए इसे चालू करना होगा; इसके बिना pooled flow pager के बाद खत्म होता है। `PER_SCREEN` की तरह Privacy, Goal के साथ आती है, हालाँकि उस flow में pager और goal screen के बीच कोई step नहीं है। [Privacy → Goal](privacy-goals-screen.hi.md#shared_pool-में) देखें।

**Ads on return.** Pool की resume screen के लिए `app_resume.type = native_resume` (default) और `ad_config` में declared और चालू `native_resume` चाहिए; दूसरे type के साथ return ads उसी type के अनुसार चलते हैं, जैसा [Ads on return](#ads-on-return) में बताया गया है। यह तभी खुलती है जब app कम से कम `native_flow.pool.resume.min_background_ms` (default 2000 ms) background में रहा हो; classic native resume screen `open_resume.app_resume_load_delay_ms` (sample में 2000 ms) ही रखती है। Flow के बाद pool अपने ads process खत्म होने तक रखता है, इसलिए resume screen, यानी DashDrama का welcome back, onboarding के बचे हुए ads दिखाती है, हर ad अपने fill के बाद अधिकतम एक घंटे तक, और `native_resume` केवल खुली रहने पर, हमेशा POLITE, request करती है। यह process death या उसी process में बाद के किसी `PER_SCREEN` run तक चलता है; अगला cold start सामान्य resume screen इस्तेमाल करता है। इसका SDK की Welcome Back screen से कोई संबंध नहीं।

**Purchases.** User premium होते ही हर pooled slot छिप जाता है और pool request करना बंद कर देता है, पहले से चल रही chain का अगला floor भी। Pool के पास पहले से रखे ads purchase के समय तभी release होते हैं जब host [BillingKit](billing-integration.hi.md) की तरह `AdGate.installPremiumObserver(appScope, Billing.isPremium)` बुलाता है। इसके बिना वे memory में रहते हैं, कभी दिखते नहीं, flow खत्म होने तक; flow के बाद हुई purchase में तब तक, जब तक वे अपने fill के एक घंटे बाद expire न हों, कोई `PER_SCREEN` run pool release न करे, या process खत्म न हो।

**Revenue और reports.** Revenue ad unit के स्तर पर attribute होता है: हर floor से पहले pool उस ad unit ID को request करने वाली placement के नाम register करता है, और paid event placement को ID से ढूँढता है। इसलिए उधार लिया गया ad उस placement के नाम report होता है जिसने उसे load किया, उस screen के नाम नहीं जिसने उसे दिखाया। कई placements में साझा ID (Privacy और Goal दोनों `native_select` इस्तेमाल करते हैं; sample हर format के लिए एक test ID साझा करता है) उस placement के नाम report होती है जिसने उसे सबसे बाद में request किया, जिससे per-placement reports बिगड़ती हैं; हर placement को अपनी IDs दें। `ad_request` हर नई load chain पर एक बार भेजा जाता है (लौटने पर होने वाला refresh कुछ नहीं भेजता), हर slot पर नहीं। बिना ad के ख़त्म होने वाली chain बिना ad unit ID के एक `ad_load_failed` भेजती है; fail हुआ refresh कुछ नहीं भेजता, क्योंकि पुराना ad बना रहता है। Gate से छोड़ा गया POLITE load `ad_skipped` नहीं भेजता। Dashboards को `ob_native_flow` से बाँटें।

**DashDrama के 4-page shape के साथ QA.**

1. इसे `app/src/main/assets/onboarding_config.json` में रखें और code में `goal.options` दें ([Privacy → Goal](privacy-goals-screen.hi.md)):

   ```json
   {
     "native_flow": { "type": "SHARED_POOL" },
     "onboarding": { "order": ["ob1", "ob2", "full1", "ob3"] },
     "privacy_goals_screen": { "enabled": true }
   }
   ```

   `privacy_goals_screen.enabled` को `true` रखें: SDK default `false` है। अपने ही pages पर pool आज़माने के लिए asset में केवल `{"native_flow":{"type":"SHARED_POOL"}}` चाहिए। Remote `native_flow.type` asset से ऊपर है।
2. `ad_config.json` में `native_ob1` और `native_full1` चालू करें (sample में बंद हैं) और `native_ob2` व `native_ob3` पर `"isEnable": false` रखें। इन दो keys को delete न करें: बंद key अपने page को बिना slot के छोड़ती है, जबकि delete की गई key उस unit पर लौटती है जो आपका `AdsConfig` code में set करता है (जैसे `contentStepNative`)। `native_lang`, `native_lang_alt` और `native_resume` चालू रखें, और `native_select` व `native_select_alt` को [Privacy → Goal](privacy-goals-screen.hi.md#ads-और-completion) की तरह जोड़ें (sample इन्हें declare नहीं करता), हर एक के साथ `ad_config_debug.json` में एक test `ids` floor।
3. App data clear करें और process restart करें, फिर LFO → OB1 → OB2 → Full1 → OB3 → Privacy → Goal → आपकी app तक चलें। OB2 और OB3 पर कोई ad नहीं दिखता।

**Log पढ़ना।** `OnboardingSdk.setFlowLogging(true)` बुलाएँ और Logcat को `OB_FLOW` पर filter करें। Pool lines ऐसी दिखती हैं: `[   +1234] LOAD   | pool | request key=language1 tier=0 refresh=false`; `key` pool key है (`language1`, `language2`, `language_confirm`, `step_ob1`, `fullscreen_full1`, `step_partner_privacy`, `step_partner_goal_alt`, `native_resume`…) और `tier` key के `ids` में 0 से गिनी गई position है।

| `pool \|` के बाद की line | अर्थ |
| --- | --- |
| `activate` | एक `SHARED_POOL` run शुरू हुआ। `classic_run` का अर्थ है कि एक `PER_SCREEN` run ने pool release किया; `deactivate` का अर्थ है कि `OnboardingSdk.reset()` ने किया। |
| `flow stage=…`, `stage -> …`, `ob1 back -> language` | दिख रहा stage, stage बदलाव, OB1 back button। |
| `adopt language1 status=…` | LFO ने splash का LFO1 preload लिया या नहीं। आमतौर पर `none`, क्योंकि SDK का splash `SHARED_POOL` run के लिए LFO1 preload नहीं करता। अगर LFO1 फिर भी preload हुआ हो: `ready` (उसका ad ले लिया गया), `pending` (उसका चल रहा load join हुआ), `cancelled_queue` (queue में रुका load cancel हुआ और LFO अपना request करती है), `none` (splash load fail हुआ या शुरू ही नहीं हुआ)। |
| `request key=… tier=… refresh=…` | एक floor request हुआ; लौटने पर refresh के लिए `refresh=true`। |
| `fill key=… refresh=…` | Placement के पास अब नया ad है। |
| `fail key=… tier=…`, `tier_timeout key=… tier=…` | उस floor ने fill नहीं दिया या समय पर जवाब नहीं दिया; अगला floor आता है। |
| `exhausted key=… refresh=… why=…` | Chain बिना fill के खत्म हुई: `exhausted` (हर floor आज़माया), `refused reason=…` (chain के बीच कोई guard बंद हुआ), `adoption_failed`। Refresh अपना रखा ad रखता है। |
| `gate_skip key=…` | दूसरी request चलने के कारण POLITE load छोड़ा गया; कोई analytics event नहीं भेजा जाता। |
| `gate_skip refresh key=native_resume` | दूसरी pool request चलने के कारण resume screen ने अपना रखा हुआ ad refresh नहीं किया; उसके बाद की POLITE `native_resume` request भी उसी gate के अधीन है। कोई analytics event नहीं भेजा जाता। |
| `config_skip key=… reason=…` | पूरे run के लिए refuse (`premium`, `no_provider`, `ads_off_remote`, `ads_off_config`, `placement_off_remote`, `no_ad_unit`, `ua_gate`); screen owner नहीं बनती। |
| `transient_skip key=… reason=…` | अभी के लिए refuse (`requests_held`, `offline`, `consent_not_granted`); screen फिर भी owner बनती है। |
| `refresh_skip key=… reason=…` | उन्हीं guards ने refresh refuse किया; रखा हुआ ad बना रहता है। |
| `expired key=…` | रखा ad `native.cache.max_age_ms` पार कर गया और हटाया गया। |
| `stale_fill key=…` | Floor का timeout होने या pool release होने के बाद fill आया; उसे बिना दिखाए destroy किया जाता है। |
| `stuck_loading key=… refresh=… ms=…` | `stuck_loading_log_ms` के बाद भी चल रही request; केवल log। |
| `release_all loaders=…` | हर pooled ad release हुआ: purchase, एक `PER_SCREEN` run या `OnboardingSdk.reset()`। |

`OnboardingSdk.dumpState()` एक suspend function है; इसे coroutine से log करें, जैसे `lifecycleScope.launch { Log.d("OB_STATE", OnboardingSdk.dumpState()) }`। इसमें run का native flow type, pool active है या नहीं और return ads को route करता है या नहीं, run के gate skips हर key के अनुसार, owner, gate खुला है या नहीं, और हर placement की एक line होती है, जैसे `language1 #0 state=Showing(ts=…) ad=held inFlight=false refreshing=false adopted=false fillAtMs=… saved=2` (`saved` उसकी saved request में floors की संख्या है)।

**DashDrama 1.0.6 से अंतर।**

- Splash के अपने natives (नीचे का slot, `native_fs`) pool और उसके gate से बाहर रहते हैं।
- SDK का ad guard (premium, consent, network, request hold, UA gate, placement switches) हर request और हर floor से पहले जाँचा जाता है; बिना ad unit वाली LFO अपना slot छिपाती है।
- Purchase हर slot छिपाता है, pool requests रोकता है और pool के ads release करता है (`AdGate.installPremiumObserver` होने पर तुरंत, नहीं तो flow खत्म होने पर; ऊपर Purchases देखें)।
- Privacy एक SDK step है, जो select screen की तरह चलता है।
- `click_action` ignore होता है।
- Ads एक सख्ती से बढ़ते (strictly increasing) timestamp से क्रम में रहते हैं, इसलिए कभी बराबरी नहीं होती; DashDrama बराबरी map order से तय करता है।
- Language-completed events Done tap पर भेजे जाते हैं; भाषा LFO के exit ad (splash interstitial का बचा हुआ ad) के बंद होने के बाद सहेजी जाती है।
- Screen हटने पर slot का ad view destroy होता है (`view.destroy_on_teardown`); ad pool में रहता है।
- Refresh चल रही request गिना जाता है।
- Ad expiry (एक घंटा) और हर floor का wait (30 सेकंड) SDK की values हैं।
- भाषा confirmation popup और OB1 back button रखे गए हैं।

</details>

## 8. आखिरी जाँच

- [ ] Grouped settings के लिए remote override, पहली-run offline local fallback और valid remote cache का offline reuse [Firebase checklist](firebase-integration.hi.md#remote-notes) के अनुसार जाँचें।
- [ ] Debug build splash खोलता है, Logcat tag `AdRemoteConfig` में `Loaded ad_config.json with <n> placements (test ids=true)` दिखता है, जहाँ `<n>` आपके ship किए example से मेल खाता है, और `OB_FLOW` कोई config या provider error नहीं बताता।
- [ ] Remote fetch के बाद, console में edit किए गए placement के लिए `OB_FLOW` में `ad_config remote omits …` line नहीं होती। Listed key app की values रखता है, console किसी दूसरे key नाम के नीचे कुछ भी set करे: field को उसी key के नीचे publish करें, या अपना key `fromAdConfig(mapOf(...))` से bind करें।
- [ ] Test ads पर LFO → OB → MainActivity तक पूरा चलें; `native_full1/2` चालू करने के बाद FULL1, OB1 के बाद और FULL2, OB2 के बाद आता है, और आखिरी interstitial (`onboarding.exit_interstitial.enabled` से चालू करने पर; default रूप से बंद) सिर्फ SDK संभालता है। LFO splash interstitial बंद होने के बाद ही खुलता है; आखिरी interstitial चालू हो तो उसके बंद होने पर MainActivity पहले से मौजूद होती है।
- [ ] LFO: भाषा चुनकर Back दबाने पर Save दिखता है और स्क्रीन बनी रहती है; मौजूदा भाषा दोबारा चुनने पर popup तुरंत खुलता है, दूसरी भाषा configured कुल tap count पूरा होने पर खुलती है।
- [ ] Notifications मना करने पर भी flow चलता है; splash, LFO, popup और OB से Home जाकर लौटने पर दो बार navigation नहीं होता। `native_ob1..4` चालू करने के बाद, OB page पर native click करने से लौटते समय step आगे बढ़ता है; LFO/popup पर स्क्रीन बनी रहती है और तैयार होते ही replacement ad bind होता है।
- [ ] `native_ob1`, `native_ob2` और `native_full1` चालू करें, फिर `native_ob2` बंद करें: सामग्री page 2 फिर भी दिखता है और page 1 का native उधार नहीं लेता। `native_full1` फिर से बंद करें: सिर्फ ad वाला page छूट जाता है। `inter_splash` और `inter_after_ob3` बंद करें: destination स्क्रीन फिर भी मिलती है।
- [ ] `SHARED_POOL` इस्तेमाल करते हों तो `OB_FLOW` चालू रखकर [4-page QA shape](#shared_pool-native-flow) चलाएँ: log में `pool | adopt language1 status=none` और फिर LFO का अपना `pool | request key=language1` दिखता है, क्योंकि splash `SHARED_POOL` run के लिए LFO1 preload नहीं करता; OB2 और OB3 पर कोई ad नहीं दिखता, और Goal का Next onboarding पूरा करता है।
- [ ] बिना नेटवर्क टेस्ट करें: default रूप से connection prompt दिखता है; offline support चुना हो तो flow SDK के timeout पर फिर भी आगे बढ़ता है और किसी app callback पर अटकता नहीं।
- [ ] पूरा होने के बाद दोबारा खोलें: splash से होते हुए आपकी app में, OB दोबारा नहीं चलता; splash interstitial बंद होने पर आपकी स्क्रीन पहले से मौजूद होती है। First-open टेस्ट करने के लिए app data clear करें; OB के बीच app बंद करके दोबारा खोलने पर splash के बाद LFO से शुरू होना चाहिए।
- [ ] आपकी app की screens चुनी हुई भाषा इस्तेमाल करती हैं; AAB भेजते समय अनुवाद और language split configuration दोनों जाँचें।
- [ ] Release से पहले: `res/values/id_ads.xml` का `admob_app_id` और **`ad_config.json`** की हर ad unit ID अपनी app के IDs से बदलें। Debug JSON को test IDs पर ही रखें। Debug में test App ID रखना हो तो `app/src/debug/res/values/id_ads.xml` में `admob_app_id` का override जोड़ें।

| लक्षण | तुरंत जाँचें |
| --- | --- |
| खुलते ही crash | merged manifest में AdMob/Meta metadata, असली Meta credentials और आपकी Application |
| OB खाली है या छूट जाता है | `configure` से पहले `install`; अपनी app की सामग्री के लिए कदम 4 में `steps(...)` इस्तेमाल करें, और देखें कि remote cache ने flow या कोई step बंद तो नहीं किया |
| ads नहीं आते | file load हुई, key mapping, `isEnable`, consent/premium की स्थिति, remote flags; फिर भी दिखाने के लिए timer न जोड़ें |
| Debug गलत IDs इस्तेमाल करता है | `ad_config_debug.json` में हर चालू placement key के test `ids` हैं (`AdRemoteConfig` का `WARN` बिना ID वाली चालू keys बताता है); मानक setup में `setAllowRemoteOverrideInDebug(true)` चालू नहीं है |
| Remote `ad_remote_config` कभी लागू नहीं होता (`AdConfig` log में `cleared`) | Console parameter का नाम: SDK `ad_remote_config`, फिर `ads_remote_config` पढ़ता है; कोई और नाम हो तो `FirebaseAdConfigSource("<name>")` दें |
| Remote IDs बदलीं लेकिन OB पुरानी इस्तेमाल करता है | `FirebaseAdConfigSource` install करें, सही keys के साथ `AdsConfig.fromAdConfig()` इस्तेमाल करें और splash को refresh करने दें। Firebase का minimum fetch interval 12 घंटे है, जब तक आपकी app उसे कम न करे। Debug `ad_config_debug.json` की test IDs इस्तेमाल करता है; `ad_remote_config` के बाकी सभी fields और दोनों settings JSON फिर भी लागू होते हैं। |
| टेस्ट में native style/reporting हर slot के लिए अलग नहीं होती | एक ही format के demo IDs साझा हैं; provider कहीं-कहीं ID से style/placement वापस ढूँढता है। असली configuration जाँचते समय अलग IDs इस्तेमाल करें |

## आपकी app को वाकई जो files चाहिए

| File | क्या करना है |
| --- | --- |
| `settings.gradle`, `app/build.gradle` | build configuration और दोनों dependencies मिलाएँ |
| `app/src/main/AndroidManifest.xml` | Metadata, आपकी Application और splash launcher |
| `app/src/main/res/values/id_ads.xml` | AdMob App ID, Meta credentials और Adjust token/event tokens |
| `app/src/main/assets/ad_config.json`, `ad_config_debug.json` | दोनों नमूना JSON files copy करें |
| `app/src/main/assets/ad_behavior_config.json`, `onboarding_config.json` (वैकल्पिक) | Local defaults बदलने के लिए बनाएँ/copy करें; [fallback नियम](firebase-integration.hi.md#local-defaults) देखें। |
| आपकी app की `strings.xml` और drawables | चारों content pages के लिए content, अनुवाद और images |
| `AppAdPlacement.kt` | OB keys और आपकी app की अपनी keys का catalog; ad unit IDs नहीं |
| [OnboardKitSetup.kt](examples/ads-onboarding/OnboardKitSetup.kt) | App content/resources और standard ad_config placements की live bindings |
| आपकी मौजूदा Application या [PartnerApp.kt](examples/ads-onboarding/PartnerApp.kt) | SDK को एक बार initialize करती है और destination स्क्रीन चुनती है |
| `SplashActivity.kt` | SDK की splash को extend करता है |

`MainActivity` आपकी app की मौजूदा destination स्क्रीन है। `AppAdPlacement.kt` के अलावा JSON को ad config में बदलने के लिए कोई file नहीं चाहिए — हर entry point सीधे placement key लेता है। इस बुनियादी flow के लिए `AppConstants`, `RemoteConfigUtils`, DevConfig, Hilt या example की paywall/uninstall screens copy करने की जरूरत नहीं।

[Ads संदर्भ](../ads/README.md) · [OnboardKit संदर्भ](../onboardkitorigin/README.hi.md)
