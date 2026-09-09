**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# adlogic-partner-sdk

Android SDKs for ads, onboarding, analytics, billing and paywalls. Choose the feature you need, complete the shared build setup, then follow that module's quickstart.

## Choose your modules

| Your app needs | Declare | Start here |
| --- | --- | --- |
| AdMob ads | `ads` | [Ads](ads/README.md) |
| Splash + language + onboarding with ads | `ads + onboardkitorigin` | [OnboardKit](onboardkitorigin/README.md) |
| Purchases with your own UI | `billingkit` | [BillingKit](billingkit/README.md) |
| A ready-made paywall | `paykit` | [PayKit](paykit/README.md) |
| Firebase Analytics or remote ads/paywall config | `suite-firebase` (add `ads`/`paykit` for their config sources) | [Firebase](suite-firebase/README.md) |
| Analytics sent to your own backend | `trackkit` | [Trackkit](trackkit/README.md) |
| Ad debugging dashboard | `adtracer (debugImplementation)` | [AdTracer](adtracer/README.md) |

Add only the modules you use. Trackkit is already exposed by ads, onboarding, billing, paywall and Firebase. PayKit brings the billing engine at runtime; add `billingkit` explicitly only if you call its APIs. Firebase is optional. An app using only billing/paywall does not pull in the ads stack.

## Build setup

Use JDK 17, `minSdk 24+` and `compileSdk 36+`. This repository builds with Kotlin 2.1.0, AGP 8.12.0, Gradle 8.13 and targetSdk 36.

Merge these repositories into your existing Gradle repository block; do not create a second `dependencyResolutionManagement` block. The three mediation repositories are needed only for ads/onboarding.

```groovy
// settings.gradle
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }

        // Only when ads or onboardkitorigin is included.
        maven { url 'https://artifact.bytedance.com/repository/pangle/' }
        maven { url 'https://android-sdk.is.com/' }
        maven { url 'https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea' }
    }
}
```

This guide targets **5.2.5**. Keep every module on the same version. When upgrading later, choose an available [release tag](https://github.com/truongvimit/adlogic-partner-sdk/tags) and read the README at that tag.

Example: an app with ads and onboarding. For another combination, replace the artifact names using the table above.

```groovy
// app/build.gradle
def sdkVersion = '5.2.5'
dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
}
```

## Integration order

Register your existing `Application` class in the manifest. In `Application.onCreate()`, after `super.onCreate()`, perform only the steps for the modules you selected:

| Step | What to do | Guide |
| --- | --- | --- |
| 1 · Analytics | To collect SDK events, install `Tracker` and register a destination before other kits emit events. | [Trackkit](trackkit/README.md) |
| 2 · Ads | Provide AdMob/Meta metadata and your ad JSON, then initialize `ERainAd`. | [Ads quickstart](ads/README.md) |
| 3 · Purchases | Install `PayKit`, or initialize `BillingKit` for your own UI. PayKit owns billing initialization when used. | [PayKit](paykit/README.md) / [BillingKit](billingkit/README.md) |
| 4 · Onboarding | Install and configure `OnboardingSdk`, then register your `ObSplashActivity` subclass. | [OnboardKit](onboardkitorigin/README.md) |

With OnboardKit, the splash runs consent and the notification step automatically. With ads alone, run `ConsentCenter.request(...)` from an Activity before requesting ads. Firebase setup also needs your app's `google-services.json` and Google Services plugin; see the [Firebase guide](suite-firebase/README.md).

## Provide your own values

| Area | What to do |
| --- | --- |
| Ads | AdMob app ID, Meta app ID/client token, placement IDs in `assets/ad_config.json` and test IDs in `ad_config_debug.json`. Debug falls back to the normal file if its file is absent. |
| Onboarding | Destination Activity, language/content configuration and ad placements. |
| Purchases | Play product IDs and entitlement mapping. PayKit also needs terms/privacy URLs and your catalog JSON. |
| Firebase · optional | Firebase app configuration and published Remote Config parameters for the sources you use. |

## Verify the integration

- Launch a debug build with test ad IDs; confirm initialization and the consent result before requesting ads.
- Exercise the feature with no ad ready: navigation must still complete. Test notification denial and Home/return.
- If you ship purchases, verify entitlement restoration before showing ads and test the paywall with your Play catalog.

For further options, follow the module guide's source links or use Go to Definition in your IDE. Start with the quickstart; no need to configure every option.

## License

MIT — see [LICENSE](LICENSE).
