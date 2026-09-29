package io.onboardkit.config

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** One goal card on the Goal and Welcome Back screens. */
data class GoalOption(
    val id: String,
    val title: CharSequence? = null,
    @StringRes val titleRes: Int = 0,
    @DrawableRes val imageRes: Int = 0,
    val imageUrl: String? = null,
)

/** [title], else [titleRes], else [id]: what the card shows and what the answer records. */
internal fun GoalOption.label(context: Context): String =
    title?.toString() ?: titleRes.takeIf { it != 0 }?.let(context::getString) ?: id

enum class SelectionMode { SINGLE, MULTIPLE }
