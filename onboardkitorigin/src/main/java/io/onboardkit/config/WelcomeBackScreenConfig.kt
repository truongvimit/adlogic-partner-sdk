package io.onboardkit.config

import androidx.annotation.LayoutRes
import io.onboardkit.R

/**
 * The screen a returning user lands on after a launcher-tap splash (the `inter_splash_o` launch).
 * Entry launches (notification, widget, uninstall) never show it. `welcome_back.enabled` in the
 * onboarding settings opts in to this screen; it is hidden by default.
 *
 * One answer is picked, then Continue ends the flow with it as the run's answer. Partners restyle it
 * by overriding [layoutRes] / [optionLayoutRes] by resource name, keeping the ids the default
 * layout documents.
 */
data class WelcomeBackScreenConfig(
    val options: List<GoalOption> = DEFAULT_OPTIONS,
    @LayoutRes val layoutRes: Int = R.layout.ob_welcome_back_screen,
    @LayoutRes val optionLayoutRes: Int = R.layout.ob_welcome_back_option,
) {
    companion object {
        val DEFAULT_OPTIONS: List<GoalOption> = listOf(
            GoalOption("edit", titleRes = R.string.ob_welcome_back_option_edit, imageRes = R.drawable.ob_welcome_back_ic_edit),
            GoalOption("add_text", titleRes = R.string.ob_welcome_back_option_add_text, imageRes = R.drawable.ob_welcome_back_ic_add_text),
            GoalOption("sign", titleRes = R.string.ob_welcome_back_option_sign, imageRes = R.drawable.ob_welcome_back_ic_sign),
            GoalOption("split_merge", titleRes = R.string.ob_welcome_back_option_split_merge, imageRes = R.drawable.ob_welcome_back_ic_split_merge),
        )
    }
}
