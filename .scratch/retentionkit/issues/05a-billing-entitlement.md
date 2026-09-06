# 05a Authoritative Billing entitlement for Retention integration

Type: task
Status: claimed
Blocked by: 01

Spec: ../spec.md

## Scope and acceptance

Own billingkit/** only, plus this issue. Add a small, source-compatible authoritative current-process entitlement snapshot/StateFlow to Billing. Cached/default isPremium=false and awaitReady/verifyFinish are not evidence that Play successfully verified no ownership. Initial state must be Unknown; only a trustworthy completed sweep can produce verified non-premium. A verified purchased entitlement can produce premium. Failed/partial/disconnected queries must not manufacture verified non-premium or erase a previously verified result. Late installation must observe verification completed before Billing.install without waiting for a future callback. State read/publication must be coherent under concurrent verification/purchase callbacks. Preserve existing isPremium/awaitReady behavior and all Java entry points; no new dependency on RetentionKit. Document semantics and test failed/partial/late-install/successful-empty/purchase/refund transitions with existing Billing tests plus meaningful new tests. Coordinate exact public API promptly with ticket05 and ticket06 authors. No ADB or root edits.

This additive seam is required by the existing spec's unknown-entitlement gate and minimal integration objective. Full ticket05 adapter and ticket06 final acceptance depend on it. Do not fix unrelated billing behavior in this task.

## Comments

Inspection: AppPurchase.finishVerify sets verifyFinish for both successful and failed sweeps; verifiedThisProcess is private. Billing.awaitReady fast-path and its registration-race fallback synthesize Ready/OK from verifyFinish. Keep compatibility for existing callers and expose an explicitly authoritative separate API.

Claimed in isolated `05a-billing`, branch `codex/retentionkit-billing`, 2026-09-07.
