# RetentionKit core contract — v1

Package: `io.retentionkit.core`. The signatures below are implemented by the shared runtime; downstream code depends on this contract, not the reference apps. Android minSdk 24, compileSdk 36, JVM 17. `retention-core` depends on AndroidX lifecycle-process/core only; no ads, Firebase, Compose, review or billing. Notifications, widgets, feedback and review have separate implementations and public module guides; the [RetentionKit facade](../retentionkit/README.md) combines them and supplies optional suite adapters.

## Installation and lifecycle

```kotlin
val result = RetentionRuntime.install(application, RetentionOptions(
    modules = listOf(notificationModule, widgetModule, feedbackModule, reviewModule),
    featureProvider = RetentionFeatureProvider { localized -> listOf(
        RetentionFeature("notes", localized.getString(R.string.notes), R.drawable.ic_notes),
        RetentionFeature("history", localized.getString(R.string.history), R.drawable.ic_history),
    ) },
    localeProvider = RetentionLocaleProvider { app -> selectedLocaleContext(app) },
    router = RetentionRouter { context, entry ->
        Intent(context, PartnerEntryActivity::class.java)
    },
    initialUserState = RetentionUserState(entitlement = RetentionEntitlement.NON_SUBSCRIBER),
    initialOverrides = mapOf("notifications.enabled" to "true"),
    eventSink = RetentionEventSink { event -> trackSafely(event.name, event.attributes) },
))
val runtime = (result as? RetentionInstallResult.Installed)?.runtime
```

- Call from **Application.onCreate**, including cold receiver/provider process starts. Installation performs bounded local state restore, module attach and scheduling reconcile. It does not fetch network data or need an Activity. Provider code must tolerate `get() == null` before Application.onCreate; no ContentProvider auto-initializer is introduced.
- `RetentionRuntime.install(Application, RetentionOptions): RetentionInstallResult`; result is `Installed(runtime, reused)` or `Failed(reasons)`. Main app process only; secondary process installation returns Failed. Malformed state/config and integration exceptions return Failed instead of crashing app startup. Duplicate/invalid module IDs reject installation.
- `RetentionRuntime.get(): RetentionRuntime?` only returns a fully restored/attached runtime. Concurrent/repeated installs serialize and share the first successful instance; subsequent options do not silently replace it. Modules receive the runtime argument during attach because `get()` remains null until installation finishes.
- `RetentionRuntime.uninstallForTests()` unregisters callbacks/observers, closes leases and calls module shutdown in reverse order. It does **not** erase persistent state. Reinstall in tests models restored state, not uninstall/reinstall of the actual application.
- `initialUserState.entitlement` is authoritative for **each process installation**, default UNKNOWN. It replaces cached entitlement before module attach/reconcile; an IAP app resolves it later via EntitlementChanged. A no-IAP app explicitly supplies NON_SUBSCRIBER on every install. Other initialUserState fields are first-install defaults only: persisted setup/install/last-active milestones are restored, never reset to the new defaults.
- `RetentionOptions` accepts optional `clock: RetentionClock` and `store: RetentionStore` for deterministic tests; production defaults use system clock and SharedPreferences. No separate Firebase config source belongs in core.
- Core owns one ProcessLifecycleOwner observer and Application.ActivityLifecycleCallbacks. It tracks a weak resumed Activity (`runtime.activities.current()`), process foreground/background and last-active state. Do not add another observer just to infer process transitions. Ads/onboarding/permission ownership stays with host adapters. ProcessLifecycleOwner's debounce distinguishes internal Activity transitions from process background; it does not detect force-stop or guarantee background delivery.

## Module API and concurrency

```kotlin
class MyModule : RetentionModule {
    override val id = "notifications" // stable [A-Za-z0-9_.:-], max 128
    private lateinit var runtime: RetentionRuntime
    override fun validateConfig(config: RetentionConfigSnapshot): List<String> = emptyList()
    override fun attach(runtime: RetentionRuntime) { this.runtime = runtime }
    override fun onSignal(signal: RetentionSignal) { /* handle relevant signals */ }
    override fun reconcile(reason: String) { /* desired schedules from runtime.config */ }
    override fun shutdown() { /* cancel owned callbacks/jobs and release leases */ }
}
```

Callbacks must be short/local and may be called from non-main threads. Modules own/cancel their asynchronous work and marshal UI to main. Core serializes signal delivery with a single drainer queue, including reentrant signals. Queue locks are held only while adding/removing signals; module/subscriber/shutdown callbacks run outside those locks. A concurrent signal call may return accepted before its callback has run. Configuration snapshots and store transactions are synchronized independently; async callbacks must compare their captured revision to `runtime.config.revision` and recheck eligibility before effects. `reconcile` may be called concurrently with a signal or another reconcile; each module must serialize its scheduler/state transitions. Never wait for main-thread work while holding a core/store lock.

- `attach` failure is recorded and isolated; the failed module is shut down and excluded from future dispatch. Other modules still run. Failure is visible through `runtime.diagnostics.snapshot()`.
- `runtime.signal(signal): Boolean` means accepted for dispatch (false after shutdown), not durable subscriber acknowledgement or successful completion of every module. A concurrent caller can return before delivery. Durable user-state changes commit before observers receive the signal; a storage failure is diagnosed and that signal is not delivered. A host outbox must track its own explicit subscriber acknowledgement separately and reuse stable business IDs on replay; that acknowledgement still does not prove all SDK modules committed their effects.
- `runtime.subscribe(owner, (RetentionSignal) -> Unit): RetentionSubscription` returns AutoCloseable. Dispatch uses a snapshot; listeners may close/add subscriptions in a callback. Exceptions from one subscriber/module/event sink are diagnosed without preventing other subscribers. VM errors are not swallowed.
- Core reconciles active modules after install, ConfigurationChanged, EntitlementChanged, SetupCompleted and ProcessForeground. `runtime.reconcile(reason)` is also available for boot/time/package receivers.

`RetentionSignal` variants:

| Variant | Meaning / state effect |
|---|---|
| `SetupCompleted` | Persist setup=true, onboarding=false and first setupCompletedAtMillis; repeated completion does not reset grace. |
| `OnboardingChanged(active)` | Persist whether a host onboarding flow is currently active. |
| `EntitlementChanged(UNKNOWN/NON_SUBSCRIBER/SUBSCRIBER)` | Persist entitlement snapshot; UNKNOWN suppresses marketing. No-IAP apps explicitly use NON_SUBSCRIBER. |
| `BusinessSuccess(featureId, eventId = UUID)` | Business success; review module owns deduplication/threshold. Reuse eventId when replaying one business operation. |
| `AdClicked(clickId = UUID)` | Transient click signal; notification module owns expiring click token and delayed return logic. |
| `ExternalTransitionStarted(token, kind, durationMillis = 120000)` | Scoped system/external transition; revokes current UI lease and suppresses marketing until finished/expired. Duration 1..300000ms. |
| `ExternalTransitionFinished(token)` | End the matching external suppression only. |
| `HostUiChanged(owner, visible, durationMillis = 120000)` | Ads/paywall/other foreground UI exclusion; bounded duration 1..300000ms. |
| `ProcessForeground` / `ProcessBackground` | Normally emitted by core lifecycle. Module tests may deliver them explicitly. |
| `ConfigurationChanged(revision)` | Emitted after updateConfig commits; modules must read runtime.config, not trust an arbitrary stale signal revision. |

Transient ad-click/external-transition/lease state is cleared on a new process. Module implementations must choose cancellation of delayed click tokens on attach (recommended) or explicit durable TTL recovery; no long-lived boolean adClicked. `RetentionUserState` exposes setupCompleted, onboardingActive, entitlement, installedAtMillis, lastActiveAtMillis and setupCompletedAtMillis. Last-active advances on foreground, background and business success, so a long foreground session is not counted as inactivity. `runtime.isForeground` is transient and false on a cold background start.

## Persistent transactions and config

```kotlin
val claimed = runtime.store.transaction("notifications.delivery") { state ->
    if (state.boolean("occurrence:$occurrenceId")) false else {
        state.put("occurrence:$occurrenceId", true)
        state.put("attempted_at", runtime.clock.wallTimeMillis())
        true
    }
}
```

`RetentionStore.snapshot(namespace): RetentionState`; `transaction(namespace, (RetentionTransaction) -> T): T`. State has `string(key, default: String? = null)`, `long(key, default = 0)`, `boolean(key, default = false)`, `entries(): Map<String,String>`. Transaction adds `put(key, String|Long|Boolean)`, `remove(key)`, `clear()`. Callback failure rolls back the draft; commit is synchronous and occurs before return. A failed commit throws RetentionStorageException. Namespaces are atomic independently, **not** a cross-namespace transaction. Use a single namespace for a claim+budget+rotation operation that must be atomic. Never post/start UI/network inside a store transaction. Nested retention transactions are rejected to prevent overwriting an inner commit. Production store shares locks across instances of the same package/file; multi-process writers are unsupported. Corrupt persisted namespaces fail visibly rather than silently reverting to enabling defaults.

Storage keys `core.user`, `core.config`, `core.entries` belong to core. Downstream namespaces should begin with the module ID. The production file defaults to `retentionkit_state_v1`; constructors can supply another name for isolated tests. All fields are persisted strings; modules may serialize their own versioned JSON into a string.

- `runtime.config: RetentionConfigSnapshot` has immutable `revision: Long`, `values: Map<String,String>` and string/long/boolean helpers. Initial overrides seed the first snapshot only; subsequent installations restore the complete last-known-good snapshot. Explicitly removed keys remain absent through restart. Module defaults apply when a key is absent. Apply a validated updateConfig patch for changed host defaults/migrations instead of expecting install to overwrite cached configuration.
- `runtime.updateConfig(overrides, removeKeys = emptySet()): RetentionConfigResult` performs **PATCH**, not replacement. It validates the complete resulting profile with every installed module's `validateConfig` outside core locks, then compares the captured revision under the state lock, synchronously stores one whole revision, publishes it, and emits ConfigurationChanged. Concurrent writers cause validation to retry (up to 16 times; further contention rejects without committing). Invalid values/validator exception reject the entire update and preserve the previous revision. Removal is explicit; absent remote fields never erase defaults.
- Module validators must reject malformed typed values (helpers deliberately return a fallback; they are not validators). A config adapter uses existing suite-firebase shared fetch state and calls updateConfig after parsing. No Activity or network prerequisite is added here.
- Initial profile and persisted overrides are validated on reinstall. If newly shipped validators reject cached values, install reports failure; adapters must provide an explicit migration/reset rather than silently enabling defaults. Validators are called before attach and must not depend on runtime.

`RetentionClock`: `wallTimeMillis()`, `elapsedRealtimeMillis()`, `timeZone(): TimeZone`. Use wall clock for calendar/TTL/persistence; elapsed clock for in-process leases/delays. Notification local-slot/DST/rotation logic belongs to the notification module; core has no generic untested scheduler abstraction.

## Entries, routing and PendingIntent identities

`RetentionEntry(source, destination, actionId, token = UUID, campaignId = null, instanceId = null, createdAtMillis = System.currentTimeMillis(), expiresAtMillis = null, mode = ONCE)`.

Source enum: DAILY, WINBACK, ONBOARDING_ABANDONMENT, AD_RETURN, REMINDER, PINNED, LOCKSCREEN, WIDGET, SHORTCUT, FEEDBACK, OTHER. IDs are stable `[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}`. Destination identifies a host feature/action registry entry; it is not an unchecked web URL. Expires must be after created. Modules using test clocks explicitly set createdAtMillis.

```kotlin
val entry = RetentionEntry(
    source = RetentionEntrySource.WIDGET,
    destination = "notes",
    actionId = "open_notes",
    instanceId = widgetId.toString(),
    mode = RetentionEntryMode.REUSABLE,
)
val explicitIntent = runtime.createEntryIntent(entry) ?: return
val pending = PendingIntent.getActivity(context, 0, explicitIntent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
```

`createEntryIntent` validates the envelope and requires the router to return an explicit component in the host package. Core writes its encoded string extra and a SHA-256 **category** distinguishing source/campaign/action/destination/instance/token; it preserves host data URI and flags. Android PendingIntent filter identity includes categories, so different actions/instances cannot overwrite each other even with the same requestCode. The host component must actually be an Activity; module adapters use `PendingIntent.getActivity`, never click trampolines. The low-level runtime preserves router flags and does not add an interstitial. Standard non-Onboard integration uses `RetentionSplashRouter(SplashActivity::class.java)`, which deliberately sets NEW_TASK | CLEAR_TASK for every external delivery. Forward the exact rewritten envelope through Splash/setup and resumed Main before final feature dispatch. The optional Onboard bridge uses its existing SplashEntry entry ad policy; see [entry contract](../retentionkit/ENTRY_CONTRACT.md).

`RetentionEntryCodec` offers `validate(entry): String?`, `encode(entry): String`, `decode(raw)`, `read(intent)`, `write(intent, entry): Intent`, `identityUri(entry): Uri`. Read/decode return Valid(entry), Invalid(reason), or Absent and do **not** consume anything. The extra key is `RetentionEntryCodec.EXTRA_ENTRY` (`io.retentionkit.entry.v1`). It is versioned JSON, not a custom Parcelable requiring consumer keep rules. External entries are untrusted; hosts must still validate the requested destination against their own allowed registry.

```kotlin
// Host EntryActivity: use both onCreate and onNewIntent (call setIntent(newIntent)).
when (val accepted = runtime.entries.capture(intent)) {
    is RetentionEntryAcceptance.Accepted -> {
        val token = accepted.entry.token
        // Forward original encoded entry/token through splash, permissions, onboarding.
        // Do NOT consume here if the feature still needs setup.
        navigateThroughSetup(token)
    }
    else -> openDefaultDestination()
}

// Final destination routing boundary, after all required setup:
val entry = runtime.entries.pending(token) ?: return
if (runtime.entries.consume(token)) openFeature(entry.destination)
```

- `runtime.entries.capture(intent)` validates, stages durably and returns Accepted(entry), Rejected(reason) or Absent. `stage(entry)` is available when host already has a decoded ONCE entry; `pending(token)` and `pending()` read the durable backlog. `consume(token)` is atomic and succeeds once for a staged, unexpired token.
- REUSABLE templates (widget, dynamic shortcut, recurring pinned action) are materialized into a fresh ONCE token for **each delivered Intent**. Capture rewrites that delivered Activity Intent, so recreation or passing the same Intent through setup keeps its token; the OS-owned PendingIntent template remains reusable. Hosts must retain/forward the rewritten envelope (or token), and call setIntent for onNewIntent. Do not generate an ONCE token once for the lifetime of a widget.
- One-shot notification entries preserve their occurrence token. Replayed consumed tokens are rejected. Distinct payloads sharing one token are rejected. Pending capacity is 32; ledger TTL is seven days or the entry's shorter explicit expiry. Expired entries do not navigate. Consumption tokens are stored through process restart; lastActive/cooldowns do not infer that navigation succeeded.
- Consume immediately **before** final navigation and only after a valid destination is available. This is an at-most-once navigation claim, not an exactly-once promise through a crash between consume and navigation. The host may record a separate completion if needed.

## Foreground UI and marketing gates

`runtime.ui.acquire(owner, durationMillis = 60000, purpose = RetentionUiPurpose.PROMPT)` returns Acquired(RetentionUiLease) or Blocked(reason). A lease has token, owner, `isValid()`, `activity(): Activity?`, and idempotent `close()`. Only one exists at a time, expires within five minutes, and is invalidated on Activity pause/destroy, process background, onboarding or scoped host/system UI. Activity is held weakly. Check `lease.activity()` **again** on main immediately before an async UI launch; never cache the returned Activity in a long-lived callback. Always close in terminal callbacks and timeout paths. Releasing an expired/stale lease cannot release a newer owner's lease.

`RetentionUiPurpose.ENTRY` is only for an explicit selected entry; it uses `RetentionUiHost.canPresentEntry(Activity)` (default delegates to `canPresent`) at acquisition and every lease recheck. Automatic review/widget prompts and ordinary feedback.show retain PROMPT. ENTRY still enforces actual current resumed Activity, foreground, onboarding, other reservations and external transitions. `RetentionHandoffScope.forEntry(...)` retains this purpose after the intentional lease revocation, while its original constructor remains PROMPT/source compatible. It ignores only its own transition token at final checks, never another owner.

`ui.eligibility(purpose = RetentionUiPurpose.PROMPT)` returns RetentionEligibility.Allowed or Blocked(reason, detail). `RetentionSuppressionReason` covers common unavailable/config/user/lifecycle/permission/channel/cooldown/cap/duplicate/expired reasons; modules may put specific machine-readable details in the detail field or event attributes. `RetentionCapability` is Available, Unavailable(reason), or Unknown(reason), so pin-request accepted and support-unknown are not coerced into success.

`runtime.marketingEligibility(graceMillis = 86400000, requireBackground = true, phase = RetentionMarketingPhase.AFTER_SETUP)` checks runtime, setup, onboarding, entitlement, 24-hour grace measured from durable setupCompletedAtMillis, external transition and foreground. It applies to **marketing only**, never a host's functional notifications. Every notification module still checks its own enabled/campaign/user/permission/channel/inactivity/cooldown/cap/TTL/revision conditions immediately before posting. Core does not auto-create notification channels or request permission. A host permission/ads adapter remains the single owner.

The explicit `RetentionMarketingPhase.ONBOARDING` exception requires **active, unfinished** host-reported onboarding, with grace measured from install instead of setup. It still checks entitlement, external transitions and background. Notification abandonment passes `graceMillis = 0` and this phase after its confirmed-background delay; ordinary campaigns keep AFTER_SETUP. SetupCompleted immediately makes ONBOARDING ineligible (WRONG_PHASE). There is no generic ignore-setup flag.

## Diagnostics and evidence

`runtime.emit(RetentionEvent(name, attributes))` guards the optional sink. Sink failure does not invalidate a submitted side effect or spend another attempt. `runtime.diagnostics.record(component, message, level = INFO, error = null)` and `snapshot()` provide a bounded 100-record local ledger with timestamp, level and exception stack string. No analytics backend is included. Do not record user document text/PII in diagnostics or event fields.

Use truthful milestones: notification `post_submitted`, skipped(reason), opened, dismissed; widget pin_requested/pin_confirmed/unknown; review request vs launch-attempt vs flow_finished_unknown; feedback/system_handoff. A module's review/pin/timeouts tests belong to that module; the core tests establish state/identity/lifecycle/failure isolation, not platform delivery guarantees.

## Optional host UI integration

`RetentionHandoffScope(runtime, activity, token, kind, durationMillis = 120000, onClosed = {})` is the shared source-Activity scope for feedback and Review/Store; widget pin verification remains separate. Its constructor has no effects. Register it in the module's owner map/flight **before** calling `start()`, because ExternalTransitionStarted observers can synchronously cancel and close it. All scope methods run on main. The scope holds only a weak Activity and closes idempotently on source return after pause, destruction, explicit failure/cancel, or timeout (maximum five minutes). Module shutdown must close its scopes.

`scope.dispatch { activityOrNull -> ... }` runs on main after the current core signal queue drains. A signal raised inside an existing subscriber is accepted/queued, not synchronously completed; this barrier prevents platform launch before its observers run. The core continuation queue is bounded to 128 and cleared on shutdown, with no callbacks under locks or blocking waits. A scope closed/expired before execution supplies null. Do not capture the original Activity in the continuation. Dispatch rechecks foreground, resumed source, finishing/destroyed state, onboarding, other UI owners and the optional host gate; it ignores only this scope's external token, not other owners. The module must then recheck its runtime/config/session and launch directly without another partner callback. Never recheck the deliberately revoked original lease after starting a handoff.

`RetentionOptions.uiHost: RetentionUiHost = RetentionUiHost.NONE` is a vendor-free optional adapter. `canPresent(Activity): Boolean` is checked synchronously before acquiring a lease and again on every `lease.activity()` / `isValid()` read. Return false while host ads/paywall UI is active. Do not infer this from a generic ads-disabled/consent/premium predicate. Exceptions deny safely and enter `core.ui_host` diagnostics.

`onLeaseAcquired(owner, token, durationMillis): AutoCloseable` may acquire an owner-scoped bounded host resource (for example resume-ad suppression). It receives no Activity and must not retain one. Core closes the resource once on explicit close, elapsed expiry even without further reads, pause/destroy, host/system invalidation or shutdown. A stale lease cannot close a newer lease's resource. All adapter callbacks, including close, run outside coordinator locks. Callbacks must remain short/nonblocking; reentrant host state changes are checked before the lease is published. External transition tokens are separate owners: closing a UI lease during system handoff does not remove the transition's resource.

## Optional remote config source

Selective consumers can add `RetentionRemoteConfig(source: RetentionConfigSource, timeoutMillis = 10000)` to `RetentionOptions.modules`. The source port lives in core, with no umbrella/vendor dependency: `val id`, `fetch(timeoutMillis, RetentionConfigCallback): AutoCloseable`; callback outcomes are Document(json), Missing or Failed(reason). A source returns promptly and owns cancellation of its work. Callbacks can be synchronous or on any thread. The controller invokes sources off main, marshals results onto main, enforces a bounded timeout, cancels superseded requests, and ignores duplicate/late/shutdown callbacks. `refresh()` starts a new generation; `lastResult` reports Applied/Skipped/Failed.

Version-1 JSON is a **patch**, for example `{"version":1,"overrides":{"widgets.enabled":true,"review.max_attempts":2},"removeKeys":["notifications.daily.slots"]}`. Missing fields/remote documents do not erase defaults or cached overrides. Values are scalar strings/booleans/numbers; use module-documented string formats for compound values. Explicit removeKeys restores module defaults. Invalid types, versions, unknown root fields or conflicting set/remove operations are rejected; module validators reject the whole candidate before commit. `RetentionConfigParser.parse` exposes the same validation result for host tooling.

Core already persists each accepted config snapshot synchronously before publishing it. The source controller stores no competing cache: a cold receiver sees the last-good config before source refresh. Results are applied only against the captured config revision using core's existing optimistic commit; a newer host/config write wins even if it occurs during validation. No source result is used as permission or entitlement authority. `retention_config_applied/skipped/failed` events describe sync outcomes.
