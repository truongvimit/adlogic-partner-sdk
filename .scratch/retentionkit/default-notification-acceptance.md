# Reopened default-notification acceptance

Base: `2865f6d50c23b94b0d54b801aa89ad0fe7c1b365`. Branch: `codex/retentionkit`. Status: implementation in progress; no new GREEN/default/wake acceptance yet.

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

## Pending evidence

Fresh default foreground/background physical loop GREEN; normal first setup; actual OS-calendar locked/awake/channeloff/wake lifecycle; reboot before launcher; final source SDK/app tests, R8 and selective POM closure; independent Standards/Spec review. Root retains ADB ownership and restores device settings/business data/widget17. External PR08 remains a distinct GitHub permission limitation.
