# Ads behavior and onboarding settings

[English](remote-settings.md) · [Tiếng Việt](remote-settings.vi.md) · [हिन्दी](remote-settings.hi.md)

For Console setup, follow [publishing the three String parameters](firebase-integration.md#remote-json). To supply custom offline defaults, follow [creating the two app-side JSON files](firebase-integration.md#local-defaults). Both full examples below match the SDK assets exactly.

**Version requirement:** use SDK `5.3.5` or newer for grouped settings and `AdsConfig.fromAdConfig()`, with the same version for all modules. Adding Firebase keys alone does not update an older SDK.

## Documents and ownership

The existing Firebase parameter remains `ad_remote_config`; `ad_config.json` / `ad_config_debug.json` remain its local asset filenames. Add only the two new String parameters:

| Parameter | Full default JSON | SDK source |
| --- | --- | --- |
| `ad_behavior_config` | [Copy/paste sample](examples/ads-onboarding/ad_behavior_config.json) | [SDK asset](../ads/src/main/assets/ad_behavior_config.json) |
| `onboarding_config` | [Copy/paste sample](examples/ads-onboarding/onboarding_config.json) | [SDK asset](../onboardkitorigin/src/main/assets/onboarding_config.json) |

- **ad_config:** `id`, `ids`, `isEnable`, `enable_ua_check`, `reloadIntervalSeconds`, `colorCTA`, `heightCTA`, `positionCTA`, `components`, `open_resume.app_resume_load_delay_ms`. The new documents do not duplicate these fields, ad-unit mappings or individual unit switches.
- **ad_behavior_config:** ad-format timeout/cache, reload/preload policy, frequency/AutoBuffer, consent timeout, telemetry, native CTA corner radius and app-open behavior. Banner type/size uses SDK presets.
- **onboarding_config:** flow steps, X/Skip timing/style, auto-next, swipe/back/click-return, splash strategy, LFO/OB preload, exit/question behavior, native templates, LFO confirmation appearance and selection from the app's language catalog. Enabling a step cannot re-enable an ad unit with `isEnable=false`.
- **App code/resources:** `R.layout`, `R.drawable`, `R.string`, custom page layouts, language resources/catalog, progress indicators, system bars/orientation and Activity exclusions. SDK ad-presentation presets remain remotely configurable.

The removed aliases `app_open.presentation.excluded_hosts`, `app_open.enabled`, `app_open.load.background_delay_ms`, `banner.reload.interval_ms`, placement/native-placement mappings, duplicate unit switches and grouped app-content payloads `ui`/`question.content` are ignored, including in old cached JSON. Existing remote UI APIs still work through `ob_ui_content`, `ob_ui_design_tokens`, `ob_question_config` and `ob_enable_ui_content`; these are not new fields in the two grouped documents. Question button copy through `QuestionConfig.ctaTextRes` remains app-owned and is separate from an ad's CTA.

## Configure the app once

Use the [partner setup sample](examples/ads-onboarding/OnboardKitSetup.kt). Keep app content/resources in the app and let the SDK resolve the ad document at use time:

```kotlin
ads = AdsConfig.fromAdConfig()
```

This is also the `onboardKitConfig` builder default. It preserves placement keys rather than copying ad IDs at Application startup. After fetch, page eligibility, preload and display use the current units; no second `configure()` call is required. Ad-only steps with no usable unit are removed before opening the pager. Manually constructed `AdsConfig(...)` with raw IDs remains supported for hosts that own unit resolution themselves.

| Slot | Default ad_config key |
| --- | --- |
| Splash banner / interstitial | `banner_splash` / `inter_splash` |
| Returning-user splash | `<splash interstitial key>_o` (`inter_splash_o`) |
| LFO1 / LFO2 / confirmation dialog | `native_lang` / `native_lang_alt` / `native_popup_lang` |
| Content OB1 / OB2 / OB4 | `native_ob1` / `native_ob2` / `native_ob3` |
| Fullscreen OB3 | `native_fs` |
| OB5 | `native_onboarding_fullscreen_1_4` |
| Question native / interstitial | `native_question` / `inter_question` |
| Exit interstitial | `inter_after_ob3` |
| App resume | `open_resume` |

Declare only nonstandard associations in code:

```kotlin
ads = AdsConfig.fromAdConfig(mapOf(
    AdPlacement.Language1 to "my_language_native",
    AdPlacement.StepNative(StepId("custom")) to "my_content_native",
))
```

This association also selects `ad_behavior_config.placement_overrides.<key>`: LFO1 normally uses `native_lang`, not the internal telemetry/buffer key `language1`. IDs, floors, unit switches and existing CTA fields remain in ad_config.

## Local defaults, remote cache and failure

App files named `app/src/main/assets/ad_behavior_config.json` / `onboarding_config.json` override the SDK assets. Copy a full sample or declare only the fields you want to change, then rebuild and restart the process. With no app file, bundled defaults work without any manual setters. The [Firebase guide](firebase-integration.md#local-defaults) includes two complete minimal examples.

- Valid remote fields after successful fetch/activation > custom app asset > existing host constructor/setter fallback > bundled SDK defaults. Valid cached remote fields retain this priority at startup.
- Successful fetch with a missing parameter/field removes its previous remote assignment and falls back to local/legacy. Invalid field types/enums/ranges and `null` are ignored; valid `false`/`0` remain assignments.
- Fetch failure/timeout, malformed or blank whole JSON, or unsupported schema keeps the last valid document. On a first run without valid remote cache, local/defaults remain. **A failed fetch does not force local values over a valid remote cache.**
- Publish `{}` or `{"schema_version":1}` to clear a document's remote overrides after a successful fetch. An empty String is malformed, not a reset. New remote objects replace previous remote overrides rather than patching them; omitted fields fall back locally.
- A custom/sparse app asset assigns every valid field present, including `false`/`0`. An unchanged copy of the full bundled asset preserves existing host constructor/setter fallbacks. Firebase's published default value is still remote, not the SDK's local default; Firebase in-app defaults are not accepted as fetched remote by the source.
- SDK defaults are generated from the assets during build, available before Context initialization and contain no `null`. No duplicate Kotlin/XML defaults need maintenance. Invalid app fields fall back; the app does not need its own parser.
- Consent, premium, `setCanRequestAds`, `AdsConfig.enabled=false`, runtime reload pause and lifecycle may still block ads. AutoBuffer needs the host's `start` integration; JSON does not create Activities or initialize host features.
- Legacy `ob_*` keys remain fallbacks/compatible. Debug keeps ad IDs from `ad_config_debug.json` while accepting both grouped documents; there are no automatic `_debug` variants for the new parameters.

## Behavior scopes

Screen slot override > shared content/fullscreen OB override > placement override > format override > local/default. Empty `behavior` objects provide an optional scope with no leaf overrides; no ad IDs, unit switches or app resource IDs go there.

| Format in placement_overrides | Supported fields |
| --- | --- |
| banner | `reload.allowed`, `reload.auto_enabled`, `reload.resume_debounce_ms`, `presentation.*` |
| native | `load.tier_timeout_ms`, `reload.*`, `preload.*`, `presentation.auto_shimmer`, `presentation.empty_visibility`, `presentation.cta_corner_radius_dp` |
| interstitial | `load.tier_timeout_ms`, `load_and_show.wait_timeout_ms`, `load_and_show.buffer_wait_timeout_ms`, `presentation.loading_enabled`, `cache.max_age_ms` |
| rewarded | `load.tier_timeout_ms`, `cache.max_age_ms` |

OB interstitial slots support tier and wait timeouts. Frequency, next-screen timing, pre-show delay, app-open and native cache TTL are format-wide. Custom steps support `onboarding.steps.<id>.fullscreen.skip.{enabled,delay_ms,style}`, `.auto_next.{enabled,delay_ms}` and content `.native_template`.

Banner cadence comes from positive `ad_config.<key>.reloadIntervalSeconds`, otherwise the host value (SDK default 15000 ms). Setting an interval does not enable the timer. Initial app-open delay stays in `open_resume.app_resume_load_delay_ms` (2000 ms). General native click replacement defaults to `true`; content/fullscreen OB and OB5 default to `false` because click-return advances/leaves those steps. Native timer reload is separate from click replacement. Cache age may only be shortened from the documented SDK limits.

## Native templates, CTA and X/Skip experiments

- `lfo.native_template`: `CTA_BOTTOM`; `onboarding.ads.content_template`: `CTA_TOP`; per-content-step `native_template`: `""` to inherit; `question.native.template`: `CTA_BOTTOM`.
- Frame priority: explicit per-step template > explicit screen/group template > per-placement `ad_config.positionCTA` (`TOP`/`BOTTOM`) > host/SDK template. Explicit includes valid remote or custom app assets; the unmodified SDK asset does not override existing settings. Remove the corresponding template override when testing `positionCTA` itself.
- Content presets are `CTA_TOP`, `CTA_BOTTOM`, `COMPACT`; group template fields also accept `FULL_SCREEN`/`DIALOG` as before. The language popup always uses `DIALOG`; ad-only OB3/OB5 always use `FULL_SCREEN`. App-provided custom layout resources remain local.
- Preload and show share template resolution. If a host preloads early, or remote is refreshed after a preload, bind uses the current SDK frame without discarding the loaded ad. Already visible views remain until a subsequent bind. This is not the default splash ordering: LFO1 is scheduled after remote under both strategies.
- Shared `flow.fullscreen_skip_style`, OB `onboarding.fullscreen.skip.style`, per-step `.fullscreen.skip.style` and `ob5.skip.style` accept `CLOSE_ICON` / `TEXT`. A specific scope overrides a shared scope, then falls back to host configuration. Declared style defaults are `CLOSE_ICON`; changing style does not change Skip/auto-next timing.
- `native.presentation.cta_corner_radius_dp`: `20` dp, overridable by placement/screen. It applies when an explicit CTA background color is supplied through `colorCTA`/`NativeAdStyle.ctaBackgroundColor`; `default` color preserves the XML drawable.
- `lfo.confirm_button.image_url` / `tint_color`: `""` keeps the XML icon/color. Image-load failure uses the SDK check icon; invalid color is ignored. These style the LFO confirm control, separately from ad CTA fields.
- `lfo.languages.supported_codes`: `[]` keeps the app/SDK catalog. Unknown codes are dropped and an empty filtered result falls back to that catalog. `lfo.languages.default_code`: `""` preserves the existing choice; a new code must exist in the app catalog.

`flow.lock_portrait`, `flow.system_bars.*` and `onboarding.steps.ob1/ob2/ob4.progress_visible` remain outside the new JSON schema. Use `BehaviorConfig`, `SystemBarConfig` and `ContentStepDefinition.showsProgressIndicator` with the existing defaults.

## Fetch timing and QA

`ALTERNATE` waits for remote completion or timeout/fallback before splash ads. `SAME_TIME` may start only splash banner/interstitial before that step completes. LFO1 preload is scheduled **after remote in both strategies**; LFO `PARALLEL` means not waiting for the splash interstitial's load outcome. Strategy is selected at splash entry from current local/cache settings; a strategy value just fetched applies on a subsequent splash attempt.

Firebase shares in-flight fetches; one caller's timeout does not cancel others. Parsing/validation runs off Main, persistence on IO, and ads/UI notifications on Main. A successful fetch is reused in the process and remains subject to Firebase's fetch interval. Restart the process during Console QA; reopening a screen alone does not guarantee a new fetch. `AdConfig.refresh()` may return `false` for pinned debug ad IDs even when grouped settings were applied. See the [QA steps](firebase-integration.md#remote-notes).

## Complete default fields

Times below are milliseconds except explicitly named seconds in ad_config/legacy APIs. `schema_version=1`; `revision` is metadata. Empty objects add no scope-specific overrides, and empty template strings mean inheritance. These tables and the copy/paste files use the same defaults as the SDK assets; JSON names/enums are not translated.

### ad_behavior_config

| Field | SDK default |
| --- | --- |
| `schema_version` | `1` |
| `revision` | `0` |
| `global.ads_enabled` | `true` |
| `consent.network_timeout_ms` | `20000` |
| `diagnostics.flow_logging_enabled` | `true` |
| `diagnostics.ads_telemetry_enabled` | `true` |
| `banner.reload.allowed` | `false` |
| `banner.reload.auto_enabled` | `false` |
| `banner.reload.resume_debounce_ms` | `500` |
| `banner.presentation.type` | `"NORMAL"` |
| `banner.presentation.collapsible_gravity` | `"BOTTOM"` |
| `banner.presentation.inline_style` | `"LARGE"` |
| `banner.presentation.inline_max_height_dp` | `50` |
| `banner.presentation.fixed_size` | `"BANNER"` |
| `native.load.tier_timeout_ms` | `30000` |
| `native.cache.max_age_ms` | `3600000` |
| `native.reload.allowed` | `false` |
| `native.reload.on_ad_click` | `true` |
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
| `interstitial.load_and_show.buffer_wait_timeout_ms` | `5000` |
| `interstitial.load_and_show.allow_wait_for_auto_buffer` | `false` |
| `interstitial.presentation.next_screen_timing` | `"AFTER_AD"` |
| `interstitial.presentation.loading_enabled` | `true` |
| `interstitial.presentation.pre_show_delay_ms` | `800` |
| `interstitial.frequency.interval_ms` | `0` |
| `interstitial.frequency.max_clicks_per_24h` | `0` |
| `interstitial.auto_buffer.enabled` | `false` |
| `interstitial.auto_buffer.tick_ms` | `0` |
| `interstitial.auto_buffer.idle_tick_ms` | `30000` |
| `interstitial.auto_buffer.min_tick_ms` | `5000` |
| `interstitial.auto_buffer.preload_lead_ms` | `2000` |
| `interstitial.auto_buffer.rules` | `{}` |
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
| `splash.ads.banner.behavior` | `{}` |
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
| `lfo.native_template` | `"CTA_BOTTOM"` |
| `lfo.native1.behavior` | `{}` |
| `lfo.native2.enabled` | `true` |
| `lfo.native2.behavior` | `{}` |
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
| `lfo.confirm_dialog.native_behavior` | `{}` |
| `lfo.languages.supported_codes` | `[]` |
| `lfo.languages.default_code` | `""` |
| `lfo.exit.reuse_splash_inter` | `true` |
| `onboarding.navigation.lock_pager_swipe` | `true` |
| `onboarding.navigation.swipe_completes_last_step` | `true` |
| `onboarding.navigation.back_navigates_back` | `true` |
| `onboarding.navigation.ad_click_return_completes_step` | `true` |
| `onboarding.ads.content_template` | `"CTA_TOP"` |
| `onboarding.ads.content_native_behavior.reload.on_ad_click` | `false` |
| `onboarding.ads.fullscreen_native_behavior.reload.on_ad_click` | `false` |
| `onboarding.fullscreen.skip.enabled` | `true` |
| `onboarding.fullscreen.skip.delay_ms` | `1000` |
| `onboarding.fullscreen.skip.style` | `"CLOSE_ICON"` |
| `onboarding.fullscreen.auto_next.enabled` | `true` |
| `onboarding.fullscreen.auto_next.delay_ms` | `3000` |
| `onboarding.steps.ob1.enabled` | `true` |
| `onboarding.steps.ob1.native_template` | `""` |
| `onboarding.steps.ob1.behavior` | `{}` |
| `onboarding.steps.ob2.enabled` | `true` |
| `onboarding.steps.ob2.native_template` | `""` |
| `onboarding.steps.ob2.behavior` | `{}` |
| `onboarding.steps.ob3.enabled` | `true` |
| `onboarding.steps.ob3.behavior` | `{}` |
| `onboarding.steps.ob4.enabled` | `true` |
| `onboarding.steps.ob4.native_template` | `""` |
| `onboarding.steps.ob4.behavior` | `{}` |
| `onboarding.preload.initial_content_trigger` | `"FIRST_LANGUAGE_SELECTION"` |
| `onboarding.preload.initial_content_count` | `2` |
| `onboarding.preload.next_step_enabled` | `true` |
| `onboarding.preload.upcoming_fullscreen_enabled` | `true` |
| `onboarding.preload.ob5_on_last_step` | `true` |
| `onboarding.preload.question_on_last_step` | `true` |
| `onboarding.exit_interstitial.enabled` | `true` |
| `onboarding.exit_interstitial.preload_on_entry` | `true` |
| `onboarding.exit_interstitial.wait_timeout_ms` | `8000` |
| `onboarding.exit_interstitial.next_screen_timing` | `"UNDER_AD"` |
| `onboarding.exit_interstitial.behavior` | `{}` |
| `ob5.enabled` | `false` |
| `ob5.native.behavior.reload.on_ad_click` | `false` |
| `ob5.skip.enabled` | `true` |
| `ob5.skip.delay_ms` | `3000` |
| `ob5.skip.style` | `"CLOSE_ICON"` |
| `ob5.auto_dismiss_ms` | `15000` |
| `question.enabled` | `true` |
| `question.old_user_enabled` | `false` |
| `question.native.behavior` | `{}` |
| `question.native.template` | `"CTA_BOTTOM"` |
| `question.native.refresh_on_select` | `false` |
| `question.native.refresh_throttle_ms` | `2000` |
| `question.interstitial.behavior` | `{}` |
| `question.selection.mode` | `"MULTIPLE"` |
| `question.selection.min_count` | `1` |
