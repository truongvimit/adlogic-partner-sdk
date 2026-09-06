# 02 Notification campaigns and scheduling

Type: task
Status: ready-for-agent
Blocked by: 01

Spec: ../spec.md

## Scope and acceptance

Own retention-notifications/**. Implement all seven spec campaign families and standard renderer/customization, separate channel gates, stable multi-action PendingIntents, dismiss receiver, inactive winback, expiring delayed-ad-return and onboarding abandonment, persisted rotation/budgets, skip/replace lockscreen, calendar scheduler/reconcile/boot/update/time/TTL, cached config and migration API. Read Translator notifications source as reference, not copy unchanged. No exact alarm/FSI/FGS permissions. Tests include gate transitions, stale revision/disabled callbacks, slot reduction, async failure, process restore, click destinations and scheduling edge cases. Do not edit shared core without parent agreement; depend on its contract.

Follow the shared implementation contract and acceptance ledger in spec.md. Commit only owned files; write implementation/test results under Answer and change Status to resolved when verified. Provide commit hashes and concise context pointers for the merger.

## Comments

Implementation pending.
