# Ads behavior और onboarding settings

**OB catalog:** `ob1..ob4` → `native_ob1..4`; `full1/full2` → `native_full1/2`. Default: `ob1, full1, ob2, full2, ob3, ob4`. All eligible OB natives preload on language selection. Remote `onboarding.order` selects/reorders app-declared steps. [Configuration and migration / Hướng dẫn chi tiết](onboarding-flow.vi.md). `native_fs` remains the separate splash native.

JSON में पेज चुनने और उनका क्रम तय करने के लिए केवल `onboarding.order` इस्तेमाल करें; पेज हटाने के लिए उसका ID निकालें। `steps` केवल वैकल्पिक template, behavior या fullscreen overrides के लिए है। `steps.<id>.enabled` अब अनदेखा किया जाता है; वैध `order` पुराने `ob_enable_step_ob1..4` flags से भी प्राथमिकता रखता है। हर ad placement को `ad_config.<placement>.isEnable` से नियंत्रित करें: ads बंद होने पर content पेज रहता है, fullscreen ad पेज छोड़ दिया जाता है।

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

हटाए गए aliases `app_open.presentation.excluded_hosts`, `app_open.enabled`, `app_open.load.background_delay_ms`, `banner.reload.interval_ms`, placement/native-placement mappings, duplicate unit switches और app-content groups `ui`/`question.content` ignore होते हैं, पुराने cached JSON में भी। पुराने remote UI APIs `ob_ui_content`, `ob_ui_design_tokens`, `ob_question_config`, `ob_enable_ui_content` से चलते रहते हैं; वे नए grouped documents में fields नहीं हैं। Question button का `QuestionConfig.ctaTextRes` app में रहता है और ad CTA से अलग है।

## App को एक बार configure करें

[Partner setup sample](examples/ads-onboarding/OnboardKitSetup.kt) इस्तेमाल करें। Content/resources app में रखें; SDK इस्तेमाल के समय वर्तमान ad document resolve करता है:

```kotlin
ads = AdsConfig.fromAdConfig()
```

यह `onboardKitConfig` builder का default भी है। Application startup पर IDs copy करने के बजाय placement keys बने रहते हैं। Fetch के बाद page eligibility, preload और display वर्तमान units लेते हैं; दूसरा `configure()` आवश्यक नहीं। Usable unit न होने पर ad-only page pager खोलने से पहले हटता है। Raw IDs के साथ manual `AdsConfig(...)` उन hosts के लिए उपलब्ध है जो units स्वयं resolve करते हैं।

| Slot | Default ad_config key |
| --- | --- |
| Splash banner / interstitial | `banner_splash` / `inter_splash` |
| Splash native (नीचे का slot, जब `splash.ads.slot_format` = `NATIVE`) | `native_splash` |
| Returning-user splash | `<splash interstitial key>_o` (`inter_splash_o`) |
| LFO1 / LFO2 / confirmation dialog | `native_lang` / `native_lang_alt` / `native_popup_lang` |
| Content OB1 / OB2 / OB3 / OB4 | `native_ob1` / `native_ob2` / `native_ob3` / `native_ob4` |
| Fullscreen Full1 / Full2 | `native_full1` / `native_full2` |
| OB5 | `native_onboarding_fullscreen_1_4` |
| Question native / interstitial | `native_question` / `inter_question` |
| Exit interstitial | `inter_after_ob3` |
| App resume | `open_resume` |

### Splash का निचला slot

Splash में loading bar के नीचे एक ही ad slot है, और `splash.ads.slot_format` तय करता है कि उसे कौन
सा format भरेगा:

| Value | Placement | ad_config key |
| --- | --- | --- |
| `BANNER` (default) | `AdPlacement.SplashBanner` | `banner_splash` |
| `NATIVE` | `AdPlacement.SplashInlineNative` | `native_splash` |

एक launch सिर्फ़ चुने हुए format को request करता है, इसलिए यह बदलना खर्च को हटाता है, दूसरा impression
नहीं जोड़ता। इंतज़ार भी साझा है: `splash.timing.banner_wait_ms` जो भी format लोड हो रहा हो उसे सीमित
करता है, और `ob_ads_splash_banner_enabled` दोनों के लिए इस position को बंद करता है।

Native एक तय media-left frame से render होता है, इसलिए `positionCTA` और `components` का क्रम बेअसर
हैं — `colorCTA` और `heightCTA` फिर भी लागू होते हैं। `AdPlacement.SplashInlineNative` और
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

यही association `ad_behavior_config.placement_overrides.<key>` भी चुनती है: LFO1 सामान्यतः `native_lang` इस्तेमाल करता है, internal telemetry/buffer key `language1` नहीं। IDs, floors, unit switches और मौजूदा CTA fields ad_config में रहते हैं।

## Local defaults, remote cache और failure

`app/src/main/assets/ad_behavior_config.json` / `onboarding_config.json` नाम की app files SDK assets की जगह लेती हैं। पूरा sample copy करें या केवल बदलने वाले fields लिखें, फिर rebuild और process restart करें। App file न हो तो bundled defaults बिना manual setters के काम करते हैं। [Firebase guide](firebase-integration.hi.md#local-defaults) में दोनों छोटे, पूरे उदाहरण हैं।

- Successful fetch/activation के valid remote fields > custom app asset > मौजूदा host constructor/setter fallback > SDK defaults। Cached valid remote fields startup पर भी इसी priority में हैं।
- Successful fetch में missing parameter/field पिछला remote assignment हटाकर local/legacy fallback लेता है। गलत type/enum/range और `null` ignore होते हैं; valid `false`/`0` assignments बने रहते हैं।
- Fetch failure/timeout, malformed या blank पूरा JSON, unsupported schema होने पर अंतिम valid document बना रहता है। पहली run में valid remote cache न हो तो local/defaults रहते हैं। **Failed fetch valid remote cache के ऊपर local values लागू नहीं करता।**
- Successful fetch पर remote overrides हटाने के लिए `{}` या `{"schema_version":1}` publish करें। Empty String malformed है, reset नहीं। नया remote object पुराने remote overrides को replace करता है, patch नहीं; missing fields local fallback लेते हैं।
- Custom/partial app asset का हर मौजूद valid field explicit assignment है, `false`/`0` समेत। पूरी bundled asset की बिना बदली copy host constructor/setter fallback रखती है। Firebase की published default value remote है, SDK local default नहीं; source Firebase in-app defaults को fetched remote नहीं मानता।
- SDK defaults build के समय assets से generate होते हैं, Context से पहले उपलब्ध हैं और `null` नहीं रखते। अलग Kotlin/XML defaults maintain नहीं करने पड़ते। App के invalid fields fallback लेते हैं; app को नया parser नहीं चाहिए।
- Consent, premium, `setCanRequestAds`, `AdsConfig.enabled=false`, runtime reload pause और lifecycle ads रोक सकते हैं। AutoBuffer के लिए host का `start` integration आवश्यक है; JSON Activities या host features initialize नहीं करता।
- पुराने `ob_*` keys fallback/compatibility के लिए हैं। Debug ad IDs `ad_config_debug.json` से रखता है लेकिन दोनों grouped documents स्वीकार करता है; नए parameters के automatic `_debug` variants नहीं हैं।

## Behavior scopes

Screen slot override > shared content/fullscreen OB override > placement override > format override > local/default। Empty `behavior` object optional scope है, उसमें कोई leaf override नहीं। इसमें ad IDs, unit switches या app resource IDs न रखें।

| placement_overrides में format | Supported fields |
| --- | --- |
| banner | `reload.allowed`, `reload.auto_enabled`, `reload.resume_debounce_ms`, `presentation.*` |
| native | `click.action`, `load.tier_timeout_ms`, `reload.*`, `preload.*`, `presentation.auto_shimmer`, `presentation.empty_visibility`, `presentation.cta_corner_radius_dp` |
| interstitial | `load.tier_timeout_ms`, `load_and_show.wait_timeout_ms`, `load_and_show.buffer_wait_timeout_ms`, `presentation.loading_enabled`, `cache.max_age_ms` |
| rewarded | `load.tier_timeout_ms`, `cache.max_age_ms` |

OB interstitial slots tier और wait timeout support करते हैं। Frequency, next-screen timing, pre-show delay, app-open और native cache TTL format scope में हैं। Custom steps `onboarding.steps.<id>.fullscreen.skip.{enabled,delay_ms,style}`, `.auto_next.{enabled,delay_ms}` तथा content `.native_template` support करते हैं।

Banner cadence positive `ad_config.<key>.reloadIntervalSeconds` से, नहीं तो host value से आती है (SDK default 15000 ms)। Interval सेट करना timer चालू नहीं करता। Initial app-open delay `open_resume.app_resume_load_delay_ms` में है (2000 ms)। Native click actions और defaults नीचे दिए हैं। Timer reload click replacement से अलग है। Cache age केवल documented SDK limit से कम की जा सकती है।

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
- Frame priority: explicit per-step template > explicit screen/group template > placement का `ad_config.positionCTA` (`TOP`/`BOTTOM`) > host/SDK template। Explicit में valid remote और custom app asset शामिल हैं; unmodified SDK asset पुराने settings override नहीं करता। `positionCTA` स्वयं test करने के लिए संबंधित template override हटाएँ।
- Content presets `CTA_TOP`, `CTA_BOTTOM`, `COMPACT` हैं; group template fields पहले की तरह `FULL_SCREEN`/`DIALOG` भी लेते हैं। Language popup हमेशा `DIALOG`, ad-only Full1/Full2/OB5 हमेशा `FULL_SCREEN` इस्तेमाल करते हैं। Custom app layout resources local रहते हैं।
- Preload और show एक template resolver इस्तेमाल करते हैं। Host जल्दी preload करे या preload के बाद remote refresh हो तो bind वर्तमान SDK frame इस्तेमाल करता है, loaded ad हटाए बिना। दिखता हुआ view अगले bind तक बना रहता है। यह default splash order नहीं: दोनों strategies में LFO1 remote के बाद schedule होता है।
- Shared `flow.fullscreen_skip_style`, OB `onboarding.fullscreen.skip.style`, per-step `.fullscreen.skip.style` और `ob5.skip.style` में `CLOSE_ICON` / `TEXT` मान्य हैं। Specific scope shared scope से पहले, फिर host fallback है। घोषित styles के defaults `CLOSE_ICON` हैं; style बदलने से Skip/auto-next timing नहीं बदलती।
- `native.presentation.cta_corner_radius_dp`: `20` dp; placement/screen से override किया जा सकता है। `colorCTA`/`NativeAdStyle.ctaBackgroundColor` में explicit color हो तभी लागू होता है; `default` color XML drawable रखता है।
- `lfo.confirm_button.image_url` / `tint_color`: `""` XML icon/color रखता है। Image failure पर SDK check icon, invalid color पर मौजूदा color रहता है। यह LFO confirm control है, ad CTA से अलग।
- `lfo.languages.supported_codes`: `[]` app/SDK catalog रखता है। Unknown codes हटते हैं; filtered result खाली हो तो catalog fallback है। `lfo.languages.default_code`: `""` पुराना चुनाव रखता है; नया code app catalog में होना चाहिए।

`flow.lock_portrait`, `flow.system_bars.*` और `onboarding.steps.ob1/ob2/ob4.progress_visible` नए JSON schema में नहीं हैं। पुराने defaults के साथ `BehaviorConfig`, `SystemBarConfig`, `ContentStepDefinition.showsProgressIndicator` इस्तेमाल करें।

## Fetch timing और QA

`ALTERNATE` splash ads से पहले remote completion या timeout/fallback का इंतज़ार करता है। `SAME_TIME` केवल splash banner/interstitial उससे पहले शुरू कर सकता है। LFO1 preload **दोनों strategies में remote के बाद** schedule होता है; LFO `PARALLEL` का मतलब splash interstitial के load outcome का इंतज़ार न करना है। Strategy splash entry पर local/cache settings से चुनी जाती है; अभी fetch हुआ strategy बदलाव अगली splash attempt में लागू होता है।

Firebase in-flight fetch साझा करता है; एक caller का timeout दूसरों को cancel नहीं करता। Parsing/validation Main से बाहर, persistence IO पर और ads/UI notification Main पर होते हैं। Successful fetch process में reuse होता है और Firebase fetch interval लागू रहता है। Console QA में process restart करें; सिर्फ screen दोबारा खोलना नया fetch सुनिश्चित नहीं करता। Debug IDs pinned हों तो settings लागू होने के बाद भी `AdConfig.refresh()` `false` दे सकता है। [QA कदम](firebase-integration.hi.md#remote-notes) देखें।

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
| `splash.timing.banner_wait_ms` | `0` |
| `splash.timing.notification_settle_ms` | `0` |
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
