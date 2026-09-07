# Verify normal partner flows and close review

Type: task
Status: ready-for-agent
Blocked by: 15, 16, 17
Base: 2865f6d

Fresh default physical ADB tests without QA config for foreground/background notifications; OS alarms not receiver injection for lockscreen/calendar; channel/all permission/subscriber/pending suppression; reopen/reboot no launcher schedule recovery. Preserve physical app data/widget/system settings. Verify build and targeted full modules, R8 and consumers for final source. Run independent Standards/Spec review per skill, single fixer, incremental commits, PR external permission recorded accurately.

## Acceptance

Evidence must distinguish ordinary production defaults from explicit synthetic engine fixtures. Prior corrected acceptance is historical and does not prove this reopened request.


## Coordination

Parent tracker checkpoint after ticket16 merge:15 is resolved for module implementation (126 passing selected cases and release AAR),16 is resolved for scoped facade/example implementation (75 passing cases and debug/test APKs),17 remains in progress. This ticket retains the final normal-device, screen-wake, optional-vendor R8/consumer and independent review acceptance. Its dependency on16 is one-way;16 does not remain open waiting for18. Root's early normal-device observations and the historical corrected279/17/matrix records do not constitute completion of this final-source acceptance.


## Feedback readiness correction — claimed

audit_pdf owns the bounded feedback module/test correction in private feedback-readiness at base78545de. Actual Suite16 launcher-Uninstall path creates RetentionFeedbackActivity then closes before survey shown/native loaded; source/evidence investigation and real lifecycle/focus RED→GREEN are required. Agreed seams: SDK Activity lifecycle/focus plus core host gating, real default/custom survey and lease cleanup. Root retains actual devices and overall18 acceptance; this subtask does not resolve18. No ADB/root/other implementation worktree edits. Exclusive Gradle lease transferred from17 for targeted/full feedback checks.

## Answer — feedback readiness implementation scope

Completed by audit_pdf in the private feedback-readiness worktree. A real lifecycle/focus regression reproduced the accepted survey immediately finishing while host focus was false. Internal activation now distinguishes READY/WAITING/TERMINAL; waiting uses focus/core signals with bounded fallback polling and a60-second monotonic timeout. The exact durable session/purpose is retained, actions still require the core lease, and config disable/shutdown cancel waiting UI. Pause, finish and destruction release owned callbacks/subscription/lease; native resources retain their existing lifecycle cleanup. Explicit feedback remains available to paid users.

Actual final command at production/test source `f15d4a45fc5be023d19425e8d6976738918ce8f4`: feedback39/39 PASS, zero failure/error/skip, release AAR PASS18seconds. Evidence: [full manifest](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-default-fix/ticket18-feedback-readiness/full-feedback-final/result.json), [minimized RED](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-default-fix/ticket18-feedback-readiness/red-minimized/result.json). Targeted/diagnostic runs are separate provenance and are not added to39. No ADB/root implementation changes; root receives the Gradle lease for integrated APK/device validation. This scoped Answer completes only the feedback correction; overall18 remains open under parent ownership.
