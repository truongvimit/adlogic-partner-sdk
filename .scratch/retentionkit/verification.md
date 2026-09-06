# RetentionKit verification

## Baseline

- Base `632df43`; unmodified example `./gradlew :app:assembleDebug --console=plain --max-workers=2`: PASS (2026-09-07, 1m30s).
- Connected device: Pixel 5, Android14/API34, serial 14161FDD400111. This is the available real-device scope; other OEMs are not yet verified.
- Existing compiler/deprecation/resource warnings observed; baseline app build succeeds.
- Baseline `:ads:testDebugUnitTest :onboardkitorigin:testDebugUnitTest :trackkit:testDebugUnitTest`: PASS, 316 tests (149 ads, 159 onboarding, 8 trackkit), zero failures/errors/skips. Log outside repo: `/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-baseline-tests.log`.
- Baseline APK installed using `adb install -r`; launcher cold start `am start -W` succeeded (2225 ms). Evidence directory outside repo: `/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device`.

## PR setup

- Branch pushed through existing owner SSH identity. Draft PR creation via gh failed: `must be a collaborator`; active gh account only has READ, owner token invalid. In-app browser has no logged-in GitHub session. User was asked asynchronously to restore the existing owner login; implementation continues.

## Integrated bridge checkpoint

- Root at `64ffcfb`: `./gradlew :onboardkitorigin:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.onboardkit.ads.AppOpenResumeHomeReturnDeviceTest --console=plain --max-workers=2`: PASS on Pixel5/API34, 1 test, 45 seconds build/run.
- The test performs two actual Android Home/return cycles. Two process-lifecycle readers observe the same legacy click suppression on the first return; neither sees it on the next return. This is a physical regression check after the scoped-suppression changes, not proof of a real ad click or the new RetentionKit feature flows.
- Actual JUnit XML independently checked with `scripts/retentionkit/verify.py tests`: 1 passed, zero failures/errors/skips. Captured output `/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-bridge-device-home-return.log`; evidence hash/count record alongside it as `.json`.
- Partial05 implementer checks: full ads 159 + OnboardKit 164 tests passed for suppression/no-splash changes; active-flow 4 + splash 10 targeted tests passed after active-state addition. Final combined rerun remains pending.

## Selective consumer preflight

- Before final05 core/adapter integration, isolated project consumers for core, notifications and widgets each passed `assembleRelease` with R8/resource shrinking and no broad keep/dontwarn rules. These checks detect early packaging/API issues; the final publication/project/POM matrix remains pending.
- Exact captured commands and source commits: `/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-consumers-preflight/{core,notifications,widgets}-project/run.json` (core at35ee9c6; notifications/widgets atbb78c64). Durations46/42/41 seconds respectively.
- Core minified preflight APK installed and launched on an API36 Google Play arm64 emulator. Real UI showed setup-incomplete entry waiting, then completed setup and executed word count (`A useful text tool` → `4 words`). Separate asserted ADB scenarios passed cold typed entry, warm replay rejection and process-restart replay rejection. Evidence `/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/core-api36-routing-preflight/` plus `core-api36-*.xml`/PNG.
- Emulator was launched read-only/no snapshot save from existing Pixel_4_2 AVD. Boot initially showed a System UI ANR under build load; after Wait, UI checks passed and crash log was empty. Emulator stopped after this preflight to release RAM. This is emulator evidence, not physical API36 hardware coverage.
- Widget minified preflight APK installed on physical Pixel5; `am start` succeeded. UI inspection then found the device locked behind PIN, so no pin outcome is claimed. User was asked asynchronously to unlock; no credentials requested or attempted. Full physical feature UI acceptance is pending unlock.

## Ticket 01 — core/publication foundation

Status: **PASS** for Ticket 01 scope, 2026-09-07.

- 29 Robolectric tests: transactional namespaces, corruption/rollback, concurrent installs/patches, callback lock inversion, state restore, explicit config removal, current-process entitlement, setup grace, scoped onboarding, entry/reusable identity/consumption and weak-Activity UI leases.
- `:retention-core:testDebugUnitTest` and `:retention-core:assembleRelease` passed with `--max-workers=2`.
- All six new modules produced release AARs; five non-core libraries remain build scaffolds for downstream implementation.
- Publication POMs generated/inspected for all six. Core releaseRuntimeClasspath contains no ads/Firebase/billing/Compose. Settings and JitPack publication registrations are present.
- Detailed commands and limits: `retention-core/VERIFICATION.md`. Public APIs: `retention-core/CONTRACT.md`.
- **Not tested here:** ADB, physical device, notification post delivery, widget launcher outcome, Play review display, optional adapters, sample end-to-end behavior, minified consuming application. These remain downstream acceptance work.

## Implementation acceptance

### Integrated execution checkpoints (acceptance still in progress)

- Final05 source ac8c7d5:385 completed unit tests passed (ads162, OnboardKit168, core39, shared Firebase4, facade12), plus facade/Firebase release AAR builds. Billing adds20 tests; notification35, widget26, feedback18 and review14 suites passed in their implementing commits. These are scoped results; the final integrated regression run is still pending.
- At723a57c, six local Maven publications and all twelve selective/umbrella project-versus-POM consumer combinations passed release R8/resource shrinking, actual runtime dependency graphs and matching AAR/POM/merged-manifest checks. Maven consumers used exact QA version `retentionkit-qa-20260907-723a57c`, without project substitution or broad keep/dontwarn rules. Immutable evidence: `/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-consumers-20260907-723a57c`. Later widget/consumer fixes require updated affected evidence.
- Example14 unit tests and debug/test APK builds passed through89b9386, mergedce85e7f. The first API36 device suite at5898af5 ran11 cases:9 passed,2 failed. Pinned actions reached all four real destinations but ActivityScenario teardown rejected the intentionally mutated Intent identity; the outbox fixture incorrectly disabled review counting. Source fixtures corrected; these initial failures remain preserved in `retentionkit-device/example-api36-run-1/`.
- The next13-case attempt atce85e7f stopped all cases at the explicit notification-permission precondition: the earlier AGP run had uninstalled the app, and reinstall restored the default denied state. This is a harness setup failure, not feature acceptance. Evidence: `retentionkit-device/example-api36-run-2-precondition/`. Permission was then granted on the disposable emulator before rerun.
- AGP8.12 exposes a broken task `--serial` filter (ImmutableCollection.remove). Use `ANDROID_SERIAL` instead. It also uninstalls tested APKs by default; all subsequent connected runs set `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`. The option and behavior were verified in the locally cached AGP8.12 BooleanOption/UtpConfigFactory source. No physical app data was cleared.
- Actual minified umbrella UI on API36 confirmed launcher pin dialog/cancel and reason-free feedback Keep. Cancel exposed a bounded widget lifecycle gap: transparent launcher return left the external owner active until a full process foreground or120s timeout. Feedback rescue exposed a consumer retry race: host resumed before the finishing feedback Activity released its scope; the entry remained durable and routed correctly on the next resume. Both are being corrected, so these are findings, not final passes. XML/PNG evidence: `retentionkit-device/example-api36-manual/`.
- Physical Pixel5 remains securely locked (`dumpsys trust`: deviceLocked=1). The earlier asynchronous unlock request remains pending; no PIN was requested or attempted. Current API36 results are emulator coverage only.

Status values: PENDING, PASS, FAIL, PLATFORM LIMIT, HARDWARE UNAVAILABLE. Unit evidence and device evidence are independent; source inspection is not a device pass. Test counts are taken from completed JUnit XML, not console task names. Final checks run on the integrated branch.

| ID | Contract | Deterministic / build evidence | Physical-device evidence |
|---|---|---|---|
| C01 | Repeated install, invalid configuration, failing module/sink isolation | PENDING | PENDING cold Application start |
| C02 | Transactional state, restart, last-known-good config, invalid/missing fields | PENDING | PENDING process restart |
| C03 | Prompt single-flight, timeout, Activity destruction, host UI/external leases | PENDING | PENDING system return |
| C04 | Entry validation, action/instance identity, capture/consume, expiry, replay | PENDING | PENDING cold/warm/new Intent |
| N01 | Permission denied/granted later, disabled channel, setup/unknown/premium gates | PENDING | PENDING permission + channel |
| N02 | Daily and inactive winback slots, initial grace and new-user profile | PENDING | PENDING engine-driven sample |
| N03 | DST, timezone/clock change, stale/reduced/disabled schedule revisions | PENDING | PENDING reschedule inspection |
| N04 | Duplicate occurrence, side-effect failure, budget/rotation after submit | PENDING | PENDING actual post + actions |
| N05 | Foreground/config/user-state/TTL recheck before posting | PENDING | PENDING foreground suppression |
| N06 | Scoped onboarding abandonment and cancellation on completion | PENDING | PENDING sample flow |
| N07 | Delayed ad-return, click expiry, return/system/process cancellation | PENDING | PENDING background/return |
| N08 | Silent reminder, Later, cooldown and action routing | PENDING | PENDING shade action |
| N09 | Pinned action tiles and per-action destination | PENDING | PENDING shade taps |
| N10 | Lockscreen skip/replace across midnight and persisted rotation | PENDING | PENDING active/replace post |
| N11 | Boot/package/time reconciliation, Doze/TTL, force-stop relaunch | PENDING | PENDING available device scenarios |
| W01 | Pin supported/requested/confirmed/unknown, duplicate callback | PENDING | PENDING Pixel launcher cancel/confirm |
| W02 | Multiple widget instances, resize/update/delete/restore, locale | PENDING | PENDING launcher instances + resize |
| W03 | Owned dynamic shortcuts and disable reconciliation | PENDING | PENDING launcher shortcut |
| F01 | Optional reasons, Keep, feature rescue, Continue to App Info | PENDING | PENDING each UI action |
| R01 | Success threshold, persisted cooldown/cap, concurrent/late/failing callbacks | PENDING | PENDING genuine request outcome |
| R02 | Destroyed/background Activity, bounded UI ownership, manual Store | PENDING | PENDING handoff/return |
| I01 | OnboardKit source compatibility, durable destination, genuine no-ad entry | PENDING | PENDING splash/onboarding route |
| I02 | Existing OPEN/WELCOME policy, overlapping owners, failed external launch | PENDING | PENDING resume behavior |
| I03 | Shared Firebase source, missing remote, Trackkit exception isolation | PENDING | PENDING bundled/cached startup |
| P01 | Every selective AAR/POM and umbrella, no unrelated transitive modules | PENDING | Not a device check |
| P02 | Debug + minified release build, consumer rules and merged manifests | PENDING | PENDING installed debug smoke |
| P03 | Existing SDK regression suites | Baseline PASS 316; integrated PENDING | Targeted integrated tests PENDING |
| D01 | Partner install/config/custom UI/migration and callback semantics | PENDING documentation review | Reproduction instructions PENDING |

## Platform scope

- Pixel 5 API34 is available. Other launchers/OEMs and API24/API36 hardware: HARDWARE UNAVAILABLE unless a supported emulator is added and explicitly identified.
- Play controls whether the review card is shown and whether a review is submitted. An SDK completion is outcome-unknown; a sideloaded device run cannot certify production Play quota/display.
- Inexact delivery may be deferred by Android/Doze. Force-stop recovery starts with user interaction; there is no claim of automatic execution while the app remains stopped.
- The available package has notification permission granted at baseline. Device tests must restore permission and any global device settings they change; do not clear unrelated app data.
