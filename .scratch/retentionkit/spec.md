# RetentionKit implementation specification

Status: active
Base: main at 632df43026b6f880deb84d478c71d22188ce093a
Delivery branch: codex/retentionkit
Requested: 2026-09-07

## Outcome

Implement and publish-ready package a complete Android RetentionKit inside this SDK repository, integrated into the existing example app. Partners configure content, destinations, user state and business success events; the SDK owns the repeated flow and Android mechanics. TranslatorGuru is the primary source reference, with PDF Reader and Caller-ID supplying widget/pinned/exit variants. Do not modify those reference applications.

The parent research is `/Users/Shared/Panacea/Documents/SDKOptimize/RETENTIONKIT_FEASIBILITY_REPORT.md`; related audit and platform files in that directory contain exact source pointers. Research statements are not substitutes for current tests. Existing SDK consumers must retain source compatibility and behavior unless they explicitly install the new kit/adapter.

## Required deliverables

1. Publishable `retention-core`, `retention-notifications`, `retention-widgets`, `retention-feedback`, `retention-review`, and umbrella `retentionkit` Android library modules. A selective consumer must not inherit unrelated modules or ads/Firebase/Compose. All publish alongside existing modules through the existing Gradle/JitPack conventions. Core may depend on AndroidX lifecycle/core and vendor-free trackkit; no vendor ads/billing/config dependency.
2. One install entry, standard defaults, shared content catalogue, typed entry envelope, persistent state, coordinated foreground prompts, replaceable content/UI/navigation, explicit outcomes and debug diagnostics. Install from Application must survive cold receiver/provider starts without Activity/network prerequisites. Invalid partner configuration reports a failure without crashing app startup.
3. Standard notification flows: daily, inactive winback, onboarding abandonment, delayed ad-click return, silent reminder with Later, pinned feature tiles and lockscreen skip/replace/rotation. All posts pass current enabled/user/permission/channel/foreground/cooldown/cap/TTL checks. Marketing suppression applies to the configured marketing channels, not the app's functional notifications.
4. Widget action-grid template with localized feature labels/icons, pin prompt/callback, unsupported and unknown outcomes, independent multiple instances, update/resize/delete/restore handling. Custom provider/renderer option. Dynamic feature and feedback shortcuts only manipulate SDK-owned IDs.
5. Reusable exit-feedback UI: optional reasons, feature rescue/Keep, clear Continue to app management. No mandatory survey, no uninstall interception, no success claim after opening system Settings.
6. Play review after business successes with persisted threshold/cooldown/cap and in-flight protection, safe Activity lifecycle, bounded async callbacks and UI coordination. Manual Rate opens Play Store. No pre-review star gating; completion is outcome-unknown, never rated=true. Feedback and review state are separate.
7. Optional adapter fitting OnboardKit routing, resume-ad suppression, Trackkit and suite-firebase's existing shared config client. Permission must have one owner. Entry extras survive onboarding and are consumed once at final destination. No default second Firebase fetcher, duplicated resume observer or forced changes to existing splash behavior.
8. Working example demonstrating all flows with real feature destinations and clear labels. A dedicated sample playground is appropriate in this SDK demo app; business sample content can be translation/document utilities. Release example must have genuine defaults. Debug-only controls may move eligibility time/test delays and show diagnostics but must never bypass production logic in the library or claim Play review success. No exported release test receivers.
9. Unit/Robolectric + relevant instrumentation tests, build/minify/manifest/dependency composition checks, ADB tests on attached Pixel 5 API34 and a reproducible device script/result ledger with commands, results and limitations. Unit tests must cover failure/restart/concurrency/config transitions, not merely constructors/getters.
10. Partner README with minimal full example, customization and migration, remote keys/defaults, callback semantics, target/minSdk details and known platform behavior. Complete draft PR with scoped commits, code-review findings fixed, ready for review. Do not merge PR or create a release tag.

## Platform and product decisions for this implementation

- Notification templates may appear on the lockscreen through normal Android notification behavior. No default full-screen intent, forced screen wake, exact-alarm permission or permanent foreground service for marketing. Do not reproduce reference-app bugs or APIs that imply those guarantees.
- Default slots match Translate: daily 08:00/19:00; winback 11:00/14:00; lockscreen 11:30/17:00/20:00; new-user cohort first two elapsed days. Setup completion plus an initial 24-hour marketing grace are explicit defaults; standard winback requires 48 hours inactive. Defaults can be overridden through a validated versioned profile.
- Onboarding abandonment is an explicit eligibility phase: it requires active **unfinished** onboarding, so it must not pass through an unconditional setup-completed gate. Its default setup grace is zero and its background confirmation delay is 3 seconds; it still requires known non-subscriber state, permission/channel, no external transition, current background, TTL/cap/cooldown and active campaign. Completing/aborting onboarding or returning foreground cancels it. The 24-hour post-setup grace remains the default for ordinary after-setup marketing. A configurable nonzero onboarding grace is measured from install; do not reset it on every onboarding screen.
- Reminder cooldown 15 minutes. Ad-click-return waits 3 seconds after confirmed process background, with an expiring click token and cancellation on foreground/system transition; a stale click must not trigger a later unrelated exit. Onboarding abandonment is scoped to a host-reported onboarding state, not every Activity pause.
- Review defaults match Translate: 5 business successes, 10 days between attempts, maximum 3 attempts. Count an attempt at the chosen documented SDK-request milestone; failed/late/duplicate callbacks cannot spend multiple counters or lock the prompt gate forever.
- Stable notification IDs and pending-intent identity; each action retains its own destination. State must be durable and transactional within the app process. Explicitly document multi-process constraints instead of pretending SharedPreferences is a cross-process transaction system.
- Scheduler computes local calendar slots, handles DST/timezone/clock changes, and does not catch up expired slots in a burst. Reconcile old/new desired schedules atomically by revision: stale callbacks cannot resurrect disabled/deleted campaigns. Restore on boot, own package replacement, time/timezone change and eligible app relaunch. Force-stop remains stopped until user interaction.
- Store last-known-good remote overrides and parse absent fields without erasing defaults. Remote source initialization must not block Application startup. App subscriber entitlement Unknown suppresses marketing until resolved; app without IAP can explicitly report non-subscriber.
- UI flows coordinate one prompt at a time and cooperate with host ads/onboarding/paywall. Host external/system transitions have bounded/scoped suppression. Callback completion must not navigate from a destroyed/background Activity or create an Activity leak.
- Notification PendingIntent points directly to an Activity; no broadcast/service trampoline for clicks. Dismiss/Later receivers only cancel/track and cannot launch UI. Internal receivers not exported; externally reachable entries validate envelopes/IDs.
- SDK telemetry names actual evidence: post_submitted, skipped(reason), opened, dismissed; pin_requested vs pin_confirmed; review_requested vs flow_finished_unknown; feedback/system_handoff. Trackkit adapter failures never break functional flow.
- Default entry route does not force an interstitial at app load/exit. Optional OnboardKit bridge preserves typed entry and destination and supports a real no-ad route; existing SplashEntry users remain compatible. The example can demonstrate entry sources and existing ad configuration without making unsupported policy promises.

## Shared implementation contract

Ticket 01 owns the exact public core interfaces and records them in `retention-core/CONTRACT.md` with usage examples before downstream tickets start. Prefer small Kotlin interfaces/data types and Java-interoperable APIs. Downstream module implementations depend only on the core contract.

The contract must include:

- Localized `RetentionFeature` catalogue provider; stable feature IDs and optional bundled image assets; context for selected app locale, not only device locale.
- `RetentionEntry` encode/decode and unique consumption token with source/campaign/action/destination. Host router constructs an explicit Activity intent; final-destination consumption is a separate operation so splash routing cannot recurse.
- `RetentionUserState`, setup/onboarding and entitlement snapshot; `RetentionSignal` for setup, success, ad click, scoped/external transitions, process foreground/background and config changes.
- Runtime install/get/uninstall-for-tests; module attach/signal/reconcile/shutdown lifecycle; safe subscriber list dispatch; shared clock, persistent namespaced store, UI lease coordinator, diagnostics and guarded event sink. Isolate a failing module/callback without swallowing programmer errors silently in diagnostics.
- Capability/suppress result types and no-op defaults; lifecycle-owned foreground Activity access without strong leaks. Config/update observer hooks sufficient for module-specific validated settings and cached overrides.
- Deterministic scheduling/rotation/budget helpers where genuinely shared; keep notification-specific behavior inside the notification module.

Avoid publishing APIs nobody uses: every public capability must have either a sample callsite or an acceptance test. Custom UI still uses the SDK-provided entry/action identities; it does not replace the state machine.

## Acceptance ledger

Every required behavior is tracked under `.scratch/retentionkit/verification.md` with test command/name or device evidence and status. `not supported by platform`, `not tested on unavailable hardware`, and `failed` are separate from `passed`. Completion means all implemented supported flows and required checks pass; a single Pixel cannot certify every OEM.

Minimum scenarios: denied/granted-later permission; disabled channel; premium/unknown/setup suppression; daily/winback; duplicate trigger and async disable; lockscreen skip across midnight and replace; content rotation after process death; reduced slots/update/reboot; TTL/Doze and force-stop recovery; background vs internal Activity; delayed-return cancellation; all notification actions; cold/warm/onNewIntent routing; widget cancel/confirmed/multiple instances; review in-flight/quota/failure/lifecycle; feedback keep/feature/reasons/continue; ads/system-return suppression; minified assembly and isolated module packaging.

## Task graph

01 core and module foundation → {02 notifications, 03 widgets, 04 feedback and review, 05 suite adapters and facade}

{02,03,04,05} → 06 example integration → 07 device validation and docs → 08 code review and final fixes.

Additive Ticket05a provides authoritative Billing entitlement needed by T05/T06: cached false or verification completion after a failed sweep is insufficient evidence for NON_SUBSCRIBER. Existing Billing APIs stay source-compatible.

T05 may inspect while other modules are being implemented but must verify against their final public APIs. T07 may prepare tests earlier. Each implementer uses a private worktree/branch and commits only its ticket-owned files. A merger agent merges completed work onto `codex/retentionkit`. Only root uses the physical ADB device unless explicitly delegated. Gradle concurrency max two workers per invocation; avoid multiple memory-heavy full-app builds.

## PR and local issues

This repository's issue tracker is local Markdown. The PR closes this spec and the numbered local tickets by path/checklist, not fabricated GitHub issue numbers. Specs/tickets are force-added individually because `.scratch/` is ignored; do not alter the user's ignore policy or commit unrelated local notes/secrets.
