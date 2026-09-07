# 11 Shared notification profile and common document mapping

Type: task
Status: resolved
Blocked by:

Spec: ../spec.md, Corrected acceptance

## Scope and acceptance

Own retention-notifications code/tests/docs and required isolated config-source mapping agreed with root. Derive a generic shared profile from canonical Noti documents and exact current Translate parameters where documents leave values unspecified. Record conflicts with a chosen precedence, rather than claiming contradictory behavior simultaneously. Maintain migration compatibility for existing channels/known keys.

Cover campaign-specific content, stable family identity, priority/guard windows, daily and inactivity cadence, lockscreen slots/new cohort/replace false across midnight, setup/subscriber/permission/channel suppression, max caps and restore. Catalogue and default fixtures must be generic. Existing renderer/content extension must preserve SDK PendingIntent routing. Add meaningful regressions for changed policy and map exact RC keys without a duplicate Firebase owner.

Work in private worktree, commit scoped parts. Build only when root grants ownership. No ADB.

## Comments — historical checkpoints

Claimed by platform_research; source audit is outside repo at retentionkit-correction/noti-spec-audit.md.


## Answer

Notification-owned implementation is complete at `b695be5`; integration acceptance remains with the parent correction run, so this ticket remains claimed until the shared Firebase mapper and full Splash/example route are integrated.

- Default public `NotificationPreset.COMMON_PLAN` implements the canonical cadence, common channel groups, campaign framing and priority/30s guard (including first pinned creation). `LEGACY_SDK` explicitly preserves old SDK behavior;48h is not attributed to Translate. Existing7101–7107 identities and legacy channel override support stay intact.
- Added APP_EXIT7108/OTHER for confirmed completed-app departure; AD_RETURN wins on the same departure. Common exit occurrence is persisted before inexact fallback and live3s timer; callback/foreground/config/permission/subscriber/restart checks prevent stale replay. Hard kill before scheduling is explicitly unobservable.
- Winback14–45d inclusive,2/day/max3 lifetime; durable reservations survive pruning/restart and import retained legacy submitted/unknown attempts once. Unknown process-death delivery remains unknown. No reconstruction of already-pruned history is claimed.
- Common lockscreen defaults skip untouched across slots/days, random excluding previous, no displayed timeout; delivery TTL remains1h. Ordinary app foreground dismisses it. Persistent template produces a fresh once-consumed entry on tap; custom renderer retains SDK PendingIntent ownership. Wake20s is explicitly nonportable, with `wake_capability=os_controlled` and official API rationale.
- Default generic EN/VI campaign-specific title/body/action, independent collapsed/expanded exit titles, bounded local BigPicture and lockscreen image/CTA/X. Invalid/empty higher-priority content cannot starve a valid lower flow. `NotificationLegacyConfig.keys/map` exposes the single exact alias table; old `translate()` remains source compatible. Root owns shared Firebase source composition, without a second config client.

Validation actually executed: `:retention-notifications:testDebugUnitTest :retention-notifications:assembleRelease --no-daemon --console=plain --max-workers=2`, JBR21. Final actual XML **56 tests,0 failures/errors/skips**:26 existing behavior,20 new common-profile,5 calendar,5 Android adapter. Both production variants compile and release AAR assembly passes. First run had only a fixture compile error constructing an internal config snapshot; fixed by installing the real runtime. Final run3 is green.

Immutable logs, actual JUnit, AAR and hashes: `/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/notification-validation/b695be5/verification.json`. Canonical complete-source audit: `/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/noti-spec-audit.md`. No ADB or physical timing run was performed by this agent. Shared-core/facade integration and full entry-chain device acceptance must be reported separately.

## Answer — final integrated acceptance

Resolved at tested source69d0f71/production86048d6. Canonical COMMON_PLAN, shared Firebase legacy mapping, entry route lifetime and generic example are integrated. All61 notification and10 Firebase cases passed freshly in the279-case run;12 consumer R8 graphs/compositions and the full app release pass. Physical notification gates/Later/pinned/action routes and genuine default-revision0 first-install ONBOARD to Notes are recorded separately from injected calendar timing. Old display envelopes and explicit core expiry remain honored; no OS wake SLA is claimed.

Evidence: [Fresh unit result](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-units-69d0f71/result.json) · [Matrix](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/consumers-69d0f71/matrix-summary.json) · [First-install OS proof](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-first-install-api36.json). Restoration/extra smoke and external release/PR are tracked by13/08, not hidden completion prerequisites for this implemented profile.
