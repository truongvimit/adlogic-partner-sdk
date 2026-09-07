# 09 Physical Pixel acceptance follow-up

Type: task
Status: claimed
Blocked by: none (requires exclusive unlocked device for remaining UI work)

Spec: ../spec.md

## Scope and acceptance

Run the existing and manual RetentionKit example flows on the attached unlocked Pixel 5. Preserve user data, unrelated widgets and concurrent source changes. Fix any reproducible defect in a private implementer worktree, merge with a merger, retain failures and record exact tested source/APK identity. Do not call engine-driven receiver injection an OS alarm wake check or report platform-controlled outcomes as completed.

## Answer

See `../physical-acceptance.md`. The first physical run exposed a debug QA clock rollback, fixed at95181844 and merged993c376 with four RED/GREEN regressions. Physical4356938 recorded15/15 passing Android cases and additional widget/feedback manual checks. Source7cb23c3 now includes another user task's Ads correction; its affected unit/build checks are separate from the still-installed4356938 APK. Concurrent Home/Back/launcher/ad input interrupted manual feedback; one original driver PASS was explicitly excluded. Final exclusive-device install/rerun/manual matrix remains pending. This ticket is not resolved and no full physical acceptance is claimed.
