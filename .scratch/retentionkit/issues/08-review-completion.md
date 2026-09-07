# 08 Code review fixes and ready PR

Type: task
Status: claimed
Blocked by: 07

Spec: ../spec.md

## Scope and acceptance

Run code-review skill against fixed base632df43 with Standards and Spec agents. A single implementer agent fixes all actionable findings in private worktree, commits and has merger integrate. Re-run tests affected by fixes, mark ticket/spec completion honestly, update PR description/checklist/evidence and make ready. Preserve user branch; remove only merged ticket worktrees. Do not merge PR/tag a release.

Follow the shared implementation contract and acceptance ledger in spec.md. Commit only owned files; write implementation/test results under Answer and change Status to resolved when verified. Provide commit hashes and concise context pointers for the merger.

## Comments

Review S1: claimed for the single implementer handling Standards S1/S2 and the Spec P2 handoff finding. Final review acceptance and PR completion remain root-owned.

Review corrections and final validation are complete; PR readiness still requires an authorized GitHub write login. See Answer below.

## Answer

Implementation and review acceptance complete in the available test scope; this ticket remains **claimed** for the external PR-creation/readiness requirement. Current GitHub API permissions were rechecked after final device acceptance: pull=true, push/admin/maintain=false. Earlier draft creation failed with `must be a collaborator`; restoring an authorized write login is required. No PR URL or ready status is claimed. The final PR description is concrete and recorded in `../pr-body.md`; branch pushes succeed through the existing owner SSH identity.

Independent reviews used fixed base `632df43` and frozen `1d06439`: Standards found two P3 items (tracker status vocabulary and duplicate handoff lifecycle scope); Spec found one P2 item (reentrant callbacks could invalidate the host/config before platform launch). One implementer fixed all findings in `6988108`/`90ac742`, merged at `bf68f1e`. Shared scope registration, queue-drain dispatch and final runtime/host/config/session checks preserve counters and release only the owned resource.170 RetentionKit cases passed, including16 new regressions; independent bounded fix inspection found no unresolved concrete issue.

Final ADB exposed a separate app ordering defect: two new Intents could make an older staged entry overwrite the latest destination. The real Activity regression failed twice; `a4d3c31`/`2ff967a` fixes selection/coalescing/retry-budget handling. Three new Activity regressions and token/consumed-receipt pinned assertions now pass. Final verification is549 actual unit/Robolectric cases,15/15 API36 Android cases, all12 release-minified project/Maven consumers,12 graphs/compositions,6 local publications and full example R8. Exact provenance/limits are in `../verification.md`; it distinguishes fresh17 app cases from532 unchanged non-app results.

Source implementation and all supported checks have no known unresolved finding. Physical UI follow-up is now tracked by additive ticket09: the unlocked Pixel recorded15/15 Android cases and some manual checks, but concurrent input interrupted full acceptance; and OEM/Play behavior is not universally certified. Reference-app source remains unchanged. Physical app data was not cleared; test-owned widget/business state is recorded in the follow-up ledger. No release tag or remote SDK release was made. Cleanup/push disposition is recorded in the final handoff record outside the repo. The parent specification remains active for external PR completion and the additive exclusive-device acceptance follow-up.

## Corrected final PR handoff

All corrected implementation/review/device/package/resource acceptance is complete; tickets10–14 are resolved and09 is superseded. Branch `codex/retentionkit` was pushed through the existing authorized SSH identity. After pushing3801f74, actual `gh pr create --draft --base main --head codex/retentionkit --body-file .scratch/retentionkit/pr-body.md` was rejected by GitHub: `GraphQL: must be a collaborator (createPullRequest)`. The active API account has pull/read but no push permission; the saved owner account is not currently authenticated. No PR exists, so readiness cannot be set. This ticket remains external authentication only. [PR attempt](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-pr-attempt.json). No merge, tag or remote release was performed.
