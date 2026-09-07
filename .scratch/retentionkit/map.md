# RetentionKit task graph

Spec: [spec.md](spec.md) · Base: `632df43` · Branch: `codex/retentionkit`

## Frontier

**Reopened for the user's shared-Noti correction:** tickets[10](issues/10-standard-entry-feedback.md), [11](issues/11-shared-notification-profile.md), and independent work in[12](issues/12-generic-example-integration.md) are claimed. Final12 depends on10/11; then[13 validation](issues/13-corrected-validation.md) and[14 review](issues/14-corrected-review.md). Fixed correction base543de03. The earlier acceptance below is historical, including its direct destination/no-entry-ad behavior, and does not prove the corrected full Splash flow.

Tickets01–07 and additive05a are resolved and merged. SDK/app implementation, supported validation, partner documentation and code-review corrections are accepted. Ticket08 remains **claimed** only because the ready-PR step requires a GitHub account with write permission. The current account has READ-only repository access; branch pushes through the existing owner SSH identity succeed. The spec stays active for that external completion step. Additive ticket09 is claimed for the unlocked Pixel follow-up:15/15 Android cases at4356938 and some manual flows passed, but concurrent device input interrupted full UI acceptance. Latest build/source evidence is in physical-acceptance.md.

## Decisions and completion pointers

- Shared presets based on TranslatorGuru, with PDF Reader/Caller-ID variants; core plus four selective feature artifacts and umbrella facade. Reference apps were not modified by this task.
- Optional integration reuses OnboardKit, Ads, authoritative Billing, Trackkit and shared Firebase. No forced release or change to existing consumer defaults.
- [01 core](issues/01-core-foundation.md), [02 notifications](issues/02-notifications.md), [03 widgets](issues/03-widgets-shortcuts.md), [04 feedback/review](issues/04-feedback-review.md), [05 suite/facade](issues/05-suite-adapters-facade.md), [05a Billing](issues/05a-billing-entitlement.md): implementation and module contracts merged; final integrated evidence supersedes earlier scoped counts.
- [06 example](issues/06-example-integration.md): four functional EN/VI tools, SDK-owned flows, durable app outbox and typed routing. Final app-only correction `a4d3c31`, integrated `2ff967a`, prevents older queued entries overwriting the selected destination; three new actual-Activity regressions. Final docs/acceptance `6ac6724`.
- [07 packaging/docs](issues/07-validation-docs.md): six local publications, twelve selective/umbrella project/POM-only Maven R8 consumers and actual dependency/manifest checks passed at unchanged SDK `bf68f1e`. Partner guides and final evidence are linked in the ticket Answer.
- [08 review](issues/08-review-completion.md): independent Standards2/P3 and Spec1/P2 findings fixed by one implementer in `6988108`/`90ac742`, integrated `bf68f1e`. All findings have fixes and affected regression evidence. PR creation/readiness remains an external authentication prerequisite.
- Earlier verification checkpoint: **549 unit/Robolectric cases** (532 unchanged non-app +17 fresh app), **15/15 actual API36 emulator cases**, full example debug/test/release R8 and existing paywall sample build passed. Installed debug APK SHA matches on emulator and physical Pixel5. Physical baseline/Home-return evidence is scoped separately from unavailable full UI coverage.
- Actual emulator manual checks include pin Cancel/Add, multiple/resize/delete/locale widgets, real feature routing, permission/Later/shortcuts, reboot/timezone/force-stop reconciliation and final minified POM consumer feedback/Store return. Play quota/card/rating and OEM alarm timing are platform-controlled.

Exact commands, source checkpoints, actual JUnit hashes, failed runs/fixes and limitations: [verification.md](verification.md). Partner entry: [RetentionKit README](../../retentionkit/README.md). Implementation report: [Vietnamese handoff](../../retentionkit/IMPLEMENTATION_REPORT.vi.md). Prepared PR description: [pr-body.md](pr-body.md). No release tag or remote RetentionKit publication was created.
