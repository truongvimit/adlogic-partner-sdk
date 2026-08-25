**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# adlogic-partner-sdk

> Seven Android libraries for ads, onboarding, analytics, billing and paywalls, published together from one repository.

## Modules

| Module | What it does | Guide |
|---|---|---|
| `ads` | AdMob loading/showing, ad config, UMP consent, premium gating | [ads/README.md](ads/README.md) |
| `onboardkitorigin` | First-open flow: splash, language, onboarding pager, survey | [onboardkitorigin/README.md](onboardkitorigin/README.md) |
| `trackkit` | Vendor-free analytics contract (`Tracker`, `TrackSink`, taxonomy) | [trackkit/README.md](trackkit/README.md) |
| `suite-firebase` | The one Firebase adapter: GA4 sink, ad config source, paywall config source | [suite-firebase/README.md](suite-firebase/README.md) |
| `billingkit` | Play Billing engine (`com.ads.module.billing`) | [billingkit/README.md](billingkit/README.md) |
| `paykit` | Paywall UI over the `billingkit` engine | [paykit/README.md](paykit/README.md) |
| `adtracer` | Debug-only ad lifecycle dashboard | [adtracer/README.md](adtracer/README.md) |

## Which modules to declare

| Partner | Declares | What the APK provably lacks |
|---|---|---|
| Ads only, no IAP | `ads` (+ `suite-firebase`) | No Play Billing class at all |
| IAP + prebuilt paywall, no ads | `billingkit` + `paykit` | No GMA/AdMob class at all |
| IAP with your own paywall UI | `billingkit` | Neither `paykit` nor `ads` |
| Ads and IAP | `ads` + `billingkit` (+ `paykit`) | — |

`onboardkitorigin` depends on `ads`, and `paykit` on `billingkit`, at runtime scope only — declare those
explicitly if you call their APIs. Never declare `trackkit`: every module that uses it exports it with `api`.

## Requirements

| | |
|---|---|
| JDK / Kotlin `jvmTarget` | 17 |
| `minSdk` | 24 |
| `compileSdk` / `targetSdk` | 36 |
| AGP / Gradle | 8.12.0 / 8.13 |

## Installation

The mediation adapters `ads` bundles are not on Maven Central — without the last three
repositories the build cannot resolve Pangle, ironSource and Mintegral:

```groovy
repositories {
    google()
    mavenCentral()
    maven { url 'https://jitpack.io' }
    maven { url 'https://artifact.bytedance.com/repository/pangle/' }
    maven { url 'https://android-sdk.is.com/' }
    maven { url 'https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea' }
}

// Replace <tag> with a tag from https://github.com/truongvimit/adlogic-partner-sdk/tags
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:suite-firebase:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:paykit:$sdkVersion"
    debugImplementation "com.github.truongvimit.adlogic-partner-sdk:adtracer:$sdkVersion"
}
```

The group id is `com.github.truongvimit.adlogic-partner-sdk` — JitPack namespaces a multi-module repo as
`com.github.<user>.<repo>`. Keep every module on the same tag; cross-version combinations are not tested.

## Quick start

**1. Your `Application`.** This order is load-bearing — see [ads/README.md](ads/README.md).

```kotlin
class App : AdsMultiDexApplication() {
    override fun onCreate() {
        super.onCreate()

        Tracker.install(this, TrackerConfig(appVersionCode = BuildConfig.VERSION_CODE.toLong()))
        Tracker.addSink(FirebaseSink(collectionFollowsConsent = false))
        AdRemoteConfig.initializeFromAssets(this)
        AdConfig.install(FirebaseAdConfigSource())
        ConsentCenter.configure(ConsentOptions(timeoutMs = 20_000L))

        mERainAdConfig = ERainAdConfig(this, ERainAdConfig.ENVIRONMENT_PRODUCTION)
        mERainAdConfig.adjustConfig = AdjustConfig(true, getString(R.string.adjust_token)).apply {
            eventAdImpression = getString(R.string.event_token)
            fbAppId = getString(R.string.facebook_app_id)
        }
        mERainAdConfig.facebookClientToken = getString(R.string.facebook_client_token)
        ERainAd.getInstance().init(this, mERainAdConfig)
        ERainTuning.install()
        AppOpenManager.getInstance().disableAppResumeWithActivity(SplashActivity::class.java)

        OnboardingSdk.install(this) {
            adProvider = ERainAdProvider()
            listener = OnboardingListener { context, _ ->
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
```

**2. Your splash.** `class SplashActivity : ObSplashActivity()`, declared as the `MAIN`/`LAUNCHER`
activity with an AppCompat theme. Consent, remote fetch, splash ads, the minimum display time and
the navigation out all run inside it — never call `OnboardingSdk.start()` from here.

**3. Configure the flow.** After `install(...)`, call `onboardKitConfig { ... }.onSuccess { OnboardingSdk.configure(it) }`
— the builder returns a `Result`. Without a config the flow is skipped. See [onboardkitorigin/README.md](onboardkitorigin/README.md).

## What your app must declare

**`AndroidManifest.xml`** — inside `<application>`. GMA throws at init without the first entry; the
two Facebook ones are required because `ERainAd.init` initializes `FacebookSdk` unconditionally:

```xml
<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="${app_id}" />
<meta-data android:name="com.facebook.sdk.ApplicationId"  android:value="@string/facebook_app_id" />
<meta-data android:name="com.facebook.sdk.ClientToken"    android:value="@string/facebook_client_token" />
```

Set `manifestPlaceholders = [app_id: "ca-app-pub-XXXX~YYYY"]` per build type, and set `android:name` on
the `<application>` tag to your `Application` class.

**String resources** — `translatable="false"`:

| Name | Used by | Blank means |
|---|---|---|
| `adjust_token` | Adjust app token | Adjust stays off, logged as an error |
| `event_token` | Adjust ad-impression event | Impressions skipped with a warning |
| `adjust_event_token_purchase` | Adjust purchase event | Purchases skipped with a warning |
| `facebook_app_id` | Meta adapter + Adjust `fbAppId` | `FacebookSdk.sdkInitialize` throws in `Application.onCreate` |
| `facebook_client_token` | Meta adapter | Facebook requests fail server-side |

**Files you create** — the SDKs ship none of these:

| Path | Required | Missing it means |
|---|:---:|---|
| `src/main/assets/ad_config.json` | yes | Every placement is disabled silently, no crash |
| `src/main/assets/ad_config_debug.json` | strongly advised | A debug run spends your **live** ad units |
| `google-services.json` | with `suite-firebase` (plus the `com.google.gms.google-services` plugin) | No GA4 sink, no remote ad config, no paywall document |

## Reducing APK size

`ads` bundles seven AdMob mediation adapters, the largest thing in the APK. Drop the ones your AdMob account
does not mediate — on `configurations`, not on the `ads` dependency, because `onboardkitorigin` depends on
`ads` too and a per-dependency exclude would leave that second path open:

```groovy
configurations.configureEach {
    exclude group: 'com.google.ads.mediation', module: 'pangle'
    exclude group: 'com.pangle.global'
}
```

Each network is an adapter plus the SDK it pulls; excluding only the adapter leaves the SDK behind. Pairs:
`applovin`→`com.applovin`, `vungle`→`com.vungle`, `pangle`→`com.pangle.global`, `unity`→`com.unity3d.ads`,
`mintegral`→`com.mbridge.msdk.oversea`, `ironsource`→`com.unity3d.ads-mediation`. `facebook` is the
exception — exclude the module, never the group: `exclude group: 'com.facebook.android', module:
'audience-network-sdk'`. That group also holds `facebook-core`, which `ERainAd.init` requires.

If R8 reports `Missing class` for an excluded network, add `-dontwarn com.pangle.global.**` (and so
on) to `proguard-rules.pro`. Change your AdMob mediation groups before the Gradle exclusions.

## License

MIT — see [LICENSE](LICENSE).
