package io.onboardkit.config

import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import io.onboardkit.R

data class PrivacyGoalsScreenConfig(
    val enabled: Boolean = false,
    /** Optional legacy override. New partners should override the SDK resource names instead. */
    val privacy: PrivacyScreenConfig = PrivacyScreenConfig(),
    /** Optional legacy override. New partners should override the SDK resource names instead. */
    val goal: GoalsScreenConfig = GoalsScreenConfig(),
)
data class PrivacyScreenConfig(
    @LayoutRes val layoutRes: Int = R.layout.ob_privacy_screen,
    @IdRes val consentViewId: Int = R.id.ob_privacy_consent_checkbox,
    /**
     * Legacy escape hatch for old integrations. New layouts can use any clickable Button and
     * the SDK discovers it automatically, so this does not need to be configured.
     */
    @Deprecated("Use a clickable action view in the partner layout; no ID configuration is needed.")
    @IdRes val continueViewId: Int = 0,
    /** Kept for source compatibility; the SDK default is the shared ad slot ID. */
    @Deprecated("The shared ob_privacy_goals_ad ID is used by convention.")
    @IdRes val adContainerId: Int = R.id.ob_privacy_goals_ad,
)
data class GoalsScreenConfig(
    @LayoutRes val layoutRes: Int = R.layout.ob_goal_screen,
    @LayoutRes val optionLayoutRes: Int = R.layout.ob_goal_option,
    @IdRes val optionsViewId: Int = R.id.ob_goal_options,
    /** Legacy escape hatch; new layouts can use any clickable Button and need no action ID. */
    @Deprecated("Use a clickable action view in the partner layout; no ID configuration is needed.")
    @IdRes val nextViewId: Int = 0,
    /** Kept for source compatibility; the SDK default is the shared ad slot ID. */
    @Deprecated("The shared ob_privacy_goals_ad ID is used by convention.")
    @IdRes val adContainerId: Int = R.id.ob_privacy_goals_ad,
    /** @deprecated item content is partner-owned; the SDK rebinds the first TextView/ImageView on every bind when present. */
    @Deprecated("Item view IDs are no longer part of the partner contract.")
    @IdRes val optionTitleViewId: Int = 0,
    /** @deprecated item content is partner-owned; the SDK rebinds the first TextView/ImageView on every bind when present. */
    @Deprecated("Item view IDs are no longer part of the partner contract.")
    @IdRes val optionImageViewId: Int = 0,
    /** @deprecated selected state is exposed through the item root's isSelected/Checkable state. */
    @Deprecated("Use the item root selected state or a selector drawable.")
    @IdRes val optionSelectedViewId: Int = 0,
    val options: List<QuestionOption> = emptyList(),
    val selectionMode: SelectionMode = SelectionMode.MULTIPLE,
    val minSelection: Int = 1,
)
