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
