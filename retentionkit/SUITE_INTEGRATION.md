# Existing Ads / Onboard / Billing suite

Use this optional entry point when the app already declares OnboardKit, Ads, Billing, Trackkit and `suite-firebase`. The ordinary `RetentionKitOptions` API and selective artifacts stay vendor-free. None of these optional vendor dependencies is added to the umbrella's published runtime graph.

## Three integration points

Install after the existing suite initialization in `Application.onCreate`, including cold receiver/provider starts. Required app facts are the Splash and Main classes, one localized feature catalogue, and the final feature router:

```kotlin
val result = RetentionSuite.install(this, RetentionSuiteOptions(
    splashActivity = SplashActivity::class.java,
    mainActivity = MainActivity::class.java,
    featureProvider = RetentionFeatureProvider(::localizedFeatures),
    featureRouter = RetentionRouter { context, _ -> Intent(context, FeatureActivity::class.java) },
))
```

Handle `RetentionKitInstallResult.Failed` as a configuration failure; repeated installation reuses the active suite. All four retention modules and the common notification plan are enabled by default. The preset owns the Onboard router/UI gate, authoritative Billing state, actual Ads click observation, Trackkit sink, and common legacy/JSON Firebase configuration through the existing shared fetch. Do not add those adapters again. `usesBilling = false` is only for an app with no IAP; an IAP app stays UNKNOWN until Billing supplies verified state.

Make the app Splash extend `RetentionSplashActivity` instead of `ObSplashActivity`. Keep existing billing, remote-config, ad and consent overrides, calling super where required. The base captures and saves the exact materialized entry; a new explicit tap gets another real Splash attempt. Inside the existing OnboardingListener, after the app's own language/business work, delegate once:

```kotlin
RetentionSuite.get()?.onOutcome(context, outcome)
```

Do not replace the listener, bypass an entry ad because setup is complete, or start Main a second time. The suite uses the outcome's exact passthrough, preserves the standard `inter_noti` / `inter_widget` / `inter_uninstall` policies and cancels only its unfinished-setup selections on Aborted.

The final ComponentActivity implements one UI callback:

```kotlin
class FeatureActivity : AppCompatActivity(), RetentionFeatureHost {
    override fun onRetentionFeature(entry: RetentionEntry, destination: String) {
        showFeature(destination)
    }
}
```

Main needs no capture, saved-state, new-Intent or handoff fields. The suite binds before onStart, waits for actual resumed/focused Main readiness, handles standard SDK feedback internally, and forwards features without consuming. At the final Activity it owns capture, new delivery replacement, saved materialization, bounded retry and final claim. A normal or rejected new Intent clears only that Activity's selection; unrelated pending entries remain inert. Retries stop on pause/destroy or after ten seconds and restart on resume/focus gain. Known retired IDs can use `resolveDestination`; the original envelope/UUID is preserved. The callback consumes at most once before rendering; process death or a throwing host callback does not promise exactly-once presentation. Business operations retain their own durable IDs/outbox.

## Explicit permission and readiness

Place a visible normal UI button for `RetentionSuite.get()?.requestNotifications(this)`. It requests Android13+ permission only after that user action, or opens app notification settings when needed. `openNotificationSettings(this)` is also available. The suite registers the launcher, suppresses only its owned resume-ad transition, and reconciles on result/return. The handoff expires after120 seconds and closes on source destruction. A late permission result cannot close a newer settings or foreign owner's scope; a still-unanswered permission request cannot start a second indistinguishable permission request.

If Onboard or another existing host component already owns the permission prompt, keep that prompt and call `permissionChanged()` from its result instead. There is no automatic permission prompt and no second permission owner that runs on install/resume.

Ordinary notifications react to SDK readiness; partners do not call `refreshForegroundNotifications()` to start defaults. `notificationStatus()` is read-only and reports each campaign's enabled/pending state, last real outcome/blocked reason, next saved alarm and config revision. “Submitted” means the Android post call succeeded, not that the user saw it. Permission, channel settings, verified entitlement and real host state still apply.

`hostCanPresent` defaults to actual window focus; supply an additional short predicate for app-owned dialogs/consent/paywalls. Configured Main permits explicit entries but never automatic widget/review prompts. Native container layout, selected app locale, catalogue content and completed business operations remain app facts; pass `localeProvider`, feedback/native options and report stable success UUIDs as needed. Never capture an Activity in application-level options. `customize` is an advanced escape hatch for selective behavior or explicitly declared QA fixtures; normal integration needs none.

## Verification scope

This simplification is a new implementation checkpoint. Earlier correction matrix/device results are historical, not acceptance for these lifecycle changes. Ticket16 records fresh facade/app and subsequent device validation.
