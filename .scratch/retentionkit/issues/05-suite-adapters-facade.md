# 05 Suite integration and simple partner facade

Type: task
Status: claimed
Blocked by: 01

Spec: ../spec.md

## Scope and acceptance

Own retentionkit/**, focused suite-firebase retention adapter additions and optional integration package. Implement short umbrella install/config with standard module defaults and feature toggles, dynamic validated/cached overrides wired to the existing RemoteConfigClient and Trackkit without duplicate fetchers. Provide optional OnboardKit route/permission/resume-ad integration preserving no-ad navigation and existing consumers. Never overwrite existing setResumeSkipPolicy. Coordinate final module APIs from tickets02–04. Tests for missing remote keys, stale config, no dependency on ads in selective kits, source callback failure and entry consumption. Any necessary changes to existing OnboardKit must be narrow/source-compatible with regression tests.

Follow the shared implementation contract and acceptance ledger in spec.md. Commit only owned files; write implementation/test results under Answer and change Status to resolved when verified. Provide commit hashes and concise context pointers for the merger.

## Comments

Claimed for preliminary, independent ads/OnboardKit compatibility bridges. Ticket 01 remains a dependency for the Retention facade and adapters; this claim does not resolve that dependency or the whole ticket.

## Answer — preliminary bridge increment

- `AppOpenManager.suppressResume(owner, reason, timeoutMs)` and the three-argument `skipNextResume` return an idempotent `ResumeSuppression` handle. Holds and next-return snapshots are independent per handle, expire within 1–600000 ms and are queried by the existing shared OPEN/WELCOME gate. Cancellation does not clear legacy ad-click state, another owner's lease, the installed ResumeSkipPolicy or the durable enable mode. Commit: `29c5279`.
- `SplashEntry.intentWithoutSplashAds` preserves the existing entry and passthrough while skipping splash banner/interstitial requests, interstitial display and SPLASH_INTER paywall checkpoint. Consent, permission and first-open onboarding continue under host policy. This is explicitly splash-only suppression; later onboarding ads are not silently changed. Existing `intent()` calls and enum entries retain their behavior.
- Validation: `./gradlew :ads:testDebugUnitTest :onboardkitorigin:testDebugUnitTest --max-workers=2 --console=plain` passed: 159 ads tests + 164 OnboardKit tests, zero failures/errors/skips. New coverage includes overlapping owners, failed/canceled system launch, expiry, stale snapshot clearing, legacy click-policy compatibility, all three no-splash-ad entry sources, first-open setup, and the existing tagged ad/checkpoint path.
- Remaining: final core/modules integration, umbrella install facade, Firebase/Trackkit/OnboardKit adapters and their tests. Status remains claimed; no device/packaging result is claimed for this increment.
- Follow-up integration seam: `OnboardingSdk.isFlowActive: StateFlow<Boolean>` is authoritative process UI state. Only an actual Start decision sets it true; terminal outcome delivery resets it before the host listener, reset clears it, and failed Activity launch clears it without swallowing the error. Existing FlowStarted telemetry is unchanged and must not be used as active-state truth because skips also emit it. Targeted `OnboardingActiveFlowTest` (4) plus `SplashLongPromptTest` (10) passed after this addition, covering delayed collection after skip/completion, abort and launch failure.
