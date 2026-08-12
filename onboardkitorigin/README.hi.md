# OnboardKit (HI)

> English: **[README.md](README.md)** · Tiếng Việt: **[README.vi.md](README.vi.md)**

पहली बार app खोलने का पूरा flow, एक library के रूप में: splash → language → onboarding steps →
वैकल्पिक full-screen ad → वैकल्पिक question screen → आपका app। Ads, remote config, state
persistence, analytics funnel और "user यहाँ फँसना नहीं चाहिए" वाली हर गारंटी अंदर ही है। आप सिर्फ़
ad unit ids, content, और flow ख़त्म होने पर कहाँ जाना है — यह देते हैं।

- Namespace `io.onboardkit` · resource prefix `ob_` · entry point `OnboardingSdk`
- Ads `OnboardingAdProvider` interface से जाते हैं। `ERainAdProvider` इसे `:ads` (ERainAd/AdMob) से
  जोड़ता है; आप अपना implementation दे सकते हैं, या ads-रहित flow के लिए `null`।
- Analytics `:trackkit` के `Tracker` से जाते हैं। एक sink जोड़िए और पूरा funnel खुद रिपोर्ट करता है।

**[`../trackkit/README.hi.md`](../trackkit/README.hi.md) भी पढ़िए।** `Tracker.install()` और एक sink
के बिना, यह SDK जो भी event भेजता है वह validate होकर फेंक दिया जाता है।

---

## 1. Gradle setup

```groovy
dependencies {
    implementation 'com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:1.3.0'
    implementation 'com.github.truongvimit.adlogic-partner-sdk:ads:1.3.0'          // ERainAdProvider के लिए
    implementation 'com.github.truongvimit.adlogic-partner-sdk:trackkit-firebase:1.3.0'
}
```

`onboardkitorigin`, `ads` पर runtime scope में निर्भर है, इसलिए `com.ads.module.*` उसके ज़रिए आपके
compile classpath पर **नहीं** आता — अगर आप `ERainAdProvider` बनाते हैं या ad APIs सीधे छूते हैं तो
`ads` अलग से declare कीजिए।

JDK 17 और `minSdk` 24 चाहिए। flow की चारों activities library manifest में घोषित हैं और अपने-आप
merge होती हैं; आपको अपने manifest में **कुछ नहीं जोड़ना**।

---

## 2. चार चरणों में integration

### 2.1 `Application.onCreate()` — install

क्रम मायने रखता है। `Tracker.install()` पहले आता है: उससे पहले भेजे गए events buffer होते हैं, खोते
नहीं, पर वे उसी session से जुड़ते हैं जो install के समय चल रहा हो।

```kotlin
override fun onCreate() {
    super.onCreate()

    initTracking()        // Tracker.install + addSink — trackkit/README.hi.md देखें
    initAds()             // ERainAd.getInstance().init(this, config)
    initOnboardKit()      // नीचे
}

private fun initOnboardKit() {
    OnboardingSdk.install(this) {
        adProvider = ERainAdProvider()        // या ads न चाहिए तो null
        paywallGate = MyPaywallGate()         // वैकल्पिक, §6 देखें
        listener = OnboardingListener { ctx, outcome ->
            if (outcome is OnboardingOutcome.Completed) {
                outcome.selectedLanguage?.let { AppPrefs(ctx).languageCode = it }
            }
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    OnboardingSdk.configure(buildConfig()).onFailure { Log.e(TAG, "OnboardKit config अस्वीकृत", it) }
}
```

`install()` synchronous और हल्का है। `configure()` एक `Result` लौटाता है — **उसे जाँचिए।** अस्वीकृत
config लागू नहीं होता, और उसके बाद flow खुद को skipped बताता है, कोई और लक्षण नहीं दिखता।

### 2.2 Config

```kotlin
private fun buildConfig() = onboardKitConfig {
    splash = SplashConfig(
        logoRes = R.drawable.ic_logo,
        minDisplayTimeMs = 3_000,
    )
    language = LanguageConfig(defaultCode = "en")

    defaultSteps()                            // OB1, OB2, OB3 (full-screen ad), OB4

    question = QuestionConfig(
        options = listOf(
            QuestionOption("romance", "Romance", imageRes = R.drawable.opt_romance),
            QuestionOption("scifi", "Sci-Fi", imageRes = R.drawable.opt_scifi),
        ),
    )

    ads = AdsConfig(
        splashInterstitial   = InterstitialAdUnit("ca-app-pub-…/1111"),
        splashBanner         = BannerAdUnit("ca-app-pub-…/2222"),
        languageNative       = NativeAdUnit.waterfall(highFloor = "…/3333", allPrice = "…/4444"),
        languageDupNative    = NativeAdUnit("ca-app-pub-…/5555"),
        contentStepNative    = NativeAdUnit("ca-app-pub-…/6666"),
        fullScreenStepNative = NativeAdUnit("ca-app-pub-…/7777"),
        ob5Native            = NativeAdUnit("ca-app-pub-…/8888"),
        questionNative       = NativeAdUnit("ca-app-pub-…/9999"),
        questionInterstitial = InterstitialAdUnit("ca-app-pub-…/0000"),
    )
}.getOrThrow()
```

`defaultSteps()` OB1–OB4 का template है। अपने steps चुनने के लिए:

```kotlin
steps(
    ContentStepDefinition(StepId.OB1, titleRes = R.string.ob1_title, imageRes = R.drawable.ob1),
    AdFullScreenStepDefinition(StepId.OB3, showSkipButton = true, skipButtonDelaySec = 3),
    ContentStepDefinition(StepId.OB4, layoutRes = R.layout.my_ob4),   // आपका layout, §4 देखें
)
```

**Steps का क्रम code में तय है**; remote config सिर्फ़ किसी step को बंद कर सकता है, क्रम कभी नहीं
बदल सकता। यह जानबूझकर है — remote से पुनःक्रमित होने वाली सूची ही वह कारण थी जिससे audit किया गया
SDK, कोई step बंद होने पर ग़लत step का completion event भेजने लगता था।

### 2.3 Splash — subclass कीजिए, copy नहीं

आपकी launcher activity `ObSplashActivity` को extend करती है। timing, चार समानांतर tasks, barrier
और interstitial सब पहले से अंदर हैं; आप बस ज़रूरी hooks भरते हैं।

```kotlin
class SplashActivity : ObSplashActivity() {

    override suspend fun onConsentRequired() {
        // UMP यहाँ दिखाइए; नतीजा आने पर return कीजिए। बाहर एक hard timeout फिर भी लगा रहता है।
        // अपने consent callback से Tracker.setConsent(analytics, ads) ठीक एक बार बुलाइए।
    }

    override suspend fun onInitBilling() { /* AppPurchase init */ }

    override fun onRemoteFetched() { /* आपकी अपनी remote keys तैयार हैं */ }
}
```

इसे manifest में हमेशा की तरह launcher घोषित कीजिए। `OnboardingSdk.start()` **खुद मत बुलाइए** —
`ObSplashActivity` अपना pipeline पूरा होते ही बुला देता है।

### 2.4 Settings से दोबारा प्रवेश

```kotlin
OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)
```

`SETTINGS` mode में ads नहीं दिखते, असली back button होता है, और यह first-open funnel से बाहर रहता
है ताकि आपका LFO conversion बढ़ा-चढ़ाकर न दिखे।

---

## 3. जो अपने-आप मिलता है

नीचे किसी चीज़ के लिए आपके call site की ज़रूरत नहीं। बस एक `TrackSink` registered होना चाहिए।

| चरण | Events |
|---|---|
| Flow शुरू | `fo_flow_start` — denominator, flow skip होने पर भी भेजा जाता है |
| Splash | `fo_splash_view`, `fo_splash_complete` (`dwell_ms`) |
| Language | `fo_language_view`, `fo_language_select`, `fo_language_complete` (सब में `screen_index`), `fo_language_flow_complete` |
| Steps | `fo_step_view`, `fo_step_complete` — `step`, `index`, `dwell_ms`, `exit_reason` (`cta` / `skip` / `auto_next` / `ad_failed` / `auto_dismiss`) के साथ |
| Question | `fo_question_view`, `fo_question_answer`, `fo_question_complete` |
| Flow ख़त्म | `fo_flow_complete` (`steps_shown`, `dwell_ms`) |
| Ad slots | `ad_request`, `ad_show`, `ad_load_failed`, `ad_skipped` (`reason` = `policy` / `no_ad_unit` / `not_ready`) |
| Paywall | `iap_paywall_view`, `iap_paywall_result` (`status`) |
| Screens | flow की हर screen के लिए `screen_view` |

Event की पहचान **step id** है, pager index कभी नहीं — इसलिए कोई step बंद करने से दूसरा step ग़लत
नाम पर नहीं खिसक सकता।

इन्हें अपने code में भी पाने के लिए एक plugin जोड़िए:

```kotlin
OnboardingSdk.install(this) {
    analyticsPlugin { event -> myOwnLogger.log(event.name, event.params) }
}
```

आप `OnboardingSdk.events` से flow को `Flow<OnboardingEvent>` की तरह देख भी सकते हैं, या
`isCompleted()`, `selectedLanguage()`, `answers()` से state पढ़ सकते हैं।

---

## 4. अपना layout देना — id का अनुबंध

किसी step पर `layoutRes`, या `LanguageConfig.layoutRes`, या `SplashConfig.layoutRes` दीजिए। SDK id
से bind करता है, इसलिए ये ids मौजूद होनी **चाहिए**, वरना वह slot छोड़ दिया जाता है:

| Id | प्रकार | कहाँ |
|---|---|---|
| `ob_native_container` | `FrameLayout` | native वाली हर screen |
| `ob_native_shimmer` | shimmer include | container के बगल में |
| `ob_ad_block` | `ViewGroup` | wrapper, slot अस्वीकृत होने पर छिपता है |
| `ob_primary_cta` | `ObPrimaryButton` | content steps |
| `ob_step_indicator` | `ObStepIndicator` | content steps |
| `ob_skip_button` | `View` | full-screen ad screens |
| `ob_ad_block_2`, `ob_native_container_2`, `ob_native_shimmer_2` | ऊपर जैसा | **सिर्फ़ language screen** — दूसरा native slot |
| `ob_splash_logo`, `ob_splash_app_name`, `ob_splash_progress`, `ob_splash_ad_container` | | splash |

Native templates मानक AdMob ids (`ad_headline`, `ad_media`, `ad_call_to_action`, …) इस्तेमाल करते
हैं ताकि `Admob.populateUnifiedNativeAdView` उन्हें bind कर सके।

---

## 5. Remote config keys

सभी keys पर `ob_` prefix है ताकि आपके app के अपने namespace से टकराव न हो। Default values code में
हैं (`ObRemoteKeys`) — sync रखने के लिए कोई defaults XML नहीं।

**Kill switches** `ob_enable_all_ads`, `ob_enable_ui_content`
**Steps** `ob_enable_step_ob1`…`ob5`, `ob_enable_question`, `ob_enable_question_old_user`
**Language** `ob_enable_language_native_2`, `ob_pass_lfo_if_completed`, `ob_language_supported_codes` (CSV)
**Ads** `ob_reuse_splash_inter`, `ob_ads_{language,content,fullscreen,question}_native_enabled`, `ob_ads_question_inter_enabled`, `ob_ads_splash_inter_id`
**Timing** `ob_splash_min_display_ms`, `ob_skip_button_delay_sec`, `ob_fullscreen_auto_dismiss_sec`
**Skip buttons** `ob_show_skip_ob3`, `ob_show_skip_ob5`
**Templates** `ob_native_template_{content,language,question}` = `cta_top` | `cta_bottom` | `compact`
**Server-driven UI** `ob_ui_content`, `ob_ui_design_tokens`, `ob_question_config`
**Cache stamp** `ob_config_version` — मान बदलिए, local UI cache साफ़ हो जाएगा

`ob_enable_step_ob5` का default **false** है: OB5 एक स्वतंत्र full-screen ad screen है, जब तक आप
जानबूझकर न माँगें तब तक बंद।

---

## 6. Paywall (वैकल्पिक)

```kotlin
class MyPaywallGate : PaywallGate {
    override suspend fun shouldShow(placement: PaywallPlacement) =
        placement == PaywallPlacement.AFTER_ONBOARDING && !AppPurchase.getInstance().isPurchased
    override suspend fun present(activity: Activity, placement: PaywallPlacement): PaywallOutcome {
        return PaywallOutcome.Dismissed
    }
}
```

Placements: `SPLASH_INTER`, `AFTER_ONBOARDING`, `AFTER_QUESTION_OLD_USER`। हर presentation
`iap_paywall_view` + `iap_paywall_result` रिपोर्ट करती है; ख़रीद स्वयं billing layer द्वारा
`iap_success` के रूप में रिपोर्ट होती है, इसलिए revenue कभी दो बार नहीं गिना जाता।

---

## 7. Integration checklist

- [ ] `Tracker.install()` **और** कम से कम एक `Tracker.addSink(...)` — वरना कोई event नहीं पहुँचेगा
- [ ] `Tracker.setConsent(analytics, ads)` ठीक एक बार, UMP callback से
- [ ] `OnboardingSdk.install()` फिर `configure()`, दोनों `Application.onCreate()` में
- [ ] `configure()` का result जाँचा गया हो, फेंका न गया हो
- [ ] Launcher activity `ObSplashActivity` को extend करती हो
- [ ] जो placement आपने चालू किया उसके लिए ad unit id हो — खाली id `ad_skipped/no_ad_unit` रिपोर्ट करेगा
- [ ] Remote keys ऊपर दिए defaults के साथ publish हों, या बिल्कुल न हों (तब code के defaults लगते हैं)
- [ ] `OnboardingListener` `Completed` **और** `Skipped` — दोनों पर कहीं navigate करे
- [ ] Custom layouts में §4 की ids मौजूद हों
- [ ] Debug build पर जाँचिए: `ConsoleSink` SDK से निकलने वाला हर event print करता है

---

## 8. पुराने SDK से जानबूझकर किए गए अंतर

- `lastCompletedStep` checkpoint: flow के बीच app बंद हो जाए तो सही step से दोबारा शुरू होता है,
  language screen से नहीं।
- LFO2 (उसी screen पर दूसरा native impression) और उत्तर पर ad refresh — दोनों **default में बंद**;
  config + remote से चालू कीजिए।
- Premium हर screen पर ads छिपाता है, OB5 सहित, और सिर्फ़-ad वाले steps पूरी तरह हटा सकता है।
- Full-screen ad screen में हमेशा एक रास्ता बाहर होता है: auto-next बंद हो तो Skip ज़बरदस्ती दिखता
  है, साथ में remote-configured auto-dismiss भी।
- Question के उत्तर DataStore में सहेजे जाते हैं और analytics को भेजे जाते हैं — मूल SDK न सहेजता
  था, न log करता था।
- Remote से आया कोई ख़राब step या option सिर्फ़ वही एक तत्व छोड़ता है, पूरी screen नहीं गिराता।
- `fo_flow_complete` **हर** exit path पर fire होता है। audit किए गए SDK में समतुल्य event तीन में से
  दो रास्तों पर छूट जाता था, इसलिए ज़्यादातर users उसे कभी उत्पन्न ही नहीं करते थे।
