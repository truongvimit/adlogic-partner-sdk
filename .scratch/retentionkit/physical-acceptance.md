# Pixel 5 — physical acceptance follow-up

**Status: in progress; full manual UI acceptance is not complete.** The Pixel 5 was unlocked and testing resumed on 2026-09-07. Device input was subsequently interleaved with another test/user: Home/Back, launcher MAIN launches and ad screens interrupted the feedback checks. ADB mutations are paused pending exclusive use. This supersedes earlier statements that the device is still PIN-locked.

## Source and actual execution

- Device: Pixel 5, Android 14/API34, serial `14161FDD400111`, system timezone Asia/Ho_Chi_Minh. The existing installation was updated with `adb install -r`; app data was not cleared and the app was not uninstalled.
- The first physical run recorded **13/15**, with DAILY and blocked-channel DAILY failing. The same DAILY method failed twice more. Debug QA rewound its fixture clock from 08:56 to the still-valid 08:00 slot, so production correctly rejected a time before setup. This was a QA input defect, not a change needed in production eligibility.
- `95181844`, merged at `993c376`, makes fixture time advance only. Four actual receiver-backed regressions changed from three failures/one pass to **4/4 PASS**. They cover an already-due valid slot, blocked channel, future slot and expired envelope. Both DAILY instrumentation cases now initialize at 08:56 local the following day and assert due/expiry/setup relationships, so wall time passing 09:00 cannot hide the regression. SDK/main/release code is unchanged by this correction.
- After integration with Ads commit `4356938`, the physical runner recorded **15/15 PASS**, zero failures or skips, with identical app/test APK hashes before and after. Debug APK SHA256: `dce485fbf940205fabc98dd0ca5134529364403f95f6d8e13ce8ef4d5cde649c`; test APK: `9717615f62569dcca8d6c576b0e759e52bc3dfbed4aa4a07bb3f98db7f581b3b`. Actual runner status is preserved; no JUnit XML was fabricated.
- Another user task subsequently committed Ads `7cb23c3`. The private validation checkout includes it and has **360 freshly executed cases PASS**: Ads171, Onboard168, app21. The current scoped product total is **562 = 202 retained cases from unchanged modules + 360 fresh cases**, not a single invocation. The new debug/test artifacts are preserved but **have not been installed/tested on the Pixel** while the device is being used concurrently. The physical 15/15 result must not be relabeled as a 7cb23c3 run. Full release R8 at7cb23c3 also passed; only Crashlytics mapping upload was excluded. Release APK SHA256 `885a6c25b0de18589072c0c142119b2a9733cec624b72010200242c35eb5760c`; mapping `9b9fe55a7577b39e27086bdfd0ba9e3d9e3e9c83a699852cde937510ec226917`. The release APK was not installed on the device.

## Observed manual coverage (4356938 unless noted)

| Flow | Recorded result | Scope / evidence |
|---|---|---|
| Standard Main → Everyday tools | PASS | Actual `OPEN RETENTION PLAYGROUND` button at the earlier2ff967a APK; main app source is unchanged. Setup was already complete at baseline; fresh onboarding was not repeated on this installation. |
| Widget → all four features, warm and cold | PASS for observed destination/business checks | Translation and save, saved phrase visibility, text normalization/count, document reading estimate. Cold cases explicitly killed only the app's background process and confirmed a new process; a new consumed receipt was required. This is not force-stop recovery. |
| Invitation Not now → feedback | PASS | Feedback opened without a Home/timeout workaround; no new widget instance. |
| Pixel Launcher Cancel → feedback | PASS | Actual **Hủy** button; SDK recorded `unknown/returned_without_callback`, not a fabricated Android cancellation callback. Widget17 remained unchanged. |
| Widget addition | Confirmed callback observed | Owned widget17 was added during the earlier2ff967a APK and works after the4356938 update. The initial confirmation tap was not directly observed; a later actual launcher dialog was captured for Cancel. No second-instance/resize/delete pass is claimed. |
| Feedback reasons / rotation / Keep | PASS | All three reasons selected/deselected, same-session selection retained across actual landscape recreation and restored portrait, Keep with and without reasons. |
| Feedback rescue → translation | Destination and usable result observed | Direct Try button and new consumed receipt. Recheck in the exclusive final pass with the other three rescues. |
| Feedback rescue → saved phrases | **Excluded from acceptance** | The driver originally wrote PASS, but system events showed intervening Home/resume. The original record is preserved; the corrected verdict is INCONCLUSIVE. Later continuation also encountered a launcher MAIN/ad sequence outside the driver's commands. |
| Feedback rescue → text/document | Manual pending | The Android suite covers real text rescue; the remaining exclusive manual matrix is unfinished. |
| Seven notification campaigns | Android suite PASS | Actual Android transport/receiver with explicit synthetic QA clock/state. Calendar injection is not OS alarm wake evidence. Pinned calls actual PendingIntents; reminder test invokes the delete PendingIntent, not a manual shade Later tap. |

## Remaining exclusive-device checks

1. Install the exact final debug/test pair and run all 15 cases again, checking both hashes before/after.
2. Complete four manual feedback rescues, App Info + Back with/without reason, manual Rate → correct Store page → Back and immediate subsequent action.
3. Actual shade content/tile taps, Later and swipe dismissal, notification permission deny → grant; distinguish submitted notification from visible lockscreen delivery.
4. Second widget instance, resize, independent delete and EN/VI refresh; actual launcher shortcuts. Remove only widgets created by this test when cleanup is appropriate.
5. Standard force-stop/user relaunch and timezone/reboot recovery where the device remains available. Fresh onboarding, Play quota/card/submitted rating, Doze/OEM timing and uninstall completion must not be inferred from the current suite.

The test-owned screen-awake and rotation settings have been restored to their original values. Notification permission remains granted, timezone and automatic timezone remain unchanged, and unrelated widgets/apps are preserved. Test widget17 and sample business results remain available for continuation. No PIN bypass, app-data reset, uninstall, paid-ad click, remote SDK release or rating submission was performed.

## Evidence

- Physical raw runner, initial failures, minimized physical reproductions, UI XML/PNG, corrected manual verdicts and settings restoration: `/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-device/physical-api34-full-20260907/`.
- Clock RED/GREEN/source/commit/APK proof: `/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-physical-calendar-clock/`; narrative `/Users/Shared/Panacea/Documents/SDKOptimize/RETENTIONKIT_PHYSICAL_CALENDAR_CLOCK_FIX.md`.
- Frozen 4356938 build/tests/R8 and 555-case split provenance: `/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-physical-final-20260907-4356938/`.
- Latest 7cb23c3 build/test proof and artifacts: `/Users/Shared/Panacea/Documents/SDKOptimize/evidence/retentionkit-physical-final-20260907-7cb23c3/`.
- The prior API36 emulator acceptance, library/POM-only consumer matrix and platform limits remain in `verification.md`; they are not substitutes for the pending physical UI work.
