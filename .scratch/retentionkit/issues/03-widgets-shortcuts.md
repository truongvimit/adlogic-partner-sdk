# 03 Widget templates and owned shortcuts

Type: task
Status: resolved

Spec: ../spec.md

## Scope and acceptance

Own retention-widgets/**. Implement localized 3–4 action grid template provider, configurable feature catalogue/custom rendering seam, invitation/pin coordinator with actual confirmation callback, installed-instance state/update/resize/delete/restore and direct Activity PendingIntent routes. Provide owned dynamic feature shortcut helper that never deletes other app/module IDs. Handle API24/25/26 support, launcher unsupported, user cancel/unknown, multi-instance and process startup, scoped host UI suppression. Include meaningful tests of callback semantics/identity/restore and actual resource inflation. No ad dependency.

Follow the shared implementation contract and acceptance ledger in spec.md. Commit only owned files; write implementation/test results under Answer and change Status to resolved when verified. Provide commit hashes and concise context pointers for the merger.

## Comments

Claimed in codex/retentionkit-widgets. Implementing against compiling core contract a665e00; final validation will include the core concurrency/entitlement follow-up.

## Answer

Implemented in `490261d`, after final core was merged at `32d09efa` (core tree `6eab586`). Only `retention-widgets/**` and this ticket are owned/changed.

- `retention-widgets/README.md`: exact facade API, options/config keys, typed route contract, platform evidence/limitations and provider customization.
- `RetentionWidgets.kt`: core module lifecycle, main-thread serialized effects, per-instance selection/size/update/restore/delete, immutable direct Activity PendingIntents, config-revision guards and disable reconciliation.
- `WidgetPinCoordinator.kt` + `WidgetPinReceiver`: bounded weak-Activity invitation, last lease recheck before tokenized external handoff, persisted random callback capabilities, new installed ID/provider verification, duplicate/restart/unknown/late callback semantics. Explicit non-exported one-shot mutable callback permits the launcher's `EXTRA_APPWIDGET_ID`; widget clicks remain immutable. Transition finish clears only this request owner.
- `StandardWidgetRenderer.kt` + `rk_widget_grid` / `rk_widget_row`: real localized three/four-action RemoteViews, resize behavior and custom renderer fallback. English/Vietnamese built-ins; shared catalogue provides host-localized feature labels/icons.
- `FeatureShortcuts.kt`: exact persistent ownership ledger, API25+ dynamic feature shortcuts with shared typed envelopes, per-launcher-activity quota accounting for foreign+manifest IDs and one reserved slot by default. No global replace/remove operation and no deletion of other owners or user widgets.

Validation: `./gradlew :retention-widgets:testDebugUnitTest :retention-widgets:assembleRelease --max-workers=2 --console=plain` **PASS**, 26 tests, zero failures/errors/skips; log `/tmp/retentionkit-03-final.log`. Tests exercise API24/25/26/34, actual PendingIntent fill-in extra delivery and receiver validation, process reinstallation callback, duplicate/unsupported/failure/unknown/late callbacks, scoped suppression, dialog dismissal/pause, real resource inflation/localization, instance identity/resize/restore/delete, custom provider/renderer/stale config and shortcut ownership/quota. `git diff --check` passed.

No ADB/device claim in this module ticket. OEM launcher placement and API36 actual taps remain integrating-app smoke coverage. Optional vendor busy gating and SDK-dialog lease suppression are the explicitly planned ticket05 core/suite adapter, not a widget ad dependency.
