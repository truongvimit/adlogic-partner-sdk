# Migration to 3.0.0 — auto-shimmer & SDK-owned native styling

Two things moved into `ads` in 3.0.0. First, `NativeAdHelper` now derives the loading skeleton
from the placement's own ad layout — a detached copy is inflated, repainted into grey blocks and
wrapped in a `ShimmerFrameLayout`, generated lazily at the first Loading with nothing else to
show. Second, native styling (block order, CTA height/color) is now SDK-owned via
`NativeAdHelper.setNativeStyle(NativeAdStyle)`, applied to **both** the loaded ad and the auto
skeleton, so the swap never shifts. Shimmer precedence per placement:

1. `setShimmerLayoutView(view)` — your view; the SDK never touches it
2. `setShimmerLayout(R.layout.my_shimmer)` — **new**: an explicit layout resource
3. `NativeAdConfig.autoShimmer` — the auto-derived skeleton; **defaults to `true`**
4. nothing

## Nothing to change per call site — but check one thing

Explicit shimmers still win, so every existing call site keeps its exact behaviour. The one trap:
a screen whose own shimmer view sits inside the ad container **without** being passed via
`setShimmerLayoutView` now shows **two** skeletons — the auto one on top of yours. Either pass
yours in, or opt that placement out:

```kotlin
nativeConfig.autoShimmer = false
```

Also be aware: placements that previously sat empty while loading now show a skeleton. If a screen
must stay empty during load, opt it out the same way.

## What you can delete

Per-placement shimmer plumbing is dead weight now:

- the shimmer XML layouts
- the `<include>` of those layouts in every screen
- the `setShimmerLayoutView(...)` calls

Call sites shrink to `setNativeContentView(container).requestAds(...)`. Two optional helpers:
`NativeAdShimmer.prewarm(context, R.layout.native_ad_layout)` at splash pre-pays the one-time cold
inflate, and tagging a view with `NativeAdShimmer.TAG_SHIMMER_KEEP` (`"shimmer_keep"`) keeps it
out of the grey repaint. Text is made transparent (widths hold), media gets a 160dp floor, and the
"Ad" badge is never repainted.

## What you can adopt: `setNativeStyle`

Map your remote config onto `NativeAdStyle` and hand it to the helper — no style set means the
layout renders exactly as its XML declares, nothing implicit:

```kotlin
helper.setNativeStyle(
    NativeAdStyle(
        components = listOf(NativeComponent.MEDIA, NativeComponent.ICON_HEADLINE, NativeComponent.CTA),
        ctaHeightDp = 44,
        ctaBackgroundColor = Color.parseColor("#1E88E5"),
    )
)
```

Reordering via `components` requires the layout convention: a vertical LinearLayout
`@id/ad_container` holding `@id/block_icon_headline`, `@id/ad_body`, `@id/ad_media`,
`@id/ad_call_to_action`. Layouts without `ad_container` fall back to in-place visibility toggles
(flat icon/headline/advertiser views are handled individually; the "Ad" badge is never hidden).
`NativeComponent.fromKey("cta")` resolves remote-config keys; clamping and color parsing stay
app-side (see `AdUnitConfig.toNativeStyle()` in the example app). Style lands after the AdMob
bind, so exclusions stick. `setAutoShimmerDecorator {}` is the escape hatch to post-process the
generated skeleton beyond what `NativeAdStyle` covers.

## OnboardKit

The four `ob_shimmer_native_*.xml` layouts are gone and onboarding screens no longer `<include>`
any shimmer — native slots derive their skeleton from the resolved template layout.
`OnboardingAdProvider.bindNative` kept its signature; the generated skeleton arrives as its
shimmer argument, so custom providers need no change.

## No API removals

Nothing was removed from `ads`. `ERainAd.populateNativeAdView` still exists; the default binder
now routes through `NativeAdStyler.populate`, which is behaviour-identical when no style is set.
`NativeAdStyler.applyLayout` / `applyAppearance` / `populate` are public if you bind ads yourself.

# Migration: the billing engine moved from `ads` to `billingkit`

The Play Billing engine (`com.ads.module.billing.*` and the billing listeners in
`com.ads.module.funtion.*`) now ships as its own module, `billingkit`. `ads` no longer contains a
single Play Billing class, so an IAA-only partner no longer ships `com.android.billingclient` in
the APK or declares it in data safety.

## If your app sells IAP or subscriptions

Add **one line** to `build.gradle`, next to the modules you already declare:

```groovy
implementation "com.github.truongvimit.adlogic-partner-sdk:billingkit:$sdkVersion"
```

That is the entire migration:

- **No code changes.** Every class kept its fully qualified name — `com.ads.module.billing.AppPurchase`,
  `com.ads.module.billing.Billing`, `PurchaseItem`, `PurchaseVerifier`, the listeners in
  `com.ads.module.funtion.*`. Imports, call sites and ProGuard rules keep working as-is.
- **No behaviour changes.** Ad gating by premium, the cached entitlement, dev mode driven by
  `ERainAdConfig.variantDev`, purchase events and Adjust revenue all work exactly as before when
  `ads` and `billingkit` are both present. `billingkit` plugs itself into `ads` automatically.
- `paykit` already depends on `billingkit`, so a paywall app that upgrades every module to the same
  tag gets it transitively at runtime. Declare it explicitly anyway (as above) if your own code
  calls `AppPurchase` / `Billing` directly — `implementation` scope does not put it on your compile
  classpath through `paykit`.

## If your app runs ads only (no IAP)

Do nothing. Ads keep working, `AdGate.isPurchased` now answers through the `Entitlement` port and
returns `false` with no billing engine installed, and your APK drops the Play Billing library.

## If you build a paywall without ads

Declare `billingkit` (and `paykit` for the prebuilt UI) and skip `ads` entirely — the APK contains
no GMA/AdMob classes. Set dev mode through `BillingKit.setDevMode(boolean)`; it defaults to off.

## Deprecated, still working

- `ERainLogEventManager.onTrackRevenuePurchase(...)` / `onTrackPurchaseFail(...)` — kept and
  delegating; the engine now reports purchases itself through `Tracker` and
  `io.trackkit.mmp.MmpTracking`.
- `com.ads.module.event.MmpTracking` remains the MMP door for code that depends on `ads`; the
  registry underneath it moved to `io.trackkit.mmp.MmpTracking` so `billingkit` can report purchase
  revenue without knowing `ads` exists.
