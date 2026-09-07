# RetentionKit

The umbrella supplies standard notifications, widgets, feedback and Play review through one shared runtime. Use the individual `retention-*` artifacts when only a subset is needed. No default facade signature or option references ads, OnboardKit, Billing or Firebase.

## Artifact status and a complete example

These artifacts are **unreleased on this branch**. The existing 5.1.1 SDK release does not include RetentionKit. In this checkout use `implementation project(':retentionkit')`; choose a single `retention-*` project instead for a smaller dependency graph. Local QA coordinates use `com.github.truongvimit:<artifact>:<exact-QA-version>` in the explicitly selected local repository. Use the documented release coordinates only after publication; local QA publication does not make a remote release available.

The full [partner example](../app/RETENTION_EXAMPLE.md) demonstrates the standard suite flow. The [isolated consumer](../sample-retention-only/README.md) proves selective packaging without vendor SDKs: [Application and shared feature catalogue](../sample-retention-only/src/main/java/io/retentionkit/sample/ProofApplication.kt), [umbrella install/capture/dispatch](../sample-retention-only/src/profiles/umbrella/java/io/retentionkit/sample/ProfileFactory.kt), and [Activity with real text operations, setup and restored pending routes](../sample-retention-only/src/main/java/io/retentionkit/sample/ProofActivity.kt). Build its umbrella profile to exercise the actual facade; it needs no ads, Firebase, OnboardKit or Billing.

Partners supply three things: localized feature identities/content, one explicit entry Activity, and truthful lifecycle/business state. The SDK supplies the repeated retention mechanics. An IAP app keeps entitlement UNKNOWN until verified; an app without IAP explicitly sets NON_SUBSCRIBER.

## Verified corrected integration

Frozen source69d0f71 passed279 fresh tests across nine affected modules, full example release R8, six fresh local QA publications and all12 project/POM-only consumer graph/manifest/R8 checks at `retentionkit-qa-20260907-69d0f71`. Identical production source86048d6 passed17 physical Pixel cases and a genuine first-install API36 long-onboarding route. See [current acceptance](../.scratch/retentionkit/correction-acceptance.md) for exact commands, APK hashes, OS/QA/ad limits and pending restoration/cleanup; [Vietnamese report](CORRECTION_REPORT.vi.md) gives partner steps. These are local QA artifacts, not a remote release.

## Existing suite: start here

For apps already using Ads/Onboard/Billing/Firebase, use [the three-point suite integration](SUITE_INTEGRATION.md). `RetentionSuite` supplies adapter/config composition, a Splash base and automatic Main/final-feature lifecycle binding; the app supplies its catalogue/router and one feature UI callback. Permission stays an explicit normal UI action. The manual capture/handoff examples below are the lower-level, vendor-free integration and are not additional required suite hooks.

## Vendor-free partner install

Call from `Application.onCreate`, including cold starts for receivers/providers. Supply the localized feature catalogue and one explicit host entry Activity:

```kotlin
val result = RetentionKit.install(this, RetentionKitOptions(
    featureProvider = RetentionFeatureProvider { context -> listOf(
        RetentionFeature("notes", context.getString(R.string.notes), R.drawable.ic_notes),
        RetentionFeature("saved_items", context.getString(R.string.saved_items), R.drawable.ic_saved),
        RetentionFeature("text_tools", context.getString(R.string.text_tools), R.drawable.ic_tools),
        RetentionFeature("guide", context.getString(R.string.guide), R.drawable.ic_guide),
    ) },
    router = RetentionSplashRouter(SplashActivity::class.java), // non-Onboard front door
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

## Standard Splash → Main → feature handoff

Every external notification/action, widget/shortcut and feedback/rescue tap starts the configured Splash, including a warm task. With the suite bridge, OnboardKit owns entry interstitial `inter_noti`, `inter_widget` or `inter_uninstall`, consent and legitimate skip policy; it completes the entry boundary before its terminal listener opens Main. Do not short-circuit ready users or redirect entry directly to a feature. Non-Onboard hosts use core `RetentionSplashRouter` and implement the equivalent Splash/setup boundary.

Capture once in Splash, preserve that rewritten materialized envelope/token in saved Activity state, and forward it through setup. Never recapture an original reusable OS template after recreation. The existing OnboardingListener can use:

```kotlin
bridge.mainIntent(context, MainActivity::class.java, outcome)?.let(context::startActivity)
```

It copies only that outcome’s extras and returns null for Aborted; no backlog selection. Other host listener work stays in place. Bind Main’s small helper during onCreate, before onStart:

```kotlin
private lateinit var handoff: RetentionMainHandoff

override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    // Inflate the actual Main UI first.
    handoff = requireNotNull(RetentionKit.get()).mainHandoff(this, state,
        RetentionRouter { context, entry ->
            when (entry.destination) {
                "notes", "saved_items", "text_tools", "guide" -> Intent(context, FeatureActivity::class.java)
                else -> null
            }
        })
}
override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handoff.onNewIntent(intent) }
override fun onSaveInstanceState(out: Bundle) { handoff.onSaveInstanceState(out); super.onSaveInstanceState(out) }
```

The helper waits for this Main to be resumed, setup complete and shared UI gates ready; it retries at most ten seconds per resume or window-focus gain and stops on pause/destroy. A long dialog can outlive that budget; Android focus return starts a new bounded attempt without another host callback. Closing the helper removes its focus listener. It saves only the selected token and successful-forward marker, holds bounded resume-ad suppression, and handles the SDK feedback destination itself. Features are forwarded **without consume** to the final Activity; failed routers stay retryable. `hasPendingEntry` exposes the selected ledger state. Main continuation requires that exact ONCE envelope; explicit menu `openViaEntry()` can start from ordinary Main using the same safe ENTRY gate. A configured Main permits ENTRY when the host predicate passes; it never permits automatic review/widget prompts. Modal/focus checks remain the host’s responsibility. No second interstitial or durable queue is created.

## Final feature consumption

In the final feature Activity, capture the forwarded ONCE envelope and restore the selected token on recreation. Use `setIntent(newIntent)` on a new delivery; clear the previous selection before accepting it. No automatic fallback to another pending entry:

```kotlin
val kit = RetentionKit.get() ?: return
when (val accepted = kit.capture(intent)) {
    is RetentionEntryAcceptance.Accepted -> pendingEntryToken = accepted.entry.token
    is RetentionEntryAcceptance.Rejected -> { pendingEntryToken = null; logRejectedEntry(accepted.reason) }
    RetentionEntryAcceptance.Absent -> { pendingEntryToken = null }
}
// When the final Activity is resumed and setup is complete:
val token = pendingEntryToken ?: return
when (val route = kit.dispatchPending(token)) {
    is RetentionDispatchResult.Navigate -> {
        pendingEntryToken = null
        openFeature(route.entry.destination)
    }
    RetentionDispatchResult.SdkHandled -> {
        // Scheduled SDK work can remain staged if its final gate blocks.
        if (kit.runtime.entries.pending(token) == null) pendingEntryToken = null
    }
    is RetentionDispatchResult.Unavailable -> {
        // Retain a still-staged selection for a later bounded retry.
        if (kit.runtime.entries.pending(token) == null) pendingEntryToken = null
    }
}
```

`dispatchPending` checks setup and explicit ENTRY readiness at both final checks, validates host destinations against the current shared feature catalogue, and atomically consumes a host entry before returning Navigate. Standard feedback destinations delegate to the feedback module before consumption, so partners need no magic-string branch. SdkHandled means accepted/scheduled by the SDK, not visible UI. A blocked route stays pending. A successful consume followed by process death is at-most-once, not an exactly-once navigation guarantee. The advanced `consume(token): RetentionEntry?` helper claims only host feature entries; callers using it own final readiness/destination gates.

Facade helpers publish actual host state/events: `setupCompleted()`, `onboardingChanged(active)`, `entitlementChanged(value)`, `businessSuccess(featureId, stableOperationId)`, `adClicked(stableClickId)`, and `permissionChanged()`. Review reacts to real successful business events. Do not manufacture successes from opening a screen or forward delayed/buffered analytics events as live ad-click state. The base `RetentionKit` never requests notification permission. The optional `RetentionSuite.requestNotifications(activity)` can own an explicit user-triggered request; an existing host/Onboard permission owner instead calls `permissionChanged` from its result.

Modules are exposed as `kit.notifications`, `.widgets`, `.feedback`, `.review` (nullable when disabled). Typical explicit actions are `widgets?.showPinInvitation()`, `feedback?.openViaEntry()` and `review?.openStore()`. Do not ask for a star rating before automatic Play review. Pin Requested/Unknown and review outcome-unknown are deliberate platform semantics; see each module's README.

Save the pending token with Activity state and do not recapture the original Intent on recreation. Route only the latest explicitly captured token or the selection restored with that Activity; never choose an arbitrary older entry from the pending ledger after consuming the current selection. Dispatch after core has observed the resumed Activity, for example from a posted callback after `onResume`; retry a blocked route when setup/UI state changes. A source SDK Activity can finish its external scope after the destination's first resume callback. The isolated consumer therefore uses a bounded five-second readiness retry while resumed, cancels on pause/consumption and retains the entry when still blocked. Do not poll indefinitely or treat SdkHandled as consumption.

## Bundled behavior and when it starts

| Area | Default behavior | Host responsibility |
|---|---|---|
| Daily notifications | Local 08:00/19:00 after completed setup and verified eligibility; unfinished-install grace is separate. | One notification permission owner, valid icon/content and a known non-subscriber. |
| Winback / lockscreen | COMMON_PLAN winback during days 14–45 at 11:00/14:00, up to three lifetime posts; lockscreen 11:30/17:00/20:00, skip an active item by default. | Preserve user channel choices; OS delivery/lockscreen presentation is conditional. |
| Onboarding / ad return | COMMON_PLAN unfinished onboarding after 24 hours from install, or a real ad click; both require confirmed background + 3 seconds. | Report real onboarding state; suite bridge forwards actual ad clicks once. |
| Reminder / pinned | Quiet foreground delivery when setup, entitlement and permission become ready; reminder cooldown 15 minutes and Later action. | Leave functional app notifications under their existing owner. |
| Widgets / shortcuts | Localized feature grid, pin invitation/request, owned dynamic shortcuts. | Ask from a suitable user action; pin Requested/Unknown is not Confirmed. |
| Feedback | Optional reasons, feature rescue, Keep and Continue to Android uninstall confirmation, with App Info fallback. | Optional branding/content only; no survey is required before Continue. |
| Review | 5 real business successes, 10 days between launch attempts, maximum 3 attempts. | Stable operation IDs; manual Rate calls `review.openStore()` independently. |

All notification families default enabled under their gates. Full keys, caps and TTLs are in [notifications](../retention-notifications/README.md); module options/overrides are in [widgets](../retention-widgets/README.md), [feedback](../retention-feedback/README.md) and [review](../retention-review/README.md). Null module options disable behavior in the umbrella but do not remove its declared artifacts; use selective artifacts to remove dependencies.

`runtime.signal(...)` returning true means accepted into the dispatch queue. It is **not** durable acknowledgement from subscribers or proof that every module completed. Use stable IDs when replaying events. If the host has a durable outbox, its own subscriber acknowledgement must be explicit and separate; it still cannot claim all SDK modules committed. The facade's `businessSuccess` helper returns Unit, not delivery confirmation.

## Customize content or UI while keeping SDK flow state

Keep the default templates unless the app needs a different presentation. Supply local, bounded content from the selected app-locale Context; destinations must exist in the shared feature catalogue:

```kotlin
val notificationOptions = RetentionNotificationOptions(
    smallIconRes = R.drawable.ic_notification,
    contentProvider = NotificationContentProvider { localized, campaign, features ->
        features.map { feature ->
            NotificationContent(
                id = "${campaign.name.lowercase()}_${feature.id}",
                title = feature.label,
                body = localized.getString(R.string.retention_try_feature, feature.label),
                destination = feature.id,
            )
        }
    },
)
```

For a custom feedback view, the SDK Activity still owns lifecycle, insets, restored session state and system handoffs. This compact example intentionally omits the optional survey; all navigation goes through its controller:

```kotlin
val feedbackOptions = FeedbackOptions(
    brandColor = 0xff2455c7.toInt(),
    uiFactory = FeedbackUiFactory { activity, controller, copy ->
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun button(label: String, action: () -> Unit) {
            column.addView(Button(activity).apply {
                text = label
                setOnClickListener { action() }
            })
        }
        column.addView(TextView(activity).apply { text = copy.title })
        column.addView(TextView(activity).apply { text = copy.body })
        controller.features().forEach { feature ->
            button(feature.label) { controller.tryFeature(feature.id) }
        }
        val slot = FrameLayout(activity)
        column.addView(slot)
        controller.bindNative(slot) // binds configured nativeContent, lifecycle cleanup stays SDK-owned
        button(copy.keepLabel) { controller.keep() }
        column.addView(TextView(activity).apply { text = copy.systemExplanation })
        button(copy.continueLabel) { controller.continueToSystem() }
        ScrollView(activity).apply { addView(column) }
    },
)
// Pass notifications = notificationOptions and feedback = feedbackOptions to RetentionKitOptions.
```

Imports are `io.retentionkit.notifications.*`, `io.retentionkit.feedback.*` and Android widget classes. The app supplies the notification icon and `retention_try_feature` string. For a survey, bind configured reason IDs to `controller.selectReason(id, selected)` and restore selection from `controller.state()`. SDK actions report Applied/Blocked/Failed; custom UI should present recoverable failures if needed.

A custom widget renderer receives `WidgetAction.pendingIntent`; use that supplied identity for each RemoteViews click. A custom notification renderer likewise uses the prepared content/action/dismiss PendingIntents. Replacing a layout does not replace routing, dedupe, cooldown or entitlement checks.

## Optional suite integrations

The following classes are optional; declare their existing SDKs explicitly. They are never loaded by the default facade.

### Host manifest and reboot recovery

Verify the **full application's merged manifest**, including vendor libraries. Google Mobile Ads25.3.0 can contribute a `tools:node="remove"` rule for `RECEIVE_BOOT_COMPLETED`, overriding the permission declared by the notifications library. A host using notification schedule recovery must explicitly retain it in its higher-priority manifest:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"
        tools:node="merge" />
</manifest>
```

The example's `verifyDebugRetentionHostManifest` and `verifyReleaseRetentionHostManifest` tasks read AGP's actual merged manifest artifact, require the unrestricted BOOT permission and enabled restore receiver/action, and run with their corresponding assemble task. Reports retain the manifest SHA under `app/build/reports/retention-host`. Selective consumers without Ads do not validate this host composition. BOOT reception still requires Android delivery, available credential storage and Application installation of the SDK; current-process UNKNOWN entitlement does not authorize a new marketing post. Actual reboot-before-launcher behavior needs device verification separately from these manifest checks.

```kotlin
val bridge = OnboardRetentionBridge(
    SplashActivity::class.java,
    mainActivity = MainActivity::class.java,
    hostCanPresent = { activity -> hostRetentionUiReady(activity) },
)
val result = RetentionKit.install(this, RetentionKitOptions(
    featureProvider = features,
    localeProvider = locale,
    router = bridge.router,
    uiHost = bridge,
    adapters = listOf(bridge, BillingRetentionBridge()), // omit billing bridge in apps without IAP
    eventSink = TrackkitRetentionEventSink(),
    configSource = FirebaseRetentionConfigSource(
        legacyKeys = RetentionLegacyConfig.keys,
        legacyMapper = RetentionLegacyConfig::overrides,
    ),
))
```

Packages: facade `io.retentionkit`, module/core types `io.retentionkit.*`, suite adapters `io.retentionkit.integration`, Firebase source `io.suite.firebase`. Install Tracker and its chosen sinks once, then existing OnboardKit, then RetentionKit. The facade does not install Tracker/Firebase/OnboardKit again. `BillingRetentionBridge` observes BillingKit's engine-owned authoritative `Billing.entitlement` StateFlow: UNKNOWN stays unknown; only VERIFIED_NON_PREMIUM maps to NON_SUBSCRIBER, and VERIFIED_PREMIUM maps to SUBSCRIBER. Late installation reads the current verified snapshot. It never uses cached/default `isPremium` or `awaitReady` as proof, and never launches/initializes billing. Declare BillingKit explicitly only when using this optional adapter.

`OnboardRetentionBridge` implements core `RetentionModule` and `RetentionUiHost`. Its standard router uses existing `SplashEntry.intent` and preserves the entry interstitial/AFTER_AD boundary and consent/Billing/ad policy. `entryAdPolicy=RetentionEntryAdPolicy.WITHOUT_SPLASH_ADS` is an explicit advanced opt-in; the standard example/QA never uses it. All initial routes still use the host entry Activity; the feedback module alone launches its session-protected internal Activity.

In the existing `OnboardingListener`, call `bridge.mainIntent(context, MainActivity::class.java, outcome)` and start its non-null result. Advanced hosts can retain `bridge.onOutcome(outcome)` to obtain the same passthrough Bundle. It marks Completed/Skipped as setup complete by default; pass `setupCompleted=false` if the host still has setup work. Aborted does not complete setup. The bridge also collects authoritative `OnboardingSdk.isFlowActive` and persisted completed state; it does not infer active UI from FlowStarted telemetry or replace the host's listener. `bridge.capture(intent)` is a convenience equivalent to facade capture when attached.

The bridge's synchronous UI gate checks actual fullscreen-ad state, current GMA/Splash Activity and authoritative onboarding activity. Optional `hostCanPresent(Activity)` adds the host's paywall/dialog/focus gate. For configured Main, return true when dialogs/focus permit explicit navigation; the helper separately validates its selected pending entry. Do not require a pending token in this predicate, because a new menu entry has none yet; PROMPT remains blocked on Main regardless. Construct a fresh bridge per fresh runtime install, including QA restart after uninstallForTests; do not reuse a detached adapter whose subscriptions were canceled. It never treats a generic consent/premium/remote-disabled ad reason as unsafe UI. Each SDK UI lease owns a bounded resume-suppression resource; expiry/pause/destroy/close releases only that lease. External/system transitions own separate tokens and preserve them when a UI lease is revoked for handoff. Finished-token cleanup is posted to the next main turn, so a core ProcessForeground callback cannot remove suppression between OPEN and WELCOME's synchronous return readers. Failure without departure clears next turn, without poisoning a later return or clearing other owners. The SDK feedback Activity is registered in the existing Activity exclusion list before it starts.

Actual ad clicks are forwarded once from the ads module's sole synchronous vendor-click point (`ERainLogEventManager.observeAdClicks`). No per-placement Retention callback wiring is needed when this bridge is installed. The observer is owner-scoped, removable, exception-isolated and never replays buffered Tracker events; shutdown removes only the bridge registration. Existing Tracker ad_click emission and daily cap counting continue unchanged. Do not also call `kit.adClicked` for those same suite clicks; keep the explicit helper for an app-owned ad system outside this bridge.

For host-controlled Settings/permission handoffs, `bridge.beginExternal(kind, durationMillis=120000)` returns AutoCloseable; close on result or launch failure. This scope does not launch a second permission UI. No bridge replaces `setResumeSkipPolicy`, changes global resume-enable flags, or installs a duplicate process lifecycle observer.

`TrackkitRetentionEventSink` forwards evidence-based core events to the existing Tracker. Core guards sink exceptions. `FirebaseRetentionConfigSource("retention_config")` uses suite-firebase's existing shared fetch client and remote-only values. Its port/controller live in core so a notification-only app can use them without the umbrella. See [core contract](../retention-core/CONTRACT.md) and [Firebase config notes](../suite-firebase/RETENTION.md).

## Config and migration

Remote documents use `{"version":1,"overrides":{...},"removeKeys":[...]}`. Missing keys preserve built-in/cached defaults; removal must be explicit. Complete-profile validation precedes atomic persistence. The async controller rejects stale generations, duplicate/late callbacks and results older than intervening host config writes. `kit.refreshConfig()` retries explicitly; no network is awaited by installation or a receiver.

Migrate one old owner at a time: map the feature catalogue/entry routes, preserve desired channel IDs through notification options, then stop the replaced app-owned alarms/widgets/shortcuts/prompts. The SDK does not delete unknown legacy IDs or uninstall another module's observers. Existing functional notifications remain host-owned.

| Previous owner | Migration action |
|---|---|
| App-owned notification alarms/receivers | Inventory exact IDs and PendingIntents; disable the old scheduler and cancel only those known entries before enabling the corresponding SDK campaign. Keep matching channel IDs through `channelIds` when their meaning is unchanged. |
| Existing widgets | Keep old provider instances functional until users migrate; Android bindings are not silently reassigned to a new provider. Use the custom provider/renderer seam for deliberate integration. |
| Launcher shortcuts | Remove only the app's documented legacy IDs. SDK quota calculations preserve foreign and manifest shortcuts. |
| Exit/uninstall screen | Route to feedback through the host entry Activity/facade. Continue opens Android confirmation by default (explicit App Info mode/fallback); remove any expectation of intercepting system uninstall or receiving an uninstall-success callback. |
| Rating prompt | Replace old star gates and separate manual Store navigation from automatic Play review. Old counters/cooldowns are not imported automatically. |
| Permission, ads and config | Keep a single permission owner. Reuse the suite adapters/shared Firebase client and remove duplicate click/return/config wiring for replaced flows. |

Choose a staged rollout/kill switch when old counters or scheduled state cannot be migrated. Do not delete/recreate a user-blocked notification channel to bypass its setting.

## Validation scope

Corrected SDK scope first passed162/162 unit tests and five release AARs at `ef94857`. The focus follow-up at `7c3470d` passed30 fresh facade tests and its release AAR;134 other module tests retain verified identical source/XML provenance, giving164 scoped results across invocations. See [verified entry contract](ENTRY_CONTRACT.md#verified-sdk-scope). Full app/device/consumer validation remains separate.

The results below are **historical checkpoints**, superseded for standard-flow acceptance by tickets10–13. They do not prove corrected Splash → entry ad/skip → resumed Main → feature/native routing. Current interface and behavior are in [ENTRY_CONTRACT.md](ENTRY_CONTRACT.md); corrected source/build/device evidence will be recorded after integration. No new device pass is claimed by this SDK patch.

The earlier [product evidence](/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-final-app-20260907-2ff967a/combined-product-tests.json) records 549 passing unit tests, zero failures/errors/skips: 532 unchanged non-app cases at SDK tree `bf68f1e` plus 17 freshly executed app cases at `2ff967a`. These are scoped results across recorded runs, not one invocation. Non-app counts: core 46, notifications 35, widgets 36, feedback 22, review 19, facade 12, Firebase 4, Billing 20, ads 162, OnboardKit 168 and Trackkit 8. Coverage includes reentrant/queued handoff cancellation, owned scope cleanup, routing, config persistence, Billing authority and existing OPEN/WELCOME/ad-click behavior.

At `bf68f1e`, all six project and six POM-only Maven consumers passed release R8/resource shrinking, all 12 actual runtime graph/merged-manifest composition checks passed, and all six matching local AAR/POM/Gradle metadata inspections passed using `retentionkit-qa-20260907-bf68f1e`. Standalone umbrella consumers did not pull the optional ads/OnboardKit/Billing/Firebase stack. The full example also passed debug/test APK and minified release assembly. See [consumer verification](../sample-retention-only/VERIFICATION.md) for exact artifacts, commands, historical checkpoints and source identity.

Root-owned [API 36 instrumentation](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/example-api36-run-6-final-verification.json) passed 15/15 cases on the final app tree. The earlier [minified Maven umbrella smoke](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/example-api36-manual/final-bf68-maven-umbrella-verdict.json) passed cold launch/setup, immediate feedback rescue to word count, a real business result, Store handoff/return and Keep. Physical/OEM coverage is limited; no Play card/rating or uninstall success is inferred. Retention artifacts remain unreleased.

Physical follow-up: the unlocked Pixel recorded15/15 Android cases at4356938 and additional manual checks. The latest7cb23c3 build/source and unfinished exclusive-device matrix are recorded in [the current physical ledger](../.scratch/retentionkit/physical-acceptance.md). Earlier counts on this page remain checkpoint evidence.
