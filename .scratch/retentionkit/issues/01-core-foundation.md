# 01 Core and publication foundation

Type: task
Status: ready-for-agent
Blocked by: none

Spec: ../spec.md

## Scope and acceptance

Own retention-core/** plus new module build scaffolds, settings.gradle and JitPack publication registrations. Implement shared contract from spec.md, record exact public interfaces and downstream examples in retention-core/CONTRACT.md. Include transactional namespaced persistent store, signals/lifecycle, user state, localized feature catalogue, typed durable entry consumption, UI leases, event/diagnostic isolation, main-process/install lifecycle. Add meaningful unit/Robolectric tests and prove :retention-core:testDebugUnitTest and assembleRelease. Scaffolds for the other five new libraries may contain build/manifest/consumer-rules only; no fake implementations. Do not edit app, ads, onboardkitorigin or suite-firebase.

Follow the shared implementation contract and acceptance ledger in spec.md. Commit only owned files; write implementation/test results under Answer and change Status to resolved when verified. Provide commit hashes and concise context pointers for the merger.

## Comments

Implementation pending.
