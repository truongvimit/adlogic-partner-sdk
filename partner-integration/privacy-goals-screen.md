# Privacy → Goal screens

[English](privacy-goals-screen.md) · [Tiếng Việt](privacy-goals-screen.vi.md) · [हिन्दी](privacy-goals-screen.hi.md)

Privacy → Goal is an optional final part of onboarding: Splash → language → onboarding pager → exit interstitial (when enabled; off by default) → Privacy → Goal → app. It is available only for an unfinished flow. Enable it in the app asset or remote `onboarding_config`:

```json
{ "privacy_goals_screen": { "enabled": true } }
```

The SDK default is `false`. The host can also enable it in `PrivacyGoalsScreenConfig`; remote and app assets take priority over code. Supply `goal.options`. Without that list the SDK logs a warning, skips these screens and their native preloads, and follows the normal onboarding exit. Screen availability does not depend on ad fill or premium status.

The rest of this page describes the default `PER_SCREEN` flow; [Under SHARED_POOL](#under-shared_pool) lists what changes in the `SHARED_POOL` flow.

```kotlin
privacyGoalsScreen = PrivacyGoalsScreenConfig(
    enabled = true,
    goal = GoalsScreenConfig(
        options = listOf(
            GoalOption("work", title = "Work", imageRes = R.drawable.partner_work),
            GoalOption("study", title = "Study", imageRes = R.drawable.partner_study),
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

Use these entries in `ad_config.json` with production IDs; `ad_config_debug.json` needs only `native_select` and `native_select_alt`, each with one test floor in `ids`:

```json
{
  "native_select": {
    "ids": [{ "id": "HIGH_NATIVE_UNIT", "isEnable": true }, { "id": "BASE_NATIVE_UNIT", "isEnable": true }],
    "isEnable": true,
    "click_action": "reload"
  },
  "native_select_alt": {
    "ids": [{ "id": "ALT_HIGH_NATIVE_UNIT", "isEnable": true }, { "id": "ALT_BASE_NATIVE_UNIT", "isEnable": true }],
    "isEnable": true,
    "click_action": "reload"
  }
}
```

Privacy and Goal have separate SDK placements sharing these configured keys. `native_select` is the initial ad; the first consent acceptance/goal selection starts the ALT replacement. The initial ad stays visible until ALT binds successfully; no-fill keeps it. Both use the 4:3 media-left frame (`ob_layout_native_media_left.xml`, template `2`), including preload, unless the key's `templateId` picks another. CTA color/height apply; `components` does not change this frame. `click_action` covers every floor in the key's `ids`; its default is `reload`.

Each key's `isEnable` controls its entire `ids` waterfall; a floor's own `isEnable` pauses only that floor. Turning off either key disables that ad slot, not the screens. Consent, premium, UA and the global ad gate still apply. There are no separate `privacyAd.enabled`/`goalAd.enabled` switches.

Privacy's first native preloads on the actual last pager step, including a fullscreen step. When the exit interstitial is enabled, `onboarding.exit_interstitial.next_screen_timing` remains effective: `UNDER_AD` opens Privacy under the exit ad; `AFTER_AD` waits for dismissal. Notification/widget/uninstall entries wait for dismissal in this exit path.

Privacy requires acceptance before Continue. Finishing Goal records the picks as `GoalAnswer` values (emitted as `OnboardingEvent.GoalsSelected`, returned in `OnboardingOutcome.Completed.goals` and later by `OnboardingSdk.selectedGoals()`) and completes onboarding directly; this branch does not continue to the ordinary OB5/paywall exit. Back from Goal returns to Privacy; Back from Privacy closes the task. Goal choices are selected again when the Goal screen is recreated.

The flow is marked complete only after Goal finishes. Closing the app beforehand leaves it unfinished; the next launch starts again from Splash → language. Disabling the feature skips inflating these layouts and uses the ordinary onboarding exit.

## Under SHARED_POOL

With [`native_flow.type`](remote-settings.md#native-flow-type) set to `SHARED_POOL`, Privacy and Goal are the last stages of the pooled onboarding screen ([SHARED_POOL native flow](ads-onboarding-integration.md#shared_pool-native-flow)). `privacy_goals_screen.enabled` (off by default), `goal.options`, the layout contract above and the `native_select` / `native_select_alt` keys stay the same. Both screens follow one select-screen pattern:

| | Privacy | Goal |
| --- | --- | --- |
| On open | Requests `native_select` as a POLITE load (skipped while another pool request runs) and becomes the pool's owner | Same |
| Selection | Every consent change, checking or unchecking | Every option tap |
| On every selection | Requests `native_select_alt` as a FORCED load (not skipped while another pool request runs; once `native_select_alt` holds an ad or is loading, later selections request nothing) and makes it the owner | Same |
| Forward button | Continue stays hidden until `native_flow.pool.select.next_reveal_delay_ms` (3000 ms) after the first consent change, then works only while consent is checked | Next stays hidden until the same delay after the first tap, then works while at least `minSelection` options are picked |
| Forward action | Preloads Goal's first ad as a POLITE load and opens Goal | Records the goals as above, shows the splash interstitial's leftover when `native_flow.pool.goals.exit_reuse_splash_inter` is `true`, then completes onboarding without a paywall |
| Back | Does nothing | Does nothing |

Each time the screen comes back to the front, it takes the pool's ownership back, refreshes the ad it owns while that ad keeps showing, and repeats a POLITE request for the last placement it asked for. Until its own ad fills, the slot shows the pool's pick: the newest ad no screen has shown yet first, otherwise the one shown most recently, and a shimmer while a request runs. A native click neither reloads nor navigates (`click_action` is ignored). Return ads may open over both screens (`native_flow.pool.resume_on_goals`, default `true`). No exit interstitial runs before Privacy, and Privacy's first ad is not preloaded on the last pager page: Privacy requests it when it opens. Privacy and Goal request separately but share the `native_select` / `native_select_alt` IDs, so revenue for those IDs is reported under whichever of them requested last.
