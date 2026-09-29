# Privacy → Goal screens

[English](privacy-goals-screen.md) · [Tiếng Việt](privacy-goals-screen.vi.md) · [हिन्दी](privacy-goals-screen.hi.md)

Privacy → Goal is an optional final part of onboarding: Splash → language → onboarding pager → exit interstitial → Privacy → Goal → app. It is available only for an unfinished flow. Enable it in the app asset or remote `onboarding_config`:

```json
{ "privacy_goals_screen": { "enabled": true } }
```

The SDK default is `false`. The host can also enable it in `PrivacyGoalsScreenConfig`; remote and app assets take priority over code. Supply `goal.options`, or the host's `question.options` as fallback. Without either list the SDK logs a warning, skips these screens and their native preloads, and follows the normal onboarding exit. Screen availability does not depend on ad fill or premium status.

```kotlin
privacyGoalsScreen = PrivacyGoalsScreenConfig(
    enabled = true,
    goal = GoalsScreenConfig(
        options = listOf(
            QuestionOption("work", title = "Work", imageRes = R.drawable.partner_work),
            QuestionOption("study", title = "Study", imageRes = R.drawable.partner_study),
        ),
        selectionMode = SelectionMode.MULTIPLE,
        minSelection = 1,
    ),
)
```

These types are in `io.onboardkit.config`. Use your own drawables. `SINGLE` selection is also supported; set `minSelection` to a reachable count (1 for `SINGLE`). Content and resources belong to the app; the remote flag only enables the screens.

## Layout contract

The SDK supplies layouts. To customize them, override these app resource names:

- `res/layout/ob_privacy_screen.xml`
- `res/layout/ob_goal_screen.xml`
- `res/layout/ob_goal_option.xml`

Keep `ob_privacy_consent_checkbox` for the Privacy consent control, `ob_goal_options` as the Goal `RecyclerView`, and `ob_privacy_goals_ad` as a `FrameLayout` on both screens. Use the same ad frame size and position. No per-view config is needed when following these conventions.

The SDK finds a clickable `Button` or `TextView` for the action. If several controls are clickable, mark the intended action with optional ID `ob_privacy_goals_continue`. The option item's root receives clicks and `isSelected`/`Checkable` state. The first `TextView` receives the option title on every bind (including recycled holders); the first `ImageView` receives its image when provided. Keep these views for option data, use `tools:text` for preview labels, and use selectors with `duplicateParentState="true"` for child selection styling.

## Ads and completion

Use these entries in both `ad_config.json` and `ad_config_debug.json`, with production IDs in release and test IDs in debug:

```json
{
  "native_select_high": { "id": "HIGH_NATIVE_UNIT", "isEnable": true },
  "native_select": { "id": "BASE_NATIVE_UNIT", "isEnable": true, "click_action": "reload" },
  "native_select_alt_high": { "id": "ALT_HIGH_NATIVE_UNIT", "isEnable": true },
  "native_select_alt": { "id": "ALT_BASE_NATIVE_UNIT", "isEnable": true, "click_action": "reload" }
}
```

Privacy and Goal have separate SDK placements sharing these configured keys. `native_select` is the initial ad; the first consent acceptance/goal selection starts the ALT replacement. The initial ad stays visible until ALT binds successfully; no-fill keeps it. Both use the fixed 4:3 media-left frame (`ob_layout_native_media_left.xml`), including preload. CTA color/height apply; `positionCTA` does not change this frame. Read `click_action` only from the base key; its default is `reload`.

The base `isEnable` controls its entire waterfall, including `_high`. Turning off either base disables that ad slot, not the screens. Consent, premium, UA and the global ad gate still apply. There are no separate `privacyAd.enabled`/`goalAd.enabled` switches.

Privacy's first native preloads on the actual last pager step, including a fullscreen step. `onboarding.exit_interstitial.next_screen_timing` remains effective: `UNDER_AD` opens Privacy under the exit ad; `AFTER_AD` waits for dismissal. Notification/widget/uninstall entries wait for dismissal in this exit path.

Privacy requires acceptance before Continue. Finishing Goal persists `QuestionAnswer` values, emits `OnboardingEvent.QuestionAnswered`, and completes onboarding directly; this branch does not continue to the ordinary OB5/question/paywall exit. Back from Goal returns to Privacy; Back from Privacy closes the task. Goal choices are selected again when the Goal screen is recreated.

The flow is marked complete only after Goal finishes. Closing the app beforehand leaves it unfinished; the next launch starts again from Splash → language. Disabling the feature skips inflating these layouts and uses the ordinary onboarding exit.
