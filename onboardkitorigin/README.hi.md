# OnboardKit

> First-open flow एक library के रूप में: splash → language → onboarding steps → वैकल्पिक full-screen ad
> → वैकल्पिक question → आपकी app।

Ads, remote config, state persistence और analytics funnel — सब अंदर है। आपको सिर्फ़ ad unit ids, copy, और
flow ख़त्म होने पर कहाँ जाना है, यह देना है।

English: **[README.md](README.md)** · Tiếng Việt: **[README.vi.md](README.vi.md)**

## आवश्यकताएँ

| | |
|---|---|
| minSdk / compileSdk / JDK | 24 / 36 / 17 |
| Namespace, resource prefix, entry point | `io.onboardkit`, `ob_`, `OnboardingSdk` |
| Firebase | Hosted `ob_*` values fetch करने के लिए `google-services.json` + `com.google.gms.google-services` |
| Ad unit ids | `AdRemoteConfig` के ज़रिए `assets/ad_config.json` से, या सीधे `AdsConfig` में |

## Installation

```groovy
// <tag> की जगह https://github.com/truongvimit/adlogic-partner-sdk/tags से कोई tag रखें
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

`:ads` को अलग से declare करें — इस module के भीतर वह `implementation` dependency है, इसलिए
`com.ads.module.*` वरना आपके compile classpath पर नहीं होगा। `:trackkit` `api` से export होता है,
`consumer-rules.pro` module के साथ आता है, और SDK की activities library manifest में हैं — उन्हें दोबारा
declare न करें।

## Integration

### 1. `Application.onCreate()`

`Tracker.install()` सबसे पहले — उससे पहले निकले events सिर्फ़ buffer होते हैं। `OnboardingSdk.install()`
`configure()` से पहले — install से पहले दिया गया config गिरा दिया जाता है और तब पूरा flow skip हो जाता है।

```kotlin
override fun onCreate() {
    super.onCreate()
    initTracking()                                    // Tracker.install + Tracker.addSink
    AdRemoteConfig.initializeFromAssets(this)         // assets/ad_config.json
    AdConfig.install(FirebaseAdConfigSource())        // वैकल्पिक: remote ad config
    ConsentCenter.configure(ConsentOptions(timeoutMs = 20_000))
    ERainAd.getInstance().init(this, buildERainAdConfig())   // देखें ../ads/README.md
    ERainTuning.install()                             // एक बार, ERainAd.init के बाद

    OnboardingSdk.install(this) {
        adProvider = ERainAdProvider()                // ad-free flow के लिए null
        paywallGate = OnboardKitPaywallGate()         // वैकल्पिक, :paykit से
        listener = OnboardingListener { ctx, outcome -> goToMain(ctx, outcome) }
    }
    OnboardingSdk.configure(buildConfig()).onFailure { Log.e("OnboardKit", "rejected", it) }
    OnboardingSdk.setFlowLogging(BuildConfig.DEBUG)   // OB_FLOW logcat
}
```

Listener को `OnboardingOutcome.Completed`, `Skipped` **और** `Aborted` — तीनों पर navigate करना ज़रूरी है;
कोई listener न हो तो outcome गिर जाता है। `Completed.selectedLanguage` चुनी गई भाषा साथ लाता है;
`OnboardingSdk.selectedLanguage()` उसे बाद में पढ़ लेता है।

### 2. Config

```kotlin
private fun buildConfig() = onboardKitConfig {
    splash = SplashConfig(logoRes = R.drawable.ic_logo, minDisplayTimeMs = 3_000)
    language = LanguageConfig(defaultCode = "en")
    defaultSteps()                                    // OB1, OB2, OB3 (सिर्फ़ ad), OB4
    question = QuestionConfig(options = listOf(QuestionOption("romance", "Romance")))
    ads = AdsConfig(
        splashBanner         = BannerAdUnit("ca-app-pub-…/1111"),
        splashInterstitial   = InterstitialAdUnit("ca-app-pub-…/2222"),
        languageNative       = NativeAdUnit.waterfall(highFloor = "…/3333", allPrice = "…/4444"),
        contentStepNative    = NativeAdUnit("ca-app-pub-…/5555"),
        fullScreenStepNative = NativeAdUnit("ca-app-pub-…/6666"),
    )
}.getOrThrow()
```

`onboardKitConfig { }` एक `Result` लौटाता है, जिसमें validated config या validation errors होते हैं।
`SplashConfig`, `LanguageConfig`, `BehaviorConfig`, `SystemBarConfig`, `QuestionConfig` और
`AdsConfig` अपने options और defaults KDoc में बताते हैं। अपने flow के लिए ज़रूरी content और ad slots
configure करें।

**Ad slots.** `null` slot पर कोई ad नहीं दिखता। हर native और interstitial slot एक waterfall है: ids सबसे
ऊँचे floor से शुरू, एक बार में एक request, पहली fill पर रुक जाना। `AdsConfig` में वे सारे slots हैं जिन्हें यह
flow भर सकता है।

ids को hard-code करने के बजाय `ad_config.json` में रखना है, तो app-side helpers से `AdRemoteConfig`
के values को slots में बदलें:

```kotlin
private fun AdRemoteConfig?.native(baseKey: String): NativeAdUnit? =
    this?.tiersFor(baseKey)?.takeIf { it.isNotEmpty() }?.let { NativeAdUnit(tiers = it) }

private fun AdRemoteConfig?.interstitial(baseKey: String): InterstitialAdUnit? =
    this?.tiersFor(baseKey)?.takeIf { it.isNotEmpty() }?.let { InterstitialAdUnit(tiers = it) }

// यहाँ banners का waterfall नहीं होता — सिर्फ़ सबसे ऊपर वाला tier ही इस्तेमाल हो सकता है।
private fun AdUnitConfig?.toBanner(): BannerAdUnit? =
    this?.takeIf { it.isUsable }?.let { BannerAdUnit(id = it.waterfallIds.first()) }
```

```kotlin
val remoteAds = runCatching { AdRemoteConfig.getInstance() }.getOrNull()
val config = onboardKitConfig {
    ads = AdsConfig(
        splashInterstitial = remoteAds.interstitial("inter_splash"),
        languageNative     = remoteAds.native("native_lang"),
        contentStepNative  = remoteAds.native("native_ob1"),
    )
}.getOrThrow()
```

**Steps.** `defaultSteps()` की जगह अपने steps `steps(vararg StepDefinition)` या `step(…)` से गिनाएँ —
content page के लिए `ContentStepDefinition`, सिर्फ़-ad वाले page के लिए `AdFullScreenStepDefinition`। सूची
का क्रम ही display क्रम है; remote config सिर्फ़ किसी step को बंद कर सकता है। `id` एक `StepId` है
(`OB1`…`OB5`) — यह flow में **स्थान** है, content page की गिनती नहीं: default template में OB3 सिर्फ़-ad
वाला page है, इसलिए तीसरा *content* page `StepId.OB4` है।

**Native templates.** ये screens हर CTA position के लिए एक अलग layout ship करती हैं, इसलिए
`NativeTemplate` blocks को इधर-उधर करने के बजाय layout चुनता है। इसे सीधे set करें, या उसी config document
से एक और app-side helper के ज़रिए निकालें:

```kotlin
private fun AdRemoteConfig?.templateOf(
    key: String,
    default: NativeTemplate = NativeTemplate.CTA_BOTTOM,
): NativeTemplate = when (this?.unit(key)?.positionCTA) {
    "TOP" -> NativeTemplate.CTA_TOP
    "BOTTOM" -> NativeTemplate.CTA_BOTTOM
    else -> default
}
```

### 3. Splash

आपकी launcher activity `ObSplashActivity` को extend करती है। SDK consent, billing, remote fetch, ad loading,
notification permission, न्यूनतम display time और navigation संभालता है। App-specific setup के लिए hooks
override करें।

```kotlin
class SplashActivity : ObSplashActivity() {
    override suspend fun onInitBilling() { myEntitlement.awaitReady() }  // पहले premium तय करें

    override fun onRemoteFetched() {
        // यहाँ अपनी app की remote keys fetch करें
        OnboardingSdk.configure(buildConfig())   // दोबारा बनाएँ: remote ने ad unit ids बदले हो सकते हैं
    }
}
```

`SplashConfig.notificationPermissionEnabled` का default `true` है। Android 13+ और target SDK 33+ पर
consent step पूरा होने के बाद splash `POST_NOTIFICATIONS` माँगता है। Library यह permission declare
करती है। Permission पहले से मिली हो, पिछले automatic request का परिणाम आ चुका हो, या manifest से
permission हटा दी गई हो तो prompt skip होता है। Denial या cancellation flow को नहीं रोकता और ad
consent नहीं देता। App में notification permission स्वयं संभालने के लिए config में automatic request
बंद करें:

```kotlin
splash = SplashConfig(notificationPermissionEnabled = false)
```

Remote fetch और consent साथ चल सकते हैं। SDK के UMP flow में उपयोगकर्ता के उत्तर की कोई समय-सीमा
नहीं है। Consent और notification permission का परिणाम, यदि माँगा गया हो, मिलने के बाद splash resumed
Activity और window focus का इंतज़ार करता है। तभी eligible banner/interstitial loads और minimum display
period शुरू होते हैं। Ad wait budgets इन prompts के बाद शुरू होते हैं; उन्हें पढ़ने में लगा समय ad phase
का budget नहीं घटाता। Splash ad waits और minimum display period के बाद foreground/focus फिर जाँचा
जाता है। फिर अगले destination के native preloads शुरू होते हैं, उसके बाद interstitial दिखाने और flow
आगे बढ़ाने का प्रयास होता है। Banner wait का default `0` है; interstitial wait load पूरा होने या configured
budget समाप्त होने पर ख़त्म होता है, इसलिए हर ad का fill होना ज़रूरी नहीं है।

Launcher activity में `android:exported="true"`, MAIN/LAUNCHER filter और AppCompat/MaterialComponents
theme declare करें। Portrait splash के लिए:

```xml
<activity
    android:name=".SplashActivity"
    android:configChanges="orientation|screenSize|keyboardHidden|uiMode|fontScale"
    android:exported="true"
    android:screenOrientation="portrait"
    android:theme="@style/Theme.Splash">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

`configChanges` से Activity सूचीबद्ध बदलावों को recreation के बिना संभालती है। `uiMode|fontScale` रखने
पर प्रभावित custom views को स्वयं update करें। अन्य कारणों से Activity फिर भी recreate हो सकती है।
Landscape या tablet support के लिए manifest orientation की जाँच करें और
`BehaviorConfig.lockPortrait = false` सेट करें।

- यहाँ `OnboardingSdk.start()` न बुलाएँ — pipeline पूरा होते ही वह अपने आप चलता है।
- UMP के लिए `onConsentRequired()` का default रखें; यह `:ads` के `ConsentCenter` से flow चलाता है।
  Custom consent provider के लिए override पूरा करने से पहले request और personalization decisions
  को `ConsentCenter.setHostConsent(...)` से publish करें। केवल `return true` ad requests की अनुमति
  नहीं देता। Consent अनुमति दे तब भी `OnboardingSdk.setCanRequestAds(false)` अलग प्रतिबंध रहता है।
- `onDestroy()` override करें तो `super.onDestroy()` ज़रूर बुलाएँ — `ConsentCenter.detach(this)` वहीं है।

बाद में, कहीं से भी: `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)`।

## Notification, widget या uninstall shortcut से entry

`SplashEntry` (`NOTIFICATION`, `WIDGET`, `UNINSTALL`) feature launches को splash से route करता है,
entry-specific interstitial unit चुनता है और destination खुलने का समय तय करता है। Feature extras दें और
listener में destination संभालें।

**1. entry का intent splash पर दागें, अपनी main screen पर नहीं।** Tap एक session शुरू करता है, इसलिए वह वही
रास्ता लेता है जो launcher tap लेता है। `SplashEntry.intent` उस launch को tag करता है और
`NEW_TASK or CLEAR_TASK` पहले से सेट कर देता है; आप ऊपर से feature extras जोड़ें।

```kotlin
SplashEntry.WIDGET.intent(context, SplashActivity::class.java)
    .putExtra(EXTRA_WIDGET_ACTION, "merge_pdf")
```

**2. Extras passthrough बनकर साथ चलते हैं।** `ObSplashActivity` उसे अपने ही `intent.extras` से भरता है, SDK
उसे हर screen के पार ले जाता है, और `Completed` तथा `Skipped` पर वापस सौंप देता है (`Aborted` पर कभी नहीं)।
आपके feature extras SDK के लिए अपारदर्शी हैं।

**3. Listener outcome को route करता है** — यही एक फ़ैसला हर app खुद लेती है:

```kotlin
listener = OnboardingListener { context, outcome ->
    val extras = when (outcome) {
        is OnboardingOutcome.Completed -> outcome.passthrough
        is OnboardingOutcome.Skipped -> outcome.passthrough
        is OnboardingOutcome.Aborted -> null
    }
    val destination = when (SplashEntry.from(extras)) {
        SplashEntry.UNINSTALL -> ConfirmUninstallActivity::class.java
        else -> MainActivity::class.java
    }
    context.startActivity(
        Intent(context, destination)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { extras?.let(::putExtras) },
    )
}
```

इस handoff में `NEW_TASK` रखें और `CLEAR_TASK` न जोड़ें: ad अभी स्क्रीन पर हो सकता है और task clear
करने से उसकी host Activity finish हो जाएगी। Extras को `onCreate` **और** `onNewIntent` दोनों में संभालें;
delivery destination के launch mode और task state पर निर्भर है।

**4. Entry-specific ads और navigation timing।** `SplashEntry` launch अपनी entry की key इस्तेमाल करता है
(`inter_noti`, `inter_widget`, `inter_uninstall`), पूरे waterfall सहित; वह key न हो या बंद हो तो सामान्य
splash resolution पर लौट आता है। उसे `AFTER_AD` भी मिलता है, जबकि launcher tap `UNDER_AD` पर रहता है — वही
trade-off जो [`../ads/README.md`](../ads/README.md#when-the-next-screen-starts) में `InterNextAction` का
है। `nextScreenTiming()` या `splashInterstitialOverride()` सिर्फ़ और बारीक बँटवारे के लिए override करें।

## अपने layouts

सिर्फ़ `SplashConfig.layoutRes` और `ContentStepDefinition.layoutRes` किसी screen द्वारा पढ़े जाते हैं। बाकी
`layoutRes` knobs validation में reject हो जाते हैं — उन्हें `0` पर छोड़ें और उसी नाम का SDK layout override
करें, उसकी हर id बनाए रखते हुए।

| इसके बजाय | यह layout override करें |
|---|---|
| `LanguageConfig.layoutRes` / `.itemLayoutRes` | `ob_activity_language.xml` / `ob_item_language.xml` |
| `QuestionConfig.layoutRes` / `.optionLayoutRes` | `ob_activity_question.xml` / `ob_item_question_option.xml` |
| `AdFullScreenStepDefinition.layoutRes` | `ob_fragment_ad_step.xml` |

Splash हर id null-safely bind करता है, इसलिए जो id आप छोड़ देंगे वह बस skip हो जाएगी। पर content-step layout
में उसकी **सारी** ids होनी चाहिए, वरना वह page एक log के साथ SDK layout पर लौट जाता है।

| Screen | Id | Type |
|---|---|---|
| Splash | `ob_splash_logo` / `ob_splash_app_name` / `ob_splash_progress` | `ImageView` / `TextView` / `ProgressBar` |
| | `ob_splash_ad_container` | `FrameLayout`; इसके अंदर `<include layout="@layout/layout_banner_control" />` रखें, वरना splash banner के पास जुड़ने की जगह नहीं होगी |
| Content step | `ob_step_image` / `ob_step_player` / `ob_step_card` | `ImageView` / `androidx.media3.ui.PlayerView` / `LinearLayout` |
| | `ob_step_title` / `ob_step_subtitle` / `ob_step_indicator` / `ob_primary_cta` | `TextView` / `TextView` / `ObStepIndicator` / `ObPrimaryButton` |
| | `ob_ad_block` / `ob_native_container` | `FrameLayout` (slot अस्वीकृत होने पर छिपा) / `FrameLayout` |

Flow के भीतर अपनी किसी screen के लिए, `showInterstitial(placement, onNext, onFinished)`
`AppCompatActivity` पर एक public extension है: `onNext` में destination शुरू करें (ad के नीचे), `onFinished`
में मौजूदा screen finish करें। हर callback अधिकतम एक बार चलता है, `onNext` पहले और `onFinished` बाद में; `onNext` से
`finish()` कभी न बुलाएँ। Native के लिए ऐसा कोई public समकक्ष नहीं है — अपने natives `:ads` के
`NativeAdHelper` से render करें।

## Paywall gate

```kotlin
class MyPaywallGate : PaywallGate {
    override suspend fun shouldShow(placement: PaywallPlacement) =
        placement == PaywallPlacement.AFTER_ONBOARDING && !myEntitlement.isPremium

    override suspend fun present(activity: Activity, placement: PaywallPlacement): PaywallOutcome =
        PaywallOutcome.Dismissed   // या Purchased / ContinueWithAds
}
```

Placements: `SPLASH_INTER`, `AFTER_ONBOARDING`, `AFTER_QUESTION_OLD_USER`। `paywallGate` सेट न करें तो हर
checkpoint सीधे पार हो जाता है। `:paykit` ship कर रहे हैं? उसका तैयार `OnboardKitPaywallGate` इस्तेमाल करें —
देखें [`../paykit/README.md`](../paykit/README.md)।

## Remote config

हर `ob_*` key, type और default `io.onboardkit.remote.ObRemoteKeys` में देखें। SDK startup पर cached flags
बहाल करता है और cache न होने पर defaults इस्तेमाल करता है। Firebase configured होने पर splash remote
values अपने आप fetch और apply करता है। Overrides Firebase console में publish करें।

## Analytics

`Tracker.install()` और एक `Tracker.addSink(...)` जुड़ते ही funnel अपने आप निकलने लगता है — event नामों के लिए
देखें [`../trackkit/README.md`](../trackkit/README.md)। इसके बजाय SDK के अपने events चाहिए तो `install` के
भीतर `analyticsPlugin { event -> log(event.name, event.params) }` जोड़ें, या `OnboardingSdk.events` /
`.state` collect करें।

`isCompleted()`, `selectedLanguage()` और `answers()` सहेजी गई progress पढ़ते हैं। `markCompleted()` flow
को complete चिह्नित करता है; `reset()` progress साफ़ करता है। ये suspend functions हैं।

## समस्या-निवारण

| लक्षण | कारण | समाधान |
|---|---|---|
| Flow कभी चलता ही नहीं | `configure()` विफल, या `install()` से पहले चला | `Result` log करें; पहले `install()` बुलाएँ |
| User flow से बाहर ही नहीं निकलता | कोई `OnboardingListener` नहीं, या वह `Skipped` को अनदेखा करता है | तीनों outcomes संभालें |
| हर placement `no_provider` कहता है | `adProvider` null छोड़ा गया | `adProvider = ERainAdProvider()` |
| हर placement `consent_not_granted` कहता है | Consent ने requests की अनुमति नहीं दी, या host ने उन्हें बंद किया है | `ConsentCenter.canRequestAds()` और `OnboardingSdk.canRequestAds()` जाँचें; UMP पूरा करें या custom provider का परिणाम publish करें |
| सिर्फ़-ad वाला page कभी नहीं दिखता | `fullScreenStepNative` / `stepNatives[OB3]` के लिए कोई usable unit नहीं | एक configure करें; सिर्फ़ remote step flag काफ़ी नहीं |
| Splash banner कभी नहीं दिखता | `ob_splash_ad_container` या `layout_banner_control` include गायब | दोनों अपने splash layout में जोड़ें |

## License

MIT — देखें [`../LICENSE`](../LICENSE)।
