package io.onboardkit.remote

import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import io.onboardkit.ads.AdPlacement
import io.onboardkit.config.*
import io.onboardkit.core.StepId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PrivacyGoalsScreenSettingsTest {
    @After fun reset() {
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
    }

    private fun config() = onboardKitConfig {
        privacyGoalsScreen = PrivacyGoalsScreenConfig(
            enabled = true,
            privacy = PrivacyScreenConfig(1, 2, 3, 4),
            goal = GoalsScreenConfig(5, 6, 7, 8, 9),
        )
    }.getOrThrow()

    @Test fun `remote toggles screen group independently from ads`() {
        val config = config()
        OnboardingSettings.document.acceptSuccessfulFetch("""{"privacy_goals_screen":{"enabled":false}}""")
        assertFalse(OnboardingSettings.resolve(config).privacyGoalsScreen.enabled)
        OnboardingSettings.document.acceptSuccessfulFetch("""{"privacy_goals_screen":{"enabled":true}}""")
        assertTrue(OnboardingSettings.resolve(config).privacyGoalsScreen.enabled)
    }

    @Test fun `disabled base suppresses high floor without disabling screen group or alternate`() {
        val document = AdRemoteConfig(ads = mapOf(
            "native_select" to AdUnitConfig(id = "base", isEnable = false),
            "native_select_high" to AdUnitConfig(id = "high", isEnable = true),
            "native_select_alt" to AdUnitConfig(id = "alt", isEnable = true),
            "native_select_alt_high" to AdUnitConfig(id = "alt-high", isEnable = true),
        ))
        val config = config()
        val ads = config.ads.resolvePlacements(document)
        assertEquals(0, ads.nativeUnitFor(AdPlacement.StepNative(StepId.PARTNER_PRIVACY))?.tierCount)
        assertEquals(listOf("alt-high", "alt"), ads.nativeUnitFor(AdPlacement.StepNative(StepId.PARTNER_PRIVACY_ALT))?.loadOrder)
        assertTrue(config.privacyGoalsScreen.enabled)
    }
}
