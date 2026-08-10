package io.onboardkit.ads

import io.onboardkit.core.StepId

/**
 * Every ad slot the flow knows about. Preload buffers, enable flags and analytics all key
 * off [key] — the SDK never references vendor ad objects by screen name.
 */
sealed interface AdPlacement {
    val key: String

    data object SplashBanner : AdPlacement {
        override val key: String = "splash_banner"
    }

    data object SplashInterstitial : AdPlacement {
        override val key: String = "splash_inter"
    }

    data object Language1 : AdPlacement {
        override val key: String = "language1"
    }

    data object Language2 : AdPlacement {
        override val key: String = "language2"
    }

    data class StepNative(val stepId: StepId) : AdPlacement {
        override val key: String = "step_${stepId.value}"
    }

    data class StepFullScreen(val stepId: StepId) : AdPlacement {
        override val key: String = "fullscreen_${stepId.value}"
    }

    data object Ob5 : AdPlacement {
        override val key: String = "ob5"
    }

    data object QuestionNative : AdPlacement {
        override val key: String = "question_native"
    }

    data object QuestionInterstitial : AdPlacement {
        override val key: String = "question_inter"
    }
}
