**Language / Ngôn ngữ / भाषा:** [English](README.md) | [Tiếng Việt](README.vi.md) | [हिन्दी](README.hi.md)

# adlogic-partner-sdk

> Seven Android libraries for ads, onboarding, analytics, billing and paywalls, published together
> from one repository.

Declare only the modules you ship. This page covers the build setup and the integration order;
each module's README is the guide for its own surface.

## Modules

| Module | What it does | Guide |
|---|---|---|
| `ads` | AdMob loading/showing, ad config, UMP consent, premium gating | [ads/README.md](ads/README.md) |
| `onboardkitorigin` | First-open flow: splash, language, onboarding pager, survey | [onboardkitorigin/README.md](onboardkitorigin/README.md) |
| `trackkit` | Vendor-free analytics contract (`Tracker`, `TrackSink`) | [trackkit/README.md](trackkit/README.md) |
| `suite-firebase` | The one Firebase adapter: GA4 sink, ad config source, paywall config source | [suite-firebase/README.md](suite-firebase/README.md) |
| `billingkit` | Play Billing engine (`com.ads.module.billing`) | [billingkit/README.md](billingkit/README.md) |
| `paykit` | Paywall UI over the `billingkit` engine | [paykit/README.md](paykit/README.md) |
| `adtracer` | Debug-only ad lifecycle dashboard | [adtracer/README.md](adtracer/README.md) |

## Which modules to declare

| You ship | Declare | What the APK provably lacks |
|---|---|---|
| Ads only (IAA) | `ads` (+ `suite-firebase`) | No Play Billing class at all |
| IAP + prebuilt paywall, no ads | `billingkit` + `paykit` | No GMA/AdMob class at all |
| IAP with your own paywall UI | `billingkit` | Neither `paykit` nor `ads` |
| Ads and IAP | `ads` + `billingkit` (+ `paykit`) | — |

`onboardkitorigin` depends on `ads`, and `paykit` on `billingkit`, at `implementation` scope —
declare those explicitly if you call their APIs. Never declare `trackkit`: every module that uses
it exports it with `api`.

## Requirements

| | |
|---|---|
| JDK / Kotlin `jvmTarget` | 17 |
| `minSdk` | 24 |
| `compileSdk` / `targetSdk` | 36 |
| AGP / Gradle | 8.12.0 / 8.13 |

## Installation

The mediation adapters `ads` bundles are not on Maven Central — without the last three
repositories the build cannot resolve Pangle, ironSource and Mintegral.

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

The group id is `com.github.truongvimit.adlogic-partner-sdk` — JitPack namespaces a multi-module
repo as `com.github.<user>.<repo>`. Keep every module on the same tag; cross-version combinations
are not tested.

## What your app must provide

**`AndroidManifest.xml`** — inside `<application>`, when you ship `ads`. GMA throws at init without
the first entry; the two Facebook ones are required because `ERainAd.init` initializes `FacebookSdk`
unconditionally:

```xml
<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="${app_id}" />
<meta-data android:name="com.facebook.sdk.ApplicationId"  android:value="@string/facebook_app_id" />
<meta-data android:name="com.facebook.sdk.ClientToken"    android:value="@string/facebook_client_token" />
```

Set `manifestPlaceholders = [app_id: "ca-app-pub-XXXX~YYYY"]` per build type, and point
`android:name` on the `<application>` tag at your own `Application` class.

**String resources** (`translatable="false"`). `facebook_app_id` and `facebook_client_token` are
mandatory with `ads`. `adjust_token`, `event_token` and `adjust_event_token_purchase` are read only
by the `AdjustConfig` you build yourself — a blank `adjust_token` turns Adjust off.

**Files you create** — the SDKs ship none of these:

| Path | Required for | Missing it means |
|---|---|---|
| `src/main/assets/ad_config.json` | `ads` | Every placement is disabled silently, no crash |
| `src/main/assets/ad_config_debug.json` | debug builds | A debug run spends your **live** ad units |
| `google-services.json` + the `com.google.gms.google-services` plugin | `suite-firebase` | No GA4 sink, no remote ad config, no paywall document |

## Integration order

Everything below runs in `Application.onCreate()`, in this order. The order is load-bearing:
`Tracker` first because events emitted earlier are only buffered, and the ad config before
`ERainAd.init` because that is what binds ad unit ids to placements.

```kotlin
class App : AdsMultiDexApplication() {
    override fun onCreate() {
        super.onCreate()

        // 1. Analytics — see trackkit/README.md and suite-firebase/README.md
        Tracker.install(this, TrackerConfig(appVersionCode = BuildConfig.VERSION_CODE.toLong()))
        Tracker.addSink(FirebaseSink(collectionFollowsConsent = false))

        // 2. Ads — see ads/README.md
        AdRemoteConfig.initializeFromAssets(this)
        AdConfig.install(FirebaseAdConfigSource())
        ConsentCenter.configure(ConsentOptions(timeoutMs = 20_000L))
        ERainAd.getInstance().init(this, buildERainAdConfig())
        AppOpenManager.getInstance().disableAppResumeWithActivity(SplashActivity::class.java)

        // 3. Billing and paywall — see billingkit/README.md and paykit/README.md
        PayKit.install(this, payKitConfig { /* … */ }.getOrThrow())
        PayKit.configSource(FirebaseConfigSource())

        // 4. First-open flow — see onboardkitorigin/README.md
        ERainTuning.install()
        OnboardingSdk.install(this) {
            adProvider = ERainAdProvider()
            paywallGate = OnboardKitPaywallGate()
            listener = OnboardingListener { context, _ ->
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        OnboardingSdk.configure(buildOnboardKitConfig())
    }
}
```

Then make your launcher activity `class SplashActivity : ObSplashActivity()`. Consent, the remote
fetch, splash ads, the minimum display time and the navigation out all run inside it — see
[onboardkitorigin/README.md](onboardkitorigin/README.md).

Drop any step whose module you do not ship: an ads-only app stops after 2, an IAP-only app declares
only 1 and 3.

## Reducing APK size

`ads` bundles seven AdMob mediation adapters, the largest thing in the APK. Drop the ones your
AdMob account does not mediate — on `configurations`, not on the `ads` dependency, because
`onboardkitorigin` depends on `ads` too and a per-dependency exclude would leave that second path
open:

```groovy
configurations.configureEach {
    exclude group: 'com.google.ads.mediation', module: 'pangle'
    exclude group: 'com.pangle.global'
}
```

Each network is an adapter plus the SDK it pulls; excluding only the adapter leaves the SDK behind.
Pairs: `applovin`→`com.applovin`, `vungle`→`com.vungle`, `pangle`→`com.pangle.global`,
`unity`→`com.unity3d.ads`, `mintegral`→`com.mbridge.msdk.oversea`,
`ironsource`→`com.unity3d.ads-mediation`. `facebook` is the exception — exclude the module, never
the group: `exclude group: 'com.facebook.android', module: 'audience-network-sdk'`. That group also
holds `facebook-core`, which `ERainAd.init` requires.

If R8 reports `Missing class` for an excluded network, add `-dontwarn com.pangle.global.**` (and so
on) to `proguard-rules.pro`. Change your AdMob mediation groups before the Gradle exclusions.

## Finding the details

These READMEs cover integration only. Every option, default and behaviour flag is documented in
KDoc on the type that owns it, and every module publishes a sources jar — so the full, always
current reference is one **Go to definition** away in the IDE. Start from `AdUnitConfig` and
`ConsentOptions` (ads), `OnboardKitConfig` and `ObRemoteKeys` (onboardkitorigin), `TrackerConfig`
and `TrackkitEvents` (trackkit), `PayKitConfig` (paykit), `AppPurchase` (billingkit).

## License

MIT — see [LICENSE](LICENSE).
