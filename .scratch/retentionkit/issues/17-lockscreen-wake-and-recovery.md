# Implement canonical lockscreen wake/recovery

Type: task
Status: claimed
Blocked by: 15
Base: c40d18d
Owner: codex/retentionkit-lockscreen-wake

Implement supported channel-gated bounded screen wake with canonical duration/retry/pending-state rules and cancellation; do not use alarm/call full-screen-intent workaround. Use platform-wake.md and original documents. Prove supported physical Pixel behavior awake/lockscreen plus no-wake channel off. BOOT schedule restore must not depend on launcher. Own transport manifest and notification tests coordinated after15.

## Acceptance

Evidence must distinguish ordinary production defaults from explicit synthetic engine fixtures. Prior corrected acceptance is historical and does not prove this reopened request.

Implementation in private `lockscreen-wake` worktree. Agreed seams: real notification/PowerManager/alarm transport, durable wake budget and cancellation through module lifecycle, and six-profile manifest/AAR verifier. Public API capability is a bounded20s lease with max2 attempts per message; exact physical screen-off remains system/user controlled. Root owns device evidence. Gradle awaits ticket16 release.


## Answer — implementation ready, physical acceptance pending

Owned implementation is complete at source `020196d`; this ticket remains in progress until parent Ticket18 records actual supported-device wake and compact-card acceptance. No device/ADB work was performed by this implementer.

- COMMON_PLAN performs eligible bounded20s screen-wake requests, hard max2 durable claims per occurrence; no ON_AFTER_RELEASE/FSI/Activity/privileged-permission workaround. Exact physical screen-off remains system/user controlled.
- Confirms matching active notification before wake, with at most100/300/1000ms checks and a timed≤2s CPU-only lease while waiting. Rechecks user/channel/config state and cancels/releases owned work on dismissal/open/subscriber/disable/replacement/shutdown. The second wake uses an occurrence-bound SCREEN_OFF observer and bounded inexact checkpoint recovery.
- Cold UNKNOWN retains only an already-posted matching pending Lockscreen while pausing wake. Authoritative free can continue its existing budget; subscriber cancels. Existing Ticket15 cold calendar deferral is preserved. Proven-unposted claim abort now permits same-occurrence retry after UNKNOWN or a still-enabled new config revision; uncertain post/receipt/death dedupe remains conservative.
- Collapsed48dp Lockscreen has headline/body/X/CTA; heads-up uses the compact view, expanded keeps image. Pinned exposes4 real actions collapsed and expanded. Explicit request-context labels avoid SystemUI locale drift; partner renderer remains customizable.
- WAKE_LOCK is permitted only in notifications/umbrella by verifier; other selective profiles and existing FSI/exact/FGS/overlay/battery negatives remain enforced.

Validation:97 fresh notification unit/Robolectric tests PASS, release AAR PASS;49 unchanged-core tests retained from actual83f6357 verification.27 Python fixtures PASS. Immutable logs/XML/AAR and actual RED/GREEN history are outside repo at `SDKOptimize/retentionkit-default-fix/ticket17/`; final code run is `final-after-claim-fix/result.json`. No JitPack publication, consumer matrix or physical timing is inferred from these results. Parent owns those final checks and facade legacy-key warning correction.
