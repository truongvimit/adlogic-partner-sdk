# Fix normal common-plan notification delivery

Type: task
Status: resolved
Blocked by: none
Base: 2865f6d

Remove undocumented post-setup default24h wait while retaining first-day unfinished-setup protection. Re-evaluate foreground Reminder/Pinned when setup, entitlement, permission or host UI become ready. Correct cold calendar delivery/reboot race without silently consuming eligible slots on transient UNKNOWN. All defaults source-backed; canonical audit is /Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-default-fix/canonical-audit.md. Add actual seam RED then GREEN regressions; preserve subscriber/channel/foreground suppression and opt-out. Own retention-notifications and necessary focused retention-core changes/tests only.

## Acceptance

Evidence must distinguish ordinary production defaults from explicit synthetic engine fixtures. Prior corrected acceptance is historical and does not prove this reopened request.

## Comments

audit_pdf claims ticket15 in private default-delivery. Canonical audit rejects the undocumented post-setup24h default. Agreed seams: actual module runtime signals/foreground publication and real saved calendar receiver envelopes with transient entitlement; preserve subscriber, permission/channel, foreground and opt-out gates. Root retains device execution and integration ownership.

## Answer

Implemented in the private default-delivery branch. COMMON_PLAN now has zero post-setup grace, Reminder unlimited daily with the documented15-minute cooldown, quiet PINNED without an invented daily/cooldown cap, and AD_RETURN/APP_EXIT daily caps opt-in. LEGACY_SDK values and explicit partner overrides remain supported; common cap0 means unbounded. Remaining chosen numeric SDK limits are identified in the module README rather than attributed to the canonical documents.

The current foreground open survives late setup/Billing/permission/host readiness and gets one bounded guard retry for the quiet surface. One coalesced reentrant readiness pass avoids losing the open or creating an unbounded callback loop. Successful/open-visible outcomes do not replay on every readiness signal. Cold calendar UNKNOWN retains the original occurrence/due/TTL/revision, with durable30s/1m/2m/4m/8m/16m checkpoints (six maximum), then expiry cleanup; current verified entitlement is required. Read-only `RetentionNotifications.status()` exposes actual attempt reasons and requested alarm times without triggering work. No core API or source change was needed.

Actual RED→GREEN invocations cover first completed default open, delayed readiness, Reminder cap, cold UNKNOWN, PINNED restoration, both departure caps, pre-expiry process-death retry and reentrant callbacks. Final immutable source498824635b43c1ee887e3cfce016f991f22b4ab3/tree d7654f60a6f389c4a62afb6bbe4aeb7a82f89a46 passed77 notification +49 core tests in one invocation (126 total, zero failures/errors/skips), plus the release AAR, in21s. Command: `:retention-notifications:testDebugUnitTest :retention-notifications:assembleRelease :retention-core:testDebugUnitTest --max-workers=2 --console=plain`, JBR21. XML timestamps/hashes, clean source manifest, logs and copied AAR are outside the repo at `/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-default-fix/ticket15/module-verification-2/result.json`; targeted checkpoints remain separately preserved and are not added to126. The preceding snapshot-constructor compile failure is retained as evidence and was corrected without making the core constructor public.

All default-open scenarios begin at config revision0 without notification overrides. Clock/Android transport are test seams; explicit cap overrides and reentrant/expiry races are separately named engine fixtures. This ticket performs no ADB/device, screen-wake, facade or example-app validation. Root owns fresh normal-device acceptance; ticket17 owns the wake implementation. Historical default-device failures remain evidence, not erased by unit results.
