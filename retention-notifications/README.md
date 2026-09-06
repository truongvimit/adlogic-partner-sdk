# Retention notifications

Seven configurable marketing flows with Android templates, durable calendar scheduling, local content rotation and entry routing. Select `retention-notifications` when the app needs notifications without widgets, feedback, Play review, ads, Firebase, WorkManager or Compose.

## Install and route

Install `RetentionNotifications()` from `Application.onCreate` inside `RetentionRuntime.install`; see [CONTRACT.md](CONTRACT.md) for the complete constructor and public signatures. Supply localized features, an explicit host Activity router, a monochrome small notification icon and the current entitlement. No-IAP apps explicitly use `NON_SUBSCRIBER`; `UNKNOWN` and subscribers suppress marketing. Call `SetupCompleted` when setup really completes. The initial setup grace is 24 hours.

A host EntryActivity handles both `onCreate` and `onNewIntent` (call `setIntent(newIntent)`):

```kotlin
when (val accepted = runtime.entries.capture(intent)) {
    is RetentionEntryAcceptance.Accepted -> {
        notifications.recordOpened(accepted.entry) // optional, truthful click telemetry
        // Forward this rewritten envelope/token through required setup.
        val entry = runtime.entries.pending(accepted.entry.token)
        if (entry != null && setupReady && destinationExists(entry.destination)) {
            if (runtime.entries.consume(entry.token)) openFeature(entry.destination)
        }
    }
    else -> openDefaultScreen()
}
```

The SDK uses immutable direct Activity PendingIntents. Each body/action has distinct category identity, destination and token; one-shot entries survive setup and are consumed once. Pinned action templates are reusable; core creates a fresh accepted token for each delivered Intent. The library adds no splash Activity, ad gate, clear-task flag or notification trampoline. Dismiss/Later uses a separate internal receiver that cannot launch UI.

## Campaigns and profile v1 defaults

All seven families default enabled, under the shared eligibility gates. They remain silent until the app has valid content/routes and the real permission, user and lifecycle conditions pass. Channels use `rk_retention_<campaign>` IDs by default. Channels are created once; existing user choices are preserved. Normal marketing channels default IMPORTANCE_DEFAULT; quiet reminder/pinned channels use IMPORTANCE_LOW with sound/vibration off. All templates use PRIVATE lockscreen visibility: the OS/user determines redaction and presentation.

| Family/key | Trigger | TTL | Cooldown | Daily cap |
|---|---|---:|---:|---:|
| DAILY / `daily` | Local 08:00, 19:00; background | 1 hour | 1 hour | 2 |
| WINBACK / `winback` | Local 11:00, 14:00; background and ≥48 hours inactive | 1 hour | 12 hours | 1 |
| ONBOARDING / `onboarding` | Active unfinished setup → confirmed process background +3 seconds | 5 minutes | 24 hours | 1 |
| AD_RETURN / `ad_return` | Actual AdClicked → confirmed process background +3 seconds | 5 minutes | 15 minutes | 2 |
| REMINDER / `reminder` | ProcessForeground or explicit foreground refresh, silent with Later | 6 hours | 15 minutes | 4 |
| PINNED / `pinned` | ProcessForeground or explicit foreground refresh, silent feature tiles; skip while active | 6 hours | 15 minutes | 2 |
| LOCKSCREEN / `lockscreen` | Local 11:30, 17:00, 20:00; background; skip while active by default | 1 hour | 1 hour | 3 |

Additional defaults (all key names below start with `notifications.`):

| Key | Default | Validation |
|---|---|---|
| `profile_version` | `1` | Exactly `1` |
| `enabled` | `true` | Strict lowercase boolean |
| `setup_grace_ms` | `86400000` | 0–365 days, milliseconds |
| `new_user_days` | `2` | 0–365 elapsed days after install |
| `winback.inactivity_ms` | `172800000` | 0–365 days, milliseconds |
| `onboarding.grace_ms` | `0` | 0–365 days from durable install time, not each onboarding screen |
| `background_delay_ms` | `3000` | 1–60000 ms and shorter than both token TTLs |
| `ad_return.token_ttl_ms`, `onboarding.token_ttl_ms` | `300000` each | 1–300000 ms |
| `lockscreen.replace` | `false` | Strict lowercase boolean |
| `daily.slots` | `08:00,19:00` | Up to 24 unique `HH:mm` slots |
| `winback.slots` | `11:00,14:00` | Same |
| `lockscreen.slots`, `lockscreen.new_user_slots` | `11:30,17:00,20:00` each | Same; new-user profile applies during the first two elapsed days |

Every `<campaign>` has `.enabled`, `.ttl_ms` (1 ms–7 days), `.cooldown_ms` (0–365 days) and `.daily_cap` (1–50), with defaults in the campaign table. An empty slot string explicitly schedules no slots. Malformed/duplicate slots, unsupported profile versions, unknown notification keys and malformed booleans reject the **entire** update. No key allows subscriber marketing, exact alarms, screen wake or full-screen intent.

Use the existing core cached PATCH API. Absent fields keep the last-known-good profile/defaults; explicit remove restores a default:

```kotlin
val result = runtime.updateConfig(mapOf(
    "notifications.daily.slots" to "09:00,18:30",
    "notifications.lockscreen.replace" to "true",
))
// Applied(revision) or Rejected(reasons); no second Firebase client or startup fetch.
runtime.updateConfig(emptyMap(), removeKeys = setOf("notifications.daily.slots"))
```

Onboarding is a separate marketing phase: active and unfinished, grace from install (default zero), known non-subscriber, no host/system transition, permission/channel and current background. Completion, abort, foreground, config/entitlement changes or a scoped external transition invalidate pending callbacks. Ad-return also requires a fresh click recorded in the foreground. Both tokens are deliberately cancelled on process restart: a cold unrelated exit cannot use an old click. These three-second timers are in-process best effort, not wakeup guarantees.

## Content, renderer and ownership

The default content provider uses the core localized feature catalogue. Daily and reminder use accessible system text templates; winback can use a bounded bundled BigPicture; lockscreen has a bundled expanded title/body/CTA/dismiss layout; pinned has up to four independent feature tiles. English and Vietnamese fallback copy is bundled, with the selected app locale supplied by core. More app-specific copy and localization belongs in the provider.

Supply `NotificationContentProvider` for a local list of `NotificationContent` per family, with stable unique IDs, catalogue destinations and optional bundled `imageRes`. Selection cycles deterministically without repeating the previous recorded content when multiple items exist. A removed/changed catalogue safely selects the first valid item. Failed rendering, blocked gates and failed notify do not advance rotation. Concurrent renders recheck the last selected content before claiming and may skip `rotation_changed`.

`NotificationRenderer` decorates a standard Android builder synchronously. Keep it short and local; receiver calls have Android's broadcast deadline. Only use the supplied main/action/dismiss PendingIntents in custom views. Standard fields and action list are reset by the SDK after decoration. The SDK cannot validate arbitrary PendingIntents a partner embeds in RemoteViews. It rechecks live config revision, lifecycle, entitlement, permission/channel, TTL and host transition after rendering and again after claim. No unmanaged image thread, network fetch or Activity retention is provided.

## Durable state and scheduling contract

The namespace `notifications.state.v1` belongs to this module. A synchronous transaction reserves an occurrence and its daily budget **before** `notify`; success finalizes last-submit, rotation and active occurrence together. A thrown `notify` releases its budget reservation, leaves a failed dedupe record and does not spend cooldown. A new trigger can retry; the same occurrence is not automatically retried. Blocked/invalid content never claims. A crash after claim leaves `claimed` with an **unknown delivery outcome**, conservatively reserving that day's capacity and cooldown. It is never reported submitted on restore. The bounded ledger retains eight days of records (maximum 4096); stale claims age out, while calendar handled dates remain. Exactly-once display through a crash is not promised.

A notify return reports `PostSubmitted`; the OS may still suppress or delay presentation. If the following persistence operation fails, `receiptPersisted=false`, diagnostics record failure and the existing unknown claim prevents immediate duplicate replay. Events are dispatched on main outside engine/store locks; sink errors do not repeat notify. No event claims displayed/impression/screen-woken.

Calendar alarms use `AlarmManager.setWindow(RTC_WAKEUP, ...)` with a ten-minute **requested window**, not a ten-minute delivery SLA. RTC_WAKEUP is the integer `0`, wakes the CPU if the OS delivers it and does not wake the screen. Android may defer inexact alarms for power restrictions/Doze. No exact-alarm permission, full-screen intent, wake lock or foreground service exists.

Each alarm has a stable campaign/slot PendingIntent and a saved revision, local date, due time and expiry. Reconcile durably replaces the desired set, cancels removed identities and rearms desired identities. Failed scheduling is diagnosed and retried on reconciliation. A queued old-revision or changed-time callback cannot post or restore removed slots. The next local occurrence is persisted/rearmed before processing a delivered one, even if current gates fail. A clock moved backward cannot repeat a handled date; expired occurrences are skipped, not replayed in a burst.

Calendar math uses `java.util.GregorianCalendar` on API 24: a missing DST time shifts forward by the gap, an overlapping time chooses Calendar's later standard-time occurrence, and identity is one local date/slot. Timezone/clock change triggers recomputation. Boot and own package replacement rearm from cached configuration via Application installation, without an Activity or network. Force-stop stays stopped until user interaction; ordinary process death differs. Notification timeoutAfter is enforced by Android on API 26+; API 24/25 have gate-time TTL and may retain an already posted notification until tap/dismiss/replace. Pinned/lockscreen active checks use actual tagged OS notifications and do not reset at midnight.

State and scheduler transitions are serialized inside this module; transactions are atomic in the main app process only, as core documents. Arbitrary multi-process writers are unsupported. Config/OS delivery are not one cross-system atomic transaction: revision is checked at commit, and reconciliation cancels disabled campaigns after concurrent state changes.

## Migration and validation

Disable the legacy engine and cancel its exact known alarm PendingIntents/notification IDs before enabling this module. The SDK does not guess old classes/request codes or cancel another module's notifications. Preserve channel IDs with `RetentionNotificationOptions(channelIds = ...)` where meanings match. Never delete a blocked old channel to bypass the user's choice.

`NotificationLegacyConfig.translate(presentLegacyValues)` maps known Translate keys to native overrides and reports `unsupportedKeys`. Review the unsupported list (premium/wake/content JSON and other subsystems require explicit product/content migration), then pass translated overrides to `runtime.updateConfig`. Missing legacy fields are not inferred. Old daily budgets/cooldowns are not silently imported; choose a rollout grace/kill switch if state continuity is required.

Tests run with `./gradlew :retention-notifications:testDebugUnitTest :retention-notifications:assembleRelease --no-daemon --console=plain --max-workers=2`. Robolectric checks gates, actual Android channel/manifest/PendingIntent adapters, seven templates, delayed cancellation, state restart, storage/post failures, concurrency, stale revisions, reduced slots, TTL/DST/timezone and rotation. Passing these does not prove physical/OEM/Doze timing. The root task owns Pixel ADB and final sample/minified/selective-publication validation under Tickets06–08.
