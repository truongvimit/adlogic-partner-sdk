package io.onboardkit.ads

import io.onboardkit.core.StepId
import io.trackkit.AdFormat

/**
 * Every ad slot the flow knows about. Preload buffers, enable flags and analytics all key
 * off [key] — the SDK never references vendor ad objects by screen name.
 *
 * [format] lives here rather than at the call sites so a placement can never be reported under
 * one format by analytics and judged as another by the gate.
 */
sealed interface AdPlacement {
    val key: String
    val format: AdFormat

    data object SplashBanner : AdPlacement {
        override val key: String = "splash_banner"
        override val format: AdFormat = AdFormat.BANNER
    }

    data object SplashInterstitial : AdPlacement {
        override val key: String = "splash_inter"
        override val format: AdFormat = AdFormat.INTERSTITIAL
    }

    data object Language1 : AdPlacement {
        override val key: String = "language1"
        override val format: AdFormat = AdFormat.NATIVE
    }

    data object Language2 : AdPlacement {
        override val key: String = "language2"
        override val format: AdFormat = AdFormat.NATIVE
    }

    /**
     * Native inside the "Confirm Language" modal, raised when the user taps the language they
     * have already selected.
     *
     * Its own placement rather than a third language slot: the modal can be raised more than once
     * per screen, so sharing [Language2]'s buffer would have the second raise silently re-bind an
     * ad that was already counted.
     */
    data object LanguageConfirm : AdPlacement {
        override val key: String = "language_confirm"
        override val format: AdFormat = AdFormat.NATIVE
    }

    data class StepNative(val stepId: StepId) : AdPlacement {
        override val key: String = "step_${stepId.value}"
        override val format: AdFormat = AdFormat.NATIVE
    }

    data class StepFullScreen(val stepId: StepId) : AdPlacement {
        override val key: String = "fullscreen_${stepId.value}"
        override val format: AdFormat = AdFormat.NATIVE_FULL_SCREEN
    }

    data object Ob5 : AdPlacement {
        override val key: String = "ob5"
        override val format: AdFormat = AdFormat.NATIVE_FULL_SCREEN
    }

    data object QuestionNative : AdPlacement {
        override val key: String = "question_native"
        override val format: AdFormat = AdFormat.NATIVE
    }

    data object QuestionInterstitial : AdPlacement {
        override val key: String = "question_inter"
        override val format: AdFormat = AdFormat.INTERSTITIAL
    }

    /**
     * The ad shown when the app returns to the foreground.
     *
     * A placement like any other so it goes through the same gate and reports the same block
     * reasons — the audit found app-resume was the one format with its own private rule set.
     */
    data object AppResume : AdPlacement {
        override val key: String = "app_resume"
        override val format: AdFormat = AdFormat.APP_OPEN
    }
}
