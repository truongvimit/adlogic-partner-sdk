# 06 Full example integration

Type: task
Status: in-progress
Blocked by: 02, 03, 04, 05

Spec: ../spec.md

## Scope and acceptance

Own app/** and any standalone composition proof sample modules. Integrate umbrella once from GlobalApp, reuse selected app language/entitlement/OnboardKit completion/shared Firebase, preserve existing ads sample and add a clear Retention playground entry. Provide working destination screens (translation/document/task sample content), notification actions, widget, feedback and business-success/review controls. Standard default release behavior and debug-only test conveniences clearly separated. Add safe deterministic device instrumentation/automation, no release exported test receivers, no force success/mock production transport. Use only Google test ads in device debug. Remove/replace overlapping example legacy rating/shortcut wiring only where used.

Follow the shared implementation contract and acceptance ledger in spec.md. Commit only owned files; write implementation/test results under Answer and change Status to resolved when verified. Provide commit hashes and concise context pointers for the merger.

## Comments

Implementation is prepared in app/**; see app/RETENTION_EXAMPLE.md. Four real EN/VI utilities, durable result/success outbox, shared catalogue, facade/suite adapters, post-resume pending routing, standard feedback/manual rate and known-ID-only shortcut migration are implemented. Debug-only engine profile and 11 instrumented cases cover all seven notification campaigns, action destinations and critical gates; release contains no fixture entry point.

Validation checkpoint: `:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest` passed (11 unit tests, 0 failures/errors) against the first compiling facade integration. Final05/authoritative Billing/ad-click dependencies synchronized at9bc12fc; Billing adapter and final routing normalization wired afterward. Final app APK/R8 assembly and rerun are pending the coordinated Gradle window. No ADB/device execution by this agent. Ticket remains open until final build and root-owned device evidence.


Final05 integration checkpoint: debug APK and debug instrumentation APK assembled successfully against final suite dependencies. Unit tests now 14 passed, 0 failed/errors/skipped, including failed state persistence, queued signal replay, and preference-memory rollback after failed outbox acknowledgement. Success acknowledgement belongs to an owned per-runtime subscriber after core dispatch, not the nonblocking signal Boolean. No core/library changes.

APK paths: app/build/outputs/apk/debug/ITG_Base_Project_v1.0.0_v100_09.07.2026-debug.apk and app/build/outputs/apk/androidTest/debug/ITG_Base_Project_v1.0.0_v100_09.07.2026-debug-androidTest.apk. Root device execution and final minified release remain pending; ticket stays in-progress.


Root API36 instrumentation run1: 9/11 pass; evidence `/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/example-api36-run-1`. Pinned actions all reached destinations on the same Activity instance, but ActivityScenario ignored actual DESTROYED because setIntent changed its launch identity; test now asserts same Activity/task and restores harness identity just before teardown. Review counter test incorrectly inherited review.enabled=false; it now enables real review counter/dedupe with threshold1000 and asserts no Play attempt. Locale expectation now uses runtime's selected-locale catalogue. UI handles actual Blocked/Skipped outcomes without claiming a request was sent. These changes require compilation and targeted device rerun; no new pass is claimed yet.


Added a twelfth device case for actual PlayReviewTransport with explicitly accelerated eligibility: real phrase translation, request and truthful failed/timeout/unknown terminal, released lease; no fake transport or rating/card assertion. Awaiting root targeted execution.


Added thirteenth device case: actual Android initially blocked channel in a unique QA namespace, actual saved alarm and channel_blocked/no-post assertion, cleanup only that channel. Root covers widget launcher/feedback UI manually on the minified umbrella consumer; no duplicate UI fixture suite added.


Validation at540d1e6: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` PASS; 14 unit tests, 0 failures/errors/skips; all13 instrumentation cases compile into actual test APK. Includes all scoped run1 fixture corrections, real Play smoke, real blocked-channel test and staged SDK token preservation. Root targeted/full device rerun and final release R8 remain pending. Gradle window released for consumer follow-up and root instrumentation.


Prepared15-case package source: added real SDK feedback Keep/rescue and AppInfo+Back tests (no selected reasons, fake launcher, Home/restart workaround or mandatory survey). Not yet compiled/run; root13-case run remains frozen while this source is prepared.


Integrated regression at immutable private06 code head `1ae2b275d14ac162a66587a124358370af48e976` PASS (2m11s). Verified its SDK/library/consumer tree equals delivery root `c407daddb7a40de44cac9a9b7e1bd9ee1f21620f`; only own06 ticket/app README/additional feedback instrumentation differed. Actual XML totals: core39, notifications35, widgets36, feedback18, review14, facade12, shared Firebase4, Billing20, ads162, OnboardKit168, app14 = **522 tests, 0 failures/errors/skips**. Debug application APK and15-case test APK assembled successfully. App SHA256 `198de54bb1dac3495c6586db38201960d126a371df53fbb36ce9dc292f67d422`; test SHA256 `d59558ed695a2061a6c8e676ec16c0bfa7241490205b16e6a39f276a3cc9824f`. Root15-case actual run and final app release R8 pending.

Root-owned API36 evidence: prior13-case full real instrumentation PASS. Standard adapter/manual path also PASS: actual widget pin confirmed ID2; widget TextTools tap through consent/language/onboarding/PayKit reached a real three-word normalization; selected VI refreshed actual widget labels; warm widget Document tap opened real VI document. Evidence PNG/XML verified at `/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/example-api36-manual/example-widget-business-after-full-setup.*`, `example-widget-vietnamese-refresh.*`, `example-widget-warm-document-route.*`. These standard-path checks used normal suite adapters/router, not the QA fixture. Physical Pixel remains root-reported PIN-locked; no physical-device pass is claimed.
