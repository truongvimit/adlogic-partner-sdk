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

## Implementation acceptance

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
