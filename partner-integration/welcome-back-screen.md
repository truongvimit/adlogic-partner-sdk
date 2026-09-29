# Welcome Back screen

[English](welcome-back-screen.md) · [Tiếng Việt](welcome-back-screen.vi.md) · [हिन्दी](welcome-back-screen.hi.md)

Welcome Back is the screen a returning user sees after the splash every time they open the app from the launcher: Splash (`inter_splash_o`) → Welcome Back → app. The user picks one of four goals, then Continue hands over to the app. It never opens for a first-open user or for a notification, widget or uninstall entry; those keep their current route.

It is on by default. Turn it off in the app asset or remote `onboarding_config`:

```json
{ "welcome_back": { "enabled": false } }
```

Off, a returning launcher launch goes straight to the app, opened underneath the splash ad, with `OnboardingOutcome.Skipped(ALREADY_COMPLETED)`.

The SDK ships four PDF goals (Edit PDF, Add text, Sign & fill, Split & merge). Replace them with your own:

```kotlin
welcomeBackScreen = WelcomeBackScreenConfig(
    options = listOf(
        GoalOption("scan", titleRes = R.string.goal_scan, imageRes = R.drawable.ic_goal_scan),
        GoalOption("read", titleRes = R.string.goal_read, imageRes = R.drawable.ic_goal_read),
    ),
)
```

An empty list turns the screen off. Selection is always single.

## Layout contract

To restyle, override these resource names in the app:

- `res/layout/ob_welcome_back_screen.xml`
- `res/layout/ob_welcome_back_option.xml`

Keep `ob_welcome_back_options` as the `RecyclerView`, `ob_welcome_back_continue` as the action and `ob_welcome_back_ad` as a `FrameLayout` inside its own parent block. The option root receives clicks and `isSelected`; the first `TextView` gets the title and the first `ImageView` the image.

## Ads and completion

Declare these in both `ad_config.json` (production IDs) and `ad_config_debug.json` (test IDs), in the same shape as the other native placements:

```json
{
  "native_welcome1_high": { "id": "HIGH_NATIVE_UNIT", "isEnable": true },
  "native_welcome1": { "id": "BASE_NATIVE_UNIT", "isEnable": true, "click_action": "reload" },
  "native_welcome2_high": { "id": "ALT_HIGH_NATIVE_UNIT", "isEnable": true },
  "native_welcome2": { "id": "ALT_BASE_NATIVE_UNIT", "isEnable": true, "click_action": "reload" }
}
```

`native_welcome1` is preloaded by the splash at the same moment and in the same mode as LFO1 (`splash.load.lfo1_preload_mode`). `native_welcome2` is preloaded when Welcome Back opens and replaces the first ad on the first tap; the first ad stays if the second has no fill. Both render with the LFO native template (`lfo.native_template`, `positionCTA` of the key). The base `isEnable` switches off its whole waterfall; the screen still shows.

With the default timing the screen opens after the splash interstitial is dismissed. Continue records the pick as a `GoalAnswer` (emitted as `OnboardingEvent.GoalsSelected`, readable later through `OnboardingSdk.selectedGoals()`) and delivers `OnboardingOutcome.Skipped(ALREADY_COMPLETED)` with the launch passthrough. Nothing is marked complete and no `FlowCompleted` / `fo_flow_complete` fires, so the screen shows again on the next launcher launch. Back closes the app.
