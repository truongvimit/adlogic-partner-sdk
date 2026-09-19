package io.onboardkit.config

import io.onboardkit.remote.OnboardingSettings
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import io.onboardkit.core.StepId
import io.onboardkit.core.StepType

/** App declares the available steps; onboarding.order may select/reorder this catalog. */
sealed interface StepDefinition {
    val enabled: Boolean
    val id: StepId
    val type: StepType
    val showsProgressIndicator: Boolean
}

data class ContentStepDefinition(
    override val id: StepId,
    @StringRes val titleRes: Int = 0,
    @StringRes val subtitleRes: Int = 0,
    val title: CharSequence? = null,
    val subtitle: CharSequence? = null,
    @DrawableRes val imageRes: Int = 0,
    /** App-supplied layout honoring the ID contract; 0 = SDK default layout. */
    @LayoutRes val layoutRes: Int = 0,
    override val showsProgressIndicator: Boolean = true,
    override val enabled: Boolean = true,
) : StepDefinition {
    override val type: StepType get() = StepType.CONTENT
}

enum class FullScreenSkipStyle { TEXT, CLOSE_ICON }

/** Side the Skip/X takes. The two sides are exact mirrors: same inset, same top margin. */
enum class FullScreenSkipPosition { RIGHT, LEFT }

data class AdFullScreenStepDefinition(
    override val id: StepId,
    val showSkipButton: Boolean = OnboardingSettings.defaultBool("onboarding.fullscreen.skip.enabled"),
    val skipButtonDelaySec: Int = (OnboardingSettings.defaultNumber("onboarding.fullscreen.skip.delay_ms") / 1000).toInt(),
    /** Counts from page selection, including time spent in the background. */
    val autoNextEnabled: Boolean = OnboardingSettings.defaultBool("onboarding.fullscreen.auto_next.enabled"),
    val autoNextDelayMs: Long = OnboardingSettings.defaultNumber("onboarding.fullscreen.auto_next.delay_ms"),
    @LayoutRes val layoutRes: Int = 0,
    /** Null inherits AdsConfig.fullScreenSkipStyle. */
    val skipButtonStyle: FullScreenSkipStyle? = null,
    /** Owned by this page alone; there is no shared position scope to inherit. */
    val skipButtonPosition: FullScreenSkipPosition = FullScreenSkipPosition.RIGHT,
    override val enabled: Boolean = true,
) : StepDefinition {
    override val type: StepType get() = StepType.AD_FULL_SCREEN
    override val showsProgressIndicator: Boolean get() = false
}
