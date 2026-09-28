# AdLogic stable settings audit (source snapshot 2026-09-28)

Phạm vi của báo cáo là source hiện tại của SDK và app integration trong checkout này. Báo cáo không dùng lịch sử release để suy diễn schema cũ.

## Stable version

`git` hiện ở `36f2445` và tag `5.5.5`. Remote tags có `5.5.5` là stable cao nhất; `6.0.0-beta01` là prerelease nên không được chọn. `versions.gradle:1-4` đặt `adlogic_sdk_version = '5.5.5'`; root `build.gradle:28-37` dùng giá trị đó làm Maven version. Các module publishable lấy cùng biến cho BuildConfig (ví dụ `onboardkitorigin/build.gradle:16-20`, `paykit/build.gradle:15-19`).

## Ba JSON document thật sự được SDK xử lý

| Document | Firebase key | App override | SDK bundled/default | Parser và validator | Cache/persistence | Resolver/consumer |
|---|---|---|---|---|---|---|
| `ad_remote_config` | `ad_remote_config` (`FirebaseAdConfigSource.kt:14-35`) | `assets/ad_config_debug.json` khi debuggable, rồi `assets/ad_config.json`; app integration có cả hai (`app/src/main/assets/`) | Không có `ad_config*.json` trong SDK; parser defaults trong `AdConfigParser.kt:61-73` và `AdRemoteConfig.kt:30-50` | `AdConfigParser.parseConfig` (`AdConfigParser.kt:25-55`), field-level sparse presence (`declaredFields`) và log field invalid | Firebase activated value (`RemoteConfigClient.remoteRawString`, `RemoteConfigClient.kt:73-86`) và process `AdRemoteConfig.remotePatch`; không có SharedPreferences riêng | `AdRemoteConfig.resolveSources` (`AdRemoteConfig.kt:300-306`), `AdConfigSource.refresh` (`AdConfigSource.kt:77-141`), placement/waterfall consumers |
| `ad_behavior_config` | `ad_behavior_config` (`FirebaseAdConfigSource.kt:18-21`) | Optional app `assets/ad_behavior_config.json` at APK asset root (if host ships it) | `ads/src/main/assets/adlogic_defaults/ad_behavior_config.json:1-124`; typed defaults generated from this path (`ads/build.gradle:123-132`, `gradle/settings-defaults.gradle:1-50`) | `SettingsDocument.parse/accepts/valid` (`SettingsDocument.kt:146-255`), schema/type/enum/range validation; `AdBehavior` adds placement defaults (`AdBehavior.kt:7-40`) | `SharedPreferences("adlogic_settings_ad_behavior_config", "remote")` (`SettingsDocument.kt:48-65`); `SettingsRegistry` applies successful maps (`SettingsRegistry.kt:14-49`) | `SettingsSnapshot`/`ScopeChain` (`SettingsDocument.kt:301-383`), `BehaviorValues` (`AdBehavior.kt:45-63`) and every ad helper/onboarding behavior reader |
| `onboarding_config` | `onboarding_config` (`FirebaseAdConfigSource.kt:18-21`) | Optional app `assets/onboarding_config.json` at APK asset root; partner example supplies one at `partner-integration/examples/ads-onboarding/onboarding_config.json` | `onboardkitorigin/src/main/assets/adlogic_defaults/onboarding_config.json:1-208`; generated typed defaults (`onboardkitorigin/build.gradle:105-112`, `gradle/settings-defaults.gradle:1-50`) | Same `SettingsDocument` validator plus `OnboardingSettings.extraDefault` (`OnboardingSettings.kt:21-44`) | `SharedPreferences("adlogic_settings_onboarding_config", "remote")`; grouped update through `SettingsRegistry` | `OnboardingSettings.resolve/resolveConfig` (`OnboardingSettings.kt:190-350`), `resolveQuestion` and `behavior`, consumed by splash, LFO, onboarding, OB5, question UI |

`paywall_config` is a separate PayKit document (`paykit/src/main/res/raw/pw_default_config.json`, `paykit-firebase/FirebaseConfigSource.kt`) and is outside the three SDK ads/onboarding documents above. Legacy scalar `ob_*` Firebase parameters are also not JSON documents; `RemoteConfigSyncer` maps delivered legacy values into the onboarding document's remote tier.

The legacy Firebase strings `ob_ui_content`, `ob_ui_design_tokens`, and `ob_question_config` are JSON *fields* inside the old scalar-flag channel, not registered `SettingsDocument`s. They are parsed by `RemoteFlags`/`ObRemote` and `RemoteQuestionParser`, then consumed by the legacy UI path. They therefore do not have independent bundled JSON assets or SharedPreferences document keys; their cache is the legacy `ob_remote_cache` (`RemoteConfigSyncer.kt:32-36,131-147`). Legacy question title and CTA strings now preserve an explicitly empty JSON string; only an omitted member falls through to the compiled value (`OnboardingSettings.kt:348-358`, `ObQuestionActivity.kt:72-78`). These strings remain compatibility inputs and the exact grouped SDK document count is three.

## Common precedence contract and reset behavior

`SettingsDocument` keeps four independent maps: generated defaults, app root asset, current remote document, and delivered legacy values. `SettingsSnapshot` reads document remote fields first, then asset; the caller's host/code argument is the next fallback, then generated default (`SettingsDocument.kt:301-340`). `BehaviorValues` walks remote scopes before any asset scope (`AdBehavior.kt:45-63`), so a broad remote scope beats a narrow app asset scope. `AdRemoteConfig.resolveSources` merges SDK empty model → code config → app asset → sparse remote fields (`AdRemoteConfig.kt:300-306`); `mergeUnit` tests `declaredFields`, so `false`, `0`, `""`, `[]`, and `null` where explicitly supported remain present (`AdRemoteConfig.kt:30-91`).

Presence is separate from value. `SettingsDocument.flatten` retains empty lists/objects and marks JSON null (`SettingsDocument.kt:263-279`); invalid/null fields are dropped with a document/path/value warning while sibling fields remain (`SettingsDocument.kt:149-165`). `SettingsSnapshot` has source-specific accessors and explicit-empty-object handling (`SettingsDocument.kt:310-352`). No resolver uses Elvis/`value ?: fallback` to interpret a valid false/zero/empty value as missing.

* A successful `{}` or `{"schema_version":1}` replaces the remote map with an empty map, persists `{}`, and resolves every field to app asset → code → SDK default (`SettingsDocument.kt:106-140`). For `ad_remote_config`, `AdConfigSource` treats a successful Firebase blank/missing key as a clear and calls `AdRemoteConfig.applyRemote(AdRemoteConfig())` (`AdConfigSource.kt:95-105,131-140`).
* A fetch failure/timeout returns `null` from the source and leaves the last valid remote map in place; Firebase adapter reads only `VALUE_SOURCE_REMOTE` and preserves blank as a delivered clear (`RemoteConfigClient.kt:73-86`, `FirebaseAdConfigSource.kt:25-35`).
* `SettingsDocument.initialize` restores cached remote JSON from SharedPreferences before resolving (`SettingsDocument.kt:48-65`). `AdConfig.install` applies the source cache asynchronously (`AdConfigSource.kt:59-65,108-119`); `AdRemoteConfig.reset` clears source layers and active placement registration (`AdRemoteConfig.kt:326-345`).

## Document field inventory

### `ad_remote_config`

Every placement object supports exactly these fields (`AdConfigParser.kt:61-124`):

| Field | Parser/default | Presence/fallback rules |
|---|---|---|
| `id` | string, default `""` | empty string is valid; missing/invalid falls through |
| `ids` | string list, default `[]` | empty list is valid; malformed element invalidates the list as a unit |
| `isEnable` | boolean, default `false` | both `true` and `false` valid |
| `enable_ua_check` | boolean, default `false` | both values valid |
| `reloadIntervalSeconds` | integer `>=0`, default `null` | `0` valid; missing remains absent (the helper maps this only where explicitly wired) |
| `app_resume_load_delay_ms` | integer/legacy numeric string `0..86,400,000`, default `2,000` | `0` valid; only `open_resume` uses it |
| `colorCTA` | string, default `"default"` | empty string valid |
| `heightCTA` | integer `>=0`, default `40` | `0` valid |
| `positionCTA` | string or JSON `null` (the only nullable ad field), default `null` | explicit null clears lower position; wrong type is dropped |
| `components` | string list, default `['icon_headline','body','media','cta']` | empty list valid and preserved |

Top-level placement names are dynamic. Waterfall resolution accepts `<base>_high`, `<base>_high1` … `<base>_high9`, then `<base>` (`AdRemoteConfig.kt:145-171,347-396`); an explicit `ids` list is unlimited. Current app assets contain 52 placements in both `app/src/main/assets/ad_config.json` and `ad_config_debug.json`; release IDs and debug IDs differ, while behavior fields have the same shape. `AdRemoteConfig.initializeFromAssets` selects debug asset only for a debuggable app and `withPinnedIds` pins its IDs while allowing remote non-ID fields (`AdRemoteConfig.kt:175-257`).

### `ad_behavior_config`

The bundled asset is the schema/default map. Scalar defaults are listed below; an absent remote field falls through to the app root asset, host/code option, then the listed SDK value.

* Metadata: `schema_version=1`, `revision=0`.
* Global/diagnostics: `global.ads_enabled=true`; `consent.network_timeout_ms=10000`; `diagnostics.flow_logging_enabled=true`; `diagnostics.ads_telemetry_enabled=true`.
* Banner: `reload.allowed=true`, `reload.auto_enabled=true`, `reload.resume_debounce_ms=500`; `presentation.type=NORMAL`, `collapsible_gravity=BOTTOM`, `inline_style=LARGE`, `inline_max_height_dp=50`, `fixed_size=BANNER`.
* Native: `load.tier_timeout_ms=30000`; `cache.max_age_ms=3600000`; `reload.allowed=false`, `on_ad_click=true`, `resume_debounce_ms=500`, `min_after_bind_ms=3000`, `timer_enabled=false`, `interval_ms=15000`; `presentation.auto_shimmer=true`, `empty_visibility=GONE`, `cta_corner_radius_dp=20`; `click.action=reload`.
* Interstitial: `load.tier_timeout_ms=30000`; `load_and_show.wait_timeout_ms=8000`, `buffer_wait_timeout_ms=8000`, `allow_wait_for_auto_buffer=true`; `presentation.next_screen_timing=AFTER_AD`, `loading_enabled=true`, `pre_show_delay_ms=800`; `frequency.interval_ms=0`, `max_clicks_per_24h=0`; `cache.max_age_ms=3600000`.
* Auto-buffer: `enabled=true`, `shared_config=false`, `tick_ms=0`, `idle_tick_ms=30000`, `min_tick_ms=5000`, `preload_lead_ms=2000`; rules `inter_all.enabled=true, interval_ms=30000, tap_threshold=2` and `inter_back.enabled=true, interval_ms=30000, tap_threshold=1`.
* Rewarded: `load.tier_timeout_ms=30000`; `cache.max_age_ms=3600000`.
* App-open: `load.timeout_ms=30000`, `max_background_requests=3`, `background_retry_window_ms=120000`, `offline_recheck_ms=5000`, `failure_backoff_ms=[5000,30000,120000]`; `presentation.loading_timeout_ms=3000`, `pre_show_delay_ms=800`, `skip_after_ad_click=false`; `cache.max_age_ms=14400000`.
* `placement_overrides={}` is an object field. Dynamic placement override paths are accepted only when `AdBehavior.supportsPlacementField` says the format supports them (`AdBehavior.kt:8-31`).

Enums/ranges are enforced centrally (`SettingsDocument.kt:201-255`): click actions `auto_next|none|reload`; ad strategy `SAME_TIME|ALTERNATE`; slot format `BANNER|NATIVE`; preload triggers; timing/skip styles and positions; banner presentation types; visibility and template enums; positive timeout/range constraints and list element checks. Unknown paths, wrong types, nulls, invalid enum/range values are logged and removed independently.

### `onboarding_config`

The bundled defaults (`onboardkitorigin/src/main/assets/adlogic_defaults/onboarding_config.json`) support:

* Metadata: `schema_version=1`, `revision=0`.
* Flow: `skip_ad_only_steps_when_premium=true`, `fullscreen_skip_style=CLOSE_ICON`; there are no flow-wide ads enable fields; the global gate is `ad_behavior_config.global.ads_enabled`.
* Splash: `splash.ads.slot_format=BANNER`; empty behavior objects for banner/native/interstitial; timing `min_display_ms=3000`, `ad_budget_ms=60000`, `slot_min_visible_ms=1000`, `slot_wait_after_inter_ms=10000`, `notification_settle_ms=1000`; load `ad_strategy=ALTERNATE`, `lfo1_preload_mode=SEQUENTIAL`, `remote_fetch_timeout_ms=10000`, `consent_hook_timeout_ms=10000`, `billing_timeout_ms=5000`; permissions both true; navigation `next_screen_timing=AUTO`; native skip `delay_ms=3000`, `style=CLOSE_ICON`, `position=RIGHT`, click action `reload`.
* LFO: `native_template=CTA_BOTTOM`; native1 click `reload`; native2 `enabled=true`, click `reload`, `swap_wait_timeout_ms=8000`, `preload_trigger=LFO_SHOWN`; tap hint `enabled=true`, `delay_ms=3000`; confirm button `visible_before_selection=true`, `save_on_back=true`, `image_url=""`, `tint_color=""`; confirm dialog `enabled=true`, `show_from_tap=4`, `native_preload_trigger=DIALOG_OPEN`, click `reload`; `languages.supported_codes=[]`, `default_code=""`; `exit.reuse_splash_inter=true`.
* Onboarding navigation/ads: `lock_pager_swipe=false`, `swipe_completes_last_step=true`, `back_navigates_back=true`, `ad_click_return_completes_step=true`; content template `CTA_TOP`, content/fullscreen click actions `auto_next`; fullscreen skip `enabled=true`, `delay_ms=5000`, `style=CLOSE_ICON`; auto-next `enabled=true`, `delay_ms=15000`; per-step full1/full2 skip position `RIGHT`; preload flags `ob5_on_last_step=true`, `question_on_last_step=true`; exit interstitial `enabled=true`, `preload_on_entry=true`, `wait_timeout_ms=8000`, `next_screen_timing=UNDER_AD`, empty behavior; order `[ob1,ob2,full1,ob3,full2,ob4]`.
* OB5: `enabled=false`; native click `reload`; skip `enabled=true`, `delay_ms=3000`, `style=CLOSE_ICON`, `position=RIGHT`; `auto_dismiss_ms=15000`.
* Question: `enabled=true`, `old_user_enabled=false`; native click `reload`, `template=CTA_BOTTOM`, `refresh_on_select=false`, `refresh_throttle_ms=2000`; empty interstitial behavior; selection `mode=MULTIPLE`, `min_count=1`.

`OnboardingSettings.resolveConfig` applies all scalar fields through `SettingsSnapshot` (`OnboardingSettings.kt:206-350`). `supported_codes` is validated against the app's language catalog; a valid explicit `[]` is retained as an empty offered list, while an invalid remote list falls back to a valid asset list/catalog. `default_code=""` is an explicit clear and resolves to `null`; it is never replaced by the code default. `ObLanguageActivity` only treats a configured default as selected in SETTINGS mode; first-open starts with no selected row (`ObLanguageActivity.kt:105-124`), while device language remains a hint (`resolveHintCode`, lines 156-168). Saved user selection is kept in instance state and is separate from configured default.

## App integration call chain

`GlobalApp.onCreate` sets Firebase fetch interval (debug 0/release 3600 seconds) and installs the onboarding fetch delegate (`app/src/main/java/com/itg/template/app/GlobalApp.kt:53-80,132-145`). `AdsAppManager.initialize` loads app ad assets, installs `FirebaseAdConfigSource`, then initializes ad/consent and listeners (`app/src/main/java/com/itg/template/ads/AdsAppManager.kt:25-63`). `FirebaseAdConfigSource.fetchSettings` fetches both grouped documents and returns raw remote strings; `AdConfig.refresh` applies grouped docs before ad-unit document, all under the same successful-fetch/clear semantics (`FirebaseAdConfigSource.kt:14-35`, `AdConfigSource.kt:77-141`).

The SDK default JSON assets are namespaced under `adlogic_defaults/`; app overrides, when present, stay at the APK root (`$name.json`). This prevents an SDK default asset from being mistaken for an app override. Generated defaults and the source asset are checked by the Gradle generator (`gradle/settings-defaults.gradle:8-49`). App ad assets are packaged from `app/src/main/assets/ad_config*.json`; partner sample assets are in `partner-integration/examples/ads-onboarding/`.

Fresh packaging evidence from the final tree:

* `:app:assembleDebug`, `:app:assembleRelease`, `:app:bundleDebug`, and `:app:bundleRelease` all succeeded after a clean of the affected modules; release APK/AAB passed R8.
* APK/AAB asset listings contain the app's `assets/ad_config.json` and `assets/ad_config_debug.json`, plus `assets/adlogic_defaults/ad_behavior_config.json` and `assets/adlogic_defaults/onboarding_config.json` from the SDK libraries. The SDK no longer contributes root `ad_behavior_config.json` or `onboarding_config.json`, so an app root file is unambiguously an override.
* Source-to-artifact SHA-256 matches: `ad_config.json` `7dd1b3…a731`, `ad_config_debug.json` `222d2c…bfcb`, bundled behavior `c02d0e…1f69`, bundled onboarding `6f2c67…5857` in both APK and AAB (AAB entries are under `base/assets/`).
* The debug/release parity matrix verifies only ad IDs differ; behavior fields are identical.

## Files/modules changed

* Resolver/parser: `ads/src/main/java/com/ads/module/config/{AdConfigParser,AdRemoteConfig,AdUnitConfigStyle,AdConfigSource}.kt`, `ads/src/main/java/com/ads/module/config/settings/{SettingsDocument,AdBehavior}.kt`.
* Consumers: banner/native helpers, app-open/interstitial behavior, `onboardkitorigin` splash/LFO/onboarding/question UI and `OnboardingSettings`.
* Firebase/integration: `suite-firebase/FirebaseAdConfigSource.kt`, the `RemoteConfigClient` call path, `app/AdsAppManager`, `MainActivity` code patch path, and debug/release assets.
* Defaults/version/build: namespaced SDK assets, generated-default Gradle task, `versions.gradle`, module BuildConfig version fields, and Trackkit metadata.
* Tests: data-driven ad parser/resolver/settings matrices, generated-default checks, onboarding language/persistence/timing checks, Firebase adapter keys, debug/release parity, and real-asset packaging validation.

## Fields and call sites that previously violated the contract

The audit found these noncompliant paths and moved them to the centralized resolver; the current matrix has no remaining grouped-document field exception:

| Area | Previously noncompliant field/consumer | Root cause | Current fix |
|---|---|---|---|
| `ad_remote_config` | All placement fields, especially `isEnable`, `enable_ua_check`, `reloadIntervalSeconds`, `colorCTA`, `heightCTA`, `positionCTA`, `components`, `ids`, and `app_resume_load_delay_ms` | Whole-object replacement and `takeIf { it > 0 }` erased field presence | Sparse `declaredFields` merge and field-level parser; false/0/empty/list values survive |
| `ad_remote_config` | Banner `reloadIntervalSeconds=0`; native style blank CTA position | Consumer treated zero/blank as missing | Presence-aware banner getter and nullable style mapping |
| `ad_behavior_config` | `native.presentation.empty_visibility`, app-resume click policy, placement behavior scopes | Direct remote-only reads and enum constructors bypassed asset/code fallback | `SettingsSnapshot`/`BehaviorValues` source-first resolver with safe enum parsing |
| `onboarding_config` | Splash `next_screen_timing`, exit wait timeout, skip position, preload/navigation behavior | Hook/host value was consulted before remote or bundled default | `OnboardingSettings.behavior` and remote-first splash resolver |
| `onboarding_config` | `lfo.languages.supported_codes`, `lfo.languages.default_code`, tap hint and confirm flow | Empty list/default was converted to catalog/default selection; device hint was conflated with selection | Explicit empty preservation; LFO1 starts unselected; saved selection is separate |
| Legacy JSON compatibility | Question title/CTA | `takeIf { it.isNotBlank() }` treated explicit empty as missing | DTO presence is preserved; only omitted member falls back |

## Findings resolved in this run

1. Schema metadata with a wrong type/value is logged and dropped as one invalid field; valid siblings continue. Malformed JSON still retains the last valid remote snapshot (`SettingsDocument.kt:146-165, 201-204`).
2. Empty lists/objects remain present wherever the schema permits them, including `supported_codes=[]`, `failure_backoff_ms=[]`, ad `components=[]`, and ad `ids=[]`; wrong element types drop only that field.
3. Scope resolution tests cover broad remote versus narrow asset and the reverse; source rank is evaluated before scope rank (`BehaviorValues`, `ScopeChain`, `SettingsFieldMatrixTest`).
4. `positionCTA=null` is the only nullable ad field and style consumers map an explicit clear to a nullable presentation value; no consumer uses Elvis fallback for valid false/zero/empty values.
5. `ObLanguageActivity` preserves an explicit empty language list, starts LFO1 with no selected row, keeps device language as a hint, and never turns an ad return into a selection without a saved user tap.
6. `RemoteConfigUtils` is no longer in the ad resolver call chain; `AdsAppManager` installs the centralized `FirebaseAdConfigSource`/`AdConfig` path. Its legacy helper remains only as unused compatibility code.
7. Firebase cache lookup restores the persisted last valid ad document when the activated value is unavailable after a failed fetch/restart, while a successful missing parameter clears that persisted snapshot.

The earlier source issue that discarded an app asset when its flattened values equaled bundled defaults has been removed: `SettingsDocument.localOverrides` now returns the parsed map directly (`SettingsDocument.kt:85-88`), and SDK copies are namespaced so root app assets remain distinguishable.

Validation commands and counts are recorded in the handoff: full `:ads:testDebugUnitTest` passes; onboarding resolver/UI/timing suites pass after clean (140 targeted tests), the remote-timing and two native-binding tests pass when run individually, and Firebase adapter/remote-client tests pass (8). A full onboarding run reached 544 tests; its one stale entry-timing expectation was updated to the required remote-first contract, while two native-binding cases hit the JVM worker's memory ceiling in the large combined run and pass in isolated runs. The final app builds provide the APK/AAB evidence above.
