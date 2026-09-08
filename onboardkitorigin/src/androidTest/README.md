# Device regression tests

Use an awake, unlocked Android device and the separate `io.onboardkit.test` APK. The sample/user app is not the instrumentation target. Back up test-package data and record notification/rotation settings before resetting fixtures; restore them afterwards.

```sh
./gradlew :onboardkitorigin:assembleDebugAndroidTest
adb install -r onboardkitorigin/build/outputs/apk/androidTest/debug/onboardkitorigin-debug-androidTest.apk
```

Run each parameter combination in a fresh instrumentation process with default test-package data. The remote-cache read and handled-notification phases intentionally retain data from their preceding write/deny phase.

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
- `under_ad_home`, `after_ad`
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
