# Partner integration guides

Pick the guide for the feature your app needs. Work through the basic integration in order; open the optional tables only when your app needs to change a default behaviour.

| Your app needs | Guide to read | Module |
| --- | --- | --- |
| Splash → language → onboarding with ads → main screen | **[Ads + OnboardKit integration](ads-onboarding-integration.md)** | `ads` + `onboardkitorigin` |
| Ads in your app's own screens | Already did the Ads + OnboardKit guide: use the [native](ads-onboarding-integration.md#app-screen-native-with-a-placement-constant), [inter](ads-onboarding-integration.md#app-screen-interstitial-with-a-placement-constant), [app-open](ads-onboarding-integration.md#app-open-on-return) samples with your app's [AppAdPlacement.kt](examples/ads-onboarding/AppAdPlacement.kt). Not using OnboardKit: follow [Ads](../ads/README.md) from init and placements through consent. | `ads` |
| Purchases with your own UI | [BillingKit integration](billing-integration.md) | `billingkit` |
| A ready-made paywall | [PayKit integration](paywall-integration.md) | `paykit`, add `billingkit` when you call its APIs directly |
| Remote JSON / Firebase Analytics | [Firebase integration](firebase-integration.md) | `suite-firebase` plus the module you connect |
| Your app's events / choosing where analytics go | [Trackkit integration](trackkit-integration.md) | `trackkit` is already exposed by the kits |
| Ad debugging dashboard | [AdTracer integration](adtracer-integration.md) | `adtracer`, debug only |

## Order to wire into your app

1. **Pick the main feature:** Ads/OB, BillingKit with your own UI, or PayKit with its ready-made UI. An app can combine ads and purchases; PayKit initializes billing itself, so do not register another catalog with `AppPurchase.initBilling`.
2. **If you collect analytics:** install Tracker and a sink in the Application before the kits emit events. Firebase and AdTracer are optional destinations; wire Adjust through the [ads/Adjust guide](ads-onboarding-integration.md#adjust-tokens-and-verification).
3. **Initialize the kits and local data:** use your existing Application. With OB plus a paywall, install PayKit before OnboardKit; wait for billing in the splash hook before ads.
4. **If you use remote:** add the Firebase sources after the local setup. The OB splash already refreshes ads; the paywall needs its own `PayKit.sync()` before you need remote config.
5. **Test:** run the checklist at the end of the guide you chose; add AdTracer if you need to follow the ad flow on a dashboard.

You do not need to do every guide. Each guide states its dependencies, the files you need and the optional parts; do not copy several Application classes or install the same SDK more than once.

## How to use these guides

- **Basic code:** declares only your app's data and the SDK wiring; keeps the SDK defaults; the sample JSON keeps the example's configuration.
- **Optional tables:** state the default, when to change it and where to configure it. You do not need to copy a whole table into your code or Firebase.
- **App placements:** [AppAdPlacement.kt](examples/ads-onboarding/AppAdPlacement.kt) collects the OB keys and your app screens in one place. The placement key is the only identity of an ad slot — an ad unit ID is shared across placements and so cannot tell them apart; ad unit IDs live in the JSON.
- **Sample files:** [ad_config.json](examples/ads-onboarding/ad_config.json) and [ad_config_debug.json](examples/ads-onboarding/ad_config_debug.json) both use test ad IDs. Copy them into `app/src/main/assets/`; replace the IDs in the real file before release.
- **Module READMEs:** look up further APIs, lifecycle and customization when you need them.

## Sample files to copy

| Feature | Sample | Where it goes |
| --- | --- | --- |
| Ads + OB | [Production JSON](examples/ads-onboarding/ad_config.json), [debug JSON](examples/ads-onboarding/ad_config_debug.json), [AppAdPlacement](examples/ads-onboarding/AppAdPlacement.kt), [OnboardKitSetup](examples/ads-onboarding/OnboardKitSetup.kt), [PartnerApp](examples/ads-onboarding/PartnerApp.kt) | Assets, catalog and SDK initialization; both JSON files use test ad IDs. |
| Billing with your own UI | [BillingProducts.kt](examples/billing/BillingProducts.kt) | Product/base plan/offer catalog; replace with your app's Play products. |
| Paywall | [paywall_config.json](examples/paywall/paywall_config.json), [paywall_strings.xml](examples/paywall/paywall_strings.xml) | `res/raw`, `res/values`; the example's fields are complete, replace the catalog/copy/URLs. |
| App events | [AppEvents.kt](examples/trackkit/AppEvents.kt) | Event/param keys in one place, keep only the events your app needs. |
| Debug dashboard | [AdTracerSink](examples/adtracer/debug/AdTracerSink.kt), [debug entry](examples/adtracer/debug/DebugSinks.kt), [release no-op](examples/adtracer/release/DebugSinks.kt) | debug/release source sets; same package as your app. |

Firebase uses the `google-services.json` the Console issues for your app; there is no sample credentials file to copy.

All dependency examples read the same `adlogicSdkVersion` from your app's `gradle.properties`; see [build setup](../README.md#build-setup). To upgrade the SDK, update that property in your app.

[SDK README](../README.md) · [Start with Ads + OnboardKit](ads-onboarding-integration.md)
