# Retention Review

Optional `retention-review` library, package `io.retentionkit.review`. It depends on retention-core, AndroidX Core and Google Play Review; no ads/Firebase/Compose/billing. minSdk24, compileSdk36, JVM17.

```kotlin
val review = RetentionReviewModule() // real PlayReviewTransport by default
RetentionRuntime.install(app, RetentionOptions(modules = listOf(review), /* shared host config */))
// Once the app has completed setup:
RetentionRuntime.get()?.signal(RetentionSignal.SetupCompleted)
// Use a stable operation ID when retrying delivery of the same business success:
RetentionRuntime.get()?.signal(RetentionSignal.BusinessSuccess("translate", operationId))
// A user explicitly presses Rate:
review.openStore()
```

`RetentionReviewModule(defaults: ReviewOptions = ReviewOptions(), transportFactory: ReviewTransportFactory = ReviewTransportFactory.PLAY)` implements RetentionModule, ID `review`. It responds to core BusinessSuccess signals automatically; `requestIfEligible(): ReviewActionResult` can explicitly re-evaluate already qualified successes at a host-chosen appropriate moment. No automatic prompt occurs just because the process opens/reconciles. `openStore()` is the manual action and never requests an in-app review or spends automatic counters, including when automatic review is disabled. Both methods return Scheduled or Unavailable(reason); Scheduled is not evidence of a displayed card/store. `snapshot(): ReviewSnapshot?` exposes persisted successesSinceAttempt, attempts, lastAttemptAtMillis, retryAfterMillis and inFlightPhase for diagnostics.

## Defaults and validated overrides

| Core config key | Default |
|---|---:|
| review.enabled | true |
| review.success_threshold | 5 |
| review.cooldown_days | 10 |
| review.max_attempts | 3 |
| review.retry_backoff_ms | 300000 |
| review.request_timeout_ms | 15000 |
| review.flow_timeout_ms | 120000 |

Update through `runtime.updateConfig(...)`, which validates the whole profile before publication. ReviewOptions supplies bundled defaults. Only strict booleans/bounded integers are accepted. Cooldown is measured from the last reserved launch attempt; backward wall-clock changes remain blocked. Success IDs are SHA-256 fingerprints in the module's `review.state.v1` namespace; feedback data is separate. The dedupe ledger retains at most4096 unique successes and fails closed at capacity rather than replaying old events; a diagnostic skipped reason identifies this unusual limit. No more successes are retained after the attempt cap. A launch consumes one threshold batch, preserving any additional successes recorded while ReviewInfo was pending.

## Request and callback semantics

The UI lease is acquired before requesting ReviewInfo. No request occurs while another foreground prompt/host UI is active, without a resumed Activity, or before setup completes. Only one request/launch exists for this module across the app process. Before launch the current lease/Activity, setup, enabled state and captured config revision are checked again. Destroyed/paused/background hosts, host ads, onboarding, external transitions and config changes cancel pending requests without consuming a launch attempt.

An **attempt is reserved transactionally immediately before invoking transport.launch**, after ReviewInfo and the final gate succeed. It increments attempts and lastAttemptAtMillis; it does not mean the Play card was displayed. A thrown/failed launch remains a spent attempt. Request failure/timeout/cancellation does not spend the launch cap and has a separate retry backoff. Duplicate/late callbacks are ignored by token+phase. Request and flow deadlines release leases and local callbacks. A cold attach cancels any persisted requesting claim with backoff; a recovered launching claim keeps its spent attempt and is reported as outcome-unknown. No `rated=true` field exists.

Immediately before handing off to Play/Store, the module obtains the final Activity from its lease, then starts its own scoped ExternalTransitionStarted signal. That signal intentionally revokes the lease; the module does not re-check it after revocation. A temporary Activity return observer and bounded timeout close only that scope on return/destroy/failure/completion. No second permanent process/resume observer is installed. Async callbacks never retain the Activity in module state or navigate after it is destroyed.

`ReviewTransportFactory`/`ReviewTransport` is the test seam. Request returns `ReviewInfoResult.Ready(ReviewToken)` or Failed(reason); launch returns FinishedOutcomeUnknown or Failed(reason); manual openStore returns whether a system intent was submitted. Production PlayReviewTransport uses ReviewManagerFactory/requestReviewFlow/launchReviewFlow. Manual Store uses market:// with a Play HTTPS fallback when no market Activity exists. A fake transport proves state transitions only, never production Play card display or a user's rating.

Events (all <=40 ASCII chars): `retention_review_success`, `_requested`, `_launch_attempt`, `_flow_unknown`, `_skipped`, `_failed`, `_timeout`, `_cancelled`, `_store_handoff`, `_recovered`. Observe actual outcomes through the shared RetentionEventSink; sink exceptions do not repeat a side effect.
