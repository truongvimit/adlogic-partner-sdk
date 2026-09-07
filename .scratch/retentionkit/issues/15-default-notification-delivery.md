# Fix normal common-plan notification delivery

Type: task
Status: ready-for-agent
Blocked by: none
Base: 2865f6d

Remove undocumented post-setup default24h wait while retaining first-day unfinished-setup protection. Re-evaluate foreground Reminder/Pinned when setup, entitlement, permission or host UI become ready. Correct cold calendar delivery/reboot race without silently consuming eligible slots on transient UNKNOWN. All defaults source-backed; canonical audit is /Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-default-fix/canonical-audit.md. Add actual seam RED then GREEN regressions; preserve subscriber/channel/foreground suppression and opt-out. Own retention-notifications and necessary focused retention-core changes/tests only.

## Acceptance

Evidence must distinguish ordinary production defaults from explicit synthetic engine fixtures. Prior corrected acceptance is historical and does not prove this reopened request.
