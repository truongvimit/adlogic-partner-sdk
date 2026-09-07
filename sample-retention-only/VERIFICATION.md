# Retention consumer and publication verification

The captured matrix at `bf68f1e` passed **12 release builds with R8/resource shrinking, 12 resolved runtime graph/composition checks and six publication inspections**. It includes the reviewed shared handoff scope, post-callback launch checks, widget return correction and bounded consumer route readiness retry. This proves actual selective/umbrella consumer linkage and packaging; device behavior and code-review correctness have separate evidence.

## Frozen source and local publication

- Library and consumer commit: `bf68f1e6b11e0c15e2e1f8ad64670b516529de33`.
- Explicit local QA version: `retentionkit-qa-20260907-bf68f1e`.
- Maven group: `com.github.truongvimit`; repository: `/Users/duongkhai/.m2/repository`.
- Six published artifacts: `retention-core`, `retention-notifications`, `retention-widgets`, `retention-feedback`, `retention-review`, `retentionkit`. This application is not published.
- [Machine-readable matrix summary](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-consumers-20260907-bf68f1e/matrix-summary.json).
- [Publication build capture](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-consumers-20260907-bf68f1e/publication-build/run.json).

Every Gradle capture reports a clean, unchanged source checkout before/after execution, the exact argv and VERSION environment, timestamps, exit code, and log hashes. AAR/POM/.module copies, APKs, merged release manifests and R8 mappings are retained with hashes in the external evidence directory. All six publication coordinate/integrity checks passed; recorded log/artifact hashes were rechecked when aggregating the matrix.

## Matrix

Each row passed the actual release assembly, `releaseRuntimeClasspath` graph, and matching AAR/POM/merged release manifest composition check:

| Profile | Dependency source | Result | APK bytes | Retention dependency closure |
|---|---|---|---:|---|
| core | project | PASS | 109338 | core |
| core | Maven POM | PASS | 109334 | core |
| notifications | project | PASS | 144742 | core, notifications |
| notifications | Maven POM | PASS | 144730 | core, notifications |
| widgets | project | PASS | 135242 | core, widgets |
| widgets | Maven POM | PASS | 135254 | core, widgets |
| feedback | project | PASS | 132946 | core, feedback |
| feedback | Maven POM | PASS | 132938 | core, feedback |
| review | project | PASS | 140631 | core, review |
| review | Maven POM | PASS | 140635 | core, review |
| umbrella | project | PASS | 202399 | all six artifacts |
| umbrella | Maven POM | PASS | 202403 | all six artifacts |

The APK sizes describe this tiny signed proof app, not an SDK size benchmark. Different application IDs and resources can change APK bytes; use the recorded hashes to identify artifacts.

Each Maven graph resolved the exact local version without any `project :` nodes. POM-only metadata resolution explicitly disables Gradle metadata redirection; external AndroidX/Play dependencies resolve normally. Selective graphs contain only their chosen retention artifact plus core. No named ads, OnboardKit, Billing, Firebase, Compose or MMP dependency leaked into any profile. Only notifications and umbrella declare POST_NOTIFICATIONS/RECEIVE_BOOT_COMPLETED; the other profiles add only their package-specific AndroidX receiver-protection permission. No broad keep/dontwarn rule was added.

## Reproduce and locate evidence

For every profile/source pair, the evidence directory contains:

- `<profile>-<source>-build/run.json`, `gradle.stdout`, `gradle.stderr`.
- `<profile>-<source>-runtime/run.json` and the actual dependencies stdout.
- `<profile>-<source>-artifacts/artifacts.json`, APK, merged manifest and mapping.
- `<profile>-<source>-composition.json`; publication reports are `<artifact>-publication.json`, with exact published files under `published/<artifact>/`.

The [sample README](README.md) gives project/POM selection. The captured build command runs `:sample-retention-only:printRetentionConsumerConfiguration` and `:sample-retention-only:assembleRelease`; the separate graph command runs `:sample-retention-only:dependencies --configuration releaseRuntimeClasspath`. Both use the actual profile/source properties and `--no-daemon --console=plain --max-workers=2 --stacktrace`. Maven captures add the exact version/repository properties. The [verification tool](../scripts/retentionkit/README.md) passes the explicit publication version into Gradle, records arguments without a shell and verifies matching evidence.

## Earlier checkpoints and source identity

Historical matrices at [723a57c](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-consumers-20260907-723a57c/matrix-summary.json) and [1d06439](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-consumers-20260907-1d06439/matrix-summary.json) remain immutable. The full matrix above republishes and rechecks the integrated `bf68f1e` implementation, including review-fix `90ac742`; earlier QA artifacts were not reused.

Final app delivery `2ff967a909cf043bf6e66b3424258a78efe67947` adds four app-only files/changes after `bf68f1e`. All non-app paths, including the six libraries, tooling and isolated consumer, have identical Git trees. This source identity is recorded in the matrix summary. Later library corrections require a new exact QA version and an affected recheck.

## Integrated product and device evidence

- [Scoped product test summary](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-app-20260907-2ff967a/combined-product-tests.json): 549 passed, zero failures/errors/skips. This combines 532 unchanged non-app cases at `bf68f1e` with 17 fresh app cases at `2ff967a`; it is not one invocation. The non-app cases include core 46, notifications 35, widgets 36, feedback 22, review 19, facade 12, Firebase 4, Billing 20, ads 162, OnboardKit 168 and Trackkit 8.
- [Final app build capture](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-app-20260907-2ff967a/run.json): debug APK, test APK and minified release passed. Artifact hashes are recorded [alongside it](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-app-20260907-2ff967a/artifacts.json).
- [Root-owned API 36 JUnit](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/example-api36-run-6-final-verification.json): 15/15 passed on the final app tree, zero failures/errors/skips; XML SHA-256 `09d11219e950a8cb785d4560e104ccc4c179d38e7115c35abb1bcc4a21a47d3b`.
- [Root-owned minified POM umbrella smoke](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/example-api36-manual/final-bf68-maven-umbrella-verdict.json): install/cold launch, setup, reason-free rescue immediately to word count, actual four-word result, observed Store handoff/return, feedback and Keep passed. APK SHA matches this matrix: `d48fc9fc3b56e562447bb874d20700c0ec4c2d6868039780b494fb03628de421`. The Store capture establishes source-UID market launch; its exact resolved detail-query was not retained in this run.

Referenced JUnit and smoke-file hashes were verified when preparing this record. Physical/OEM coverage, a visible Play review card/rating and uninstall success are not inferred. The physical device was PIN-locked at this checkpoint. Root subsequently recorded15/15 Android cases and additional manual checks on the unlocked Pixel; full exclusive-device acceptance remains pending in `.scratch/retentionkit/physical-acceptance.md`. Overall acceptance and PR/release status belong to the root ledger.

## Readiness regression evidence

Commit `da337928558e5646853654bd57f1656ec4de0da7` adds a resumed-only retry: 20 attempts at 250 ms, cancelled on pause and successful consumption. A new resume, setup completion, new entry or explicit refresh starts a fresh bounded window. This handles the source feedback Activity closing its external scope after the destination's initial resume attempt; it does not bypass core UI eligibility.

The same command, `:sample-retention-only:testDebugUnitTest -PretentionProfile=core -PretentionDependencySource=project`, ran against clean, immutable red/green commits:

- [Red capture](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retention-proof-readiness-red-cap/run.json), `ff26a8a`: three actual Activity/core cases, two failures. The word-count route stayed staged after scope completion and no retry occurred. Pause cancellation already passed.
- [Green capture](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retention-proof-readiness-green/run.json), `da33792`: all three passed, zero failures/errors/skips; [inspected JUnit](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retention-proof-readiness-green/tests.json). Cases assert late readiness routes without another Home cycle, pause cancels effects until resume, and a persistent block stops retries while preserving the entry.
- [Parser fixture run](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retention-proof-readiness-green/parser-tests/run.json): 25 passed. These fixtures verify evidence tooling only.

These historical Activity regression tests exercise production capture, routing and core UI state under Robolectric API 34; source scope completion is an explicit core signal. The final API 36 handoff evidence is linked separately above.

The Python verification fixtures test parser/command safety only; they are not SDK product tests. Device routing, API 36 insets, launcher confirmation, visible notification delivery, production Play review and full example acceptance are recorded separately by the root validation owner. No device command was used for this consumer/publication work. A submitted OS API request cannot prove widget placement, a displayed review card, a rating or uninstall.

Ticket07 packaging/documentation validation is complete within this recorded scope. These Retention artifacts are unreleased; the existing 5.1.1 release does not ship them.
