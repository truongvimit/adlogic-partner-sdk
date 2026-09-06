# Ticket 01 verification

Verified 2026-09-07 in the isolated `codex/retentionkit-core` worktree. Scope is production core and build/publication scaffolds. The notification/widget/feedback/review/facade implementations belong to later tickets and are not claimed complete here.

## Commands and outcomes

| Command/check | Result |
|---|---|
| `./gradlew :retention-core:compileDebugKotlin --max-workers=2 --console=plain` | PASS |
| `./gradlew :retention-core:testDebugUnitTest :retention-core:assembleRelease --max-workers=2 --console=plain` | PASS, 29 tests, zero failures/errors/skips in final test XML |
| `./gradlew :retention-core:assembleRelease :retention-notifications:assembleRelease :retention-widgets:assembleRelease :retention-feedback:assembleRelease :retention-review:assembleRelease :retentionkit:assembleRelease --max-workers=2 --console=plain` | PASS, six AARs produced; optional modules are scaffolds |
| `generatePomFileForReleasePublication` for all six modules | PASS; generated POM dependencies inspected |
| `./gradlew :retention-core:dependencies --configuration releaseRuntimeClasspath --max-workers=2 --console=plain` | PASS; no Firebase, GMA/Play services ads, billing, Compose or project `ads` |
| `git diff --check` | PASS |

Generated local evidence (not committed): `retention-core/build/reports/tests/testDebugUnitTest/index.html`, `retention-core/build/test-results/testDebugUnitTest/TEST-*.xml`, `retention-core/build/foundation-validation.log`, `retention-core/build/runtime-dependencies.txt`, and each module's `build/publications/release/pom-default.xml`.

Publication shape: core exposes lifecycle-process and Kotlin stdlib, with core-ktx runtime dependency. Each selective scaffold exposes core plus Kotlin stdlib only. The umbrella exposes the five feature/core modules. Settings and JitPack register all six release publications; no unrelated existing SDK file was changed. The source configuration has minSdk 24 / compileSdk 36 / Java-Kotlin target 17.

## Behavior covered by the 29 tests

| Suite | Tests | Evidence established |
|---|---:|---|
| RetentionStoreTest | 5 | Concurrent writers using multiple store instances atomically claim shared budget; rollback on callback error; snapshot isolation; rejected nested transactions; corrupt-state failure; namespace independence. |
| RetentionEntryTest | 5 | Staging through setup survives store recreation; concurrent consumption succeeds once; reusable widget delivery generates fresh token but recreation preserves it; PendingIntent filter identities distinguish action/instance while preserving host URI/flags; malformed/collision/expired/capacity outcomes. |
| RetentionRuntimeTest | 10 | Concurrent singleton install restores revision before attach; validation and persistence failures preserve last-known-good snapshot; explicit config removal survives reinstall; setup timestamp is idempotent; current-process entitlement replaces cached entitlement; last-active includes session end and cannot go backward; failing module/listener/sink isolation; reentrant subscription order; invalid install retry; main-process guard; localized catalog and explicit host routing. |
| RetentionUiTest | 5 | Exclusive bounded leases use elapsed time; stale lease cannot release a new lease; Activity pause/destroy removes weak host and revokes UI; scoped external/ads suppression expires and does not persist across process recreation; scoped onboarding marketing phase and post-setup cancellation; foreground/Activity/onboarding prompt gates. |
| RetentionConcurrencyTest | 4 | A module holding its own lock can enqueue a signal without lock inversion; config validation does not block unrelated state signals behind a core lock; concurrent config PATCH writers preserve both fields/revisions; cold attach/reconcile observes UNKNOWN or SUBSCRIBER supplied for this process rather than cached free entitlement. |

## Boundaries

Tests run on Robolectric API 34, not physical hardware. No ADB was used. Android API 24 runtime, OEM launcher/Doze behavior, notification delivery, Play review card display, permission ownership, widget confirmation and actual minified application assembly are not established by this core ticket. Module tests and later integration/device tickets own them. Release AAR assembly is not evidence that R8 consumer rules or a physical-device flow has passed.

Store transactions are one-process, one-namespace atomic. Entry consumption is an at-most-once navigation claim, not exactly-once navigation through a crash. Config validation callbacks must be pure and may be retried after a concurrent revision. Async modules must recheck revision/user/lifecycle/channel/TTL at effect time and cancel owned work on shutdown. The exact integration contract is [CONTRACT.md](CONTRACT.md).
