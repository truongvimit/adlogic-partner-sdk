# retention-feedback

User-invoked feedback and feature rescue, followed by Android’s uninstall confirmation only when the user chooses Continue. This module cannot intercept the launcher/system uninstall flow or know whether an app was uninstalled. No ads, Firebase, Compose, survey service, or review dependency.

## Install and entry handling

```kotlin
import io.retentionkit.feedback.*
val feedback = RetentionFeedbackModule(FeedbackOptions(
    brandColor = 0xff2455c7.toInt(),
    featureIds = listOf("notes", "saved_items"), // optional subset of core feature catalog
))
// Include feedback in RetentionOptions.modules during Application initialization.
// Core router points every initial entry to your own explicit Splash/entry Activity.
// After setup/navigation gates are ready, with a resumed Activity:
feedback.openViaEntry() // Settings/menu: configured Splash -> entry policy -> Main-ready survey
// OR use the materialized entry returned by runtime.entries.capture(intent):
if (feedback.handles(entry)) feedback.handleEntry(entry)
```

`RetentionFeedbackModule.DESTINATION` is `retention.feedback`. The facade should dispatch `handles` before consuming a host feature entry. `handleEntry` requires a materialized `ONCE` entry, stages it idempotently, and consumes only after UI gates pass, immediately before launching the feedback screen. A blocked entry stays pending for a later explicit retry. Do not consume first. An OS launch failure after consumption is an at-most-once failure; a new user tap can use `openViaEntry()`. The advanced `show()` remains a direct prompt API and uses ordinary PROMPT gates; it is not the standard external entry path.

`openViaEntry()` / `show()` / `handleEntry()` return `FeedbackShowResult.Scheduled` when work is accepted onto main, or `Unavailable(reason)` for detached/rejected input. Scheduled does not mean visible; events report the outcome. `handles(entry)` is a pure routing check. `session(token)` exposes a durable session snapshot; `get()` returns the attached module for internal Activity restoration.

The final SDK screen is `io.retentionkit.feedback.RetentionFeedbackActivity`, declared `exported=false`. It requires an SDK-generated session token; never route a launcher shortcut directly to it. Host ad bridges should exclude this Activity from app-open/resume placements. Core external-transition scopes also cover opening the SDK screen and handing off to Android/host features.

## Content and custom UI

Defaults use app icon/name, an optional reason survey, up to four localized core features, Keep, and Continue to Android with a system explanation. Default `systemAction=UNINSTALL_CONFIRMATION` opens `ACTION_UNINSTALL_PACKAGE`; the library manifest declares `REQUEST_DELETE_PACKAGES`, required on Android P+. The user/system owns confirmation. If launch fails, `appManagementFallback=true` opens App Info only after a new scope/config/session check. Set `systemAction=APP_MANAGEMENT` explicitly to use App Info, or disable fallback to keep a failed confirmation retryable. English and Vietnamese resources are included. Override the `rk_feedback_*` string resources in other `values-xx` directories, or supply `FeedbackContentProvider` (called with core's selected app-locale Context). Branding uses `brandColor` and optional `appIconRes`.

`FeedbackOptions` fields: `enabled`, `shortcutEnabled`, `showReasons`, `featureIds`, `brandColor`, `appIconRes`, `contentProvider`, `uiFactory`, `launcher`, `systemAction`, `appManagementFallback`, `nativeContent`. Config keys `feedback.enabled`, `feedback.shortcut_enabled`, `feedback.show_reasons` accept strict booleans through core's last-known-good config.

A custom `FeedbackUiFactory.create(activity, controller, content): View` replaces the view inside the SDK Activity. Bind actions to the supplied controller:

- `state(): FeedbackSession?`, `features(): List<RetentionFeature>`.
- `selectReason(id, selected)` changes optional selection; unknown IDs are rejected.
- `keep()` ends the session and closes feedback.
- `tryFeature(featureId)` creates a typed core entry through the host router.
- `continueToSystem()` runs the configured confirmation/App Info policy without requiring any reason.
- `continueToAppManagement()` remains an explicit source-compatible App Info action; it opens `ACTION_APPLICATION_DETAILS_SETTINGS` for this package. It works with no reason selected.

Actions return `FeedbackActionResult.Applied`, `Blocked(reason)`, or `Failed(reason)` and require main-thread invocation with a current SDK UI lease. Custom views must use these actions, not invoke uninstall/review directly. The Activity is an AndroidX `LifecycleOwner` and retains weak Activity reference, system/IME inset handling, token restoration and back behavior. Session and selected IDs survive Activity recreation and process death; stale sessions expire after 30 minutes. Keep/back and feature/system submission are terminal; failure to launch restores OPEN so retry remains possible. System-screen return simply closes the completed feedback screen.

When called from a core subscriber, `Applied` means accepted; handoff waits for queued synchronous observers, then rechecks the same runtime, config revision, session and resumed source. Events report the eventual handoff or rejection. Disable/cancel during this boundary cannot open SDK UI, a rescue feature or a system screen; a blocked initial entry remains pending. Failed handoff restores OPEN only while the module remains enabled, otherwise CANCELLED. Shared core scopes expire after 120 seconds and release only their own token.

## Shortcut ownership and observable outcomes

Only dynamic shortcut ID `retention.feedback.open` belongs to this module. It adds/updates its own ID, subtracts foreign dynamic and manifest entries from the associated launcher Activity quota, and never replaces/removes other IDs. Disabling the shortcut removes its own dynamic entry and disables its own pinned copy. OEM quota/rate-limit rejection is safe and does not imply installation. Shortcut taps travel through the host entry router with a reusable core template, materialized separately per delivery.

Event names use `retention_feedback_*`: `entry_requested`, `requested`, `shown`, `reason`, `kept`, `feature_handoff`, `system_handoff`, `skipped`, `failed`. Reason events contain only configured stable IDs. `system_handoff` records `action=uninstall_confirmation|app_management` only after `startActivity` accepts that action; it does not mean uninstall. No event claims uninstalled, rated, or confirmed OS visibility. `FeedbackLauncher` is the injectable startActivity seam; production default uses Android `startActivity`. Own external scopes close on failure, source return/destruction, or 120-second timeout without clearing host scopes.

### Lifecycle-safe native slot and custom layout

`FeedbackNativeContent.bind(activity, lifecycleOwner, container: ViewGroup): AutoCloseable` lets the host attach its existing native helper. Return its owned cleanup handle. Feedback has no ads dependency and does not initialize another loader. Default UI creates a slot automatically when `nativeContent` is configured. A custom factory calls `controller.bindNative(slot)` once per slot; bindings close once on Activity destruction/recreation, and a fresh view binds to the new LifecycleOwner. The controller also exposes `lifecycleOwner`.

```kotlin
val options = FeedbackOptions(
    showReasons = false, // optional; no reason or rating prerequisite
    uiFactory = FeedbackUiFactory { activity, controller, content ->
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(activity).apply { text = content.title })
        fun action(label: String, run: () -> Unit) {
            column.addView(Button(activity).apply { text = label; setOnClickListener { run() } })
        }
        controller.features().forEach { feature -> action(feature.label) { controller.tryFeature(feature.id) } }
        val nativeSlot = FrameLayout(activity)
        column.addView(nativeSlot)
        controller.bindNative(nativeSlot) // no-op without nativeContent; lifecycle cleanup stays SDK-owned
        action(content.keepLabel) { controller.keep() }
        action(content.continueLabel) { controller.continueToSystem() }
        ScrollView(activity).apply { addView(column) }
    },
)
```

Import `android.widget.*` and `io.retentionkit.feedback.*`. Supply the host adapter through `nativeContent`; suite example uses its existing AdsManager/native helper and `native_uninstall`. Feature rescue calls the configured standard router again, so FEEDBACK travels through Splash/UNINSTALL entry ad policy before Main and the selected feature. Do not route it directly to a feature Activity.

The [entry integration contract](../retentionkit/ENTRY_CONTRACT.md) describes corrected host flow. Historical automated coverage and platform limits: [VERIFICATION.md](VERIFICATION.md).
