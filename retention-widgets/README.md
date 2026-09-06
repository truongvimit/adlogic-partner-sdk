# Retention widgets

`io.retentionkit.widgets` provides a localized action widget, a bounded pin invitation/coordinator, and owned dynamic feature shortcuts. It depends only on `retention-core`. No ad, Firebase, Compose, billing, service, alarm or background Activity-launch dependency is introduced.

## Install and route

Install in `Application.onCreate` so Android can restore a widget or deliver its pin callback after a cold process start:

```kotlin
val widgets = RetentionWidgets() // keep this instance, or use RetentionWidgets.get()
RetentionRuntime.install(this, RetentionOptions(
    modules = listOf(widgets),
    featureProvider = RetentionFeatureProvider { localized ->
        listOf(
            RetentionFeature("translate", localized.getString(R.string.translate), R.drawable.ic_translate),
            RetentionFeature("camera", localized.getString(R.string.camera), R.drawable.ic_camera),
            RetentionFeature("conversation", localized.getString(R.string.conversation), R.drawable.ic_conversation),
        )
    },
    localeProvider = RetentionLocaleProvider { app -> appLocaleContext(app) },
    router = RetentionRouter { context, entry ->
        Intent(context, EntryActivity::class.java) // optional suite bridge constructs a no-splash-ad entry
    },
))
```

The standard renderer uses a two-column grid for narrow/unknown-width widgets. A compact four-column row requires a reported minimum width of at least 300 dp and minimum height below 140 dp; minimum height alone can describe a launcher's landscape bound and must not force a narrow portrait widget into a row. Resize updates are scoped to each widget instance.

The feature provider is shared with other retention modules and receives the selected app locale. The default widget displays its first four features (normally three or four). Each action is an **immutable direct Activity PendingIntent**, with `RetentionEntrySource.WIDGET`, a reusable envelope, feature ID as destination/action, and its widget ID as instance ID. Re-rendering keeps PendingIntent identity stable; another action or instance has a different identity. Shortcuts use `SHORTCUT` entries.

In the host entry Activity, call `runtime.entries.capture(intent)` in both `onCreate` and `onNewIntent`. Forward the rewritten envelope through splash/onboarding, leave its token pending until the destination is ready, then navigate only after `entries.consume(token)` succeeds. Core's ledger is the authority for once-per-delivery consumption. This module never starts an Activity from a receiver or forces an ad before a feature. Capture/navigation and optional splash policy belong to the facade/host.

## Public API for facade and host UI

| API | Contract |
| --- | --- |
| `RetentionWidgets(WidgetOptions = WidgetOptions())` | A `RetentionModule`; attach through core once. |
| `RetentionWidgets.get(): RetentionWidgets?` | Current attached module; null after shutdown. |
| `pinCapability(): RetentionCapability` | Re-queries OS/launcher support; safe without an Activity. API 24/25 unavailable. |
| `showPinInvitation(): WidgetInvitationResult` | Main-thread user action. Shows bundled localized invitation if the core UI lease permits it. |
| `requestPin(): WidgetPinResult` | Main-thread user action, skips the invitation and asks the launcher. No reward/interstitial prerequisite. |
| `pinStatus(token): WidgetPinResult?` | Persisted evidence for the request; null if unknown to the ledger. No retained callbacks. |
| `configureInstance(appWidgetId, featureIds): Boolean` | Main-thread, installed owned instance only; zero to four distinct existing feature IDs. Empty restores defaults. |
| `instances(): List<WidgetInstance>` | Saved configurations filtered by current launcher-owned IDs. |
| `refresh()` | Re-render installed widgets and reconcile dynamic shortcuts. Call after in-app locale/catalogue changes. |

`WidgetPinResult.Requested(token)` means the platform accepted the request, **not that a widget was added**. Only a matching random request token plus the launcher's callback ID, an installed ID belonging to the configured provider, and an ID absent from the request's baseline can produce `Confirmed(token, appWidgetId)`. Duplicate callbacks and reattribution to another instance are rejected. A verified late callback may promote `Unknown` to `Confirmed` for up to 24 hours. The ledger retains at most 32 request capabilities; evicted tokens fail closed.

`Duplicate(token)` prevents overlapping pending system prompts, including after a restart. API 24/25 and unsupported launchers return `Unavailable`; API refusal and exceptions release the lease/transition. `Blocked` means onboarding/host/system UI, background, another prompt, or no resumed Activity. The coordinator does not add subscriber/grace gates to a user-initiated widget action.

Android supplies no negative pin callback. A real pause→resume of the originating Activity without confirmation, a background→foreground return, source Activity destruction, timeout (default 120 seconds), or a disable operation produces `Unknown(token, reason)`. These do not claim cancellation or installation. Dismissing the SDK invitation itself emits `retention_widget_invitation_dismissed` and never calls the launcher. Pin results/events have no Activity reference or host callback to retain across lifecycle changes. A temporary observer holds the originating Activity weakly; an initial/self resume without an actual pause is ignored. Return processing is deferred one main turn and rechecks both the current Activity and pending request, so a queued verified callback can win. Every terminal path unregisters this observer and releases only its matching transition, including storage failures; a failed write emits no fabricated persisted outcome. After a storage outage the durable request may remain pending until the next successful expiry/reconciliation, but it cannot retain the Activity observer or UI suppression.

The SDK invitation holds its dialog weakly, reserves a 60-second core lease, and closes when the lease/config/lifecycle becomes invalid. Immediately before requesting the launcher, it checks the lease's weak Activity and configuration revision. It then emits scoped `ExternalTransitionStarted("widgets.pin:<token>", "widget_pin", timeout)`; this intentionally revokes its lease. Matching finish signals occur on confirmed/failed/unsupported/unknown/timeout/return/shutdown. They never clear another owner's suppression. The suite bridge can use these core transition signals to suppress resume ads; the widget module itself does not modify global ad flags. SDK dialog lease observation and vendor-specific synchronous safety gates belong to the optional suite/core UI adapter, not telemetry inference.

## Configuration and customization

Core config updates are patches, validated before application. Recognized keys:

| Key | Default | Validation |
| --- | --- | --- |
| `widgets.enabled` | `true` | Exact `true` or `false`. |
| `widgets.shortcuts.enabled` | `WidgetOptions.shortcutsEnabled` (`true`) | Exact `true` or `false`. |
| `widgets.pin.timeout_ms` | `120000` | Integer 1000–300000. |

`WidgetOptions` contains `providerClass`, `renderer`, `featureIds`, `shortcutsEnabled`, `maxShortcuts` (default 3, range 0–4), and `reservedShortcutSlots` (default 1, range 0–4). Collections are copied when the module is constructed. Default one free shortcut slot leaves capacity for the feedback module; set zero explicitly when this app has no such need. Foreign/manifest IDs consume additional capacity.

`RetentionWidgetRenderer.render(localizedContext, WidgetInstance, List<WidgetAction>): RemoteViews` is the local synchronous custom rendering seam. `WidgetInstance` provides `appWidgetId`, `featureIds`, `minWidthDp`, `minHeightDp`; `WidgetAction` provides `RetentionFeature` and the SDK-created immutable `pendingIntent`. Bind these PendingIntents to your layout's click targets. Keep rendering short and local, without UI/network/config side effects. Exceptions fall back to the standard renderer and enter core diagnostics. Custom RemoteViews must use supported views and valid host resources; the host launcher ultimately inflates them. Config revision is checked after custom providers/renderers and before publication.

The standard RemoteViews layouts use a two-column grid or compact horizontal row for short widgets. The default and minimum resize heights allow room for icons plus two-line labels. Built-in strings include English and Vietnamese; app feature labels/icons come from the shared provider. Apps may override `rk_widget_*` strings with other translations. System `LOCALE_CHANGED` updates widgets; call `refresh()` for an app-selected locale.

For custom size/preview metadata, subclass `RetentionWidgetProvider`, declare it with your `android.appwidget.provider` metadata, and pass its class in `WidgetOptions.providerClass`. Remove the default provider from the merged manifest using `tools:node="remove"` to avoid an extra picker entry. Keep the provider and pin receiver non-exported. System broadcasts/explicit pin PendingIntents still deliver to these components. App `onUpdate`, resize, delete and restore callbacks delegate automatically through the subclass. Install the module in the main process only, as required by core.

Installed instances keep independent selections and sizes. Restore moves saved old IDs to new IDs before updating routes. Startup intentionally preserves old configurations until the restore broadcast arrives. Manual picker additions get the same rendering without being misreported as a confirmed pin request. Disabling the module cancels pending flow state, replaces its widget surfaces with a passive localized empty state, and removes its dynamic shortcuts. It never deletes user widgets or another provider's IDs.

## Shortcut ownership

Feature shortcut IDs use `io.retentionkit.widgets.feature.<feature-id>`, with exact published/reserved IDs persisted in `widgets.shortcuts`. Reconcile uses `addDynamicShortcuts` and removal of ledger-owned IDs only; it never calls `setDynamicShortcuts`, `removeAllDynamicShortcuts`, or removes/disables other modules' shortcuts. A pre-existing ID collision outside the ledger is not adopted or overwritten.

Available quota is platform maximum per launcher Activity minus unique foreign dynamic/manifest IDs for that Activity minus the reserved slot, capped by `maxShortcuts`. No launcher Activity or a throttled/platform failure defers shortcut publication and logs diagnostics without failing widget installation. Reconcile retries on core foreground/config changes or `refresh()`. Existing user-pinned shortcuts are not removed by dynamic reconciliation. Install ordering may change the exact count; each module must continue respecting shared platform capacity and other owners.

## Evidence and validation

Events use `retention_widget_*` names within 40 ASCII word characters. Observed events include `invitation_shown`, `invitation_dismissed`, `pin_requested`, `pin_confirmed`, `pin_callback_rejected`, `pin_unknown`, `pin_failed`, `deleted`, and `restored`. Tokens, widget IDs, counts and reasons are attributes. An event sink failure cannot crash the widget coordinator.

The pin callback is the sole mutable PendingIntent: explicit internal receiver, fixed action/component/data, random persisted token, one-shot, `FLAG_MUTABLE` on API 31+, legacy mutability on API 26–30. Android fills `EXTRA_APPWIDGET_ID`; immutable callbacks would discard it. Widget click PendingIntents remain immutable. See [AppWidgetManager.requestPinAppWidget](https://developer.android.com/reference/android/appwidget/AppWidgetManager#requestPinAppWidget(android.content.ComponentName,%20android.os.Bundle,%20android.app.PendingIntent)) and [PendingIntent immutability](https://developer.android.com/reference/android/app/PendingIntent#FLAG_IMMUTABLE).

Run `./gradlew :retention-widgets:testDebugUnitTest :retention-widgets:assembleRelease --max-workers=2`. Robolectric tests cover API 24/25/26/34, real callback fill-in delivery and receiver validation, restart/duplicate/unsupported/unknown/late confirmation, scoped suppression, invitation dismissal/lifecycle, actual RemoteViews inflation/localization, instance identity/restore/resize/delete, custom renderer/provider behavior, stale config, and shortcut quota/ownership. These tests do not assert OEM launcher acceptance, visible pin UI, actual widget placement, or API 36 launch behavior; those require the integrating app's device smoke test.

Device-discovered correction validation: 36 widget tests (including 10 return/cleanup/layout regressions) and release AAR assembly passed. Tests use the observed 207×128 dp minimum option bounds with a 206×222 dp portrait surface; actual launcher cancel/confirm/resize retesting remains part of the device acceptance ledger.
