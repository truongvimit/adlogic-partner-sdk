package io.onboardkit.ads

import io.onboardkit.core.StepId
import io.trackkit.AdFormat

internal val AdPlacement.isPrivacyGoalsNative: Boolean
    get() = this is AdPlacement.StepNative && stepId in setOf(
        StepId.PARTNER_PRIVACY, StepId.PARTNER_PRIVACY_ALT,
        StepId.PARTNER_GOAL, StepId.PARTNER_GOAL_ALT,
    )

/** The placement whose ad units and ad_config key a carousel slot 2 shares; itself otherwise. */
internal val AdPlacement.carouselBase: AdPlacement
    get() = when (this) {
        AdPlacement.Language1Carousel -> AdPlacement.Language1
        AdPlacement.Language2Carousel -> AdPlacement.Language2
        else -> this
    }

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

    /**
     * The native alternative to [SplashBanner] in the splash screen's bottom slot.
     * `splash.ads.slot_format` picks which of the two fills that slot; one launch never requests
     * both.
     *
     * Not [SplashNative], which is the standalone full-screen native shown *after* the splash
     * interstitial. Separate placements because they differ in format, buffer and skip rules —
     * one identity would report a bottom-slot ad as a full-screen impression.
     */
    data object SplashInlineNative : AdPlacement {
        override val key: String = "splash_native"
        override val format: AdFormat = AdFormat.NATIVE
    }

    data object SplashInterstitial : AdPlacement {
        override val key: String = "splash_inter"
        override val format: AdFormat = AdFormat.INTERSTITIAL
    }

    /** Separate from the fullscreen pages inside onboarding (native_full1/native_full2). */
    data object SplashNative : AdPlacement {
        override val key: String = "native_fs"
        override val format: AdFormat = AdFormat.NATIVE_FULL_SCREEN
    }

    data object AfterOnboardingInterstitial : AdPlacement {
        override val key: String = "inter_after_ob3"
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
     * Slot 2 of the LFO1 carousel: [Language1] in everything but its pool buffer. It reads
     * `native_lang`, and analytics and paid events name it `language1`; only [key] differs, so the
     * two slots of one carousel can each hold an ad.
     */
    data object Language1Carousel : AdPlacement {
        override val key: String = "language1_carousel"
        override val format: AdFormat = AdFormat.NATIVE
    }

    /** Slot 2 of the LFO2 carousel: [Language2] in everything but its pool buffer, like [Language1Carousel]. */
    data object Language2Carousel : AdPlacement {
        override val key: String = "language2_carousel"
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

    /** Native on the returning-user Welcome Back screen, preloaded by the splash like [Language1]. */
    data object WelcomeBack1 : AdPlacement {
        override val key: String = "welcome_back1"
        override val format: AdFormat = AdFormat.NATIVE
    }

    /** Preloaded when Welcome Back opens; swapped in on the first option tap. */
    data object WelcomeBack2 : AdPlacement {
        override val key: String = "welcome_back2"
        override val format: AdFormat = AdFormat.NATIVE
    }

    /** Native on the screen a return from the background opens when `app_resume.type` is native_resume. */
    data object ResumeNative : AdPlacement {
        override val key: String = "native_resume"
        override val format: AdFormat = AdFormat.NATIVE
    }

    /**
     * Interstitial behind the Start of either native resume screen. The screen closes once it is
     * gone, or at once when it never shows.
     */
    data object ResumeInterstitial : AdPlacement {
        override val key: String = "inter_resume"
        override val format: AdFormat = AdFormat.INTERSTITIAL
    }

    /**
     * The ad shown when the app returns to the foreground.
     *
     * A placement like any other so it goes through the same gate and reports the same block
     * reasons — the audit found app-resume was the one format with its own private rule set.
     */
    data object AppResume : AdPlacement {
        override val key: String = "open_resume"
        override val format: AdFormat = AdFormat.APP_OPEN
    }
}
