# Verify normal partner flows and close review

Type: task
Status: ready-for-agent
Blocked by: 15, 16, 17
Base: 2865f6d

Fresh default physical ADB tests without QA config for foreground/background notifications; OS alarms not receiver injection for lockscreen/calendar; channel/all permission/subscriber/pending suppression; reopen/reboot no launcher schedule recovery. Preserve physical app data/widget/system settings. Verify build and targeted full modules, R8 and consumers for final source. Run independent Standards/Spec review per skill, single fixer, incremental commits, PR external permission recorded accurately.

## Acceptance

Evidence must distinguish ordinary production defaults from explicit synthetic engine fixtures. Prior corrected acceptance is historical and does not prove this reopened request.


## Coordination

Parent tracker checkpoint after ticket16 merge:15 is resolved for module implementation (126 passing selected cases and release AAR),16 is resolved for scoped facade/example implementation (75 passing cases and debug/test APKs),17 is now resolved for scoped implementation (merged5e26fcf;97 notification tests/AAR PASS;49 unchanged core cases retained). The facade alias/test, Android test naming and explicit example Settings integration still await the next integrated build. This ticket retains the final normal-device, screen-wake, optional-vendor R8/consumer and independent review acceptance. Its dependency on16 is one-way;16 does not remain open waiting for18. Root's early normal-device observations and the historical corrected279/17/matrix records do not constitute completion of this final-source acceptance.
