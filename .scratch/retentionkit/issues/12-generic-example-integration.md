# 12 Generic example using the complete standard SDK flow

Type: task
Status: resolved
Blocked by: 10, 11

Spec: ../spec.md, Corrected acceptance

## Scope and acceptance

Own app code/resources/assets/unit/android tests and app/RETENTION_EXAMPLE.md. Independent catalogue/UI work may start now; final integration waits for10/11. Use generic Notes, Saved items, Text tools and Guide; preserve old local sample data and explicitly map retired feature IDs. No translation-themed fixtures or test actions.

Remove ready-entry Splash bypass and QA direct destination router. Actual entry chain must run Splash, existing entry interstitial/legitimate skip, Main resumed, then exact selected feature. Preserve setup/outcome/newIntent/rotation/cold-process identity and original ad showcase compatibility. Medium feature native and uninstall native use current Ads SDK and configured debug test ad units. Example demonstrates partner content/branding/customization without recreating SDK state machines.

Feedback entry actions route through Splash. Use ticket10 controller/native seam; valid system confirmation is cancelled in device tests. Shared notification defaults/params/content come from ticket11 with small explicit host config. Debug eligibility/clock controls use the same routing and clear descriptions of synthetic state. Real business-success operations feed review; no manufactured review success. Extend real Activity and instrumented tests to observe the full path.

Work in private worktree with scoped incremental commits. One build owner by root; no ADB.

## Comments — historical checkpoints

Claimed by audit_caller_translator. Source audit at retentionkit-correction/translate-flow-audit.md.

Implementation checkpoint `5206487` integrates standard SDK entry APIs through `ef94857` and the common notification/Firebase profile. Generic catalogue/data migration, actual Splash → Main handoff, medium native slots and 17 instrumented routes are implemented. App26 actual unit cases passed at `c8f0cbd`; production/unit source is identical at `5206487`. Debug/test APK build passed and release R8 passed11m15s at clean `5206487`. External evidence: `SDKOptimize/retentionkit-correction/evidence/example-final/{unit-result,debug-result,release-result}.json`.

Status remains claimed while the device owner runs the corrected17 cases and the SDK-owned Main focus follow-up is integrated. Follow-up debug callback logging and semantic ad-close instrumentation retain actual transport callbacks; no product flow or ad-policy override is introduced. No ADB was run by this implementer.

Final source checkpoint `6cd46e1` includes SDK focus `7c3470d`. App29/29 actual cases and debug/test APKs passed36s at clean unchanged source; evidence `SDKOptimize/retentionkit-correction/evidence/example-focus-clock-6cd46e1/result.json`. Two deterministic actual QA-clock regressions went RED at `70a7e02` and GREEN at `0c54bc7`; a real Splash Activity callback test verifies reusable materialization. The unassisted Pixel `5206487` baseline is retained honestly as15/17; its clock/premature-token harness causes are fixed in this checkpoint. Status remains claimed pending the root's two-case/full17 rerun and final affected packaging acceptance.

## Answer

Resolved for generic example integration at69d0f71/production86048d6. Notes/Saved items/Text tools/Guide perform real offline work with atomic legacy migration and stable success IDs. Production and QA use actual Splash→Onboard terminal→Main-resumed→selected feature/feedback, medium native keys and exact materialized consumption. Absent/rejected new deliveries cannot revive old backlog; recreation preserves the selected token. Genuine long onboarding now survives notification display expiry.

All32 app cases passed freshly within279; physical Pixel5/API34 passed17/17 unassisted with final app/test hashes; first-install API36 defaultrevision0 reaches Notes with actual native_noti loaded/impression. Full app release R8 and all12 consumers pass. [Fresh unit result](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-units-69d0f71/result.json) · [Pixel17](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/pixel-86048d6-full/result.json) · [First install](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-first-install-api36.json) · [R8](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/example-release-69d0f71/result.json). QA/OS/ad boundaries are documented in app/RETENTION_EXAMPLE.md. Root owns supplementary smoke/restoration/cleanup in13 and external PR status in08.
