# Corrected shared-flow acceptance

Status: in progress. Fixed correction base: `543de03`; delivery branch: `codex/retentionkit`.

Earlier direct-to-feature test/device results do not certify this correction. Evidence below is real execution with source provenance; no screenshot, build success, notification submission or Play callback is treated as proof of an unrelated outcome.

## Executed source checks

| Scope | Frozen source | Actual result | Evidence outside checkout |
|---|---|---|---|
| Common notification module | `b695be5` | 56 cases passed, no failures/errors/skips; release AAR assembled | `SDKOptimize/retentionkit-correction/notification-validation/b695be5/verification.json` |
| Core, feedback, facade/adapters, widgets, review | `ef94857` | 162 cases passed (49 + 28 + 28 + 37 + 20), no failures/errors/skips; five release AARs assembled | `retentionkit-correction/evidence/sdk-full-ef94857/result.json` |
| Shared Firebase plus isolated core consumer | `1ebcc2e` | 16 cases passed (10 + 6), no failures/errors/skips | `retentionkit-correction/evidence/remote-consumer-first/tests.json` and retained XML |
| Generic app and actual Activity unit regressions | `c8f0cbd` | 26 cases passed; later `5206487` changes Android instrumentation only | `retentionkit-correction/evidence/example-final/c8f0cbd-unit-xml/` |
| Actual Android instrumentation | Pending final APK execution | No pass claimed yet | Expected 17 explicit source methods after completed-Onboard/real-ad-close fixture fixes |

The SDK reentrancy/purpose regressions were first reproduced at `9984e98`: 15 executed cases, four expected failures, zero errors/skips. The same targeted suite passed 15/15 at `ef94857`. Those cases are included in the 162 module total, not added again. A further late-dialog-focus case is being reproduced and fixed before final SDK acceptance.

## Physical work in progress

The baseline Pixel 5 / API 34 package hash was `dce485fb…`; notification permission and screen/time/rotation settings were captured. Initial corrected APK `c1c33aa` was installed with `adb install -r`, preserving app data; installed hash matched `bed19ba5…` and test hash matched `48e24cdf…`.

Initial manual observations on that checkpoint:

- Actual normal Splash showed a clearly labelled AdMob test interstitial. Android Back closed it and Main resumed.
- The generic catalogue displayed Notes, Saved items, Text tools and Guide. Existing saved result remained present. The feature screen loaded and displayed an actual medium native test ad.
- Widget invitation opened the actual launcher pin dialog. Cancelling returned to the feature screen. This is observed cancellation/return, not pin confirmation.
- An explicitly accelerated DAILY envelope produced a real notification titled “Today’s tip: Notes”. Body tap showed an actual test interstitial; ActivityTaskManager recorded Main then the feature Activity, and the isolated ledger contained the consumed token. A later unrelated navigation to DeveloperChecklistActivity was recorded and device coordination requested; final destination UI acceptance will use the new full-chain instrumentation assertions.

Raw initial manual captures: `retentionkit-correction/manual/001–015*`. These captures can include surrounding device UI and are not public screenshot assets. The full manual matrix, actual native/inter callbacks, updated APK hash and exact source still need final recording.

A task-owned headless API 36 emulator was started with a separate data image and read-only/no-snapshot AVD mode. The first fresh launch hit a real offline gate caused by guest DNS failure; an explicit DNS restart is underway. No emulator first-open pass is claimed. Physical app/OS data were not cleared.

## Pending completion

- Final SDK focus-return correction, affected app rebuild and actual 17-case Android run.
- Complete physical notification body/CTA/pinned/Later/dismiss, widget actions/lifecycle, shortcut/feedback/native/system-confirmation cancellation, review and permission/recovery observations.
- Six fresh publications, twelve minified project/POM-only consumers and corresponding runtime/manifest composition checks.
- Independent Standards/Spec review, scoped fixes and affected reruns.
- Final report, exact branch push/PR status and task-owned worktree cleanup.
