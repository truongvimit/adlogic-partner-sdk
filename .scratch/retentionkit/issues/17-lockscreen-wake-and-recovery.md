# Implement canonical lockscreen wake/recovery

Type: task
Status: in-progress
Blocked by: none (15 merged c40d18d)
Base: c40d18d
Owner: codex/retentionkit-lockscreen-wake

Implement supported channel-gated bounded screen wake with canonical duration/retry/pending-state rules and cancellation; do not use alarm/call full-screen-intent workaround. Use platform-wake.md and original documents. Prove supported physical Pixel behavior awake/lockscreen plus no-wake channel off. BOOT schedule restore must not depend on launcher. Own transport manifest and notification tests coordinated after15.

## Acceptance

Evidence must distinguish ordinary production defaults from explicit synthetic engine fixtures. Prior corrected acceptance is historical and does not prove this reopened request.

Implementation in private `lockscreen-wake` worktree. Agreed seams: real notification/PowerManager/alarm transport, durable wake budget and cancellation through module lifecycle, and six-profile manifest/AAR verifier. Public API capability is a bounded20s lease with max2 attempts per message; exact physical screen-off remains system/user controlled. Root owns device evidence. Gradle awaits ticket16 release.
