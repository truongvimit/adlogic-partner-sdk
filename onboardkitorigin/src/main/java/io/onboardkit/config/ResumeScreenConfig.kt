package io.onboardkit.config

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.onboardkit.R

/**
 * The screen native resume opens when the app returns from the background with `app_resume.type`
 * native_resume: an image, a text card with a Start button, and the `native_resume` ad. Start closes
 * it onto the screen the user left. A text given directly outranks its resource. Restyle the layout
 * by overriding `ob_resume_screen` by resource name. The only required views are `ob_resume_ad`
 * (a FrameLayout) and `ob_resume_start` (or one discoverable Button/TextView); image, copy and the
 * ad block are optional. The Start color and reveal delay are the remote settings
 * `native_resume.start_button.color` and `native_resume.start_button.delay_ms`.
 */
data class ResumeScreenConfig(
    @StringRes val titleRes: Int = R.string.ob_resume_title,
    @StringRes val subtitleRes: Int = R.string.ob_resume_subtitle,
    val title: CharSequence? = null,
    val subtitle: CharSequence? = null,
    @DrawableRes val imageRes: Int = R.drawable.ob_img_onboard_placeholder,
)
