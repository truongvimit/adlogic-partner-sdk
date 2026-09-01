package io.onboardkit.config

import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.core.StepId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardKitConfigTest {

    @Test
    fun `default steps build a valid config`() {
        val result = onboardKitConfig { defaultSteps() }
        assertTrue(result.isSuccess)
        assertEquals(4, result.getOrThrow().steps.size)
    }

    @Test
    fun `duplicate step ids fail with a clear message`() {
        val result = onboardKitConfig {
            steps(
                ContentStepDefinition(StepId.OB1),
                ContentStepDefinition(StepId.OB1),
            )
        }
        val error = result.exceptionOrNull() as? ObConfigException
        assertTrue(error != null && error.errors.any { it.contains("Duplicated step ids") })
    }

    @Test
    fun `blank ad unit id fails validation instead of crashing later`() {
        val result = onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(languageNative = NativeAdUnit("  "))
        }
        val error = result.exceptionOrNull() as? ObConfigException
        assertTrue(error != null && error.errors.any { it.contains("languageNative") })
    }

    @Test
    fun `blank confirm-modal ad unit is validated like every other slot`() {
        val result = onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(languageConfirmNative = NativeAdUnit("  "))
        }
        val error = result.exceptionOrNull() as? ObConfigException
        assertTrue(error != null && error.errors.any { it.contains("languageConfirmNative") })
    }

    @Test
    fun `the confirm modal resolves its own unit and falls back to nothing`() {
        val withUnit = AdsConfig(
            languageNative = NativeAdUnit("lang"),
            languageConfirmNative = NativeAdUnit("confirm"),
        )
        assertEquals(
            NativeAdUnit("confirm"),
            withUnit.nativeUnitFor(AdPlacement.LanguageConfirm),
        )
        // Unset stays null: Language2 falls back to languageNative on purpose, this one must not.
        val withoutUnit = AdsConfig(languageNative = NativeAdUnit("lang"))
        assertNull(withoutUnit.nativeUnitFor(AdPlacement.LanguageConfirm))
    }

    @Test
    fun `the confirm modal is fixed to the dialog template`() {
        assertEquals(
            NativeTemplate.DIALOG,
            NativeTemplates.templateForPlacement(AdPlacement.LanguageConfirm),
        )
    }

    @Test
    fun `question minSelection below one is rejected`() {
        val result = onboardKitConfig {
            defaultSteps()
            question = QuestionConfig(
                options = listOf(QuestionOption("a")),
                minSelection = 0,
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `native unit reports its tier count`() {
        assertEquals(1, NativeAdUnit("x").tierCount)
        assertEquals(4, NativeAdUnit(tiers = listOf("a", "b", "c", "d")).tierCount)
    }

    @Test
    fun `named waterfall factory orders natives high floor first`() {
        val unit = NativeAdUnit.waterfall(highFloor = "high", allPrice = "all")
        assertEquals(listOf("high", "all"), unit.loadOrder)
        assertEquals("high", unit.topTierId)
    }

    @Test
    fun `named waterfall factory keeps high-medium-all ordering`() {
        val unit = InterstitialAdUnit.waterfall(
            highFloor = "high",
            mediumFloor = "mid",
            allPrice = "all",
        )
        assertEquals(listOf("high", "mid", "all"), unit.loadOrder)
        assertEquals("high", unit.topTierId)
    }

    @Test
    fun `single id unit has no waterfall`() {
        val unit = NativeAdUnit("only")
        assertEquals(1, unit.tierCount)
        assertEquals("only", unit.topTierId)
    }

    @Test
    fun `waterfall keeps declared floor order and drops blanks`() {
        val unit = NativeAdUnit.waterfall(highFloor = "high", allPrice = "all")
        assertEquals(listOf("high", "all"), unit.loadOrder)
        assertEquals("high", unit.topTierId)
    }

    @Test
    fun `empty tier list fails validation`() {
        val result = onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(languageNative = NativeAdUnit(tiers = emptyList()))
        }
        val error = result.exceptionOrNull() as? ObConfigException
        assertTrue(
            error != null &&
                error.errors.any { it.contains("languageNative") && it.contains("at least one") },
        )
    }

    @Test
    fun `blank tier inside the waterfall is reported`() {
        val result = onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(questionNative = NativeAdUnit(tiers = listOf("high", "  ", "all")))
        }
        val error = result.exceptionOrNull() as? ObConfigException
        assertTrue(
            error != null &&
                error.errors.any { it.contains("questionNative") && it.contains("blank id") },
        )
    }

    @Test
    fun `repeated ad unit id across tiers is reported`() {
        val result = onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(
                splashInterstitial = InterstitialAdUnit(tiers = listOf("same", "same")),
            )
        }
        val error = result.exceptionOrNull() as? ObConfigException
        assertTrue(
            error != null &&
                error.errors.any { it.contains("splashInterstitial") && it.contains("repeats") },
        )
    }

    @Test
    fun `multi tier config passes validation`() {
        val result = onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(
                languageNative = NativeAdUnit(tiers = listOf("high", "mid", "all")),
                splashInterstitial = InterstitialAdUnit(tiers = listOf("i-high", "i-mid", "i-all")),
            )
        }
        assertTrue(result.isSuccess)
    }

    @Test
    fun `minSelection is still read by a screen, not just validated`() {
        // The knob is only meaningful if something consumes it. It lost its last read site once
        // and nothing failed, because validation alone kept looking like coverage.
        val source = java.io.File(
            "src/main/java/io/onboardkit/ui/question/ObQuestionActivity.kt",
        ).readText()
        assertTrue(
            "ObQuestionActivity must gate the CTA on QuestionConfig.minSelection",
            source.contains("minSelection"),
        )
    }

    @Test
    fun `swipeCompletesLastStep is on out of the box and read by the pager host`() {
        // Ships enabled — a partner opts out, never in. The source check keeps the knob wired
        // the same way the minSelection test above keeps its knob wired.
        assertTrue(BehaviorConfig().swipeCompletesLastStep)
        val source = java.io.File(
            "src/main/java/io/onboardkit/ui/onboarding/ObOnboardingHostActivity.kt",
        ).readText()
        assertTrue(
            "ObOnboardingHostActivity must gate the advance-fling detector on " +
                "BehaviorConfig.swipeCompletesLastStep",
            source.contains("swipeCompletesLastStep"),
        )
    }

    @Test
    fun `adClickReturnCompletesStep is on out of the box and read by the step pages`() {
        assertTrue(BehaviorConfig().adClickReturnCompletesStep)
        val source = java.io.File(
            "src/main/java/io/onboardkit/ui/pager/LazyStepFragment.kt",
        ).readText()
        assertTrue(
            "LazyStepFragment must gate the ad-click return on " +
                "BehaviorConfig.adClickReturnCompletesStep",
            source.contains("adClickReturnCompletesStep"),
        )
    }

    @Test
    fun `the language save-on-back button ships on and is read by the screen`() {
        assertTrue(LanguageConfig().saveButtonOnBackEnabled)
        val source = java.io.File(
            "src/main/java/io/onboardkit/ui/language/ObLanguageActivity.kt",
        ).readText()
        assertTrue(
            "ObLanguageActivity must gate the save button on " +
                "LanguageConfig.saveButtonOnBackEnabled",
            source.contains("saveButtonOnBackEnabled"),
        )
        assertTrue(
            "back on the first-open language screen must never leave the flow",
            !source.contains("finishAffinity()"),
        )
    }

    @Test
    fun `the splash offline gate ships on and is read by the splash`() {
        assertTrue(SplashConfig().noInternetPromptEnabled)
        val source = java.io.File(
            "src/main/java/io/onboardkit/ui/splash/ObSplashActivity.kt",
        ).readText()
        assertTrue(
            "ObSplashActivity must gate on SplashConfig.noInternetPromptEnabled",
            source.contains("noInternetPromptEnabled"),
        )
        assertTrue(
            "the gate must release on window focus, not on resume: on Android 13+ the splash " +
                "stays RESUMED under the system connectivity dialog",
            source.contains("windowFocused"),
        )
    }

    @Test
    fun `a layoutRes no screen reads is rejected instead of shipping a page that ignores it`() {
        val result = onboardKitConfig {
            defaultSteps()
            language = LanguageConfig(layoutRes = 123)
        }
        val error = result.exceptionOrNull() as? ObConfigException
        assertTrue(
            error != null && error.errors.any { it.contains("LanguageConfig.layoutRes") },
        )
    }

    @Test
    fun `the two layoutRes knobs that are wired stay accepted`() {
        val result = onboardKitConfig {
            splash = SplashConfig(layoutRes = 123)
            steps(ContentStepDefinition(StepId.OB1, layoutRes = 456))
        }
        assertTrue(result.isSuccess)
    }

    @Test
    fun `language base key groups regional variants`() {
        assertEquals("en", ObLanguage("en-US", "English", 0).baseKey)
        assertEquals("hi", ObLanguage("hi", "Hindi", 0).baseKey)
    }
}
