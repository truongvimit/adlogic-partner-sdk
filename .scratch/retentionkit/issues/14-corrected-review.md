# 14 Independent corrected Standards/Spec review

Type: task
Status: claimed
Blocked by: 13

Spec: ../spec.md, Corrected acceptance

## Scope and acceptance

Run code-review skill against fixed correction base543de038c8e61ae393cdcc5fa84390b199ceda38. Independent Standards and Spec reviews; a single private-worktree implementer fixes findings and affected checks rerun. Complete commits and branch push; prepare concrete PR text. PR creation/readiness still depends on authorized GitHub write login (current API identity READ only). Clean only task-owned merged worktrees and registry entries. No release tag or PR merge.

## Comments

audit_pdf owns the single implementation of the final pinned18fade8 review findings in private sdk-entry: three Standards P3 items plus the stale local entry selection P2 in both destination Activities. Tests exercise actual new-Intent delivery under a shared UI block and saved materialized identity through recreation. Build ownership remains coordinated; final review acceptance/push stays with root after affected verification.

Root also reproduced a normal first-open notification tap that expired during genuine setup. The single fixer separated delivery/display TTL from new notification route lifetime without changing core's explicit-expiry contract. Existing envelopes remain untouched; new ones keep core's bounded seven-day retention. [Device source evidence](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/manual_emulator/029-day-two-notifications.json) records the original notification; adjacent030–044 UI/state captures record the affected setup journey. JVM regressions exercise rendered body/CTA capture, setup beyond display expiry, old pinned templates and expired-send rejection. Final affected checks and immutable evidence follow before merge.

The final launcher check also found the default shortcut label/icon diverged from the canonical Uninstall presentation. The module now defaults to localized Uninstall/Gỡ cài đặt and a separate bundled red-trash `shortcutIconRes`, preserving `appIconRes` for the survey header, custom label content, owned ID and standard router. Actual metadata regression RED at3f0d6c3 became GREEN at4ca54f5. Final feedback/API integration capture follows this independent source checkpoint; prior22c7fcb evidence stays immutable.

## Answer — single-fixer implementation verified; final acceptance pending

The three Standards items, stale latest-delivery selection P2, real onboarding route-lifetime P2 and Uninstall shortcut presentation P3 are implemented in the private sdk-entry branch. Source checkpoint `86048d619dfadb3371a8d7448f629d897b449d68` preserves core explicit-expiry semantics, persisted notification JSON/key compatibility, old backlog, exact restored ONCE identity, shortcut ownership and host entry routing. Partner shortcut icon customization is independent of survey header branding.

Actual red/green regressions cover both Activities, accepted notification routes through long setup and shortcut metadata. Complete affected app32 + notifications61 + core consumer9 passed102/102 at22c7fcb with notification release AAR and APK assembly. Feedback31 passed31/31 at86048d6 with feedback release AAR and final app debug/test APK assembly. These are separate source-scoped invocations; unchanged module trees and all XML/artifact hashes are recorded. The app32 tests predate the feedback-only correction; final app dependency compilation/packaging passed. The unchanged test APK was reused by the successful assembly task, while the target debug APK was rebuilt.

Evidence: [correction summary](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/review-fixes-summary.md), [102-test capture](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/review-fixes-final-22c7fcb/result.json), [31-test/final APK capture](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/review-shortcut-final-86048d6/result.json).

Status remains claimed. Root owns independent final review, new-source physical/emulator checks (including fresh genuine onboarding and actual launcher label/icon), final R8/publication matrix, merger and push. No ADB, root source mutation, external release or PR operation was performed by this fixer. Previously posted notification envelopes with an explicit old deadline are not rewritten; prior device evidence stays historical.
