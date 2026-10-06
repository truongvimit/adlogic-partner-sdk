# Cấu hình ads và hành vi onboarding

**OB catalog:** `ob1..ob4` → `native_ob1..4`; `full1/full2` → `native_full1/2`. Default: `ob1, full1, ob2, full2, ob3, ob4`. All eligible OB natives preload on language selection. Remote `onboarding.order` selects/reorders app-declared steps. [Configuration / Hướng dẫn chi tiết](onboarding-flow.vi.md). `native_fs` remains the separate splash native.

`onboarding.order` là danh sách duy nhất chọn và sắp xếp màn trong JSON; bỏ ID để bỏ màn. Không cần khai báo `steps` nếu không có tùy chỉnh riêng. `steps.<id>.enabled` đã bỏ và bị bỏ qua. `order` remote ưu tiên hơn các cờ cũ `ob_enable_step_ob1..4` mà backend đã gửi, và các cờ đó ưu tiên hơn `order` trong asset app. Màn app khai báo `enabled = false` vẫn ẩn cho tới khi `order` remote liệt kê nó hoặc `ob_enable_step_obN = true` đã gửi bật nó; `order` trong asset app vẫn giữ màn đó ẩn. Remote không thêm được màn app chưa khai báo. `steps.<id>` chỉ dành cho behavior và fullscreen tùy chọn. Bật/tắt ads từng vị trí bằng `ad_config.<placement>.isEnable`: content vẫn hiện khi ads tắt, màn fullscreen không có ads sẽ được bỏ qua.

[English](remote-settings.md) · [Tiếng Việt](remote-settings.vi.md) · [हिन्दी](remote-settings.hi.md)

SDK giữ nguyên Firebase `ad_remote_config`, assets `ad_config.json` / `ad_config_debug.json`. Hai parameter mới là **String chứa object JSON**. Xem [các bước publish trên Firebase](firebase-integration.vi.md#remote-json) và [tạo hai file local custom default](firebase-integration.vi.md#local-defaults). Các [file mẫu](examples/ads-onboarding/) khớp default SDK.

Dùng **version SDK mới nhất** trên [JitPack](https://jitpack.io/#truongvimit/adlogic-partner-sdk) cho mọi module. Chỉ thêm key Firebase không nâng cấp SDK cũ đã tích hợp trong app.

| Parameter | File mặc định trong SDK |
|---|---|
| `ad_behavior_config` | `ads/src/main/assets/adlogic_defaults/ad_behavior_config.json` |
| `onboarding_config` | `onboardkitorigin/src/main/assets/adlogic_defaults/onboarding_config.json` |

Cụm Privacy → Goal tùy chọn là phần cuối onboarding, sau interstitial cuối pager. Bật `privacy_goals_screen.enabled` và cung cấp lựa chọn goal; xem [Privacy → Goal](privacy-goals-screen.vi.md).

Welcome Back mặc định ẩn; bật `welcome_back.enabled` để user cũ mở từ launcher vào màn này sau splash. Xem [Welcome Back](welcome-back-screen.vi.md).

## Mỗi giá trị có một nơi quản lý

- **ad_config:** `id`, `ids`, `isEnable`, `enable_ua_check`, `reloadIntervalSeconds`, `colorCTA`, `colorBackground`, `heightCTA`, `templateId`, `components`, `click_action` của native, `open_resume.app_resume_load_delay_ms`. Hai JSON mới không khai báo lại các field này, mapping ad unit hoặc công tắc từng ad unit.
- **ad_behavior_config:** timeout/cache, policy reload, frequency/AutoBuffer, consent timeout, telemetry, bo góc CTA native và hành vi app-open. Banner type/size là preset định dạng quảng cáo của SDK; không chứa resource/layout của app.
- **onboarding_config:** bật/tắt bước của luồng, skip/X delay, auto-next, swipe/back, chiến lược splash, thời điểm preload LFO/OB và hành vi exit; kiểu nút X/Skip, hình/màu nút xác nhận LFO và lựa chọn ngôn ngữ trong catalog của app. Bật bước không bật lại placement đang `isEnable=false` trong ad_config.
- **Code/resource của app:** reference `R.layout`, `R.drawable`, `R.string`, layout custom của trang, catalog/resource ngôn ngữ, progress indicator, system bars/orientation và Activity exclusions. Các preset trình bày quảng cáo có sẵn trong SDK vẫn được remote điều khiển; không cần truyền resource ID qua JSON.

`app_open.presentation.excluded_hosts`, `app_open.enabled`, `app_open.load.background_delay_ms`, `banner.reload.interval_ms`, mọi `placement`/`native_placement`, các switch ad unit và nhóm payload nội dung app `ui` đã được bỏ khỏi schema mới. Payload/cached payload còn các field này được bỏ qua; không ghi đè nơi quản lý chính.

## Partner cấu hình một lần

```kotlin
OnboardingSdk.install(application) {
    adProvider = ERainAdProvider()
}
OnboardingSdk.configure(onboardKitConfig {
    splash = SplashConfig(logoRes = R.drawable.logo)
    steps(
        ContentStepDefinition(StepId.OB1, titleRes = R.string.welcome, imageRes = R.drawable.welcome),
        ContentStepDefinition(StepId.OB2, titleRes = R.string.features),
        AdFullScreenStepDefinition(StepId.FULL1),
        ContentStepDefinition(StepId.OB4, titleRes = R.string.get_started),
    )
    // Cũng là mặc định của builder. Không cần chép từng ad ID hoặc timing vào code.
    ads = AdsConfig.fromAdConfig()
}.getOrThrow())
```

`fromAdConfig()` giữ association bằng key, không chụp một bản ad ID tại lúc Application khởi động. Khi ad_config đổi sau splash, SDK resolve lại để lập danh sách trang, preload và hiển thị. Không cần configure lại từ callback remote. Một bước fullscreen không có ad unit hợp lệ bị loại trước khi mở pager.

| Slot | Key chuẩn trong ad_config |
|---|---|
| Splash banner/interstitial | `banner_splash` / `inter_splash` |
| Splash native (slot dưới, khi `splash.ads.slot_format` = `NATIVE`) | `native_splash` |
| Splash người dùng cũ | `<key splash interstitial>_o` (`inter_splash_o`); không khai báo `_o` thì người dùng cũ dùng unit của `inter_splash` |
| Splash mở từ notification / widget / uninstall | `inter_noti` / `inter_widget` / `inter_uninstall`; key thiếu hoặc bị tắt thì chuyển về key của tệp user |
| Privacy / Goal initial; ALT | `native_select`; `native_select_alt` (each has its own `_high` tier) |
| LFO1 / LFO2 / dialog | `native_lang` / `native_lang_alt` / `native_popup_lang` |
| Content OB1 / OB2 / OB3 / OB4 | `native_ob1` / `native_ob2` / `native_ob3` / `native_ob4` |
| Fullscreen Full1 / Full2 | `native_full1` / `native_full2` |
| OB5 | `native_onboarding_fullscreen_1_4` |
| Welcome Back đầu; ALT | `native_welcome1`; `native_welcome2` (mỗi key có tier `_high` riêng) |
| Exit interstitial | `inter_after_ob3` |
| App resume | `open_resume`; `ad_config.json` hoặc backend khai báo kèm ID thì bật app-open mà không cần seed `idAdResume`. Gọi `AppOpenManager.getInstance().disableAppResume()` để giữ app-open tắt |

`inter_splash` là công tắc tổng cho user mới, `inter_splash_o` (hoặc `inter_splash` khi chưa khai báo `_o`) cho user cũ: `isEnable: false` ở đó tắt mọi inter splash của tệp user đó, kể cả entry. Entry dùng key riêng khi key được khai báo và đang bật, nếu không thì dùng key của tệp user. Load và show đều gate theo cùng key. Key Firebase cũ `ob_ads_splash_inter_enabled=false` tắt mọi inter splash cho tất cả user.

### Slot dưới màn splash

Splash có đúng một slot quảng cáo dưới thanh loading, và `splash.ads.slot_format` chọn format nào
lấp vào:

| Giá trị | Placement | Key trong ad_config |
| --- | --- | --- |
| `BANNER` (mặc định) | `AdPlacement.SplashBanner` | `banner_splash` |
| `NATIVE` | `AdPlacement.SplashInlineNative` | `native_splash` |

Native cần đủ hai vế: `slot_format` đặt `NATIVE` **và** một entry `native_splash` dùng được
trong ad_config. Thiếu một trong hai thì slot chỉ đơn giản là rỗng.

Mỗi lượt mở chỉ request một format. Slot tự load và render độc lập: interstitial ready không chờ slot load, impression hoặc thời gian hiển thị tối thiểu. `slot_min_visible_ms` và `slot_wait_after_inter_ms` không giữ interstitial. Consent, premium, focus, đóng notification, minimum display của splash và gate update/paywall vẫn có hiệu lực. `ob_ads_splash_banner_enabled` điều khiển cả hai format.

Native dùng khung media-left trừ khi `native_splash.templateId` chọn template khác; media-left
luôn hiện đúng như layout vẽ sẵn nên `components` không có gì để tác động — `colorCTA` và `heightCTA` vẫn áp dụng. `AdPlacement.SplashInlineNative` khác
`AdPlacement.SplashNative` — cái sau vẫn là native full-screen tuỳ chọn (`native_fs`) hiện sau
inter splash. Trong code cờ này là `io.onboardkit.config.SplashAdSlotFormat`, còn ad unit resolve
vào `AdsConfig.splashInlineNative`.

App dùng key khác chỉ khai báo association đó một lần trong code:

```kotlin
ads = AdsConfig.fromAdConfig(mapOf(
    AdPlacement.Language1 to "my_language_native",
    AdPlacement.StepNative(StepId("custom")) to "my_content_native",
))
```

Đổi ID, tiers, switch, màu/chiều cao/vị trí CTA, components và hành động click native (`click_action`) vẫn thực hiện ở ad_config. Chọn preset template SDK và bo góc CTA dùng hai JSON mới như bảng dưới. Association này cũng dùng để tìm `ad_behavior_config.placement_overrides.<key>`; LFO1 mặc định tìm `native_lang`, không nhầm với key telemetry/buffer nội bộ `language1`. API `AdsConfig(...)` truyền raw ID vẫn được hỗ trợ, nhưng key nào trong bảng trên mà `ad_remote_config` của backend khai báo sẽ ưu tiên hơn unit ghi trong code, kể cả `stepNatives`, `splashInterstitialOldUser` và `splashInterstitialOverride()`; unit trong code chỉ áp cho placement mà remote không nhắc tới. `ad_config.json` của chính app chỉ ghi đè code ở các key liên kết qua `AdsConfig.fromAdConfig`. SDK không đoán association từ các ID có thể trùng nhau.

## Local JSON và fallback

**App không cần tạo file nếu dùng default SDK.** Khi cần custom, partner có thể đặt `app/src/main/assets/ad_behavior_config.json` / `onboarding_config.json` ở asset root. SDK giữ bản bundled dưới `adlogic_defaults/`, còn file root của app được loader nhận là app tier riêng, kể cả khi nội dung giống bundled default. Có thể dùng JSON thưa, chỉ chứa field cần đổi; field còn thiếu lấy SDK defaults. Một custom asset được xem là assignment local tường minh cho các field hợp lệ có mặt, kể cả false/0. Bản sao đầy đủ đặt tại asset root của app cũng ghi đè constructor/setter của host, kể cả khi giá trị giống default SDK.

Ví dụ local hoặc remote chỉ đổi swipe và thời gian X:

```json
{
  "schema_version": 1,
  "onboarding": {
    "navigation": { "lock_pager_swipe": false },
    "fullscreen": { "skip": { "delay_ms": 1500 } }
  }
}
```

- Thứ tự ưu tiên cho mọi setting: field hợp lệ của `onboarding_config` / `ad_behavior_config` remote > key `ob_*` cũ mà backend đã gửi > custom local asset > cấu hình Kotlin/hook của host > SDK defaults. Remote ở bất kỳ scope nào ưu tiên hơn asset app ở bất kỳ scope nào. Field remote hợp lệ đã cache giữ thứ tự này lúc khởi động; key backend chưa từng gửi không được tính. Các field hành vi không yêu cầu partner set lại bằng code.
- Thiếu parameter/field sau fetch thành công: xóa assignment remote cũ tương ứng, trở về nguồn kế tiếp bên dưới. Null, sai type/enum/range bị bỏ qua theo field và được log với logcat tag `AdLogicSettings`; false và 0 hợp lệ được giữ.
- Fetch lỗi/timeout, JSON hỏng hoặc schema chưa hỗ trợ: giữ snapshot hợp lệ gần nhất; document bị từ chối được log dưới `AdLogicSettings`. Lần đầu chưa có remote dùng local/default. Cache lưu SharedPreferences, khôi phục ở lần chạy sau; lỗi cache không làm mất default trong bộ nhớ. Fetch lỗi không ép local ghi đè remote cache hợp lệ. Fetch về sau `splash.load.remote_fetch_timeout_ms` của splash vẫn được áp dụng cho phần còn lại của phiên. Muốn bỏ override, publish `{}` hoặc `{"schema_version":1}` rồi fetch thành công; không dùng String rỗng. Đổi asset local cần build và khởi động lại process.
- `AdConfig.install` áp ngay `ad_remote_config` mà backend gửi gần nhất (giá trị Firebase activate gần nhất), nên fetch chậm hoặc lỗi vẫn chạy trên document đó thay vì asset. Fetch settings lỗi không chặn `ad_remote_config` được áp dụng.
- Defaults SDK sinh từ chính asset khi build, dùng được trước Context; không có bản Kotlin/XML cần đồng bộ cho các field thuộc hai JSON. Build từ chối null/sai schema.
- `ad_behavior_config.global.ads_enabled` là cờ global duy nhất áp dụng cho ads toàn SDK. Từng vị trí LFO/onboarding vẫn dùng `isEnable` và placement flag riêng; đã bỏ các cờ bật/tắt toàn flow. Consent, premium và lifecycle vẫn áp dụng. Đặt `global.ads_enabled=false` để tắt toàn bộ ads, hoặc tắt các placement tương ứng.
- Key `ob_*` cũ tiếp tục tương thích. Key đã gửi đặt giá trị theo cả hai chiều, xếp dưới hai document nhóm và trên asset app/host; `ob_splash_min_display_ms <= 0` giữ giá trị local. Custom UI legacy qua `ob_ui_content`/`ob_ui_design_tokens` vẫn theo API cũ, không được nhân bản sang hai JSON mới. Các API remote nội dung cũ vẫn sử dụng được; reference layout/resource và nội dung mặc định do app khai báo. Không nhầm nhóm nội dung trang này với template/style của quảng cáo trong hai JSON mới.
- Mọi build đọc cấu hình từ `ad_config.json`. Build debuggable đọc thêm `ad_config_debug.json`, file này chỉ có một `"id"` cho mỗi key all-price (`native_reward`, không cần `native_reward_high`): ID test thay mọi ID của vị trí, các floor `_high*` không request, nên debug không chạy waterfall. Field khác trong file debug bị bỏ qua kèm log `WARN`; key đang bật mà thiếu ID test thì không có ad. `ad_remote_config` remote áp mọi field trừ ID; `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` nhận cả ID remote khai báo. Hai JSON hành vi áp dụng như release.
- Splash chạy consent, fetch remote và billing song song. Slot banner/native và interstitial được request ngay khi consent kết thúc, dùng cấu hình và entitlement hiện có; không đợi remote hoặc billing. Remote đã cache hoặc đã về vẫn ưu tiên hơn asset. Job refresh thuộc SDK, tiếp tục sau khi splash đóng, với thời gian chờ nền ít nhất 60 giây. Các lần đọc sau nhận giá trị mới; request, timer và quyết định chuyển màn đã chốt không chạy lại. `SAME_TIME` và `ALTERNATE` cùng dùng thứ tự này. `onRemoteFetched()` chỉ chạy khi splash còn sống; tích hợp cần sống cùng process dùng `SettingsRegistry.addFetchListener`.

Sau refresh, cảnh báo OnboardKit liệt kê key placement app đã map nhưng remote không khai báo. Các key này giữ giá trị app; sửa một key khác tên trong Firebase không tác động đến chúng.

## Scope của behavior

Slot behavior > nhóm content/fullscreen OB > placement override > format override > local/default. Thứ tự này áp dụng trong cùng một nguồn: giá trị remote ở bất kỳ scope nào ưu tiên hơn giá trị asset app ở bất kỳ scope nào. Các object `behavior` trống là scope tùy chọn cho hành vi chưa có trong ad_config, không chứa ad IDs/switch/UI resources.

| Format trong placement_overrides | Field được hỗ trợ |
|---|---|
| banner | reload.allowed, reload.auto_enabled, reload.resume_debounce_ms, presentation.* |
| native | load.tier_timeout_ms, reload.*, presentation.auto_shimmer, presentation.empty_visibility, presentation.cta_corner_radius_dp |
| interstitial | load.tier_timeout_ms, load_and_show.wait_timeout_ms, load_and_show.buffer_wait_timeout_ms, presentation.loading_enabled, cache.max_age_ms |
| rewarded | load.tier_timeout_ms, cache.max_age_ms |

App/SDK chủ động gọi preload native. Preload replacement dùng `setEnablePreload` và `preloadAfterShow`; remote không hỗ trợ `preload.enabled` / `preload.after_show`. Lịch preload onboarding dùng `lfo1_preload_mode`, `preload_trigger` và `onboarding.preload.*`. Timeout mỗi tier dùng `native.load.tier_timeout_ms` và `interstitial.load.tier_timeout_ms` (mặc định 30000 ms).

Slot inter splash được load trước rồi show từ buffer, nên `behavior` của nó nhận `load.tier_timeout_ms`; mọi wait `load_and_show.*` bị bỏ qua vì slot được load trước rồi mới show; field `load_and_show.*` ở đó bị bỏ qua. Chỉ inter cuối OB chờ fill: `onboarding.exit_interstitial.wait_timeout_ms` ưu tiên hơn `placement_overrides` và `interstitial.load_and_show.wait_timeout_ms` ở scope format. Frequency, next-screen timing, pre-show delay, app-open và native cache TTL ở scope format chung. Per-step fullscreen cho phép `onboarding.steps.<id>.fullscreen.skip.{enabled,delay_ms,style,position}` và `.auto_next.{enabled,delay_ms}`; riêng `position` chỉ tồn tại ở mức per-step, không có scope `onboarding.fullscreen` hay `flow` ở trên.

Banner reload cadence lấy `ad_config.<key>.reloadIntervalSeconds` nếu là số dương; thiếu/sai dùng giá trị host (mặc định SDK 15000ms). Khai báo interval không tự bật timer. App-open delay chỉ lấy `open_resume.app_resume_load_delay_ms`, mặc định 2000ms. Click vào ad onboarding bỏ qua lần app-open kế tiếp, trừ khi remote đặt `app_open.presentation.skip_after_ad_click` là `false`. Không thêm banner tier timeout khi loader chưa có timer đó.

Object behavior rỗng không che leaf ở scope rộng hơn hoặc nguồn bên dưới. `presentation.loading_enabled` dùng behavior đã chốt theo màn/placement cho cả `show()` và `loadAndShow()`, kể cả ad đã có sẵn. Banner đọc lại `enable_ua_check` của placement sau refresh; setter `forceUaCheck` tường minh của host ưu tiên hơn. Native vẫn áp dụng `presentation.cta_corner_radius_dp` khi helper không có style tường minh.

## Hành động khi click native

Hành động click của native chỉ có một nguồn: `click_action` trên key của placement trong `ad_config`, nhận `auto_next`, `none` hoặc `reload`. Giống `enable_ua_check` và các field CTA, SDK chỉ đọc field này ở base key của placement (`native_ob1`, `native_lang`, `native_home`…); giá trị đặt trên key floor `_high`, `_high1`…`_high9` bị bỏ qua. `ad_behavior_config` và `onboarding_config` không có field hành động click. SDK chốt đúng một hành động ở callback click/open đầu tiên, giữ nguyên đến khi quay về kể cả remote thay đổi giữa chừng.

- `reload`: request ad thay thế ngay lúc click/open, không đợi resume và không có delay cố định. Khi quay về, dùng ad đã tải hoặc chờ đúng request đang chạy. Resume app thông thường không kích hoạt click reload.
  Trong lúc chờ vẫn hiển thị ad cũ, không hiện shimmer. Chỉ thay khi ad mới bind thành công; tải lỗi giữ ad cũ và khung quảng cáo. Shimmer chỉ dùng lúc tải ban đầu chưa có ad.
- `auto_next`: quay về thì chuyển trang onboarding đang hiển thị, không tải ad thay thế. Ở LFO2: tự confirm ngôn ngữ đã chọn. Ở LFO1: lặp lại cú tap của user vào ngôn ngữ họ đã chọn, như khi tap lại hàng đó; user chưa tap hàng nào thì không làm gì. Ở native khác, action này chỉ bỏ ad thay thế giống `none`; điều hướng vẫn do app quyết định.
- `none`: giữ ad và trang hiện tại; không reload theo click, không tự chuyển trang.

Mặc định khi key không khai `click_action`:

| Native | Mặc định |
|---|---|
| Trang pager onboarding: content `ob1..ob4` và content step app tự khai, fullscreen `full1/full2` | `auto_next` |
| LFO1, LFO2, dialog xác nhận LFO, Privacy/Goal, Welcome Back, OB5, native splash (`native_splash`, `native_fs`) và native ở màn app | `reload` |

Ad của trang pager không reload: `reload` trên các key đó được xử lý như `none`. LFO2 không có unit riêng thì dùng key của LFO1, kéo theo hành động của LFO1, khi key đó đã được gắn: `AdsConfig.fromAdConfig` (mặc định của builder) gắn nó, backend khai key đó cũng gắn. `AdsConfig(...)` tự dựng mà không có cả hai thì LFO2 đọc `native_lang_alt`. [ad_config mẫu](examples/ads-onboarding/ad_config.json) khai tường minh đúng các giá trị này trên mọi base key native.

`click_action` theo thứ tự ưu tiên của ad_config, gộp theo từng field: `ad_remote_config` của backend > `ad_config.json` của app > mặc định trong code. Key remote không khai `click_action` giữ giá trị của asset; giá trị sai bị log và bỏ qua. `ad_config_debug.json` chỉ chứa ID, nên mọi build lấy `click_action` từ `ad_config.json` và remote. Với native ở màn app, mặc định trong code là `reload`; `NativeAdHelper.setReloadOnAdClick(false)` đổi thành `none`, và `click_action` vẫn thắng. Timer/resume refresh và timeout tự chuyển trang fullscreen là các cài đặt riêng.

Ví dụ trong `ad_config`: OB1 giữ nguyên trang sau khi click ad, LFO2 tự confirm ngôn ngữ khi quay về. Key floor `_high` không mang `click_action`:

```json
{
  "native_ob1_high": { "id": "ca-app-pub-xxx/ob1_high", "isEnable": true },
  "native_ob1": { "id": "ca-app-pub-xxx/ob1", "isEnable": true, "click_action": "none" },
  "native_lang_alt": { "id": "ca-app-pub-xxx/lfo2", "isEnable": true, "click_action": "auto_next" }
}
```

## Template và CTA để UA/MO thử nghiệm

- `ad_remote_config.<placement>.templateId` (số) chọn khung: `1` khung LFO, `2` card media-left 4:3, `3` card 1.91:1. Không có, `null` hoặc số lạ thì giữ mặc định của slot — `1` cho LFO1/2, Welcome Back và OB content, `2` cho Privacy/Goal và splash inline. Remote > asset app > code như mọi field.
- `components` là thứ duy nhất sắp thứ tự native. Template `1` và `3` theo nó; không có thì thứ tự mặc định đặt CTA ở dưới. Template `1`: `["icon_headline","media","cta"]` (mặc định) hoặc `["cta","media","icon_headline"]` để đưa CTA lên trên.
- `positionCTA` đã bỏ; payload vẫn gửi field này sẽ bị bỏ qua. Các field `lfo.native_template`, `onboarding.ads.content_template` và `onboarding.steps.<id>.native_template` cũng đã bỏ; payload cũ chứa chúng sẽ bị bỏ qua.
- Template 3 là một layout, bốn cách sắp xếp của thiết kế lấy từ thứ tự `components`: A `["media","icon_headline","body","cta"]`, B `["cta","icon_headline","body","media"]`, C `["icon_headline","body","media","cta"]` (thứ tự mặc định), D `["cta","media","icon_headline","body"]`. Body nằm trong header nên bỏ `body` chỉ làm ẩn nó.
- Template `2`, popup LFO (`DIALOG`) và Full1/Full2/OB5/native_fs (`FULL_SCREEN`) luôn hiện đúng như layout vẽ sẵn và bỏ qua `components`; popup và native fullscreen còn bỏ qua cả `templateId`. `colorCTA` áp dụng cho CTA và badge Ad ở mọi khung; `colorBackground` đổi màu nền card ở mọi khung, giữ nguyên hình dạng, và mặc định không khai.
- Preload và show dùng chung bộ chọn template. Nếu host chủ động preload sớm hoặc remote được refresh sau preload, native được inflate theo template hiện hành tại bind, tái sử dụng ad đã tải. LFO1 được lên lịch theo preload mode hiện có, không chờ remote. Một ad đang hiển thị giữ view hiện tại đến lần bind tiếp theo.
- `flow.fullscreen_skip_style`, `onboarding.fullscreen.skip.style`, `onboarding.steps.<id>.fullscreen.skip.style`, `ob5.skip.style` nhận `CLOSE_ICON`/`TEXT`. Trong cùng một nguồn, scope cụ thể ưu tiên scope chung (remote ở bất kỳ scope nào ưu tiên hơn asset app), rồi tới cấu hình host; thời gian X/Skip và auto-next không đổi khi chỉ đổi style. Default các style khai báo sẵn là `CLOSE_ICON`.
- Phía đặt X/Skip khai theo từng trang native fullscreen, không có scope chung ở trên: `onboarding.steps.<id>.fullscreen.skip.position` cho mỗi trang fullscreen trong OB, `ob5.skip.position` cho OB5 standalone, `splash.native.skip.position` cho native_fs giữa inter splash và LFO. Cả ba nhận `RIGHT`/`LEFT`, default `RIGHT` — đúng phía X vẫn nằm từ trước; JSON gốc khai sẵn `full1` và `full2`, id step khác do app khai vẫn nhận ở cùng path đó. Các format khác không có cờ này: interstitial, app-open, banner và native inline đều không có nút X này. Hai phía đối xứng tuyệt đối: cùng khoảng cách tới mép và cùng margin trên, chỉ đổi phía chứ không đổi kích thước, style hay thời gian. Locale RTL vẫn lật như hiện tại: `RIGHT` theo mép cuối dòng chữ, `LEFT` theo mép đầu.
- `native.presentation.cta_corner_radius_dp` mặc định `20` dp, có thể override theo placement hoặc scope native từng màn. Áp dụng khi CTA có màu nền tường minh từ `colorCTA`/`NativeAdStyle.ctaBackgroundColor`; màu `default` giữ drawable XML như trước.
- `lfo.confirm_button.style`: `CHECK_ICON` (mặc định) hiển thị tick; `TEXT` hiển thị “Done”. Cả hai lấy màu từ `onboarding.primary_color`, mờ 50% trước khi chọn ngôn ngữ và sáng đầy đủ sau khi chọn. `visible_before_selection=false` ẩn cả hai kiểu trước khi chọn. `image_url` chỉ áp dụng với `CHECK_ICON`.
- `lfo.confirm_button.image_url`: mặc định `""`, giữ icon XML. URL ảnh lỗi dùng icon check của SDK. Màu nút tick luôn lấy từ `onboarding.primary_color`; CTA quảng cáo vẫn dùng field của ad_config.
- `lfo.languages.supported_codes` mặc định `[]`: giữ catalog app/SDK; mã không có trong catalog bị loại, kết quả rỗng trở về catalog. `lfo.languages.default_code` mặc định `""` được gửi tường minh: xóa ngôn ngữ chọn sẵn trong cấu hình (không xóa lựa chọn user đã lưu); mã phải nằm trong danh sách được hiển thị (`supported_codes` đã lọc, nếu không có thì catalog). `LanguageConfig.defaultCode` của host nằm ngoài `supported_codes` sẽ không được chọn sẵn.

Ví dụ chỉnh phía nút X cho từng trang native fullscreen. JSON gốc khai sẵn `full1` và `full2` — hai trang fullscreen tiêu chuẩn; app khai id riêng thì thêm id đó y hệt:

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

Các field chỉ thuộc app đã loại khỏi nhóm UI 26 field là `flow.lock_portrait`, ba field `flow.system_bars.*` và `onboarding.steps.ob1/ob2/ob4.progress_visible`. Chúng tiếp tục dùng `BehaviorConfig`, `SystemBarConfig`, `ContentStepDefinition.showsProgressIndicator` với default cũ. Payload nội dung `ui.*` không nhân bản sang JSON mới; nội dung UI remote cũ vẫn qua `ob_ui_content`, `ob_ui_design_tokens` và toggle `ob_enable_ui_content`.

## Fetch và publish

Firebase `fetchAndActivate().await()` chia sẻ tác vụ đang chạy qua delegate suite/example; timeout/cancellation của caller không hủy caller khác. Parse/validate trên Default, persist trên IO, publish snapshot trước khi flow dùng cấu hình; các notification chạm ads/UI chạy Main. Preset template/Skip và style ads đọc snapshot tại điểm sử dụng; payload nội dung trang qua API remote UI cũ vẫn có luồng publish riêng.

Fetch thành công được dùng lại trong process và vẫn chịu minimum fetch interval của Firebase, mặc định 12 giờ. SDK không tự đặt interval này; muốn thay đổi trên Console tới được lần mở sau, đặt `minimumFetchIntervalInSeconds` trong app (ví dụ `0` ở debug, `3600` ở release). `AdConfig.refresh()` trả về việc có áp dụng một document `ad_remote_config` hay không; giá trị này không phản ánh hai settings nhóm. Xem [các bước QA](firebase-integration.vi.md#remote-notes).

## Toàn bộ defaults

Thời gian dùng milliseconds, trừ `reloadIntervalSeconds` trong ad_config và API legacy hậu tố Sec. schema_version=1, revision chỉ là metadata. Object rỗng nghĩa là không thêm override cho scope đó.

### ad_behavior_config

| Field | SDK default | Ghi chú |
|---|---|---|
| `schema_version` | `1` | Phiên bản schema đang hỗ trợ: 1. |
| `revision` | `0` | Metadata được lưu cùng document; không phải gate. |
| `global.ads_enabled` | `true` | Master mới toàn SDK; true vẫn cần host/consent/premium/slot cho phép. |
| `consent.network_timeout_ms` | `10000` | Timeout network UMP, áp lần request consent sau; không đóng form đang đọc. |
| `diagnostics.flow_logging_enabled` | `true` | OB_FLOW log; không đổi debug/test IDs. |
| `diagnostics.ads_telemetry_enabled` | `true` | false tắt nguồn telemetry ads; true vẫn giữ ownership chống report trùng. |
| `banner.reload.allowed` | `true` | Map canReloadAds; đây là policy có thể override, runtime flagUserEnableReload vẫn veto. |
| `banner.reload.auto_enabled` | `true` | Bật timer SDK; false không tự tắt reload-on-resume. |
| `banner.reload.resume_debounce_ms` | `500` | Debounce resume. |
| `banner.presentation.type` | `"NORMAL"` | NORMAL/LARGE_ANCHORED/COLLAPSIBLE/INLINE/INLINE_MAX_HEIGHT/FIXED. |
| `banner.presentation.collapsible_gravity` | `"BOTTOM"` | TOP/BOTTOM. |
| `banner.presentation.inline_style` | `"LARGE"` | Theo enum style SDK đã hỗ trợ. |
| `banner.presentation.inline_max_height_dp` | `50` | >=32; không tự chọn loại banner khi thiếu height. |
| `banner.presentation.fixed_size` | `"BANNER"` | BANNER/LARGE_BANNER/MEDIUM_RECTANGLE/FULL_BANNER/LEADERBOARD. |
| `native.load.tier_timeout_ms` | `30000` | Giữ timeout riêng đang cấu hình; provider OB cùng resolver format/slot. |
| `native.cache.max_age_ms` | `3600000` | Có thể giảm tuổi cache; không tăng quá lifetime hiện tại. |
| `native.reload.allowed` | `false` | Map canReloadAds; không là công tắc click reload. |
| `native.reload.resume_debounce_ms` | `500` | Debounce resume. |
| `native.reload.min_after_bind_ms` | `3000` | >0; cooldown sau bind. |
| `native.reload.timer_enabled` | `false` | Tách trạng thái timer khỏi interval để không tự bật khi chỉ đổi time. |
| `native.reload.interval_ms` | `15000` | >0; bị ràng buộc bởi min_after_bind_ms. |
| `native.presentation.auto_shimmer` | `true` | Tắt tự sinh shimmer; custom shimmer host vẫn là local. |
| `native.presentation.empty_visibility` | `"GONE"` | GONE/INVISIBLE. |
| `native.presentation.cta_corner_radius_dp` | `20` | Bo góc CTA (dp) khi có ctaBackgroundColor/colorCTA tường minh; màu default giữ XML. |
| `interstitial.load.tier_timeout_ms` | `30000` | Mỗi tier. |
| `interstitial.load_and_show.wait_timeout_ms` | `8000` | Budget đợi fill; không bao gồm chờ người dùng đóng ad. |
| `interstitial.load_and_show.buffer_wait_timeout_ms` | `8000` | UI wait khi tham gia AutoBuffer; dùng timeout đã resolve khi bắt đầu chờ, không có trần 5 giây; giá trị âm được xử lý thành 0. |
| `interstitial.load_and_show.allow_wait_for_auto_buffer` | `true` | Mặc định chờ AutoBuffer; partner có thể tắt bằng override. |
| `interstitial.presentation.next_screen_timing` | `"AFTER_AD"` | AFTER_AD/UNDER_AD, map InterNextAction; screen/explicit call timing ưu tiên cao hơn. |
| `interstitial.presentation.loading_enabled` | `true` | Dialog loading khi chờ fill và chuẩn bị show; false không hủy yêu cầu ads. |
| `interstitial.presentation.pre_show_delay_ms` | `800` | Nối delay preparation; không đồng nhất timeout đợi fill. |
| `interstitial.frequency.interval_ms` | `0` | Giữ scope hiện tại AutoBuffer; không tự áp splash/OB. |
| `interstitial.frequency.max_clicks_per_24h` | `0` | 0=tắt; counter theo inter ad unit. |
| `interstitial_auto_buffer.enabled` | `true` | Công tắc remote cho preload/refill tự động. Host vẫn phải configure placements và gọi start() từ content lifecycle. Mặc định true; remote/asset false chặn buffer. Chỉ áp dụng placements của AutoBuffer, không áp dụng toàn bộ interstitial. |
| `interstitial_auto_buffer.shared_config` | `false` | true giữ hành vi hiện tại (interval chung, vẫn hỗ trợ independent_interval và guard 2 thao tác chung). false tách toàn bộ placement: cooldown/retry và bộ đếm tap riêng, bỏ guard 2 thao tác chung; dùng rules.<placement>.interval_ms/tap_threshold hoặc cấu hình host. Không cần bật independent_interval khi false. |
| `interstitial_auto_buffer.tick_ms` | `0` | 0 theo interval chung. |
| `interstitial_auto_buffer.idle_tick_ms` | `30000` | Cadence khi interval tắt. |
| `interstitial_auto_buffer.min_tick_ms` | `5000` | Sàn tick, giữ trần 30 phút hiện tại. |
| `interstitial_auto_buffer.preload_lead_ms` | `2000` | Preload/refill/load qua manager cần đủ tap_threshold và max(0, interval_ms - preload_lead_ms). Show vẫn phải đủ toàn bộ interval_ms. |
| `interstitial_auto_buffer.rules` | `inter_all: 30000ms / 2 taps; inter_back: 30000ms / 1 tap` | Map theo placement: {enabled, independent_interval, tap_threshold, interval_ms}; đầy đủ placements/independentIntervalPlacements/tapThresholds/intervalMsByPlacement. rules={} xóa remote rules, không xóa cấu hình host hoặc mặc định SDK. |
| `interstitial.cache.max_age_ms` | `3600000` | Chỉ giảm so với lifetime hiện tại. |
| `rewarded.load.tier_timeout_ms` | `30000` | Giữ cache/request chung theo placement; không auto refill. |
| `rewarded.cache.max_age_ms` | `3600000` | Một unused fill; không thêm buffer_count/refill policy. |
| `app_open.load.timeout_ms` | `30000` | RESUME_FETCH_TIMEOUT_MS. |
| `app_open.load.max_background_requests` | `3` | Số request trong cửa sổ retry. |
| `app_open.load.background_retry_window_ms` | `120000` | Giới hạn cửa sổ retry. |
| `app_open.load.offline_recheck_ms` | `5000` | Cadence kiểm tra khi offline. |
| `app_open.load.failure_backoff_ms` | `[5000,30000,120000]` | Backoff theo số lần request app-open thất bại; chỉ nhận mảng 1–10 số nguyên dương; `[]` hoặc có phần tử sai thì bỏ qua, dùng lịch của asset app/SDK. |
| `app_open.presentation.loading_timeout_ms` | `3000` | Giới hạn loading resume. |
| `app_open.presentation.pre_show_delay_ms` | `800` | Delay trước show cached app-open. |
| `app_open.presentation.skip_after_ad_click` | `false` | Map policy click-return; explicit one-shot suppression từ host vẫn giữ. Click ad onboarding vẫn bỏ lần app-open kế tiếp, trừ khi remote đặt `false` tường minh. |
| `app_open.cache.max_age_ms` | `14400000` | Không tăng quá lifetime hiện tại. |
| `placement_overrides` | `{}` | Override theo key ad_config; chỉ các field hành vi được hỗ trợ. |

### onboarding_config

| Field | SDK default | Ghi chú |
|---|---|---|
| `schema_version` | `1` | Phiên bản schema đang hỗ trợ: 1. |
| `revision` | `0` | Metadata được lưu cùng document; không phải gate. |
| `privacy_goals_screen.enabled` | `false` | Bật cụm Privacy → Goal cuối onboarding; xem [Privacy → Goals](privacy-goals-screen.vi.md). |
| `flow.skip_ad_only_steps_when_premium` | `true` | Bỏ trang chỉ chứa ads cho premium; không cho premium xem ads. |
| `flow.fullscreen_skip_style` | `"CLOSE_ICON"` | Kiểu X/Skip chung; không thay đổi delay/auto-next. |
| `splash.ads.slot_format` | `"BANNER"` | Chọn định dạng cho slot dưới splash: `BANNER` hoặc `NATIVE`. |
| `splash.ads.banner.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `splash.ads.native.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `splash.ads.interstitial.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `splash.timing.min_display_ms` | `3000` | Giữ legacy <=0 fallback local; canonical mới >=0, 0 được ghi rõ là không giữ minimum. |
| `splash.timing.ad_budget_ms` | `60000` | Budget chung sau notification/focus, không phải timeout tier. |
| `splash.timing.slot_min_visible_ms` | `1000` | Giữ để tương thích; splash không sử dụng. |
| `splash.timing.slot_wait_after_inter_ms` | `10000` | Giữ để tương thích; splash không sử dụng. |
| `splash.timing.notification_settle_ms` | `1000` | Sàn tối thiểu sau khi user trả lời prompt noti, trước khi inter được show. |
| `splash.load.ad_strategy` | `"ALTERNATE"` | Cả hai giá trị dùng luồng consent-first, không chờ remote. |
| `splash.load.lfo1_preload_mode` | `"SEQUENTIAL"` | PARALLEL/SEQUENTIAL so với inter splash; độc lập ad_strategy. |
| `splash.load.remote_fetch_timeout_ms` | `10000` | Đang fetch không tự đổi timeout cho chính lần fetch đó; lần sau dùng cache. |
| `splash.load.consent_hook_timeout_ms` | `10000` | Riêng hook tùy biến; UMP timeout nằm trong ad_behavior_config.consent. |
| `splash.load.billing_timeout_ms` | `5000` | Capture trước khi billing step chạy. |
| `splash.permissions.no_internet_prompt_enabled` | `true` | Giữ mặc định gating hiện tại. |
| `splash.permissions.notification_enabled` | `true` | Vẫn giữ granted/đã hỏi/manifest/OS checks. |
| `splash.navigation.next_screen_timing` | `"AUTO"` | AUTO/AFTER_AD/UNDER_AD. AUTO giữ mặc định: entry noti/widget/uninstall và native_fs chờ inter đóng. Giá trị khác thắng ở mọi lần mở, kể cả entry; `UNDER_AD` bỏ màn native_fs ở lần mở đó. |
| `splash.native.skip.delay_ms` | `3000` | Native splash trước LFO.
| `splash.native.skip.style` | `"CLOSE_ICON"` | Native splash trước LFO.
| `splash.native.skip.position` | `"RIGHT"` | Native splash trước LFO.
| `splash.native.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `lfo.native1.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `lfo.native2.enabled` | `true` | Bật/tắt hành động đổi sang native thứ hai sau chọn ngôn ngữ; không bật lại ad unit bị tắt. |
| `lfo.native2.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `lfo.native2.swap_wait_timeout_ms` | `8000` | Timeout giữ LFO1 nếu LFO2 chưa bind. |
| `lfo.native2.preload_trigger` | `"LFO_SHOWN"` | LFO_SHOWN hoặc FIRST_SELECTION. Không tắt hiển thị slot 2. |
| `lfo.tap_hint.enabled` | `true` | Thay ob_show_language_tap_hint. |
| `lfo.tap_hint.delay_ms` | `3000` | Thay ob_language_tap_hint_delay_sec. |
| `lfo.confirm_button.visible_before_selection` | `true` | Remote (hoặc `ob_show_language_confirm_before_select` đã gửi), rồi asset app, ghi đè `LanguageConfig.confirmVisibleBeforeSelect` theo cả hai chiều. |
| `lfo.confirm_button.save_on_back` | `true` | Back trước chọn vẫn inert. |
| `lfo.confirm_button.style` | `"CHECK_ICON"` | `CHECK_ICON` / `TEXT` (Done); màu từ `onboarding.primary_color`. |
| `lfo.confirm_button.image_url` | `""` | Rỗng giữ drawable check hiện tại; URL lỗi giữ icon dự phòng. |
| `onboarding.primary_color` | `"#FF375E"` | Màu dùng chung cho NEXT, Get Started cuối, indicator đang chọn, nút tick/Done LFO và radio ngôn ngữ đang chọn. |
| `lfo.confirm_dialog.enabled` | `true` | Thay ob_show_language_confirm_dialog. |
| `lfo.confirm_dialog.show_from_tap` | `4` | Số nguyên >=1; chỉ gate khi chọn ngôn ngữ khác. Chọn lại ngôn ngữ hiện tại mở popup ngay nhưng vẫn cộng count. |
| `lfo.confirm_dialog.native_preload_trigger` | `"DIALOG_OPEN"` | DIALOG_OPEN/LFO_SHOWN/FIRST_SELECTION; mặc định on-demand. |
| `lfo.confirm_dialog.native_behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `lfo.languages.supported_codes` | `[]` | Rỗng giữ catalog; mã lạ bị loại, lọc rỗng trở về catalog. |
| `lfo.languages.default_code` | `""` | Rỗng tường minh xóa default cấu hình; không xóa lựa chọn user đã lưu. Chỉ nhận mã thuộc danh sách hiển thị. |
| `lfo.exit.reuse_splash_inter` | `true` | Chỉ nhánh thoát LFO không vào pager. |
| `onboarding.navigation.lock_pager_swipe` | `false` | Giữ chính sách page eligibility hiện tại; false không tự mở swipe OB1 trong working tree. |
| `onboarding.navigation.swipe_completes_last_step` | `true` | Trong working tree còn cần !lock_pager_swipe. |
| `onboarding.navigation.back_navigates_back` | `true` | Giữ behavior Back hiện tại. |
| `onboarding.ads.content_native_behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `onboarding.ads.fullscreen_native_behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `onboarding.fullscreen.skip.enabled` | `true` | Thay showSkipButton && ob_show_skip_ob3. |
| `onboarding.fullscreen.skip.delay_ms` | `5000` | New >=0; legacy -1 kế thừa, không chuyển -1000 thành timer. |
| `onboarding.fullscreen.skip.style` | `"CLOSE_ICON"` | Kiểu X/Skip của trang fullscreen trong OB. |
| `onboarding.fullscreen.auto_next.enabled` | `true` | Không điều khiển OB5 standalone. |
| `onboarding.fullscreen.auto_next.delay_ms` | `15000` | Timer từ page selection, tính background như hiện tại. |
| `onboarding.steps.full1.fullscreen.skip.position` | `"RIGHT"` | Phía đặt X/Skip của riêng trang full1. |
| `onboarding.steps.full2.fullscreen.skip.position` | `"RIGHT"` | Phía đặt X/Skip của riêng trang full2. |
| `onboarding.order` | Thứ tự app khi thiếu | Array chọn/sắp xếp catalog; `[]` bỏ pager. |
| `onboarding.preload.ob5_on_last_step` | `true` | Preload duy nhất của OB5, chạy khi tới cuối pager; vẫn cần OB5 bật. Lúc rời pager OB5 chỉ mở khi native đã load xong, nên `false` tắt hẳn OB5 dù `ob5.enabled` là `true`. |
| `onboarding.exit_interstitial.enabled` | `true` | Bật/tắt hành động interstitial cuối OB; unit vẫn phải được ad_config cho phép. |
| `onboarding.exit_interstitial.preload_on_entry` | `true` | Preload inter kết thúc OB khi vào pager. |
| `onboarding.exit_interstitial.wait_timeout_ms` | `8000` | Riêng inter cuối OB. |
| `onboarding.exit_interstitial.next_screen_timing` | `"UNDER_AD"` | AFTER_AD/UNDER_AD; entry đặc biệt vẫn AFTER_AD. |
| `onboarding.exit_interstitial.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `ob5.enabled` | `false` | Standalone chỉ mở nếu ad ready như hiện tại. |
| `ob5.native.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `ob5.skip.enabled` | `true` | ob_show_skip_ob5. |
| `ob5.skip.delay_ms` | `3000` | Tách override riêng với pager; legacy ob_skip_button_delay_sec áp cả hai như trước. |
| `ob5.skip.style` | `"CLOSE_ICON"` | Kiểu X/Skip của màn OB5 standalone. |
| `ob5.skip.position` | `"RIGHT"` | Phía đặt X/Skip của màn OB5 standalone. |
| `ob5.auto_dismiss_ms` | `15000` | Thời gian đóng OB5; tối thiểu 5000ms. |
| `welcome_back.enabled` | `false` | Màn Welcome Back cho user cũ mở từ launcher; xem [Welcome Back](welcome-back-screen.vi.md). |
| `welcome_back.native1.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `welcome_back.native2.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |

`native_fs` chờ đúng lượt preload từ splash và hiện shimmer trong lúc chờ; vào màn không tạo request mới. Mặc định nút X hiện sau 3 giây kể từ khi bind ad. Màn không tự chuyển tiếp; key cũ `splash.native.auto_dismiss_ms` không còn tác dụng.

`interstitial_auto_buffer` là nhóm cấp cao nhất, mặc định `enabled: true`. Nhóm này chỉ điều khiển placements khai báo trong `InterstitialAutoBuffer` hoặc remote `rules`, trừ placements đã reserve. Host vẫn phải gọi `configure()` / `start()`; bật field này không tự khởi động buffer hoặc tự show quảng cáo. Các cấu hình interstitial khác vẫn nằm trong `interstitial`.

`interstitial_auto_buffer.rules.<placement>` trong remote hoặc asset app có thể thêm placement được quản lý ngoài danh sách host; predicate của host và `enabled: false` vẫn chặn nó. Khi buffer đang chạy, placement vừa được nhận quản lý bắt đầu cooldown đầu tiên lúc settings thay đổi. `tick_ms: 0` theo `interstitial.frequency.interval_ms`, fallback về `ERainAdConfig.intervalInterstitialAd` của host.

## Force update

Parameter String riêng `force_update_config` điều khiển ngưỡng versionCode và bắt buộc/gợi ý cập nhật; không nằm trong hai document settings ở trên. Xem [setup, JSON, cache và tích hợp gate](force-update-integration.vi.md).

### Onboarding primary color

Đặt `onboarding.primary_color` trong `onboarding_config` thành `"#RRGGBB"` hoặc `"#AARRGGBB"` (ví dụ `"#1E88E5"`). Đây là màu dùng chung cho nút NEXT, nút Get Started cuối, indicator tiến độ đang chọn, nút tick/Done ở LFO và radio ngôn ngữ đang chọn ở LFO. Remote overrides the app asset; an empty value keeps the existing UI color. Native `ad_config.<placement>.colorCTA` colors both the CTA background and the Ad badge background.
