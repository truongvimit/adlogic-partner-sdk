# Trackkit architecture

Companion to [README.md](README.md). The README tells you **what to call**; this file tells you
**who is allowed to call it**, and why the module graph is shaped the way it is.

Read this before you add an SDK, add a module, or "just import Adjust here for a second".

---

## 1. The module map

```
                ┌────────────────────────────────────────────────────────────┐
                │  :app                                         ASSEMBLER    │
                │  GlobalApp.initTracking()                                  │
                └──┬────────┬───────────┬─────────┬─────────┬─────────────┬──┘
              impl │   impl │      impl │    impl │    impl │       debug │
       ┌───────────┘        │           │         │         │             └──────────┐
       ▼                    ▼           ▼         ▼         ▼                        ▼
┌───────────────────┐  ┌────────┐ ┌─────────────┐ ┌─────────┐ ┌────────────────────┐ ┌───────────┐
│ :onboardkitorigin │  │ :ads   │ │ :billingkit │ │ :paykit │ │   :suite-firebase  │ │ :adtracer │
└─────────┬─────────┘  └───┬────┘ └──────┬──────┘ └────┬────┘ │      ADAPTERS      │ └───────────┘
          │ api        api │         api │         api │      └─────────┬──────────┘
          │                │             │             │                │ api
          └────────────────┴──────┬──────┴─────────────┴────────────────┘
                                  ▼
      ┌──────────────────────────────────────────────────────────┐
      │ :trackkit                                        PORT    │
      │ Tracker · TrackSink · AdImpression · TrackkitEvents      │
      │ PlacementRegistry · mmp.MmpTracking  — zero vendor deps  │
      └──────────────────────────────────────────────────────────┘

      Cross edges the boxes cannot show:
          :onboardkitorigin ──impl────────▶ :ads         (ERainAdProvider bridges internally)
          :paykit           ──impl────────▶ :billingkit  (BillingBridge, the one door)
          :paykit           ──compileOnly─▶ :ads         (one app-resume switch, try/catch'd)
          :paykit           ──compileOnly─▶ :onboardkitorigin (PaywallGate SPI, optional at runtime)
          :billingkit       ──compileOnly─▶ :ads         (entitlement hand-off, try/catch'd)

      FORBIDDEN, and the whole point of the layout:
          :onboardkitorigin ──✗──▶ any vendor adapter (today :suite-firebase)
          :paykit           ──✗──▶ any vendor adapter (today :suite-firebase)
          :ads              ──✗──▶ :billingkit    (an IAA-only APK ships zero billing classes)
          :billingkit       ──✗──▶ :ads at runtime (an IAP-only APK ships zero GMA classes)

      The same shape, one level out: :paykit reads its paywall document through the
      vendor-free PaywallConfigSource, and :suite-firebase is its only adapter (§9).
```

Every edge, with its Gradle configuration:

| From                 | To                                                      | Configuration                     | Why that configuration                                                                                                                                                         |
|----------------------|---------------------------------------------------------|-----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `:ads`               | `:trackkit`                                             | `api`                             | `ERainLogEventManager` returns and accepts Trackkit types (`AdFormat`, `AdImpression`), so they leak into the ads API surface and must be on the consumer's compile classpath. |
| `:onboardkitorigin`  | `:trackkit`                                             | `api`                             | Same reason: `TrackkitPlugin` and `ERainAdProvider` are part of its public wiring.                                                                                             |
| `:onboardkitorigin`  | `:ads`                                                  | `implementation`                  | `ERainAdProvider` bridges to `ERainAd`/`Admob` internally; a partner injecting its own `OnboardingAdProvider` never sees `:ads`.                                               |
| `:paykit`            | `:trackkit`                                             | `api`                             | Same reason again: the paywall reports its funnel through `Tracker`, and a host that routes those events needs `TrackkitEvents.Iap` on its compile classpath.                  |
| `:billingkit`        | `:trackkit`                                             | `api`                             | The billing engine reports `iap_success`/`iap_fail` through `Tracker` and purchase revenue through `io.trackkit.mmp.MmpTracking`; hosts route `TrackkitEvents.Iap` in their own code. |
| `:billingkit`        | `:ads`                                                  | `compileOnly`                     | The entitlement hand-off (`Entitlement.install`) compiles against `:ads` but must never drag GMA into an IAP-only APK. Guarded by try/catch; absent at runtime is a supported state. |
| `:paykit`            | `:billingkit`                                           | `implementation`                  | The billing engine, reached through `io.paykit.billing.BillingBridge` — the one file that imports `com.ads.module`. No `com.ads` type reaches a consumer.                      |
| `:paykit`            | `:ads`                                                  | `compileOnly`                     | One call — `AppOpenManager.disableAppResumeWithActivity` — so a paywall-without-ads host does not inherit the GMA stack. Guarded by try/catch.                                 |
| `:paykit`            | `:onboardkitorigin`                                     | `compileOnly`                     | `OnboardKitPaywallGate` implements OnboardKit's `PaywallGate` SPI, but a host that ships a paywall without onboarding must not inherit four activities it never opens.         |
| `:suite-firebase`   | `:paykit`                                               | `compileOnly`                     | `FirebaseConfigSource` implements `PaywallConfigSource`, but a partner who wants only the analytics sink or the ad-config source must not inherit the paywall UI. The class is unreachable exactly when it is unloadable, so no runtime guard is needed — same idiom as `OnboardKitPaywallGate`. |
| `:suite-firebase`   | `firebase-config`                                       | `api`                             | Same deliberate exception as the Firebase sink: the BOM is exported so the host's other Firebase artifacts stay on one version train.                                          |
| `:suite-firebase` | `:trackkit`                                             | `api`                             | A sink is useless without `TrackSink`; the app must see both.                                                                                                                  |
| `:suite-firebase`   | `:ads`                                                  | `compileOnly`                     | `FirebaseAdConfigSource` implements `AdConfigSource`. Same reasoning as `:paykit` above.                                                                                       |
| `:suite-firebase` | `firebase-analytics`                                    | `api`                             | Deliberate exception: the app almost always uses Firebase directly too, so the BOM is exported to keep one version train.                                                      |
| `:app`               | `:ads`, `:billingkit`, `:onboardkitorigin`, `:paykit*`, `:trackkit*` | `implementation`      | Nothing consumes `:app`.                                                                                                                                                       |
| `:app`               | `:adtracer`                                             | `debugImplementation`             | The dashboard must not exist in a release APK at all — not stripped, not present.                                                                                              |
| `:trackkit`          | —                                                       | `compileOnly androidx.annotation` | The port has no runtime dependency on anything. That is what makes it a port.                                                                                                  |

---

## 2. The four roles

### PORT — `:trackkit`

`:trackkit` is a vocabulary and nothing else: `Tracker`, `TrackSink`, `TrackEvent`,
`TrackkitEvents`, `AdImpression`, `AdFormat`, `Consent`, `PlacementRegistry`, and the MMP seam
`mmp.MmpTracking` (an interface plus a registry — the Adjust relay behind it lives in `:ads`, §4).
It defines what an impression *is* — a placement, a format, an ad unit, a value in a currency —
without knowing that AdMob, Adjust or Firebase exist. Its `build.gradle` has one `compileOnly` annotation dependency and
no vendor at all, and that is checked simply by reading it.

**Forbidden:** naming a vendor. No `com.adjust`, `com.google.firebase` or `com.facebook` import may
ever appear under `trackkit/src/main`. The moment one does, every partner that consumes `:ads`
inherits that SDK, and the module stops being a port. The port is frozen — changes to it are
breaking changes for five modules and every partner build at once.

### REPORTER — `:ads`, `:billingkit`, `:onboardkitorigin`, `:paykit`

A reporter owns real objects with a lifecycle and describes what happens to them. `:ads` owns the
AdMob and AppLovin ad objects: it creates them, loads them, shows them, and therefore it is the only
code in the tree that can see an `AdValue` or a `MaxAd`. Its job in this architecture is to convert
those into an `AdImpression` and hand it to `Tracker.adRevenue()` — see
`ERainLogEventManager.logPaidAdImpression`. `:onboardkitorigin` owns the first-open flow and bridges
its internal `AnalyticsEvent` catalog to the canonical taxonomy through
`io.onboardkit.core.analytics.TrackkitPlugin`, registered automatically by `OnboardingSdk.install`.
`:paykit` owns the paywall presentation and reports the `iap_` funnel from one file,
`io.paykit.analytics.PaywallTracking`.

`:billingkit` owns the Play `BillingClient` and the purchase object, so it is the sole reporter of
`iap_success` — the paywall in `:paykit` reports the view, the click, the failure and the terminal
result, and stops. Two reporters emitting one purchase put the same money on two different clocks,
which is a figure nobody can reconcile a quarter later.

**Forbidden:** knowing where the data goes. A reporter calls `Tracker` and stops. It owns zero
vendor reporting code — no `Adjust.trackEvent`, no `logPurchase`, no `FirebaseAnalytics.logEvent`.
It must never depend on a vendor adapter module — today that is `:suite-firebase` (§5). It must not decide whether an event is
"worth" sending; that is the assembler's call, expressed by which sinks it registers.

### ADAPTER — `:suite-firebase`

An adapter implements `TrackSink` and translates the vendor-free vocabulary into one vendor's API.
Because one class owns a whole vendor surface, vendor-specific rules live in one readable place —
Firebase's `Bundle` conversion, its 25-param / 100-char limits, and Consent Mode.

Firebase is currently the only adapter. That is the point of the shape, not a gap: a second vendor
is a new module plus one line in `:app` (§8), and nothing else in the codebase changes.

**Forbidden:** being depended on by a reporter, initialising its own SDK from a reporter's
lifecycle, or reaching back into `:ads` for context. An adapter receives everything it needs as
arguments.

There is deliberately **no** `:trackkit-adjust`. See §4.

### ASSEMBLER — `:app`

The app is the composition root. It is the only module that knows which vendors this particular
partner ships, and it says so in exactly one function, `GlobalApp.initTracking()`:
`Tracker.install(...)` followed by `Tracker.addSink(FirebaseSink(...))`, plus
`ConsoleSink()` and the debug dashboard sink under `BuildConfig.DEBUG`. Dropping build is deleting
one constructor call and one Gradle line — no reporter changes, no call sites
touched.

**Forbidden:** instrumenting other people's objects. The app does not wrap ad callbacks, does not
decorate `AdCallback`, and does not attach `OnPaidEventListener` — `:ads` created those objects, so
`:ads` instruments them (§6). The app supplies context the SDK cannot know (placements, consent,
tokens) and chooses destinations. That is the whole job.

## 3. Who owns Adjust: init vs reporting

Two different questions, and conflating them is what produced the original mess.

**Initialisation** — the app token, the environment, the install referrer, `Adjust.onResume/onPause`
session tracking, the attribution listener. This lives in `ERainAd.setupAdjust()` inside `:ads`. It
must, because the attribution result feeds straight back into ad policy: the organic/non-organic
verdict is written to prefs and read by `ERainAd` to gate ad display.

**Reporting** — turning a business fact into an Adjust call. This lives in
`com.ads.module.event.ERainAdjust`, which is the single writer: every `Adjust.trackEvent`,
`Adjust.trackAdRevenue`, `Adjust.trackMeasurementConsent` and `Adjust.trackThirdPartySharing` in the
codebase is in that one file. Sibling modules reach it through `MmpTracking`, never directly.

The rule that keeps it honest: **one writer**. If a second place starts calling `Adjust.*`, the
question "is this dollar reported twice?" stops having a cheap answer.

## 4. Why there is no `:trackkit-adjust`

A deliberate decision, taken from measurement rather than taste.

**What was measured.** In this template, **68 of 68** Adjust touch points live in `:ads`;
`:onboardkitorigin` and `:app` contain **zero** `com.adjust.sdk` references. In the Apero production
app, dex-level analysis finds exactly **11** invocations of the `Adjust` facade, confined to **3
classes**, all inside the ads module.

**Why the containment is real.** Adjust is an MMP, not a product-analytics tool. It answers two
questions — which campaign produced this install, and how much money did that user make — so
everything it consumes (ad revenue, purchase revenue, install referrer, session lifecycle,
attribution) originates in code `:ads` already owns. The relationship is also **bidirectional**:
`ERainAd` reads Adjust's organic/non-organic verdict back into prefs and uses it to gate ad display.
Adjust is not a pure sink here; it is an input to ad policy, and a sink cannot express that.

**What separation would cost.** A separate Gradle module forces every partner to add a dependency
and one `addSink(...)` line in `Application`. Forgetting that line loses all ad revenue in Adjust —
silently, with a green dashboard. In the current design that failure is impossible.

**So how does a module outside `:ads` send a conversion event?** Through the `MmpTracking` seam.
The registry lives in the port as `io.trackkit.mmp.MmpTracking` — the "a module ships without
`:ads`" trigger fired when the billing engine moved to `:billingkit` and had to report purchase
revenue with no `:ads` on its classpath. `:ads` still owns the Adjust relay and registers it (from `ERainAd.init` and from the
`com.ads.module.event.MmpTracking` facade, which stays as the published door for modules that
depend on `:ads`):

```java
MmpTracking.trackEvent(config.getAdjustTokenOnboardingComplete());
```

For a second MMP, implement `io.trackkit.mmp.MmpTracking.Relay` and call `addRelay(...)`. No call
site changes. Purchase revenue arrives through the relay's `onPurchaseRevenue`, which is how
`:billingkit` reports money without learning any vendor's name.

**What did not move.** Adjust itself — init, attribution, session tracking, every `Adjust.*` call —
stays in `:ads`, single writer intact. The port gained an interface and a registry, zero vendor
code, so it is still a port.

**When to revisit further.** Triggers (a) and (c) remain: (a) a partner wants a different MMP
instead of Adjust; (c) two or more apps have a revenue path that bypasses this SDK entirely
(RevenueCat, StoreKit). The seam is already in the port, so either is now a relay registration,
not a refactor.

**Firebase and Meta stay in Trackkit**, because those destinations receive signal from all three
modules and carry no reverse dependency.

## 5. The dependency rule

> **A REPORTER may depend on the PORT. A REPORTER may never depend on an ADAPTER.**

`:suite-firebase` — not as `api`, not as `implementation`, not "temporarily".

What breaks if you violate it: Gradle dependencies are transitive, so
**every partner app that consumes `:ads`**, including the ones with no Adjust account. They get a
larger APK, another SDK in their data-safety disclosure and privacy manifest, another SDK's
permissions and network calls merged into their manifest, and another release train to track — for
a vendor they do not use and cannot remove without forking `:ads`. Turning it "off" at runtime does
not help: an unconfigured SDK is still shipped, still declared, and still audited.

The same argument applies in reverse to `:onboardkitorigin` and to `:paykit`, and it is why
`:trackkit` itself is kept ruthlessly free of vendors: it sits underneath all three reporters, so
anything added there is added to everything.

Sanity check, and it should stay empty:

```bash
grep -rn "io.trackkit.firebase" ads/src onboardkitorigin/src paykit/src
```

---

## 6. The instrumentation rule

> **The module that owns an object's lifecycle instruments that object. Host apps never wrap
> callbacks.**

`:ads` creates the `AdView`, `InterstitialAd`, `RewardedAd` and `AppOpenAd` instances, so `:ads`
attaches `setOnPaidEventListener` and `:ads` reports the lifecycle. The app passes a placement name
and a functional callback and gets its callback back unchanged. If tracking a new ad event requires
the app to change a call site, the instrumentation is in the wrong module.

This is not a local invention; it is what every mature SDK in this space does.

| Precedent                  | Shape                                                                                                                                                                                                                                                                                                                                    | Doc                                                                                                                                                                                                          |
|----------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Media3 / ExoPlayer**     | `addAnalyticsListener()` is a *second, additive* observation channel that sits beside `Player.Listener`. Analytics never wraps the functional listener — you register another one, and you get richer context (`EventTime`) than the functional path carries.                                                                            | [Analytics in Media3](https://developer.android.com/media/media3/exoplayer/analytics) · [`AnalyticsListener`](https://developer.android.com/reference/androidx/media3/exoplayer/analytics/AnalyticsListener) |
| **AdMob**                  | `setOnPaidEventListener` is attached by whoever *creates* the ad object; every format has its own. Here `:ads` creates them, so `:ads` attaches it.                                                                                                                                                                                      | [Impression-level ad revenue](https://developers.google.com/admob/android/impression-level-ad-revenue)                                                                                                       |
| **ironSource / LevelPlay** | `addImpressionDataListener` is one register-once observer, declared before SDK init — "declare the listener before initializing the LevelPlay SDK to avoid any loss of information". Per-impression context the SDK cannot know is supplied out-of-band, not by wrapping call sites. This is exactly the `PlacementRegistry` shape (§7). | [Impression-level revenue integration](https://docs.unity.com/en-us/grow/levelplay/sdk/android/impression-level-revenue-integration)                                                                         |
| **OkHttp**                 | `EventListener` is a genuine decorator — but it is installed **once**, on `OkHttpClient.Builder`, at the composition root. Callers keep writing `client.newCall(request)` and never see it. A decorator applied at every call site is not the OkHttp pattern; it is the thing OkHttp avoids.                                             | [OkHttp events](https://github.com/square/okhttp/blob/master/docs/features/events.md)                                                                                                                        |

The pattern this replaced, and what was wrong with it: `AdsManager.kt` nested two decorators around
an anonymous callback at every ad call site — an ads-taxonomy wrapper around a debug-dashboard
wrapper around the app's actual `AdCallback`. Three concerns in one expression, at roughly thirty
sites, each of which had to be edited in lockstep to add a single event. The debug dashboard in
particular is a *destination*, not a decorator: it belongs behind `TrackSink`, registered once in
the debug variant, where it observes everything automatically and costs the call sites nothing.

---

## 7. How placement reaches an impression

`ad_impression` carries a `placement` param — `inter_splash`, `native_language`, `open_resume` —
because revenue you cannot attribute to a screen cannot inform a layout decision.

The ad SDK cannot supply it. AdMob's `OnPaidEventListener` receives an `AdValue` and knows the ad
unit id; it has no concept of "the screen that asked". Ad unit ids are also reused across screens
and rotated per waterfall tier, so the id alone is not a placement. And the paid event arrives long
after the load, on a different callback, so there is no call stack to read it from.

So placement travels out-of-band, through `io.trackkit.PlacementRegistry`:

```kotlin
// Once, at init — while you still know why you are loading this unit.
PlacementRegistry.register(adUnitId = "ca-app-pub-…/1111", placement = "inter_splash")

// Later, inside :ads, when AdMob reports the money:
PlacementRegistry.placementOf(adUnitId)   // "inter_splash", or "unknown"
```

- The registry is a bounded LRU (64 entries), so a waterfall that rotates ids cannot leak.
- Registration is idempotent; blank ids and blank placements are ignored.
- Modules that construct their own ad requests register their own units at request time —
  `ERainAdProvider` in `:onboardkitorigin` calls `PlacementRegistry.register` for every tier before
  it loads, because that is the only place the onboarding screen key is known.
- `placementOf` takes a fallback, used for AppLovin MAX where the SDK does report its own
  placement string.

Register early. An impression whose unit was never registered still reports, with
`placement = "unknown"` — visible in the data rather than silently missing, but useless for
attribution.

---

## 8. Adding a new vendor

The whole layout exists so that this is a three-step change touching two files.

**1. Add the sink.** One class implementing `TrackSink`, in `:suite-firebase` if the vendor is
already Firebase-adjacent, otherwise in a new `:suite-<vendor>` module. Depend on the
port with `api`, on the vendor SDK with `implementation` — the vendor's types must not appear in
your public signature.

```groovy
// suite-<vendor>/build.gradle
dependencies {
    api project(':trackkit')
    implementation 'com.vendor:vendor-sdk:1.2.3'
}
```

```kotlin
class VendorSink(private val config: VendorConfig) : TrackSink {

    override val id: String = "vendor"          // stable and unique; addSink ignores duplicates

    override fun onInstall(context: Context) { Vendor.start(context, config) }

    override fun onEvent(name: String, params: Map<String, Any?>) { Vendor.log(name, params) }

    override fun onConsent(consent: Consent) { Vendor.setEnabled(consent.analyticsGranted) }

    // Implement only if the vendor has a dedicated revenue API. Otherwise skip it: `ad_impression`
    // and the cumulative revenue events already arrive through onEvent.
    override fun onAdRevenue(impression: AdImpression) {
        // impression.value is already in impression.currency — do not convert.
        Vendor.logRevenue(impression.value, impression.currency, impression.placement)
    }
}
```

**2. Register it in `:app`.** Add the Gradle line and one constructor call in
`GlobalApp.initTracking()`.

**3. Touch nothing else.** Not `:ads`, not `:onboardkitorigin`, not `:paykit`, not a single call
site. Every event in the catalog reaches the new sink the moment it is registered, including events
written before the vendor existed.

If step 3 turns out to be impossible — if the new vendor needs something no `TrackSink` callback
carries — that is a signal to extend the port, once, for every sink, rather than to reach into a
reporter. Adding a vendor should never require a reporter to learn a vendor's name.

---

## 9. The same shape, for paywall config

`:paykit` copies this layout one level out, for a different vendor surface: where analytics fan out
to a `TrackSink`, the paywall document fans **in** from a `PaywallConfigSource`.

```
      :paykit                       PORT + REPORTER
      PaywallConfigSource · StaticConfigSource · RawResourceConfigSource
              ▲
              │ api
      :suite-firebase              ADAPTER
      FirebaseConfigSource — the only vendor this repo ships
```

Same rules, and for the same reason:

- `:paykit` names no config vendor. `grep -rn "com.google.firebase" paykit/src` stays empty, so a
  partner who feeds the paywall from its own back end compiles no Firebase at all.
- `:suite-firebase` depends on `:paykit` — `compileOnly`, so it is a compile-time contract and not
  something an ads-only partner inherits — and never the reverse. A source module is depended on by
  the assembler and by nobody else: `PayKit.configSource(FirebaseConfigSource())` is one line in
  `Application.onCreate`, and it is the only line that names the vendor.
- Adding a config vendor is a new module and that one line. Nothing in `:paykit` changes, because
  `fetch(timeoutMs): String?` is the whole contract.

The one asymmetry worth knowing: a `TrackSink` is a destination, so a missing one loses data
silently. A `PaywallConfigSource` is a source, so a missing one is visible — `PayKit.sync()` returns
`false` and the paywall falls back to the cached document and then to the JSON bundled in the AAR.
That fallback chain is why the paywall has no "config not loaded" failure mode to design around.
