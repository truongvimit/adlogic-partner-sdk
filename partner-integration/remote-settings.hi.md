# Ads behavior और onboarding settings

**OB catalog:** `ob1..ob4` → `native_ob1..4`; `full1/full2` → `native_full1/2`. Default: `ob1, full1, ob2, full2, ob3, ob4`. All eligible OB natives preload on language selection. Remote `onboarding.order` selects/reorders app-declared steps. [Configuration and migration / Hướng dẫn chi tiết](onboarding-flow.vi.md). `native_fs` remains the separate splash native.

JSON में पेज चुनने और उनका क्रम तय करने के लिए केवल `onboarding.order` इस्तेमाल करें; पेज हटाने के लिए उसका ID निकालें। `steps` केवल वैकल्पिक template, behavior या fullscreen overrides के लिए है। `steps.<id>.enabled` अब अनदेखा किया जाता है। Remote `order` backend द्वारा भेजे गए पुराने `ob_enable_step_ob1..4` flags से प्राथमिकता रखता है, और वे flags app asset के `order` से। App में `enabled = false` से declare किया गया page तब तक छिपा रहता है जब तक remote `order` उसे सूची में न रखे या backend द्वारा भेजा गया `ob_enable_step_obN = true` उसे चालू न करे; app asset का `order` उसे छिपा ही रखता है। जो page app ने कभी declare नहीं किया, उसे remote नहीं जोड़ सकता। हर ad placement को `ad_config.<placement>.isEnable` से नियंत्रित करें: ads बंद होने पर content पेज रहता है, fullscreen ad पेज छोड़ दिया जाता है।

[English](remote-settings.md) · [Tiếng Việt](remote-settings.vi.md) · [हिन्दी](remote-settings.hi.md)

Console setup के लिए [तीन String parameters publish करने के कदम](firebase-integration.hi.md#remote-json) देखें। Custom offline defaults के लिए [app में दो local JSON files बनाना](firebase-integration.hi.md#local-defaults) देखें। नीचे के पूरे उदाहरण SDK assets से मेल खाते हैं।

सभी modules के लिए [JitPack](https://jitpack.io/#truongvimit/adlogic-partner-sdk) का **newest SDK version** इस्तेमाल करें। केवल Firebase keys जोड़ने से app में integrated पुराना SDK update नहीं होता।

## Documents और field ownership

मौजूदा Firebase parameter `ad_remote_config` ही है; `ad_config.json` / `ad_config_debug.json` उसके local asset filenames हैं। सिर्फ दो नए String parameters जोड़ें:

| Parameter | पूरा default JSON | SDK source |
| --- | --- | --- |
| `ad_behavior_config` | [Copy/paste sample](examples/ads-onboarding/ad_behavior_config.json) | [SDK asset](../ads/src/main/assets/ad_behavior_config.json) |
| `onboarding_config` | [Copy/paste sample](examples/ads-onboarding/onboarding_config.json) | [SDK asset](../onboardkitorigin/src/main/assets/onboarding_config.json) |

- **ad_config:** `id`, `ids`, `isEnable`, `enable_ua_check`, `reloadIntervalSeconds`, `colorCTA`, `heightCTA`, `positionCTA`, `components`, `open_resume.app_resume_load_delay_ms`। नए documents इन fields, ad-unit mappings या individual unit switches को दोहराते नहीं हैं।
- **ad_behavior_config:** format के अनुसार timeout/cache, reload/preload policy, frequency/AutoBuffer, consent timeout, telemetry, native CTA radius और app-open behavior। Banner type/size SDK presets हैं।
- **onboarding_config:** flow steps, X/Skip timing/style, auto-next, swipe/back/click-return, splash strategy, LFO/OB preload, exit/question behavior, native templates, LFO confirmation appearance और app के language catalog में से चयन। Step चालू करने से `isEnable=false` वाला ad unit चालू नहीं होता।
- **App code/resources:** `R.layout`, `R.drawable`, `R.string`, custom page layouts, language resources/catalog, progress indicators, system bars/orientation और Activity exclusions। SDK ad-presentation presets remote से बदले जा सकते हैं।

हटाए गए aliases `app_open.presentation.excluded_hosts`, `app_open.enabled`, `app_open.load.background_delay_ms`, `banner.reload.interval_ms`, placement/native-placement mappings, duplicate unit switches और app-content groups `ui`/`question.content` ignore होते हैं, पुराने cached JSON में भी। पुराने remote UI APIs `ob_ui_content`, `ob_ui_design_tokens`, `ob_question_config`, `ob_enable_ui_content` से चलते रहते हैं; वे नए grouped documents में fields नहीं हैं। Valid `ob_question_config` नए users को question दिखाता है, भले app ने कोई `QuestionConfig` configure न किया हो; उसका `cta_text` question button का text तय करता है, `QuestionConfig.ctaTextRes` fallback है, और minimum selection दिखाए गए options तक सीमित (clamp) होता है। यह button ad CTA से अलग है।

## App को एक बार configure करें

[Partner setup sample](examples/ads-onboarding/OnboardKitSetup.kt) इस्तेमाल करें। Content/resources app में रखें; SDK इस्तेमाल के समय वर्तमान ad document resolve करता है:

```kotlin
ads = AdsConfig.fromAdConfig()
```

यह `onboardKitConfig` builder का default भी है। Application startup पर IDs copy करने के बजाय placement keys बने रहते हैं। Fetch के बाद page eligibility, preload और display वर्तमान units लेते हैं; दूसरा `configure()` आवश्यक नहीं। Usable unit न होने पर ad-only page pager खोलने से पहले हटता है। Raw IDs के साथ manual `AdsConfig(...)` अब भी supported है, लेकिन नीचे की जो key backend का `ad_remote_config` declare करता है वह code में लिखे units से ऊपर है, `stepNatives`, `splashInterstitialOldUser` और `splashInterstitialOverride()` समेत; code units उन placements पर लागू होते हैं जिनके बारे में remote कुछ नहीं कहता। App की अपनी `ad_config.json` code को केवल उन keys के लिए override करती है जिन्हें आप `AdsConfig.fromAdConfig` से bind करते हैं।

| Slot | Default ad_config key |
| --- | --- |
| Splash banner / interstitial | `banner_splash` / `inter_splash` |
| Splash native (नीचे का slot, जब `splash.ads.slot_format` = `NATIVE`) | `native_splash` |
| Returning-user splash | `<splash interstitial key>_o` (`inter_splash_o`); `_o` declare न हो तो returning users को `inter_splash` के units मिलते हैं |
| Notification / widget / uninstall entry splash | `inter_noti` / `inter_widget` / `inter_uninstall`; key न हो या बंद हो तो user segment की key इस्तेमाल होती है |

नए users के लिए `inter_splash` और returning users के लिए `inter_splash_o` (`_o` declare न हो तो `inter_splash`) master switch है: वहाँ `isEnable: false` उस segment के हर splash interstitial को बंद करता है, entries समेत। Entry अपनी key तभी इस्तेमाल करती है जब वह declare हो और चालू हो, वरना segment की key। Load और show दोनों उसी key से gate होते हैं। पुरानी Firebase key `ob_ads_splash_inter_enabled=false` सभी users के लिए हर splash interstitial बंद करती है।
| LFO1 / LFO2 / confirmation dialog | `native_lang` / `native_lang_alt` / `native_popup_lang` |
| Content OB1 / OB2 / OB3 / OB4 | `native_ob1` / `native_ob2` / `native_ob3` / `native_ob4` |
| Fullscreen Full1 / Full2 | `native_full1` / `native_full2` |
| OB5 | `native_onboarding_fullscreen_1_4` |
| Question native / interstitial | `native_question` / `inter_question` |
| Exit interstitial | `inter_after_ob3` |
| App resume | `open_resume`; backend इसे ID के साथ declare करे तो `idAdResume` seed के बिना भी app-open चालू हो जाता है। App-open बंद रखने के लिए `AppOpenManager.getInstance().disableAppResume()` बुलाएँ |

### Splash का निचला slot

Splash में loading bar के नीचे एक ही ad slot है, और `splash.ads.slot_format` तय करता है कि उसे कौन
सा format भरेगा:

| Value | Placement | ad_config key |
| --- | --- | --- |
| `BANNER` (default) | `AdPlacement.SplashBanner` | `banner_splash` |
| `NATIVE` | `AdPlacement.SplashInlineNative` | `native_splash` |

Native को दोनों चाहिए: `slot_format` को `NATIVE` **और** ad_config में एक usable `native_splash`
entry। इनमें से कोई एक भी न हो तो slot खाली ही रहता है।

एक launch सिर्फ़ चुने हुए format को request करता है, इसलिए यह बदलना खर्च को हटाता है, दूसरा impression
नहीं जोड़ता। इंतज़ार भी साझा है: `splash.timing.slot_min_visible_ms` interstitial को उस format से दूर रखता है जो slot भर
रहा हो, और `ob_ads_splash_banner_enabled` दोनों के लिए इस position को बंद करता है।

यह window slot के **load** होने और splash के **स्क्रीन वापस पाने** में से जो बाद में हो, वहाँ से चलती है — दोनों
सच होने पर ही कोई उसे देख सकता है: notification dialog के पीछे भरा ad स्क्रीन पर था, पर user के सामने नहीं।
जो slot fail हो, skip हो या जिसका ad unit न हो, वह window शुरू ही नहीं करता और उसका इंतज़ार नहीं होता;
जिस slot ने कोई जवाब नहीं दिया — न load हुआ, न fail — उसका इंतज़ार notification dialog बंद होने से
ज़्यादा से ज़्यादा `splash.timing.slot_wait_ms` तक होता है, या `splash.timing.ad_budget_ms` पहले ख़त्म हो
तो तब तक; उसके बाद interstitial उसके बिना दिखता है। हर hold उसी budget के बचे हुए हिस्से तक सीमित रहता है। Vendor impression बिल्कुल नहीं देखा जाता,
क्योंकि collapsible banner कभी report नहीं करता। `0` पुराना असुरक्षित व्यवहार लौटाता है।

Native एक तय media-left frame से render होता है, इसलिए `positionCTA` और `components` का क्रम बेअसर
हैं — `colorCTA` और `heightCTA` फिर भी लागू होते हैं। `AdPlacement.SplashInlineNative` और
`AdPlacement.SplashNative` अलग हैं; दूसरा splash interstitial के बाद दिखने वाला optional full-screen
native (`native_fs`) है। Code में यह flag `io.onboardkit.config.SplashAdSlotFormat` है और ad units
`AdsConfig.splashInlineNative` में resolve होते हैं।

Banner notification permission dialog के पीछे render होता है और native उसके बंद होने पर bind होता है,
लेकिन नीचे दी गई गारंटी दोनों के लिए एक जैसी है, क्योंकि वह impression से नहीं बल्कि splash को screen
वापस मिलने के क्षण से नापी जाती है।

**5.4.0 में अपग्रेड।** `AdPlacement` एक sealed interface है और यह release उसमें
`SplashInlineNative` जोड़ता है, इसलिए आपका exhaustive `when (placement)` — सबसे अधिक संभावना custom
`OnboardingAdProvider` में — तब तक compile नहीं होगा जब तक नए placement के लिए एक branch न जुड़े।
इसके अलावा कुछ नहीं टूटता: `AdsConfig.splashInlineNative` का default है इसलिए मौजूदा constructor
calls अप्रभावित हैं, और bundled `slot_format` `BANNER` ही रहता है, इसलिए बिना बदलाव वाला app पहले
जैसा ही चलता है।



केवल अलग नाम वाली associations code में घोषित करें:

```kotlin
ads = AdsConfig.fromAdConfig(mapOf(
    AdPlacement.Language1 to "my_language_native",
    AdPlacement.StepNative(StepId("custom")) to "my_content_native",
))
```

यही association `ad_behavior_config.placement_overrides.<key>` भी चुनती है: LFO1 सामान्यतः `native_lang` इस्तेमाल करता है, internal telemetry/buffer key `language1` नहीं। IDs, floors, unit switches और मौजूदा CTA fields ad_config में रहते हैं।

## Local defaults, remote cache और failure

`app/src/main/assets/ad_behavior_config.json` / `onboarding_config.json` नाम की app files SDK assets की जगह लेती हैं। पूरा sample copy करें या केवल बदलने वाले fields लिखें, फिर rebuild और process restart करें। App file न हो तो bundled defaults बिना manual setters के काम करते हैं। [Firebase guide](firebase-integration.hi.md#local-defaults) में दोनों छोटे, पूरे उदाहरण हैं।

- हर setting की precedence: remote `onboarding_config` / `ad_behavior_config` का valid field > backend द्वारा भेजी गई legacy `ob_*` key > custom app asset > host Kotlin config/hooks > bundled SDK defaults। किसी भी scope का remote किसी भी scope के app asset से ऊपर है। Cached valid remote fields startup पर भी इसी priority में हैं; जो keys backend ने कभी नहीं भेजीं वे गिनी नहीं जातीं।
- Successful fetch में missing parameter/field पिछला remote assignment हटाकर अगले नीचे वाले source पर fallback करता है। गलत type/enum/range और `null` ignore होते हैं और logcat tag `AdLogicSettings` में log होते हैं; valid `false`/`0` assignments बने रहते हैं।
- Fetch failure/timeout, malformed या blank पूरा JSON, unsupported schema होने पर अंतिम valid document बना रहता है; rejected document `AdLogicSettings` में log होता है। पहली run में valid remote cache न हो तो local/defaults रहते हैं। **Failed fetch valid remote cache के ऊपर local values लागू नहीं करता।** Splash के `splash.load.remote_fetch_timeout_ms` के बाद पहुँचा fetch भी बाकी session के लिए लागू होता है।
- `AdConfig.install` backend द्वारा भेजा गया अंतिम `ad_remote_config` (Firebase की last activated value) तुरंत लागू करता है, इसलिए धीमा या failed fetch asset पर नहीं, उसी document पर चलता है। Settings fetch fail होने पर भी `ad_remote_config` लागू होने से नहीं रुकता।
- Successful fetch पर remote overrides हटाने के लिए `{}` या `{"schema_version":1}` publish करें। Empty String malformed है, reset नहीं। नया remote object पुराने remote overrides को replace करता है, patch नहीं; missing fields अगले नीचे वाले source पर fallback करते हैं।
- Custom/partial app asset का हर मौजूद valid field explicit assignment है, `false`/`0` समेत। पूरी bundled asset की बिना बदली copy host constructor/setter fallback रखती है। Firebase की published default value remote है, SDK local default नहीं; source Firebase in-app defaults को fetched remote नहीं मानता।
- SDK defaults build के समय assets से generate होते हैं, Context से पहले उपलब्ध हैं और `null` नहीं रखते। अलग Kotlin/XML defaults maintain नहीं करने पड़ते। App के invalid fields fallback लेते हैं; app को नया parser नहीं चाहिए।
- Consent, premium, `setCanRequestAds(false)`, remote `global.ads_enabled=false`, runtime reload pause और lifecycle अब भी ads रोकते हैं। `AdsConfig.enabled=false` तब तक लागू रहता है जब तक remote `flow.ads_enabled=true` (या backend द्वारा भेजा गया `ob_enable_all_ads=true`) onboarding ads चालू न करे; app asset ऐसा नहीं कर सकता। AutoBuffer के लिए host का `start` integration आवश्यक है; JSON Activities या host features initialize नहीं करता।
- पुराने `ob_*` keys compatible रहते हैं। Backend द्वारा भेजी गई key अपनी value दोनों दिशाओं में तय करती है, grouped documents से नीचे और app asset/host से ऊपर; `ob_splash_min_display_ms <= 0` local value रखता है।
- Debuggable build remote `ad_remote_config` का हर field लागू करता है, लेकिन ad unit IDs `ad_config_debug.json` (debug file न हो तो `ad_config.json`) के ही रखता है; जो keys केवल remote declare करता है वे हटा दी जाती हैं, जब तक वे किसी slot को बंद न करें। एक `WARN` log pinned file का नाम बताता है, और `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` remote IDs भी ले लेता है। दोनों grouped documents release की तरह लागू होते हैं; नए parameters के automatic `_debug` variants नहीं हैं।

## Behavior scopes

Screen slot override > shared content/fullscreen OB override > placement override > format override > local/default। यह क्रम एक ही source के भीतर लागू होता है: किसी भी scope का remote value किसी भी scope के app-asset value से ऊपर है। Empty `behavior` object optional scope है, उसमें कोई leaf override नहीं। इसमें ad IDs, unit switches या app resource IDs न रखें।

| placement_overrides में format | Supported fields |
| --- | --- |
| banner | `reload.allowed`, `reload.auto_enabled`, `reload.resume_debounce_ms`, `presentation.*` |
| native | `click.action`, `load.tier_timeout_ms`, `reload.*`, `preload.*`, `presentation.auto_shimmer`, `presentation.empty_visibility`, `presentation.cta_corner_radius_dp` |
| interstitial | `load.tier_timeout_ms`, `load_and_show.wait_timeout_ms`, `load_and_show.buffer_wait_timeout_ms`, `presentation.loading_enabled`, `cache.max_age_ms` |
| rewarded | `load.tier_timeout_ms`, `cache.max_age_ms` |

OB interstitial slots tier और wait timeout support करते हैं; exit interstitial के लिए `onboarding.exit_interstitial.wait_timeout_ms`, `placement_overrides` और format `interstitial.load_and_show.wait_timeout_ms` से ऊपर है। Frequency, next-screen timing, pre-show delay, app-open और native cache TTL format scope में हैं। Custom steps `onboarding.steps.<id>.fullscreen.skip.{enabled,delay_ms,style,position}`, `.auto_next.{enabled,delay_ms}` तथा content `.native_template` support करते हैं। इनमें `position` केवल per-step है — इसके ऊपर `onboarding.fullscreen` या `flow` scope नहीं है।

Banner cadence positive `ad_config.<key>.reloadIntervalSeconds` से, नहीं तो host value से आती है (SDK default 15000 ms)। Interval सेट करना timer चालू नहीं करता। Initial app-open delay `open_resume.app_resume_load_delay_ms` में है (2000 ms)। Onboarding ad पर click अगला app-open छोड़ देता है, जब तक remote `app_open.presentation.skip_after_ad_click` को `false` न करे। Native click actions और defaults नीचे दिए हैं। Timer reload click replacement से अलग है। Cache age केवल documented SDK limit से कम की जा सकती है।

## Native click actions

`click.action` में `auto_next`, `none` या `reload` चुनें। पहले click/open callback पर केवल एक action तय होता है; वापस आने तक remote बदलने पर भी वही action रहता है।

- `reload`: click/open पर तुरंत replacement request शुरू होती है। वापस आने पर तैयार ad या उसी pending request का उपयोग होता है। कोई fixed delay नहीं; सामान्य app resume click reload नहीं है।
  नया ad सफलतापूर्वक bind होने तक पुराना ad बिना shimmer दिखता रहता है। Load fail होने पर पुराना ad और slot बने रहते हैं। Shimmer केवल पहली loading में, जब कोई ad नहीं है, दिखता है।
- `auto_next`: वापस आने पर onboarding page आगे जाता है; replacement request नहीं होती। LFO2 में चुनी हुई भाषा confirm होती है; LFO1 में current/default भाषा चुनकर LFO2 खुलता है।
- `none`: मौजूदा ad/page रखें; click reload या automatic navigation नहीं।

Defaults: LFO1/LFO2 और बाकी natives `reload`; onboarding pager के content/fullscreen pages `auto_next`। अलग splash native और OB5 `reload` हैं। Example में अंतिम content page का step ID `ob4` और fullscreen का `ob3` है।

`ad_behavior_config` में `native.click.action` या `placement_overrides.<key>.click.action` उपयोग करें। `onboarding_config` में नीचे दिए screen/group paths या `onboarding.steps.<id>.behavior.click.action` उपयोग करें। Explicit valid action पुराने `reload.on_ad_click` और `navigation.ad_click_return_completes_step` flags से ऊपर है: auto-next और click reload साथ नहीं चलते। Timer/resume refresh और fullscreen timeout अलग settings हैं।

`onboarding_config` example: LFO2 का default `reload` बदलकर automatic confirmation:

```json
{
  "lfo": {
    "native1": { "behavior": { "click": { "action": "reload" } } },
    "native2": { "behavior": { "click": { "action": "auto_next" } } }
  },
  "onboarding": {
    "steps": { "ob1": { "behavior": { "click": { "action": "none" } } } }
  }
}
```

## Native template, CTA और X/Skip प्रयोग

- `lfo.native_template`: `CTA_BOTTOM`; `onboarding.ads.content_template`: `CTA_TOP`; हर content-step का `native_template`: `""` यानी inherit; `question.native.template`: `CTA_BOTTOM`।
- Frame priority: remote template (per-step > screen/group) > backend के `ad_remote_config` से placement का `positionCTA` (`TOP`/`BOTTOM`) > custom app asset template (per-step > screen/group) > app की `ad_config.json` का `positionCTA` > host/SDK template। Unmodified SDK asset पुराने settings override नहीं करता। `positionCTA` स्वयं test करने के लिए संबंधित template override हटाएँ।
- Content presets `CTA_TOP`, `CTA_BOTTOM`, `COMPACT` हैं; group template fields पहले की तरह `FULL_SCREEN`/`DIALOG` भी लेते हैं। Language popup हमेशा `DIALOG`, ad-only Full1/Full2/OB5 हमेशा `FULL_SCREEN` इस्तेमाल करते हैं। Custom app layout resources local रहते हैं।
- Preload और show एक template resolver इस्तेमाल करते हैं। Host जल्दी preload करे या preload के बाद remote refresh हो तो bind वर्तमान SDK frame इस्तेमाल करता है, loaded ad हटाए बिना। दिखता हुआ view अगले bind तक बना रहता है। यह default splash order नहीं: दोनों strategies में LFO1 remote के बाद schedule होता है।
- Shared `flow.fullscreen_skip_style`, OB `onboarding.fullscreen.skip.style`, per-step `.fullscreen.skip.style` और `ob5.skip.style` में `CLOSE_ICON` / `TEXT` मान्य हैं। एक ही source के भीतर specific scope shared scope से पहले है (किसी भी scope का remote app asset से ऊपर है), फिर host fallback है। घोषित styles के defaults `CLOSE_ICON` हैं; style बदलने से Skip/auto-next timing नहीं बदलती।
- X/Skip का side हर native full-screen page का अपना है, ऊपर कोई shared scope नहीं: हर full-screen step के लिए `onboarding.steps.<id>.fullscreen.skip.position`, standalone OB5 के लिए `ob5.skip.position`, और splash interstitial से LFO के बीच के native_fs के लिए `splash.native.skip.position`। तीनों में `RIGHT` / `LEFT` मान्य हैं, default `RIGHT` — वही side जहाँ X हमेशा से था; shipped JSON में `full1` और `full2` declare हैं, और app का declare किया कोई भी दूसरा step id उसी path पर स्वीकार होता है। किसी और format में यह control नहीं है: interstitial, app-open, banner और inline native में यह button होता ही नहीं। दोनों sides पूरी तरह mirror हैं: अपने edge से समान inset और समान top margin, इसलिए केवल side बदलता है, size/style/timing नहीं। RTL locale में screen आज की तरह ही mirror होती है: `RIGHT` text end, `LEFT` text start।
- `native.presentation.cta_corner_radius_dp`: `20` dp; placement/screen से override किया जा सकता है। `colorCTA`/`NativeAdStyle.ctaBackgroundColor` में explicit color हो तभी लागू होता है; `default` color XML drawable रखता है।
- `lfo.confirm_button.image_url` / `tint_color`: `""` XML icon/color रखता है। Image failure पर SDK check icon, invalid color पर मौजूदा color रहता है। यह LFO confirm control है, ad CTA से अलग।
- `lfo.languages.supported_codes`: `[]` app/SDK catalog रखता है। Unknown codes हटते हैं; filtered result खाली हो तो catalog fallback है। `lfo.languages.default_code`: `""` पुराना चुनाव रखता है; code दिखाई जाने वाली list (filtered `supported_codes`, वरना catalog) में होना चाहिए। Host का `LanguageConfig.defaultCode` अगर `supported_codes` से बाहर रह जाए तो preselect नहीं होता।

हर native full-screen page के X side का override उदाहरण। Shipped JSON में `full1` और `full2` — standard full-screen pages — declare हैं; app अपना step id declare करे तो उसे भी इसी तरह जोड़ें:

```json
{
  "splash": { "native": { "skip": { "position": "LEFT" } } },
  "onboarding": {
    "steps": {
      "full1": { "fullscreen": { "skip": { "position": "LEFT" } } },
      "full2": { "fullscreen": { "skip": { "position": "RIGHT" } } }
    }
  },
  "ob5": { "skip": { "position": "LEFT" } }
}
```

`flow.lock_portrait`, `flow.system_bars.*` और `onboarding.steps.ob1/ob2/ob4.progress_visible` नए JSON schema में नहीं हैं। पुराने defaults के साथ `BehaviorConfig`, `SystemBarConfig`, `ContentStepDefinition.showsProgressIndicator` इस्तेमाल करें।

## Fetch timing और QA

दोनों strategies splash ads से पहले remote completion या timeout/fallback का इंतज़ार करती हैं। उसके बाद `ALTERNATE` `onRemoteFetched()` का भी इंतज़ार करता है; `SAME_TIME` उस hook का इंतज़ार किए बिना splash banner/interstitial शुरू करता है। LFO1 preload **दोनों strategies में remote के बाद** schedule होता है; LFO `PARALLEL` का मतलब splash interstitial के load outcome का इंतज़ार न करना है। Strategy और notification-permission का फैसला remote step settle होने के बाद पढ़े जाते हैं, इसलिए उस step में fetch हुई value उसी launch पर लागू होती है। Notification prompt उस step का इंतज़ार करता है; consent और billing फिर भी उसके साथ-साथ चलते हैं। Remote `splash.navigation.next_screen_timing` की `AUTO` के अलावा कोई भी value launcher starts पर overridden `nextScreenTiming()` से ऊपर है; notification/widget/uninstall entries hook ही रखती हैं, और app-asset value सिर्फ default hook को मिलती है।

Firebase in-flight fetch साझा करता है; एक caller का timeout दूसरों को cancel नहीं करता। Parsing/validation Main से बाहर, persistence IO पर और ads/UI notification Main पर होते हैं। Successful fetch process में reuse होता है और Firebase के minimum fetch interval के अधीन रहता है, जो default रूप से 12 घंटे है; SDK इसे set नहीं करता, इसलिए Console edits अगले launch तक पहुँचने ज़रूरी हों तो अपनी app में `minimumFetchIntervalInSeconds` set करें (उदाहरण: debug में `0`, release में `3600`)। Console QA में process restart करें; सिर्फ screen दोबारा खोलना नया fetch सुनिश्चित नहीं करता। `AdConfig.refresh()` बताता है कि कोई `ad_remote_config` document लागू हुआ या नहीं; यह grouped settings के बारे में कुछ नहीं बताता। [QA कदम](firebase-integration.hi.md#remote-notes) देखें।

## सभी default fields

नीचे समय milliseconds में है; ad_config/legacy API में स्पष्ट seconds नाम वाले fields अपवाद हैं। `schema_version=1`; `revision` metadata है। Empty objects scope-specific override नहीं जोड़ते और empty template strings inheritance बताते हैं। Tables और copy/paste files SDK assets के समान defaults रखते हैं; JSON names/enums का अनुवाद नहीं होता।

### ad_behavior_config

| Field | SDK default |
| --- | --- |
| `schema_version` | `1` |
| `revision` | `0` |
| `global.ads_enabled` | `true` |
| `consent.network_timeout_ms` | `20000` |
| `diagnostics.flow_logging_enabled` | `true` |
| `diagnostics.ads_telemetry_enabled` | `true` |
| `banner.reload.allowed` | `true` |
| `banner.reload.auto_enabled` | `true` |
| `banner.reload.resume_debounce_ms` | `500` |
| `banner.presentation.type` | `"NORMAL"` |
| `banner.presentation.collapsible_gravity` | `"BOTTOM"` |
| `banner.presentation.inline_style` | `"LARGE"` |
| `banner.presentation.inline_max_height_dp` | `50` |
| `banner.presentation.fixed_size` | `"BANNER"` |
| `native.load.tier_timeout_ms` | `30000` |
| `native.cache.max_age_ms` | `3600000` |
| `native.reload.allowed` | `false` |
| `native.click.action` | `"reload"` |
| `native.reload.on_ad_click` (legacy fallback) | `true` |
| `native.reload.resume_debounce_ms` | `500` |
| `native.reload.min_after_bind_ms` | `3000` |
| `native.reload.timer_enabled` | `false` |
| `native.reload.interval_ms` | `15000` |
| `native.preload.enabled` | `false` |
| `native.preload.after_show` | `false` |
| `native.presentation.auto_shimmer` | `true` |
| `native.presentation.empty_visibility` | `"GONE"` |
| `native.presentation.cta_corner_radius_dp` | `20` |
| `interstitial.load.tier_timeout_ms` | `30000` |
| `interstitial.load_and_show.wait_timeout_ms` | `8000` |
| `interstitial.load_and_show.buffer_wait_timeout_ms` | `8000` |
| `interstitial.load_and_show.allow_wait_for_auto_buffer` | `true` |
| `interstitial.presentation.next_screen_timing` | `"AFTER_AD"` |
| `interstitial.presentation.loading_enabled` | `true` |
| `interstitial.presentation.pre_show_delay_ms` | `800` |
| `interstitial.frequency.interval_ms` | `0` |
| `interstitial.frequency.max_clicks_per_24h` | `0` |
| `interstitial_auto_buffer.enabled` | `true` |
| `interstitial_auto_buffer.shared_config` | `false` by default. `true` preserves the shared group policy and independent_interval overrides. `false` gives every placement its own cooldown, retry and tap counter, using rules.<placement>.interval_ms/tap_threshold or host options; disables the shared two-action guard. |
| `interstitial_auto_buffer.tick_ms` | `0` |
| `interstitial_auto_buffer.idle_tick_ms` | `30000` |
| `interstitial_auto_buffer.min_tick_ms` | `5000` |
| `interstitial_auto_buffer.preload_lead_ms` | `2000` — preload requires both tap_threshold and max(0, interval_ms - preload_lead_ms). Showing requires the full interval_ms. |
| `interstitial_auto_buffer.rules` | `inter_all: 30000ms / 2 taps; inter_back: 30000ms / 1 tap` |
| `interstitial.cache.max_age_ms` | `3600000` |
| `rewarded.load.tier_timeout_ms` | `30000` |
| `rewarded.cache.max_age_ms` | `3600000` |
| `app_open.load.timeout_ms` | `30000` |
| `app_open.load.max_background_requests` | `3` |
| `app_open.load.background_retry_window_ms` | `120000` |
| `app_open.load.offline_recheck_ms` | `5000` |
| `app_open.load.failure_backoff_ms` | `[5000,30000,120000]` |
| `app_open.presentation.loading_timeout_ms` | `3000` |
| `app_open.presentation.pre_show_delay_ms` | `800` |
| `app_open.presentation.skip_after_ad_click` | `false` |
| `app_open.cache.max_age_ms` | `14400000` |
| `placement_overrides` | `{}` |

### onboarding_config

| Field | SDK default |
| --- | --- |
| `schema_version` | `1` |
| `revision` | `0` |
| `flow.ads_enabled` | `true` |
| `flow.skip_ad_only_steps_when_premium` | `true` |
| `flow.fullscreen_skip_style` | `"CLOSE_ICON"` |
| `splash.ads.slot_format` | `"BANNER"` |
| `splash.ads.banner.behavior` | `{}` |
| `splash.ads.native.behavior` | `{}` |
| `splash.ads.interstitial.behavior` | `{}` |
| `splash.timing.min_display_ms` | `3000` |
| `splash.timing.ad_budget_ms` | `60000` |
| `splash.timing.slot_min_visible_ms` | `1000` |
| `splash.timing.slot_wait_ms` | `10000` |
| `splash.timing.notification_settle_ms` | `800` |
| `splash.load.ad_strategy` | `"ALTERNATE"` |
| `splash.load.lfo1_preload_mode` | `"SEQUENTIAL"` |
| `splash.load.remote_fetch_timeout_ms` | `10000` |
| `splash.load.consent_hook_timeout_ms` | `20000` |
| `splash.load.billing_timeout_ms` | `5000` |
| `splash.permissions.no_internet_prompt_enabled` | `true` |
| `splash.permissions.notification_enabled` | `true` |
| `splash.navigation.next_screen_timing` | `"AUTO"` |
| `splash.native.skip.delay_ms` | `3000` |
| `splash.native.skip.style` | `"CLOSE_ICON"` |
| `splash.native.skip.position` | `"RIGHT"` |
| `splash.native.auto_dismiss_ms` | `15000` |
| `splash.native.behavior.click.action` | `"reload"` |
| `lfo.native_template` | `"CTA_BOTTOM"` |
| `lfo.native1.behavior.click.action` | `"reload"` |
| `lfo.native2.enabled` | `true` |
| `lfo.native2.behavior.click.action` | `"reload"` |
| `lfo.native2.swap_wait_timeout_ms` | `8000` |
| `lfo.native2.preload_trigger` | `"LFO_SHOWN"` |
| `lfo.tap_hint.enabled` | `true` |
| `lfo.tap_hint.delay_ms` | `3000` |
| `lfo.confirm_button.visible_before_selection` | `false` |
| `lfo.confirm_button.save_on_back` | `true` |
| `lfo.confirm_button.image_url` | `""` |
| `lfo.confirm_button.tint_color` | `""` |
| `lfo.confirm_dialog.enabled` | `true` |
| `lfo.confirm_dialog.show_from_tap` | `4` |
| `lfo.confirm_dialog.native_preload_trigger` | `"DIALOG_OPEN"` |
| `lfo.confirm_dialog.native_behavior.click.action` | `"reload"` |
| `lfo.languages.supported_codes` | `[]` |
| `lfo.languages.default_code` | `""` |
| `lfo.exit.reuse_splash_inter` | `true` |
| `onboarding.navigation.lock_pager_swipe` | `false` |
| `onboarding.navigation.swipe_completes_last_step` | `true` |
| `onboarding.navigation.back_navigates_back` | `true` |
| `onboarding.navigation.ad_click_return_completes_step` | `true` |
| `onboarding.ads.content_template` | `"CTA_TOP"` |
| `onboarding.ads.content_native_behavior.click.action` | `"auto_next"` |
| `onboarding.ads.fullscreen_native_behavior.click.action` | `"auto_next"` |
| `onboarding.fullscreen.skip.enabled` | `true` |
| `onboarding.fullscreen.skip.delay_ms` | `5000` |
| `onboarding.fullscreen.skip.style` | `"CLOSE_ICON"` |
| `onboarding.fullscreen.auto_next.enabled` | `true` |
| `onboarding.fullscreen.auto_next.delay_ms` | `15000` |
| `onboarding.steps.full1.fullscreen.skip.position` | `"RIGHT"` |
| `onboarding.steps.full2.fullscreen.skip.position` | `"RIGHT"` |
| `onboarding.order` | App order when absent; array selects/reorders app catalog, `[]` skips pager. |
| `onboarding.preload.ob5_on_last_step` | `true` |
| `onboarding.preload.question_on_last_step` | `true` |
| `onboarding.exit_interstitial.enabled` | `true` |
| `onboarding.exit_interstitial.preload_on_entry` | `true` |
| `onboarding.exit_interstitial.wait_timeout_ms` | `8000` |
| `onboarding.exit_interstitial.next_screen_timing` | `"UNDER_AD"` |
| `onboarding.exit_interstitial.behavior` | `{}` |
| `ob5.enabled` | `false` |
| `ob5.native.behavior.click.action` | `"reload"` |
| `ob5.skip.enabled` | `true` |
| `ob5.skip.delay_ms` | `3000` |
| `ob5.skip.style` | `"CLOSE_ICON"` |
| `ob5.skip.position` | `"RIGHT"` |
| `ob5.auto_dismiss_ms` | `15000` |
| `question.enabled` | `true` |
| `question.old_user_enabled` | `false` |
| `question.native.behavior.click.action` | `"reload"` |
| `question.native.template` | `"CTA_BOTTOM"` |
| `question.native.refresh_on_select` | `false` |
| `question.native.refresh_throttle_ms` | `2000` |
| `question.interstitial.behavior` | `{}` |
| `question.selection.mode` | `"MULTIPLE"` |
| `question.selection.min_count` | `1` |

`interstitial.auto_buffer` has moved to top-level `interstitial_auto_buffer` (default `enabled: true`). Update remote config and custom host assets to the new key; the old key is no longer read. This group controls only placements configured in `InterstitialAutoBuffer` or its remote `rules`, excluding reserved placements. The host must still call `configure()` / `start()`; enabling this field does not start the buffer or show ads automatically. Other interstitial settings remain under `interstitial`.
