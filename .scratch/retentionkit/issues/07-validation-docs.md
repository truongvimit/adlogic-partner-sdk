# 07 Device QA packaging and partner documentation

Type: task
Status: resolved
Blocked by: 06

Spec: ../spec.md

## Scope and acceptance

Own retentionkit/README.md plus other module READMEs as needed, README module tables, repeatable scripts/tests and .scratch/retentionkit/verification.md. Run full new-module tests, relevant existing regression suites, debug/minified release assembly, local Maven publication/POM and isolated dependency/merged-manifest checks. Root exclusively runs Pixel5 API34 ADB; record exact evidence for all acceptance rows with screenshots/logs when useful outside repo, no credentials/raw private data. Fix failures through scoped implementer commits; include migration instructions, config defaults, custom content/UI example and limitations. Do not claim unavailable OEM or Play production quota coverage.

Follow the shared implementation contract and acceptance ledger in spec.md. Commit only owned files; write implementation/test results under Answer and change Status to resolved when verified. Provide commit hashes and concise context pointers for the merger.

## Comments

Review S1 aligned this active ticket to `claimed` while validation was pending. The final Answer below records the completed packaging/documentation scope; the root owner maintains the overall acceptance ledger and external release/PR status.

## Answer

Completed partner defaults/customization/migration guidance, unreleased-artifact module tables, the functional isolated consumer and repeatable evidence tooling. The consumer respects system/IME insets, restores selected routes, preserves staged SDK entries and uses bounded readiness retry. The facade example clears consumed selection and never falls back to arbitrary older ledger entries.

Final immutable SDK/consumer source: `bf68f1e6b11e0c15e2e1f8ad64670b516529de33`; local QA `retentionkit-qa-20260907-bf68f1e`. All 12 project/POM-only release R8/resource-shrink builds, 12 actual runtime graph/merged-manifest composition checks and six matching AAR/POM/.module publication inspections passed. Maven graphs contain no project substitutions or unrelated vendor/suite stack; no broad keep/dontwarn workaround. Captured sources were clean/unchanged and evidence hashes were verified.

Integrated evidence records 532 unchanged non-app tests plus 17 fresh app tests at app-only delivery `2ff967a` (549 passed across scoped runs, zero failures/errors/skips), final debug/test/release app artifacts, and 15/15 root-owned API 36 cases. Root also passed handoff smoke on the final minified POM umbrella APK: immediate reason-free rescue to word count, actual business result, Store return and Keep. Historical regression evidence separately includes three consumer Activity cases and 25 tooling fixtures.

Context: [consumer verification](../../../sample-retention-only/VERIFICATION.md), [partner guide](../../../retentionkit/README.md), [matrix summary](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-consumers-20260907-bf68f1e/matrix-summary.json), [scoped product tests](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-app-20260907-2ff967a/combined-product-tests.json), [final API 36 JUnit](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/example-api36-run-6-final-verification.json).

The original physical checkpoint was PIN-locked. The unlocked follow-up has15/15 Android cases and additional manual checks at4356938, while full exclusive-device acceptance remains pending in ticket09/physical-acceptance.md; earlier counts above remain historical. No Play card/rating, uninstall success, remote publication or broader OEM guarantee is claimed. Historical module verification files remain labeled checkpoints; final root/device verdicts retain their scope and limitations.
