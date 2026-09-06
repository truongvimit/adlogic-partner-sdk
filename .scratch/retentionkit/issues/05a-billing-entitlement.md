# 05a Authoritative Billing entitlement for Retention integration

Type: task
Status: resolved
Blocked by: 01

Spec: ../spec.md

## Scope and acceptance

Own billingkit/** only, plus this issue. Add a small, source-compatible authoritative current-process entitlement snapshot/StateFlow to Billing. Cached/default isPremium=false and awaitReady/verifyFinish are not evidence that Play successfully verified no ownership. Initial state must be Unknown; only a trustworthy completed sweep can produce verified non-premium. A verified purchased entitlement can produce premium. Failed/partial/disconnected queries must not manufacture verified non-premium or erase a previously verified result. Late installation must observe verification completed before Billing.install without waiting for a future callback. State read/publication must be coherent under concurrent verification/purchase callbacks. Preserve existing isPremium/awaitReady behavior and all Java entry points; no new dependency on RetentionKit. Document semantics and test failed/partial/late-install/successful-empty/purchase/refund transitions with existing Billing tests plus meaningful new tests. Coordinate exact public API promptly with ticket05 and ticket06 authors. No ADB or root edits.

This additive seam is required by the existing spec's unknown-entitlement gate and minimal integration objective. Full ticket05 adapter and ticket06 final acceptance depend on it. Do not fix unrelated billing behavior in this task.

## Comments

Inspection: AppPurchase.finishVerify sets verifyFinish for both successful and failed sweeps; verifiedThisProcess is private. Billing.awaitReady fast-path and its registration-race fallback synthesize Ready/OK from verifyFinish. Keep compatibility for existing callers and expose an explicitly authoritative separate API.

Claimed in isolated `05a-billing`, branch `codex/retentionkit-billing`, 2026-09-07.

## Answer

Implemented an additive engine-owned authoritative state seam in315ec5e: `Billing.entitlement: StateFlow<BillingEntitlement>` with UNKNOWN / VERIFIED_NON_PREMIUM / VERIFIED_PREMIUM; Java static getter plus `AppPurchase.getEntitlement()` and `getEntitlementSnapshot()`. One atomic versioned source prevents registration races, stale overlapping sweeps and a pre-purchase query erasing a later grant. Failed/partial/null/disconnected queries do not create verified free. Current catalogue registration invalidates old evidence. Legacy readiness/cached APIs remain unchanged; no Retention dependency.

Contracts: `billingkit/AUTHORITATIVE_ENTITLEMENT.md`. Verification: `billingkit/ENTITLEMENT_VERIFICATION.md`. Command PASS: `./gradlew :billingkit:testDebugUnitTest :billingkit:assembleRelease --max-workers=2`.20 tests:7 atomic/coherent-flow and13 actual AppPurchase transport tests, zero failures/errors/skips. Java8 output/API signatures and isolated runtime dependency graph verified. Only BillingKit and own05a issue changed. No ADB/push/merge/root edits.
