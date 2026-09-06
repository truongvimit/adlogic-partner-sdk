# RetentionKit

The umbrella supplies standard notifications, widgets, feedback and Play review through one shared runtime. Use the individual `retention-*` artifacts when only a subset is needed. No default facade signature or option references ads, OnboardKit, Billing or Firebase.

## Minimal partner install

Call from `Application.onCreate`, including cold starts for receivers/providers. Supply the localized feature catalogue and one explicit host entry Activity:

```kotlin
val result = RetentionKit.install(this, RetentionKitOptions(
    featureProvider = RetentionFeatureProvider { context -> listOf(
        RetentionFeature("translate", context.getString(R.string.translate), R.drawable.ic_translate),
        RetentionFeature("camera", context.getString(R.string.camera), R.drawable.ic_camera),
        RetentionFeature("conversation", context.getString(R.string.conversation), R.drawable.ic_conversation),
    ) },
    router = RetentionRouter { context, _ -> Intent(context, EntryActivity::class.java) },
    localeProvider = RetentionLocaleProvider { app -> selectedAppLocaleContext(app) },
    // An app without IAP may explicitly supply NON_SUBSCRIBER. Otherwise keep UNKNOWN until verified.
    initialUserState = RetentionUserState(entitlement = RetentionEntitlement.UNKNOWN),
))
when (result) {
    is RetentionKitInstallResult.Installed -> { /* result.kit; result.reused is true on repeated install */ }
    is RetentionKitInstallResult.Failed -> logConfigurationProblem(result.reasons)
}
```

Install is bounded/local and does not wait for network or an Activity. Invalid config returns Failed. A failed optional module attach is isolated in core diagnostics. Repeated installs keep the first successful runtime. `RetentionKit.get()` returns the current facade or null; a runtime installed separately must be used through its own API rather than replaced by the facade.

`RetentionKitOptions.notifications/widgets/feedback/review` each accepts its module options; all default to standard behavior, and null disables that module. `adapters`, `uiHost`, `eventSink`, `configSource`, `initialOverrides`, `clock` and `store` are optional shared seams. The umbrella includes all four artifacts; null turns off behavior, not the declared dependency. See the module READMEs for customization, remote keys and platform limits.

## Capture once, route after setup

Use the same method from your entry Activity's `onCreate` and `onNewIntent` (call `setIntent(newIntent)` in the latter). Capture rewrites a reusable widget/shortcut envelope into one delivery token; forward these rewritten extras through splash/onboarding:

```kotlin
val kit = RetentionKit.get() ?: return
when (val accepted = kit.capture(intent)) {
    is RetentionEntryAcceptance.Accepted -> pendingEntryToken = accepted.entry.token
    is RetentionEntryAcceptance.Rejected -> logRejectedEntry(accepted.reason)
    RetentionEntryAcceptance.Absent -> Unit
}
// When the final Activity is resumed and setup is complete:
when (val route = kit.dispatchPending(pendingEntryToken)) {
    is RetentionDispatchResult.Navigate -> openFeature(route.entry.destination)
    RetentionDispatchResult.SdkHandled -> Unit
    is RetentionDispatchResult.Unavailable -> Unit // retain pending token for a later explicit retry
}
```

`dispatchPending` checks setup and core UI readiness, validates host destinations against the current shared feature catalogue, and atomically consumes a host entry before returning Navigate. Standard feedback destinations delegate to the feedback module before consumption, so partners need no magic-string branch. SdkHandled means accepted/scheduled by the SDK, not visible UI. A blocked route stays pending. A successful consume followed by process death is at-most-once, not an exactly-once navigation guarantee. The advanced `consume(token): RetentionEntry?` helper claims only host feature entries; callers using it own final readiness/destination gates.

Facade helpers publish actual host state/events: `setupCompleted()`, `onboardingChanged(active)`, `entitlementChanged(value)`, `businessSuccess(featureId, stableOperationId)`, `adClicked(stableClickId)`, and `permissionChanged()`. Review reacts to real successful business events. Do not manufacture successes from opening a screen or forward delayed/buffered analytics events as live ad-click state. RetentionKit never requests notification permission; the app's existing permission owner does so, then calls `permissionChanged` (foreground reconciliation also rechecks granted-later state).

Modules are exposed as `kit.notifications`, `.widgets`, `.feedback`, `.review` (nullable when disabled). Typical explicit actions are `widgets?.showPinInvitation()`, `feedback?.show()` and `review?.openStore()`. Do not ask for a star rating before automatic Play review. Pin Requested/Unknown and review outcome-unknown are deliberate platform semantics; see each module's README.

## Optional suite integrations

The following classes are optional; declare their existing SDKs explicitly. They are never loaded by the default facade.

```kotlin
val bridge = OnboardRetentionBridge(SplashActivity::class.java)
val result = RetentionKit.install(this, RetentionKitOptions(
    featureProvider = features,
    localeProvider = locale,
    router = bridge.router,
    uiHost = bridge,
    adapters = listOf(bridge),
    eventSink = TrackkitRetentionEventSink(),
    configSource = FirebaseRetentionConfigSource(),
))
```

Packages: facade `io.retentionkit`, module/core types `io.retentionkit.*`, suite adapters `io.retentionkit.integration`, Firebase source `io.suite.firebase`. Install Tracker and its chosen sinks once, then existing OnboardKit, then RetentionKit. The facade does not install Tracker/Firebase/OnboardKit again. Keep initial entitlement UNKNOWN until the host has trustworthy purchase verification; cached false is not proof of a non-subscriber.

`OnboardRetentionBridge` implements core `RetentionModule` and `RetentionUiHost`. Its router uses the existing `SplashEntry.intentWithoutSplashAds`: suppresses splash banner/interstitial requests, display and SPLASH_INTER checkpoint while retaining consent, permission and first-open setup under host policy. Later onboarding ads remain host policy. All initial routes still use the host entry Activity; the feedback module alone launches its session-protected internal Activity.

In the existing `OnboardingListener`, call `bridge.onOutcome(outcome)` and forward the returned Bundle to the final host Activity. It marks Completed/Skipped as setup complete by default; pass `setupCompleted=false` if the host still has setup work. Aborted does not complete setup. The bridge also collects authoritative `OnboardingSdk.isFlowActive` and persisted completed state; it does not infer active UI from FlowStarted telemetry or replace the host's listener. `bridge.capture(intent)` is a convenience equivalent to facade capture when attached.

The bridge's synchronous UI gate checks actual fullscreen-ad state, current GMA/Splash Activity and authoritative onboarding activity. Optional `hostCanPresent(Activity)` adds the host's paywall/dialog gate. It never treats a generic consent/premium/remote-disabled ad reason as unsafe UI. Each SDK UI lease owns a bounded resume-suppression resource; expiry/pause/destroy/close releases only that lease. External/system transitions own separate tokens and preserve them when a UI lease is revoked for handoff. Finished-token cleanup is posted to the next main turn, so a core ProcessForeground callback cannot remove suppression between OPEN and WELCOME's synchronous return readers. Failure without departure clears next turn, without poisoning a later return or clearing other owners. The SDK feedback Activity is registered in the existing Activity exclusion list before it starts.

For host-controlled Settings/permission handoffs, `bridge.beginExternal(kind, durationMillis=120000)` returns AutoCloseable; close on result or launch failure. This scope does not launch a second permission UI. No bridge replaces `setResumeSkipPolicy`, changes global resume-enable flags, or installs a duplicate process lifecycle observer.

`TrackkitRetentionEventSink` forwards evidence-based core events to the existing Tracker. Core guards sink exceptions. `FirebaseRetentionConfigSource("retention_config")` uses suite-firebase's existing shared fetch client and remote-only values. Its port/controller live in core so a notification-only app can use them without the umbrella. See [core contract](../retention-core/CONTRACT.md) and [Firebase config notes](../suite-firebase/RETENTION.md).

## Config and migration

Remote documents use `{"version":1,"overrides":{...},"removeKeys":[...]}`. Missing keys preserve built-in/cached defaults; removal must be explicit. Complete-profile validation precedes atomic persistence. The async controller rejects stale generations, duplicate/late callbacks and results older than intervening host config writes. `kit.refreshConfig()` retries explicitly; no network is awaited by installation or a receiver.

Migrate one old owner at a time: map the feature catalogue/entry routes, preserve desired channel IDs through notification options, then stop the replaced app-owned alarms/widgets/shortcuts/prompts. The SDK does not delete unknown legacy IDs or uninstall another module's observers. Existing functional notifications remain host-owned.

## Validation scope

Module tests cover config missing/invalid/stale/restart/timeout/source failure, shared-fetch concurrency/cancellation, facade cold/warm/reusable entry consumption, standard internal feedback routing, authoritative UI safety/lease cleanup, and both core-first/ads-first OPEN/WELCOME return-reader orders. Run `:retention-core:testDebugUnitTest :suite-firebase:testDebugUnitTest :retentionkit:testDebugUnitTest` plus existing ads/OnboardKit regressions with `--max-workers=2`.

Publishable AAR assembly does not prove optional-dependency R8 composition or OEM behavior. The integration acceptance includes minified selective/umbrella consumers and device smoke on the example. Neither unit tests nor a successful system API call claims a notification was seen, a widget was placed, a Play review was shown/rated, or an app was uninstalled.
