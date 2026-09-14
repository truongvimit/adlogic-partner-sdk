package io.onboardkit.remote

import com.ads.module.config.settings.AdBehavior
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import io.onboardkit.ads.AdPlacement
import io.onboardkit.config.*
import io.onboardkit.core.StepId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class OnboardingSettingsTest {
    @After fun clearRemote() {
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdBehavior.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
    }

    @Test fun `all defaults are available before application initialization`() {
        assertEquals(AdLoadStrategy.ALTERNATE, SplashConfig().adLoadStrategy)
        assertTrue(BehaviorConfig().lockPagerSwipe)
        assertFalse(LanguageConfig().confirmVisibleBeforeSelect)
        assertEquals(1000L, OnboardingSettings.number("onboarding.fullscreen.skip.delay_ms"))
        assertEquals(3000L, OnboardingSettings.number("ob5.skip.delay_ms"))
    }

    @Test fun `sparse remote resolves screens and preserves host values and hard ads gate`() {
        val c = OnboardKitConfig(splash = SplashConfig(minDisplayTimeMs = 4500), ads = AdsConfig(enabled = false), language = LanguageConfig(), question = null, system = SystemBarConfig(), behavior = BehaviorConfig(),
            steps = listOf(AdFullScreenStepDefinition(StepId.OB3, autoNextDelayMs = 9000)))
        OnboardingSettings.document.acceptSuccessfulFetch("""{"flow":{"ads_enabled":true},"onboarding":{"navigation":{"lock_pager_swipe":false},"fullscreen":{"auto_next":{"enabled":false}},"steps":{"ob3":{"fullscreen":{"skip":{"delay_ms":0}}}}}}""")
        val effective = OnboardingSettings.resolve(c)
        assertFalse(effective.ads.enabled)
        assertFalse(effective.behavior.lockPagerSwipe)
        assertEquals(4500L, effective.splash.minDisplayTimeMs)
        val step = effective.steps.single() as AdFullScreenStepDefinition
        assertFalse(step.autoNextEnabled)
        assertEquals(0, step.skipButtonDelaySec)
        assertEquals(9000L, step.autoNextDelayMs)
    }

    @Test fun `unknown nested field and bad enum cannot crash config access`() {
        assertTrue(OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"steps":{"ob1":{"unknown":true}},"exit_interstitial":{"next_screen_timing":"AUTO"},"ads":{"content_template":""}}}"""))
        val resolved = OnboardingSettings.resolve(onboardKitConfig { }.getOrThrow())
        assertEquals(NativeTemplate.CTA_TOP, resolved.ads.contentStepTemplate)
    }

    @Test fun `new JSON takes precedence over old remote flags including zero`() {
        OnboardingSettings.document.acceptSuccessfulFetch("""{"splash":{"timing":{"min_display_ms":0}},"lfo":{"native2":{"enabled":false}},"ob5":{"enabled":true}}""")
        val flags = OnboardingSettings.resolveFlags(RemoteFlags(enableLanguageNative2 = true, enableStepOb5 = false, splashMinDisplayMs = 4000))
        assertFalse(flags.enableLanguageNative2)
        assertTrue(flags.enableStepOb5)
        assertEquals(0L, flags.splashMinDisplayMs)
    }
    @Test fun `missing remote preserves original config and repeated resolution reuses snapshots`() {
        val config = onboardKitConfig { defaultSteps() }.getOrThrow()
        assertSame(config, OnboardingSettings.resolve(config))
        OnboardingSettings.document.acceptSuccessfulFetch("""{"flow":{"fullscreen_skip_style":"TEXT"}}""")
        val resolved = OnboardingSettings.resolve(config)
        assertSame(resolved, OnboardingSettings.resolve(config))
        assertEquals(FullScreenSkipStyle.TEXT, (resolved.steps[2] as AdFullScreenStepDefinition).skipButtonStyle)
        val flags = RemoteFlags()
        assertSame(OnboardingSettings.resolveFlags(flags), OnboardingSettings.resolveFlags(flags.copy()))
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        assertSame(config, OnboardingSettings.resolve(config))
    }

    @Test fun `native placement remapping uses override for the mapped ad key`() {
        AdBehavior.document.acceptSuccessfulFetch("""{"placement_overrides":{"custom_native":{"native":{"load":{"tier_timeout_ms":2345}}}}}""")
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"steps":{"ob1":{"native_placement":"custom_native"}}},"lfo":{"confirm_dialog":{"native_placement":"custom_native"}}}""")
        assertEquals(2345L, OnboardingSettings.behavior(AdPlacement.StepNative(StepId.OB1)).long("load.tier_timeout_ms", 30000))
        assertEquals(2345L, OnboardingSettings.behavior(AdPlacement.LanguageConfirm).long("load.tier_timeout_ms", 30000))
    }

    @Test fun `disabled mappings suppress fallback and ad unit update invalidates resolved cache`() {
        val custom = StepId("custom")
        val config = onboardKitConfig {
            step(ContentStepDefinition(custom))
            ads = AdsConfig(
                splashInterstitial = InterstitialAdUnit("local_inter"),
                contentStepNative = NativeAdUnit("local_native"),
                languageNative = NativeAdUnit("local_language"),
            )
        }.getOrThrow()
        OnboardingSettings.document.acceptSuccessfulFetch("""{"splash":{"ads":{"interstitial":{"old_user_placement":"remote_inter"}}},"lfo":{"native2":{"placement":"remote_native"}},"onboarding":{"steps":{"custom":{"native_placement":"remote_native"}}}}""")
        val unavailable = OnboardingSettings.resolve(config)
        assertTrue(unavailable.ads.splashInterstitialOldUser!!.loadOrder.isEmpty())
        assertTrue(unavailable.ads.nativeUnitFor(AdPlacement.Language2)!!.loadOrder.isEmpty())
        assertTrue(unavailable.ads.nativeUnitFor(AdPlacement.StepNative(custom))!!.loadOrder.isEmpty())
        AdRemoteConfig.update(AdRemoteConfig(mapOf(
            "remote_inter" to AdUnitConfig("remote_i", true),
            "remote_native" to AdUnitConfig("remote_n", true),
        )))
        val available = OnboardingSettings.resolve(config)
        assertEquals(listOf("remote_i"), available.ads.splashInterstitialOldUser!!.loadOrder)
        assertEquals(listOf("remote_n"), available.ads.nativeUnitFor(AdPlacement.StepNative(custom))!!.loadOrder)
    }

    @Test fun `invalid remote default language retains host default`() {
        val config = onboardKitConfig { language = LanguageConfig(defaultCode = "en") }.getOrThrow()
        OnboardingSettings.document.acceptSuccessfulFetch("""{"lfo":{"languages":{"default_code":"missing-language"}}}""")
        assertEquals("en", OnboardingSettings.resolve(config).language.defaultCode)
    }

    @Test fun `OB5-only mapping is resolved without another group changing`() {
        val config = onboardKitConfig { ads = AdsConfig(ob5Native = NativeAdUnit("local_ob5")) }.getOrThrow()
        AdRemoteConfig.update(AdRemoteConfig(mapOf("remote_ob5" to AdUnitConfig("remote_ob5_id", true))))
        OnboardingSettings.document.acceptSuccessfulFetch("""{"ob5":{"native":{"placement":"remote_ob5"}}}""")
        assertEquals(listOf("remote_ob5_id"), OnboardingSettings.resolve(config).ads.ob5Native!!.loadOrder)
    }

    @Test fun `custom step enabled flag changes flow membership and missing field restores it`() {
        val custom = StepId("welcome")
        val config = onboardKitConfig { step(ContentStepDefinition(custom)) }.getOrThrow()
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"steps":{"welcome":{"enabled":false}}}}""")
        assertTrue(io.onboardkit.flow.FlowNavigator.enabledSteps(OnboardingSettings.resolve(config), RemoteFlags()).isEmpty())
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"steps":{"welcome":{"enabled":true}}}}""")
        assertEquals(listOf(custom), io.onboardkit.flow.FlowNavigator.enabledSteps(OnboardingSettings.resolve(config), RemoteFlags()))
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        assertEquals(listOf(custom), io.onboardkit.flow.FlowNavigator.enabledSteps(OnboardingSettings.resolve(config), RemoteFlags()))
    }

}
