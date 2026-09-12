# OnboardKit

Splash → भाषा चयन → onboarding → वैकल्पिक प्रश्न/paywall → आपकी app।
SDK स्क्रीन बदलना, ads preload करना और प्रगति सहेजना संभालता है; आपकी app सामग्री और अंतिम स्क्रीन देती है।

[English](README.md) · [Tiếng Việt](README.vi.md)

[Partner integration guides](../partner-integration/README.hi.md) · [Ads + OnboardKit walkthrough](../partner-integration/ads-onboarding-integration.hi.md)

## शुरू करने से पहले

- minSdk 24, compileSdk 36 और JDK 17 इस्तेमाल करें। [साझा build setup](../README.hi.md) पूरा करें और सभी modules में एक ही प्रकाशित tag रखें।
- Built-in ad provider के लिए पहले [ads guide](../ads/README.md) पूरा करें: AdMob/Meta manifest values, ad config assets और Application में `ERainAd.init()`।
- Funnel चाहिए तो OnboardKit से पहले `Tracker` और sink install करें; [Trackkit](../trackkit/README.hi.md) देखें।
- नीचे दोनों dependencies जोड़ें। OnboardKit, Trackkit को export करता है; `com.ads.module.*` इस्तेमाल करने वाले app code को स्पष्ट `ads` dependency चाहिए। Firebase और PayKit वैकल्पिक हैं।

App की `gradle.properties` में `adlogicSdkVersion` एक बार सेट करें; [shared build setup](../README.hi.md#build-setup) देखें।

```groovy
def sdkVersion = providers.gradleProperty('adlogicSdkVersion').get()
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
}
```

## 1. Application में install और configure करें

इसे अपनी मौजूदा Application में **ऊपर दिए ads initialization के बाद** जोड़ें; ERain को दो बार initialize न करें।
उदाहरण Google test ad units इस्तेमाल करता है। Release के लिए अपने units और अपनी `MainActivity` रखें।
App के `R`, `BuildConfig` और `MainActivity` अलग packages में हों तो उन्हें import करें; नीचे के सभी config types `io.onboardkit.config.*` में शामिल हैं।

```kotlin
import android.app.Application
import android.content.Intent
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.ads.erain.ERainTuning
import io.onboardkit.config.*
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Complete ERainAd.init here; add Tracker/sinks if needed (ads guide).
        ERainTuning.install()

        OnboardingSdk.install(this) {
            adProvider = ERainAdProvider()
            listener = OnboardingListener { context, outcome ->
                val extras = when (outcome) {
                    is OnboardingOutcome.Completed -> outcome.passthrough
                    is OnboardingOutcome.Skipped -> outcome.passthrough
                    is OnboardingOutcome.Aborted -> null
                }
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .apply { extras?.let { putExtras(it) } },
                )
            }
        }
        val config = onboardKitConfig {
            splash = SplashConfig(appNameRes = R.string.app_name)
            defaultSteps()
            ads = AdsConfig(
                splashInterstitial = InterstitialAdUnit(
                    "ca-app-pub-3940256099942544/1033173712",
                ),
                languageNative = NativeAdUnit(
                    "ca-app-pub-3940256099942544/2247696110",
                ),
                contentStepNative = NativeAdUnit(
                    "ca-app-pub-3940256099942544/2247696110",
                ),
            )
        }.getOrThrow()
        OnboardingSdk.configure(config).getOrThrow()
        OnboardingSdk.setFlowLogging(BuildConfig.DEBUG)
    }
}
```

`configure()` से पहले `install()` बुलाएँ। Config बनाना और configure करना दोनों `Result` देते हैं; उदाहरण गलत setup को स्पष्ट दिखाने के लिए `getOrThrow()` इस्तेमाल करता है।
Listener तीनों outcomes संभालता है। `Completed.selectedLanguage` चुनी गई भाषा भी देता है।
अंतिम handoff में `NEW_TASK` रखें, `CLEAR_TASK` न जोड़ें: splash ad या onboarding के अंत का ad अभी इसी task में दिख रहा हो सकता है।

## 2. अपना launcher splash जोड़ें

```kotlin
import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity()
```

```xml
<application android:name=".App">
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

इन declarations को अपने manifest में मिलाएँ; ads guide की metadata और permissions रखें। SDK की screens library manifest में पहले से हैं।
खुद `OnboardingSdk.start()` न बुलाएँ और splash को finish न करें; यह flow `ObSplashActivity` संभालता है।

### Default flow behavior

- Default रूप से status/caption bars दिखते हैं और navigation bar छिपता है; बदलने के लिए `SystemBarConfig` इस्तेमाल करें।
- अधूरा flow अगली बार खुलने पर दोबारा Splash → LFO → OB से शुरू होता है। पूरा हो चुका flow onboarding छोड़ देता है।
- Language popup चौथे item tap से खुलता है। Popup खुलने पर उसका native load होता है; click/open एक replacement preload करता है जो वापसी पर दिखता है।
- OB step से ad-return default रूप से step पूरा करता है (`BehaviorConfig.adClickReturnCompletesStep = true`)। Provider, OB steps/OB5 के लिए click replacement बंद रखता है; language, popup और प्रश्न के natives में यह चालू रहता है।


- `notificationPermissionEnabled = true`: Android 13+ / target 33+ पर consent के बाद notification permission माँगी जाती है। Grant या पिछले automatic request का दर्ज परिणाम अगली prompt रोकता है; मना करने पर भी flow चलता है। App खुद prompt संभाले तो `false` रखें।
- `noInternetPromptEnabled = true`: आगे बढ़ने से पहले splash नेटवर्क जोड़ने को कहता है। App को offline खोलने देना हो तो `false` रखें।
- `lockPortrait = true`: आपकी splash subclass सहित SDK screens portrait में lock होती हैं। ऊपर दिए splash `configChanges` बनाए रखें, ताकि lock, dark mode या font scale बदलने पर Activity दोबारा न बने। Landscape app में इसे `false` करें और merged manifest की orientation settings भी देखें।
- `consentTimeoutMs = 20_000`: SDK के default UMP flow में **उपयोगकर्ता के जवाब की समय-सीमा नहीं है**। SDK का consent flow resolve नहीं हो रहा हो तो यह budget custom hook को अब भी सीमित करता है।
- अनुमति मिलने के बाद splash दिख रहा हो तो notification prompt के पीछे ads लोड हो सकते हैं। Home पर नए requests रुकते हैं। Minimum समय ad phase के साथ शुरू होकर loading/prompt के साथ चलता है। Default रूप से पहली बार का flow (भाषा/onboarding) `AFTER_AD` इस्तेमाल करता है, और onboarding पूरा होने के बाद launcher से खुली destination (आपकी app या पुराने user का प्रश्न) `UNDER_AD` इस्तेमाल करती है; notification, widget और uninstall entries हमेशा `AFTER_AD` इस्तेमाल करती हैं; बदलने के लिए splash में `nextScreenTiming()` override करें और default रखने वाले cases में `super` call करें। दोनों timings interstitial दिखाने से पहले बचा हुआ minimum पूरा करती हैं: `UNDER_AD` अगली स्क्रीन खोलकर तुरंत ad दिखाता है, जबकि `AFTER_AD` ad बंद होते ही अगली स्क्रीन खोलता है।

### Splash और भाषा के विकल्प

दिया गया splash/provider consent, preload और navigation संभालता है। केवल ज़रूरी defaults बदलें;
इस flow में अलग app timer न जोड़ें।

| विकल्प | Default / उपयोग |
|---|---|
| `SplashConfig.minDisplayTimeMs` | Splash interstitial दिखने से पहले 3000 ms, या ad न होने पर navigation से पहले। Remote `ob_splash_min_display_ms` (default 3000) 0 से बड़ा हो तो इस field को override करता है, इसलिए यह field तभी लागू होता है जब Firebase में वह value `0` हो; Firebase के बिना minimum हमेशा 3000 ms रहता है। |
| `ob_splash_ad_budget_ms` | Notification पूरा होने और splash को focus मिलने के बाद ads के लिए अधिकतम 60000 ms। |
| `ob_splash_lfo_parallel_preload_enabled` | `false`: splash waterfall पूरा होने या wait समाप्त होने पर पहला language native preload करें। `true`: splash ads के साथ preload करें। |
| `LanguageConfig.tapHintEnabled` + `ob_show_language_tap_hint` | भाषा चुनने का hand hint दिखाने के लिए दोनों enabled हों। |
| `ob_language_tap_hint_delay_sec` | 3 सेकंड; `0` तुरंत दिखाता है। भाषा चुनने पर hint रद्द होता है; SETTINGS/पहले से चुनी भाषा में नहीं दिखता। |

`ob_*` Firebase Remote Config के वैकल्पिक parameters हैं। अन्य विकल्पों के लिए
[ObRemoteKeys](src/main/java/io/onboardkit/remote/RemoteKeys.kt) देखें।
Onboarding के बाहर native slots के लिए [Ads गाइड](../ads/README.md#native-preload-repeated-show-and-refresh) पढ़ें।

## 3. Ads और सामग्री को screens से जोड़ें

सिर्फ जरूरी optional slots configure करें; कुछ slots fallback units लेते हैं, जैसा [AdsConfig](src/main/java/io/onboardkit/config/AdsConfig.kt) में बताया गया है।

| Configuration | Screen |
|---|---|
| `splashBanner`, `splashInterstitial` | Splash banner / interstitial |
| `languageNative`, `languageDupNative` | भाषा स्क्रीन का पहला / replacement native |
| `contentStepNative`, `stepNatives[StepId.OB1]` | Content pages का साझा native / प्रति-step override |
| `fullScreenStepNative` | सिर्फ ad वाला step; usable unit न हो तो skip होता है |
| `afterOnboardingInterstitial` | Onboarding पूरा होने का अलग interstitial (`inter_after_ob3`) |
| `appResume` | Language/content के दौरान और in-app returns पर app-open eligibility |

`defaultSteps()` OB1, OB2, OB3 (सिर्फ ad), OB4 बनाता है। अपनी सामग्री और images के लिए इसे `steps(ContentStepDefinition(...), ...)` से बदलें; [step definitions](src/main/java/io/onboardkit/config/StepDefinition.kt) देखें।
Native/interstitial waterfall में `tiers = listOf(highId, fallbackId)` request के क्रम में दें; banner एक ID लेता है।

`inter_splash` या `native_lang` जैसे JSON नामों को app को `AdsConfig` से जोड़ना होता है; SDK field name से हर mapping नहीं निकालता।
`AdRemoteConfig.getInstance().tiersFor(key)` इस्तेमाल करें और refreshed IDs आने के बाद `onRemoteFetched()` में config दोबारा बनाएँ।
जब base key `isEnable: false` घोषित हो तो `tiersFor` खाली list लौटाता है — base key placement का master switch है और हर `_high*` floor को साथ बंद करता है — इसलिए बंद slot null ad unit बनता है और flow उसे छोड़ देता है।
Sample का [OnboardKitSetup](../app/src/main/java/com/itg/template/app/OnboardKitSetup.kt) पूरी mapping और native templates दिखाता है।

## Fullscreen page और onboarding के बाद interstitial

इन्हें content वाले `onboardKitConfig` block में configure करें। `StepId` का package
`io.onboardkit.core` है; बाकी types `io.onboardkit.config` में हैं।

```kotlin
// Include this among your content pages in steps(...).
AdFullScreenStepDefinition(
    StepId.OB3,
    skipButtonStyle = FullScreenSkipStyle.CLOSE_ICON, // TEXT for “Skip”
    skipButtonDelaySec = 1,
    autoNextEnabled = true,
    autoNextDelayMs = 3_000,
)

// Add these fields to your existing AdsConfig(...).
afterOnboardingInterstitial = InterstitialAdUnit("YOUR_INTERSTITIAL_UNIT_ID"),
afterOnboardingInterstitialEnabled = true,
```

Fullscreen page में default X 1 सेकंड बाद दिखता है और page selection से 3 सेकंड बाद अगला
page खुलता है। Manual completion के लिए `autoNextEnabled = false` रखें। Step के ad से वापसी
default रूप से step पूरा करती है, इसलिए ये placements click पर replacement preload/show नहीं
करते। Remote `ob_skip_button_delay_sec >= 0` local delay को override करता है; `-1` local value लेता है।
`AdsConfig.fullScreenSkipStyle` OB3/OB5 का साझा button style है। Standalone OB5 में अलग defaults
हैं: 3 सेकंड का skip delay और 15 सेकंड का auto-dismiss।

`inter_after_ob3` splash से अलग placement है। दिया गया provider pager entry पर preload करता
है और completion पर fill के लिए अधिकतम 8 सेकंड इंतज़ार करता है। Default रूप से
(`AdsConfig.afterOnboardingInterstitialTiming = NextScreenTiming.UNDER_AD`) अगली स्क्रीन ad के नीचे
खुलती है; notification, widget और uninstall entries dismissal का इंतज़ार करती हैं। हमेशा इंतज़ार के लिए
`NextScreenTiming.AFTER_AD` (`io.onboardkit.ads`) रखें।
`afterOnboardingInterstitialEnabled` और remote `ob_ads_inter_after_ob3_enabled` दोनों true
हों। ऐप इस ad trigger को संभालता हो तो local switch `false` रखें; इससे automatic preload और
show दोनों बंद होते हैं। इसे content AutoBuffer group में शामिल न करें।

## App-open on return

[Ads app-open setup](../ads/README.md#app-open-on-return) पूरा करें, फिर
`appResume = AdRemoteConfig.getInstance().tiersFor("open_resume").takeIf { it.isNotEmpty() }?.let { InterstitialAdUnit(tiers = it) }`
को `AdsConfig` में जोड़ें, ताकि दोनों एक ही `open_resume` placement पढ़ें।
Language और onboarding content pages वास्तविक background/return पर तैयार resume ad दिखा
सकते हैं। Splash, standalone fullscreen और survey excluded हैं; fullscreen pager pages,
page transitions और language confirmation dialog अस्थायी रूप से resume रोकते हैं।
Language/content Activities पर ऐप के exclusions तभी हटाएँ जब वहाँ resume ads चाहिए।
SDK loading और screen eligibility संभालता है; नया Activity lifecycle callback नहीं चाहिए।

## वैकल्पिक integrations

- **Firebase:** Firebase configured हो तो splash `ob_*` flags fetch करता है; वरना cache/defaults इस्तेमाल होते हैं। [ObRemoteKeys](src/main/java/io/onboardkit/remote/RemoteKeys.kt) में supported keys हैं। Remote ad JSON या GA4 sink के लिए [suite-firebase](../suite-firebase/README.md) जोड़ें; सिर्फ ad config source install करने से fetch नहीं होता।
- **Paywall:** पहले [PayKit](../paykit/README.md) install करें, फिर `OnboardingSdk.install` में `paywallGate = OnboardKitPaywallGate()` रखें (`io.paykit.integration`)। Gate unset हो तो paywall skip होता है। App में purchases और ads दोनों हों तो नीचे billing readiness वाला कदम भी पूरा करें।
- **अपना consent provider:** UMP के लिए default `onConsentRequired()` रखें। Custom override में लौटने से पहले CMP का परिणाम `ConsentCenter.setHostConsent(canRequestAds, personalized)` से publish करें। सिर्फ `true` लौटाना ad request की अनुमति नहीं है; `setCanRequestAds(false)` host की अलग रोक है और `true` सिर्फ उस रोक को हटाता है। `onDestroy()` override करें तो `super.onDestroy()` जरूर बुलाएँ।
- **Custom UI / प्रश्न:** [screen configuration](src/main/java/io/onboardkit/config/OnboardKitConfig.kt) और [QuestionConfig](src/main/java/io/onboardkit/config/QuestionConfig.kt) देखें। सिर्फ splash और content-step के `layoutRes` overrides supported हैं; बाकी layout fields validation में fail होते हैं। SDK resources override करते समय IDs बनाए रखें।

**Purchases और ads:** `PayKit.install()` / BillingKit initialization purchase verification को asynchronously शुरू करता है; इससे यह तय नहीं होता कि premium restore हो चुका है। Base `onInitBilling()` hook खाली है।
ऊपर की minimal splash को ऐसे override से बदलें जो splash ad phase से पहले `Billing.awaitReady()` का इंतजार करे।
`Billing` को सीधे बुलाने के लिए उसी tag पर `implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"` भी जोड़ें; [BillingKit](../billingkit/README.md) देखें।

```kotlin
import com.ads.module.billing.Billing
import io.onboardkit.ui.splash.ObSplashActivity

class SplashActivity : ObSplashActivity() {
    override suspend fun onInitBilling() {
        val readiness = Billing.awaitReady()
        // Apply your app's policy for ReadyResult.Timeout / ReadyResult.Error.
    }
}
```

`Billing.awaitReady()` से `Ready`, `Timeout` या `Error` मिलता है; परिणाम के अनुसार अपनी app की error policy लागू करें। मौजूदा `SplashConfig.billingTimeoutMs` (default 5,000 ms) पूरे hook को भी सीमित करता है, इसलिए splash deadline परिणाम आने से पहले hook cancel कर सकती है; timeout purchase status तय नहीं करता।
Integration point के लिए [sample splash](../app/src/main/java/com/itg/template/ui/component/splash/SplashActivity.kt) देखें।

Notification/widget/uninstall entry को [SplashEntry](src/main/java/io/onboardkit/ui/splash/SplashEntry.kt) से अपनी splash पर भेजें:

```kotlin
import io.onboardkit.ui.splash.SplashEntry

val intent = SplashEntry.WIDGET.intent(context, SplashActivity::class.java)
    .putExtra("widget_action", "open_document")
```

ऊपर का listener `Completed`/`Skipped` के extras आगे भेजता है। Destination के `onCreate` और `onNewIntent` दोनों में इन्हें पढ़ें।
Entries `inter_noti`, `inter_widget` या `inter_uninstall` इस्तेमाल करती हैं; fallback सामान्य splash unit है। ये entries हमेशा ad बंद होने के बाद navigate करती हैं, क्योंकि उनकी destination अपनी एक screen खोलती है, जो दिख रहे ad को ढक देगी। Launcher start पहली बार ad के बाद LFO खोलता है, और onboarding पूरा होने पर आपकी app ad के नीचे खोलता है।

## समस्या निवारण

| समस्या | जाँचें |
|---|---|
| Flow तुरंत skip होता है | `install()` पहले, फिर `configure()` चला और दोनों `Result` सफल हैं |
| Flow खत्म होता है, app नहीं खुलती | Listener `Completed`, `Skipped`, `Aborted` तीनों संभालता है |
| `no_provider` / `consent_not_granted` | Provider installed है; `ConsentCenter.canRequestAds()` और host की रोक देखें |
| सिर्फ ad वाला page नहीं दिखता | `fullScreenStepNative` या उसका `stepNatives` override usable है |
| Custom splash banner नहीं दिखता | Layout में `ob_splash_ad_container` के अंदर `layout_banner_control` include है |

Integration के दौरान `OnboardingSdk.setFlowLogging(true)` रखें (`OB_FLOW` in Logcat)।
बाद में भाषा बदलने के लिए `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)` बुलाएँ (`io.onboardkit.ui.language`)।

[Sample Application](../app/src/main/java/com/itg/template/app/GlobalApp.kt) · [Sample splash](../app/src/main/java/com/itg/template/ui/component/splash/SplashActivity.kt) · [MIT license](../LICENSE)
