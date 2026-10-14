# Ads behavior और onboarding settings

**OB catalog:** `ob1..ob4` → `native_ob1..4`; `full1/full2` → `native_full1/2`. Default: `ob1, full1, ob2, full2, ob3, ob4`. All eligible OB natives preload on language selection. Remote `onboarding.order` selects/reorders app-declared steps. [Configuration / Hướng dẫn chi tiết](onboarding-flow.vi.md). `native_fs` remains the separate splash native.

JSON में पेज चुनने और उनका क्रम तय करने के लिए केवल `onboarding.order` इस्तेमाल करें; पेज हटाने के लिए उसका ID निकालें। `steps` केवल वैकल्पिक behavior या fullscreen overrides और `ob1.back.enabled` के लिए है। `steps.<id>.enabled` अब अनदेखा किया जाता है। Remote `order` backend द्वारा भेजे गए पुराने `ob_enable_step_ob1..4` flags से प्राथमिकता रखता है, और वे flags app asset के `order` से। App में `enabled = false` से declare किया गया page तब तक छिपा रहता है जब तक remote `order` उसे सूची में न रखे या backend द्वारा भेजा गया `ob_enable_step_obN = true` उसे चालू न करे; app asset का `order` उसे छिपा ही रखता है। जो page app ने कभी declare नहीं किया, उसे remote नहीं जोड़ सकता: undeclared ID वाला `order` पूरा ignore होता है और अगला source लागू होता है। हर ad placement को `ad_config.<placement>.isEnable` से नियंत्रित करें: ads बंद होने पर content पेज रहता है, fullscreen ad पेज छोड़ दिया जाता है।

[English](remote-settings.md) · [Tiếng Việt](remote-settings.vi.md) · [हिन्दी](remote-settings.hi.md)

Console setup के लिए [तीन String parameters publish करने के कदम](firebase-integration.hi.md#remote-json) देखें। Custom offline defaults के लिए [app में दो local JSON files बनाना](firebase-integration.hi.md#local-defaults) देखें। नीचे के पूरे उदाहरण SDK assets से मेल खाते हैं, सिवाय onboarding sample की तीन बातों के: `onboarding.primary_color` `""` है ताकि app का मौजूदा color बना रहे, जबकि SDK asset `"#FF375E"` रखता है; `onboarding.exit_interstitial.enabled` `true` है, जो exit interstitial चालू करता है, जबकि SDK default `false` है; और [`native_flow`](#native-flow-type) scope शामिल नहीं है, ताकि sample को अपने asset के रूप में copy करने वाला app native flow remote और SDK defaults से ही ले।

सभी modules के लिए [JitPack](https://jitpack.io/#truongvimit/adlogic-partner-sdk) का **newest SDK version** इस्तेमाल करें। केवल Firebase keys जोड़ने से app में integrated पुराना SDK update नहीं होता।

वैकल्पिक Privacy → Goal, pager और उसके exit interstitial (चालू होने पर; default रूप से बंद) के बाद onboarding का अंतिम भाग है। `privacy_goals_screen.enabled` चालू करें और goal options दें; [Privacy → Goal](privacy-goals-screen.hi.md) देखें।

Launcher से app खोलने वाला लौटता user splash के बाद डिफ़ॉल्ट रूप से Welcome Back छोड़कर app में जाता है। इसे दिखाने के लिए `welcome_back.enabled=true` करें। [Welcome Back](welcome-back-screen.hi.md) देखें।

## Documents और field ownership

मौजूदा Firebase parameter `ad_remote_config` ही है; `ad_config.json` / `ad_config_debug.json` उसके local asset filenames हैं। सिर्फ दो नए String parameters जोड़ें:

| Parameter | पूरा default JSON | SDK source |
| --- | --- | --- |
| `ad_behavior_config` | [Copy/paste sample](examples/ads-onboarding/ad_behavior_config.json) | [SDK source asset](../ads/src/main/assets/adlogic_defaults/ad_behavior_config.json) |
| `onboarding_config` | [Copy/paste sample](examples/ads-onboarding/onboarding_config.json) | [SDK source asset](../onboardkitorigin/src/main/assets/adlogic_defaults/onboarding_config.json) |

- **ad_config:** `ids`, `isEnable`, `enable_ua_check`, `reloadIntervalSeconds`, `colorCTA`, `colorBackground`, `colorAdBadge`, `colorAdBadgeText`, `heightCTA`, `templateId`, `components`, native `click_action`, `open_resume.app_resume_load_delay_ms`। नए documents इन fields, ad-unit mappings या individual unit switches को दोहराते नहीं हैं।
- **ad_behavior_config:** format के अनुसार timeout/cache, reload policy, frequency/AutoBuffer, consent timeout, telemetry, native CTA radius, resume type और app-open behavior। Banner type/size SDK presets हैं।
- **onboarding_config:** flow steps, X/Skip timing/style, auto-next, swipe/back, splash strategy, LFO/OB preload, exit behavior, LFO confirmation appearance, native resume का Start color और app के language catalog में से चयन। Step चालू करने से `isEnable=false` वाला ad unit चालू नहीं होता।
- **App code/resources:** `R.layout`, `R.drawable`, `R.string`, custom page layouts, language resources/catalog, progress indicators, system bars/orientation और Activity exclusions। SDK ad-presentation presets remote से बदले जा सकते हैं।

हटाए गए aliases `app_open.presentation.excluded_hosts`, `app_open.enabled`, `app_open.load.background_delay_ms`, `banner.reload.interval_ms`, placement/native-placement mappings, duplicate unit switches और app-content group `ui` ignore होते हैं, पुराने cached JSON में भी। पुराने remote UI APIs `ob_ui_content`, `ob_ui_design_tokens`, `ob_enable_ui_content` से चलते रहते हैं; वे नए grouped documents में fields नहीं हैं।

## App को एक बार configure करें

[Partner setup sample](examples/ads-onboarding/OnboardKitSetup.kt) इस्तेमाल करें। Content/resources app में रखें; SDK इस्तेमाल के समय वर्तमान ad document resolve करता है:

```kotlin
ads = AdsConfig.fromAdConfig()
```

यह `onboardKitConfig` builder का default भी है। Application startup पर IDs copy करने के बजाय placement keys बने रहते हैं। Fetch के बाद page eligibility, preload और display वर्तमान units लेते हैं; दूसरा `configure()` आवश्यक नहीं। Usable unit न होने पर ad-only page pager खोलने से पहले हटता है। Raw IDs के साथ manual `AdsConfig(...)` अब भी supported है, लेकिन नीचे की जो key backend का `ad_remote_config` या app की अपनी `ad_config.json` declare करती है वह code में लिखे units से ऊपर है, `stepNatives`, `splashInterstitialOldUser` और `splashInterstitialOverride()` समेत; code units सिर्फ उन placements पर लागू होते हैं जिन्हें इनमें से कोई declare नहीं करता। Non-standard key, जैसे custom step की key, तभी लागू होती है जब आप उसे `AdsConfig.fromAdConfig(mapOf(...))` से bind करें।

| Slot | Default ad_config key |
| --- | --- |
| Splash banner / interstitial | `banner_splash` / `inter_splash` |
| Splash native (नीचे का slot, जब `splash.ads.slot_format` = `NATIVE`) | `native_splash` |
| Splash full-screen native (splash interstitial के बाद, LFO से पहले) | `native_fs` |
| Returning-user splash | `<splash interstitial key>_o` (`inter_splash_o`); `_o` declare न हो तो returning users को `inter_splash` के units मिलते हैं |
| Notification / widget / uninstall entry splash | `inter_noti` / `inter_widget` / `inter_uninstall`; key न हो या बंद हो तो user segment की key इस्तेमाल होती है |
| Privacy / Goal initial; ALT | `native_select`; `native_select_alt` (हर एक का अपना `ids` waterfall) |
| LFO1 / LFO2 / confirmation dialog | `native_lang` / `native_lang_alt` / `native_popup_lang` |
| Content OB1 / OB2 / OB3 / OB4 | `native_ob1` / `native_ob2` / `native_ob3` / `native_ob4` |
| Fullscreen Full1 / Full2 | `native_full1` / `native_full2` |
| OB5 | `native_onboarding_fullscreen_1_4` |
| Welcome Back initial; ALT | `native_welcome1`; `native_welcome2` (हर एक का अपना `ids` waterfall) |
| Exit interstitial | `inter_after_ob3` |
| App resume | `ad_behavior_config` का `app_resume.type` `native_resume`, `inter_resume`, `open_resume` या `none` चुनता है; उसी नाम की ad_config key ID के साथ चालू होनी चाहिए, वरना वापसी पर कुछ नहीं दिखता। ID के साथ declare हुए `open_resume` को `idAdResume` seed नहीं चाहिए। `AppOpenManager.getInstance().disableAppResume()` हर type बंद रखता है |

नए users के लिए `inter_splash` और returning users के लिए `inter_splash_o` (`_o` declare न हो तो `inter_splash`) master switch है: वहाँ `isEnable: false` उस segment के हर splash interstitial को बंद करता है, entries समेत। Entry अपनी key तभी इस्तेमाल करती है जब वह declare हो और चालू हो, वरना segment की key। Load और show दोनों उसी key से gate होते हैं। पुरानी Firebase key `ob_ads_splash_inter_enabled=false` सभी users के लिए हर splash interstitial बंद करती है।

### Splash का निचला slot

Splash में loading bar के नीचे एक ही ad slot है, और `splash.ads.slot_format` तय करता है कि उसे कौन
सा format भरेगा:

| Value | Placement | ad_config key |
| --- | --- | --- |
| `BANNER` (default) | `AdPlacement.SplashBanner` | `banner_splash` |
| `NATIVE` | `AdPlacement.SplashInlineNative` | `native_splash` |

Native को दोनों चाहिए: `slot_format` को `NATIVE` **और** ad_config में एक usable `native_splash`
entry। इनमें से कोई एक भी न हो तो slot खाली ही रहता है।

हर launch एक format request करता है। Slot स्वतंत्र रूप से load और render होता है: ready interstitial slot के load, impression या minimum visibility का इंतज़ार नहीं करता। `slot_min_visible_ms` और `slot_wait_after_inter_ms` presentation नहीं रोकते। Consent, premium, focus, notification dismissal, splash minimum display और update/paywall gates लागू रहते हैं। `ob_ads_splash_banner_enabled` दोनों formats नियंत्रित करता है।

Native media-left frame से render होता है जब तक `native_splash.templateId` कोई और template न चुने; media-left हमेशा वैसा ही दिखता है जैसा बनाया गया है, इसलिए `components` बेअसर
है — `colorCTA` और `heightCTA` फिर भी लागू होते हैं। `AdPlacement.SplashInlineNative` और
`AdPlacement.SplashNative` अलग हैं; दूसरा splash interstitial के बाद दिखने वाला optional full-screen
native (`native_fs`) है। Code में यह flag `io.onboardkit.config.SplashAdSlotFormat` है और ad units
`AdsConfig.splashInlineNative` में resolve होते हैं।

केवल अलग नाम वाली associations code में घोषित करें:

```kotlin
ads = AdsConfig.fromAdConfig(mapOf(
    AdPlacement.Language1 to "my_language_native",
    AdPlacement.StepNative(StepId("custom")) to "my_content_native",
))
```

यही association `ad_behavior_config.placement_overrides.<key>` भी चुनती है: LFO1 सामान्यतः `native_lang` इस्तेमाल करता है, internal telemetry/buffer key `language1` नहीं। IDs, floors, unit switches, CTA fields और native click action ad_config में रहते हैं।

## Local defaults, remote cache और failure

`app/src/main/assets/ad_behavior_config.json` / `onboarding_config.json` app tier हैं। SDK अपनी bundled copies `adlogic_defaults/` के नीचे रखता है, इसलिए app की root asset files को loader अलग, स्पष्ट override मानता है, भले उनका content bundled default जैसा ही हो। पूरा sample copy करें या केवल बदलने वाले fields लिखें, फिर rebuild और process restart करें। App file न हो तो bundled defaults बिना manual setters के काम करते हैं। [Firebase guide](firebase-integration.hi.md#local-defaults) में दोनों छोटे, पूरे उदाहरण हैं।

- हर setting की precedence: remote `onboarding_config` / `ad_behavior_config` का valid field > backend द्वारा भेजी गई legacy `ob_*` key > custom app asset > host Kotlin config/hooks > bundled SDK defaults। किसी भी scope का remote किसी भी scope के app asset से ऊपर है। Cached valid remote fields startup पर भी इसी priority में हैं; जो keys backend ने कभी नहीं भेजीं वे गिनी नहीं जातीं।
- Successful fetch में missing parameter/field पिछला remote assignment हटाकर अगले नीचे वाले source पर fallback करता है। गलत type/enum/range और `null` ignore होते हैं और logcat tag `AdLogicSettings` में log होते हैं; valid `false`/`0` assignments बने रहते हैं।
- Numbers पूर्ण संख्या और 0 से 2147483647 के बीच होने चाहिए। `*wait_timeout_ms` को छोड़कर हर `*timeout_ms` के लिए, और `min_after_bind_ms`, `idle_tick_ms`, `min_tick_ms`, `offline_recheck_ms` व `native.reload.interval_ms` के लिए `0` reject होता है। `show_from_tap` और `max_background_requests` कम से कम 1, `ob5.auto_dismiss_ms` कम से कम 5000, `inline_max_height_dp` कम से कम 32; `cache.max_age_ms` 1–3600000 (app-open 1–14400000)।
- Fetch failure/timeout या malformed या blank पूरा JSON होने पर अंतिम valid document बना रहता है; rejected document `AdLogicSettings` में log होता है। `ad_behavior_config` / `onboarding_config` में unsupported `schema_version` केवल एक ignored field है और बाकी fields लागू रहते हैं; केवल `ad_remote_config` इसके कारण पूरा document reject करता है। पहली run में valid remote cache न हो तो local/defaults रहते हैं। **Failed fetch valid remote cache के ऊपर local values लागू नहीं करता।** Background fetch max(`splash.load.remote_fetch_timeout_ms`, 60000) ms तक इंतज़ार करता है और splash उसका इंतज़ार नहीं करता; splash के बाद पहुँचा fetch भी बाकी session के लिए लागू होता है।
- `AdConfig.install` backend द्वारा भेजा गया अंतिम `ad_remote_config` (Firebase की last activated value) तुरंत लागू करता है, इसलिए धीमा या failed fetch asset पर नहीं, उसी document पर चलता है। Settings fetch fail होने पर भी `ad_remote_config` लागू होने से नहीं रुकता।
- Successful fetch पर remote overrides हटाने के लिए `{}` या `{"schema_version":1}` publish करें। Empty String malformed है, reset नहीं। नया remote object पुराने remote overrides को replace करता है, patch नहीं; missing fields अगले नीचे वाले source पर fallback करते हैं।
- Custom/partial app asset का हर मौजूद valid field explicit assignment है, `false`/`0` समेत। App asset root में रखी पूरी copy भी host constructor/setter को override करती है, भले values SDK defaults जैसी हों। Firebase की published default value remote है, SDK local default नहीं; source Firebase in-app defaults को fetched remote नहीं मानता।
- SDK defaults build के समय assets से generate होते हैं, Context से पहले उपलब्ध हैं और `null` नहीं रखते। अलग Kotlin/XML defaults maintain नहीं करने पड़ते। App के invalid fields fallback लेते हैं; app को नया parser नहीं चाहिए।
- `ad_behavior_config.global.ads_enabled` पूरे ads module का global gate है; consent, premium और lifecycle checks लागू रहते हैं। सभी ads रोकने के लिए `global.ads_enabled=false` रखें, या संबंधित placement बंद करें।
- पुराने `ob_*` keys compatible रहते हैं। Backend द्वारा भेजी गई key अपनी value दोनों दिशाओं में तय करती है, grouped documents से नीचे और app asset/host से ऊपर; `ob_splash_min_display_ms <= 0` local value रखता है, और negative `ob_skip_button_delay_sec` ignore होता है।
- हर build अपनी settings `ad_config.json` से पढ़ता है। Debuggable build `ad_config_debug.json` भी पढ़ता है, जिसमें हर placement key के लिए सिर्फ `ids` होता है, आम तौर पर एक test floor: ये floors placement के पूरे waterfall की जगह लेते हैं, इसलिए हर placement सामान्य load path से अपनी test ID load करता है। Debug file का कोई भी दूसरा field `WARN` के साथ ignore होता है, और `ad_config.json` में चालू जिस placement की debug entry नहीं है उसे ad नहीं मिलता (यह भी `WARN`)। Remote `ad_remote_config` IDs को छोड़कर हर field लागू करता है; `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` remote की declare की गई IDs भी ले लेता है। Debug file न हो तो debug `ad_config.json` की IDs इस्तेमाल करता है। दोनों grouped documents release की तरह लागू होते हैं; नए parameters के automatic `_debug` variants नहीं हैं।

Refresh के बाद OnboardKit warning उन app-mapped placement keys को बताती है जो remote में नहीं हैं। वे app values रखती हैं; Firebase में अलग नाम की key बदलने से उन पर असर नहीं होता।

## Behavior scopes

Screen slot override > shared content/fullscreen OB override (`onboarding.ads.content_native_behavior` Privacy/Goal natives पर भी लागू होता है) > placement override > format override > local/default। यह क्रम एक ही source के भीतर लागू होता है: किसी भी scope का remote value किसी भी scope के app-asset value से ऊपर है। Empty `behavior` object optional scope है, उसमें कोई leaf override नहीं। इसमें ad IDs, unit switches या app resource IDs न रखें।

| placement_overrides में format | Supported fields |
| --- | --- |
| banner | `reload.allowed`, `reload.auto_enabled`, `reload.resume_debounce_ms`, `presentation.*` |
| native | `load.tier_timeout_ms`, `reload.*`, `presentation.auto_shimmer`, `presentation.empty_visibility`, `presentation.cta_corner_radius_dp` |
| interstitial | `load.tier_timeout_ms`, `load_and_show.wait_timeout_ms`, `load_and_show.buffer_wait_timeout_ms`, `presentation.loading_enabled`, `cache.max_age_ms` |
| rewarded | `load.tier_timeout_ms`, `cache.max_age_ms` |

Native preload app/SDK code शुरू करता है। Replacement preload के लिए `setEnablePreload` और `preloadAfterShow` हैं; remote `preload.enabled` / `preload.after_show` समर्थित नहीं हैं। Onboarding schedule `lfo1_preload_mode`, `preload_trigger` और `onboarding.preload.*` से चलता है। Per-tier timeout `native.load.tier_timeout_ms` और `interstitial.load.tier_timeout_ms` से तय होता है (default 30000 ms)।

Splash interstitial slot पहले load होता है, फिर buffer से show होता है, इसलिए उसका `behavior` केवल `load.tier_timeout_ms` लेता है; `load_and_show.*` wait को अनदेखा किया जाता है क्योंकि slot पहले load और बाद में show होता है; वहाँ `load_and_show.*` field ignore होता है। केवल exit interstitial fill का इंतज़ार करता है: `onboarding.exit_interstitial.wait_timeout_ms`, `placement_overrides` और format `interstitial.load_and_show.wait_timeout_ms` से ऊपर है। Frequency, next-screen timing, pre-show delay, app-open और native cache TTL format scope में हैं। Custom steps `onboarding.steps.<id>.fullscreen.skip.{enabled,delay_ms,style,position}`, `.auto_next.{enabled,delay_ms}` support करते हैं। इनमें `position` केवल per-step है — इसके ऊपर `onboarding.fullscreen` या `flow` scope नहीं है।

Banner cadence positive `ad_config.<key>.reloadIntervalSeconds` से, नहीं तो host value से आती है (SDK default 15000 ms)। SDK का banner refresh default में चालू है (`banner.reload.allowed` और `banner.reload.auto_enabled` `true` हैं); interval सिर्फ उसकी cadence तय करता है। हर banner ad unit पर, सभी floors समेत, AdMob console refresh बंद करें, वरना banner दो बार refresh होगा। SDK refresh बंद करने के लिए `banner.reload.auto_enabled` को `false` करें (timer नहीं; resume पर reload रहता है) या `banner.reload.allowed` को `false` करें (SDK reload बिल्कुल नहीं), globally या `placement_overrides.<key>` में। किसी भी resume ad के request से पहले background में रहने का समय `open_resume.app_resume_load_delay_ms` में है (2000 ms), हर `app_resume.type` के लिए। किसी भी format के ad पर click अगला resume ad केवल तब छोड़ता है जब `app_open.presentation.skip_after_ad_click` `true` हो (default `false`)। Native click actions और defaults नीचे दिए हैं। Timer reload click replacement से अलग है। Cache age केवल documented SDK limit से कम की जा सकती है।

खाली behavior object व्यापक scope या निचले source की leaf value नहीं रोकता। `presentation.loading_enabled`, `show()` और `loadAndShow()` दोनों में captured placement/format behavior लेता है, ready cached ad पर भी। Interstitial screen scopes (`splash.ads.interstitial.behavior`, `onboarding.exit_interstitial.behavior`) केवल `load.tier_timeout_ms` और `load_and_show.wait_timeout_ms` लेते हैं (splash वाला `load_and_show.*` छोड़ देता है), इसलिए `loading_enabled` को `placement_overrides.<key>` या `interstitial` format में set करें। Remote refresh के बाद banner placement का `enable_ua_check` दोबारा पढ़ता है; host का explicit `forceUaCheck` setter पहले आता है। Explicit style न होने पर भी native helper `presentation.cta_corner_radius_dp` लागू करता है।

## Native click actions

Native के click action का एक ही source है: `ad_config` में उसकी key पर `click_action`, जिसकी value `auto_next`, `none`, `reload` या `reload_waterfall` होती है। `enable_ua_check` और CTA fields की तरह यह placement की key (`native_ob1`, `native_lang`, `native_home`…) पर लिखा जाता है और उसके `ids` के हर floor पर लागू होता है; पुरानी `_high*` key पर दी गई value ignore होती है। `ad_behavior_config` और `onboarding_config` में click action field नहीं है। पहले click/open callback पर केवल एक action तय होता है; वापस आने तक remote बदलने पर भी वही action रहता है।

- `reload`: click/open पर तुरंत replacement request शुरू होती है, केवल all-price floor से (`ids` का आख़िरी चालू floor)। वापस आने पर तैयार ad या उसी pending request का उपयोग होता है। इसलिए जल्दी लौटने वाला user `ids` में all price से पहले वाले floors के पीछे इंतज़ार नहीं करता। कोई fixed delay नहीं; सामान्य app resume click reload नहीं है।
- `reload_waterfall`: `reload` जैसा, लेकिन replacement `ids` के हर चालू floor से होकर चलता है, सबसे ऊँचा पहले।
  दोनों reload में नया ad सफलतापूर्वक bind होने तक पुराना ad बिना shimmer दिखता रहता है। Load fail होने पर पुराना ad और slot बने रहते हैं। Shimmer केवल पहली loading में, जब कोई ad नहीं है, दिखता है।
- `auto_next`: वापस आने पर onboarding page आगे जाता है; replacement request नहीं होती। LFO2 में चुनी हुई भाषा confirm होती है; LFO1 में user की पहले से चुनी हुई भाषा पर tap दोहराया जाता है, जैसे उस row को दोबारा tap करना; कोई row tap न हुई हो तो कुछ नहीं होता। किसी दूसरे native पर यह `none` की तरह केवल replacement छोड़ता है; navigation आपकी app के हाथ में रहता है।
- `none`: मौजूदा ad/page रखें; click reload या automatic navigation नहीं।

Key पर `click_action` न हो तो defaults:

| Natives | Default |
| --- | --- |
| Onboarding pager pages: content `ob1..ob4` और app के declared content steps, fullscreen `full1/full2`; splash full-screen native `native_fs` | `auto_next` |
| LFO1, LFO2, LFO confirmation dialog, Privacy/Goal, Welcome Back, OB5, splash bottom-slot native `native_splash` और app-screen natives | `reload` |

Pager page ads timer या resume पर कभी refresh नहीं होते, लेकिन click page के action का पालन करता है: `reload` और `reload_waterfall` replacement preload करते हैं और user के लौटने पर उसे bind करते हैं, page वहीं रहता है; user के ad में रहने तक fullscreen page का auto-next countdown रुका रहता है। Click के समय placement का कोई load पहले से चल रहा हो तो click उसी में join करता है, चाहे वह किसी भी floor पर हो। जिस LFO2 का अपना unit नहीं है वह LFO1 की key इस्तेमाल करता है, और उसके साथ LFO1 का action भी, बशर्ते वह key bound हो: `AdsConfig.fromAdConfig` (builder default) उसे bind करता है, और backend या app की अपनी `ad_config.json` का उस key को declare करना भी। इनमें से किसी के बिना hand-built `AdsConfig(...)` में LFO2 `native_lang_alt` पढ़ता है। [Sample ad_config](examples/ads-onboarding/ad_config.json) हर native key पर यही values explicit रूप से declare करता है।

`click_action` field-दर-field ad_config precedence मानता है: backend का `ad_remote_config` > app का `ad_config.json` > code default। जो remote key `click_action` छोड़ देती है वह asset की value रखती है; invalid value log होकर ignore होती है। `ad_config_debug.json` में सिर्फ IDs होती हैं, इसलिए हर build `click_action` `ad_config.json` और remote से लेता है। App-screen native का code default `reload` है; `NativeAdHelper.setReloadOnAdClick(false)` इसे `none` कर देता है, फिर भी `click_action` ऊपर रहता है। Timer/resume refresh और fullscreen timeout अलग settings हैं।

`ad_config` example: ad click के बाद OB1 अपने page पर रहता है, और LFO2 वापसी पर भाषा confirm करता है। `click_action` `ids` के हर floor पर लागू होता है:

```json
{
  "native_ob1": {
    "ids": [
      { "id": "ca-app-pub-xxx/2222222222", "isEnable": true },
      { "id": "ca-app-pub-xxx/1111111111", "isEnable": true }
    ],
    "isEnable": true,
    "click_action": "none"
  },
  "native_lang_alt": {
    "ids": [{ "id": "ca-app-pub-xxx/3333333333" }],
    "isEnable": true,
    "click_action": "auto_next"
  }
}
```

## Native template, CTA और X/Skip प्रयोग

- `ad_remote_config.<placement>.templateId` (number) frame चुनता है: `1` LFO frame, `2` 4:3 media-left card, `3` 1.91:1 card। न हो, `null` हो या अनजान number हो तो slot का default रहता है — LFO1/2, Welcome Back और OB content पर `1`, Privacy/Goal और splash inline पर `2`। हर field की तरह remote > app asset > code।
- `components` ही अकेली चीज़ है जो native का क्रम तय करती है। Templates `1` और `3` इसका पालन करते हैं; न हो तो default क्रम CTA को नीचे रखता है। Template `1`: `["icon_headline","media","cta"]` (default) या CTA ऊपर रखने के लिए `["cta","media","icon_headline"]`।
- `positionCTA` हटा दिया गया है; जो payload अब भी इसे भेजता है, उसे अनदेखा किया जाता है। `lfo.native_template`, `onboarding.ads.content_template` और `onboarding.steps.<id>.native_template` भी हटा दिए गए हैं; पुराने payload में ये fields अनदेखे होते हैं।
- Template 3 एक layout है; design की चार arrangements `components` के क्रम से आती हैं: A `["media","icon_headline","body","cta"]`, B `["cta","icon_headline","body","media"]`, C `["icon_headline","body","media","cta"]` (default क्रम), D `["cta","media","icon_headline","body"]`। Body header के अंदर है, इसलिए `body` हटाने से वह सिर्फ़ छिपता है।
- Template `2`, LFO popup (`DIALOG`) और Full1/Full2/OB5/native_fs (`FULL_SCREEN`) हमेशा वैसे ही दिखते हैं जैसे बनाए गए हैं और `components` को अनदेखा करते हैं; popup और fullscreen natives `templateId` को भी अनदेखा करते हैं। `colorCTA` हर frame में CTA और Ad badge पर लागू होता है; `colorBackground` हर frame के card का रंग बदलता है, shape वही रहता है, और default में नहीं होता। `colorAdBadge` और `colorAdBadgeText` हर frame में Ad badge का background और text रंग बदलते हैं।
- Preload और show एक template resolver इस्तेमाल करते हैं। Host जल्दी preload करे या preload के बाद remote refresh हो तो bind वर्तमान SDK frame इस्तेमाल करता है, loaded ad हटाए बिना। दिखता हुआ view अगले bind तक बना रहता है। LFO1 मौजूदा preload mode के अनुसार schedule होता है, remote का इंतज़ार नहीं।
- Shared `flow.fullscreen_skip_style`, OB `onboarding.fullscreen.skip.style`, per-step `.fullscreen.skip.style` और `ob5.skip.style` में `CLOSE_ICON` / `TEXT` मान्य हैं। एक ही source के भीतर specific scope shared scope से पहले है (किसी भी scope का remote app asset से ऊपर है), फिर host fallback है। घोषित styles के defaults `CLOSE_ICON` हैं; style बदलने से Skip/auto-next timing नहीं बदलती।
- X/Skip का side हर native full-screen page का अपना है, ऊपर कोई shared scope नहीं: हर full-screen step के लिए `onboarding.steps.<id>.fullscreen.skip.position`, standalone OB5 के लिए `ob5.skip.position`, और splash interstitial से LFO के बीच के native_fs के लिए `splash.native.skip.position`। तीनों में `RIGHT` / `LEFT` मान्य हैं, default `RIGHT` — वही side जहाँ X हमेशा से था; shipped JSON में `full1` और `full2` declare हैं, और app का declare किया कोई भी दूसरा step id उसी path पर स्वीकार होता है। किसी और format में यह control नहीं है: interstitial, app-open, banner और inline native में यह button होता ही नहीं। दोनों sides पूरी तरह mirror हैं: अपने edge से समान inset और समान top margin, इसलिए केवल side बदलता है, size/style/timing नहीं। RTL locale में screen आज की तरह ही mirror होती है: `RIGHT` text end, `LEFT` text start।
- `native.presentation.cta_corner_radius_dp`: `20` dp; placement/screen से override किया जा सकता है। `colorCTA`/`NativeAdStyle.ctaBackgroundColor` में explicit color हो तभी लागू होता है; `default` color XML drawable रखता है।
- `lfo.confirm_button.style`: `CHECK_ICON` (default) shows the check icon; `TEXT` shows “Done”. Both use `onboarding.primary_color`, with 50% opacity before selection and full opacity after selection. `visible_before_selection=false` hides either style until selection. `image_url` applies only to `CHECK_ICON`.
- `lfo.confirm_button.image_url`: `""` XML icon रखता है। Image failure पर SDK check icon आता है। Check tint हमेशा `onboarding.primary_color` से आता है; यह ad CTA से अलग है।
- `lfo.confirm_button.tap_hint.enabled`: default में बंद (`false`)। सिर्फ़ `true` होने पर — remote से, या app के `onboarding_config.json` asset में — language चुनते ही (LFO2) LFO1 वाला हाथ confirm action पर आता है और check icon या “Done” text की ओर इशारा करता है। Screen छोड़ने तक loop चलता है; SETTINGS में कभी नहीं दिखता। Remote हमेशा asset से ऊपर है, इसलिए remote `false` asset के `true` को बंद कर देता है। LFO1 की row वाला हाथ `lfo.tap_hint` से चलता है।
- `lfo.languages.supported_codes`: `[]` app/SDK catalog रखता है। Unknown codes हटते हैं; filtered result खाली हो तो catalog fallback है। `lfo.languages.default_code`: स्पष्ट `""` configured default हटाता है (saved user selection नहीं मिटाता); code दिखाई जाने वाली list (filtered `supported_codes`, वरना catalog) में होना चाहिए। Host का `LanguageConfig.defaultCode` अगर `supported_codes` से बाहर रह जाए तो preselect नहीं होता।
- First-open LFO पर जो tap दूसरी भाषा चुनता है और confirmation popup नहीं खोलता, वह स्क्रीन पर दिख रहे native (LFO2 swap हो चुका हो तो LFO2, वरना LFO1) को नया ad देता है। जो tap LFO2 swap शुरू करता है, उस पर वही swap नया ad है और कुछ reload नहीं होता। अगर LFO2 नहीं दिख पाता (no fill, या `lfo.native2.swap_wait_timeout_ms` के भीतर bind नहीं होता), तो LFO1 बना रहता है और उसकी जगह reload होता है (जब तक confirmation popup पहले से खुला न हो), इसलिए उस चुनाव को भी नया ad मिलता है। Swap छोड़ दिए जाने के बाद fill हुआ LFO2 ad अगली बार भाषा बदलने पर swap होकर आता है, LFO1 के दोबारा request करने से पहले। ऐसा हर दूसरा tap reload करता है, `lfo.native2.enabled` `false` हो तो पहला tap भी। Popup खोलने वाला tap कुछ reload नहीं करता, क्योंकि popup ad को ढक देता है: `lfo.confirm_dialog.enabled` `true` रहते यह `lfo.confirm_dialog.show_from_tap` से आगे का हर tap है। `show_from_tap` हर tap पर पढ़ा जाता है, इसलिए LFO खुले रहते आई remote value अगले tap से यह सीमा, और उसके साथ reloads, खिसका देती है। मौजूदा भाषा दोबारा चुनना भाषा बदलना नहीं है और कभी reload नहीं करता। Reload key का पूरा waterfall (`ids` के चालू floors, सबसे ऊँचा पहले) request करता है, key का `click_action` कुछ भी हो, और replacement bind होने तक मौजूदा ad रखता है; reload fail हो तो पुराना ad रहता है। Slot पर कोई ad न दिख रहा हो (अभी shimmer, या no fill), user उसके ad पर click करके अभी लौटा न हो, या LFO2 swap या उस slot का कोई और load अभी चल रहा हो, तो कोई request नहीं होती, इसलिए तेज़ी से भाषा बदलने पर requests जमा नहीं होतीं।
- `onboarding.steps.ob1.back.enabled`: default में चालू (`true`)। यह तभी लागू होता है जब OB1 pager का पहला page हो; किसी और page पर न यह switch है न button। चालू होने पर OB1 के ऊपर-बाएँ कोने में (RTL में ऊपर-दाएँ) back button दिखता है; यह button, system Back और OB1 पर back gesture LFO पर लौटाते हैं। LFO पहली बार की तरह खुलता है: कोई भाषा पहले से चुनी नहीं, tap hint फिर से; LFO1 स्क्रीन खुलते ही request होता है क्योंकि इस बार उसका कोई preload नहीं है, और LFO2 अपने सामान्य `lfo.native2.preload_trigger` पर, दोनों पूरे waterfall पर। अगली भाषा चुनने पर OB natives फिर preload होते हैं: अब तक इस्तेमाल न हुआ ad बना रहता है; किसी बाद वाले page पर पहले से लगा पर वहाँ अभी तक न देखा गया ad (कोई impression, click या open नहीं) भी बना रहता है, और वह page बिना नए request के वही ad फिर दिखाता है। OB1, और जिस page का ad देखा जा चुका है, नया ad लेता है। बाकी हर page, और OB1 जब यह `false` हो या OB1 पहला page न हो, पुराना Back रखता है: `onboarding.navigation.back_navigates_back` के अनुसार एक page पीछे, और पहले page से app बंद। Value pager खुलते समय पढ़ी जाती है।

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

## Native flow type

`onboarding_config` में `native_flow.type` तय करता है कि splash के बाद की screens अपने natives कैसे पाती हैं:

- `PER_SCREEN` (default): इस guide के बाकी हिस्से में बताया गया flow। LFO, हर onboarding page और Privacy → Goal अपनी-अपनी placement request करते हैं।
- `SHARED_POOL`: LFO, onboarding pager और Privacy → Goal SDK की एक ही screen के अंदर चलते हैं, और हर native slot एक साझा pool से ad लेता है। सामने वाली screen अपनी placement का ad दिखाती है, जैसे ही वह pool में आ जाए; तब तक slot pool का सबसे नया, अभी तक न दिखाया गया ad दिखाता है, फिर किसी दूसरी screen पर पहले दिखाया जा चुका ad। जब एक request चल रही हो, दूसरी screens के background loads queue नहीं होते, छोड़ दिए जाते हैं। ad_config keys, floors और `isEnable` switches `PER_SCREEN` जैसे ही हैं। इसके लिए SDK का built-in ad provider चाहिए; custom `OnboardingAdProvider` के साथ run `PER_SCREEN` ही रहता है।

Type हर run में एक बार पढ़ा जाता है, जब splash language screen खोलता है; बाद में आई value अगले run से लागू होती है, इसलिए कोई run बीच में type नहीं बदलता। Welcome Back और app के अंदर का language picker हमेशा `PER_SCREEN` इस्तेमाल करते हैं। `SHARED_POOL` run के बाद उसी process में native resume (`app_resume.type` `native_resume`) pool की resume screen खोलता है, जो pool के बचे हुए ads दिखाती है और `native_resume` request करती है; अगला cold start फिर सामान्य resume screen इस्तेमाल करता है।

- **Flat alias.** जो backend केवल flat keys publish करता है, वह Boolean `ob_native_reuse_enabled` (default `false`) भेज सकता है: `true` `SHARED_POOL` चुनता है, `false` `PER_SCREEN`। यह तभी गिना जाता है जब backend ने इसे भेजा हो, और बाकी `ob_*` keys की तरह अगले cold start के लिए cache होता है। Precedence: remote `native_flow.type` > भेजी गई `ob_native_reuse_enabled` > app asset > SDK default `PER_SCREEN`। Invalid `native_flow.type` ignore होता है और अगला source लागू होता है।
- **Analytics.** Language screen खोलने वाला हर run user property `ob_native_flow` को `per_screen` या `shared_pool` पर set करता है, यानी वही flow जो वास्तव में चला।
- **पहला open.** Splash remote का इंतज़ार नहीं करता, इसलिए नए install पर, जिसका पहला fetch अभी नहीं पहुँचा, वही type चलता है जो app asset या SDK default बताता है, और onboarding पूरा होने के बाद दोबारा नहीं चलता। A/B results को remote assignment से नहीं, `ob_native_flow` user property से बाँटें।
- **QA.** App के `onboarding_config.json` asset में `{"native_flow":{"type":"SHARED_POOL"}}` रखें, या इसे remote publish करें, फिर process restart करें।

केवल `SHARED_POOL` द्वारा पढ़ी जाने वाली values `native_flow.pool` के नीचे हैं, उसी अर्थ वाली classic key के नाम पर। Classic key `PER_SCREEN` को चलाती रहती है और pool उसे नहीं पढ़ता:

| `PER_SCREEN` key (default) | `SHARED_POOL` key (default) |
| --- | --- |
| `onboarding.fullscreen.skip.delay_ms` (`5000`) | `native_flow.pool.onboarding.fullscreen.skip.delay_ms` (`2000`), page के पहले resume से गिना जाता है |
| `onboarding.fullscreen.auto_next.delay_ms` (`15000`) | `native_flow.pool.onboarding.fullscreen.auto_next.delay_ms` (`10000`), page selection से गिना जाता है |
| `lfo.confirm_button.visible_before_selection` (`true`) | `native_flow.pool.lfo.confirm_button.reveal_delay_ms` (`3000`): पहले language tap के इतनी देर बाद तक confirm button छिपा रहता है |
| कोई नहीं | `native_flow.pool.select.next_reveal_delay_ms` (`3000`): पहले consent बदलाव या option tap के इतनी देर बाद तक Privacy का Continue और Goal का Next छिपे रहते हैं |

दोनों flows इन चीज़ों के लिए एक ही key पढ़ते हैं: language confirmation dialog (`lfo.confirm_dialog.enabled`, `lfo.confirm_dialog.show_from_tap`, चुनी हुई row पर दोबारा tap), OB1 back button (`onboarding.steps.ob1.back.enabled`), ad expiry (`ad_behavior_config` का `native.cache.max_age_ms`, एक घंटा) और हर floor का wait (`native.load.tier_timeout_ms` और उसके `placement_overrides`, 30 सेकंड; जो floor समय पर जवाब न दे वह failure गिना जाता है और अगला floor request होता है)। `onboarding.order`, `privacy_goals_screen.enabled` और `lfo.exit.reuse_splash_inter` दोनों पर लागू होते हैं।

`SHARED_POOL` में `PER_SCREEN` की ये features नहीं हैं: LFO tap hint और device-language promotion, LFO2 swap और उसके timers, `click_action` (click न navigate करता है न reload), OB5, pager swipe, pager के बाद का exit interstitial और Goal के बाद का paywall। इसकी किसी भी screen पर system Back कुछ नहीं करता; OB1 back button चुनी हुई भाषा रखते हुए और confirm button दिखाते हुए LFO पर लौटाता है। LFO का Done splash interstitial का बचा हुआ ad दिखाता है जब `lfo.exit.reuse_splash_inter` `true` हो, और Goal का Next तब जब `native_flow.pool.goals.exit_reuse_splash_inter` `true` हो।

| `native_flow.pool` field | Default | असर |
| --- | --- | --- |
| `adopt_splash_language1` | `true` | Splash का preload किया LFO1 native दोबारा request होने के बजाय pool में जुड़ता है। |
| `locale_apply` | `"IN_PLACE"` | `IN_PLACE` चुनी भाषा चल रही screen पर लागू करता है; `RECREATE` उसे फिर से बनाता है। |
| `exit.next_screen_timing` | `"AFTER_AD"` | `AFTER_AD` / `UNDER_AD`: अगला stage LFO या Goal के exit ad के बंद होने के बाद शुरू हो या उसके नीचे। |
| `done_hidden_gone` | `true` | छिपा forward button जगह नहीं लेता; `false` उसकी जगह रखता है। |
| `pager.smooth_scroll` | `true` | Page बदलना animate होता है। |
| `pager.offscreen_pages` | `0` | मौजूदा page के बगल में जीवित रखे pages; `0` हर page रखता है। |
| `resume_on_goals` | `true` | Privacy और Goal पर return ads दिख सकते हैं। |
| `stuck_loading_log_ms` | `60000` | इतनी देर बाद भी चल रही request log करता है; केवल log। |
| `view.destroy_on_teardown` | `true` | Screen हटने पर slot का ad view destroy होता है; ad pool में रहता है। |
| `goals.exit_reuse_splash_inter` | `true` | Goal का Next splash interstitial का बचा हुआ ad दिखाता है। |
| `parity.refresh_overwrites_unshown` | `true` | Screen पर लौटने का refresh अभी तक न दिखाए गए ad को भी बदलता है; `false` केवल दिखाए गए को। |
| `parity.retry_skipped_polite` | `false` | दूसरी request चलते समय छोड़ा गया background load छोड़ ही दिया जाता है; `true` pool खाली होने पर उसे फिर चलाता है। |
| `parity.async_gate` | `true` | कोई request चल रही है या नहीं, यह main thread से बाहर गिना जाता है और एक frame पीछे रह सकता है; `false` तुरंत गिनता है। |
| `parity.async_selector` | `true` | हर slot कौन सा ad दिखाए, यह main thread से बाहर चुना जाता है; `false` तुरंत चुनता है। |
| `parity.borrow_showing` | `true` | जिस slot के पास दिखाने को और कुछ नहीं, वह कहीं और दिखाया जा चुका ad दिखा सकता है; `false` उसे छिपाता है। |
| `parity.destroy_replaced_ads` | `false` | बदला गया या expire हुआ ad destroy किए बिना छोड़ा जाता है; `true` उसे destroy करता है। |
| `parity.keep_duplicate_ids` | `true` | Floors ad_config की सूची के अनुसार request होते हैं, duplicates समेत; `false` duplicates हटाता है। |
| `parity.impression_marks_loader` | `true` | Placement की requests का कोई भी impression उसे shown चिह्नित करता है; `false` केवल उसके मौजूदा ad का। |
| `parity.screen_slots_bind_started` | `true` | LFO, Privacy, Goal और resume screen started होते ही ads bind करते हैं; `false` resumed होने पर। |
| `parity.pager_slots_bind_resumed` | `true` | Pager pages resumed होने पर ads bind करते हैं; `false` started होते ही। |
| `parity.keep_after_flow` | `true` | Resume screen के लिए pool process खत्म होने तक रहता है; `false` onboarding पूरा होने पर उसे release करता है। |
| `parity.reset_on_new_run` | `false` | नया run pool के ads रखता है; `true` खाली pool से शुरू करता है। |
| `parity.respect_focus_window` | `false` | Requests window focus का इंतज़ार नहीं करतीं; `true` `PER_SCREEN` natives की तरह इंतज़ार करता है। |
| `parity.resume_background_preload` | `false` | Native resume केवल अपनी screen खुलने पर request करता है; `true` background में भी preload करता है। |
| `parity.refresh_marks_in_flight` | `true` | Refresh चल रही request गिना जाता है, इसलिए दूसरी screens के background loads रुकते हैं जबकि रखा हुआ ad दिखता रहता है; `false` refresh को इस जाँच से बाहर रखता है। |

`parity.*` switches उस reference app का व्यवहार दोहराते हैं जिस पर pool बना है। जब तक आप जान-बूझकर वह व्यवहार tune न कर रहे हों, इन्हें default पर छोड़ें।

## Fetch timing और QA

Splash consent, remote refresh और billing साथ शुरू करता है। Consent पूरा होते ही banner/native slot और interstitial मौजूदा configuration और entitlement से request होते हैं; remote या billing का इंतज़ार नहीं होता। Cache या fetch से मिले remote values asset से ऊपर रहते हैं। SDK-owned refresh splash बंद होने के बाद भी चलता है, background wait कम-से-कम 60 सेकंड है। बाद के reads नई values लेते हैं; पहले भेजे requests, timers और तय navigation दोबारा नहीं चलते। `SAME_TIME` और `ALTERNATE` दोनों यही क्रम अपनाते हैं। `onRemoteFetched()` केवल जीवित splash पर चलता है; process-owned integration के लिए `SettingsRegistry.addFetchListener` इस्तेमाल करें।

Firebase in-flight fetch साझा करता है; एक caller का timeout दूसरों को cancel नहीं करता। Parsing/validation Main से बाहर, persistence IO पर और ads/UI notification Main पर होते हैं। Successful fetch process में reuse होता है और Firebase के minimum fetch interval के अधीन रहता है, जो default रूप से 12 घंटे है; SDK इसे set नहीं करता, इसलिए Console edits अगले launch तक पहुँचने ज़रूरी हों तो अपनी app में `minimumFetchIntervalInSeconds` set करें (उदाहरण: debug में `0`, release में `3600`)। Console QA में process restart करें; सिर्फ screen दोबारा खोलना नया fetch सुनिश्चित नहीं करता। `AdConfig.refresh()` बताता है कि कोई `ad_remote_config` document लागू हुआ या नहीं; यह grouped settings के बारे में कुछ नहीं बताता। [QA कदम](firebase-integration.hi.md#remote-notes) देखें।

## सभी default fields

नीचे समय milliseconds में है; ad_config/legacy API में स्पष्ट seconds नाम वाले fields अपवाद हैं। `schema_version=1`; `revision` metadata है। Empty objects scope-specific override नहीं जोड़ते और empty template strings inheritance बताते हैं। Tables SDK assets के समान defaults रखते हैं। Copy/paste onboarding file तीन जगह अलग है: `onboarding.primary_color` `""` है, `onboarding.exit_interstitial.enabled` `true` है, और उसमें `native_flow` scope नहीं है। JSON names/enums का अनुवाद नहीं होता।

### ad_behavior_config

| Field | SDK default |
| --- | --- |
| `schema_version` | `1` |
| `revision` | `0` |
| `global.ads_enabled` | `true` |
| `consent.network_timeout_ms` | `10000` |
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
| `native.reload.resume_debounce_ms` | `500` |
| `native.reload.min_after_bind_ms` | `3000` |
| `native.reload.timer_enabled` | `false` |
| `native.reload.interval_ms` | `15000` |
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
| `interstitial_auto_buffer.rules` | `inter_all: 30000ms / 2 taps; inter_back: 30000ms / 1 tap` — हर rule `enabled`, `interval_ms`, `tap_threshold` और `independent_interval` लेता है (`shared_config` `true` होने पर `true` उस placement को अपना interval देता है)। |
| `interstitial.cache.max_age_ms` | `3600000` |
| `rewarded.load.tier_timeout_ms` | `30000` |
| `rewarded.cache.max_age_ms` | `3600000` |
| `app_open.load.timeout_ms` | `30000` |
| `app_open.load.max_background_requests` | `3` |
| `app_open.load.background_retry_window_ms` | `120000` |
| `app_open.load.offline_recheck_ms` | `5000` |
| `app_open.load.failure_backoff_ms` | `[5000,30000,120000]` — 1–10 integers, हर एक 1–3600000; `[]` या कोई invalid element ignore होता है और app asset/SDK schedule लागू होता है। |
| `app_open.presentation.loading_timeout_ms` | `3000` |
| `app_open.presentation.pre_show_delay_ms` | `800` |
| `app_open.presentation.skip_after_ad_click` | `false` |
| `app_open.cache.max_age_ms` | `14400000` |
| `app_resume.type` | `native_resume` — `native_resume`, `inter_resume`, `open_resume` या `none`; कोई और value ignore होती है और निचला tier लागू होता है। |
| `placement_overrides` | `{}` |

### onboarding_config

| Field | SDK default |
| --- | --- |
| `schema_version` | `1` |
| `revision` | `0` |
| `privacy_goals_screen.enabled` | `false` |
| `flow.skip_ad_only_steps_when_premium` | `true` |
| `flow.fullscreen_skip_style` | `"CLOSE_ICON"` |
| `splash.ads.slot_format` | `"BANNER"` |
| `splash.ads.banner.behavior` | `{}` |
| `splash.ads.native.behavior` | `{}` |
| `splash.ads.interstitial.behavior` | `{}` |
| `splash.timing.min_display_ms` | `3000` |
| `splash.timing.ad_budget_ms` | `60000` |
| `splash.timing.slot_min_visible_ms` | `1000` | Compatibility के लिए स्वीकार; splash इसे इस्तेमाल नहीं करता। |
| `splash.timing.slot_wait_after_inter_ms` | `10000` | Compatibility के लिए स्वीकार; splash इसे इस्तेमाल नहीं करता। |
| `splash.timing.notification_settle_ms` | `1000` |
| `splash.load.ad_strategy` | `"ALTERNATE"` |
| `splash.load.lfo1_preload_mode` | `"SEQUENTIAL"` |
| `splash.load.remote_fetch_timeout_ms` | `10000` |
| `splash.load.consent_hook_timeout_ms` | `10000` |
| `splash.load.billing_timeout_ms` | `5000` |
| `splash.permissions.no_internet_prompt_enabled` | `true` |
| `splash.permissions.notification_enabled` | `true` |
| `splash.navigation.next_screen_timing` | `"AUTO"` — `AUTO` / `AFTER_AD` / `UNDER_AD` |
| `splash.native.skip.delay_ms` | `3000` |
| `splash.native.skip.style` | `"CLOSE_ICON"` |
| `splash.native.skip.position` | `"RIGHT"` |
| `splash.native.behavior` | `{}` |
| `lfo.native1.behavior` | `{}` |
| `lfo.native2.enabled` | `true` |
| `lfo.native2.behavior` | `{}` |
| `lfo.native2.swap_wait_timeout_ms` | `8000` |
| `lfo.native2.preload_trigger` | `"LFO_SHOWN"` |
| `lfo.tap_hint.enabled` | `true` |
| `lfo.tap_hint.delay_ms` | `3000` |
| `lfo.confirm_button.visible_before_selection` | `true` |
| `lfo.confirm_button.save_on_back` | `true` |
| `lfo.confirm_button.style` | `"CHECK_ICON"` — `CHECK_ICON` / `TEXT` (Done); uses `onboarding.primary_color` |
| `lfo.confirm_button.image_url` | `""` |
| `lfo.confirm_button.tap_hint.enabled` | `false` — `true` (remote या app asset) होने पर selection के बाद confirm action पर हाथ |
| `onboarding.primary_color` | `"#FF375E"` | Shared NEXT, final Get Started, active indicator, LFO check/Done और selected language radio color. |
| `lfo.confirm_dialog.enabled` | `true` |
| `lfo.confirm_dialog.show_from_tap` | `4` |
| `lfo.confirm_dialog.native_preload_trigger` | `"DIALOG_OPEN"` — `LFO_SHOWN` / `FIRST_SELECTION` / `DIALOG_OPEN` |
| `lfo.confirm_dialog.native_behavior` | `{}` |
| `lfo.languages.supported_codes` | `[]` |
| `lfo.languages.default_code` | `""` |
| `lfo.exit.reuse_splash_inter` | `true` |
| `onboarding.navigation.lock_pager_swipe` | `false` |
| `onboarding.navigation.swipe_completes_last_step` | `true` |
| `onboarding.navigation.back_navigates_back` | `true` |
| `onboarding.navigation.peek_px` | `1` |
| `onboarding.ads.content_native_behavior` | `{}` |
| `onboarding.ads.fullscreen_native_behavior` | `{}` |
| `onboarding.fullscreen.skip.enabled` | `true` |
| `onboarding.fullscreen.skip.delay_ms` | `5000` |
| `onboarding.fullscreen.skip.style` | `"CLOSE_ICON"` |
| `onboarding.fullscreen.auto_next.enabled` | `true` |
| `onboarding.fullscreen.auto_next.delay_ms` | `15000` |
| `onboarding.steps.ob1.back.enabled` | `true` — OB1 पहला page हो तो उस पर ऊपर back button; button, Back और back gesture LFO पर लौटाते हैं। बाकी pages पुराना Back रखते हैं |
| `onboarding.steps.full1.fullscreen.skip.position` | `"RIGHT"` |
| `onboarding.steps.full2.fullscreen.skip.position` | `"RIGHT"` |
| `onboarding.order` | App order when absent; array selects/reorders app catalog, `[]` skips pager. |
| `onboarding.preload.ob5_on_last_step` | `true` |
| `onboarding.exit_interstitial.enabled` | `false` — exit interstitial बंद रहता है जब तक remote, app asset, भेजी गई `ob_ads_inter_after_ob3_enabled = true` या `AdsConfig.afterOnboardingInterstitialEnabled = true` उसे चालू न करे। पुराने SDKs में default `true` था। |
| `onboarding.exit_interstitial.preload_on_entry` | `true` |
| `onboarding.exit_interstitial.wait_timeout_ms` | `8000` |
| `onboarding.exit_interstitial.next_screen_timing` | `"UNDER_AD"` |
| `onboarding.exit_interstitial.behavior` | `{}` |
| `ob5.enabled` | `false` |
| `ob5.native.behavior` | `{}` |
| `ob5.skip.enabled` | `true` |
| `ob5.skip.delay_ms` | `3000` |
| `ob5.skip.style` | `"CLOSE_ICON"` |
| `ob5.skip.position` | `"RIGHT"` |
| `ob5.auto_dismiss_ms` | `15000` |
| `welcome_back.enabled` | `false` |
| `welcome_back.native1.behavior` | `{}` |
| `welcome_back.native2.behavior` | `{}` |
| `native_resume.start_button.color` | `"#FF375E"` — native resume screen पर Start का text color; `""` layout का color रखता है। |
| `native_flow.type` | `"PER_SCREEN"` — `PER_SCREEN` / `SHARED_POOL`; [Native flow type](#native-flow-type) देखें |
| `native_flow.pool.parity.refresh_overwrites_unshown` | `true` |
| `native_flow.pool.parity.retry_skipped_polite` | `false` |
| `native_flow.pool.parity.async_gate` | `true` |
| `native_flow.pool.parity.async_selector` | `true` |
| `native_flow.pool.parity.borrow_showing` | `true` |
| `native_flow.pool.parity.destroy_replaced_ads` | `false` |
| `native_flow.pool.parity.keep_duplicate_ids` | `true` |
| `native_flow.pool.parity.impression_marks_loader` | `true` |
| `native_flow.pool.parity.screen_slots_bind_started` | `true` |
| `native_flow.pool.parity.pager_slots_bind_resumed` | `true` |
| `native_flow.pool.parity.keep_after_flow` | `true` |
| `native_flow.pool.parity.reset_on_new_run` | `false` |
| `native_flow.pool.parity.respect_focus_window` | `false` |
| `native_flow.pool.parity.resume_background_preload` | `false` |
| `native_flow.pool.parity.refresh_marks_in_flight` | `true` |
| `native_flow.pool.adopt_splash_language1` | `true` |
| `native_flow.pool.locale_apply` | `"IN_PLACE"` — `IN_PLACE` / `RECREATE` |
| `native_flow.pool.exit.next_screen_timing` | `"AFTER_AD"` — `AFTER_AD` / `UNDER_AD` |
| `native_flow.pool.done_hidden_gone` | `true` |
| `native_flow.pool.pager.smooth_scroll` | `true` |
| `native_flow.pool.pager.offscreen_pages` | `0` |
| `native_flow.pool.resume_on_goals` | `true` |
| `native_flow.pool.stuck_loading_log_ms` | `60000` |
| `native_flow.pool.view.destroy_on_teardown` | `true` |
| `native_flow.pool.lfo.confirm_button.reveal_delay_ms` | `3000` |
| `native_flow.pool.select.next_reveal_delay_ms` | `3000` |
| `native_flow.pool.goals.exit_reuse_splash_inter` | `true` |
| `native_flow.pool.onboarding.fullscreen.skip.delay_ms` | `2000` |
| `native_flow.pool.onboarding.fullscreen.auto_next.delay_ms` | `10000` |

`native_fs` मौजूदा splash preload का इंतज़ार करता है और loading के दौरान shimmer दिखाता है; स्क्रीन खोलने पर नया request नहीं होता। डिफ़ॉल्ट रूप से ad bind होने के 3 सेकंड बाद X दिखता है। स्क्रीन अपने आप बंद नहीं होती; पुरानी `splash.native.auto_dismiss_ms` key अनदेखी की जाती है।

`onboarding.preload.ob5_on_last_step` OB5 का इकलौता preload है, और pager exit OB5 तभी खोलता है जब उसका native पहले से load हो चुका हो। इसलिए इसे `false` करने पर OB5 बंद हो जाता है, `ob5.enabled: true` होने पर भी।

`interstitial_auto_buffer` is a top-level group with `enabled: true` by default. This group controls only placements configured in `InterstitialAutoBuffer` or its remote `rules`, excluding reserved placements. The host must still call `configure()` / `start()`; enabling this field does not start the buffer or show ads automatically. Other interstitial settings remain under `interstitial`.

Remote या app asset का `interstitial_auto_buffer.rules.<placement>` उस placement को tune करता है जिसे host ने `configure()` में list किया है, या `enabled: false` से उसे बंद करता है। Host की list से बाहर की placement के लिए rule भी उसे managed list में जोड़ देता है: साफ़ `"enabled": true` हो तो buffer उसे चलाता है, न हो तो placement managed रहती है पर बंद, इसलिए उसका preload, load और show सब `DISABLED_CONFIG` के साथ skip होते हैं। Host का `isPlacementEnabled` predicate फिर भी लागू होता है। SDK के bundled rules सिर्फ़ `inter_all` और `inter_back` के लिए हैं, जो आज auto-buffer होने वाली दो placements हैं; bundled values किसी placement को अपने आप चालू नहीं करतीं, इसलिए host को उन्हें list करना ही होगा। `tick_ms: 0`, `interstitial.frequency.interval_ms` लेता है, host `ERainAdConfig.intervalInterstitialAd` fallback है।

### Onboarding primary color

Set `onboarding.primary_color` in `onboarding_config` to `"#RRGGBB"` or `"#AARRGGBB"` (for example `"#1E88E5"`). यह NEXT बटन, final Get Started बटन, active progress indicator, LFO check/Done button और selected LFO language radio का साझा onboarding color है। Remote overrides the app asset; an empty value keeps the existing UI color. Native `ad_config.<placement>.colorCTA` colors both the CTA background and the Ad badge background.
