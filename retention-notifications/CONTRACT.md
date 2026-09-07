# Notification module contract — profile v1

Package `io.retentionkit.notifications`, module `:retention-notifications`, minSdk 24. Depends only on core. Installs eight common marketing families; no Activity or network initialization.

```kotlin
val notifications = RetentionNotifications(
    RetentionNotificationOptions(smallIconRes = R.drawable.ic_notification)
)
RetentionRuntime.install(application, RetentionOptions(
    modules = listOf(notifications),
    featureProvider = RetentionFeatureProvider { context -> listOf(
        RetentionFeature("notes", context.getString(R.string.notes), R.drawable.ic_notes),
        RetentionFeature("saved_items", context.getString(R.string.saved_items), R.drawable.ic_saved_items),
    ) },
    router = RetentionRouter { context, entry -> Intent(context, HostSplashActivity::class.java) },
    initialUserState = RetentionUserState(entitlement = RetentionEntitlement.NON_SUBSCRIBER),
))
```

Host must signal SetupCompleted once its real setup finishes, OnboardingChanged(active) around unfinished onboarding, AdClicked(clickId) from actual ad clicks, and scoped ExternalTransitionStarted/Finished for permission/Settings handoffs. Core owns process lifecycle. Module never requests notification permission; the host's existing permission owner does this, then calls runtime.reconcile("permission_changed").

Public API:

- `RetentionNotifications(options = RetentionNotificationOptions()) : RetentionModule`, ID `notifications`.
- `NotificationCampaign`: DAILY, WINBACK, ONBOARDING, AD_RETURN, REMINDER, PINNED, LOCKSCREEN, APP_EXIT.
- `refreshForegroundNotifications(): Map<NotificationCampaign, NotificationOutcome>` refreshes REMINDER and PINNED only, through real gates. Core ProcessForeground invokes it automatically. It cannot force a background flow or bypass permission/grace/cap.
- `recordOpened(entry: RetentionEntry): Boolean` is an optional host hook **after** `runtime.entries.capture(intent)` returned Accepted and **before** final consumption. It checks that the entry is pending, then durably deduplicates telemetry. It does not route or consume the entry. Pass the rewritten Accepted entry for reusable pinned actions.
- `reconcile(reason)` recalculates/rearms calendar alarms; boot, time/timezone, own package replacement and core restore/config/foreground also call it. Permission granted later does not replay previously handled slots.
- `NotificationOutcome.PostSubmitted(notificationId, receiptPersisted)`, `Skipped(reason)`, `Failed(stage)`. Submitted describes notify returning; receiptPersisted=false means disk finalization failed after that call. Neither describes visible delivery.

Customize local content with `NotificationContentProvider.content(localizedContext, campaign, features): List<NotificationContent>`. Each content has stable id, title, body, destination, up to four `NotificationAction(id,label,destination,iconRes)` and optional bundled `imageRes` plus independent `expandedTitle` (defaults to title). All destinations must exist in the feature catalogue. Empty content skips; malformed/duplicate IDs or invalid routes fail before claim. Content rotation advances only after recorded submit and survives process restart. A single item may repeat.

`NotificationRenderer.decorate(NotificationRenderRequest, Notification.Builder)` is synchronous, short, local-only. Request carries the selected content and the SDK's main/dismiss/action PendingIntents. Use these same PendingIntents in custom RemoteViews. The SDK resets main/delete/standard actions, channel, small icon, expiry and safe marketing flags after decoration, then rechecks revision and all gates. It cannot inspect arbitrary clicks embedded in partner RemoteViews; do not create different intents there. No network or unowned async renderer callback is supported.

Events use `retention_noti_post_submitted`, `retention_noti_post_failed`, `retention_noti_failed`, `retention_noti_skipped`, `retention_noti_opened`, `retention_noti_dismissed`; campaign and skip reason are attributes. Dispatch occurs on main outside engine/store locks and is dropped at shutdown. No analytics dependency or inferred impression exists.

`RetentionNotificationOptions.preset` defaults to `NotificationPreset.COMMON_PLAN`; `LEGACY_SDK` preserves the old SDK cadence/channel identities. See the README for explicit source precedence. `NotificationLegacyConfig.keys` and `map(values)` allow the host's single config source to fetch/map actual legacy values; `translate(values)` remains an alias.

APP_EXIT is a confirmed completed-app departure; AD_RETURN wins for a valid ad-click departure. Source is OTHER with campaignId `app_exit`, so it uses the same explicit Splash route without changing core's source enum. Common exits persist an inexact alarm plus a live3s timer; no OS kill callback or precise delivery time is claimed. Persistent common lockscreen entries use REUSABLE templates to remain tappable after their delivery TTL and core's staged-entry retention window; each capture still becomes ONCE.

Every tap must reach actual host Splash, shared entry interstitial outcome, Main resumed, then destination. The module creates only the PendingIntent envelope; use the facade's standard entry controller or implement that host lifecycle chain. It does not treat recordOpened as navigation success.

Posting/display expiry is not route expiry. New notification entries omit an explicit `expiresAtMillis`, allowing accepted onboarding routes to survive the notification's display timeout. Core's seven-day retention remains unchanged (from creation for one-shot entries, from each delivered capture for reusable pinned/persistent-lockscreen templates). The send gate and Android timeout still enforce the campaign TTL. Prior posted/staged envelopes keep their old expiry; only newly created envelopes receive the correction. Schedule serialization retains the historical JSON `date` field and identical alarm/occurrence keys; the internal occurrence discriminator distinguishes calendar dates from departure identities.

Common lockscreen wake is automatic after an eligible matching post. `notifications.lockscreen.wake.enabled` defaults true for COMMON_PLAN and false for LEGACY_SDK; `notifications.lockscreen.wake_duration_ms` defaults20000 and accepts1–20000. A durable hard limit allows at most two screen-wake attempts per occurrence. A timed≤2s partial CPU lease may bridge bounded asynchronous post confirmation; screen leases use public SCREEN_BRIGHT_WAKE_LOCK with ACQUIRE_CAUSES_WAKEUP and omit ON_AFTER_RELEASE. No FSI, background Activity launch or privileged TURN_SCREEN_ON permission is used. Acquisition is not proof of physical display-on, and20s is not a forced display-off promise.

A pending matching posted Lockscreen survives transient UNKNOWN while wake is paused; verified subscriber cancels it. No new post or wake bypasses authoritative entitlement. Module dismissal/open/foreground, disabling a gate and shutdown release/cancel owned wake work. Already-posted wake eligibility does not reuse delivery TTL or frequency reservations. Old callbacks cannot affect a replacement's lease/budget. Standard compact/heads-up lockscreen layouts expose X and CTA within48dp; compact pinned exposes four allowlisted actions. Partner renderers remain configurable.

See README for every remote key/default, migration, persistence/crash semantics and platform limits. Tickets15/17 contain reopened default/wake evidence; original Ticket02/07 acceptance is historical. Final physical/OEM acceptance is recorded by the parent under Ticket18.
