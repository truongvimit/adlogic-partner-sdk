# Feedback verification — 2026-09-07

Ticket04 implementation in `codex/retentionkit-feedback-review`. Automated verification only; physical device/OEM validation belongs to the integrated example task.

Command (PASS):

```sh
./gradlew :retention-feedback:testDebugUnitTest :retention-review:testDebugUnitTest :retention-feedback:assembleRelease :retention-review:assembleRelease --max-workers=2
```

Feedback: **18 executions, zero failures/errors/skips** (Robolectric4.16.1, API34; default screen additionally runs API24 and API36). Review contributes14 executions, separately documented. Test source: `src/test/java/io/retentionkit/feedback/RetentionFeedbackModuleTest.kt`.

| Behavior | Verified evidence |
|---|---|
| Keep, optional survey, Continue | No reason required; Keep does not launch Settings; Continue submits exact package App Info and terminal SYSTEM_HANDOFF only once. No uninstalled/rated event. |
| Feature rescue | One typed core FEEDBACK entry, real feature destination, session attribution and terminal handoff. Unknown feature/reason is blocked. |
| Failure and recovery | Rejected handoff restores OPEN plus selections and allows retry; thrown Activity launch and unavailable durable storage release own lease/scope without launch. |
| Recreation and process restoration | Default checkbox and custom controller restore selection/token; old controller cannot act; replacing runtime and rebuilding Activity reuses durable session without duplicating shown event. |
| UI/resources | Default screen loads at API24/36, manifest Activity is not exported, Back works; selected Vietnamese locale used, optional reason section removable, system/IME insets applied. |
| Entry consumption | Host UI blocked entry remains pending. A later safe route consumes once immediately before screen launch; reusable unmaterialized entry rejected. |
| Disable/lifecycle/timeouts | Disable closes visible/pending session, stale session expires, missing Activity start times out, scopes do not remove a host's unrelated external transition. |
| Shortcut ownership/quota | Existing host dynamic shortcut survives update/disable. Manifest + foreign dynamic count exhausts quota without removing either. Own template is reusable, has an explicit component and an action even if host router omitted one. |

Release artifact `build/outputs/aar/retention-feedback-release.aar` assembled. Separate `releaseRuntimeClasspath` resolution confirms only core/AndroidX dependencies, no review/ads/Firebase/Compose/billing. Test transport does not prove an OEM launcher accepted a shortcut or that system App Info became visible; actual Android submission and state boundaries are tested. Integrated device checks remain necessary for those platform outcomes.
