**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# OnboardKit

> पहली बार app खुलने का पूरा flow, एक library के रूप में: splash → language → onboarding steps → वैकल्पिक full-screen ad → वैकल्पिक question → आपका app।

Ads, remote config, state persistence और analytics funnel अंदर ही हैं। आप ad unit ids, copy, और flow ख़त्म होने पर कहाँ जाना है — बस यह देते हैं।

## Requirements

| | |
|---|---|
| minSdk / compileSdk / JDK | 24 / 36 / 17 |
| Namespace, resource prefix, entry point | `io.onboardkit`, `ob_`, `OnboardingSdk` |
| Firebase | `google-services.json` + `com.google.gms.google-services`; इसके बिना हर `ob_*` key अपने default पर ही टिकी रहती है |
| Ad unit ids | `AdRemoteConfig` के ज़रिए `assets/ad_config.json`, या `AdsConfig` में सीधे literals |

## Installation

```groovy
// Replace <tag> with a tag from https://github.com/truongvimit/adlogic-partner-sdk/tags
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
}
```

`:ads` को अलग से declare करें — इस module के अंदर वह `implementation` dependency है, इसलिए `com.ads.module.*`
वरना आपके compile classpath पर नहीं आता। `:trackkit` `api` से export होता है, `consumer-rules.pro` module के
साथ ही ship होती है, और चारों SDK activities library manifest में हैं — इन्हें दोबारा declare न करें।

## Quick start

### 1. `Application.onCreate()`

सबसे पहले `Tracker.install()` — उससे पहले के events सिर्फ़ buffer होते हैं। `configure()` से पहले `OnboardingSdk.install()`
— install से पहले दिया गया config गिरा दिया जाता है और तब पूरा flow skip हो जाता है।

```kotlin
override fun onCreate() {
    super.onCreate()
    initTracking()                                    // Tracker.install + Tracker.addSink
    AdRemoteConfig.initializeFromAssets(this)         // assets/ad_config.json
    AdConfig.install(FirebaseAdConfigSource())        // optional: remote ad config
    ConsentCenter.configure(ConsentOptions(timeoutMs = 20_000, testDeviceHashedId = "…"))
    val adConfig = ERainAdConfig(this)                // fill its fields: see ../ads/README.md
    ERainAd.getInstance().init(this, adConfig)
    ERainTuning.install()                             // once, after ERainAd.init

    OnboardingSdk.install(this) {
        adProvider = ERainAdProvider()                // null for an ad-free flow
        paywallGate = MyPaywallGate()                 // optional
        listener = OnboardingListener { ctx, outcome -> goToMain(ctx, outcome) }
    }
    OnboardingSdk.configure(buildConfig()).onFailure { Log.e("OnboardKit", "rejected", it) }
    OnboardingSdk.setFlowLogging(BuildConfig.DEBUG)   // OB_FLOW logcat; on by default
}
```

Listener को `OnboardingOutcome.Completed`, `Skipped` **और** `Aborted` — तीनों पर navigate करना ज़रूरी है; एक भी listener register न हो तो outcome गिर जाता है। `Completed.selectedLanguage` में चुनी हुई language आती है; `OnboardingSdk.selectedLanguage()` उसे बाद में पढ़ लेता है।

### 2. Config

```kotlin
private fun buildConfig() = onboardKitConfig {
    splash = SplashConfig(logoRes = R.drawable.ic_logo, minDisplayTimeMs = 3_000)
    language = LanguageConfig(defaultCode = "en")
    defaultSteps()                                    // OB1, OB2, OB3 (ad-only), OB4
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

`defaultSteps()` की जगह अपनी list `steps(vararg StepDefinition)` या `step(…)` से दें। List का क्रम ही
display क्रम है; remote config सिर्फ़ किसी step को बंद कर सकता है।

### 3. Splash

आपकी launcher activity `ObSplashActivity` को extend करती है। Consent, billing, remote fetch, ad requests,
minimum display, interstitial और navigation अंदर हैं; आप सिर्फ़ hooks भरते हैं।

```kotlin
class SplashActivity : ObSplashActivity() {
    override suspend fun onInitBilling() { myEntitlement.awaitReady() }  // resolve premium first

    override fun onRemoteFetched() {
        // fetch your app's own remote keys here
        OnboardingSdk.configure(buildConfig())   // rebuild: remote may have changed ad unit ids
    }
}
```

इसे `android:exported="true"`, एक MAIN/LAUNCHER filter और AppCompat/MaterialComponents theme के साथ declare करें।
`onConsentRequired()` को override न करें — इसका default `:ads` के `ConsentCenter` से UMP flow चलाता है;
override सिर्फ़ तब करें जब app में consent step ही न हो और आपको `return true` करना हो। `OnboardingSdk.start()` यहाँ न बुलाएँ, pipeline resolve होते ही वह ख़ुद चलता है। अगर आप `onDestroy()` override करें तो `super.onDestroy()` ज़रूर बुलाएँ —
`ConsentCenter.detach(this)` वहीं रहता है। बाद में: `OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)`।
अगर यही launcher notification, widget या shortcut tap भी संभालता है, तो देखें [Notification या widget से app में आना](#notification-या-widget-से-app-में-आना)।

## Notification या widget से app में आना

जिस tap के साथ किसी feature का नाम आता है, उसे पूरे first-open flow से बचकर निकलना है और फिर वह feature खोलना है —
बिना उस ad को ढके जिसके लिए अभी पैसे चुकाए गए। चार हिस्से, जिनमें आख़िरी फ़ैसला सिर्फ़ आप ले सकते हैं।

**1. Entry को splash पर भेजें, अपनी main screen पर नहीं.** Tap एक session की शुरुआत है, इसलिए वह वही रास्ता लेता है जो
launcher tap लेता है — consent, remote, splash interstitial, फिर language / onboarding या सीधे आगे। Feature को intent
extra के रूप में साथ ले जाएँ।

```kotlin
// notification trampoline, widget PendingIntent, shortcut …
Intent(context, SplashActivity::class.java).apply {
    putExtra(EXTRA_WIDGET_ACTION, "merge_pdf")
    // सिर्फ़ NEW_TASK नहीं, CLEAR_TASK भी: इसके बिना पिछले session का बचा हुआ task ही आगे आ जाता है
    // और splash — उसके साथ ad और routing — कभी चलता ही नहीं।
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
}
```

**2. Extras passthrough बनकर पूरे flow से गुज़रते हैं.** `ObSplashActivity` इसे अपने ही `intent.extras` से लेता है; SDK
इसे language, onboarding और question screen के पार ले जाता है और terminal outcome पर वापस सौंप देता है। Flow में कोई
इसे पढ़ता नहीं — SDK के लिए यह अपारदर्शी data है।

| Outcome | Passthrough साथ लाता है |
|---|---|
| `Completed` | हाँ |
| `Skipped` | हाँ — flow config से बंद था, पहले ही पूरा हो चुका था, या दिखाने को कुछ नहीं था |
| `Aborted` | नहीं |

**3. Listener इसे वापस आपकी main screen के intent पर लगाता है.**

```kotlin
listener = OnboardingListener { context, outcome ->
    val extras = when (outcome) {
        is OnboardingOutcome.Completed -> outcome.passthrough
        is OnboardingOutcome.Skipped -> outcome.passthrough
        is OnboardingOutcome.Aborted -> null
    }
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { extras?.let(::putExtras) },
    )
}
```

सिर्फ़ `NEW_TASK`, `CLEAR_TASK` कभी नहीं: `UNDER_AD` में यह तब चलता है जब ad screen पर है, और task clear करने का मतलब
है उसी Activity को finish कर देना जो ad को होस्ट कर रही है। SDK की हर screen अगली शुरू करते ही ख़ुद को finish कर लेती
है, इसलिए stack पर वैसे भी कुछ नहीं बचता।

Extra को `onCreate` **और** `onNewIntent` दोनों में पढ़ें — cold tap पहले में आता है, warm tap दूसरे में — और पढ़ते ही उसे
consume कर दें, वरना launching intent अगले configuration change पर feature दोबारा खोल देगा।

**4. तय करें कि destination कब शुरू हो.**

```kotlin
class SplashActivity : ObSplashActivity() {
    override fun nextScreenTiming(): NextScreenTiming =
        if (intent.hasExtra(EXTRA_WIDGET_ACTION)) NextScreenTiming.AFTER_AD
        else NextScreenTiming.UNDER_AD
}
```

| | `UNDER_AD` (default) | `AFTER_AD` |
|---|---|---|
| Destination शुरू होता है | `show()` के उसी tick पर, ad के पीछे | ad हट जाने के बाद |
| User को दिखता है | ad बंद होते ही पूरी बनी हुई screen | एक छोटा ठहराव, फिर screen |
| तब चुनें जब | destination वही जगह है जहाँ user रुकता है | destination आते ही ख़ुद कुछ और खोलता है |

`AFTER_AD` हमेशा सुरक्षित है; यह सिर्फ़ शुरुआती बढ़त छोड़ता है। `UNDER_AD` optimization है, और ठीक एक स्थिति में ग़लत है:
जब destination आते ही दूसरा `startActivity` करता है। GMA की ad Activity आपके अपने task में रहती है, इसलिए वह launch ad
के *ऊपर* चढ़ जाता है और impression गिने जाने से पहले ही उसे ढक देता है।

| Destination आते ही क्या खोलता है | Timing |
|---|---|
| कुछ नहीं — user आकर वहीं रहता है | `UNDER_AD` |
| `Dialog`, bottom sheet या fragment transaction | `UNDER_AD` — ये destination के अपने token पर window हैं, ad के पीछे चुपचाप बैठे रहते हैं |
| कोई दूसरी Activity | `AFTER_AD` |
| Camera, audio या video playback | `AFTER_AD` |

फ़ैसला हर launch पर लें, पूरे app के लिए नहीं: launcher tap और widget tap एक ही splash पर पहुँचते हैं पर दोनों को अलग जवाब
चाहिए — इसीलिए यह `SplashConfig` का field नहीं, Activity पर एक hook है। जब किसी notification में कई action हों और उनमें
से सिर्फ़ कुछ ही feature खोलते हों, तो सबके लिए `AFTER_AD` देना सही है — उन entries को बस शुरुआती बढ़त का नुक़सान होता है,
और कुछ नहीं।

यही फ़ैसला एक परत नीचे भी है, उन interstitials के लिए जो आप ख़ुद दिखाते हैं: जिस screen का callback एक बीच की screen शुरू
करता है और फिर वह feature खोलती है, उसे
`InterstitialAdManager.show(…, nextAction = InterNextAction.AfterDismiss)` चाहिए। देखें [`../ads/README.md`](../ads/README.md)।

## Configuration

| Config | Field | Type | Default |
|---|---|---|---|
| `SplashConfig` | `layoutRes` / `logoRes` / `appNameRes` | `@LayoutRes` / `@DrawableRes` / `@StringRes Int` | `0` = SDK layout / app icon / app label |
| | `minDisplayTimeMs` / `remoteFetchTimeoutMs` / `consentTimeoutMs` / `billingTimeoutMs` | `Long` | `3_000` / `10_000` / `15_000` / `5_000` |
| | `adLoadStrategy` | `AdLoadStrategy` | `ALTERNATE`; `SAME_TIME` fetch के दौरान ही ads load करता है |
| `LanguageConfig` | `languages` / `defaultCode` | `List<ObLanguage>` / `String?` | `ObLanguages.ALL` (21 languages, flags शामिल) / `null` |
| | `secondNativeOnSelectEnabled` / `tapHintEnabled` / `confirmVisibleBeforeSelect` | `Boolean` | `true` |
| `BehaviorConfig` | `lockPagerSwipe` / `backNavigatesBack` / `reloadAdOnStepReturn` | `Boolean` | `true` / `true` / `false` |
| `SystemBarConfig` | `showStatusBar` / `showNavigationBar` | `Boolean` | `true` |
| `QuestionConfig` | `titleRes` / `ctaTextRes` / `title` | `@StringRes Int` / `CharSequence?` | `0` / `0` / `null` |
| (`null` इसे skip कर देता है) | `options` — `QuestionOption(id, title, titleRes, imageRes, imageUrl)` | `List<QuestionOption>` | `emptyList()`; खाली list भी screen skip कर देती है |
| | `selectionMode` / `minSelection` / `refreshAdOnSelect` | `SelectionMode` / `Int` / `Boolean` | `MULTIPLE` / `1` (≥ 1) / `false` |

**Steps.** `ContentStepDefinition(id, titleRes = 0, subtitleRes = 0, title = null, subtitle = null, imageRes = 0,
layoutRes = 0, showsProgressIndicator = true)` और `AdFullScreenStepDefinition(id, showSkipButton = true,
skipButtonDelaySec = 3, autoNextEnabled = false, autoNextDelayMs = 15_000, layoutRes = 0)`। `id` एक `StepId` है:
`OB1`…`OB5` — ये flow में position हैं, content pages नहीं: default template में OB3 ad-only page है, इसलिए तीसरा *content* page `StepId.OB4` है।

**AdsConfig.** `null` slot कोई ad नहीं दिखाता। हर native/interstitial slot एक waterfall है: ids सबसे ऊँचे floor से क्रम में, एक बार में एक request, और पहला fill मिलते ही रुक जाता है।

| Field | Type | Default |
|---|---|---|
| `enabled` / `skipAdOnlyStepsWhenPremium` | `Boolean` | `true` — master switch / premium user ad-only steps skip करता है |
| `splashBanner` | `BannerAdUnit?` | `null` |
| `splashInterstitial` / `splashInterstitialOldUser` | `InterstitialAdUnit?` | `null`; old-user दूसरे पर fall back करता है |
| `languageNative` / `languageDupNative` | `NativeAdUnit?` | `null`; dup `languageNative` पर fall back करता है |
| `contentStepNative`, `fullScreenStepNative`, `ob5Native`, `questionNative` | `NativeAdUnit?` | `null` |
| `stepNatives` | `Map<StepId, NativeAdUnit>` | `emptyMap()` — `contentStepNative` / `fullScreenStepNative` का per-page override; `stepNatives[OB5]` `ob5Native` को भी cover करता है |
| `questionInterstitial`, `appResume` | `InterstitialAdUnit?` | `null` |
| `contentStepTemplate` / `languageTemplate` / `questionTemplate` | `NativeTemplate` | `CTA_BOTTOM` |

**Native templates.** Template layout चुनता है; host के `ad_config.json` का `components` तय करता है कि कौन से
block दिखें और किस क्रम में।

| `NativeTemplate` | Layout | `components` क्या नियंत्रित करता है |
|---|---|---|
| `CTA_BOTTOM` | `ob_layout_native_cta_bottom` | क्रम + दिखाना/छिपाना |
| `COMPACT` | `ob_layout_native_compact` | सिर्फ़ दिखाना/छिपाना — दो-पंक्ति layout, vertical stack नहीं |
| `FULL_SCREEN` | `ob_layout_native_fullscreen` | सिर्फ़ दिखाना/छिपाना — text media के ऊपर overlay है, vertical stack नहीं |

`onboardKitConfig { }` इन हालात में `Result.failure(ObConfigException)` लौटाता है: `StepId` दोहराया गया; `minSelection < 1`;
question option ids दोहराए गए; language list खाली; कोई tier list जो पूरी blank हो, जिसमें blank id हो या कोई id दोहराई गई हो; `splashBanner.id` blank; पाँच अस्वीकृत `layoutRes` knobs में से कोई भी set किया गया हो।

**Ad-only steps.** `AdFullScreenStepDefinition` वाला page flow से हटा दिया जाता है जब उसका placement कभी fill हो ही नहीं सकता:
कोई ad unit नहीं, `enabled = false`, master या per-placement remote flag off, कोई provider नहीं, या consent अनुत्तरित। Step
count, progress indicator और resume index उसी के साथ छोटे हो जाते हैं। Premium हटाने की वजह नहीं है — वह
`skipAdOnlyStepsWhenPremium` के हिसाब से चलता है। जो page flow में आ चुका है और फिर fill नहीं हो पाता, वह `StepHost.skipAdStep(stepId)` से बाहर निकलता है।

## Custom layouts

किसी screen में सिर्फ़ `SplashConfig.layoutRes` और `ContentStepDefinition.layoutRes` ही पढ़े जाते हैं। बाकी पाँच `layoutRes`
knobs अस्वीकार कर दिए जाते हैं — उन्हें `0` पर ही छोड़ें और उसी नाम का SDK layout override करें, उसमें declare की गई हर id रखते हुए।

| अस्वीकृत knob | इसकी जगह यह layout override करें |
|---|---|
| `LanguageConfig.layoutRes` / `.itemLayoutRes` | `ob_activity_language.xml` / `ob_item_language.xml` |
| `QuestionConfig.layoutRes` / `.optionLayoutRes` | `ob_activity_question.xml` / `ob_item_question_option.xml` |
| `AdFullScreenStepDefinition.layoutRes` | `ob_fragment_ad_step.xml` |

- Splash हर id को null-safe तरीक़े से bind करता है, इसलिए जो id आप छोड़ देंगे वह skip हो जाएगी।
- Content-step layout में उसकी **सारी** ids होनी चाहिए, वरना वह page एक log के साथ SDK layout पर fall back कर जाता है।
- Native templates (`ob_layout_native_*`) standard AdMob ids इस्तेमाल करते हैं; आप जो template override करें, उसमें पहले से declare की गई ids रखें।

| Screen | Id | Type |
|---|---|---|
| Splash | `ob_splash_logo` / `ob_splash_app_name` / `ob_splash_progress` | `ImageView` / `TextView` / `ProgressBar` |
| | `ob_splash_ad_container` | `FrameLayout`; इसके अंदर `<include layout="@layout/layout_banner_control" />` रखें वरना splash banner को attach होने की जगह ही नहीं मिलेगी |
| Content step | `ob_step_image` / `ob_step_player` / `ob_step_card` | `ImageView` / `androidx.media3.ui.PlayerView` / `LinearLayout` |
| | `ob_step_title` / `ob_step_subtitle` / `ob_step_indicator` / `ob_primary_cta` | `TextView` / `TextView` / `ObStepIndicator` / `ObPrimaryButton` |
| | `ob_ad_block` / `ob_native_container` | `FrameLayout` (slot मना होने पर block छिप जाता है) / `FrameLayout` |

Flow के अंदर अपनी किसी screen के लिए, `showInterstitial(placement, onNext, onFinished)` `AppCompatActivity` पर एक public
extension है: destination को `onNext` में start करें (ad के पीछे), और मौजूदा screen को `onFinished` में finish करें। दोनों
ज़्यादा से ज़्यादा एक बार चलते हैं, हर रास्ते पर `onNext` हमेशा पहले; `onNext` से कभी `finish()` न बुलाएँ। Native के लिए कोई public
समकक्ष नहीं है — अपने natives `:ads` के `NativeAdHelper` से render करें।

## Remote config keys

Defaults `ObRemoteKeys` में हैं; कुछ भी publish न करें तो नीचे दिए defaults ही लागू रहते हैं।

| Key | Type | Default | यह क्या करती है |
|---|---|---|---|
| `ob_enable_all_ads` / `ob_enable_ui_content` | Boolean | `true` | Master ad kill switch / server-driven UI on-off |
| `ob_enable_step_ob1` … `ob_enable_step_ob4` | Boolean | `true` | एक step को toggle करना |
| `ob_enable_step_ob5` | Boolean | `false` | Pager के बाद वाली अलग full-screen ad screen |
| `ob_enable_question` / `ob_enable_question_old_user` | Boolean | `true` / `false` | नए users के लिए survey / जो पहले ही flow पूरा कर चुके हैं उनके लिए |
| `ob_enable_language_native_2` / `ob_pass_lfo_if_completed` | Boolean | `true` | पहले language tap पर दूसरा native / language चुनी जा चुकी हो तो language screen skip |
| `ob_show_language_tap_hint` / `ob_show_language_confirm_before_select` | Boolean | `true` | Hand hint / चुनने से पहले confirm button; दोनों अपनी-अपनी `LanguageConfig` field के साथ AND होती हैं |
| `ob_language_supported_codes` | String | `""` | CSV filter और क्रम; खाली = पूरा catalog |
| `ob_reuse_splash_inter` | Boolean | `true` | Pager के अंत में buffered splash interstitial दोबारा इस्तेमाल करना |
| `ob_ads_splash_banner_enabled`, `ob_ads_splash_inter_enabled`, `ob_ads_language_native_enabled`, `ob_ads_content_native_enabled`, `ob_ads_fullscreen_native_enabled`, `ob_ads_question_native_enabled`, `ob_ads_question_inter_enabled`, `ob_ads_app_resume_enabled` | Boolean | `true` | हर placement के लिए एक switch, हर एक `ob_enable_all_ads` के साथ AND होती है |
| `ob_splash_min_display_ms` / `ob_splash_ad_budget_ms` / `ob_splash_banner_wait_ms` | Long | `3000` / `60000` / `0` | > 0 होने पर `SplashConfig.minDisplayTimeMs` को override करती है / splash interstitial की पूरी waterfall का budget (हर floor के लिए 30 s) / splash banner के लिए कितनी देर रुके |
| `ob_skip_button_delay_sec` / `ob_fullscreen_auto_dismiss_sec` | Long | `3` / `15` | `AdFullScreenStepDefinition.skipButtonDelaySec` को override करती है / OB5 auto-dismiss, न्यूनतम 5 (pager pages `autoNextDelayMs` इस्तेमाल करते हैं) |
| `ob_show_skip_ob3` / `ob_show_skip_ob5` | Boolean | `true` | Ad-only pager page पर skip button / OB5 पर |
| `ob_ui_content` / `ob_ui_design_tokens` | String | `""` | Per-step JSON (title, subtitle, colors, image या video) और उसके color/typography tokens |
| `ob_question_config` / `ob_config_version` | String / Long | `""` / `0` | पूरी compile-time option list की जगह लेने वाला JSON / local cache साफ़ करने के लिए value बदलें |

`ob_ads_splash_banner_enabled`, `ob_ads_splash_inter_enabled`, `ob_ads_app_resume_enabled`, `ob_splash_ad_budget_ms`
और `ob_splash_banner_wait_ms` locally cache नहीं होतीं: fetch आने से पहले cold start पर ये अपने defaults ही पढ़ती हैं।

## Analytics events

`Tracker.install()` और एक `Tracker.addSink(...)` जुड़ते ही ये अपने-आप emit होते हैं। Event की पहचान
`StepId` है, pager index कभी नहीं।

| चरण | Events |
|---|---|
| Flow | `fo_flow_start` (flow skip होने पर भी emit होता है), `fo_flow_complete` |
| Splash | `fo_splash_view`, `fo_splash_complete` |
| Language | `fo_language_view`, `fo_language_select`, `fo_language_complete`, `fo_language_flow_complete` |
| Steps | `fo_step_view`, `fo_step_complete` (`step`, `index`, `exit_reason` = `cta` / `skip` / `auto_next` / `ad_failed` / `auto_dismiss`) |
| Question | `fo_question_view`, `fo_question_answer`, `fo_question_complete` |
| Ads | `ad_request`, `ad_show`, `ad_load_failed`, `ad_skipped` (`reason`) |
| Paywall, screens | `iap_paywall_view`, `iap_paywall_result`; हर SDK screen पर एक `Tracker.screen(...)` |

`ad_skipped` के reasons: `premium`, `consent_not_granted`, `ads_off_config`, `no_provider`, `no_ad_unit`, `ads_off_remote`, `placement_off_remote`, `no_fill`, `not_ready`, `offline`, `ua_gate`, `capped_by_module`, `purchased_at_paywall`, `suppressed_by_flow`, `returning_from_ad_click`, `failed_to_show`। (`no_handshake` अब नहीं आता।)

इन्हें ख़ुद पाने के लिए `install` के अंदर `analyticsPlugin { event -> log(event.name, event.params) }` जोड़ें, या
`OnboardingSdk.events` / `.state` collect करें। Plugin को SDK के अपने `ob_*` event names दिखते हैं, ऊपर वाली `fo_*`
taxonomy नहीं — वह सिर्फ़ `Tracker` की तरफ़ मौजूद है। `isCompleted()`, `selectedLanguage()`, `answers()`, `markCompleted()` और `reset()` persisted progress पढ़ते और साफ़ करते हैं।

## Paywall gate

```kotlin
class MyPaywallGate : PaywallGate {
    override suspend fun shouldShow(placement: PaywallPlacement) =
        placement == PaywallPlacement.AFTER_ONBOARDING && !myEntitlement.isPremium

    override suspend fun present(activity: Activity, placement: PaywallPlacement): PaywallOutcome =
        PaywallOutcome.Dismissed   // or Purchased / ContinueWithAds
}
```

Placements: `SPLASH_INTER`, `AFTER_ONBOARDING`, `AFTER_QUESTION_OLD_USER`। `paywallGate` set न करें तो
हर checkpoint सीधे निकल जाता है।

## Troubleshooting

| लक्षण | कारण | Fix |
|---|---|---|
| Flow कभी चलता ही नहीं | `configure()` fail हुआ, या `install()` से पहले चला | `Result` को log करें; पहले `install()` बुलाएँ |
| User flow से बाहर ही नहीं निकलता | कोई `OnboardingListener` नहीं, या वह `Skipped` को अनदेखा करता है | तीनों outcomes handle करें |
| हर placement `no_provider` कहता है | `adProvider` null छोड़ दिया गया | `adProvider = ERainAdProvider()` |
| हर placement `consent_not_granted` कहता है | `consentTimeoutMs` के अंदर UMP form अनुत्तरित रहा | `ConsentOptions(testDeviceHashedId = …)` set करें |
| Ad-only page कभी दिखता ही नहीं | `fullScreenStepNative` / `stepNatives[OB3]` के लिए कोई काम का unit नहीं | एक configure करें; अकेला `ob_enable_step_ob3` काफ़ी नहीं है |
| Splash banner कभी नहीं दिखता | `ob_splash_ad_container` या `layout_banner_control` का include मौजूद नहीं | दोनों अपने splash layout में जोड़ें |

## License

MIT — देखें [`../LICENSE`](../LICENSE)।
