# retention-feedback

User-invoked feedback and feature rescue, followed by Android App Info only when the user chooses Continue. This module cannot intercept the launcher/system uninstall flow or know whether an app was uninstalled. No ads, Firebase, Compose, survey service, or review dependency.

## Install and entry handling

```kotlin
import io.retentionkit.feedback.*
val feedback = RetentionFeedbackModule(FeedbackOptions(
    brandColor = 0xff2455c7.toInt(),
    featureIds = listOf("translate", "camera"), // optional subset of core feature catalog
))
// Include feedback in RetentionOptions.modules during Application initialization.
// Core router points every initial entry to your own explicit Splash/entry Activity.
// After setup/navigation gates are ready, with a resumed Activity:
feedback.show() // user-invoked settings item
// OR use the materialized entry returned by runtime.entries.capture(intent):
if (feedback.handles(entry)) feedback.handleEntry(entry)
```

`RetentionFeedbackModule.DESTINATION` is `retention.feedback`. The facade should dispatch `handles` before consuming a host feature entry. `handleEntry` requires a materialized `ONCE` entry, stages it idempotently, and consumes only after UI gates pass, immediately before launching the feedback screen. A blocked entry stays pending for a later explicit retry. Do not consume first. An OS launch failure after consumption is an at-most-once failure; a user can still use `show()`.

`show()` / `handleEntry()` return `FeedbackShowResult.Scheduled` when work is accepted onto main, or `Unavailable(reason)` for detached/rejected input. Scheduled does not mean visible; events report the outcome. `handles(entry)` is a pure routing check. `session(token)` exposes a durable session snapshot; `get()` returns the attached module for internal Activity restoration.

The final SDK screen is `io.retentionkit.feedback.RetentionFeedbackActivity`, declared `exported=false`. It requires an SDK-generated session token; never route a launcher shortcut directly to it. Host ad bridges should exclude this Activity from app-open/resume placements. Core external-transition scopes also cover opening the SDK screen and handing off to Android/host features.

## Content and custom UI

Defaults use app icon/name, an optional reason survey, up to four localized core features, Keep, and Continue to app settings with a system explanation. English and Vietnamese resources are included. Override the `rk_feedback_*` string resources in other `values-xx` directories, or supply `FeedbackContentProvider` (called with core's selected app-locale Context). Branding uses `brandColor` and optional `appIconRes`.

`FeedbackOptions` fields: `enabled`, `shortcutEnabled`, `showReasons`, `featureIds`, `brandColor`, `appIconRes`, `contentProvider`, `uiFactory`, `launcher`. Config keys `feedback.enabled`, `feedback.shortcut_enabled`, `feedback.show_reasons` accept strict booleans through core's last-known-good config.

A custom `FeedbackUiFactory.create(activity, controller, content): View` replaces the view inside the SDK Activity. Bind actions to the supplied controller:

- `state(): FeedbackSession?`, `features(): List<RetentionFeature>`.
- `selectReason(id, selected)` changes optional selection; unknown IDs are rejected.
- `keep()` ends the session and closes feedback.
- `tryFeature(featureId)` creates a typed core entry through the host router.
- `continueToAppManagement()` opens `ACTION_APPLICATION_DETAILS_SETTINGS` for this package. It works with no reason selected.

Actions return `FeedbackActionResult.Applied`, `Blocked(reason)`, or `Failed(reason)` and require main-thread invocation with a current SDK UI lease. Custom views must use these actions, not invoke uninstall/review directly. The Activity retains lifecycle, weak Activity reference, system/IME inset handling, token restoration and back behavior. Session and selected IDs survive Activity recreation and process death; stale sessions expire after 30 minutes. Keep/back and feature/App Info submission are terminal; failure to launch restores OPEN so retry remains possible. App Info return simply closes the completed feedback screen.

When called from a core subscriber, `Applied` means accepted; handoff waits for queued synchronous observers, then rechecks the same runtime, config revision, session and resumed source. Events report the eventual handoff or rejection. Disable/cancel during this boundary cannot open SDK UI, a rescue feature or App Info; a blocked initial entry remains pending. Failed handoff restores OPEN only while the module remains enabled, otherwise CANCELLED. Shared core scopes expire after 120 seconds and release only their own token.

## Shortcut ownership and observable outcomes

Only dynamic shortcut ID `retention.feedback.open` belongs to this module. It adds/updates its own ID, subtracts foreign dynamic and manifest entries from the associated launcher Activity quota, and never replaces/removes other IDs. Disabling the shortcut removes its own dynamic entry and disables its own pinned copy. OEM quota/rate-limit rejection is safe and does not imply installation. Shortcut taps travel through the host entry router with a reusable core template, materialized separately per delivery.

Event names use `retention_feedback_*`: `requested`, `shown`, `reason`, `kept`, `feature_handoff`, `system_handoff`, `skipped`, `failed`. Reason events contain only configured stable IDs. `system_handoff` means `startActivity` accepted App Info; it does not mean uninstall. No event claims uninstalled, rated, or confirmed OS visibility. `FeedbackLauncher` is the injectable startActivity seam; production default uses Android `startActivity`. Own external scopes close on failure, source return/destruction, or 120-second timeout without clearing host scopes.

Automated coverage and platform limits: [VERIFICATION.md](VERIFICATION.md).
