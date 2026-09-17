# OB flow validation — 2026-09-17

Device: Pixel 5, Android 14, ADB serial `14161FDD400111`.

## Automated checks

- App debug APK and SDK instrumentation APK: Gradle build successful, JDK 21.
- JVM regression: 213 OnboardKit tests and 40 ads tests passed. Includes catalog/order parsing, placement mapping, preload eligibility/deduplication, language callbacks, native ownership, swipe, splash and request holds.
- Added a late-remote-order regression after that run: all 39 pager lifecycle tests passed, including keeping the running pager intact when remote removes pending IDs.
- Test worker configuration used a temporary Gradle init script (`maxHeapSize = '1536m'`, `forkEvery = 1`, `maxParallelForks = 1`). The initial all-module run exhausted the default 512 MB worker heap; focused runs completed successfully.

`FlexibleOnboardingDeviceTest` uses the real Android pager/lifecycle with a deterministic provider and locally injected remote documents. No Firebase production configuration is changed.

| Device case | Pages | Result |
|---|---|---|
| default | ob1, full1, ob2, full2, ob3, ob4 | PASS; six native requests |
| reorder | ob4, full2, ob1, ob3 | PASS; four native requests in this order |
| missing | ob1, full1, ob2, ob3, ob4 | PASS; full2 removed, OB3 content retained without ad request |
| disabled | ob1, full1, full2, ob3, ob4 | PASS; app-disabled OB2 neither shown nor requested |
| hold | default list | PASS; zero requests during AdGate hold, six after release |
| empty | empty | PASS; zero requests |

Every nonempty device case also checks OB1 swipe lock by identity, other content swipe eligibility, fullscreen locked at bind then unlocked at impression, and no new requests when returning to the first page.

Reproduce each case in a fresh instrumentation process:

```sh
adb -s 14161FDD400111 install -r -t onboardkitorigin/build/outputs/apk/androidTest/debug/onboardkitorigin-debug-androidTest.apk
adb -s 14161FDD400111 shell am instrument -w -r \
  -e class io.onboardkit.ui.onboarding.FlexibleOnboardingDeviceTest \
  -e obCase reorder \
  io.onboardkit.test/androidx.test.runner.AndroidJUnitRunner
```

Use `default`, `reorder`, `missing`, `disabled`, `hold`, or `empty` for `obCase`.

## Artemis with real Google test ads

Initial trace: `56e89556-12ec-4e61-b783-551dc3637001` (Pro).
Completion trace: `fcbdf6d0-a8e4-4b7e-83a6-c5e68314ab1e` (Flash, completed successfully).

Observed on the sample app:

- UMP consent and notification permission completed; splash interstitial closed through its close control.
- Language selection scheduled all six OB native preloads between `23:48:19.757` and `23:48:19.818`; all six filled.
- Pager opened with `[ob1, full1, ob2, full2, ob3, ob4]` at `23:48:38.704`. Only then did `inter_after_ob3` begin loading; it filled at `23:48:40.116`.
- OB1 forward swipe stayed on OB1; Next advanced to Full1.
- Both Full1 and Full2 were entered and had filled test ads. Their 15-second auto-next timers advanced the flow while Pro was processing. Real-ad fullscreen swipe timing is therefore **inconclusive in this trace**; deterministic Pixel tests separately verify impression-gated swipe eligibility.
- Flash swiped OB3 → OB4 → exit, closed the test exit interstitial, dismissed the existing paywall and reached `Ad Showcase — Partner Integration Dashboard`.
- No app crash or stuck pager observed. No ad CTA was intentionally clicked.

Pro's video-review component failed repeatedly with FFmpeg trim/filter errors. Its run was stopped and the remaining UI checks completed with Flash using screenshots. This was a test-tool failure, not an app failure. Flash stdout confirmed successful completion; no separate stderr file was emitted for that run.

The final APK was installed on the Pixel after validation. Production Remote Config publishing and SDK publishing are outside this change.
