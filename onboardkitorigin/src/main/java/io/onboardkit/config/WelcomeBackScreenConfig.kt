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
            GoalOption("resume", titleRes = R.string.ob_welcome_back_option_resume, imageRes = R.drawable.ob_welcome_back_ic_resume),
            GoalOption("discover", titleRes = R.string.ob_welcome_back_option_discover, imageRes = R.drawable.ob_welcome_back_ic_discover),
            GoalOption("favorites", titleRes = R.string.ob_welcome_back_option_favorites, imageRes = R.drawable.ob_welcome_back_ic_favorites),
            GoalOption("settings", titleRes = R.string.ob_welcome_back_option_settings, imageRes = R.drawable.ob_welcome_back_ic_settings),
        )
    }
}
