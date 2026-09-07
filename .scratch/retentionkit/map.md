# RetentionKit task graph

Spec: [spec.md](spec.md) · Base: `632df43` · Branch: `codex/retentionkit`

## Frontier

The corrected common-flow implementation is accepted at69d0f71, production-equivalent to device source86048d6: tickets10–14 are resolved, including [13 validation/closure](issues/13-corrected-validation.md), physical restoration and task-resource cleanup; the minified release smoke has passed. [08 external PR](issues/08-review-completion.md) remains claimed because the configured GitHub API account has READ-only repository access; no PR URL/readiness is claimed. Correction base543de03.

Current evidence is [correction-acceptance.md](correction-acceptance.md):279 fresh selected tests,17 physical Pixel cases, genuine first-install API36 long-onboarding/native proof,6fresh local publications,12R8 consumers and full app R8. Earlier01–09 evidence and their physical follow-up notes are historical; direct destination/no-entry-ad behavior is superseded. No release tag or remote RetentionKit publication was created.

## Decisions and completion pointers

- [11 shared notification profile](issues/11-shared-notification-profile.md): canonical generic COMMON_PLAN/legacy RC/shared Firebase integrated; notification61 andFirebase10 fresh cases, accepted-route/display TTL separation and real first-install route. [Current report](../../retentionkit/CORRECTION_REPORT.vi.md).
- [12 generic example](issues/12-generic-example-integration.md): real Notes/Saved items/Text tools/Guide, standard Splash/Onboard/Main/native integration and stable migrated token/outbox; app32 fresh,17 actual Pixel cases and default API36 first-open passed. [Example guide](../../app/RETENTION_EXAMPLE.md).
- [14 corrected review](issues/14-corrected-review.md): Standards0/Spec0 after single-implementer fixes; selection/TTL/shortcut RED→GREEN and final279/package checks. [Verified correction](correction-acceptance.md). Only external PR08 remains open.

The following pointers retain earlier implementation history and scoped counts; they are not added to current279:

- [10 standard entry/feedback](issues/10-standard-entry-feedback.md): standard Splash→Main handoff reuses the selected durable token, isolates explicit ENTRY from automatic PROMPT gates and supplies customizable feedback/native/system confirmation. [Entry contract](../../retentionkit/ENTRY_CONTRACT.md) records162 initial SDK tests and the focused30-test follow-up at7c3470d;134 unchanged module cases yield164 scoped results across invocations. These are SDK/AAR results, not final app/device/consumer acceptance.
- Shared presets based on TranslatorGuru, with PDF Reader/Caller-ID variants; core plus four selective feature artifacts and umbrella facade. Reference apps were not modified by this task.
- Optional integration reuses OnboardKit, Ads, authoritative Billing, Trackkit and shared Firebase. No forced release or change to existing consumer defaults.
- [01 core](issues/01-core-foundation.md), [02 notifications](issues/02-notifications.md), [03 widgets](issues/03-widgets-shortcuts.md), [04 feedback/review](issues/04-feedback-review.md), [05 suite/facade](issues/05-suite-adapters-facade.md), [05a Billing](issues/05a-billing-entitlement.md): implementation and module contracts merged; final integrated evidence supersedes earlier scoped counts.
- [06 example](issues/06-example-integration.md): four functional EN/VI tools, SDK-owned flows, durable app outbox and typed routing. Final app-only correction `a4d3c31`, integrated `2ff967a`, prevents older queued entries overwriting the selected destination; three new actual-Activity regressions. Final docs/acceptance `6ac6724`.
- [07 packaging/docs](issues/07-validation-docs.md): six local publications, twelve selective/umbrella project/POM-only Maven R8 consumers and actual dependency/manifest checks passed at unchanged SDK `bf68f1e`. Partner guides and final evidence are linked in the ticket Answer.
- [08 review](issues/08-review-completion.md): independent Standards2/P3 and Spec1/P2 findings fixed by one implementer in `6988108`/`90ac742`, integrated `bf68f1e`. All findings have fixes and affected regression evidence. PR creation/readiness remains an external authentication prerequisite.
- Earlier verification checkpoint: **549 unit/Robolectric cases** (532 unchanged non-app +17 fresh app), **15/15 actual API36 emulator cases**, full example debug/test/release R8 and existing paywall sample build passed. Installed debug APK SHA matches on emulator and physical Pixel5. Physical baseline/Home-return evidence is scoped separately from unavailable full UI coverage.
- Actual emulator manual checks include pin Cancel/Add, multiple/resize/delete/locale widgets, real feature routing, permission/Later/shortcuts, reboot/timezone/force-stop reconciliation and final minified POM consumer feedback/Store return. Play quota/card/rating and OEM alarm timing are platform-controlled.

Exact commands, source checkpoints, actual JUnit hashes, failed runs/fixes and limitations: [verification.md](verification.md). Partner entry: [RetentionKit README](../../retentionkit/README.md). Current report: [Vietnamese handoff](../../retentionkit/CORRECTION_REPORT.vi.md); [historical implementation](../../retentionkit/IMPLEMENTATION_REPORT.vi.md). Prepared PR description: [pr-body.md](pr-body.md). No release tag or remote RetentionKit publication was created.

Final closure: ticket13 resolved; [Final physical/restore proof](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-physical-manual-and-restore.json) and [Task worktree cleanup](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/task-worktree-cleanup.json). All corrected implementation/verification tickets10–14 are accepted. Ticket09’s physical follow-up is superseded by this corrected acceptance; ticket08 remains external PR authentication only.

External08 confirmation: branch push succeeded; actual draft PR creation after3801f74 returned `GraphQL: must be a collaborator (createPullRequest)`. [PR attempt](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-pr-attempt.json). No PR exists/readiness claimed; all code, validation, report and resource-cleanup work is complete.
