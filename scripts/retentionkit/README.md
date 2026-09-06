# RetentionKit verification tools

`verify.py` uses Python 3.9+ and the standard library. It checks **existing evidence** or captures explicitly requested commands. It does not create a consumer app, guess SDK APIs/tasks, infer device outcomes, update the verification ledger, or publish a remote release.

Run from the checkout being verified. Evidence directories/files must be new: earlier evidence is never overwritten. Keep outputs outside Git because build logs, package dumps, and optional screenshots can contain local paths or app content. JSON records retain the exact commands, timestamps, return codes, file paths, and SHA-256 hashes. Git commit/status snapshots are taken before and after each Gradle capture; a changed checkout fails the capture even when Gradle exits zero. Keep source and artifacts unchanged during each run.

## 1. Inspect test XML

```sh
python3 scripts/retentionkit/verify.py tests \
  retention-core/build/test-results/testDebugUnitTest \
  retention-review/build/test-results/testDebugUnitTest \
  --output /absolute/evidence/tests.json
```

Directories are searched for `TEST-*.xml`; explicit XML paths also work. The checker counts actual `testcase` elements, validates suite counters, handles nested suites without double counting, and reports failed case names. Missing reports, empty reports, summary-only XML, failures, errors, and all-skipped suites cannot pass. Skipped cases fail by default; `--allow-skipped` permits a partial check while preserving the skip count. Document every skip separately; it never becomes a passed testcase.

Existing XML may be stale. First capture the relevant real Gradle test tasks, then inspect their outputs. `BUILD SUCCESSFUL` alone cannot prove tests ran, because a task can be skipped or missing from the invocation. Fixture tests below validate this tool only, not RetentionKit.

## 2. Capture real Gradle tasks

The caller selects task names verified in the current checkout (`./gradlew tasks --all`). The following task names follow the repository's release publication convention; use them only once those RetentionKit modules/tasks exist:

```sh
python3 scripts/retentionkit/verify.py gradle \
  --repo /absolute/sdk-checkout \
  --task :retention-review:testDebugUnitTest \
  --task :retention-review:assembleRelease \
  --task :retention-review:publishToMavenLocal \
  --output /absolute/evidence/review-build
```

For local publication, pass `--publication-version retentionkit-qa-YYYYMMDD-COMMITHASH` with a new exact version that includes the final library commit. The tool passes it as the child process's `VERSION` environment and records it in `run.json`; it does not change the shell environment. The actual Maven POM/metadata must match. Use a new version after any library correction to avoid fixed-version cache reuse.

This **executes** Gradle, including local publication when requested. It does not invoke remote publish tasks automatically. It passes `--no-daemon --console=plain --max-workers=2 --stacktrace`, preserves stdout/stderr, and fails on command failure/timeout. Default timeout is 1,800 seconds; set `--timeout` explicitly when needed. Do not run concurrently with another root/agent Gradle build. A timeout terminates the launched process group on macOS/Linux; check for any detached Gradle daemon before retrying.

For composition, obtain the runtime graph from an actual selective consumer (preferred) or the selected library module. A library graph verifies its runtime dependencies but does not replace compiling/minifying the selective consumer:

```sh
python3 scripts/retentionkit/verify.py gradle \
  --repo /absolute/sdk-checkout \
  --task :retention-review:dependencies \
  --configuration releaseRuntimeClasspath \
  --output /absolute/evidence/review-runtime
```

The isolated consumer now lives in [`sample-retention-only`](../../sample-retention-only/README.md). Select it with repeated `--property NAME=VALUE` arguments: `retentionProfile`, `retentionDependencySource`, `retentionMavenVersion`, and `retentionMavenRepo` are the only supported keys. The capture tool validates enums, exact versions, duplicates and existing absolute local repository paths before executing Gradle. Property values stay individual argv entries, including repository paths containing spaces. See the sample README for the project/POM build matrix.

Only one explicit `:module:dependencies` task is allowed with `--configuration`. No shell interpolation is used. A Gradle dependencies task can exit zero with `FAILED` nodes; the composition checker rejects those nodes.

## 3. Verify publication files and selective composition

Locate actual outputs after the build. Do not assume AGP's merged-manifest directory or a Maven group/version path: these vary by variant/toolchain. The repository uses `release` Maven publications and `VERSION` when provided. Check AAR/POM from the **same local publication directory** to prove which pair a local consumer would receive. Optional `.module` metadata must agree with the POM. Example placeholders below intentionally are not generated files:

```sh
python3 scripts/retentionkit/verify.py publication \
  --artifact-id retention-review --version ACTUAL_VERSION \
  --aar /absolute/local-publication/ACTUAL.aar \
  --pom /absolute/local-publication/ACTUAL.pom \
  --metadata /absolute/local-publication/ACTUAL.module \
  --output /absolute/evidence/review-publication.json

python3 scripts/retentionkit/verify.py composition \
  --profile review --version ACTUAL_VERSION \
  --dependencies /absolute/evidence/review-runtime/gradle.stdout \
  --configuration releaseRuntimeClasspath \
  --aar /absolute/local-publication/ACTUAL.aar \
  --pom /absolute/local-publication/ACTUAL.pom \
  --manifest /absolute/selective-consumer/build/ACTUAL_MERGED_RELEASE_MANIFEST.xml \
  --require 'project :retention-core' \
  --output /absolute/evidence/review-composition.json
```

For a Maven consumer, require its actual Maven coordinate instead, e.g. `--require 'com.github.truongvimit:retention-core'`. Repeat `--require` for every expected dependency, including `retention-review` when inspecting a review-only **consumer** graph. Do not pass a source manifest, compile classpath, full demo app graph, or a POM from a different version. This checker cannot infer which consumer produced files supplied by the operator.

Profiles: `core`, `notifications`, `widgets`, `feedback`, `review`, `umbrella`. Each selective profile permits itself plus core; unrelated retention modules, umbrella `retentionkit`, ads/MMP vendors, Firebase, Compose, billing/paywall, and OnboardKit are rejected by named coordinate/package rules. The `umbrella` profile permits all RetentionKit modules and requires the real `retentionkit` POM; it still rejects the unrelated vendor/suite stack. Core/review/feedback also reject WorkManager. Profiles other than notifications/umbrella reject notification/boot permissions; all profiles reject foreground-service, wake-lock, exact-alarm, full-screen-intent, overlay, and battery-exemption permissions. Exported receivers whose names indicate debug/test are rejected. This is a focused leakage check, not a complete manifest security audit.

The checker inspects all four evidence layers:

| Layer | What is verified | What it cannot establish alone |
|---|---|---|
| Resolved runtime graph | Requested configuration exists once, successful invocation, no unresolved nodes, named forbidden dependencies absent even in nested branches | Classes/resources actually compiled and packaged in the consumer |
| POM | Valid AAR coordinates/version and named dependency rules | Transitive dependency closure or remote publication |
| AAR | ZIP structure, decoded manifest, `classes.jar`, classes in bundled `libs/*.jar`, named package/permission leakage | Separate transitive AARs, obfuscated/shaded code, API correctness |
| Consumer merged manifest | Final supplied permissions/components and named leakage rules | Whether it is fresh or produced by the stated build without the accompanying run evidence |

`publication` also works for umbrella `retentionkit`: it checks integrity/coordinates without applying a selective profile. Run it per published artifact. It does not contact Maven/JitPack or claim remote availability. AAR resources are allowed and expected; this script imposes no “zero resources” restriction. Android binary AXML is rejected: supply decoded AAR/AGP manifest XML, not an APK's binary manifest.

Composition evidence is complete only when paired with real release/minify **consumer** build results. Do not mark isolated publication/compatibility checks passed just because the library AAR passes this scanner.

## 4. Root-owned device evidence capture

Only the agent/person assigned the physical device may invoke this subcommand. It performs read-only capture for one explicit device and package; it does **not** install, launch, clear app data, grant/revoke permissions, force-stop, reboot, reset the device, or collect global logcat.

```sh
python3 scripts/retentionkit/verify.py adb-evidence \
  --repo /absolute/sdk-checkout \
  --device ACTUAL_ADB_SERIAL \
  --package ACTUAL.APPLICATION.ID \
  --output /absolute/evidence/widget-pin-cancel \
  --note 'Operator canceled the pin dialog; verify pin_requested exists and pin_confirmed does not.'
```

Captured commands: `get-state`, API/model/build fingerprint, `dumpsys package PACKAGE`, and package `POST_NOTIFICATION` AppOps. The target package must appear installed in its dump. `--adb /absolute/sdk/platform-tools/adb` selects the executable. Add `--screenshot` only when the current screen is appropriate to capture; exact PNG bytes are saved as `screen.png`. Inspect all captures before sharing. AppOps alone does not prove channel state or notification delivery.

Every capture records `behavioral_result: not_evaluated`. The operator supplies scenario/preconditions/action/expected/observed/result in the root ledger, linking these raw files plus app diagnostics. A command returning zero is not a pin-confirmation, review-card, uninstall, or retention success. Keep review quota/production behavior, other OS/OEM coverage, and force-stop limitations explicit.

## 5. Test the tooling without Gradle/ADB

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s scripts/retentionkit -p 'test_*.py' -v
```

The fixtures contain deliberately synthetic AAR/POM/XML/graph bytes in temporary directories and are labeled as parser tests. They do not generate product verification evidence. Coverage includes nested dependency leaks, unresolved graphs, wrong artifacts/versions, permission leakage, embedded vendor classes, malformed/missing reports, skipped tests, output preservation, validated consumer profile/Maven argv, umbrella-versus-selective dependency rules, and rejection of unsafe command arguments before subprocess execution.

Exit codes: `0` checks/capture succeeded within stated scope; `1` validation or captured command failed; `2` invalid/missing input or tool error. Every result retains explicit limitations. Never translate `not_evaluated`, unavailable, skipped, or failed into passed in the acceptance ledger.
