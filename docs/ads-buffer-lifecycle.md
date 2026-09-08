# SDK ads buffer lifecycle

This change implements the SDK-only behavior agreed with the partner. Partner application
screens are not changed. The integration steps below must be applied when adopting the SDK.

## App resume

- Initial startup, enabling resume ads, and activity transitions do not load resume ads.
- A real process background transition schedules one opportunity to load after two seconds.
  A foreground return cancels the opportunity if it has not dispatched.
- Dispatch only while still background, enabled, eligible, empty/expired, and not loading.
  No retry or refresh loop runs during the same background stay.
- Returning to foreground shows only an already-ready ad on an eligible return. It never waits
  for a load. A request that completes after that decision is cached for a future return.
- Ready ads survive foreground/background cycles, with their original four-hour expiry.
  Dismissal or failure to show does not request a replacement. The next background stay may.
- Existing consent, premium, external-action suppression, and fullscreen exclusion still apply.
  Suppression is decided for the departure/return; an ad Activity is not a new user visit.
- Android may suspend or kill a background process. A scheduled load is best effort; an ad
  cache lives only in that process and is not persisted through process death.

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

## Validation result — 2026-09-08

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
- Existing app-resume request timeout remains 30 seconds; late-fill reuse applies before that
  request expires. No production show-rate or revenue uplift has been measured by these tests.
