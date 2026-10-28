# Cấu hình ads và hành vi onboarding

**OB catalog:** `ob1..ob4` → `native_ob1..4`; `full1/full2` → `native_full1/2`. Default: `ob1, full1, ob2, full2, ob3, ob4`. All eligible OB natives preload on language selection. Remote `onboarding.order` selects/reorders app-declared steps. [Configuration / Hướng dẫn chi tiết](onboarding-flow.vi.md). `native_fs` remains the separate splash native.

`onboarding.order` là danh sách duy nhất chọn và sắp xếp màn trong JSON; bỏ ID để bỏ màn. Không cần khai báo `steps` nếu không có tùy chỉnh riêng. `steps.<id>.enabled` đã bỏ và bị bỏ qua. `order` remote ưu tiên hơn các cờ cũ `ob_enable_step_ob1..4` mà backend đã gửi, và các cờ đó ưu tiên hơn `order` trong asset app. Màn app khai báo `enabled = false` vẫn ẩn cho tới khi `order` remote liệt kê nó hoặc `ob_enable_step_obN = true` đã gửi bật nó; `order` trong asset app vẫn giữ màn đó ẩn. Remote không thêm được màn app chưa khai báo: `order` chứa ID chưa khai báo bị bỏ qua toàn bộ và nguồn kế tiếp được áp dụng. `steps.<id>` chỉ dành cho behavior, fullscreen tùy chọn và `ob1.back.enabled`. Bật/tắt ads từng vị trí bằng `ad_config.<placement>.isEnable`: content vẫn hiện khi ads tắt, màn fullscreen không có ads sẽ được bỏ qua.

[English](remote-settings.md) · [Tiếng Việt](remote-settings.vi.md) · [हिन्दी](remote-settings.hi.md)

SDK giữ nguyên Firebase `ad_remote_config`, assets `ad_config.json` / `ad_config_debug.json`. Hai parameter mới là **String chứa object JSON**. Xem [các bước publish trên Firebase](firebase-integration.vi.md#remote-json) và [tạo hai file local custom default](firebase-integration.vi.md#local-defaults). Các [file mẫu](examples/ads-onboarding/) khớp default SDK, trừ ba chỗ trong file onboarding: `onboarding.primary_color` để `""` để giữ màu hiện có của app, còn asset SDK đặt `"#FF375E"`; `onboarding.exit_interstitial.enabled` là `true`, tức bật interstitial cuối OB, còn default SDK là `false`; và không có scope [`native_flow`](#kiểu-native-flow), nên app copy file mẫu làm asset vẫn lấy native flow từ remote và default SDK.

Dùng **version SDK mới nhất** trên [JitPack](https://jitpack.io/#truongvimit/adlogic-partner-sdk) cho mọi module. Chỉ thêm key Firebase không nâng cấp SDK cũ đã tích hợp trong app.

| Parameter | File mặc định trong SDK |
|---|---|
| `ad_behavior_config` | `ads/src/main/assets/adlogic_defaults/ad_behavior_config.json` |
| `onboarding_config` | `onboardkitorigin/src/main/assets/adlogic_defaults/onboarding_config.json` |

Cụm Privacy → Goal tùy chọn là phần cuối onboarding, sau pager và interstitial cuối pager (nếu được bật; mặc định tắt). Bật `privacy_goals_screen.enabled` và cung cấp lựa chọn goal; xem [Privacy → Goal](privacy-goals-screen.vi.md).

Welcome Back mặc định ẩn; bật `welcome_back.enabled` để user cũ mở từ launcher vào màn này sau splash. Xem [Welcome Back](welcome-back-screen.vi.md).

## Mỗi giá trị có một nơi quản lý

- **ad_config:** `ids`, `isEnable`, `enable_ua_check`, `reloadIntervalSeconds`, `colorCTA`, `colorBackground`, `colorAdBadge`, `colorAdBadgeText`, `heightCTA`, `templateId`, `components`, `click_action` của native, `carousel` của LFO, `open_resume.app_resume_load_delay_ms`, `native_resume.app_resume_load_delay_ms`. Hai JSON mới không khai báo lại các field này, mapping ad unit hoặc công tắc từng ad unit.
- **ad_behavior_config:** timeout/cache, policy reload, frequency/AutoBuffer, consent timeout, telemetry, bo góc CTA native, dạng resume và hành vi app-open. Banner type/size là preset định dạng quảng cáo của SDK; không chứa resource/layout của app.
- **onboarding_config:** bật/tắt bước của luồng, skip/X delay, auto-next, swipe/back, chiến lược splash, thời điểm preload LFO/OB và hành vi exit; kiểu nút X/Skip, hình/màu nút xác nhận LFO, màu nút Start của màn native resume và lựa chọn ngôn ngữ trong catalog của app. Bật bước không bật lại placement đang `isEnable=false` trong ad_config.
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
| Native toàn màn hình của splash (sau inter splash, trước LFO) | `native_fs` |
| Splash người dùng cũ | `<key splash interstitial>_o` (`inter_splash_o`); không khai báo `_o` thì người dùng cũ dùng unit của `inter_splash` |
| Splash mở từ notification / widget / uninstall | `inter_noti` / `inter_widget` / `inter_uninstall`; key thiếu hoặc bị tắt thì chuyển về key của tệp user |
| Privacy / Goal initial; ALT | `native_select`; `native_select_alt` (mỗi key có waterfall `ids` riêng) |
| LFO1 / LFO2 / dialog | `native_lang` / `native_lang_alt` / `native_popup_lang` |
| Content OB1 / OB2 / OB3 / OB4 | `native_ob1` / `native_ob2` / `native_ob3` / `native_ob4` |
| Fullscreen Full1 / Full2 | `native_full1` / `native_full2` |
| OB5 | `native_onboarding_fullscreen_1_4` |
| Welcome Back đầu; ALT | `native_welcome1`; `native_welcome2` (mỗi key có waterfall `ids` riêng) |
| Exit interstitial | `inter_after_ob3` |
| App resume | `app_resume.type` trong `ad_behavior_config` chọn `native_resume`, `inter_resume`, `open_resume` hoặc `none`; key ad_config cùng tên phải bật kèm ID, nếu không thì quay lại không hiện gì. `open_resume` khai kèm ID không cần seed `idAdResume`. `AppOpenManager.getInstance().disableAppResume()` giữ tắt mọi dạng |

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

Đổi ID, tiers, switch, màu/chiều cao/vị trí CTA, components và hành động click native (`click_action`) vẫn thực hiện ở ad_config. Chọn preset template SDK và bo góc CTA dùng hai JSON mới như bảng dưới. Association này cũng dùng để tìm `ad_behavior_config.placement_overrides.<key>`; LFO1 mặc định tìm `native_lang`, không nhầm với key telemetry/buffer nội bộ `language1`. API `AdsConfig(...)` truyền raw ID vẫn được hỗ trợ, nhưng key nào trong bảng trên mà `ad_remote_config` của backend hoặc `ad_config.json` của chính app khai báo sẽ ưu tiên hơn unit ghi trong code, kể cả `stepNatives`, `splashInterstitialOldUser` và `splashInterstitialOverride()`; unit trong code chỉ áp cho placement mà cả hai không khai báo. Key ngoài bảng, ví dụ key của step tự khai, chỉ áp dụng khi bạn liên kết qua `AdsConfig.fromAdConfig(mapOf(...))`. SDK không đoán association từ các ID có thể trùng nhau.

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
- Số phải là số nguyên trong khoảng 0–2147483647. `0` bị từ chối cho mọi `*timeout_ms` trừ `*wait_timeout_ms`, và cho `min_after_bind_ms`, `offline_recheck_ms`, `native.reload.interval_ms`. `show_from_tap` và `max_background_requests` tối thiểu 1, `ob5.auto_dismiss_ms` tối thiểu 5000, `inline_max_height_dp` tối thiểu 32; `cache.max_age_ms` trong 1–3600000 (app-open 1–14400000).
- Fetch lỗi/timeout hoặc JSON hỏng: giữ snapshot hợp lệ gần nhất; document bị từ chối được log dưới `AdLogicSettings`. Với `ad_behavior_config` / `onboarding_config`, `schema_version` chưa hỗ trợ chỉ là một field bị bỏ qua, các field khác vẫn áp dụng; chỉ `ad_remote_config` từ chối cả document vì nó. Lần đầu chưa có remote dùng local/default. Cache lưu SharedPreferences, khôi phục ở lần chạy sau; lỗi cache không làm mất default trong bộ nhớ. Fetch lỗi không ép local ghi đè remote cache hợp lệ. Fetch nền chờ max(`splash.load.remote_fetch_timeout_ms`, 60000) ms và splash không chờ nó; fetch về sau khi splash đã đi vẫn được áp dụng cho phần còn lại của phiên. Muốn bỏ override, publish `{}` hoặc `{"schema_version":1}` rồi fetch thành công; không dùng String rỗng. Đổi asset local cần build và khởi động lại process.
- `AdConfig.install` áp ngay `ad_remote_config` mà backend gửi gần nhất (giá trị Firebase activate gần nhất), nên fetch chậm hoặc lỗi vẫn chạy trên document đó thay vì asset. Fetch settings lỗi không chặn `ad_remote_config` được áp dụng.
- Defaults SDK sinh từ chính asset khi build, dùng được trước Context; không có bản Kotlin/XML cần đồng bộ cho các field thuộc hai JSON. Build từ chối null/sai schema.
- `ad_behavior_config.global.ads_enabled` là cờ global duy nhất áp dụng cho ads toàn SDK. Từng vị trí LFO/onboarding vẫn dùng `isEnable` và placement flag riêng; đã bỏ các cờ bật/tắt toàn flow. Consent, premium và lifecycle vẫn áp dụng. Đặt `global.ads_enabled=false` để tắt toàn bộ ads, hoặc tắt các placement tương ứng.
- Key `ob_*` cũ tiếp tục tương thích. Key đã gửi đặt giá trị theo cả hai chiều, xếp dưới hai document nhóm và trên asset app/host; `ob_splash_min_display_ms <= 0` giữ giá trị local, `ob_skip_button_delay_sec` âm bị bỏ qua. Custom UI legacy qua `ob_ui_content`/`ob_ui_design_tokens` vẫn theo API cũ, không được nhân bản sang hai JSON mới. Các API remote nội dung cũ vẫn sử dụng được; reference layout/resource và nội dung mặc định do app khai báo. Không nhầm nhóm nội dung trang này với template/style của quảng cáo trong hai JSON mới.
- Mọi build đọc cấu hình từ `ad_config.json`. Build debuggable đọc thêm `ad_config_debug.json`, file này chỉ có `ids` cho mỗi key placement, thường một tầng test: các tầng này thay cả waterfall của vị trí, nên debug thường không chạy waterfall. Field khác trong file debug bị bỏ qua kèm log `WARN`; placement đang bật trong `ad_config.json` mà không có entry debug thì không có ad (cũng log `WARN`). `ad_remote_config` remote áp mọi field trừ ID; `AdRemoteConfig.setAllowRemoteOverrideInDebug(true)` nhận cả ID remote khai báo. Hai JSON hành vi áp dụng như release.
- Splash chạy consent, fetch remote và billing song song. Slot banner/native và interstitial được request ngay khi consent kết thúc, dùng cấu hình và entitlement hiện có; không đợi remote hoặc billing. Remote đã cache hoặc đã về vẫn ưu tiên hơn asset. Job refresh thuộc SDK, tiếp tục sau khi splash đóng, với thời gian chờ nền ít nhất 60 giây. Các lần đọc sau nhận giá trị mới; request, timer và quyết định chuyển màn đã chốt không chạy lại. `SAME_TIME` và `ALTERNATE` cùng dùng thứ tự này. `onRemoteFetched()` chỉ chạy khi splash còn sống; tích hợp cần sống cùng process dùng `SettingsRegistry.addFetchListener`.

Sau refresh, cảnh báo OnboardKit liệt kê key placement app đã map nhưng remote không khai báo. Các key này giữ giá trị app; sửa một key khác tên trong Firebase không tác động đến chúng.

## Scope của behavior

Slot behavior > nhóm content/fullscreen OB (`onboarding.ads.content_native_behavior` cũng áp cho native Privacy/Goal) > placement override > format override > local/default. Thứ tự này áp dụng trong cùng một nguồn: giá trị remote ở bất kỳ scope nào ưu tiên hơn giá trị asset app ở bất kỳ scope nào. Các object `behavior` trống là scope tùy chọn cho hành vi chưa có trong ad_config, không chứa ad IDs/switch/UI resources.

| Format trong placement_overrides | Field được hỗ trợ |
|---|---|
| banner | reload.allowed, reload.auto_enabled, reload.resume_debounce_ms, presentation.* |
| native | load.tier_timeout_ms, reload.*, presentation.auto_shimmer, presentation.empty_visibility, presentation.cta_corner_radius_dp |
| interstitial | load.tier_timeout_ms, load_and_show.wait_timeout_ms, load_and_show.buffer_wait_timeout_ms, presentation.loading_enabled, cache.max_age_ms |
| rewarded | load.tier_timeout_ms, cache.max_age_ms |

App/SDK chủ động gọi preload native. Preload replacement dùng `setEnablePreload` và `preloadAfterShow`; remote không hỗ trợ `preload.enabled` / `preload.after_show`. Lịch preload onboarding dùng `lfo1_preload_mode`, `preload_trigger` và `onboarding.preload.*`. Timeout mỗi tier dùng `native.load.tier_timeout_ms` và `interstitial.load.tier_timeout_ms` (mặc định 30000 ms).

Slot inter splash được load trước rồi show từ buffer, nên `behavior` của nó nhận `load.tier_timeout_ms`; mọi wait `load_and_show.*` bị bỏ qua vì slot được load trước rồi mới show; field `load_and_show.*` ở đó bị bỏ qua. Chỉ inter cuối OB chờ fill: `onboarding.exit_interstitial.wait_timeout_ms` ưu tiên hơn `placement_overrides` và `interstitial.load_and_show.wait_timeout_ms` ở scope format. Frequency, next-screen timing, pre-show delay, app-open và native cache TTL ở scope format chung. Per-step fullscreen cho phép `onboarding.steps.<id>.fullscreen.skip.{enabled,delay_ms,style,position}` và `.auto_next.{enabled,delay_ms}`; riêng `position` chỉ tồn tại ở mức per-step, không có scope `onboarding.fullscreen` hay `flow` ở trên.

Banner reload cadence lấy `ad_config.<key>.reloadIntervalSeconds` nếu là số dương; thiếu/sai dùng giá trị host (mặc định SDK 15000ms). Refresh banner của SDK bật mặc định (`banner.reload.allowed` và `banner.reload.auto_enabled` là `true`); interval chỉ đặt nhịp. Tắt refresh trên console AdMob cho mọi banner ad unit, kể cả mọi tầng, nếu không banner sẽ refresh hai lần. Muốn tắt refresh của SDK: đặt `banner.reload.auto_enabled` là `false` (không timer; vẫn reload khi resume) hoặc `banner.reload.allowed` là `false` (SDK không reload gì), cho toàn bộ hoặc trong `placement_overrides.<key>`. Thời gian ở background trước khi request ad resume là `native_resume.app_resume_load_delay_ms` (mặc định 0) cho `native_resume`, áp cho cả hai màn resume, và `open_resume.app_resume_load_delay_ms` (2000ms) cho `inter_resume` và `open_resume`. Click vào ad thuộc mọi format chỉ bỏ qua lần `open_resume` hoặc `inter_resume` kế tiếp khi `app_open.presentation.skip_after_ad_click` là `true` (mặc định `false`); native resume luôn mở. Không thêm banner tier timeout khi loader chưa có timer đó.

Object behavior rỗng không che leaf ở scope rộng hơn hoặc nguồn bên dưới. `presentation.loading_enabled` dùng behavior đã chốt theo placement/format cho cả `show()` và `loadAndShow()`, kể cả ad đã có sẵn. Scope màn của interstitial (`splash.ads.interstitial.behavior`, `onboarding.exit_interstitial.behavior`) chỉ nhận `load.tier_timeout_ms` và `load_and_show.wait_timeout_ms` (scope splash bỏ `load_and_show.*`), nên đặt `loading_enabled` trong `placement_overrides.<key>` hoặc format `interstitial`. Banner đọc lại `enable_ua_check` của placement sau refresh; setter `forceUaCheck` tường minh của host ưu tiên hơn. Native vẫn áp dụng `presentation.cta_corner_radius_dp` khi helper không có style tường minh.

## Hành động khi click native

Hành động click của native chỉ có một nguồn: `click_action` trên key của placement trong `ad_config`, nhận `auto_next`, `none`, `reload` hoặc `reload_waterfall`. Giống `enable_ua_check` và các field CTA, field này đặt trên key của placement (`native_ob1`, `native_lang`, `native_home`…) và áp cho mọi tầng trong `ids`; giá trị đặt trên key `_high*` cũ bị bỏ qua. `ad_behavior_config` và `onboarding_config` không có field hành động click. SDK chốt đúng một hành động ở callback click/open đầu tiên, giữ nguyên đến khi quay về kể cả remote thay đổi giữa chừng.

- `reload`: request ad thay thế ngay lúc click/open, chỉ ở tầng all price (tầng cuối cùng đang bật trong `ids`), không đợi resume và không có delay cố định. Khi quay về, dùng ad đã tải hoặc chờ đúng request đang chạy. User quay lại nhanh vì thế không phải chờ qua các tầng trước all price trong `ids`. Resume app thông thường không kích hoạt click reload.
- `reload_waterfall`: như `reload`, nhưng ad thay thế đi qua mọi tầng đang bật trong `ids`, cao nhất trước.
  Với cả hai kiểu reload, trong lúc chờ vẫn hiển thị ad cũ, không hiện shimmer. Chỉ thay khi ad mới bind thành công; tải lỗi giữ ad cũ và khung quảng cáo. Shimmer chỉ dùng lúc tải ban đầu chưa có ad.
- `auto_next`: quay về thì chuyển trang onboarding đang hiển thị, không tải ad thay thế. Ở LFO2: tự confirm ngôn ngữ đã chọn. Ở LFO1: lặp lại cú tap của user vào ngôn ngữ họ đã chọn, như khi tap lại hàng đó; user chưa tap hàng nào thì không làm gì. Ở native khác, action này chỉ bỏ ad thay thế giống `none`; điều hướng vẫn do app quyết định.
- `none`: giữ ad và trang hiện tại; không reload theo click, không tự chuyển trang.

Mặc định khi key không khai `click_action`:

| Native | Mặc định |
|---|---|
| Trang pager onboarding: content `ob1..ob4` và content step app tự khai, fullscreen `full1/full2`; native full-screen của splash `native_fs` | `auto_next` |
| LFO1, LFO2, dialog xác nhận LFO, Privacy/Goal, Welcome Back, OB5, native slot đáy splash `native_splash` và native ở màn app | `reload` |

Ad của trang pager không tự refresh theo timer hay khi quay lại app, nhưng click thì làm theo hành động của trang: `reload` và `reload_waterfall` tải sẵn ad thay thế và gắn vào khi user quay lại, trang giữ nguyên; trang fullscreen dừng đếm auto-next trong lúc user ở trong ad. Nếu lúc click placement đang có một lượt tải chạy dở thì click join vào lượt đó, bất kể nó đang ở tầng nào. LFO2 không có unit riêng thì dùng key của LFO1, kéo theo hành động của LFO1, khi key đó đã được gắn: `AdsConfig.fromAdConfig` (mặc định của builder) gắn nó, backend hoặc chính `ad_config.json` của app khai key đó cũng gắn. `AdsConfig(...)` tự dựng mà không có cái nào trong số đó thì LFO2 đọc `native_lang_alt`. [ad_config mẫu](examples/ads-onboarding/ad_config.json) khai tường minh đúng các giá trị này trên mọi key native.

`click_action` theo thứ tự ưu tiên của ad_config, gộp theo từng field: `ad_remote_config` của backend > `ad_config.json` của app > mặc định trong code. Key remote không khai `click_action` giữ giá trị của asset; giá trị sai bị log và bỏ qua. `ad_config_debug.json` chỉ chứa ID, nên mọi build lấy `click_action` từ `ad_config.json` và remote. Với native ở màn app, mặc định trong code là `reload`; `NativeAdHelper.setReloadOnAdClick(false)` đổi thành `none`, và `click_action` vẫn thắng. Timer/resume refresh và timeout tự chuyển trang fullscreen là các cài đặt riêng.

Ví dụ trong `ad_config`: OB1 giữ nguyên trang sau khi click ad, LFO2 tự confirm ngôn ngữ khi quay về. `click_action` áp cho mọi tầng trong `ids`:

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

## Template và CTA để UA/MO thử nghiệm

- `ad_remote_config.<placement>.templateId` (số) chọn khung: `1` LFO classic, `2` card media-left 4:3, `3` card 1.91:1, `4` card LFO rộng theo DashDrama. Không có, `null` hoặc số lạ thì giữ mặc định của slot — `4` cho LFO1/2 trong `SHARED_POOL`, `1` cho LFO classic/Welcome Back/OB content, `2` cho Privacy/Goal và splash inline. Remote > asset app > code như mọi field.
- `components` là thứ duy nhất sắp thứ tự native. Template `1` và `3` theo nó; không có thì thứ tự mặc định đặt CTA ở dưới. Template `1`: `["icon_headline","media","cta"]` (mặc định) hoặc `["cta","media","icon_headline"]` để đưa CTA lên trên.
- `positionCTA` đã bỏ; payload vẫn gửi field này sẽ bị bỏ qua. Các field `lfo.native_template`, `onboarding.ads.content_template` và `onboarding.steps.<id>.native_template` cũng đã bỏ; payload cũ chứa chúng sẽ bị bỏ qua.
- Template 3 là một layout, bốn cách sắp xếp của thiết kế lấy từ thứ tự `components`: A `["media","icon_headline","body","cta"]`, B `["cta","icon_headline","body","media"]`, C `["icon_headline","body","media","cta"]` (thứ tự mặc định), D `["cta","media","icon_headline","body"]`. Body nằm trong header nên bỏ `body` chỉ làm ẩn nó.
- Template `2`, popup LFO (`DIALOG`) và Full1/Full2/OB5/native_fs (`FULL_SCREEN`) luôn hiện đúng như layout vẽ sẵn và bỏ qua `components`; popup và native fullscreen còn bỏ qua cả `templateId`. `colorCTA` áp dụng cho CTA và badge Ad ở mọi khung; `colorBackground` đổi màu nền card ở mọi khung, giữ nguyên hình dạng, và mặc định không khai. `colorAdBadge` và `colorAdBadgeText` đổi màu nền và màu chữ badge Ad ở mọi khung.
- Preload và show dùng chung bộ chọn template. Nếu host chủ động preload sớm hoặc remote được refresh sau preload, native được inflate theo template hiện hành tại bind, tái sử dụng ad đã tải. LFO1 được lên lịch theo preload mode hiện có, không chờ remote. Một ad đang hiển thị giữ view hiện tại đến lần bind tiếp theo.
- `flow.fullscreen_skip_style`, `onboarding.fullscreen.skip.style`, `onboarding.steps.<id>.fullscreen.skip.style`, `ob5.skip.style` nhận `CLOSE_ICON`/`TEXT`. Trong cùng một nguồn, scope cụ thể ưu tiên scope chung (remote ở bất kỳ scope nào ưu tiên hơn asset app), rồi tới cấu hình host; thời gian X/Skip và auto-next không đổi khi chỉ đổi style. Default các style khai báo sẵn là `CLOSE_ICON`.
- Phía đặt X/Skip khai theo từng trang native fullscreen, không có scope chung ở trên: `onboarding.steps.<id>.fullscreen.skip.position` cho mỗi trang fullscreen trong OB, `ob5.skip.position` cho OB5 standalone, `splash.native.skip.position` cho native_fs giữa inter splash và LFO. Cả ba nhận `RIGHT`/`LEFT`, default `RIGHT` — đúng phía X vẫn nằm từ trước; JSON gốc khai sẵn `full1` và `full2`, id step khác do app khai vẫn nhận ở cùng path đó. Các format khác không có cờ này: interstitial, app-open, banner và native inline đều không có nút X này. Hai phía đối xứng tuyệt đối: cùng khoảng cách tới mép và cùng margin trên, chỉ đổi phía chứ không đổi kích thước, style hay thời gian. Locale RTL vẫn lật như hiện tại: `RIGHT` theo mép cuối dòng chữ, `LEFT` theo mép đầu.
- `native.presentation.cta_corner_radius_dp` mặc định `20` dp, có thể override theo placement hoặc scope native từng màn. Áp dụng khi CTA có màu nền tường minh từ `colorCTA`/`NativeAdStyle.ctaBackgroundColor`; màu `default` giữ drawable XML như trước.
- `lfo.confirm_button.style`: `CHECK_ICON` (mặc định) hiển thị tick; `TEXT` hiển thị “Done”. Cả hai lấy màu từ `onboarding.primary_color`, mờ 50% trước khi chọn ngôn ngữ và sáng đầy đủ sau khi chọn. `visible_before_selection=false` ẩn cả hai kiểu trước khi chọn. `image_url` chỉ áp dụng với `CHECK_ICON`.
- `lfo.confirm_button.image_url`: mặc định `""`, giữ icon XML. URL ảnh lỗi dùng icon check của SDK. Màu nút tick luôn lấy từ `onboarding.primary_color`; CTA quảng cáo vẫn dùng field của ad_config.
- `lfo.confirm_button.tap_hint.enabled`: mặc định tắt (`false`). Chỉ khi là `true` — remote gửi, hoặc khai trong asset `onboarding_config.json` của app — bàn tay của LFO1 mới lên nút xác nhận ngay khi đã chọn ngôn ngữ (LFO2), chỉ vào icon tick hoặc chữ “Done”. Tay lặp tới khi rời màn; SETTINGS không bao giờ hiện. Remote luôn thắng asset, nên remote `false` tắt được asset `true`. Tay ở hàng ngôn ngữ LFO1 vẫn theo `lfo.tap_hint`.
- `lfo.languages.supported_codes` mặc định `[]`: giữ catalog app/SDK; mã không có trong catalog bị loại, kết quả rỗng trở về catalog. `lfo.languages.default_code` mặc định `""` được gửi tường minh: xóa ngôn ngữ chọn sẵn trong cấu hình (không xóa lựa chọn user đã lưu); mã phải nằm trong danh sách được hiển thị (`supported_codes` đã lọc, nếu không có thì catalog). `LanguageConfig.defaultCode` của host nằm ngoài `supported_codes` sẽ không được chọn sẵn.
- Carousel native ở LFO (chỉ `SHARED_POOL`; `PER_SCREEN` bỏ qua): `ad_remote_config.native_lang.carousel` (LFO1) và `ad_remote_config.native_lang_alt.carousel` (LFO2) là boolean, mặc định `false`; key khác bỏ qua field này. LFO2 chưa khai `native_lang_alt` thì theo `native_lang`. Khi `true`, khối ad của LFO thành 2 trang, mỗi trang rộng 85% nên trang kia ló ra ở mép. Trang 1 là slot của LFO như cũ; trang 2 vẫn là `native_lang` / `native_lang_alt` (cùng `ids`, template, màu…), analytics và doanh thu vẫn ghi `language1` / `language2`, mỗi trang tính một lượt. Hai trang được chọn cùng lúc nên không bao giờ hiện trùng một ad: mỗi trang hiện ad của key mình, không có thì giữ ad đang hiện, không có nữa thì mượn từ pool (trang 1 lấy ad mới nhất, trang 2 chỉ lấy ad mới nhất chưa hiện); trang 2 không có gì để mượn thì request POLITE key của mình. Cứ mỗi `lfo.carousel.auto_scroll_ms` (mặc định `3000`, tối thiểu `1000`) carousel tự sang trang kia nếu trang đó đã có ad, rồi gọi POLITE cho key của trang đó (key đang giữ ad thì không request). User vuốt sang trang nào thì trang đó lấy ad mới: key đang giữ ad thì refresh, không thì POLITE; trong lúc load trang đó phủ màn trắng loading, load lỗi thì giữ ad cũ. Mọi request do carousel gửi chỉ đi khi pool không có load nào đang chạy, nên vuốt kiểu gì cũng chỉ có 1 ad đang load. Resume app: trang đang hiện refresh như key LFO vẫn làm ở `SHARED_POOL`.
- Ở LFO lần đầu (first-open), tap đổi sang ngôn ngữ khác mà không mở popup xác nhận sẽ cho native đang hiện (LFO2 nếu đã swap sang, chưa thì LFO1) một ad mới. Ở tap bắt đầu swap LFO2, chính swap là ad mới đó nên không reload gì. Nếu LFO2 không hiện được (no-fill, hoặc chưa bind trong `lfo.native2.swap_wait_timeout_ms`), LFO1 được giữ và reload thay, trừ khi popup xác nhận đã mở, nên lần chọn đó vẫn có ad mới. Ad LFO2 fill sau khi swap đã bỏ được swap vào ở lần đổi ngôn ngữ kế tiếp, trước khi LFO1 request lại. Mọi tap như vậy khác đều reload, kể cả tap đầu khi `lfo.native2.enabled` là `false`. Tap mở popup không reload vì popup đã che ad: khi `lfo.confirm_dialog.enabled` là `true`, đó là mọi tap từ `lfo.confirm_dialog.show_from_tap` trở đi. `show_from_tap` được đọc ở từng tap, nên giá trị remote về trong lúc LFO đang mở sẽ dời mốc đó, và reload dời theo, từ tap kế tiếp. Chọn lại ngôn ngữ đang chọn không phải là đổi ngôn ngữ nên không bao giờ reload. Reload request toàn bộ waterfall của key (các tầng đang bật trong `ids`, cao nhất trước), bất kể `click_action` của key, và giữ ad hiện tại tới khi ad mới bind; reload lỗi thì giữ ad cũ. Không request khi slot chưa có ad trên màn (đang shimmer, hoặc no-fill), khi user chưa quay lại sau khi click ad của slot, hoặc khi swap LFO2 hay một lượt load khác của slot đó còn đang chạy, nên đổi ngôn ngữ liên tục không chồng request.
- `onboarding.steps.ob1.back.enabled`: mặc định bật (`true`). Chỉ có tác dụng khi OB1 là trang đầu của pager; không trang nào khác có cờ này hay hiện nút. Khi bật, OB1 có nút back ở góc trên bên trái (RTL thì bên phải); nút này, nút Back hệ thống và vuốt back trên OB1 quay về LFO. LFO mở lại như lần vào đầu: chưa chọn sẵn ngôn ngữ, có lại bàn tay gợi ý; LFO1 request khi màn mở vì lần này không có preload, LFO2 theo `lfo.native2.preload_trigger` như thường, cả hai đi hết waterfall. Lần chọn ngôn ngữ kế tiếp preload lại các native OB: ad chưa dùng thì giữ; ad đã gắn sẵn vào một trang sau nhưng chưa được xem ở đó (chưa có impression, click hay open) cũng được giữ, trang đó hiện lại chính ad ấy mà không request mới. OB1, và trang có ad đã được xem, được request ad mới. Mọi trang khác, và OB1 khi cờ là `false` hoặc OB1 không phải trang đầu, giữ Back như cũ: lùi một trang theo `onboarding.navigation.back_navigates_back`, từ trang đầu thì thoát app. Giá trị được đọc khi mở pager.

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

## Kiểu native flow

`native_flow.type` trong `onboarding_config` chọn cách các màn sau splash lấy native:

- `PER_SCREEN` (mặc định): flow mà phần còn lại của tài liệu này mô tả. LFO, từng trang onboarding và Privacy → Goal tự request placement của riêng mình.
- `SHARED_POOL`: LFO, pager onboarding và Privacy → Goal chạy trong một màn của SDK, mọi slot native lấy ad từ một pool dùng chung. Màn đang ở trước hiện ad của chính placement của nó ngay khi pool có ad đó; trước đó slot hiện ad mới nhất trong pool chưa được hiển thị, rồi tới ad đã hiển thị ở màn khác. Khi đang có một request chạy, lượt load nền của các màn khác bị bỏ qua chứ không xếp hàng. Key ad_config, các tầng và công tắc `isEnable` giống hệt `PER_SCREEN`. Kiểu này cần ad provider có sẵn của SDK; với `OnboardingAdProvider` tùy biến, lượt chạy vẫn là `PER_SCREEN`.

Kiểu được quyết định một lần mỗi lượt chạy: splash hỏi trước, để biết có preload LFO1 hay không (LFO của lượt `SHARED_POOL` tự request LFO1 nên splash bỏ preload đó; `PER_SCREEN` vẫn giữ), và lúc mở màn ngôn ngữ dùng đúng câu trả lời đó. Giá trị về sau áp dụng từ lượt chạy kế tiếp, nên một lượt không bao giờ đổi kiểu giữa chừng. Welcome Back và màn chọn ngôn ngữ trong app luôn dùng `PER_SCREEN`. Sau một lượt `SHARED_POOL`, native resume (`app_resume.type` `native_resume`) trong cùng process mở màn resume của pool khi app đã ở background ít nhất `native_resume.app_resume_load_delay_ms`: màn này hiện ad còn lại trong pool và request `native_resume`, luôn ở dạng POLITE; lần mở nguội kế tiếp dùng lại màn resume thường.

- **Alias phẳng.** Backend chỉ publish key phẳng có thể gửi Boolean `ob_native_reuse_enabled` (mặc định `false`): `true` chọn `SHARED_POOL`, `false` chọn `PER_SCREEN`. Key chỉ được tính khi backend thực sự gửi về, và được cache cho lần mở nguội kế tiếp như các key `ob_*` khác. Thứ tự ưu tiên: `native_flow.type` remote > `ob_native_reuse_enabled` đã gửi > asset app > default SDK `PER_SCREEN`. `native_flow.type` không hợp lệ bị bỏ qua và nguồn kế tiếp được áp dụng.
- **Analytics.** Mỗi lượt chạy có mở màn ngôn ngữ đặt user property `ob_native_flow` thành `per_screen` hoặc `shared_pool`, tức flow thực sự đã chạy.
- **Lần mở đầu tiên.** Splash không chờ remote, nên bản cài mới mà lượt fetch đầu chưa về sẽ chạy kiểu do asset app hoặc default SDK quy định, và onboarding không chạy lại sau khi hoàn tất. Tách kết quả A/B theo user property `ob_native_flow`, không theo giá trị remote được gán.
- **QA.** Đặt `{"native_flow":{"type":"SHARED_POOL"}}` vào asset `onboarding_config.json` của app, hoặc publish remote, rồi khởi động lại process.

Giá trị chỉ `SHARED_POOL` đọc nằm dưới `native_flow.pool`, đặt tên theo key classic cùng ý nghĩa. Key classic vẫn điều khiển `PER_SCREEN` và pool không đọc nó:

| Key `PER_SCREEN` (mặc định) | Key `SHARED_POOL` (mặc định) |
| --- | --- |
| `onboarding.fullscreen.skip.delay_ms` (`5000`) | `native_flow.pool.onboarding.fullscreen.skip.delay_ms` (`2000`), tính từ lần resume đầu của trang |
| `onboarding.fullscreen.auto_next.delay_ms` (`15000`) | `native_flow.pool.onboarding.fullscreen.auto_next.delay_ms` (`10000`), tính từ lúc chọn trang |
| `lfo.confirm_button.visible_before_selection` (`true`) | `native_flow.pool.lfo.confirm_button.reveal_delay_ms` (`3000`): nút xác nhận ẩn tới khi đủ khoảng này sau lần tap ngôn ngữ đầu tiên |
| không có | `native_flow.pool.select.next_reveal_delay_ms` (`3000`): nút Continue của Privacy và Next của Goal ẩn tới khi đủ khoảng này sau lần đổi consent hoặc tap lựa chọn đầu tiên |
| không có | `native_flow.pool.lfo.loading_dialog.duration_ms` (`0`, tắt): LFO1 mở mới hiện card "Loading languages" chặn header và danh sách (không bao giờ đè ad) trong khoảng này, chỉ đếm khi màn đang hiển thị |

Hai flow đọc chung key cho popup xác nhận ngôn ngữ (`lfo.confirm_dialog.enabled`, `lfo.confirm_dialog.show_from_tap`, tap lại hàng đang chọn), nút back OB1 (`onboarding.steps.ob1.back.enabled`), thời hạn ad (`native.cache.max_age_ms` trong `ad_behavior_config`, một giờ) và thời gian chờ mỗi tầng (`native.load.tier_timeout_ms` cùng `placement_overrides` của nó, 30 giây; tầng không trả lời kịp được tính là lỗi và tầng kế tiếp được request). `onboarding.order`, `privacy_goals_screen.enabled` và `lfo.exit.reuse_splash_inter` áp dụng cho cả hai.

`SHARED_POOL` không dùng các tính năng sau của `PER_SCREEN`: preload LFO1 ở splash, bàn tay gợi ý ở LFO và việc đưa ngôn ngữ thiết bị lên đầu, swap LFO2 và các timer của nó, `click_action` (click không chuyển trang cũng không reload), OB5, vuốt pager và interstitial cuối pager. Back hệ thống không làm gì ở mọi màn của nó; nút back OB1 quay về LFO, giữ ngôn ngữ đã chọn và hiện sẵn nút xác nhận. Done ở LFO hiện interstitial splash còn lại khi `lfo.exit.reuse_splash_inter` là `true`, Next ở Goal cũng vậy khi `native_flow.pool.goals.exit_reuse_splash_inter` là `true`.

| Field trong `native_flow.pool` | Mặc định | Tác dụng |
| --- | --- | --- |
| `locale_apply` | `"IN_PLACE"` | `IN_PLACE` áp ngôn ngữ đã chọn lên màn đang chạy; `RECREATE` tạo lại màn. |
| `exit.next_screen_timing` | `"AFTER_AD"` | `AFTER_AD` / `UNDER_AD`: chặng kế tiếp bắt đầu sau khi ad thoát của LFO hoặc Goal đóng, hay ngay bên dưới ad. |
| `pager.offscreen_pages` | `1` | Số trang giữ sống cạnh trang hiện tại; `1` giữ trang kế tiếp để prepare. Giá trị lớn hơn giữ thêm trang kề bên. |
| `stuck_loading_log_ms` | `60000` | Ghi log request còn chạy sau khoảng này; chỉ log. |
| `view.destroy_on_teardown` | `true` | View ad của slot bị hủy khi màn của nó đóng; ad vẫn ở trong pool. |
| `goals.exit_reuse_splash_inter` | `true` | Next ở Goal hiện interstitial splash còn lại. |
| `parity.refresh_overwrites_unshown` | `true` | Refresh khi quay lại màn thay cả ad chưa hiển thị; `false` chỉ thay ad đã hiển thị. |
| `parity.retry_skipped_polite` | `false` | Lượt load nền bị bỏ qua khi request khác đang chạy thì bỏ luôn; `true` chạy lại khi pool rảnh. |
| `parity.async_gate` | `true` | Trạng thái có request đang chạy được tính ngoài main thread và có thể trễ một frame; `false` tính ngay. |
| `parity.async_selector` | `true` | Ad mỗi slot hiện được chọn ngoài main thread; `false` chọn ngay. |
| `parity.borrow_showing` | `true` | Slot không còn gì khác để hiện có thể hiện ad đã hiển thị ở nơi khác; `false` ẩn slot. |
| `parity.destroy_replaced_ads` | `false` | Ad bị thay hoặc hết hạn được bỏ mà không destroy; `true` destroy nó. |
| `parity.pager_slots_bind_resumed` | `false` | Trang pager bind ad ngay khi đã start để page kế bên hiện ad đang giữ mới nhất trước khi swipe kết thúc; `true` thì đợi resume. |

Các công tắc `parity.*` tái hiện app tham chiếu mà pool được mô phỏng theo. Giữ mặc định trừ khi bạn chủ đích tinh chỉnh hành vi đó.

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
| `interstitial.frequency.interval_ms` | `0` | Đồng hồ chung của AutoBuffer khi `interstitial_auto_buffer.shared_config` là `true`; không áp splash/OB. |
| `interstitial.frequency.max_clicks_per_24h` | `0` | 0=tắt; counter theo inter ad unit. |
| `interstitial_auto_buffer.shared_config` | `false` | false: mỗi placement có cooldown/retry và bộ đếm tap riêng, dùng rules.<placement>.interval_ms/tap_threshold hoặc cấu hình host. true: mọi placement dùng chung một đồng hồ theo `interstitial.frequency.interval_ms`; bộ đếm tap vẫn riêng từng placement. |
| `interstitial_auto_buffer.preload_lead_ms` | `2000` | Preload/refill/load qua manager cần tap_threshold - 1 tap (không cần tap nào trước lần show đầu tiên của placement trong process) và max(0, interval_ms - preload_lead_ms). Show vẫn phải đủ toàn bộ interval_ms. Áp dụng cho đồng hồ riêng từng placement (`shared_config: false`). |
| `interstitial_auto_buffer.rules` | `inter_all: 30000ms / 2 taps; inter_back: 30000ms / 1 tap` | Map theo placement: {enabled, tap_threshold, interval_ms}; tương ứng placements/tapThresholds/intervalMsByPlacement của host. rules={} xóa remote rules, không xóa cấu hình host hoặc mặc định SDK. |
| `interstitial.cache.max_age_ms` | `3600000` | Chỉ giảm so với lifetime hiện tại. |
| `rewarded.load.tier_timeout_ms` | `30000` | Giữ cache/request chung theo placement; không auto refill. |
| `rewarded.cache.max_age_ms` | `3600000` | Một unused fill; không thêm buffer_count/refill policy. |
| `app_open.load.timeout_ms` | `30000` | RESUME_FETCH_TIMEOUT_MS. |
| `app_open.load.max_background_requests` | `3` | Số request trong cửa sổ retry. |
| `app_open.load.background_retry_window_ms` | `120000` | Giới hạn cửa sổ retry. |
| `app_open.load.offline_recheck_ms` | `5000` | Cadence kiểm tra khi offline. |
| `app_open.load.failure_backoff_ms` | `[5000,30000,120000]` | Backoff theo số lần request app-open thất bại; chỉ nhận mảng 1–10 số nguyên, mỗi số trong 1–3600000; `[]` hoặc có phần tử sai thì bỏ qua, dùng lịch của asset app/SDK. |
| `app_open.presentation.loading_timeout_ms` | `3000` | Giới hạn loading resume. |
| `app_open.presentation.pre_show_delay_ms` | `800` | Delay trước show cached app-open. |
| `app_open.presentation.skip_after_ad_click` | `false` | Map policy click-return; explicit one-shot suppression từ host vẫn giữ. Chỉ khi `true`, click ad (mọi format) mới bỏ lần app-open hoặc inter resume kế tiếp; native resume không bị ảnh hưởng. |
| `app_open.cache.max_age_ms` | `14400000` | Không tăng quá lifetime hiện tại. |
| `app_resume.type` | `native_resume` | Dạng ad khi quay lại: `native_resume`, `inter_resume`, `open_resume` hoặc `none`; giá trị khác bị bỏ qua, dùng tầng dưới. |
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
| `lfo.carousel.auto_scroll_ms` | `3000` | Khoảng tự chuyển trang của carousel LFO ở `SHARED_POOL` (ms, tối thiểu 1000). Bật carousel ở `ad_remote_config.<key>.carousel`. |
| `lfo.tap_hint.enabled` | `true` | Thay ob_show_language_tap_hint. |
| `lfo.tap_hint.delay_ms` | `3000` | Thay ob_language_tap_hint_delay_sec. |
| `lfo.confirm_button.visible_before_selection` | `true` | Remote (hoặc `ob_show_language_confirm_before_select` đã gửi), rồi asset app, ghi đè `LanguageConfig.confirmVisibleBeforeSelect` theo cả hai chiều. |
| `lfo.confirm_button.save_on_back` | `true` | Back trước chọn vẫn inert. |
| `lfo.confirm_button.style` | `"CHECK_ICON"` | `CHECK_ICON` / `TEXT` (Done); màu từ `onboarding.primary_color`. |
| `lfo.confirm_button.image_url` | `""` | Rỗng giữ drawable check hiện tại; URL lỗi giữ icon dự phòng. |
| `lfo.confirm_button.tap_hint.enabled` | `false` | Mặc định tắt; `true` (remote hoặc asset app) mới hiện bàn tay trên nút tick/Done sau khi chọn ngôn ngữ (LFO2). |
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
| `onboarding.navigation.peek_px` | `1` | Padding ngang (px) của pager: page kề lộ ra đúng số px này; `0` tắt. Đọc khi mở màn OB. |
| `onboarding.ads.content_native_behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `onboarding.ads.fullscreen_native_behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `onboarding.fullscreen.skip.enabled` | `true` | Thay showSkipButton && ob_show_skip_ob3. |
| `onboarding.fullscreen.skip.delay_ms` | `5000` | New >=0; legacy -1 kế thừa, không chuyển -1000 thành timer. |
| `onboarding.fullscreen.skip.style` | `"CLOSE_ICON"` | Kiểu X/Skip của trang fullscreen trong OB. |
| `onboarding.fullscreen.auto_next.enabled` | `true` | Không điều khiển OB5 standalone. |
| `onboarding.fullscreen.auto_next.delay_ms` | `15000` | Timer từ page selection, tính background như hiện tại. |
| `onboarding.steps.ob1.back.enabled` | `true` | Nút back góc trên của OB1 khi OB1 là trang đầu; nút, Back và vuốt back quay về LFO. Trang khác giữ Back như cũ. `false` giữ Back như cũ ở cả OB1. |
| `onboarding.steps.full1.fullscreen.skip.position` | `"RIGHT"` | Phía đặt X/Skip của riêng trang full1. |
| `onboarding.steps.full2.fullscreen.skip.position` | `"RIGHT"` | Phía đặt X/Skip của riêng trang full2. |
| `onboarding.order` | Thứ tự app khi thiếu | Array chọn/sắp xếp catalog; `[]` bỏ pager. |
| `onboarding.preload.ob5_on_last_step` | `true` | Preload duy nhất của OB5, chạy khi tới cuối pager; vẫn cần OB5 bật. Lúc rời pager OB5 chỉ mở khi native đã load xong, nên `false` tắt hẳn OB5 dù `ob5.enabled` là `true`. |
| `onboarding.exit_interstitial.enabled` | `false` | Mặc định tắt: interstitial cuối OB chỉ hiện khi remote, asset app, `ob_ads_inter_after_ob3_enabled = true` đã gửi về hoặc `AdsConfig.afterOnboardingInterstitialEnabled = true` bật nó; unit vẫn phải được ad_config cho phép. SDK cũ mặc định `true`. |
| `onboarding.exit_interstitial.preload_on_entry` | `true` | Khi inter cuối OB đã bật, preload nó lúc vào pager. |
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
| `native_resume.start_button.color` | `"#FF375E"` | Màu chữ nút Start trên màn native resume; `""` giữ màu của layout. |
| `native_resume.start_button.delay_ms` | `0` | Delay trước khi nút Start hiện; áp dụng cho cả `PER_SCREEN` và `SHARED_POOL`. |
| `native_flow.type` | `"PER_SCREEN"` | `PER_SCREEN` / `SHARED_POOL`; xem [Kiểu native flow](#kiểu-native-flow). |
| `native_flow.pool.parity.refresh_overwrites_unshown` | `true` | Refresh thay cả ad chưa hiển thị. |
| `native_flow.pool.parity.retry_skipped_polite` | `false` | Load nền bị bỏ qua không chạy lại. |
| `native_flow.pool.parity.async_gate` | `true` | Trạng thái request đang chạy tính ngoài main thread. |
| `native_flow.pool.parity.async_selector` | `true` | Ad của slot được chọn ngoài main thread. |
| `native_flow.pool.parity.borrow_showing` | `true` | Slot được mượn ad đã hiển thị ở nơi khác. |
| `native_flow.pool.parity.destroy_replaced_ads` | `false` | Ad bị thay không bị destroy. |
| `native_flow.pool.parity.pager_slots_bind_resumed` | `false` | Trang pager bind khi start. |
| `native_flow.pool.locale_apply` | `"IN_PLACE"` | `IN_PLACE` / `RECREATE`. |
| `native_flow.pool.exit.next_screen_timing` | `"AFTER_AD"` | `AFTER_AD` / `UNDER_AD` cho lối thoát LFO và Goal. |
| `native_flow.pool.pager.offscreen_pages` | `1` | `1` giữ trang kế tiếp để prepare; giá trị lớn hơn giữ thêm trang kề bên. |
| `native_flow.pool.stuck_loading_log_ms` | `60000` | Chỉ log. |
| `native_flow.pool.view.destroy_on_teardown` | `true` | Hủy view ad khi màn đóng. |
| `native_flow.pool.lfo.confirm_button.reveal_delay_ms` | `3000` | Tương ứng `lfo.confirm_button.visible_before_selection` của `PER_SCREEN`. |
| `native_flow.pool.select.next_reveal_delay_ms` | `3000` | Nút Continue/Next của Privacy và Goal. |
| `native_flow.pool.lfo.loading_dialog.duration_ms` | `0` | Card "Loading languages" chặn LFO1; `0` là tắt. |
| `native_flow.pool.goals.exit_reuse_splash_inter` | `true` | Next ở Goal dùng interstitial splash còn lại. |
| `native_flow.pool.onboarding.fullscreen.skip.delay_ms` | `2000` | Tương ứng `onboarding.fullscreen.skip.delay_ms` (`5000`). |
| `native_flow.pool.onboarding.fullscreen.auto_next.delay_ms` | `10000` | Tương ứng `onboarding.fullscreen.auto_next.delay_ms` (`15000`), tính từ lúc chọn trang. |

`native_fs` chờ đúng lượt preload từ splash và hiện shimmer trong lúc chờ; vào màn không tạo request mới. Mặc định nút X hiện sau 3 giây kể từ khi bind ad. Màn không tự chuyển tiếp; key cũ `splash.native.auto_dismiss_ms` không còn tác dụng.

`interstitial_auto_buffer` là nhóm cấp cao nhất. Nhóm này chỉ điều khiển placements khai báo trong `InterstitialAutoBuffer` hoặc remote `rules`, trừ placements đã reserve. Host vẫn phải gọi `configure()` / `start()`; không field nào ở đây tự khởi động buffer hoặc tự show quảng cáo. Tắt một placement bằng `enabled: false` trong rule của nó. Các cấu hình interstitial khác vẫn nằm trong `interstitial`.

Interval chỉ bắt đầu từ một lần đóng ad, không bao giờ từ `start()`. Placement chưa có lần đóng nào sẽ preload ngay khi chỉ còn thiếu một tap. Tới lần show đầu tiên của placement trong một process, ngưỡng tap tính tối đa là một: placement preload không cần tap và thao tác đầu tiên show luôn, nên với `tap_threshold: 2` thì `inter_all` đầu tiên hiện ngay tap đầu, các lần sau mới ở tap thứ hai. Chỉ tính thời gian app ở foreground: đồng hồ đứng yên khi app ở background. Ad được mua sớm một thao tác, nên thao tác làm đủ `tap_threshold` show luôn, không phải chờ load nguội, khi interval đã qua. Inter thoát onboarding (`inter_after_ob3`) nằm ngoài buffer và không bao giờ bị chặn, nhưng lần đóng của nó bắt đầu interval của mọi placement trong buffer, nên `inter_all` / `inter_back` đầu tiên sau onboarding chờ đủ một interval tính từ lúc đó.

`interstitial_auto_buffer.rules.<placement>` trong remote hoặc asset app chỉnh placement mà host đã khai trong `configure()`, hoặc tắt nó bằng `enabled: false`. Rule cho placement host không khai vẫn đưa placement đó vào danh sách quản lý: ghi rõ `"enabled": true` thì buffer chạy nó, không ghi thì placement bị quản lý nhưng tắt, nên preload, load và show của nó đều bị bỏ qua với `DISABLED_CONFIG`. Predicate `isPlacementEnabled` của host vẫn áp dụng. Rule đóng gói sẵn trong SDK chỉ có `inter_all` và `inter_back`, hai placement được auto-buffer hiện nay; giá trị đóng gói sẵn không tự bật placement nào, nên host vẫn phải khai chúng.

## Force update

Parameter String riêng `force_update_config` điều khiển ngưỡng versionCode và bắt buộc/gợi ý cập nhật; không nằm trong hai document settings ở trên. Xem [setup, JSON, cache và tích hợp gate](force-update-integration.vi.md).

### Onboarding primary color

Đặt `onboarding.primary_color` trong `onboarding_config` thành `"#RRGGBB"` hoặc `"#AARRGGBB"` (ví dụ `"#1E88E5"`). Đây là màu dùng chung cho nút NEXT, nút Get Started cuối, indicator tiến độ đang chọn, nút tick/Done ở LFO và radio ngôn ngữ đang chọn ở LFO. Remote overrides the app asset; an empty value keeps the existing UI color. Native `ad_config.<placement>.colorCTA` colors both the CTA background and the Ad badge background.
