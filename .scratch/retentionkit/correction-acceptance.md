# Corrected shared-flow acceptance

Status: implementation, review, selected fresh tests and stated device/package acceptance passed; root-owned final physical restoration/cleanup and external PR completion remain separate. Correction base `543de038c8e61ae393cdcc5fa84390b199ceda38`; delivery branch `codex/retentionkit`.

Frozen build source `69d0f71ec970205902d8ca666b2876df3f938690`, tree `166c21d8b4c705f3647dc21d6baf39a1b7bc5024`. Device source `86048d619dfadb3371a8d7448f629d897b449d68` has identical production/config/tests; only issue12/14 and example README differ. [Merge equality](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/review-86048d6/merge-freeze.json). Later documentation commits do not change that tested source.

## Final executed checks

| Scope | Actual result | Immutable evidence |
|---|---|---|
| Nine-module fresh JVM/Robolectric | 279/279,0 failures/errors/skips; one invocation with `--rerun-tasks`,393 executed tasks,2m48s | [JUnit/source/command](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-units-69d0f71/result.json) |
| Physical Pixel5/API34 | 17 started,17 distinct passes,0 failures/skips; unassisted runner, installed hashes unchanged | [Actual runner protocol](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/pixel-86048d6-full/result.json) |
| First installation/API36 | Default revision0, actual permission deny then Android Settings allow; visible ONBOARD7103; selected token survives genuine setup beyond display TTL and reaches Notes | [First-open evidence](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-first-install-api36.json) |
| Local QA publication/consumers | Six fresh publications; all12 project/POM-only Maven profiles pass R8/runtime graph/manifest/composition at exact `retentionkit-qa-20260907-69d0f71` | [Matrix](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/consumers-69d0f71/matrix-summary.json) · [Inspection](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/consumers-69d0f71/inspection-summary.json) |
| Full example release | R8 PASS7m53s, APK and mapping captured; only Crashlytics mapping upload excluded, repository debug signing | [Release result](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/example-release-69d0f71/result.json) |
| Independent review | Standards0 at22c7fcb; Spec0 after86048d6; all identified findings corrected | [Standards](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/review-22c7fcb/standards.md) · [Spec](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/review-86048d6/spec.md) |

Fresh module counts: core49 + facade30 + widgets37 + review20 + feedback31 + notifications61 + Firebase10 + core-project consumer9 + app32 = **279**. These are the selected correction scope, not all unrelated legacy SDK suites. They replace earlier265/133 provenance summaries for current acceptance; targeted RED/GREEN and earlier15/17 runs remain historical, not extra tests. [Final packaging aggregate](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-packaging-69d0f71.json).

## Actual route, native and system observations

Independent audit re-parsed the actual protocol and checked APK/source/state/native evidence; SDK submission, OS creation and visibility remain distinct. [Independent device audit](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-device-proof-audit.md).

The physical suite observes actual Splash-created/resumed → Main-resumed → feature/feedback, exact materialized envelope and consumed receipt. It covers every pinned/widget/feature-shortcut destination, cold/warm/rapid entries, business outbox, actual completed-Onboard synchronization, permission/channel and subscriber gates, quiet Later, optional-reason Keep/rescue, cancelled own-package uninstall confirmation and honest Play transport terminal. Saved alarm injection and declared QA eligibility/clock do not prove AlarmManager wake timing.

The first-install API36 run used the normal app store/router and config revision0 without a QA profile injection in the recorded execution. Only the isolated emulator OS clock advanced25h before the accepted onboarding to reach the default install grace. The same token `567374ad-1cd8-3e73-8725-69f900491d17` appears pending then consumed at Notes 602.756s after Android record creation and577.019s after the accepted tap; no elapsed-time acceleration occurred during that journey. Runtime deny suppressed eligible unfinished departure; actual Android Settings allow permitted the subsequent post. [Visible notification](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/manual_emulator/059-final-onboard-shade.png) · [Pending token](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/manual_emulator/060-final-accepted-pending.json) · [Consumed route](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/manual_emulator/074-final-completed-route.json).

Native_noti actual `request_called`, `loaded`, `impression` callbacks and real ActivityTaskManager Main/feature sequence are retained in [075 log](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/manual_emulator/075-final-native-and-activity.log). These observations are separate from screenshot appearance and unit success. They do not imply all placements filled or every ad request produced an impression.

Final physical manual captures: [Uninstall launcher menu](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/manual/093-final-shortcut-menu.png) (red trash icon), [survey/native](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/manual/094-final-survey.xml), [usable Guide rescue](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/pixel-final-manual/actual-rescue-guide-retry.xml), [Store result](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/manual/101-final-manual-store.png) and [Back to Guide](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/manual/102-final-store-return.xml). The unpublished example returns Item not found in Play; this is a real Store handoff/return, not a rating or published listing. Android uninstall confirmation was cancelled; no completed uninstall is claimed.

## Artifact identity

- Debug APK on both final device runs: `6a8791f651f58bc83dd9d413bb355a0a3ebd83551871ba76fd1718ca6d61ae58`.
- Test APK: `04ecbcb2e3af07c5d17b872510d554139e0a8e660044b5c21ea6652e29fdd5b0`.
- Release APK: `43e972c58c87c8c92a104d49d5c86b4291d188fdbc34c9a70bc2476e1e802aa5`.
- Release mapping: `b238b5769249c4b87e87dff8d9e37b3fd9e4eda7900e8be5e56e67e38d123b45`.

## Remaining closure and limits

The final minified release APK passed actual API36 update-install with app data retained, reboot followed by explicit launcher→Main, actual launcher Uninstall→Splash→Main→survey, and force-stop0 own alarms followed by explicit reopen restoring7 own alarms. This proves recovery after explicit launch, not BOOT-only execution or wake timing. [Minified OS smoke](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/final-release-device-smoke.json). Physical manual fourth pinned tile also reached Guide: [actual UI](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/pixel-final-manual/actual-pinned-fourth-tile.xml). Root is completing final physical QA/restoration and task-resource cleanup; those closure operations are not yet claimed complete. Ticket13 retains that closure; ticket08 retains the external write-authorized GitHub PR requirement. Local QA publication is not a remote release; existing5.1.1 does not include RetentionKit.

No OS screen-wake/Doze/OEM deadline, Play card/rating, payment or completed uninstall is inferred. No physical app-data reset was used. Prior pre-correction direct destination/no-entry-ad acceptance remains historical and cannot certify the standard corrected chain. Partner summary: [current Vietnamese report](../../retentionkit/CORRECTION_REPORT.vi.md).
