# Hai JSON cấu hình hành vi ads và onboarding

Giữ nguyên parameter Firebase `ad_remote_config` và assets `ad_config.json` / `ad_config_debug.json`. Thêm đúng hai parameter kiểu **JSON** trên Firebase Console; Android nhận qua `getString()`:

| Firebase parameter | File mặc định duy nhất |
|---|---|
| `ad_behavior_config` | [`ads/src/main/assets/ad_behavior_config.json`](../ads/src/main/assets/ad_behavior_config.json) |
| `onboarding_config` | [`onboardkitorigin/src/main/assets/onboarding_config.json`](../onboardkitorigin/src/main/assets/onboarding_config.json) |

Các file được đóng gói trong AAR/assets và chuyển thành map có kiểu dữ liệu trong generated Java khi build. Constructor không cần Context hoặc parse JSON để đọc mặc định. Build từ chối JSON sai cú pháp, sai schema hoặc chứa null. Không có bản XML hay Kotlin literal thứ hai phải đồng bộ.

## Coroutine và snapshot

- Firebase dùng `fetchAndActivate().await()`; chờ bằng suspension. Suite giữ scope theo process với `SupervisorJob` và `Dispatchers.Default`. Mỗi caller có timeout riêng; caller timeout/hủy không hủy fetch mà caller khác đang chờ. Không có `GlobalScope`.
- `fetchOnce()` giữ cache thành công theo process như trước. Explicit refresh của OB/app vẫn được fetch lại theo minimum interval Firebase và chia sẻ tác vụ đang chạy. Kết quả Firebase `false` nghĩa là không có activation mới, không phải fetch thất bại.
- Example nối `ObRemote.installFetchDelegate(RemoteConfigClient::fetchAndActivate)` tại Application để chia sẻ fetch giữa OB, ads, paywall và app flags. OnboardKit dùng riêng vẫn có adapter Firebase độc lập; không thêm dependency vòng tới suite-firebase.
- Remote JSON được parse/validate trên `Dispatchers.Default`, ghi cache trên `Dispatchers.IO`, sau đó publish snapshot qua `StateFlow`. Cùng nội dung không tạo snapshot mới. Payload UI kết hợp legacy được chuẩn bị trước khi publish để lần đọc đầu sau remote cũng không parse trên Main. Các callback chạm Android UI/ads chạy trên Main.
- Timer/backoff/AutoBuffer đọc dữ liệu đã parse; cấu hình OB được cache theo host config và snapshot. Không deserialize toàn bộ JSON mỗi lần đọc một timer.
- API bootstrap local và `applySnapshot`/`acceptSuccessfulFetch` đồng bộ của host giữ hợp đồng gọi hiện tại; các entry point đồng bộ này phải được gọi ngoài Main nếu host tự đưa JSON lớn. Đường fetch remote trong SDK dùng pipeline suspend ở trên. Snapshot/cache có revision guard để remote đang xử lý không ghi đè assignment host mới hơn.
- JSON giúp quản lý gọn, không tự giảm số request theo số field. Console kiểm tra cú pháp JSON; SDK vẫn kiểm tra từng type/range và fallback.

## Fallback và tương thích

- Constructor lấy mặc định local từ JSON. Giá trị app truyền qua constructor/setter vẫn là fallback của chính app.
- Remote chỉ thay những leaf có mặt và hợp lệ sau fetch + activate thành công. `false` và `0` không bị xem là thiếu; null, sai kiểu, enum sai hoặc số ngoài giới hạn bị bỏ qua theo từng field.
- Remote thiếu parameter hoặc xóa field trong một lần fetch thành công: bỏ assignment cũ tương ứng, trở lại local/legacy. JSON sai toàn bộ hoặc schema chưa hỗ trợ: giữ document remote hợp lệ gần nhất. Fetch thất bại/timeout: giữ snapshot hiện tại; lần chạy đầu dùng mặc định.
- Last successful document được lưu riêng trong SharedPreferences và khôi phục khi mở lại app. Đọc/ghi cache lỗi không làm mất giá trị mặc định trong bộ nhớ.
- Giữ các `ob_*` hiện có làm fallback. Leaf JSON mới hợp lệ ưu tiên hơn key cũ. Giữ `open_resume.app_resume_load_delay_ms` và các setter interval/click cap hiện tại làm fallback.
- Remote không cấp quyền consent/premium, không vượt `setCanRequestAds`, `AdsConfig.enabled=false` từ host, hoặc `flagUserEnableReload` runtime. AutoBuffer vẫn cần host gọi `start` ở màn content; app-open vẫn cần host tích hợp và ad unit.
- Debug tiếp tục dùng `ad_config_debug.json` cho ID. Hai document hành vi được fetch cả khi debug IDs đang được pin.
- Những timer đã lên lịch không bị restart khi thay remote. Screen/native preload giữ snapshot cho request/visit hiện tại; cấu hình mới áp ở lần đọc/request tiếp theo. AutoBuffer được đánh thức để tính lại lịch sau sync.

Có thể upload toàn bộ file mặc định, hoặc chỉ object chứa field muốn đổi. Hai file mô tả **SDK defaults**, không phải toàn bộ giá trị app example đang override. Upload một field là chủ động áp remote field đó, kể cả khi nó bằng SDK default nhưng khác giá trị host đã truyền.

Ví dụ chỉ sửa bốn hành vi:

```json
{
  "schema_version": 1,
  "splash": { "load": { "ad_strategy": "SAME_TIME", "lfo1_preload_mode": "PARALLEL" } },
  "onboarding": { "navigation": { "lock_pager_swipe": false }, "fullscreen": { "skip": { "delay_ms": 1500 } } }
}
```

## Phạm vi override

`ad_behavior_config.placement_overrides.<placement>.<format>` có thể ghi đè các trường sau:

| Format | Trường được đọc theo placement |
|---|---|
| banner | reload.*, presentation.* |
| native | load.tier_timeout_ms, reload.*, preload.*, presentation.* |
| interstitial | load.tier_timeout_ms, load_and_show.wait_timeout_ms, load_and_show.buffer_wait_timeout_ms, presentation.loading_enabled (dialog chờ fill), cache.max_age_ms |
| rewarded | load.tier_timeout_ms, cache.max_age_ms |

Các trường frequency, next-screen timing, pre-show delay, app-open và native cache TTL dùng nhóm format chung. Native/banner của OnboardKit có `behavior` theo slot; content/fullscreen có default chung trong `onboarding.ads.*_native_behavior`. Các slot interstitial nhận `load.tier_timeout_ms` ở preload và `load_and_show.wait_timeout_ms` ở explicit wait. Slot override > nhóm màn OB > placement override > format override > host/default. Callback next-screen được truyền tường minh vẫn quyết định timing của lời gọi đó.

`placement`/`native_placement` rỗng nghĩa là giữ ánh xạ host; chuỗi có giá trị trỏ tới key có sẵn trong ad_config, không chứa ad-unit ID. Mapping đã chỉ định nhưng tắt/không có ID sẽ không hồi sinh ad bằng fallback khác. App resume tiếp tục lấy ID từ `open_resume` trong ad_config; `app_resume.enabled` là gate của OB.

`onboarding.steps.<id>` nhận native switch/mapping/behavior theo id; template và progress indicator áp cho content step. Fullscreen/OB5 giữ layout chuyên dụng. Per-step fullscreen dùng `onboarding.steps.<id>.fullscreen.skip.{enabled,delay_ms,style}` và `.auto_next.{enabled,delay_ms}`, fallback về `onboarding.fullscreen`.

`ui.content.steps`, `ui.design_tokens.custom_colors`, `question.content.options` giữ schema payload UI cũ. Mảng rỗng hoặc string rỗng ở các phần nội dung/catalog tùy chọn giữ nội dung/resource do host cung cấp. `excluded_hosts` là danh sách tên class Activity đầy đủ, so khớp tên, bổ sung vào exclusion host; không dùng reflection để tạo Activity.

## Các trường đang có trong file mặc định

Thời gian dùng milliseconds, trừ API legacy có hậu tố Sec. `schema_version=1` là phiên bản parser; `revision` là metadata của document, không tự thay `ob_config_version` hay làm xóa cache legacy.

### `ad_behavior_config`

| Field | SDK default | Ghi chú |
|---|---|---|
| `schema_version` | `1` | Phiên bản schema đang hỗ trợ: 1. |
| `revision` | `0` | Metadata được lưu cùng document; không phải gate. |
| `global.ads_enabled` | `true` | Master mới toàn SDK; true vẫn cần host/consent/premium/slot cho phép. |
| `consent.network_timeout_ms` | `20000` | Timeout network UMP, áp lần request consent sau; không đóng form đang đọc. |
| `diagnostics.flow_logging_enabled` | `true` | OB_FLOW log; không đổi debug/test IDs. |
| `diagnostics.ads_telemetry_enabled` | `true` | false tắt nguồn telemetry ads; true vẫn giữ ownership chống report trùng. |
| `banner.reload.allowed` | `false` | Map canReloadAds; đây là policy có thể override, runtime flagUserEnableReload vẫn veto. |
| `banner.reload.auto_enabled` | `false` | Bật timer SDK; false không tự tắt reload-on-resume. |
| `banner.reload.interval_ms` | `15000` | >=1000. Canonical mới thay nhu cầu reloadIntervalSeconds. Không tự đọc field legacy 30s vốn no-op. |
| `banner.reload.resume_debounce_ms` | `500` | Debounce resume. |
| `banner.presentation.type` | `"NORMAL"` | NORMAL/LARGE_ANCHORED/COLLAPSIBLE/INLINE/INLINE_MAX_HEIGHT/FIXED. |
| `banner.presentation.collapsible_gravity` | `"BOTTOM"` | TOP/BOTTOM. |
| `banner.presentation.inline_style` | `"LARGE"` | Theo enum style SDK đã hỗ trợ. |
| `banner.presentation.inline_max_height_dp` | `50` | >=32; không tự chọn loại banner khi thiếu height. |
| `banner.presentation.fixed_size` | `"BANNER"` | BANNER/LARGE_BANNER/MEDIUM_RECTANGLE/FULL_BANNER/LEADERBOARD. |
| `native.load.tier_timeout_ms` | `30000` | Giữ timeout riêng đang cấu hình; provider OB cùng resolver format/slot. |
| `native.cache.max_age_ms` | `3600000` | Có thể giảm tuổi cache; không tăng quá lifetime hiện tại. |
| `native.reload.allowed` | `false` | Map canReloadAds; không là công tắc click reload. |
| `native.reload.on_ad_click` | `true` | Giữ mặc định theo slot; với OB tự next khi ad click return thì không tải ad thay thế vô ích. |
| `native.reload.resume_debounce_ms` | `500` | Debounce resume. |
| `native.reload.min_after_bind_ms` | `3000` | >0; cooldown sau bind. |
| `native.reload.timer_enabled` | `false` | Tách trạng thái timer khỏi interval để không tự bật khi chỉ đổi time. |
| `native.reload.interval_ms` | `15000` | >0; bị ràng buộc bởi min_after_bind_ms. |
| `native.preload.enabled` | `false` | Map setEnablePreload; không tắt preload chain OB qua global false không rõ scope. Chain OB có nhóm riêng. |
| `native.preload.after_show` | `false` | Map preloadAfterShow; chỉ một unused ad. |
| `native.presentation.auto_shimmer` | `true` | Tắt tự sinh shimmer; custom shimmer host vẫn là local. |
| `native.presentation.empty_visibility` | `"GONE"` | GONE/INVISIBLE. |
| `native.presentation.cta_corner_radius_dp` | `20` | Màu/height/position/components tiếp tục thuộc ad_remote_config. |
| `interstitial.load.tier_timeout_ms` | `30000` | Mỗi tier. |
| `interstitial.load_and_show.wait_timeout_ms` | `8000` | Budget đợi fill; không bao gồm chờ người dùng đóng ad. |
| `interstitial.load_and_show.buffer_wait_timeout_ms` | `5000` | UI wait khi tham gia AutoBuffer; vẫn giữ giới hạn 0..5000ms của API hiện tại. |
| `interstitial.load_and_show.allow_wait_for_auto_buffer` | `false` | Giữ opt-in buffer. |
| `interstitial.presentation.next_screen_timing` | `"AFTER_AD"` | AFTER_AD/UNDER_AD, map InterNextAction; screen/explicit call timing ưu tiên cao hơn. |
| `interstitial.presentation.loading_enabled` | `true` | Dialog loading khi chờ fill và chuẩn bị show; false không hủy yêu cầu ads. |
| `interstitial.presentation.pre_show_delay_ms` | `800` | Nối delay preparation; không đồng nhất timeout đợi fill. |
| `interstitial.frequency.interval_ms` | `0` | Giữ scope hiện tại AutoBuffer; không tự áp splash/OB. |
| `interstitial.frequency.max_clicks_per_24h` | `0` | 0=tắt; counter theo inter ad unit. |
| `interstitial.auto_buffer.enabled` | `false` | Remote chỉ chạy ở host content lifecycle đã tích hợp; không khởi động từ Application/splash. |
| `interstitial.auto_buffer.tick_ms` | `0` | 0 theo interval chung. |
| `interstitial.auto_buffer.idle_tick_ms` | `30000` | Cadence khi interval tắt. |
| `interstitial.auto_buffer.min_tick_ms` | `5000` | Sàn tick, giữ trần 30 phút hiện tại. |
| `interstitial.auto_buffer.preload_lead_ms` | `2000` | Khoảng preload sớm trước interval, giữ scope independent placements. |
| `interstitial.auto_buffer.rules` | `{}` | Map theo placement: {enabled, independent_interval, tap_threshold, interval_ms}; đầy đủ placements/independentIntervalPlacements/tapThresholds/intervalMsByPlacement. rules={} xóa remote rules, không xóa cấu hình host. |
| `interstitial.cache.max_age_ms` | `3600000` | Chỉ giảm so với lifetime hiện tại. |
| `rewarded.load.tier_timeout_ms` | `30000` | Giữ cache/request chung theo placement; không auto refill. |
| `rewarded.cache.max_age_ms` | `3600000` | Một unused fill; không thêm buffer_count/refill policy. |
| `app_open.enabled` | `true` | Remote gate bổ sung; không tự tạo ID hay kích hoạt khi host chưa tích hợp. |
| `app_open.load.background_delay_ms` | `2000` | Canonical mới; legacy nested field còn làm fallback. 0..86400000. |
| `app_open.load.timeout_ms` | `30000` | RESUME_FETCH_TIMEOUT_MS. |
| `app_open.load.max_background_requests` | `3` | Số request trong cửa sổ retry. |
| `app_open.load.background_retry_window_ms` | `120000` | Giới hạn cửa sổ retry. |
| `app_open.load.offline_recheck_ms` | `5000` | Cadence kiểm tra khi offline. |
| `app_open.load.failure_backoff_ms` | `[5000,30000,120000]` | Backoff theo số lần request app-open thất bại; chỉ nhận mảng số nguyên dương, tối đa 10 mức. |
| `app_open.presentation.loading_timeout_ms` | `3000` | Giới hạn loading resume. |
| `app_open.presentation.pre_show_delay_ms` | `800` | Delay trước show cached app-open. |
| `app_open.presentation.skip_after_ad_click` | `false` | Map policy click-return; explicit one-shot suppression từ host vẫn giữ. |
| `app_open.presentation.excluded_hosts` | `[]` | Tên class Activity đầy đủ; bổ sung exclusion, không tạo class bằng reflection. |
| `app_open.cache.max_age_ms` | `14400000` | Không tăng quá lifetime hiện tại. |
| `placement_overrides` | `{}` | Giá trị mặc định trong asset; chỉ leaf hợp lệ từ remote mới ghi đè. |

### `onboarding_config`

| Field | SDK default | Ghi chú |
|---|---|---|
| `schema_version` | `1` | Phiên bản schema đang hỗ trợ: 1. |
| `revision` | `0` | Metadata được lưu cùng document; không phải gate. |
| `flow.ads_enabled` | `true` | Thay ob_enable_all_ads; chỉ phạm vi OnboardKit. |
| `flow.skip_ad_only_steps_when_premium` | `true` | Bỏ trang chỉ chứa ads cho premium; không cho premium xem ads. |
| `flow.lock_portrait` | `true` | Phải tương thích manifest/local capability. |
| `flow.fullscreen_skip_style` | `"CLOSE_ICON"` | TEXT/CLOSE_ICON; default chung pager/OB5, per-step override. |
| `flow.system_bars.show_status` | `true` | SystemBarConfig. |
| `flow.system_bars.show_navigation` | `false` | Giữ default hiện tại. |
| `flow.system_bars.show_caption` | `true` | SystemBarConfig. |
| `splash.ads.banner.enabled` | `true` | Thay ob_ads_splash_banner_enabled; chưa có slot vẫn không tạo ad. |
| `splash.ads.banner.placement` | `""` | Chỉ key trong ad_remote_config/registry đã có; omit giữ mapping hiện tại. |
| `splash.ads.banner.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `splash.ads.interstitial.enabled` | `true` | Thay ob_ads_splash_inter_enabled. |
| `splash.ads.interstitial.placement` | `""` | Ad unit vẫn nằm trong ad_remote_config. |
| `splash.ads.interstitial.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `splash.ads.interstitial.old_user_placement` | `""` | Không khai báo vẫn fallback splash chung. |
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
| `splash.entries.notification.interstitial_placement` | `"inter_noti"` | Giữ mapping host khi rỗng; ID nằm trong ad_config. |
| `splash.entries.widget.interstitial_placement` | `"inter_widget"` | Giữ mapping host khi rỗng; ID nằm trong ad_config. |
| `splash.entries.uninstall.interstitial_placement` | `"inter_uninstall"` | Giữ mapping host khi rỗng; ID nằm trong ad_config. |
| `lfo.ads_enabled` | `true` | Master native LFO1+2 từ ob_ads_language_native_enabled. |
| `lfo.native_template` | `"CTA_BOTTOM"` | CTA_TOP/CTA_BOTTOM/COMPACT/... chỉ template SDK đã hỗ trợ; JSON positionCTA giữ ưu tiên khi dùng layout theo position. |
| `lfo.native1.enabled` | `true` | Công tắc của slot/step; vẫn qua gate host. |
| `lfo.native1.placement` | `""` | Không thay ID trong JSON này. |
| `lfo.native1.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `lfo.native2.enabled` | `true` | Có explicit field mới thì thay setting secondNative; host master ad gate vẫn veto. |
| `lfo.native2.placement` | `""` | enabled=false tắt slot dù có fallback ID. |
| `lfo.native2.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `lfo.native2.swap_wait_timeout_ms` | `8000` | Timeout giữ LFO1 nếu LFO2 chưa bind. |
| `lfo.native2.preload_trigger` | `"LFO_SHOWN"` | LFO_SHOWN hoặc FIRST_SELECTION. Không tắt hiển thị slot 2. |
| `lfo.tap_hint.enabled` | `true` | Thay ob_show_language_tap_hint. |
| `lfo.tap_hint.delay_ms` | `3000` | Thay ob_language_tap_hint_delay_sec. |
| `lfo.confirm_button.visible_before_selection` | `false` | Explicit remote mới true được override UI local false; missing giữ AND semantics cũ. |
| `lfo.confirm_button.save_on_back` | `true` | Back trước chọn vẫn inert. |
| `lfo.confirm_button.image_url` | `""` | Rỗng giữ drawable check hiện tại; URL lỗi giữ icon dự phòng. |
| `lfo.confirm_button.tint_color` | `""` | Rỗng giữ tint local; màu không parse được bị bỏ qua. |
| `lfo.confirm_dialog.enabled` | `true` | Thay ob_show_language_confirm_dialog. |
| `lfo.confirm_dialog.show_from_tap` | `4` | Số nguyên >=1; gồm tap lại selected item. |
| `lfo.confirm_dialog.native_enabled` | `true` | Thay ob_ads_language_confirm_native_enabled; false không tắt popup. |
| `lfo.confirm_dialog.native_placement` | `""` | Ad ID ở JSON ad units. |
| `lfo.confirm_dialog.native_preload_trigger` | `"DIALOG_OPEN"` | DIALOG_OPEN/LFO_SHOWN/FIRST_SELECTION; mặc định on-demand. |
| `lfo.confirm_dialog.native_behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `lfo.languages.supported_codes` | `[]` | Array mã ngôn ngữ được app hỗ trợ; [] trở về catalog local, không tạo picker rỗng. |
| `lfo.languages.default_code` | `""` | Phải nằm trong catalog hỗ trợ. |
| `lfo.exit.reuse_splash_inter` | `true` | Chỉ nhánh thoát LFO không vào pager. |
| `onboarding.navigation.lock_pager_swipe` | `true` | Giữ chính sách page eligibility hiện tại; false không tự mở swipe OB1 trong working tree. |
| `onboarding.navigation.swipe_completes_last_step` | `true` | Trong working tree còn cần !lock_pager_swipe. |
| `onboarding.navigation.back_navigates_back` | `true` | Giữ behavior Back hiện tại. |
| `onboarding.navigation.ad_click_return_completes_step` | `true` | Không bật cùng click replacement vô ích cho step đang tự next. |
| `onboarding.ads.content_native_enabled` | `true` | Thay ob_ads_content_native_enabled. |
| `onboarding.ads.fullscreen_native_enabled` | `true` | Thay ob_ads_fullscreen_native_enabled; master chung cả OB5, OB5 còn slot gate riêng. |
| `onboarding.ads.content_native_placement` | `""` | Fallback ID chung cho content. |
| `onboarding.ads.fullscreen_native_placement` | `""` | Fallback ID chung fullscreen. |
| `onboarding.ads.content_template` | `"CTA_TOP"` | Template chỉ tên đã hỗ trợ. |
| `onboarding.ads.content_native_behavior.reload.on_ad_click` | `false` | Mặc định riêng OB: không tải ad thay thế khi click-return sẽ rời step. |
| `onboarding.ads.fullscreen_native_behavior.reload.on_ad_click` | `false` | Mặc định riêng OB: không tải ad thay thế khi click-return sẽ rời step. |
| `onboarding.fullscreen.skip.enabled` | `true` | Thay showSkipButton && ob_show_skip_ob3. |
| `onboarding.fullscreen.skip.delay_ms` | `1000` | New >=0; legacy -1 kế thừa, không chuyển -1000 thành timer. |
| `onboarding.fullscreen.skip.style` | `"CLOSE_ICON"` | TEXT/CLOSE_ICON. |
| `onboarding.fullscreen.auto_next.enabled` | `true` | Không điều khiển OB5 standalone. |
| `onboarding.fullscreen.auto_next.delay_ms` | `3000` | Timer từ page selection, tính background như hiện tại. |
| `onboarding.steps.ob1.enabled` | `true` | Công tắc của slot/step; vẫn qua gate host. |
| `onboarding.steps.ob1.native_enabled` | `true` | Công tắc của slot/step; vẫn qua gate host. |
| `onboarding.steps.ob1.native_placement` | `""` | Giữ mapping host khi rỗng; ID nằm trong ad_config. |
| `onboarding.steps.ob1.native_template` | `""` | Rỗng kế thừa template content; không áp fullscreen. |
| `onboarding.steps.ob1.progress_visible` | `true` | Hiển thị indicator của content step. |
| `onboarding.steps.ob1.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `onboarding.steps.ob2.enabled` | `true` | Công tắc của slot/step; vẫn qua gate host. |
| `onboarding.steps.ob2.native_enabled` | `true` | Công tắc của slot/step; vẫn qua gate host. |
| `onboarding.steps.ob2.native_placement` | `""` | Giữ mapping host khi rỗng; ID nằm trong ad_config. |
| `onboarding.steps.ob2.native_template` | `""` | Rỗng kế thừa template content; không áp fullscreen. |
| `onboarding.steps.ob2.progress_visible` | `true` | Hiển thị indicator của content step. |
| `onboarding.steps.ob2.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `onboarding.steps.ob3.enabled` | `true` | Công tắc của slot/step; vẫn qua gate host. |
| `onboarding.steps.ob3.native_enabled` | `true` | Công tắc của slot/step; vẫn qua gate host. |
| `onboarding.steps.ob3.native_placement` | `""` | Giữ mapping host khi rỗng; ID nằm trong ad_config. |
| `onboarding.steps.ob3.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `onboarding.steps.ob4.enabled` | `true` | Công tắc của slot/step; vẫn qua gate host. |
| `onboarding.steps.ob4.native_enabled` | `true` | Công tắc của slot/step; vẫn qua gate host. |
| `onboarding.steps.ob4.native_placement` | `""` | Giữ mapping host khi rỗng; ID nằm trong ad_config. |
| `onboarding.steps.ob4.native_template` | `""` | Rỗng kế thừa template content; không áp fullscreen. |
| `onboarding.steps.ob4.progress_visible` | `true` | Hiển thị indicator của content step. |
| `onboarding.steps.ob4.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `onboarding.preload.initial_content_trigger` | `"FIRST_LANGUAGE_SELECTION"` | SPLASH_HANDOFF / LFO_SHOWN / FIRST_LANGUAGE_SELECTION. |
| `onboarding.preload.initial_content_count` | `2` | Số content native đầu được preload; 0 không preload nhóm đầu. |
| `onboarding.preload.next_step_enabled` | `true` | Preload step n+1 khi chọn step n. |
| `onboarding.preload.upcoming_fullscreen_enabled` | `true` | Preload fullscreen tiếp theo sớm từ content trước nó. |
| `onboarding.preload.ob5_on_last_step` | `true` | Warm OB5 khi tới cuối pager; vẫn cần OB5 bật. |
| `onboarding.preload.question_on_last_step` | `true` | Warm question khi tới cuối pager. |
| `onboarding.exit_interstitial.enabled` | `true` | Thay ob_ads_inter_after_ob3_enabled; AdsConfig.afterOnboardingInterstitialEnabled=false vẫn veto. |
| `onboarding.exit_interstitial.placement` | `""` | Mặc định app inter_after_ob3. |
| `onboarding.exit_interstitial.preload_on_entry` | `true` | Preload inter kết thúc OB khi vào pager. |
| `onboarding.exit_interstitial.wait_timeout_ms` | `8000` | Riêng inter cuối OB. |
| `onboarding.exit_interstitial.next_screen_timing` | `"UNDER_AD"` | AFTER_AD/UNDER_AD; entry đặc biệt vẫn AFTER_AD. |
| `onboarding.exit_interstitial.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `ob5.enabled` | `false` | Standalone chỉ mở nếu ad ready như hiện tại. |
| `ob5.native.enabled` | `true` | Slot veto riêng mới; false không bị fallback pool làm bật lại. |
| `ob5.native.placement` | `""` | Default app native_onboarding_fullscreen_1_4. |
| `ob5.native.behavior.reload.on_ad_click` | `false` | Mặc định riêng OB: không tải ad thay thế khi click-return sẽ rời step. |
| `ob5.skip.enabled` | `true` | ob_show_skip_ob5. |
| `ob5.skip.delay_ms` | `3000` | Tách override riêng với pager; legacy ob_skip_button_delay_sec áp cả hai như trước. |
| `ob5.skip.style` | `"CLOSE_ICON"` | TEXT/CLOSE_ICON. |
| `ob5.auto_dismiss_ms` | `15000` | Thời gian đóng OB5; tối thiểu 5000ms. |
| `question.enabled` | `true` | ob_enable_question. |
| `question.old_user_enabled` | `false` | ob_enable_question_old_user. |
| `question.native.enabled` | `true` | ob_ads_question_native_enabled. |
| `question.native.placement` | `""` | Không tự tích hợp question nếu app không cấu hình. |
| `question.native.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `question.native.template` | `"CTA_BOTTOM"` | questionTemplate. |
| `question.native.refresh_on_select` | `false` | Chỉ khi thêm selection. |
| `question.native.refresh_throttle_ms` | `2000` | Còn cooldown sau bind. |
| `question.interstitial.enabled` | `true` | ob_ads_question_inter_enabled. |
| `question.interstitial.placement` | `""` | Unit ở ad_remote_config. |
| `question.interstitial.behavior` | `{}` | Override tùy chọn; mặc định không có leaf override. |
| `question.selection.mode` | `"MULTIPLE"` | SINGLE/MULTIPLE. |
| `question.selection.min_count` | `1` | >=1, không vượt số option hợp lệ. |
| `question.content.title` | `""` | Tiêu đề survey; rỗng giữ local. |
| `question.content.cta_text` | `""` | Nhãn CTA survey; rỗng giữ resource local. |
| `question.content.options` | `[]` | Giá trị mặc định trong asset; chỉ leaf hợp lệ từ remote mới ghi đè. |
| `app_resume.enabled` | `true` | Gate OB ob_ads_app_resume_enabled; còn cần ad_behavior app_open gate/host init. |
| `ui.enabled` | `true` | Thay ob_enable_ui_content. |
| `ui.content.steps` | `[]` | Giá trị mặc định trong asset; chỉ leaf hợp lệ từ remote mới ghi đè. |
| `ui.design_tokens.custom_colors` | `[]` | Giá trị mặc định trong asset; chỉ leaf hợp lệ từ remote mới ghi đè. |

## Những field vẫn local hoặc đã không còn consumer

Các nhóm dưới đây giữ ở code hoặc schema cũ; không tạo thêm parameter Firebase cho chúng.

- ID/tiers, isEnable, UA gate và màu/height/position/components CTA vẫn thuộc ad_config; không nhân bản sang hai JSON mới.
- Resource/layout IDs, Activity classes, callbacks, consent/premium authority, SDK/MMP tokens, debug/test devices, child/privacy settings và runtime ownership của ad giữ ở code.
- Legacy `ob_pass_lfo_if_completed`, preloadBuffer/preloadOnResume, AutoBuffer backoffMs/maxBackoffMs cũ và các counter/API lịch sử không còn consumer không được hồi sinh chỉ để có remote field.
- Không thêm timeout riêng cho banner tier: loader hiện không có timer đó. `splash.timing.banner_wait_ms` điều khiển Splash chờ banner; `banner.reload.interval_ms` điều khiển refresh SDK.
- Native cache và inter/reward cache không tăng quá 1 giờ; app-open không tăng quá 4 giờ. Delay Skip/X bằng 0 vẫn hợp lệ. Giữ lối thoát bắt buộc nếu cả skip và auto-next đều tắt.

## Kết nối

`ERainAd.init` / `AdRemoteConfig.initializeFromAssets` nạp ad_behavior; `OnboardingSdk.install` nạp onboarding. `FirebaseAdConfigSource` và `ObRemote.sync` cập nhật hai document bằng giá trị có nguồn REMOTE sau fetch thành công. Không cần thêm hai parameter nếu chưa muốn điều khiển từ Firebase: SDK vẫn chạy từ file mặc định và host options.
