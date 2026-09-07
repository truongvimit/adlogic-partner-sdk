# 13 Corrected standard-flow validation and report

Type: task
Status: claimed
Blocked by: final acceptance of 10, 11, 12; independent preparation and affected tests active

Spec: ../spec.md, Corrected acceptance

## Scope and acceptance

Root owns physical ADB, immutable source/APK evidence, actual full cold/warm entry chain, notification body/CTA/pinned/Later/dismiss, widget lifecycle, uninstall optional reasons/Keep/rescue/native/confirmation cancel, permission/channel/setup/entitlement suppression, generic review flow, process recovery and affected consumer packaging/R8. Do not reuse prior direct-routing evidence as proof of this contract. Test while implementing; failures become scoped fixes. Record OS/ad-provider limitations precisely and preserve unrelated app/device data.

Update a concise Vietnamese implementation/report matrix and partner instructions. Source tests must not claim OS alarm wake or Play review acceptance.

## Progress

Root owns this ticket. Source `1ebcc2e` passed 10 shared Firebase and 6 isolated core-consumer Activity tests (zero failures/errors/skips). Raw XML, run/source snapshots and hash ledger are retained under `SDKOptimize/retentionkit-correction/evidence/remote-consumer-first/`. These are scoped regression results, not physical full-flow acceptance.
