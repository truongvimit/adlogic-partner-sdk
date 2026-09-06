# RetentionKit verification

## Baseline

- Base `632df43`; unmodified example `./gradlew :app:assembleDebug --console=plain --max-workers=2`: PASS (2026-09-07, 1m30s).
- Connected device: Pixel 5, Android14/API34, serial 14161FDD400111. This is the available real-device scope; other OEMs are not yet verified.
- Existing compiler/deprecation/resource warnings observed; baseline app build succeeds.
- Baseline `:ads:testDebugUnitTest :onboardkitorigin:testDebugUnitTest :trackkit:testDebugUnitTest`: PASS, 316 tests (149 ads, 159 onboarding, 8 trackkit), zero failures/errors/skips. Log outside repo: `/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-baseline-tests.log`.
- Baseline APK installed using `adb install -r`; launcher cold start `am start -W` succeeded (2225 ms). Evidence directory outside repo: `/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device`.

## PR setup

- Branch pushed through existing owner SSH identity. Draft PR creation via gh failed: `must be a collaborator`; active gh account only has READ, owner token invalid. In-app browser has no logged-in GitHub session. User was asked asynchronously to restore the existing owner login; implementation continues.

## Implementation acceptance

Pending: per-requirement unit, packaging and device evidence will be added as tickets land. Do not count source inspection as a device pass.
