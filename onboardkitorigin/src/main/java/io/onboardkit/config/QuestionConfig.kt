package io.onboardkit.config

import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes

data class QuestionOption(
    val id: String,
    val title: CharSequence? = null,
    @StringRes val titleRes: Int = 0,
    @DrawableRes val imageRes: Int = 0,
    val imageUrl: String? = null,
)

enum class SelectionMode { SINGLE, MULTIPLE }

data class QuestionConfig(
    @StringRes val titleRes: Int = 0,
    val title: CharSequence? = null,
    @StringRes val ctaTextRes: Int = 0,
    val options: List<QuestionOption> = emptyList(),
    val selectionMode: SelectionMode = SelectionMode.MULTIPLE,
    val minSelection: Int = 1,
    /** Overridable option item layout — the original SDK hard-coded it. */
    @LayoutRes val optionLayoutRes: Int = 0,
    @LayoutRes val layoutRes: Int = 0,
    /** Each answer tap re-serving the native ad is an impression tactic; off by default. */
    val refreshAdOnSelect: Boolean = false,
)
