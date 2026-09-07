# Retention notifications

A standalone core-only module for common retention notifications. No ads, Firebase, widgets, Play review, WorkManager or Compose dependency. Install in `Application.onCreate`; core owns process lifecycle, durable config and feature routing.

## Installation and common entry chain

```kotlin
val notifications = RetentionNotifications(RetentionNotificationOptions(
    smallIconRes = R.drawable.ic_notification,
    preset = NotificationPreset.COMMON_PLAN,
))
```

`COMMON_PLAN` is the default. `LEGACY_SDK` preserves the original SDK cadence and `rk_retention_<campaign>` channel IDs; it is **not** a Translate preset. Pick the preset when installing. Remote patches override individual policy values, without silently switching channel identity.

Supply localized, app-neutral feature IDs/labels/descriptions and a router targeting the host's real Splash. All body/action entry intents must follow **Splash → existing entry interstitial completed or legitimately skipped → Main actually resumed → destination**. Use the umbrella's standard entry integration with OnboardKit for the common chain; direct-module partners own the equivalent lifecycle handoff. `recordOpened()` only records an accepted entry, and never navigates or consumes it. Dismiss/X/Later is an internal broadcast that cannot open UI. Every action uses a distinct immutable direct Activity PendingIntent; no notification trampoline exists.

The host owns permission prompts, genuine setup completion, authoritative entitlement, consent/ads and real feature functionality. No-IAP hosts may explicitly set `NON_SUBSCRIBER`; `UNKNOWN` and subscribers suppress all marketing. The default setup-specific onboarding grace is **24 elapsed hours from installation**, while other campaigns use a separate **24 hours from setup completion**. Both durations are configurable; elapsed time is not a calendar-day boundary. Abandonment remains active/unfinished only. Entry taps never bypass unfinished setup.

## Shared profile and source precedence

Primary references: the common `Notification System Implementation & Test Plan` (complete MD plus DOCX image tables), the newer MO lockscreen v1.0 document (2026-08-17), `Noti.pdf`, and `NOTIFICATION_GUIDE.pdf`. Generic templates and priorities follow the main plan; the detailed MO default skip logic takes precedence over its earlier repeat-wake overview. Source-app labels/art are examples. The detailed audit, source hashes and exact page references are in `SDKOptimize/retentionkit-correction/noti-spec-audit.md` outside this repository.

| Campaign / fixed SDK ID | Common trigger | Delivery TTL | Cooldown / daily cap |
|---|---|---:|---:|
| `daily` / 7101 | 08:00, 19:00 local, background | 1 h | 1 h / 2 |
| `winback` / 7102 | 11:00, 14:00 local; inactivity **14–45 days inclusive** | 1 h | 1 h / 2; **3 lifetime reservations** |
| `onboarding` / 7103 | Active unfinished setup, after install grace, confirmed background +3 s | 5 min | 24 h / 1 |
| `ad_return` / 7104 | Genuine foreground ad click, then confirmed departure +3 s | 5 min | 15 min / 2 |
| `reminder` / 7105 | Foreground/open; quiet, Later dismisses this occurrence | 6 h | **15 min** / 4 |
| `pinned` / 7106 | Foreground/open; quiet four-feature extension, skip while visible | 6 h | 15 min / 2 |
| `lockscreen` / 7107 | **11:30, 17:00, 20:00**, background | 1 h to deliver; **no posted timeout** | 1 h / 3 |
| `app_exit` / 7108 | Completed setup, no qualifying ad click, confirmed departure +3 s | 5 min | 15 min / 2 |

The main plan's “Kill App” test also covers a no-click departure; its PDF/reference implementation describes ad-click departure. `APP_EXIT` supplies the former as an observable generic campaign. One departure arms one campaign; `AD_RETURN` wins when a valid click exists. There is no OS kill callback. A hard kill before background scheduling is unobservable. Common exits save a one-shot inexact alarm before the in-process timer; process death after saving can recover the same occurrence, with every gate rechecked. Foreground, config changes, subscriber/unknown entitlement, host UI and external transitions cancel pending exits. Timer completion and OS delivery race against the same saved envelope and claim. Onboarding remains session-only; restart does not invent an active setup scope.

| Channel | Families | New-channel defaults |
|---|---|---|
| `lock_screen_alerts` | lockscreen | HIGH, public generic content |
| `updates_news` | winback, ad_return, app_exit, onboarding | HIGH |
| `daily_tips` | daily | DEFAULT, silent/no vibration |
| `reminders` | reminder | LOW, silent |
| `rk_retention_pinned` | pinned extension | LOW, silent |

Daily sound/HUN conflicts between source attachments; the shared default conservatively follows the quiet guide. Existing channels are never deleted/recreated or upgraded to evade user choices. `channelIds` overrides preserve a migrated app's exact existing channel choices. Existing SDK IDs7101–7107 stay stable; the source1001-series IDs were suggestions, not forced migration IDs.

Arbitration: lockscreen > Updates group > daily > reminder. A due, eligible, uncapped higher-priority scheduled occurrence wins even if a lower-priority receiver arrives first. Existing Updates posts block another Updates post until dismissed/opened. A durable **30-second guard** prevents cross-family bursts; this numeric value is a chosen configurable default because the main document leaves it blank. Lower-priority occurrences are skipped, not queued for burst replay. Initial pinned creation also spends the guard; an already-visible pinned surface is skipped without a new post. A single foreground refresh therefore cannot create both a reminder and pinned notification within the guard. Per-family cooldowns/caps still apply; neither priorities nor the guard can bypass permission, entitlement, phase or TTL.

The exact slots, two-day new-user cohort, replacement default false, three-second departure and reminder15min are known reference values. Winback14–45d and max3 are explicit PDF requirements. TTLs, other cooldowns/daily caps, post-setup24h grace, guard30s and token5min bounds are chosen SDK defaults, not falsely attributed to Translate. `LEGACY_SDK` keeps ≥48h unbounded inactivity, winback12h cooldown/1 per day/no lifetime cap, onboarding grace0, no shared guard/arbitration, one-hour posted lock timeout, in-process ad delay and APP_EXIT disabled. That48h inactivity was the original SDK choice, not Translate's two-day cohort.

## Complete configuration contract

All keys below use the `notifications.` prefix. Version1, `enabled=true`; all eight common campaigns enabled. Strict lowercase booleans; unknown keys/malformed values reject the entire patch and keep the last good revision. Empty slots disable that schedule.

| Keys | Common default | Accepted values |
|---|---|---|
| `profile_version` | `1` | exactly1 |
| `enabled`, `arbitration.enabled`, `app_exit.durable`, `lockscreen.persistent` | `true` | boolean |
| `setup_grace_ms`, `onboarding.grace_ms` | `86400000` each | 0–365 days in ms |
| `new_user_days` | `2` | 0–365 elapsed days from install |
| `winback.inactivity_ms`, `winback.max_inactivity_ms` | `1209600000`, `3888000000` | 0–365 days; maximum0 means unbounded, otherwise ≥minimum |
| `guard_window_ms` | `30000` | 0–365 days; 0 disables guard |
| `background_delay_ms` | `3000` | 1–60000, shorter than both token TTLs |
| `ad_return.token_ttl_ms`, `onboarding.token_ttl_ms` | `300000` each | 1–300000 |
| `lockscreen.replace` | `false` | boolean |
| `daily.slots`, `winback.slots` | `08:00,19:00`; `11:00,14:00` | ≤24 unique `HH:mm` values |
| `lockscreen.slots`, `lockscreen.new_user_slots` | `11:30,17:00,20:00` each | same; first2 elapsed days use new-user slots |
| `<campaign>.enabled` | `true` | boolean |
| `<campaign>.ttl_ms`, `.cooldown_ms`, `.daily_cap` | campaign table | TTL1ms–7d; cooldown0–365d; cap1–50 |
| `<campaign>.lifetime_cap` | winback3; others0 | 0–10000; 0 disables this limit |

Use core's cached PATCH API; absent keys preserve existing values, explicit removal restores the selected preset's default. No second Firebase client is initialized. `NotificationLegacyConfig.keys` exposes exact legacy fetch keys and `map(presentValues)` returns `NotificationConfigMigration(overrides, unsupportedKeys)`; `translate()` remains a source-compatible alias. Feed actual fetched **present** values to this mapper, then use the same config owner to apply a single combined patch.

Exact aliases: `notiDailyEnabled→daily.enabled`, `noti_daily_slots→daily.slots`, `notiWinbackEnabled→winback.enabled`, `noti_winback_slots→winback.slots`, `notiLockscreenEnabled→lockscreen.enabled`, **`noti_lockscreen_slots→lockscreen.slots`**, **`noti_lockscreen_slots_new→lockscreen.new_user_slots`**, **`notiLockscreenReplace→lockscreen.replace`**, `notiClickedAdsEnabled→ad_return.enabled`, `notiOnOpenEnabled→reminder.enabled`, `noti_new_user_days→new_user_days`. No undocumented `notiKillAppEnabled` alias is invented. Wake seconds, premium marketing, arbitrary remote image/content JSON and keys belonging to other modules are reported unsupported here, not silently accepted.

## Content and lockscreen lifecycle

The generic default catalogue combines **different campaign framing** with the real feature label/description; EN/VI strings are bundled. Daily uses BigText. Winback/exit use BigPicture with a bounded local image (a bundled generic illustration is the fallback); exits have independent collapsed “Before you go” and expanded feature titles. Lockscreen includes title/body/image/CTA/X; pinned has distinct feature tiles. Supply local `NotificationContent` for app copy/art, including `expandedTitle`, or decorate with `NotificationRenderer`. All supplied destinations must be allowlisted features. Default CTA is Action now/Mở ngay. Custom views must use the supplied PendingIntents; the SDK restores main/delete/standard actions and checks all gates again after renderer and storage boundaries.

Common lockscreen selection is random excluding the previous recorded content; rotation survives restart and is not bound to a slot. A single item may repeat. Other families cycle deterministically. Failed/blocked rendering or notify does not advance rotation. Default replacementfalse leaves an untouched OS notification in place across slot/day boundaries. No repeat alert, midnight reset or one-hour posted timeout occurs. Replacementtrue replaces the same family ID at eligible slots and rotates. X/swipe cancels only the matching occurrence; ordinary app foreground also dismisses the old lockscreen. Delivery TTL only controls whether a queued alarm may first post. The persistent lockscreen PendingIntent uses core's reusable-template mechanism so a days-old visible notification still creates a fresh, once-consumed entry when tapped; selected feature validation still applies.

The MO document explicitly asks dev to report feasibility for its20s wake request. `ACQUIRE_CAUSES_WAKEUP` is deprecated; `TURN_SCREEN_ON` is intended for home automation and is not a normal generic-app permission. A legacy wake lock may work on some devices, but cannot offer a portable all-app20s guarantee. The SDK uses **OS-controlled presentation**, records `wake_capability=os_controlled` on lockscreen submission, and does not fake compliance with a background Activity launch. No wake lock/full-screen intent/exact alarm/FGS workaround is included. [Android PowerManager](https://developer.android.com/reference/android/os/PowerManager.html), [TURN_SCREEN_ON](https://developer.android.com/reference/android/Manifest.permission#TURN_SCREEN_ON), [AOSP permission declaration](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml), checked2026-09-07.

## State, scheduling and migration limits

The owned `notifications.state.v1` namespace atomically reserves occurrence, daily/lifetime capacity and guard before notify. A known thrown notify releases its reservation; a crash/receipt-storage failure leaves unknown delivery and conservatively spent capacity. Lifetime counts do not disappear with the eight-day occurrence ledger, a new day or process restart. Successful submission advances rotation and global guard. `PostSubmitted(receiptPersisted)` describes a returned notify call, never a visible impression. Config revision, permission, channel/group, entitlement, host transition, setup phase, time bounds, cap, active-group state and rotation are rechecked; a stale alarm cannot revive a removed config.

Calendar identities remain stable per family/local slot; handled dates prevent backward-clock duplication. Reboot/package replacement/clock/timezone reconciliation rearms desired records from cold local configuration. `GregorianCalendar` works on API24: nonexistent DST time shifts forward; repeated time chooses standard-time occurrence. `setWindow(RTC_WAKEUP, due, 10min, ...)` requests an **inexact** window: RTC_WAKEUP is0 and can wake CPU, not screen. Android/Doze/OEM restrictions can defer longer. Force-stop suspends execution until user interaction. No scheduling/display timing SLA is claimed. Alarm fallback after process death is also inexact;3s is only the live timer's intended confirmation delay.

Disable and cancel the old engine's **known owned** alarm identities before migration. Do not delete user-disabled channels. Existing eight-day attempts/cooldowns and7101–7107 identities are retained; new lifetime limits import retained submitted/uncertain attempts once before pruning, without resetting known capacity; already-pruned legacy historical sends cannot be reconstructed. Cross-process storage writers remain unsupported. Active Process progress/FGS and battery-optimization UI from the source plan belong to real host jobs, not these marketing campaigns.

Validation: `:retention-notifications:testDebugUnitTest :retention-notifications:assembleRelease --max-workers=2`; unit assertions cover both profiles, Android channels/templates/actions, permission/subscriber/setup, caps/guard/concurrency, durable exit/replay/cancellation, old callbacks, lockscreen lifetime/rotation, calendar DST/timezone/reboot-style restore and real failure boundaries. Device/full Splash acceptance belongs to the parent correction run; this module's test result does not claim physical timing or ad presentation.
