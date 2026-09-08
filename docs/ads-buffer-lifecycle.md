# SDK ads buffer lifecycle

This change implements the SDK-only behavior agreed with the partner. Partner application
screens are not changed. The integration steps below must be applied when adopting the SDK.

## App resume

- Initial startup, enabling resume ads, and activity transitions do not load resume ads.
- A real process background transition captures `app_resume_load_delay_ms` from the current
  `ad_remote_config` document (default 2000 ms). Returning before dispatch cancels the schedule.
- Dispatch only while still background, enabled, eligible, empty/expired, and not loading.
  Existing cache and in-flight work are checked before the network precheck for a new request.
- Recovery is bounded: at most **3 dispatched requests per background stay**, within **120 seconds
  after the initial delay expires**. Offline checks run at most once every 5 seconds in that window
  and do not count as requests. Request preparation exceptions use the same bounded 5-second
  recovery without changing existing ownership or emitting a vendor outcome.
  Vendor failure/dispatch exception/request timeout uses the existing
  5s, 30s, 120s backoff; a later background waits any remaining backoff rather than losing its turn.
  A foreground return cancels every scheduled recovery. Repeated ON_STOP does not reset the budget.
- Returning to foreground shows only an already-ready ad on an eligible return. It never waits
  for a load or shows a callback arriving after the return decision.
- At 30 seconds, a request releases its loading slot and permits a bounded retry. Its callback
  may still fill the empty cache until a newer request dispatches or configuration/consent/premium
  invalidates it. Timeout cannot cancel Google's underlying transport; after replacement, the old
  callback is discarded so it cannot overwrite the new request. Duplicate terminal callbacks do
  not mutate the buffer or report a second vendor outcome.
- Ready ads survive foreground/background cycles. Their four-hour lifetime starts at request time,
  including when the callback arrives late. A result arriving already four hours old is rejected.
  Fill success ends recovery. Dismissal/failure to show does not refill; wait for the next background.
- Existing consent, premium, external-action suppression, and fullscreen exclusion still apply.
  Suppression is decided for the departure/return; an ad Activity is not a new user visit.
- Android may suspend or kill a background process. A scheduled load is best effort; an ad
  cache lives only in that process and is not persisted through process death.

## Remote delay and A/B testing

Edit the existing Firebase Remote Config JSON parameter `ad_remote_config`. Add this top-level
numeric field **alongside the existing placement objects**, preserving their IDs and settings:

```json
"app_resume_load_delay_ms": 2000
```

- Milliseconds, accepted range **0–86,400,000** (up to 24 hours). `0` means no additional SDK wait
  after process ON_STOP; AndroidX's process lifecycle delay still exists.
- Missing, negative, out-of-range, overflow or malformed values fall back to **2000**, without
  discarding the placement document. Integer strings are also accepted.
- Assign A/B variants of the JSON parameter with e.g. 0, 500, 2000 or 5000 for this field. The
  existing config-source refresh/activation path applies the document; no partner Activity edit
  or new Firebase fetch on background is needed. The delay is also supported in asset JSON.
- Each background stay snapshots its delay. A remote update affects the next stay, without
  moving an existing timer or resetting cache age/backoff. Debug builds keep their existing
  test-asset pinning; use test IDs in every test configuration.
- This is an SDK policy, not a claim that it reproduces LinguaPal's process-wide behavior.
- Evaluate show rate together with impressions/session and revenue/session. A decrease in
  loads alone can improve show rate without creating an additional impression.

## Resume telemetry

The existing request/vendor outcome/show funnel is preserved. `ad_skipped: load_timeout` marks
30 seconds without a terminal callback; it is **not** a vendor no-fill or a second terminal event.
A subsequent vendor fill still reports `ad_loaded`. If superseded/invalidated/expired, it also
reports `ad_skipped: fill_discarded`; otherwise it enters the cache for a later eligible return.
These counts diagnose stages, but do not by themselves prove that timeout dominates production
losses. A vendor fill is not evidence of a publisher charge or an impression.

## AutoBuffer interstitial group

- Configured, non-reserved placements form one frequency group (for example inter_all/back).
  Placements outside it, including splash and after-ob3, neither read nor stamp this clock.
- Start explicitly from the first content screen after onboarding, after remote/consent setup.
  The first preload waits the configured interval. Repeated start/resume never restarts it.
- Run preload scheduling only in the foreground. Background pauses scheduling, not the clock
  or cache. An in-flight result can still populate the cache.
- Closing a shown group interstitial starts the shared remote interval. A final waterfall load
  failure also starts the shared interval before retry. A failed individual tier is not a final
  failure. Load success never starts or resets an interval.
- A ready, unexpired ad is retained. Loading is deduplicated per placement. All managed load
  entry points, including explicit loads and topUpNow, obey activation, foreground and gate.
- At a show trigger, a ready ad is shown only if allowed now. Otherwise next is called exactly
  once, without waiting. A timer never displays an interstitial.
- Showing/preparing a group ad blocks another group show and replacement load until the first
  attempt ends. Lifecycle rejection before vendor show restores an otherwise valid cached ad.
- Failed load retry uses the remote interval, without exponential extension. With interval zero,
  automatic retries remain paced by the existing minimum polling period to avoid a busy loop.

## Partner migration

1. Initialize ads, remote configuration, and resume enablement as before. Enabling app resume
   now enables the lifecycle behavior without warming a buffer immediately.
2. Configure AutoBuffer placements and call start from the first actual content screen, including
   notification/deep-link/restored entry paths. Do not start it in Application.onCreate.
3. Keep using InterstitialAdManager.load/show with placement keys. Do not combine managed
   placements with raw ERain/Admob reload APIs, which cannot identify the placement group.
4. Remove app-side immediate reloads and temporary global interval overrides. topUpNow checks
   eligibility; it is no longer an interval bypass. stop disarms the buffer explicitly; ordinary
   process background/foreground is handled by the SDK.
5. Preload after-ob3 separately and call show at its transition. A missing ad proceeds immediately.

## Validation

Exercise public AppOpenManager lifecycle/load/show, InterstitialAutoBuffer start/stop/topUpNow,
and InterstitialAdManager load/show/callbacks with a controlled vendor boundary and clock.
Cover cancellation at the delay boundary, late fill retention, no refill, cache expiry, common
gate, splash exclusion, fixed retry, foreground pause, duplicate triggers, and exactly-once next.
Compare production show rate alongside impressions/session when the partner adopts the change.

## Historical validation — commit 722ef46 (2026-09-08)

- Full repository `./gradlew testDebugUnitTest :ads:compileReleaseKotlin
  :ads:compileReleaseJavaWithJavac --continue --console=plain`: successful.
- 407 tests, zero failures/errors/skips: ads 196, onboarding 202, trackkit 8, sample app 1.
- Verification ran in a temporary checkout of `b33362b` plus this lifecycle change only.
  Concurrent Facebook/onboarding edits in the shared workspace were excluded. A first shared
  run encountered two unrelated AdStepTimingTest failures and overlapping report writes;
  the isolated run is the authoritative result. Local Firebase build configuration was copied
  into that checkout without committing it.
- Pixel 5: AppOpenResumeLoadDeviceTest `allowed`, the same fixture `failure`, and
  AppOpenResumeHomeReturnDeviceTest all passed in separate instrumentation processes.
  Google official app-open test inventory was used for the positive load; the failure phase
  used a deliberately invalid, nonmonetized unit.
- Device dispatch logs: allowed phase had one SDK load dispatch about 2050 ms after process
  ON_STOP and obtained a real test fill. Failure phase had one dispatch about 2053 ms after
  ON_STOP; 20 subsequent explicit fetch probes produced no further dispatch during that stay.
- Standards review: no blocking findings. Spec review: no actionable lifecycle findings.
- Scope of device validation: background loading/failure and physical Home/return suppression.
  Long-held real-ad presentation requiring an operator was not rerun. Presentation, duplicate
  terminal delivery and expiry were verified at the public SDK seam with controlled GMA/time.
- That historical revision discarded callbacks after 30 seconds. The recovery update above
  supersedes that behavior. No production show-rate or revenue uplift was established by these tests.


## Recovery update validation — 2026-09-08

- Base: `7802f17`, including the existing `app_resume` telemetry commit. SDK implementation,
  configuration parsing, documentation and SDK tests only; no partner Activity implementation edits.
- Full `./gradlew testDebugUnitTest :ads:compileReleaseKotlin
  :ads:compileReleaseJavaWithJavac :onboardkitorigin:assembleDebugAndroidTest --continue --console=plain`
  passed. **441 tests**, zero failures/errors/skips: ads 223, onboarding 209, trackkit 8, app 1.
  Counts include only modules participating in that build, excluding stale reports from other projects.
- Public resume coverage: 36 load/lifecycle/config tests, 23 presentation tests, 7 telemetry tests.
  Regressions were observed failing before their fixes: offline reentry invalidation, bounded retry,
  late fill after timeout, remote delay, carried failure/timeout shortening the next delay, last-check
  offline recovery, preparation exception recovery, and old-result rejection cancelling a new timer.
- Standards review and Spec review both passed after their findings were fixed and retested.
- Full-run log: `/tmp/resume-final-verified.log` (local QA evidence, not a shipped dependency).
- Production impression/session or revenue improvement is not established by this validation.
- Pixel 5, official Google app-open test unit: remote delay **500 ms** passed the real load
  fixture. Process ON_STOP at 14:53:37.128, dispatch at 14:53:37.655 (**527 ms**), real fill at
  14:53:40.781. Logs: `/tmp/resume-device-500ms.log` and
  `/tmp/resume-device-500ms-dispatch.log`.
- Pixel 5 failure fixture is **incomplete**, not passed: the deliberately invalid test unit
  returned vendor code 1; the first retry dispatched 5008 ms after failure. ADB then stopped
  answering shell/logcat and remained offline after reconnect, before the third request and test
  completion could be confirmed. Do not use this partial run as proof of the device retry cap.
  The exact retry intervals, request cap and time window passed the automated public-seam tests.


## Completed device release round — 2026-09-08

The follow-up on production revision `c6d1048` completed **16/16 device cases** on Pixel 5 /
Android 14, including the previously interrupted retry cap: exactly three actual requests over
125 seconds. Remote delays, offline recovery, early return, retained cache, real fullscreen
presentation and AutoBuffer group gates also passed. **441/441 unit tests** and both release AAR
builds passed. No production SDK changes were required in this verification round.

See [the device release report](qa/device-release-2026-09-08.md) for observations, build hashes,
manual-step rerun history and the boundary between physical-device and deterministic coverage.
