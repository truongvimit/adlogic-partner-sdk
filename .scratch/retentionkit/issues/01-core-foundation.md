# 01 Core and publication foundation

Type: task
Status: resolved
Blocked by: none

Spec: ../spec.md

## Scope and acceptance

Own retention-core/** plus new module build scaffolds, settings.gradle and JitPack publication registrations. Implement shared contract from spec.md, record exact public interfaces and downstream examples in retention-core/CONTRACT.md. Include transactional namespaced persistent store, signals/lifecycle, user state, localized feature catalogue, typed durable entry consumption, UI leases, event/diagnostic isolation, main-process/install lifecycle. Add meaningful unit/Robolectric tests and prove :retention-core:testDebugUnitTest and assembleRelease. Scaffolds for the other five new libraries may contain build/manifest/consumer-rules only; no fake implementations. Do not edit app, ads, onboardkitorigin or suite-firebase.

Follow the shared implementation contract and acceptance ledger in spec.md. Commit only owned files; write implementation/test results under Answer and change Status to resolved when verified. Provide commit hashes and concise context pointers for the merger.

## Comments

Claimed by Ticket 01 implementer in `codex/retentionkit-core` on 2026-09-07. Core API and module publication foundation in progress.


## Answer

Implemented production `io.retentionkit.core`: concurrent local-only singleton install and main-process guard; restored transactional namespaced state and validated configuration revisions; localized feature catalog; typed durable routing with reusable-surface identity and final consumption; weak foreground Activity access; bounded exclusive UI leases; explicit setup/onboarding/entitlement/success/ad/system/lifecycle signals; isolated diagnostics/events/modules. Callback dispatch uses a single queue drainer without holding queue locks around module code; config validates outside the state lock and commits by revision comparison. Each process supplies authoritative entitlement before module attach, while setup/install/last-active milestones restore durably.

Added publishable scaffolds for `retention-notifications`, `retention-widgets`, `retention-feedback`, `retention-review`, `retentionkit`, with settings/JitPack registrations and selective dependency shape. No feature implementation is claimed for these scaffolds.

Verified 29 passing Robolectric tests and core release assembly; all six release AARs; generated publication POMs; vendor-free core dependency tree. All Gradle invocations used at most two workers. No ADB, push or merge. See `retention-core/VERIFICATION.md` and `.scratch/retentionkit/verification.md` for commands and limitations.

Downstream context pointer: `retention-core/CONTRACT.md`. Initial commits: `2fccadc`, `e0210b0`, `1151142`, `a665e00`, `03cfe00`; final verification/config-removal fix accompanies this answer. No app/ads/onboardkitorigin/suite-firebase files changed.
