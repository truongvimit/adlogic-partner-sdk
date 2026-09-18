# Cấu hình ads và hành vi onboarding

**OB catalog:** `ob1..ob4` → `native_ob1..4`; `full1/full2` → `native_full1/2`. Default: `ob1, full1, ob2, full2, ob3, ob4`. All eligible OB natives preload on language selection. Remote `onboarding.order` selects/reorders app-declared steps. [Configuration and migration / Hướng dẫn chi tiết](onboarding-flow.vi.md). `native_fs` remains the separate splash native.

`onboarding.order` là danh sách duy nhất chọn và sắp xếp màn trong JSON; bỏ ID để bỏ màn. Không cần khai báo `steps` nếu không có tùy chỉnh riêng. `steps.<id>.enabled` đã bỏ và bị bỏ qua; `order` hợp lệ cũng ưu tiên hơn các cờ cũ `ob_enable_step_ob1..4`. `steps.<id>` chỉ dành cho template, behavior và fullscreen tùy chọn. Bật/tắt ads từng vị trí bằng `ad_config.<placement>.isEnable`: content vẫn hiện khi ads tắt, màn fullscreen không có ads sẽ được bỏ qua.

[English](remote-settings.md) · [Tiếng Việt](remote-settings.vi.md) · [हिन्दी](remote-settings.hi.md)

SDK giữ nguyên Firebase `ad_remote_config`, assets `ad_config.json` / `ad_config_debug.json`. Hai parameter mới là **String chứa object JSON**. Xem [các bước publish trên Firebase](firebase-integration.vi.md#remote-json) và [tạo hai file local custom default](firebase-integration.vi.md#local-defaults). Các [file mẫu](examples/ads-onboarding/) khớp default SDK.

Dùng **version SDK mới nhất** trên [JitPack](https://jitpack.io/#truongvimit/adlogic-partner-sdk) cho mọi module. Chỉ thêm key Firebase không nâng cấp SDK cũ đã tích hợp trong app.

| Parameter | File mặc định trong SDK |
|---|---|
| `ad_behavior_config` | `ads/src/main/assets/ad_behavior_config.json` |
| `onboarding_config` | `onboardkitorigin/src/main/assets/onboarding_config.json` |

## Mỗi giá trị có một nơi quản lý

- **ad_config:** `id`, `ids`, `isEnable`, `enable_ua_check`, `reloadIntervalSeconds`, `colorCTA`, `heightCTA`, `positionCTA`, `components`, `open_resume.app_resume_load_delay_ms`. Hai JSON mới không khai báo lại các field này, mapping ad unit hoặc công tắc từng ad unit.
- **ad_behavior_config:** timeout/cache, policy reload/preload, frequency/AutoBuffer, consent timeout, telemetry, bo góc CTA native và hành vi app-open. Banner type/size là preset định dạng quảng cáo của SDK; không chứa resource/layout của app.
- **onboarding_config:** bật/tắt bước của luồng, skip/X delay, auto-next, swipe/back/click-return, chiến lược splash, thời điểm preload LFO/OB và hành vi exit/question; template native, kiểu nút X/Skip, hình/màu nút xác nhận LFO và lựa chọn ngôn ngữ trong catalog của app. Bật bước không bật lại placement đang `isEnable=false` trong ad_config.
- **Code/resource của app:** reference `R.layout`, `R.drawable`, `R.string`, layout custom của trang, catalog/resource ngôn ngữ, progress indicator, system bars/orientation và Activity exclusions. Các preset trình bày quảng cáo có sẵn trong SDK vẫn được remote điều khiển; không cần truyền resource ID qua JSON.

`app_open.presentation.excluded_hosts`, `app_open.enabled`, `app_open.load.background_delay_ms`, `banner.reload.interval_ms`, mọi `placement`/`native_placement`, các switch ad unit và nhóm payload nội dung app `ui`/`question.content` đã được bỏ khỏi schema mới. Payload/cached payload còn các field này được bỏ qua; không ghi đè nơi quản lý chính.

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
| Splash người dùng cũ | `<key splash interstitial>_o` (`inter_splash_o`) |
| LFO1 / LFO2 / dialog | `native_lang` / `native_lang_alt` / `native_popup_lang` |
| Content OB1 / OB2 / OB3 / OB4 | `native_ob1` / `native_ob2` / `native_ob3` / `native_ob4` |
| Fullscreen Full1 / Full2 | `native_full1` / `native_full2` |
| OB5 | `native_onboarding_fullscreen_1_4` |
| Question native/interstitial | `native_question` / `inter_question` |
| Exit interstitial | `inter_after_ob3` |
| App resume | `open_resume` |

### Slot dưới màn splash

Splash có đúng một slot quảng cáo dưới thanh loading, và `splash.ads.slot_format` chọn format nào
lấp vào:

| Giá trị | Placement | Key trong ad_config |
| --- | --- | --- |
| `BANNER` (mặc định) | `AdPlacement.SplashBanner` | `banner_splash` |
| `NATIVE` | `AdPlacement.SplashInlineNative` | `native_splash` |

Mỗi lần mở app chỉ request đúng format đã chọn, nên đổi cờ này là dịch chuyển doanh thu chứ không
thêm impression thứ hai. Thời gian chờ cũng dùng chung: `splash.timing.banner_wait_ms` giới hạn cho
format nào đang load, và `ob_ads_splash_banner_enabled` tắt vị trí này cho cả hai.

Native dùng khung media-left cố định nên `positionCTA` và thứ tự `components` không có gì để tác
động — `colorCTA` và `heightCTA` vẫn áp dụng. `AdPlacement.SplashInlineNative` khác
`AdPlacement.SplashNative` — cái sau vẫn là native full-screen tuỳ chọn (`native_fs`) hiện sau
inter splash. Trong code cờ này là `io.onboardkit.config.SplashAdSlotFormat`, còn ad unit resolve
vào `AdsConfig.splashInlineNative`.

**Nâng lên 5.4.0.** `AdPlacement` là sealed interface và bản này thêm `SplashInlineNative` vào đó,
nên `when (placement)` exhaustive của bạn — hay gặp nhất là khi tự implement
`OnboardingAdProvider` — sẽ không compile cho tới khi thêm nhánh cho placement mới. Ngoài ra không
vỡ gì: `AdsConfig.splashInlineNative` có giá trị mặc định nên mọi lời gọi constructor hiện tại giữ
nguyên, và `slot_format` mặc định vẫn là `BANNER` nên app không đụng gì thì hành vi y như cũ.



App dùng key khác chỉ khai báo association đó một lần trong code:

```kotlin
ads = AdsConfig.fromAdConfig(mapOf(
    AdPlacement.Language1 to "my_language_native",
    AdPlacement.StepNative(StepId("custom")) to "my_content_native",
))
```

Đổi ID, tiers, switch, màu/chiều cao/vị trí CTA và components vẫn thực hiện ở ad_config. Chọn preset template SDK và bo góc CTA dùng hai JSON mới như bảng dưới. Association này cũng dùng để tìm `ad_behavior_config.placement_overrides.<key>`; LFO1 mặc định tìm `native_lang`, không nhầm với key telemetry/buffer nội bộ `language1`. API `AdsConfig(...)` truyền raw ID vẫn được giữ cho host tự quản lý units; khi dùng API đó SDK không đoán association từ các ID có thể trùng nhau.

## Local JSON và fallback

**App không cần tạo file nếu dùng default SDK.** Khi cần custom, partner có thể đặt `app/src/main/assets/ad_behavior_config.json` / `onboarding_config.json` cùng tên để Android merge thay asset SDK. Có thể dùng JSON thưa, chỉ chứa field cần đổi; field còn thiếu lấy SDK defaults. Một custom asset được xem là assignment local tường minh cho các field hợp lệ có mặt, kể cả false/0. Bản asset nguyên mẫu SDK giữ constructor/setter của host làm fallback.

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

- Remote hợp lệ sau fetch/activate thành công > custom local asset > constructor/setter của host > SDK defaults. Các field hành vi không yêu cầu partner set lại bằng code.
- Thiếu parameter/field sau fetch thành công: xóa assignment remote cũ tương ứng, trở về local/legacy. Null, sai type/enum/range bị bỏ qua theo field; false và 0 hợp lệ được giữ.
- Fetch lỗi/timeout, JSON hỏng hoặc schema chưa hỗ trợ: giữ snapshot hợp lệ gần nhất. Lần đầu chưa có remote dùng local/default. Cache lưu SharedPreferences, khôi phục ở lần chạy sau; lỗi cache không làm mất default trong bộ nhớ. Fetch lỗi không ép local ghi đè remote cache hợp lệ. Muốn bỏ override, publish `{}` hoặc `{"schema_version":1}` rồi fetch thành công; không dùng String rỗng. Đổi asset local cần build và khởi động lại process.
- Defaults SDK sinh từ chính asset khi build, dùng được trước Context; không có bản Kotlin/XML cần đồng bộ cho các field thuộc hai JSON. Build từ chối null/sai schema.
- Consent, premium, `setCanRequestAds`, `AdsConfig.enabled=false`, runtime pause reload và lifecycle vẫn có quyền chặn. AutoBuffer cần host `start`; JSON không tự tích hợp host hoặc tạo Activity.
- Key `ob_*` cũ tiếp tục là fallback/tương thích. Custom UI legacy qua `ob_ui_content`/`ob_ui_design_tokens`/`ob_question_config` vẫn theo API cũ, không được nhân bản sang hai JSON mới. Các API remote nội dung cũ vẫn sử dụng được; reference layout/resource và nội dung mặc định do app khai báo. Không nhầm nhóm nội dung trang này với template/style của quảng cáo trong hai JSON mới.
- Debug giữ ad IDs trong ad_config_debug, vẫn nhận hai JSON hành vi. Timer/request đang chạy giữ thời điểm đã capture; lần đọc/request sau nhận cấu hình mới. `ALTERNATE` chờ bước remote kết thúc/timeout rồi mới request ads splash. `SAME_TIME` chỉ có thể request banner/interstitial splash sớm hơn; **LFO1 được lên lịch preload sau remote ở cả hai strategy**. LFO `PARALLEL` nghĩa là không đợi interstitial splash tải xong. Strategy được đọc lúc vào splash; giá trị vừa fetch áp dụng ở lượt splash sau.

## Scope của behavior

Slot behavior > nhóm content/fullscreen OB > placement override > format override > local/default. Các object `behavior` trống là scope tùy chọn cho hành vi chưa có trong ad_config, không chứa ad IDs/switch/UI resources.

| Format trong placement_overrides | Field được hỗ trợ |
|---|---|
| banner | reload.allowed, reload.auto_enabled, reload.resume_debounce_ms, presentation.* |
| native | click.action, load.tier_timeout_ms, reload.*, preload.*, presentation.auto_shimmer, presentation.empty_visibility, presentation.cta_corner_radius_dp |
| interstitial | load.tier_timeout_ms, load_and_show.wait_timeout_ms, load_and_show.buffer_wait_timeout_ms, presentation.loading_enabled, cache.max_age_ms |
| rewarded | load.tier_timeout_ms, cache.max_age_ms |

Slot interstitial OB hỗ trợ tier timeout và wait timeout. Frequency, next-screen timing, pre-show delay, app-open và native cache TTL ở scope format chung. Per-step fullscreen cho phép `onboarding.steps.<id>.fullscreen.skip.{enabled,delay_ms,style}` và `.auto_next.{enabled,delay_ms}`. `onboarding.steps.<id>.native_template` cũng áp dụng cho ID trang custom; chuỗi rỗng kế thừa template nhóm.

Banner reload cadence lấy `ad_config.<key>.reloadIntervalSeconds` nếu là số dương; thiếu/sai dùng giá trị host (mặc định SDK 15000ms). Khai báo interval không tự bật timer. App-open delay chỉ lấy `open_resume.app_resume_load_delay_ms`, mặc định 2000ms. Không thêm banner tier timeout khi loader chưa có timer đó.

## Hành động khi click native

`click.action` nhận `auto_next`, `none` hoặc `reload`. SDK chốt đúng một hành động ở callback click/open đầu tiên, giữ nguyên đến khi quay về kể cả remote thay đổi giữa chừng.

- `reload`: request ad thay thế ngay lúc click/open, không đợi resume và không có delay cố định. Khi quay về, dùng ad đã tải hoặc chờ đúng request đang chạy. Resume app thông thường không kích hoạt click reload.
  Trong lúc chờ vẫn hiển thị ad cũ, không hiện shimmer. Chỉ thay khi ad mới bind thành công; tải lỗi giữ ad cũ và khung quảng cáo. Shimmer chỉ dùng lúc tải ban đầu chưa có ad.
- `auto_next`: quay về thì chuyển trang onboarding đang hiển thị, không tải ad thay thế. Ở LFO2: tự confirm ngôn ngữ đã chọn. Ở LFO1: chọn ngôn ngữ hiện tại/mặc định để sang LFO2.
- `none`: giữ ad và trang hiện tại; không reload theo click, không tự chuyển trang.

Mặc định LFO1/LFO2 và mọi native khác là `reload`; các trang content/fullscreen trong pager onboarding là `auto_next`. Native splash riêng và OB5 là `reload`. Trong example, `ob1..ob4` là content; `full1/full2` là fullscreen. Pager OB không reload: remote `reload` được xử lý như `none`.

Cấu hình chung/placement trong `ad_behavior_config` qua `native.click.action` hoặc `placement_overrides.<key>.click.action`. Với `onboarding_config`, dùng scope từng màn/nhóm bên dưới hoặc `onboarding.steps.<id>.behavior.click.action`. Một `click.action` hợp lệ được khai báo tường minh sẽ thắng cả cờ cũ `reload.on_ad_click` và `navigation.ad_click_return_completes_step`: không thể vừa auto-next vừa click reload. Timer/resume refresh và timeout tự chuyển trang fullscreen là các cài đặt riêng.

Ví dụ override trong `onboarding_config` (LFO2 mặc định là `reload`; ví dụ đổi sang tự confirm):

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

## Template và CTA để UA/MO thử nghiệm

- LFO1/LFO2: `lfo.native_template` (SDK default `CTA_BOTTOM`). Content OB: `onboarding.ads.content_template` (`CTA_TOP`), có thể override từng `onboarding.steps.<id>.native_template` (mặc định `""`, nghĩa là kế thừa). Question: `question.native.template` (`CTA_BOTTOM`).
- Thứ tự chọn frame: template tường minh của trang > template tường minh của nhóm/màn > `ad_config.<key>.positionCTA` (`TOP`/`BOTTOM`) > template host/SDK. “Tường minh” gồm remote hợp lệ hoặc custom local asset; bản asset SDK nguyên mẫu không tự ghi đè cấu hình cũ. Khi thử riêng `positionCTA`, bỏ override template tương ứng. `positionCTA` không bị sao chép thành một field mới.
- Các preset `CTA_TOP`, `CTA_BOTTOM`, `COMPACT` dùng cho native nội dung; field template chung cũng nhận `FULL_SCREEN`/`DIALOG` như API trước. Riêng native trong popup LFO luôn dùng `DIALOG`, native ad-only Full1/Full2/OB5 luôn dùng `FULL_SCREEN` để giữ khung chứa tương ứng. Custom `R.layout` của trang vẫn ở app.
- Preload và show dùng chung bộ chọn template. Nếu host chủ động preload sớm hoặc remote được refresh sau preload, native được inflate theo template hiện hành tại bind, tái sử dụng ad đã tải. Đây không phải thứ tự splash mặc định: LFO1 luôn được lên lịch sau bước remote. Một ad đang hiển thị giữ view hiện tại đến lần bind tiếp theo.
- `flow.fullscreen_skip_style`, `onboarding.fullscreen.skip.style`, `onboarding.steps.<id>.fullscreen.skip.style`, `ob5.skip.style` nhận `CLOSE_ICON`/`TEXT`. Scope cụ thể ưu tiên scope chung, rồi cấu hình local; thời gian X/Skip và auto-next không đổi khi chỉ đổi style. Default các style khai báo sẵn là `CLOSE_ICON`.
- `native.presentation.cta_corner_radius_dp` mặc định `20` dp, có thể override theo placement hoặc scope native từng màn. Áp dụng khi CTA có màu nền tường minh từ `colorCTA`/`NativeAdStyle.ctaBackgroundColor`; màu `default` giữ drawable XML như trước.
- `lfo.confirm_button.image_url` / `tint_color`: mặc định `""`, giữ icon/màu XML. URL ảnh lỗi dùng icon check của SDK, màu không hợp lệ bị bỏ qua. Đây là nút xác nhận LFO; CTA quảng cáo vẫn dùng field của ad_config.
- `lfo.languages.supported_codes` mặc định `[]`: giữ catalog app/SDK; mã không có trong catalog bị loại, kết quả rỗng trở về catalog. `lfo.languages.default_code` mặc định `""`: giữ lựa chọn mặc định cũ; chỉ thay khi mã thuộc catalog app.

Các field chỉ thuộc app đã loại khỏi nhóm UI 26 field là `flow.lock_portrait`, ba field `flow.system_bars.*` và `onboarding.steps.ob1/ob2/ob4.progress_visible`. Chúng tiếp tục dùng `BehaviorConfig`, `SystemBarConfig`, `ContentStepDefinition.showsProgressIndicator` với default cũ. Payload nội dung `ui.*` / `question.content.*` không nhân bản sang JSON mới; nội dung UI remote cũ vẫn qua `ob_ui_content`, `ob_ui_design_tokens`, `ob_question_config`, và toggle `ob_enable_ui_content`. Text nút tiếp tục của question dùng `QuestionConfig.ctaTextRes` trong app, độc lập với CTA quảng cáo.

## Fetch và publish

Firebase `fetchAndActivate().await()` chia sẻ tác vụ đang chạy qua delegate suite/example; timeout/cancellation của caller không hủy caller khác. Parse/validate trên Default, persist trên IO, publish snapshot trước khi flow dùng cấu hình; các notification chạm ads/UI chạy Main. Preset template/Skip và style ads đọc snapshot tại điểm sử dụng; payload nội dung trang qua API remote UI cũ vẫn có luồng publish riêng.

## Toàn bộ defaults

Thời gian dùng milliseconds, trừ `reloadIntervalSeconds` trong ad_config và API legacy hậu tố Sec. schema_version=1, revision chỉ là metadata. Object rỗng nghĩa là không thêm override cho scope đó.

### ad_behavior_config

| Field | SDK default | Ghi chú |
|---|---|---|
| `schema_version` | `1` | Phiên bản schema đang hỗ trợ: 1. |
| `revision` | `0` | Metadata được lưu cùng document; không phải gate. |
| `global.ads_enabled` | `true` | Master mới toàn SDK; true vẫn cần host/consent/premium/slot cho phép. |
| `consent.network_timeout_ms` | `20000` | Timeout network UMP, áp lần request consent sau; không đóng form đang đọc. |
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
| `native.click.action` | `"reload"` | Hành động độc quyền: `auto_next`, `none`, `reload`. |
| `native.reload.on_ad_click` | `true` | Legacy fallback; `click.action` tường minh được ưu tiên. |
| `native.reload.resume_debounce_ms` | `500` | Debounce resume. |
| `native.reload.min_after_bind_ms` | `3000` | >0; cooldown sau bind. |
| `native.reload.timer_enabled` | `false` | Tách trạng thái timer khỏi interval để không tự bật khi chỉ đổi time. |
| `native.reload.interval_ms` | `15000` | >0; bị ràng buộc bởi min_after_bind_ms. |
| `native.preload.enabled` | `false` | Map setEnablePreload; không tắt preload chain OB qua global false không rõ scope. Chain OB có nhóm riêng. |
| `native.preload.after_show` | `false` | Map preloadAfterShow; chỉ một unused ad. |
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
| `app_open.load.failure_backoff_ms` | `[5000,30000,120000]` | Backoff theo số lần request app-open thất bại; chỉ nhận mảng số nguyên dương, tối đa 10 mức. |
| `app_open.presentation.loading_timeout_ms` | `3000` | Giới hạn loading resume. |
| `app_open.presentation.pre_show_delay_ms` | `800` | Delay trước show cached app-open. |
| `app_open.presentation.skip_after_ad_click` | `false` | Map policy click-return; explicit one-shot suppression từ host vẫn giữ. |
| `app_open.cache.max_age_ms` | `14400000` | Không tăng quá lifetime hiện tại. |
| `placement_overrides` | `{}` | Override theo key ad_config; chỉ các field hành vi được hỗ trợ. |

### onboarding_config

| Field | SDK default | Ghi chú |
|---|---|---|
| `schema_version` | `1` | Phiên bản schema đang hỗ trợ: 1. |
| `revision` | `0` | Metadata được lưu cùng document; không phải gate. |
| `flow.ads_enabled` | `true` | Thay ob_enable_all_ads; chỉ phạm vi OnboardKit. |
| `flow.skip_ad_only_steps_when_premium` | `true` | Bỏ trang chỉ chứa ads cho premium; không cho premium xem ads. |
| `flow.fullscreen_skip_style` | `"CLOSE_ICON"` | Kiểu X/Skip chung; không thay đổi delay/auto-next. |
| `splash.ads.slot_format` | `"BANNER"` | Chọn định dạng cho slot dưới splash: `BANNER` hoặc `NATIVE`. |
| `splash.ads.banner.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `splash.ads.native.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `splash.ads.interstitial.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `splash.timing.min_display_ms` | `3000` | Giữ legacy <=0 fallback local; canonical mới >=0, 0 được ghi rõ là không giữ minimum. |
| `splash.timing.ad_budget_ms` | `60000` | Budget chung sau notification/focus, không phải timeout tier. |
| `splash.timing.banner_wait_ms` | `0` | Đợi render trước inter, giới hạn bởi ad budget. |
| `splash.timing.notification_settle_ms` | `0` | Đệm sau notification result. |
| `splash.load.ad_strategy` | `"ALTERNATE"` | SAME_TIME/ALTERNATE với fetch remote; capture trước khởi động attempt, mới fetch chỉ áp attempt sau. |
| `splash.load.lfo1_preload_mode` | `"SEQUENTIAL"` | PARALLEL/SEQUENTIAL so với inter splash; độc lập ad_strategy. |
| `splash.load.remote_fetch_timeout_ms` | `10000` | Đang fetch không tự đổi timeout cho chính lần fetch đó; lần sau dùng cache. |
| `splash.load.consent_hook_timeout_ms` | `20000` | Riêng hook tùy biến; UMP timeout nằm trong ad_behavior_config.consent. |
| `splash.load.billing_timeout_ms` | `5000` | Capture trước khi billing step chạy. |
| `splash.permissions.no_internet_prompt_enabled` | `true` | Giữ mặc định gating hiện tại. |
| `splash.permissions.notification_enabled` | `true` | Vẫn giữ granted/đã hỏi/manifest/OS checks. |
| `splash.navigation.next_screen_timing` | `"AUTO"` | AUTO/AFTER_AD/UNDER_AD; entry noti/widget/uninstall vẫn bảo đảm AFTER_AD. |
| `splash.native.skip.delay_ms` | `3000` | Native splash trước LFO.
| `splash.native.skip.style` | `"CLOSE_ICON"` | Native splash trước LFO.
| `splash.native.auto_dismiss_ms` | `15000` | Native splash trước LFO.
| `splash.native.behavior.click.action` | `"reload"` | Request ad thay thế ngay khi click/open. |
| `lfo.native_template` | `"CTA_BOTTOM"` | Preset layout native SDK cho LFO1/LFO2; xem thứ tự ưu tiên template. |
| `lfo.native1.behavior.click.action` | `"reload"` | Request ad thay thế ngay khi click/open. |
| `lfo.native2.enabled` | `true` | Bật/tắt hành động đổi sang native thứ hai sau chọn ngôn ngữ; không bật lại ad unit bị tắt. |
| `lfo.native2.behavior.click.action` | `"reload"` | Request ad thay thế ngay khi click/open. |
| `lfo.native2.swap_wait_timeout_ms` | `8000` | Timeout giữ LFO1 nếu LFO2 chưa bind. |
| `lfo.native2.preload_trigger` | `"LFO_SHOWN"` | LFO_SHOWN hoặc FIRST_SELECTION. Không tắt hiển thị slot 2. |
| `lfo.tap_hint.enabled` | `true` | Thay ob_show_language_tap_hint. |
| `lfo.tap_hint.delay_ms` | `3000` | Thay ob_language_tap_hint_delay_sec. |
| `lfo.confirm_button.visible_before_selection` | `false` | Explicit remote mới true được override UI local false; missing giữ AND semantics cũ. |
| `lfo.confirm_button.save_on_back` | `true` | Back trước chọn vẫn inert. |
| `lfo.confirm_button.image_url` | `""` | Rỗng giữ drawable check hiện tại; URL lỗi giữ icon dự phòng. |
| `lfo.confirm_button.tint_color` | `""` | Rỗng giữ tint local; màu không parse được bị bỏ qua. |
| `lfo.confirm_dialog.enabled` | `true` | Thay ob_show_language_confirm_dialog. |
| `lfo.confirm_dialog.show_from_tap` | `4` | Số nguyên >=1; chỉ gate khi chọn ngôn ngữ khác. Chọn lại ngôn ngữ hiện tại mở popup ngay nhưng vẫn cộng count. |
| `lfo.confirm_dialog.native_preload_trigger` | `"DIALOG_OPEN"` | DIALOG_OPEN/LFO_SHOWN/FIRST_SELECTION; mặc định on-demand. |
| `lfo.confirm_dialog.native_behavior.click.action` | `"reload"` | Request ad thay thế ngay khi click/open. |
| `lfo.languages.supported_codes` | `[]` | Rỗng giữ catalog; mã lạ bị loại, lọc rỗng trở về catalog. |
| `lfo.languages.default_code` | `""` | Rỗng giữ lựa chọn cũ; chỉ nhận mã có trong catalog app. |
| `lfo.exit.reuse_splash_inter` | `true` | Chỉ nhánh thoát LFO không vào pager. |
| `onboarding.navigation.lock_pager_swipe` | `false` | Giữ chính sách page eligibility hiện tại; false không tự mở swipe OB1 trong working tree. |
| `onboarding.navigation.swipe_completes_last_step` | `true` | Trong working tree còn cần !lock_pager_swipe. |
| `onboarding.navigation.back_navigates_back` | `true` | Giữ behavior Back hiện tại. |
| `onboarding.navigation.ad_click_return_completes_step` | `true` | Legacy fallback; `click.action` tường minh được ưu tiên. |
| `onboarding.ads.content_template` | `"CTA_TOP"` | Preset chung cho native các trang content OB. |
| `onboarding.ads.content_native_behavior.click.action` | `"auto_next"` | Tự chuyển trang khi quay về từ ad; không click reload. |
| `onboarding.ads.fullscreen_native_behavior.click.action` | `"auto_next"` | Tự chuyển trang khi quay về từ ad; không click reload. |
| `onboarding.fullscreen.skip.enabled` | `true` | Thay showSkipButton && ob_show_skip_ob3. |
| `onboarding.fullscreen.skip.delay_ms` | `5000` | New >=0; legacy -1 kế thừa, không chuyển -1000 thành timer. |
| `onboarding.fullscreen.skip.style` | `"CLOSE_ICON"` | Kiểu X/Skip của trang fullscreen trong OB. |
| `onboarding.fullscreen.auto_next.enabled` | `true` | Không điều khiển OB5 standalone. |
| `onboarding.fullscreen.auto_next.delay_ms` | `15000` | Timer từ page selection, tính background như hiện tại. |
| `onboarding.order` | Thứ tự app khi thiếu | Array chọn/sắp xếp catalog; `[]` bỏ pager. |
| `onboarding.preload.ob5_on_last_step` | `true` | Warm OB5 khi tới cuối pager; vẫn cần OB5 bật. |
| `onboarding.preload.question_on_last_step` | `true` | Warm question khi tới cuối pager. |
| `onboarding.exit_interstitial.enabled` | `true` | Bật/tắt hành động interstitial cuối OB; unit vẫn phải được ad_config cho phép. |
| `onboarding.exit_interstitial.preload_on_entry` | `true` | Preload inter kết thúc OB khi vào pager. |
| `onboarding.exit_interstitial.wait_timeout_ms` | `8000` | Riêng inter cuối OB. |
| `onboarding.exit_interstitial.next_screen_timing` | `"UNDER_AD"` | AFTER_AD/UNDER_AD; entry đặc biệt vẫn AFTER_AD. |
| `onboarding.exit_interstitial.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `ob5.enabled` | `false` | Standalone chỉ mở nếu ad ready như hiện tại. |
| `ob5.native.behavior.click.action` | `"reload"` | Request ad thay thế ngay khi click/open. |
| `ob5.skip.enabled` | `true` | ob_show_skip_ob5. |
| `ob5.skip.delay_ms` | `3000` | Tách override riêng với pager; legacy ob_skip_button_delay_sec áp cả hai như trước. |
| `ob5.skip.style` | `"CLOSE_ICON"` | Kiểu X/Skip của màn OB5 standalone. |
| `ob5.auto_dismiss_ms` | `15000` | Thời gian đóng OB5; tối thiểu 5000ms. |
| `question.enabled` | `true` | ob_enable_question. |
| `question.old_user_enabled` | `false` | ob_enable_question_old_user. |
| `question.native.behavior.click.action` | `"reload"` | Request ad thay thế ngay khi click/open. |
| `question.native.template` | `"CTA_BOTTOM"` | Preset native màn question. |
| `question.native.refresh_on_select` | `false` | Chỉ khi thêm selection. |
| `question.native.refresh_throttle_ms` | `2000` | Còn cooldown sau bind. |
| `question.interstitial.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `question.selection.mode` | `"MULTIPLE"` | SINGLE/MULTIPLE. |
| `question.selection.min_count` | `1` | >=1, không vượt số option hợp lệ. |

`interstitial.auto_buffer` được chuyển thành nhóm cấp cao nhất `interstitial_auto_buffer`, mặc định `enabled: true`. Cập nhật remote config và asset tùy chỉnh của host sang key mới; SDK không còn đọc key cũ. Nhóm này chỉ điều khiển placements khai báo trong `InterstitialAutoBuffer` hoặc remote `rules`, trừ placements đã reserve. Host vẫn phải gọi `configure()` / `start()`; bật field này không tự khởi động buffer hoặc tự show quảng cáo. Các cấu hình interstitial khác vẫn nằm trong `interstitial`.

## Force update

Parameter String riêng `force_update_config` điều khiển ngưỡng versionCode và bắt buộc/gợi ý cập nhật; không nằm trong hai document settings ở trên. Xem [setup, JSON, cache và tích hợp gate](force-update-integration.vi.md).
