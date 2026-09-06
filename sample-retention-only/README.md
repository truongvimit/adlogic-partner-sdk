# Isolated RetentionKit consumer

This small application proves selective dependency composition, public API linkage and R8 packaging separately from the full SDK demo. It is not published, has no Google Services plugin, and contains no ad/Firebase/OnboardKit/billing dependency. Common source imports only core. Each build includes exactly one profile's source directory and one selected SDK artifact.

**Build validation:** all six project and six POM-only Maven release builds passed R8/resource shrinking at `bf68f1e`, with all 12 actual runtime graph/merged-manifest composition checks and six local AAR/POM/metadata inspections passing. See [VERIFICATION.md](VERIFICATION.md) for the frozen source, exact QA version, evidence, including the reviewed handoff, widget return and consumer route readiness corrections. This is local publication/build evidence; device validation is owned separately by the root task. The artifacts are unreleased and are not part of the existing 5.1.1 release.

## Select a profile and dependency source

| Gradle property | Values/default |
|---|---|
| `retentionProfile` | `core` (default), `notifications`, `widgets`, `feedback`, `review`, `umbrella` |
| `retentionDependencySource` | `project` (default), `maven` |
| `retentionMavenVersion` | Required for Maven: exact local publication version; no `+`, ranges or `latest.*` selectors |
| `retentionMavenRepo` | Required for Maven: existing absolute local repository directory, e.g. `/Users/you/.m2/repository` |

Invalid modes, profiles, duplicate properties in the capture tool, unsupported versions and missing/local-path errors fail explicitly. Maven version/repository properties are rejected in project mode. The package ID is `io.retentionkit.sample.<profile>.<source>`, so separate profiles/sources can coexist without overwriting one another.

Build output roots are `sample-retention-only/build/<profile>/<source>/`. Release enables R8 and resource shrinking, uses the Android default optimized rules plus SDK consumer rules, and has no broad `keep`/`dontwarn` workaround. Release uses the local debug signing key solely to make this verification APK installable; this is not a production signing configuration.

```sh
./gradlew :sample-retention-only:printRetentionConsumerConfiguration \
  :sample-retention-only:assembleRelease \
  -PretentionProfile=review -PretentionDependencySource=project \
  --no-daemon --console=plain --max-workers=2
```

`printRetentionConsumerConfiguration` outputs the actual selected source directory/artifact/version/package/output path and minify flag. Its `behavioralResult` is `not_evaluated`. Repeat the release assembly and runtime graph capture for all six profiles; do not call a successful default/core build evidence for another profile.

## Project versus POM evidence

Project mode depends on `project(':retention-<profile>')`; umbrella selects `project(':retentionkit')`. It proves local module linkage, not the published POM's transitive closure.

Maven mode depends on `com.github.truongvimit:<artifact>:<explicit-version>`. An exclusive repository routes this entire group to the supplied local directory, preventing fallback to JitPack/other configured repositories. `metadataSources { mavenPom(); ignoreGradleMetadataRedirection() }` intentionally resolves published POM metadata, without Gradle `.module` redirection or dependency substitution to source projects. Missing POMs/AARs or closure fail resolution. External AndroidX/Play dependencies use the repository's ordinary Google/Maven repositories.

First, the validation owner publishes the actual final SDK artifacts at one explicit `VERSION` using their real `publishToMavenLocal` tasks; this sample is never a publication task. Then build a Maven consumer:

```sh
./gradlew :sample-retention-only:printRetentionConsumerConfiguration \
  :sample-retention-only:assembleRelease \
  -PretentionProfile=review -PretentionDependencySource=maven \
  -PretentionMavenVersion=ACTUAL_VERSION \
  -PretentionMavenRepo=/absolute/local/maven/repository \
  --no-daemon --console=plain --max-workers=2
```

Capture exact flags and logs with the existing tool; coordinate Gradle builds with the root task:

```sh
python3 scripts/retentionkit/verify.py gradle \
  --repo /absolute/sdk-checkout \
  --task :sample-retention-only:assembleRelease \
  --property retentionProfile=review \
  --property retentionDependencySource=maven \
  --property retentionMavenVersion=ACTUAL_VERSION \
  --property retentionMavenRepo=/absolute/local/maven/repository \
  --output /absolute/new-evidence/review-maven-build

python3 scripts/retentionkit/verify.py gradle \
  --repo /absolute/sdk-checkout \
  --task :sample-retention-only:dependencies \
  --configuration releaseRuntimeClasspath \
  --property retentionProfile=review \
  --property retentionDependencySource=maven \
  --property retentionMavenVersion=ACTUAL_VERSION \
  --property retentionMavenRepo=/absolute/local/maven/repository \
  --output /absolute/new-evidence/review-maven-runtime
```

After actual builds, locate the merged **release** manifest inside the printed profile/source output directory, the published AAR/POM in the explicit local repository and the runtime graph in captured stdout. Feed those matching files to `verify.py composition --profile review`, requiring both `com.github.truongvimit:retention-review` and `com.github.truongvimit:retention-core`. Use the umbrella profile with the actual `retentionkit` POM and require every intended module. See [verification tools](../scripts/retentionkit/README.md) for the full evidence contract. Do not substitute a source manifest, debug graph, fabricated fixture or another profile's output.

## Runtime smoke surface

The launcher Activity shows profile/dependency/version, initialization status, real setup/config/entitlement state, the active text tool, pending entry and recent actual SDK event names. System bars and IME insets surround density-aware content padding. Input, result, selected tool and pending token survive recreation. The common manifest adds no permissions; selected SDK manifests supply their own permissions/components. Default `core` is harmless and contains no marketing surface. A no-IAP entitlement is explicitly non-subscriber; setup is incomplete until the user taps **Complete setup**. Ordinary notification grace remains the real 24-hour default.

- **Run text tool** performs uppercase conversion or a real word count and signals BusinessSuccess only after a nonblank operation completes. It does not invent review success.
- **Open word-count typed entry** starts an explicit Activity Intent through core routing. Cold/warm/onNewIntent capture is staged, retained through setup and consumed once at the allowed destination. Saved state retains the pending token and selected tool.
- Notification profile links the real notification module and offers explicit permission request plus foreground reminder refresh; returned results are displayed without claiming delivery.
- Widget profile links real pin-capability/request/refresh calls; automatic shortcuts are disabled in this proof app. A request is not pin confirmation.
- Feedback profile opens the actual optional exit-feedback UI; automatic feedback shortcut creation is disabled here. Opening Settings never claims uninstall.
- Review profile links actual eligibility request and manual Play Store APIs; automatic review remains driven by completed text operations and the module's standard gates/limits. Flow completion does not prove a card or rating.
- Umbrella profile installs using `RetentionKit.install`/`RetentionKitOptions`, captures through `kit.capture`, and routes through `kit.dispatchPending`, including the standard feedback destination. Navigation waits until setup is complete and the final Activity is resumed. A source Activity can finish its external scope just after the destination resumes: routing retries every 250 ms at most 20 times, cancels on pause/consumption and preserves the pending entry after the window ends. Resume, setup completion, a new entry or explicit status refresh starts a fresh bounded window. It also exposes actual widget, notification, feedback and Store actions.

Stable common view IDs `rk_proof_status`, `rk_proof_input`, `rk_proof_result`, `rk_proof_setup`, `rk_proof_run`, `rk_proof_entry` support the root-owned instrumentation/device smoke. There is no exported test receiver, hidden backdoor, debug timestamp bypass or fake-success toggle. Only the root/device owner may install/launch this app through ADB and add observed evidence to the ledger.

## Tooling checks and remaining acceptance

The Python tooling tests validate profile/property parsing, safety and umbrella composition rules using explicitly synthetic files. They do not run Gradle or prove this application compiles:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/retentionkit -p 'test_*.py' -v
```

The full project/POM matrix and publication inspections at `bf68f1e` are complete, including the reviewed handoff, widget return and consumer route readiness corrections. Historical consumer regressions comprise three Activity tests and 25 separate parser fixtures. Final integrated evidence records 549 scoped product unit tests, 15 root-owned API 36 cases and the minified Maven umbrella handoff smoke passing. See [VERIFICATION.md](VERIFICATION.md) for exact provenance and physical/Play/OEM limits. Ticket07 packaging/documentation is resolved within that scope; overall acceptance and release status remain with the root owner.
