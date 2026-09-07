# 11 Shared notification profile and common document mapping

Type: task
Status: claimed
Blocked by:

Spec: ../spec.md, Corrected acceptance

## Scope and acceptance

Own retention-notifications code/tests/docs and required isolated config-source mapping agreed with root. Derive a generic shared profile from canonical Noti documents and exact current Translate parameters where documents leave values unspecified. Record conflicts with a chosen precedence, rather than claiming contradictory behavior simultaneously. Maintain migration compatibility for existing channels/known keys.

Cover campaign-specific content, stable family identity, priority/guard windows, daily and inactivity cadence, lockscreen slots/new cohort/replace false across midnight, setup/subscriber/permission/channel suppression, max caps and restore. Catalogue and default fixtures must be generic. Existing renderer/content extension must preserve SDK PendingIntent routing. Add meaningful regressions for changed policy and map exact RC keys without a duplicate Firebase owner.

Work in private worktree, commit scoped parts. Build only when root grants ownership. No ADB.

## Comments

Claimed by platform_research; source audit is outside repo at retentionkit-correction/noti-spec-audit.md.
