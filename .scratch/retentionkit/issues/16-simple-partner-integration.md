# Simplify partner installation and refactor example

Type: task
Status: claimed
Blocked by: none
Base: 2865f6d

Provide deep optional suite integration facade replacing duplicate bridges/hooks with small documented install and entry integration. Refactor actual example to use it, maintain generic content and real business actions. Preserve Splash -> Onboard entry ad -> Main -> feature, authoritative Billing/shared Firebase/Ads/Trackkit and partner selective modules. Own retentionkit facade/integration and app, facade and app tests/docs. Plan outside repo integration-plan.md.

## Acceptance

Evidence must distinguish ordinary production defaults from explicit synthetic engine fixtures. Prior corrected acceptance is historical and does not prove this reopened request.

## Comments

Claimed by audit_caller_translator in private simple-integration worktree. Root-approved interface/ownership plan: /Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-default-fix/integration-plan.md. Root owns map and merge; ticket15 owns core/notification readiness and the current exclusive Gradle slot. No ADB.

## Answer

Implemented the optional `RetentionSuite` preset and `RetentionSplashActivity`. The preset composes authoritative Billing, existing Onboard/Ads control, Trackkit and shared Firebase/common legacy configuration once. It automatically binds real Main and final `RetentionFeatureHost` lifecycles: exact token capture, restored materialization, new-Intent replacement, bounded readiness/focus retries, alias validation and final consume. Main retains the original task stack and handles SDK feedback internally. Partners keep one existing Onboard terminal delegate and one feature UI callback; the app no longer duplicates capture/token/Handler/handoff state.

Normal Main/feature UI has an explicit notification permission button and read-only engine status; permission/result/settings-return resources are bounded and owner-scoped. No prompt runs automatically; existing Onboard permission ownership can forward its result. Native presentation, truthful business outbox UUIDs, locale and migration data remain app-owned. No production core or notification source was edited by16.

Fresh verified source `5ac3102f5d2bc683d1cd8b81d37a426f0021cad6` (including merged15): **75 actual unit tests PASS** in one invocation (facade43/app32, zero failure/error/skip); debug and Android-test APK assembly PASS,64s. [Immutable command/source/XML/APK evidence](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-default-fix/ticket16/full-app-suite-1/result.json). The17 Android cases compiled; this invocation did not execute them on a device.

Added13 Suite tests cover actual lifecycle/terminal/forwarded task, burst/reentry/recreation/alias/backlog, duplicate runtime installation, explicit permission/results/timeout/foreign ownership and failed restored binding. A real restored-Main regression was RED1/1 at `e631f12` (unowned listener started Feature after storage failure), then GREEN after `20714a6`; a reentrant retry ownership test found and verified `5ac3102`. [RED/transition notes](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-default-fix/ticket16/binding-regression.md). Initial superclass compilation failure is retained with zero tests and is not behavioral RED.

[Partner three-point guide](../../../retentionkit/SUITE_INTEGRATION.md) and [actual example](../../../app/RETENTION_EXAMPLE.md) describe the new required seam and optional ownership. Status remains claimed pending root's actual normal/device and optional-vendor R8 integration acceptance; earlier corrected279/17/matrix results are historical. Root owns map and final acceptance.
