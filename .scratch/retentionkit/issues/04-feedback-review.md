# 04 Exit feedback and Play review

Type: task
Status: resolved
Blocked by: 01

Spec: ../spec.md

## Scope and acceptance

Own retention-feedback/** and retention-review/**. Implement default branded/localized rescue+optional survey Activity, keep/feature/continue system App Info, and separately owned feedback shortcut. Review after successes uses Translate 5/10days/3 default, persisted eligibility and in-flight/UI lease guards, lifecycle/timeout/late callbacks, real Play Review manager integration and injectable test seam, manual Store opens directly. No star gating or false rated/uninstalled statuses. Include state/concurrency/failure/rotation/lifecycle/resource tests. Do not embed ads/Firebase; use core host interfaces.

Follow the shared implementation contract and acceptance ledger in spec.md. Commit only owned files; write implementation/test results under Answer and change Status to resolved when verified. Provide commit hashes and concise context pointers for the merger.

## Comments

Claimed in isolated worktree `04-feedback-review`, branch `codex/retentionkit-feedback-review`, 2026-09-07.

## Answer

Implemented production modules in the isolated04 worktree. Public contracts are `retention-feedback/README.md` and `retention-review/README.md`; verification details/limits are each module's `VERIFICATION.md`.

- Feedback: optional localized reasons, branded default Activity, core feature rescue, Keep/Continue App Info, custom view with SDK-owned controller, durable session/selection restoration, lifecycle/inset handling, independent owned shortcut quota. Final typed entry consumption stays after UI gates. Own scopes expire/close safely; no uninstall interception or success claim.
- Review: real Play transport, separate durable5-success/10-day/3-attempt policy, bounded dedupe and in-flight request/launch state, retry/backoff, current weak Activity/UI lease validation, scoped system handoff, duplicate/late callbacks ignored, direct manual Store action independent of auto policy. No star gate/rated flag.
- Verification PASS: `./gradlew :retention-feedback:testDebugUnitTest :retention-review:testDebugUnitTest :retention-feedback:assembleRelease :retention-review:assembleRelease --max-workers=2`.32 executions:18 feedback (API34 plus default screen API24/36),14 review (API34), zero failures/errors/skips. Release AARs built. Separate releaseRuntimeClasspath checks exclude unrelated retention modules/ads/Firebase/Compose/billing; review intentionally includes Play2.0.2. Physical Play/OEM tests deferred to integrated device ticket; none claimed here.
- Test findings fixed: shortcut action absent from host router; pending feedback session disabled before Activity arrival; UI lease release after durable review-claim storage failure; thrown feedback launch cleanup.

Commits: `a8bfe04` dependencies/claim; `b0d2d83` review implementation; `a7ac3a8` feedback implementation; `a47dec9` tests, hardening and verification docs. Core ledger unchanged. No ADB, push, merge or root checkout modifications.
