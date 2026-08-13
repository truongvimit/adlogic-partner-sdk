package io.onboardkit.config

import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import io.onboardkit.core.StepId
import io.onboardkit.core.StepType

/**
 * One entry in the ordered step list. Order in the list IS the display order —
 * remote config can only enable/disable, matching the verified original behavior.
 */
/**
 * A step is enabled or disabled by its [id]: `StepId.OB1` reads `ob_enable_step_ob1`, and so on
 * through OB5. There is no per-step key name to declare — the SDK's remote surface is a fixed,
 * typed set, so a free-text key would be one nobody reads.
 */
sealed interface StepDefinition {
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
) : StepDefinition {
    override val type: StepType get() = StepType.CONTENT
}

data class AdFullScreenStepDefinition(
    override val id: StepId,
    val showSkipButton: Boolean = true,
    val skipButtonDelaySec: Int = 3,
    /** Off by default: full-screen ad advances only on Skip unless enabled. */
    val autoNextEnabled: Boolean = false,
    val autoNextDelayMs: Long = 15_000,
    @LayoutRes val layoutRes: Int = 0,
) : StepDefinition {
    override val type: StepType get() = StepType.AD_FULL_SCREEN
    override val showsProgressIndicator: Boolean get() = false
}
