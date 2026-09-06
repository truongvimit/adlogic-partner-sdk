# RetentionKit acceptance ledger

This ledger records verified behavior by ticket; a scaffold does not establish feature completion.

## Ticket 01 — core/publication foundation

Status: **PASS** for Ticket 01 scope, 2026-09-07.

- 29 Robolectric tests: transactional namespaces, corruption/rollback, concurrent installs/patches, callback lock inversion, state restore, explicit config removal, current-process entitlement, setup grace, scoped onboarding, entry/reusable identity/consumption and weak-Activity UI leases.
- `:retention-core:testDebugUnitTest` and `:retention-core:assembleRelease` passed with `--max-workers=2`.
- All six new modules produced release AARs; five non-core libraries remain build scaffolds for downstream implementation.
- Publication POMs generated/inspected for all six. Core releaseRuntimeClasspath contains no ads/Firebase/billing/Compose. Settings and JitPack publication registrations are present.
- Detailed commands and limits: `retention-core/VERIFICATION.md`. Public APIs: `retention-core/CONTRACT.md`.
- **Not tested here:** ADB, physical device, notification post delivery, widget launcher outcome, Play review display, optional adapters, sample end-to-end behavior, minified consuming application. These remain downstream acceptance work.
