# 10 Standard entry handoff and uninstall framework

Type: task
Status: resolved
Blocked by:

Spec: ../spec.md, Corrected acceptance

## Scope and acceptance

Own retentionkit, retention-feedback, and required core/interface changes. Standard bridge routes every external entry through actual Splash with existing entry ad policy; explicit no-ad option remains opt-in. Reuse exact durable entry ledger and supply a small lifecycle-safe Main handoff interface; never drain unrelated pending entries. Coordinate exact interface with ticket12 before dependent edits.

Provide generic standard uninstall UI/controller, lifecycle-capable custom UI and host-native slot, optional reasons, feature rescue through Splash, Keep, actual system uninstall confirmation plus app-management fallback/choice. Keep existing source compatibility where practical and document changed defaults. Genericize ordinary core/widget/review/umbrella/feedback fixture content and partner guide snippets; preserve source migration attribution. Own those fixtures/docs, not app or notification implementation.

Regression checks cover entry flags/source/ad policy, exact token/recreation/rapid tap, legitimate skip, UI lifecycle/reentrant guard, custom content and confirmation fallback. Work in private worktree; commit scoped parts. Build only when root grants build ownership. No ADB.

## Comments

Claimed by audit_pdf. Root will merge through a merger agent and verify final app integration.

## Answer

Implemented in private sdk-entry, with stable public contract in [ENTRY_CONTRACT.md](../../../retentionkit/ENTRY_CONTRACT.md). Standard Onboard router now uses actual SplashEntry.intent; explicit WITHOUT_SPLASH_ADS remains opt-in and original constructors retain source compatibility. Core adds a vendor-free RetentionSplashRouter and scoped ENTRY UI purpose. Main helper waits for the real resumed Main, forwards only its selected durable token without consuming feature entries, handles feedback internally and bounds retries/resources; final feature dispatch uses ENTRY in both rechecks. No second queue or backlog fallback.

Feedback now provides openViaEntry, optional reasons/feature rescue/Keep, default Android uninstall confirmation with explicit App Info mode and guarded launch fallback. Custom factory/controller retain session state, insets and lifecycle; nativeContent is a vendor-free host binding closed on destruction/recreation. Every rescue follows configured Splash again with FEEDBACK→UNINSTALL entry classification. Ordinary Main can start an explicit menu entry through safe host UI gates while automatic prompts remain blocked. No uninstall/rating outcome is claimed.

Added widgets.invitation.enabled as a strict invitation-only switch; disabling it closes the dialog/rejects stale clicks while retaining installed widgets, shortcuts and direct user pin. Genericized ordinary core/widget/review/facade/feedback fixtures and guide examples to Notes/Saved items/Text tools/Guide; source migration attribution and historical evidence remain labeled.

Production checkpoints:07cc390 (core/entry/feedback API),1e02926 (invitation switch),e598dab (ordinary Main explicit start),ef94857 (reentrant latest-selection and final dispatch purpose corrections). Separate test/documentation commits preserve the implementation trail.

Validation: actual targeted RED15 at9984e98 produced four expected failures; identical GREEN15 at ef94857 passed. Full affected invocation at clean ef94857 passed162/162 (core49,feedback28,facade28,widgets37,review20), no failures/errors/skips, and all five release AARs in1m49. Commands, module trees, XML/log/AAR SHA256 are in [immutable result](/Users/Shared/Panacea/Documents/SDKOptimize/retentionkit-correction/evidence/sdk-full-ef94857/result.json). This resolves ticket10 SDK scope only. No ADB/remote publish; final app/device/minified consumer acceptance remains tickets12–13.
