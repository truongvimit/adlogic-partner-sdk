# Device regression tests

Use an awake, unlocked Android device and the separate `io.onboardkit.test` APK. The sample/user app is not the instrumentation target. Back up test-package data and record notification/rotation settings before resetting fixtures; restore them afterwards.

```sh
./gradlew :onboardkitorigin:assembleDebugAndroidTest
adb install -r onboardkitorigin/build/outputs/apk/androidTest/debug/onboardkitorigin-debug-androidTest.apk
```

Run each parameter combination in a fresh instrumentation process with default test-package data. The remote-cache read and handled-notification phases intentionally retain data from their preceding write/deny phase.

## Onboarding ad return and fullscreen deadlines

```sh
adb shell am instrument -w \
  -e class io.onboardkit.ui.onboarding.OnboardingAdReturnDeviceTest \
  -e returnCase content_click \
  io.onboardkit.test/androidx.test.runner.AndroidJUnitRunner
```

Run `content_click`, `content_open`, `full_click`, `full_timeout`, `next_full_zero`, and
`next_full_no_fill` separately. These open and close a real test destination Activity through a
fake native provider, assert the selected pager page and completion reasons, and outwait the
fullscreen timer to detect duplicate navigation. The last two cases cover leaving an incoming
fullscreen page while the pager is settling (zero timeout and synchronous no-fill).

Also run `io.onboardkit.ui.onboarding.Ob3BackgroundDeviceTest` and
`io.onboardkit.ui.ob5.ObFullScreenAdPauseDeviceTest` in separate processes. OB3 counts background
time; standalone OB5 restarts its foreground countdown after resume.

## Splash ordering

```sh
adb shell am instrument -w \
  -e class io.onboardkit.ui.splash.SplashOrderingDeviceTest \
  -e splashCase recreate -e lfoParallel false \
  io.onboardkit.test/androidx.test.runner.AndroidJUnitRunner
```

Run each case with `lfoParallel=false` and `true`:

- `success`, `failure`, `timeout`, `late_fill`, `banner_budget`
- `recreate`, `mode_freeze`, `rotate`
- `inter_off`, `no_unit`, `premium`, `master_off`, `host_off`, `consent_denied`
- `same_time`, `other_route`, `home_pending`, `home_expired`
- `under_ad`, `under_ad_slow`, `under_ad_home`, `under_ad_recreate`, `after_ad`, `after_ad_recreate`
- `native_ready`, `native_loading`, `native_failed` (Language entry binds/joins/ends without retry)

These use real Activities, focus, Home and rotation with a controlled external ad provider. `rotate` must actually recreate the Activity. Test-only budgets are shortened; production timing is unchanged.

## Actual notification UI

Class: `io.onboardkit.ui.splash.SplashNotificationPermissionDeviceTest`.

Pass `-e notificationPhase` with `allow`, `deny`, `dismiss`, `recreate`, `off`, `granted`, `home_after_result`, `preload`, `preload_granted_home`, or `home_prompt`. The test clicks the actual system button; it does not synthesize permission results. For `handled`, run after `deny` in a new process without clearing data. Pre-grant notification permission only for `granted` and `preload_granted_home`. Add `-e lfoParallel true` to `preload` to cover group B.

The notification remains open longer than the test's splash wait budget. This verifies that prompt reading does not consume that budget.

## Native and cache

- `io.onboardkit.ui.language.LanguageNativeSwapDeviceTest`: pending/failing/timed-out LFO2, selection, confirm and late callbacks after departure.
- `io.onboardkit.ui.question.QuestionNativeRefreshDeviceTest`: refresh timing, old-ad retention and failure throttle.
- `io.onboardkit.ads.NativeOwnershipRealGmaDeviceTest`: Google test native, shared-load dedup, consumption, recreation and Home/return. Run with `-e nativeRefresh false` and `true`.
- `io.onboardkit.remote.RemoteConfigCacheDeviceTest`: `-e cachePhase write`, then `read` in a new process without clearing data.

Real GMA tests require connectivity and an actual fill. A vendor no-fill is reported as a failure to exercise the intended scenario, not silently counted as a pass. These tests do not establish revenue uplift or exhaust every mediation network. Natural one-hour expiry remains covered by virtual-clock unit tests.

## Content insets fallback and screenshots (5.2.9)

`io.onboardkit.ui.base.ContentInsetsScreenshotDeviceTest` opens the real language screen with
system bars visible and no ad provider. It saves a screenshot and content-padding measurements
inside the separate test package. Run each capture in a fresh instrumentation process:

```sh
adb shell am instrument -w \
  -e class io.onboardkit.ui.base.ContentInsetsScreenshotDeviceTest \
  -e captureName language-normal -e simulateMissingOverlay false \
  io.onboardkit.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as io.onboardkit.test cat files/insets-language-normal.png > language-normal.png
adb exec-out run-as io.onboardkit.test cat files/insets-language-normal.txt
```

On an API 33 emulator only, set `simulateMissingOverlay=true` and use a distinct capture name.
During one synchronous insets dispatch, the test temporarily reports SDK_INT=34 so AndroidX
selects Impl34 against a framework without systemOverlays(); it restores SDK_INT in `finally`.
This affects only the test process, not the ROM or device properties. The fixed listener must
keep the screen alive and preserve bar/cutout padding. The original listener crashes instead.
This is a controlled reproduction, not evidence that a particular production device spoofs its API.
