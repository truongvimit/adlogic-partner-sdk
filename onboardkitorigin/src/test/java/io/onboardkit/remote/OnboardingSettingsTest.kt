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
    @Test fun `native click defaults and per step actions are exclusive`() {
        val auto = com.ads.module.helper.adnative.NativeClickAction.AUTO_NEXT
        val reload = com.ads.module.helper.adnative.NativeClickAction.RELOAD
        listOf(AdPlacement.Language1, AdPlacement.Language2, AdPlacement.LanguageConfirm,
            AdPlacement.QuestionNative, AdPlacement.SplashNative, AdPlacement.Ob5).forEach {
            assertEquals(it.key, reload, OnboardingSettings.nativeClickAction(it))
        }
        assertEquals(auto, OnboardingSettings.nativeClickAction(AdPlacement.StepNative(StepId.OB1)))
        assertEquals(auto, OnboardingSettings.nativeClickAction(AdPlacement.StepFullScreen(StepId.OB3)))
        assertTrue(OnboardingSettings.document.acceptSuccessfulFetch("""
            {
              "lfo": {"native2": {"behavior": {"click": {"action": "auto_next"}, "reload": {"on_ad_click": true}}}},
              "onboarding": {
                "steps": {"ob1": {"behavior": {"click": {"action": "reload"}}}},
                "navigation": {"ad_click_return_completes_step": true}
              }
            }
        """.trimIndent()))
        assertEquals(auto, OnboardingSettings.nativeClickAction(AdPlacement.Language2))
        assertEquals(reload, OnboardingSettings.nativeClickAction(AdPlacement.StepNative(StepId.OB1)))
    }

    @Test fun `remote order is a subset of the app catalog and absent order preserves app order`() {
        val cfg = onboardKitConfig {
            steps(ContentStepDefinition(StepId.OB4), AdFullScreenStepDefinition(StepId.FULL2),
                ContentStepDefinition(StepId.OB1), ContentStepDefinition(StepId.OB2, enabled = false))
        }.getOrThrow()
        assertEquals(listOf(StepId.OB4, StepId.FULL2, StepId.OB1),
            io.onboardkit.flow.FlowNavigator.enabledSteps(OnboardingSettings.resolve(cfg), RemoteFlags()))
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"order":["full2","ob2","unknown","ob4"]}}""")
        assertEquals(listOf(StepId.FULL2, StepId.OB4), OnboardingSettings.resolve(cfg).steps.map { it.id })
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"order":[]}}""")
        assertTrue(OnboardingSettings.resolve(cfg).steps.isEmpty())
    }

    @Test fun `malformed order falls back to app order instead of partially changing flow`() {
        val cfg = onboardKitConfig { defaultSteps() }.getOrThrow()
        for (order in listOf("[1, true]", "[\"ob1\",\"ob1\"]", "\"ob1\"")) {
            OnboardingSettings.document.acceptSuccessfulFetch("{\"onboarding\":{\"order\":$order}}")
            assertEquals(cfg.steps, OnboardingSettings.resolve(cfg).steps)
        }
    }

    @Test fun `standard placements keep identities across reorder with separate fullscreen pools`() {
        val ads = AdsConfig.fromAdConfig()
        for (n in 1..4) assertEquals("native_ob$n", ads.placementKeyFor(AdPlacement.StepNative(StepId("ob$n"))))
        for (n in 1..2) assertEquals("native_full$n", ads.placementKeyFor(AdPlacement.StepFullScreen(StepId("full$n"))))
        assertNull(ads.placementKeyFor(AdPlacement.StepFullScreen(StepId.OB3)))
    }

    @After fun clearRemote() {
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdBehavior.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
    }

    @Test fun `all defaults are available before application initialization`() {
        assertEquals(AdLoadStrategy.ALTERNATE, SplashConfig().adLoadStrategy)
        assertFalse(BehaviorConfig().lockPagerSwipe)
        assertFalse(LanguageConfig().confirmVisibleBeforeSelect)
        assertEquals(5000L, OnboardingSettings.number("onboarding.fullscreen.skip.delay_ms"))
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
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"navigation":{"lock_pager_swipe":false}}}""")
        val resolved = OnboardingSettings.resolve(config)
        assertSame(resolved, OnboardingSettings.resolve(config))
        assertFalse(resolved.behavior.lockPagerSwipe)
        val flags = RemoteFlags()
        assertSame(OnboardingSettings.resolveFlags(flags), OnboardingSettings.resolveFlags(flags.copy()))
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        assertSame(config, OnboardingSettings.resolve(config))
    }

    @Test fun `ad config binding resolves IDs without repeated host setup and invalidates cache`() {
        val config = onboardKitConfig { defaultSteps() }.getOrThrow()
        assertNull(OnboardingSettings.resolve(config).ads.languageNative)
        AdRemoteConfig.update(AdRemoteConfig(mapOf(
            "native_lang" to AdUnitConfig("remote_n", true),
            "native_full1" to AdUnitConfig("remote_fs", true),
        )))
        val available = OnboardingSettings.resolve(config)
        assertEquals(listOf("remote_n"), available.ads.languageNative!!.loadOrder)
        assertEquals(listOf("remote_fs"), available.ads.nativeUnitFor(AdPlacement.StepFullScreen(StepId.FULL1))!!.loadOrder)
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_full1" to AdUnitConfig("remote_fs", false))))
        val disabled = OnboardingSettings.resolve(config)
        assertTrue(disabled.ads.nativeUnitFor(AdPlacement.StepFullScreen(StepId.FULL1))!!.loadOrder.isEmpty())
        assertNull(disabled.ads.languageNative)
    }

    @Test fun `returning splash uses original o key with full waterfall and live updates`() {
        val config = onboardKitConfig { }.getOrThrow()
        AdRemoteConfig.update(AdRemoteConfig(mapOf(
            "inter_splash" to AdUnitConfig("new_user", true),
            "inter_splash_o" to AdUnitConfig("returning_base", true),
            "inter_splash_o_high" to AdUnitConfig("returning_high", true),
            "inter_splash_o_high1" to AdUnitConfig("returning_high1", true),
        )))
        val resolved = OnboardingSettings.resolve(config).ads
        assertEquals(listOf("new_user"), resolved.splashInterstitial!!.loadOrder)
        assertEquals(listOf("returning_high", "returning_high1", "returning_base"), resolved.splashInterstitialOldUser!!.loadOrder)

        AdRemoteConfig.update(AdRemoteConfig(mapOf(
            "inter_splash" to AdUnitConfig("new_user", true),
            "inter_splash_o" to AdUnitConfig("returning_base", false),
        )))
        assertTrue(OnboardingSettings.resolve(config).ads.splashInterstitialOldUser!!.loadOrder.isEmpty())

        AdRemoteConfig.update(AdRemoteConfig(mapOf("inter_splash" to AdUnitConfig("new_user", true))))
        val missing = OnboardingSettings.resolve(config).ads
        assertNull(missing.splashInterstitialOldUser)
        assertEquals(listOf("new_user"), (missing.splashInterstitialOldUser ?: missing.splashInterstitial)!!.loadOrder)
    }

    @Test fun `invalid remote default language retains host default`() {
        val config = onboardKitConfig { language = LanguageConfig(defaultCode = "en") }.getOrThrow()
        OnboardingSettings.document.acceptSuccessfulFetch("""{"lfo":{"languages":{"default_code":"missing-language"}}}""")
        assertEquals("en", OnboardingSettings.resolve(config).language.defaultCode)
    }

    @Test fun `removed mapping and UI aliases cannot override ad units or local UI`() {
        val config = onboardKitConfig {
            ads = AdsConfig(ob5Native = NativeAdUnit("local_ob5"), languageTemplate = NativeTemplate.COMPACT)
            system = SystemBarConfig(showStatusBar = false)
        }.getOrThrow()
        AdRemoteConfig.update(AdRemoteConfig(mapOf("remote_ob5" to AdUnitConfig("remote_ob5_id", true))))
        OnboardingSettings.document.acceptSuccessfulFetch("""{"ob5":{"native":{"placement":"remote_ob5","enabled":false}},"lfo":{"native_template":"CTA_TOP"},"flow":{"system_bars":{"show_status":true}},"ui":{"content":{"steps":[{"id":"ob1","title":"ignored"}]}}}""")
        val resolved = OnboardingSettings.resolve(config)
        assertEquals(listOf("local_ob5"), resolved.ads.ob5Native!!.loadOrder)
        assertEquals(NativeTemplate.CTA_TOP, resolved.ads.languageTemplate)
        assertFalse(resolved.system.showStatusBar)
        assertEquals(RemoteFlags(), OnboardingSettings.resolveFlags(RemoteFlags()))
    }

    @Test fun `order alone selects standard fullscreen and custom screens ignoring removed enabled fields`() {
        val ids = listOf(StepId.OB1, StepId.FULL1, StepId("welcome"))
        val config = onboardKitConfig {
            steps(ContentStepDefinition(ids[0]), AdFullScreenStepDefinition(ids[1]), ContentStepDefinition(ids[2]))
        }.getOrThrow()
        val legacyFlags = RemoteFlags(enableStepOb1 = false)
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"order":["welcome","ob1","full1"],"steps":{"ob1":{"enabled":false},"full1":{"enabled":false},"welcome":{"enabled":false}}}}""")
        assertFalse(OnboardingSettings.values.hasOverride("onboarding.steps"))
        assertEquals(listOf(ids[2], ids[0], ids[1]), io.onboardkit.flow.FlowNavigator.enabledSteps(
            OnboardingSettings.resolve(config), OnboardingSettings.resolveFlags(legacyFlags)))
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"order":[]}}""")
        assertTrue(OnboardingSettings.resolve(config).steps.isEmpty())
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"steps":{"welcome":{"enabled":false}}}}""")
        assertEquals(ids, OnboardingSettings.resolve(config).steps.map { it.id })
        assertEquals(legacyFlags, OnboardingSettings.resolveFlags(legacyFlags))
    }

    @Test fun `skip style scopes preserve local resources and unrelated timing`() {
        val config = onboardKitConfig {
            ads = AdsConfig(fullScreenSkipStyle = FullScreenSkipStyle.TEXT)
            behavior = BehaviorConfig(lockPortrait = false)
            system = SystemBarConfig(showStatusBar = false, showNavigationBar = true, showCaptionBar = false)
            steps(ContentStepDefinition(StepId.OB1, layoutRes = 456, showsProgressIndicator = false),
                AdFullScreenStepDefinition(StepId.OB3, skipButtonDelaySec = 7, autoNextEnabled = false),
                AdFullScreenStepDefinition(StepId("custom_ad"), skipButtonStyle = FullScreenSkipStyle.TEXT))
        }.getOrThrow()
        OnboardingSettings.document.acceptSuccessfulFetch("""{"flow":{"lock_portrait":true,"system_bars":{"show_status":true,"show_navigation":false,"show_caption":true},"fullscreen_skip_style":"CLOSE_ICON"},"onboarding":{"fullscreen":{"skip":{"style":"TEXT"}},"steps":{"ob1":{"progress_visible":true},"ob3":{"fullscreen":{"skip":{"style":"CLOSE_ICON"}}}}},"ob5":{"skip":{"style":"TEXT"}}}""")
        val resolved = OnboardingSettings.resolve(config)
        assertSame(config.system, resolved.system)
        assertFalse(resolved.behavior.lockPortrait)
        val content = resolved.steps[0] as ContentStepDefinition
        assertEquals(456, content.layoutRes)
        assertFalse(content.showsProgressIndicator)
        val ob3 = resolved.steps[1] as AdFullScreenStepDefinition
        assertEquals(FullScreenSkipStyle.CLOSE_ICON, ob3.skipButtonStyle)
        assertEquals(7, ob3.skipButtonDelaySec)
        assertFalse(ob3.autoNextEnabled)
        assertEquals(FullScreenSkipStyle.TEXT, (resolved.steps[2] as AdFullScreenStepDefinition).skipButtonStyle)
        assertEquals(FullScreenSkipStyle.TEXT, OnboardingSettings.ob5SkipStyle(resolved.ads.fullScreenSkipStyle))
        OnboardingSettings.document.acceptSuccessfulFetch("""{"flow":{"fullscreen_skip_style":"CLOSE_ICON"},"ob5":{"skip":{"style":"invalid"}}}""")
        assertEquals(FullScreenSkipStyle.CLOSE_ICON, (OnboardingSettings.resolve(config).steps[1] as AdFullScreenStepDefinition).skipButtonStyle)
        assertEquals(FullScreenSkipStyle.CLOSE_ICON, OnboardingSettings.ob5SkipStyle(OnboardingSettings.resolve(config).ads.fullScreenSkipStyle))
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        assertSame(config, OnboardingSettings.resolve(config))
        assertEquals(FullScreenSkipStyle.TEXT, OnboardingSettings.ob5SkipStyle(config.ads.fullScreenSkipStyle))
    }

    @Test fun `each fullscreen page owns its skip side and no shared scope reaches it`() {
        val config = onboardKitConfig {
            steps(AdFullScreenStepDefinition(StepId.FULL1),
                AdFullScreenStepDefinition(StepId.FULL2, skipButtonPosition = FullScreenSkipPosition.LEFT))
        }.getOrThrow()
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"steps":{"full1":{"fullscreen":{"skip":{"position":"LEFT"}}},"full2":{"fullscreen":{"skip":{"position":"RIGHT"}}}}}}""")
        val perPage = OnboardingSettings.resolve(config)
        assertEquals(FullScreenSkipPosition.LEFT, (perPage.steps[0] as AdFullScreenStepDefinition).skipButtonPosition)
        assertEquals(FullScreenSkipPosition.RIGHT, (perPage.steps[1] as AdFullScreenStepDefinition).skipButtonPosition)
        // The umbrella scopes are gone: neither key is a declared path, so both are dropped and
        // each page keeps its own side. OB5 and the splash native answer only to their own key.
        OnboardingSettings.document.acceptSuccessfulFetch("""{"flow":{"fullscreen_skip_position":"LEFT"},"onboarding":{"fullscreen":{"skip":{"position":"LEFT"}}},"ob5":{"skip":{"position":"LEFT"}}}""")
        val shared = OnboardingSettings.resolve(config)
        assertEquals(FullScreenSkipPosition.RIGHT, (shared.steps[0] as AdFullScreenStepDefinition).skipButtonPosition)
        assertEquals(FullScreenSkipPosition.LEFT, (shared.steps[1] as AdFullScreenStepDefinition).skipButtonPosition)
        assertEquals("LEFT", OnboardingSettings.text("ob5.skip.position"))
        assertEquals("RIGHT", OnboardingSettings.text("splash.native.skip.position"))
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"steps":{"full2":{"fullscreen":{"skip":{"position":"UP"}}}}}}""")
        assertEquals(FullScreenSkipPosition.LEFT, (OnboardingSettings.resolve(config).steps[1] as AdFullScreenStepDefinition).skipButtonPosition)
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        assertSame(config, OnboardingSettings.resolve(config))
        // The two standard pages are declared in the asset so a partner can see and copy them,
        // but a bundled default must never displace what the host wrote in code.
        assertEquals("RIGHT", OnboardingSettings.defaultText("onboarding.steps.full1.fullscreen.skip.position"))
        assertEquals("RIGHT", OnboardingSettings.defaultText("onboarding.steps.full2.fullscreen.skip.position"))
        assertEquals(FullScreenSkipPosition.LEFT, (OnboardingSettings.resolve(config).steps[1] as AdFullScreenStepDefinition).skipButtonPosition)
    }

    @Test fun `a shared style scope reaches a page whose style the host set in code`() {
        val config = onboardKitConfig {
            steps(AdFullScreenStepDefinition(StepId.FULL1, skipButtonStyle = FullScreenSkipStyle.CLOSE_ICON),
                AdFullScreenStepDefinition(StepId.FULL2))
        }.getOrThrow()
        OnboardingSettings.document.acceptSuccessfulFetch("""{"flow":{"fullscreen_skip_style":"TEXT"}}""")
        val shared = OnboardingSettings.resolve(config)
        assertEquals(FullScreenSkipStyle.TEXT, (shared.steps[0] as AdFullScreenStepDefinition).skipButtonStyle)
        assertEquals(FullScreenSkipStyle.TEXT, (shared.steps[1] as AdFullScreenStepDefinition).skipButtonStyle)
        assertEquals(FullScreenSkipStyle.TEXT, OnboardingSettings.ob5SkipStyle(shared.ads.fullScreenSkipStyle))
        OnboardingSettings.document.acceptSuccessfulFetch("""{"flow":{"fullscreen_skip_style":"TEXT"},"onboarding":{"steps":{"full1":{"fullscreen":{"skip":{"style":"CLOSE_ICON"}}}}}}""")
        val specific = OnboardingSettings.resolve(config)
        assertEquals(FullScreenSkipStyle.CLOSE_ICON, (specific.steps[0] as AdFullScreenStepDefinition).skipButtonStyle)
        assertEquals(FullScreenSkipStyle.TEXT, (specific.steps[1] as AdFullScreenStepDefinition).skipButtonStyle)
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        assertEquals(FullScreenSkipStyle.CLOSE_ICON, (OnboardingSettings.resolve(config).steps[0] as AdFullScreenStepDefinition).skipButtonStyle)
    }

    @Test fun `language experiments use the app catalog and missing fields retain its default`() {
        val config = onboardKitConfig { language = LanguageConfig(defaultCode = "en") }.getOrThrow()
        val supported = config.language.languages.last().code
        OnboardingSettings.document.acceptSuccessfulFetch("""{"lfo":{"languages":{"default_code":"$supported","supported_codes":["$supported"]},"confirm_button":{"image_url":"https://example.com/confirm.png","tint_color":"#ff0000"}}}""")
        assertEquals(supported, OnboardingSettings.resolve(config).language.defaultCode)
        assertEquals(supported, OnboardingSettings.resolveFlags(RemoteFlags()).languageSupportedCodes)
        assertEquals("#ff0000", OnboardingSettings.text("lfo.confirm_button.tint_color"))
        OnboardingSettings.document.acceptSuccessfulFetch("{}")
        assertEquals("en", OnboardingSettings.resolve(config).language.defaultCode)
        assertEquals("", OnboardingSettings.text("lfo.confirm_button.image_url"))
        assertEquals("", OnboardingSettings.text("lfo.confirm_button.tint_color"))
    }

}
