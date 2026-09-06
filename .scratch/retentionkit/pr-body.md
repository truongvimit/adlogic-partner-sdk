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

Validation against base `632df43`: 549 actual unit/Robolectric cases passed with no failures/skips (532 on unchanged library source plus17 freshly executed app tests), final15/15 Android cases on API36 emulator, full app debug/test/R8 release, existing paywall sample debug, six local AAR/POM publications and12 isolated release-minified consumer/graph/manifest checks. Final SDK is `bf68f1e`; final app implementation is `2ff967a`; later commits change documentation only. Review and ADB found reentrant handoff and rapid-Intent ordering defects; regression tests reproduce and protect both corrections.

Commands, source-equality records, JUnit/APK hashes and limits are in `.scratch/retentionkit/verification.md`. Pixel5/API34 accepted the final APK update and previously passed the scoped Home/return regression; it remains PIN-locked, so full physical feature UI is not certified. Emulator alarm/launcher checks do not guarantee every OEM or Play review display. Release build excludes only Crashlytics mapping upload. Artifacts are locally publishable but unreleased; existing5.1.1 does not contain RetentionKit. No release tag or merge is included.
