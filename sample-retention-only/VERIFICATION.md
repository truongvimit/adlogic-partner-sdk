# Retention consumer and publication verification

The captured matrix at `1d06439` passed **12 release builds with R8/resource shrinking, 12 resolved runtime graph/composition checks and six publication inspections**. It includes the widget return correction and bounded consumer route readiness retry. This proves actual selective/umbrella consumer linkage and packaging; device behavior and code-review correctness have separate evidence.

## Frozen source and local publication

- Library and consumer commit: `1d06439b0606edfa3585c357b986014ee0e4d01a`.
- Explicit local QA version: `retentionkit-qa-20260907-1d06439`.
- Maven group: `com.github.truongvimit`; repository: `/Users/duongkhai/.m2/repository`.
- Six published artifacts: `retention-core`, `retention-notifications`, `retention-widgets`, `retention-feedback`, `retention-review`, `retentionkit`. This application is not published.
- [Machine-readable matrix summary](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-consumers-20260907-1d06439/matrix-summary.json).
- [Publication build capture](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-consumers-20260907-1d06439/publication-build/run.json).

Every Gradle capture reports a clean, unchanged source checkout before/after execution, the exact argv and VERSION environment, timestamps, exit code, and log hashes. AAR/POM/.module copies, APKs, merged release manifests and R8 mappings are retained with hashes in the external evidence directory. All six publication coordinate/integrity checks passed; recorded log/artifact hashes were rechecked when aggregating the matrix.

## Matrix

Each row passed the actual release assembly, `releaseRuntimeClasspath` graph, and matching AAR/POM/merged release manifest composition check:

| Profile | Dependency source | Result | APK bytes | Retention dependency closure |
|---|---|---|---:|---|
| core | project | PASS | 109126 | core |
| core | Maven POM | PASS | 109130 | core |
| notifications | project | PASS | 144574 | core, notifications |
| notifications | Maven POM | PASS | 144562 | core, notifications |
| widgets | project | PASS | 135062 | core, widgets |
| widgets | Maven POM | PASS | 135062 | core, widgets |
| feedback | project | PASS | 131182 | core, feedback |
| feedback | Maven POM | PASS | 131186 | core, feedback |
| review | project | PASS | 138407 | core, review |
| review | Maven POM | PASS | 138439 | core, review |
| umbrella | project | PASS | 199427 | all six artifacts |
| umbrella | Maven POM | PASS | 199431 | all six artifacts |

The APK sizes describe this tiny signed proof app, not an SDK size benchmark. Different application IDs and resources can change APK bytes; use the recorded hashes to identify artifacts.

Each Maven graph resolved the exact local version without any `project :` nodes. POM-only metadata resolution explicitly disables Gradle metadata redirection; external AndroidX/Play dependencies resolve normally. Selective graphs contain only their chosen retention artifact plus core. No named ads, OnboardKit, Billing, Firebase, Compose or MMP dependency leaked into any profile. Only notifications and umbrella declare POST_NOTIFICATIONS/RECEIVE_BOOT_COMPLETED; the other profiles add only their package-specific AndroidX receiver-protection permission. No broad keep/dontwarn rule was added.

## Reproduce and locate evidence

For every profile/source pair, the evidence directory contains:

- `<profile>-<source>-build/run.json`, `gradle.stdout`, `gradle.stderr`.
- `<profile>-<source>-runtime/run.json` and the actual dependencies stdout.
- `<profile>-<source>-artifacts/artifacts.json`, APK, merged manifest and mapping.
- `<profile>-<source>-composition.json`; publication reports are `<artifact>-publication.json`, with exact published files under `published/<artifact>/`.

The [sample README](README.md) gives project/POM selection. The captured build command runs `:sample-retention-only:printRetentionConsumerConfiguration` and `:sample-retention-only:assembleRelease`; the separate graph command runs `:sample-retention-only:dependencies --configuration releaseRuntimeClasspath`. Both use the actual profile/source properties and `--no-daemon --console=plain --max-workers=2 --stacktrace`. Maven captures add the exact version/repository properties. The [verification tool](../scripts/retentionkit/README.md) passes the explicit publication version into Gradle, records arguments without a shell and verifies matching evidence.

## Earlier baseline and follow-up scope

The [earlier 723a57c matrix](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-consumers-20260907-723a57c/matrix-summary.json) also passed at its own immutable source. Later consumer commits `5263826` and `da33792` preserve staged SDK entries and retry readiness after source destruction. Widget correction `1239bd3` handles temporary launcher return and narrow widget layout. All are integrated into the `1d06439` source used by the full new matrix above; the earlier QA artifacts were not reused.

Any subsequent library correction needs a new exact QA version and the affected consumer closure to be rechecked. These build results alone do not close separate code-review findings or the root-owned overall acceptance.

## Readiness regression evidence

Commit `da337928558e5646853654bd57f1656ec4de0da7` adds a resumed-only retry: 20 attempts at 250 ms, cancelled on pause and successful consumption. A new resume, setup completion, new entry or explicit refresh starts a fresh bounded window. This handles the source feedback Activity closing its external scope after the destination's initial resume attempt; it does not bypass core UI eligibility.

The same command, `:sample-retention-only:testDebugUnitTest -PretentionProfile=core -PretentionDependencySource=project`, ran against clean, immutable red/green commits:

- [Red capture](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retention-proof-readiness-red-cap/run.json), `ff26a8a`: three actual Activity/core cases, two failures. The word-count route stayed staged after scope completion and no retry occurred. Pause cancellation already passed.
- [Green capture](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retention-proof-readiness-green/run.json), `da33792`: all three passed, zero failures/errors/skips; [inspected JUnit](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retention-proof-readiness-green/tests.json). Cases assert late readiness routes without another Home cycle, pause cancels effects until resume, and a persistent block stops retries while preserving the entry.
- [Parser fixture run](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retention-proof-readiness-green/parser-tests/run.json): 25 passed. These fixtures verify evidence tooling only.

The Activity tests exercise production capture, routing and core UI state under Robolectric API 34; the source scope completion is an explicit core signal. They do not substitute for the root's actual feedback/launcher flows on API 36.

The Python verification fixtures test parser/command safety only; they are not SDK product tests. Device routing, API 36 insets, launcher confirmation, visible notification delivery, production Play review and full example acceptance are recorded separately by the root validation owner. No device command was used for this consumer/publication work. A submitted OS API request cannot prove widget placement, a displayed review card, a rating or uninstall.

Ticket07 remains open until the root-owned broader validation is complete. These Retention artifacts are unreleased; the existing 5.1.1 release does not ship them.
