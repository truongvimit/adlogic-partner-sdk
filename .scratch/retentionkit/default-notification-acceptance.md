# Reopened default-notification acceptance

Base: `2865f6d50c23b94b0d54b801aa89ad0fe7c1b365`. Branch: `codex/retentionkit`. Status: scoped normal-default device GREEN below; ticket17 wake implementation and ticket18 final integrated acceptance still pending.

The user reports no notifications during ordinary example use and requires the common Noti documents, actual physical awake/lockscreen testing, and simple integration for every partner. Tickets15–18 supersede prior acceptance for these requirements. Prior QA-injected engine tests and reboot-followed-by-launch smoke are historical, not proof of default delivery or BOOT-only restore.

## Confirmed RED

`python3 /Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-default-fix/normal_open_loop.py red-normal-launcher-cold`

Actual Pixel5/API34, installed debugsha `6a8791f651f58bc83dd9d413bb355a0a3ebd83551871ba76fd1718ca6d61ae58`. Cold force-stop/relaunch retains app data, guarantees no in-memory QA profile, opens real Splash, closes actual GMA test ad and waits until Main is visible. Normal persisted SDK state: setup=true, onboarding=false, NON_SUBSCRIBER, configrevision0; permission/channel enabled. Expected Reminder7105; actualzero active app notifications, exit1. Earlier stable-Main repeat and realHome departure also emitted actual Trackkit `retention_noti_skipped` / `cooldown` for Reminder/Pinned/AppExit.

Evidence: [cold default RED](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-default-fix/red-normal-launcher-cold/result.json), [physical baseline](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-default-fix/physical-baseline.json). Inactive debugfixture prefs exist from historical runs; their presence is not runtime provenance and is never used as proof of normal defaults.

## Canonical decisions

- Source: original Noti folder; all5 original file hashes reverified unchanged. [Exact source table and corrections](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-default-fix/canonical-audit.md).
- First-day unfinished setup is suppressed. The old additional24h AFTER setup has no canonical basis; remove it for COMMON_PLAN, preserve explicit partner configuration/legacy preset.
- Quiet Reminder appears when a completed, permitted, verified-free foreground session becomes ready;15m repeat cooldown. Ready transitions cannot be lost simply because ProcessForeground preceded setup/Billing/permission readiness.
- Daily08/19; Lockscreen11:30/17/20 device localtime; Winback14–45days and3lifetime. Fresh users must not get invented Winback expectations.
- Lockscreen asks20s wake, at most2 attempts for one pending message, stop on close/swipe/open/channeloff/subscriber. Best-effort public platform capability requires actual device observation; inexact RTC_WAKEUP alone is CPU wake only.
- BOOT restores schedules before user opens app; transient entitlementUNKNOWN must stay fail-closed without permanently losing an otherwise valid due occurrence.
- Source-specific caps/priority remain; undocumented restrictive caps must not be presented as canonical. Anti-spam operational defaults must be labelled and configurable.
- Preserve standard Splash/Onboard ad/Main/feature/native, generic content, durable selected token, permission choice, authoritative Billing, shared Firebase and Trackkit. Simplification must remove repeated host sequencing, not weaken these contracts.

## Scoped physical GREEN (2026-09-07)

All runs below are actual Pixel5/API34, ordinary Application/SDK/Billing readiness, config revision0 with no overrides. No QA broadcast or synthetic ad/departure event; no device clock/settings change. Existing setup/business data/widget17 retained. These early checkpoints do not certify ticket17 or the eventual final artifact.

| Checkpoint | Actual result | Evidence under `retentionkit-default-fix/` |
| --- | --- | --- |
| Engine15, root `c40d18d`, installed APK SHA `8bc82534925a6694a76f897e6112d81e1f57294632452e279923439a4cb2c51f` | Same cold-launcher RED loop now PASS: Reminder7105, silent channel importance2, no sound/vibration; Pinned7106 followed guard | `green-engine15-normal-launcher/result.json`, `engine15-normal-tray/headers.json` |
| Engine15 | Actual Later dismisses Reminder only, leaves Pinned, no app navigation | `engine15-normal-later/result.json` |
| Suite16 production `5ac3102`, installed SHA `5c64106aad442b695a9ae8d9170ded41e74064547f491849de4cccafc2318823` | Cold open restores Pinned; Reminder correctly suppressed within its real15m cooldown | `suite16-first-normal-open/result.json` |
| Suite16 | Home→return before departure delay: no Exit/AdReturn | `suite16-home-return/result.json` |
| Suite16 | Actual GMA TEST banner click→Chrome, actual ad_click telemetry, AD_RETURN7104 posted4s later | `suite16-real-ad-return/result.json` |
| Suite16 | Actual Pinned Notes tile→Splash→real interstitial→Main→Notes; native_noti actual loaded | `suite16-pinned-notes-2/result.json` |
| Suite16 | Actual Home from Notes, APP_EXIT7108 posted; later eligible foreground had Reminder7105 again | `suite16-normal-app-exit/result.json` |
| Suite16 | Actual APP_EXIT Action now→Splash→real interstitial→Main→Notes; native_noti actual loaded | `suite16-app-exit-cta/result.json` |

The first pinned-tap harness (`suite16-pinned-notes`) stopped before tapping because content-description Notes matches both tile and standard action. The corrected run selects the actual tile resource ID. This is a harness ambiguity, not an app failure. AD_RETURN was observed posted, then expired naturally before its tap was attempted; its CTA route is not claimed as covered by that run.

## Pending evidence

Final integrated artifact normal-default regression; normal first setup; actual OS-calendar locked/awake/channeloff/wake lifecycle; reboot before launcher; final source SDK/app tests, R8 and selective POM closure; independent Standards/Spec review. Root retains ADB ownership and restores device settings/business data/widget17. External PR08 remains a distinct GitHub permission limitation.
