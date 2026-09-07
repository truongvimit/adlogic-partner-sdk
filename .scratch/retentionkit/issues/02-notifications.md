# 02 Notification campaigns and scheduling

Type: task
Status: resolved
Blocked by: 01

Spec: ../spec.md

## Scope and acceptance

Own retention-notifications/**. Implement all seven spec campaign families and standard renderer/customization, separate channel gates, stable multi-action PendingIntents, dismiss receiver, inactive winback, expiring delayed-ad-return and onboarding abandonment, persisted rotation/budgets, skip/replace lockscreen, calendar scheduler/reconcile/boot/update/time/TTL, cached config and migration API. Read Translator notifications source as reference, not copy unchanged. No exact alarm/FSI/FGS permissions. Tests include gate transitions, stale revision/disabled callbacks, slot reduction, async failure, process restore, click destinations and scheduling edge cases. Do not edit shared core without parent agreement; depend on its contract.

Follow the shared implementation contract and acceptance ledger in spec.md. Commit only owned files; write implementation/test results under Answer and change Status to resolved when verified. Provide commit hashes and concise context pointers for the merger.

## Comments

Implementation complete in `e49bdb2` on `codex/retentionkit-notifications`. Final core `6eab586` was incorporated through merger commit `ad94dc16` before final validation.

## Answer

- Added `RetentionNotifications`, all seven campaign families, complete validated profile v1 defaults, per-channel permission/user/lifecycle gates, standard localized templates, bounded bundled imagery, custom synchronous local renderer and explicit Activity PendingIntent actions. Reminder/Later, four pinned tiles, inactive winback, unfinished onboarding phase, expiring ad-return tokens, lockscreen skip/replace and durable rotation are implemented.
- Calendar `setWindow(RTC_WAKEUP)` scheduler uses stable slot identities, saved desired revisions/due/TTL and handled civil dates. It reconciles boot, own update, time/timezone, config and relaunch; stale callbacks cannot revive removed slots. Calendar/DST logic is API24-compatible without java.time/desugaring. No exact-alarm, full-screen, wake-lock or foreground-service permission.
- Claim, capacity, cooldown and rotation use one transactional module namespace. Failed notify releases the reservation; a crash/receipt failure remains outcome-unknown and conservatively reserves budget. OS occurrence metadata prevents stale dismiss callbacks from cancelling replacements and keeps Later working after a failed disk receipt. Subscriber/unknown/phase changes withdraw ineligible owned notifications. Telemetry callbacks run outside module locks, with truthful `retention_noti_*` names.
- Public integration and customization contract: `retention-notifications/CONTRACT.md`. Full keys/defaults, migration mapping, state/crash semantics and platform limits: `retention-notifications/README.md`.

Validation on 2026-09-07, worktree `02-notifications`:

```text
./gradlew :retention-notifications:testDebugUnitTest :retention-notifications:assembleRelease :retention-notifications:lintRelease --no-daemon --console=plain --max-workers=2
BUILD SUCCESSFUL
35 tests: 26 behavior + 5 calendar + 4 Android adapter, 0 failed/error/skipped
lintRelease: No issues found.
```

Tests cover seven actual templates (including RemoteViews inflation), API24/34 adapters, denied/granted-later permission, disabled channel, existing channel preservation, subscriber/setup/onboarding phases, winback inactivity, foreground reminder/pinned, distinct action routes, captured/open dedupe, stale/replaced/cancelled delay callbacks, TTL, reduced slots, restart/durable disabled profile, duplicate/concurrent cap, clock rollback/DST/timezone, lockscreen active across midnight, renderer late config/permission/premium, failed renderer/post/claim/receipt storage, unknown crash claim, catalogue changes and event-sink isolation/reentrancy. Library release AAR is assembled; internal receiver/permission manifest assertions pass. `git diff --cached --check` passed.

Physical ADB/Doze/OEM behavior, integrated sample/minified app and final selective publication verification remain explicitly owned by Tickets06–08; this ticket does not claim those have passed. No ADB, push, remote publication, main-repo edits or reference-app edits were performed.
