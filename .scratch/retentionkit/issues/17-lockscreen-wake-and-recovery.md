# Implement canonical lockscreen wake/recovery

Type: task
Status: ready-for-agent
Blocked by: 15
Base: 2865f6d

Implement supported channel-gated bounded screen wake with canonical duration/retry/pending-state rules and cancellation; do not use alarm/call full-screen-intent workaround. Use platform-wake.md and original documents. Prove supported physical Pixel behavior awake/lockscreen plus no-wake channel off. BOOT schedule restore must not depend on launcher. Own transport manifest and notification tests coordinated after15.

## Acceptance

Evidence must distinguish ordinary production defaults from explicit synthetic engine fixtures. Prior corrected acceptance is historical and does not prove this reopened request.
