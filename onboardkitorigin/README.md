# OnboardKit

> Tiếng Việt: **[README.vi.md](README.vi.md)** · हिन्दी: **[README.hi.md](README.hi.md)**

The first-open flow as a library: splash → language → onboarding steps → optional full-screen ad →
optional question → your app. Ads, remote config, state persistence, the analytics funnel and every
"user must not get stuck here" guarantee are inside. You supply ad unit ids, copy, and where to go
when it finishes.

- Namespace `io.onboardkit` · resource prefix `ob_` · entry point `OnboardingSdk`
- Ads go through the `OnboardingAdProvider` interface. `ERainAdProvider` bridges to `:ads`
  (ERainAd/AdMob); pass your own implementation, or `null` for an ad-free flow.
- Analytics go through `Tracker` from `:trackkit`. Wire one sink and the whole funnel reports itself.

**Read [`../trackkit/README.md`](../trackkit/README.md) too.** Without `Tracker.install()` plus a
sink, every event this SDK emits is validated and then discarded.

---

## 1. Gradle setup

```groovy
// Replace <tag> with a tag from https://github.com/truongvimit/adlogic-partner-sdk/tags
def sdkVersion = '<tag>'

dependencies {
    implementation "com.github.truongvimit.adlogic-partner-sdk:onboardkitorigin:$sdkVersion"
    implementation "com.github.truongvimit.adlogic-partner-sdk:ads:$sdkVersion"          // for ERainAdProvider
    implementation "com.github.truongvimit.adlogic-partner-sdk:trackkit-firebase:$sdkVersion"
}
```

Keep every module on the same tag — they are published together and are not tested across versions.

`onboardkitorigin` depends on `ads` at runtime scope, so `com.ads.module.*` is not on your compile
classpath through it — declare `ads` explicitly if you construct `ERainAdProvider` or touch ad APIs.

Requires JDK 17, `minSdk` 24. The four flow activities are declared in the library manifest and
merge automatically; you do **not** add them to yours.

---

## 2. Integration in four steps

### 2.1 `Application.onCreate()` — install

Order matters. `Tracker.install()` comes first: events emitted before it are buffered, not lost, but
they are attributed to whichever session is current when install finally runs.

```kotlin
override fun onCreate() {
    super.onCreate()

    initTracking()        // Tracker.install + addSink — see trackkit/README.md
    initAds()             // ERainAd.getInstance().init(this, config)
    initOnboardKit()      // below
}

private fun initOnboardKit() {
    OnboardingSdk.install(this) {
        adProvider = ERainAdProvider()        // or null for no ads
        paywallGate = MyPaywallGate()         // optional, see §6
        listener = OnboardingListener { ctx, outcome ->
            if (outcome is OnboardingOutcome.Completed) {
                outcome.selectedLanguage?.let { AppPrefs(ctx).languageCode = it }
            }
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    OnboardingSdk.configure(buildConfig()).onFailure { Log.e(TAG, "OnboardKit config rejected", it) }
}
```

`install()` is synchronous and cheap. `configure()` returns a `Result` — **check it.** A rejected
config is not applied, and the flow then reports itself as skipped with no other symptom.

### 2.2 The config

```kotlin
private fun buildConfig() = onboardKitConfig {
    splash = SplashConfig(
        logoRes = R.drawable.ic_logo,
        minDisplayTimeMs = 3_000,
    )
    language = LanguageConfig(defaultCode = "en")

    defaultSteps()                            // OB1, OB2, OB3 (full-screen ad), OB4

    question = QuestionConfig(
        options = listOf(
            QuestionOption("romance", "Romance", imageRes = R.drawable.opt_romance),
            QuestionOption("scifi", "Sci-Fi", imageRes = R.drawable.opt_scifi),
        ),
    )

    ads = AdsConfig(
        splashInterstitial   = InterstitialAdUnit("ca-app-pub-…/1111"),
        splashBanner         = BannerAdUnit("ca-app-pub-…/2222"),
        languageNative       = NativeAdUnit.waterfall(highFloor = "…/3333", allPrice = "…/4444"),
        languageDupNative    = NativeAdUnit("ca-app-pub-…/5555"),
        contentStepNative    = NativeAdUnit("ca-app-pub-…/6666"),
        fullScreenStepNative = NativeAdUnit("ca-app-pub-…/7777"),
        ob5Native            = NativeAdUnit("ca-app-pub-…/8888"),
        questionNative       = NativeAdUnit("ca-app-pub-…/9999"),
        questionInterstitial = InterstitialAdUnit("ca-app-pub-…/0000"),
    )
}.getOrThrow()
```

`defaultSteps()` is the OB1–OB4 template. To choose your own:

```kotlin
steps(
    ContentStepDefinition(StepId.OB1, titleRes = R.string.ob1_title, imageRes = R.drawable.ob1),
    AdFullScreenStepDefinition(StepId.OB3, showSkipButton = true, skipButtonDelaySec = 3),
    ContentStepDefinition(StepId.OB4, layoutRes = R.layout.my_ob4),   // your own layout, see §4
)
```

Step **order is fixed in code**; remote config can only toggle a step off, never reorder. That is
deliberate — a reorderable remote list is how the audited SDK ended up firing the wrong step's
completion event when a step was disabled. A step is toggled by its `StepId`: `StepId.OB1` reads
`ob_enable_step_ob1`, and so on through OB5.

#### Per-page ad units, and the numbering trap

`contentStepNative` is the shared pool for content pages. To sell pages separately, give a page its
own entry — anything not listed falls back to the shared pool, so declaring one page does not force
you to declare them all:

```kotlin
ads = AdsConfig(
    contentStepNative = NativeAdUnit("…/shared"),      // used by pages with no entry below
    stepNatives = mapOf(
        StepId.OB1 to NativeAdUnit.waterfall(highFloor = "…/1111", allPrice = "…/2222"),
        StepId.OB2 to NativeAdUnit("…/3333"),
    ),
)
```

Watch the numbering. `StepId` counts **positions in the flow**, and the ad-only page occupies one of
them — so in the default OB1, OB2, **OB3 = full-screen ad**, OB4 layout, the third *content* page is
`StepId.OB4`. If your remote keys count content pages (`native_ob1..3`) they will not line up, and
`StepId.OB3 to native("native_fs")` reads like a typo. Name the roles once, where the flow is
declared, and the rest of the file stops lying:

```kotlin
private object Page {
    val CONTENT_1      = StepId.OB1
    val CONTENT_2      = StepId.OB2
    val AD_FULL_SCREEN = StepId.OB3
    val CONTENT_3      = StepId.OB4
}
```

Every unit above is a **waterfall**: the list is ordered highest floor first, and the provider walks
it one id at a time, 30 s per floor, stopping at the first fill. `NativeAdUnit("id")` is simply a
one-floor waterfall.

#### Where ads are requested

The preload chain runs one screen ahead, and the ad-only page runs two: it has no content of its
own, so arriving there without a filled ad leaves the user on a spinner.

| While the user is on | The SDK requests |
|---|---|
| Splash (after remote) | language native, first step native, splash banner + interstitial |
| Language | second language native, first step native |
| Step *n* | step *n+1*, plus the next ad-only step wherever it is |
| Last step | OB5 and question |

### 2.3 Splash — subclass, do not copy

Your launcher activity extends `ObSplashActivity`. The sequence — consent, remote fetch, ad
requests, billing, minimum display — is already inside; you fill in the hooks you need.

```kotlin
class SplashActivity : ObSplashActivity() {

    /** Return whether ads may now be requested. Show UMP here. */
    override suspend fun onConsentRequired(): Boolean {
        // Call Tracker.setConsent(analytics, ads) exactly once from your consent callback.
        return userGrantedConsent
    }

    override suspend fun onInitBilling() { /* AppPurchase init */ }

    override fun onRemoteFetched() { /* your own remote keys are ready */ }
}
```

`onConsentRequired` is the gate for **every** ad in the flow, not just the splash one. Returning
`false` — or not returning inside `consentTimeoutMs` — runs the whole onboarding without ads rather
than requesting them unanswered; each placement reports `consent_not_granted` so the skip is
visible instead of silent. The remote fetch overlaps consent (it requests no ads); ad loads do not.

Declare it as the launcher in your manifest as usual. Do not call `OnboardingSdk.start()` yourself —
`ObSplashActivity` does it once its pipeline resolves.

### 2.4 Re-entry from Settings

```kotlin
OnboardingSdk.openLanguagePicker(activity, LanguageScreenMode.SETTINGS)
```

`SETTINGS` mode shows no ads, has a real back button, and is excluded from the first-open funnel so
it cannot inflate your LFO conversion.

---

## 3. What you get automatically

Nothing below needs a call site of yours. It requires only that a `TrackSink` is registered.

| Stage | Events |
|---|---|
| Flow entered | `fo_flow_start` — the denominator, emitted even when the flow decides to skip |
| Splash | `fo_splash_view`, `fo_splash_complete` (`dwell_ms`) |
| Language | `fo_language_view`, `fo_language_select`, `fo_language_complete` (all with `screen_index`), `fo_language_flow_complete` |
| Steps | `fo_step_view`, `fo_step_complete` — with `step`, `index`, `dwell_ms`, `exit_reason` (`cta` / `skip` / `auto_next` / `ad_failed` / `auto_dismiss`) |
| Question | `fo_question_view`, `fo_question_answer`, `fo_question_complete` |
| Flow finished | `fo_flow_complete` (`steps_shown`, `dwell_ms`) |
| Ad slots | `ad_request`, `ad_show`, `ad_load_failed`, `ad_skipped` (`reason` = `policy` / `no_ad_unit` / `not_ready`) |
| Paywall | `iap_paywall_view`, `iap_paywall_result` (`status`) |
| Screens | `screen_view` per flow screen |

The step id — never the pager index — is the event identity, so disabling a step cannot shift another
step onto the wrong name.

To also receive them in your own code, add a plugin:

```kotlin
OnboardingSdk.install(this) {
    analyticsPlugin { event -> myOwnLogger.log(event.name, event.params) }
}
```

You can also observe the flow as a `Flow<OnboardingEvent>` via `OnboardingSdk.events`, or read state
with `isCompleted()`, `selectedLanguage()`, `answers()`.

---

## 4. Supplying your own layouts — the id contract

Pass `layoutRes` on a step, `LanguageConfig.layoutRes`, or `SplashConfig.layoutRes`. The SDK binds by
id, so these ids must exist or the slot is skipped:

| Id | Type | Where |
|---|---|---|
| `ob_native_container` | `FrameLayout` | any screen with a native |
| `ob_native_shimmer` | shimmer include | next to the container |
| `ob_ad_block` | `ViewGroup` | wrapper hidden when the slot is declined |
| `ob_primary_cta` | `ObPrimaryButton` | content steps |
| `ob_step_indicator` | `ObStepIndicator` | content steps |
| `ob_skip_button` | `View` | full-screen ad screens |
| `ob_ad_block_2`, `ob_native_container_2`, `ob_native_shimmer_2` | as above | **language screen only** — the second native slot |
| `ob_splash_logo`, `ob_splash_app_name`, `ob_splash_progress`, `ob_splash_ad_container` | | splash |

Native templates use the standard AdMob ids (`ad_headline`, `ad_media`, `ad_call_to_action`, …) so
`Admob.populateUnifiedNativeAdView` can bind them.

---

## 4.1 Showing an ad from a screen of your own

You do not need this for the built-in flow — the SDK's screens already load and show their own ads.
It is for a screen you add inside the flow. There are two entry points and nothing else:

```kotlin
// Full-screen ad. Two moments, because they are not the same moment.
showInterstitial(
    AdPlacement.SplashInterstitial,
    onNext = { startNextScreen() },   // the ad is up: start the destination UNDER it
    onFinished = { finish() },        // the ad is gone: only now finish this screen
)

// Native slot
showNativeAd(
    placement = AdPlacement.QuestionNative,
    unit = config.ads.questionNative,
    container = binding.nativeContainer,
    shimmer = binding.nativeShimmer.root,
    onUnavailable = { binding.adBlock.isVisible = false },
)
```

Starting the destination at `onNext` gives it the whole display time of the ad to inflate and bind,
so it is already painted when the ad closes. Both callbacks run **at most once**, `onNext` always
before `onFinished`, on every path — including the ones where no ad appears at all. Your screen
needs no guard of its own.

If the next move is only decided after the ad — a paywall branch, say — leave `onNext` out and do
the work in `onFinished`. The cost is one visible stall; the benefit is not starting a destination
you might not want.

Never call `finish()` from `onNext`: the Activity handed to `show()` must outlive the ad, and
finishing it there kills the impression you just paid to load.

To ask before acting, `OnboardingSdk.guard().skipReason(context, placement)` returns `null` when the
ad may show, or the precise reason it may not.

---

## 5. Remote config keys

All prefixed `ob_` so they cannot collide with your app's own namespace. Defaults live in code
(`ObRemoteKeys`) — there is no defaults XML to keep in sync.

**Kill switches** `ob_enable_all_ads`, `ob_enable_ui_content`
**Steps** `ob_enable_step_ob1`…`ob5`, `ob_enable_question`, `ob_enable_question_old_user`
**Language** `ob_enable_language_native_2`, `ob_pass_lfo_if_completed`, `ob_language_supported_codes` (CSV)
**Ads on/off** `ob_reuse_splash_inter`, `ob_ads_splash_banner_enabled`, `ob_ads_splash_inter_enabled`, `ob_ads_{language,content,fullscreen,question}_native_enabled`, `ob_ads_question_inter_enabled`, `ob_ads_app_resume_enabled`
**Ad unit override** `ob_ads_splash_inter_id`, `ob_ads_splash_inter_id_old_user` (blank = use the compiled ids)
**Frequency** `ob_ads_interstitial_interval_sec`, `ob_ads_click_cap_per_day` — both default `0`, meaning off
**Timing** `ob_splash_min_display_ms` (3000), `ob_splash_ad_budget_ms` (60000), `ob_splash_banner_wait_ms` (0), `ob_skip_button_delay_sec`, `ob_fullscreen_auto_dismiss_sec`
**Skip buttons** `ob_show_skip_ob3`, `ob_show_skip_ob5`
**Templates** `ob_native_template_{content,language,question}` = `cta_top` | `cta_bottom` | `compact`
**Server-driven UI** `ob_ui_content`, `ob_ui_design_tokens`, `ob_question_config`
**Cache stamp** `ob_config_version` — change the value to clear the local UI cache

`ob_enable_step_ob5` defaults to **false**: OB5 is a standalone full-screen ad screen, off unless you
ask for it.

The two frequency keys default to `0` on purpose. The `:ads` module already has its own click cap,
and a second cap quietly subtracting impressions is not something anyone finds in a quarter. Turn
one on and the block is reported as `interval_not_elapsed` / `click_cap`, never as silence.

> Every reason an ad did not show, the order the rules run in, and how to read the `OB_FLOW` logcat
> trace: **[ADS_GATING.md](ADS_GATING.md)**.

---

## 6. Paywall (optional)

```kotlin
class MyPaywallGate : PaywallGate {
    override suspend fun shouldShow(placement: PaywallPlacement) =
        placement == PaywallPlacement.AFTER_ONBOARDING && !AppPurchase.getInstance().isPurchased
    override suspend fun present(activity: Activity, placement: PaywallPlacement): PaywallOutcome {
        return PaywallOutcome.Dismissed
    }
}
```

Placements: `SPLASH_INTER`, `AFTER_ONBOARDING`, `AFTER_QUESTION_OLD_USER`. Every presentation
reports `iap_paywall_view` + `iap_paywall_result`; the purchase itself is reported by the billing
layer as `iap_success`, so revenue is never counted twice.

---

## 7. Integration checklist

- [ ] `Tracker.install()` **and** at least one `Tracker.addSink(...)` — otherwise no event arrives
- [ ] `Tracker.setConsent(analytics, ads)` called exactly once, from the UMP callback
- [ ] `OnboardingSdk.install()` before `configure()`, both in `Application.onCreate()`
- [ ] `configure()` result checked, not discarded
- [ ] Launcher activity extends `ObSplashActivity`
- [ ] An ad unit id for every placement you enabled — a blank id reports `ad_skipped/no_ad_unit`
- [ ] Remote keys published with the defaults above, or omitted entirely (code defaults apply)
- [ ] `OnboardingListener` navigates somewhere on **both** `Completed` and `Skipped`
- [ ] Custom layouts carry the ids in §4
- [ ] Verify in a debug build: `ConsoleSink` prints every event that leaves the SDK

---

## 8. Deliberate differences from the SDK this replaces

- Checkpoint `lastCompletedStep`: killing the app mid-flow resumes at the right step instead of
  restarting from the language screen.
- LFO2 (a second native impression on the same screen) and refresh-ad-on-answer-tap are **off by
  default** — enable via config plus remote.
- Premium hides ads on every screen including OB5, and can drop ad-only steps entirely.
- A full-screen ad screen always has an exit: Skip is forced visible when auto-next is off, plus a
  remote-configured auto-dismiss.
- Question answers are persisted to DataStore and reported to analytics — the original stored and
  logged neither.
- A malformed remote step or answer drops that one element instead of failing the whole screen.
- `fo_flow_complete` fires on **every** exit path. In the audited SDK the equivalent event was
  bypassed on two of three exits, so most users never produced one.
