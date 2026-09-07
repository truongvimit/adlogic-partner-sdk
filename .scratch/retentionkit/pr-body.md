Partner apps currently implement the same notification, widget, exit-feedback and rating flows independently, which causes inconsistent scheduling, eligibility and navigation. RetentionKit centralizes these flows behind one localized feature catalogue and one install API, with selectable artifacts for smaller integrations.

The change adds `retention-core`, notifications, widgets, feedback, review and the `retentionkit` facade; seven notification presets; persistent configuration, budgets and typed entries; and scoped UI ownership. Optional bridges reuse existing OnboardKit/Ads, authoritative Billing entitlement, Trackkit and the shared Firebase client. The existing example includes four functional EN/VI tools and complete SDK flows; an independent consumer proves every artifact through project and POM-only Maven dependencies. Feedback Continue opens Android App Info. Play review completion remains outcome-unknown.

Local specification/ticket closure checklist (these are Markdown issues, not GitHub issue numbers):

- [x] `.scratch/retentionkit/issues/01-core-foundation.md`
- [x] `.scratch/retentionkit/issues/02-notifications.md`
- [x] `.scratch/retentionkit/issues/03-widgets-shortcuts.md`
- [x] `.scratch/retentionkit/issues/04-feedback-review.md`
- [x] `.scratch/retentionkit/issues/05-suite-adapters-facade.md`
- [x] `.scratch/retentionkit/issues/05a-billing-entitlement.md`
- [x] `.scratch/retentionkit/issues/06-example-integration.md`
- [x] `.scratch/retentionkit/issues/07-validation-docs.md`
- [ ] `.scratch/retentionkit/issues/08-review-completion.md`: all review findings and device defects corrected; PR creation/readiness awaits write-authorized GitHub login.

Validation against base `632df43`: latest7cb23c3 affected suites passed360 actual cases (Ads171, Onboard168, app21), with562 scoped product cases including202 retained unchanged-module cases; this is not one invocation. Debug/test and full app release R8 pass on7cb23c3. The six local RetentionKit AAR/POM publications and12 isolated release-minified consumer/graph/manifest checks remain valid at the unchanged SDK checkpointbf68f1e. Physical4356938 recorded15/15 Android cases after the debug QA clock correction; the final exclusive-device manual matrix and7cb23c3 physical rerun remain pending. Prior API36 acceptance and exact retained/fresh provenance are preserved in the verification ledger. Review and ADB corrections have regressions for reentrant handoffs, rapid Intent ordering and QA clock rollback.

Commands, source-equality records, JUnit/APK hashes and limits are in `.scratch/retentionkit/verification.md`. Pixel5/API34 accepted the final APK update and previously passed the scoped Home/return regression; the unlocked Pixel subsequently passed15/15 Android cases at4356938. Some manual flows passed, but concurrent device input interrupted full UI acceptance. Latest7cb23c3 artifacts have not yet been installed; exact current scope is in `.scratch/retentionkit/physical-acceptance.md`. Emulator alarm/launcher checks do not guarantee every OEM or Play review display. Release build excludes only Crashlytics mapping upload. Artifacts are locally publishable but unreleased; existing5.1.1 does not contain RetentionKit. No release tag or merge is included.
