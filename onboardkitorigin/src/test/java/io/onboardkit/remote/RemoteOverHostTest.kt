package io.onboardkit.remote

import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.config.settings.AdBehavior
import io.onboardkit.ads.AdPlacement
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.LanguageConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.QuestionConfig
import io.onboardkit.config.QuestionOption
import io.onboardkit.config.SelectionMode
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whatever the app configured, a value the backend delivered wins; the app decides only when it is silent. */
class RemoteOverHostTest {

    @After fun clearRemote() {
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        OnboardingSettings.acceptLegacy(RemoteFlags(supplied = emptySet()))
        AdBehavior.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
    }

    @Test fun `remote switches ads on over a host that switched them off`() {
        val config = onboardKitConfig { ads = AdsConfig.fromAdConfig().copy(enabled = false) }.getOrThrow()
        assertFalse(OnboardingSettings.resolve(config).ads.enabled)
        OnboardingSettings.document.acceptSuccessfulFetch("""{"flow":{"ads_enabled":true}}""")
        assertTrue(OnboardingSettings.resolve(config).ads.enabled)
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        OnboardingSettings.acceptLegacy(RemoteFlags(enableAllAds = true, supplied = setOf("ob_enable_all_ads")))
        assertTrue(OnboardingSettings.resolve(config).ads.enabled)
    }

    @Test fun `a delivered legacy key beats the host and an undelivered one does not`() {
        val config = onboardKitConfig {
            language = LanguageConfig(tapHintEnabled = false, confirmDialogOnReselectEnabled = false)
        }.getOrThrow()
        OnboardingSettings.acceptLegacy(RemoteFlags(showLanguageTapHint = true, showLanguageConfirmDialog = true,
            supplied = setOf("ob_show_language_tap_hint")))
        val resolved = OnboardingSettings.resolve(config)
        assertTrue(resolved.language.tapHintEnabled)
        assertFalse(resolved.language.confirmDialogOnReselectEnabled)
        OnboardingSettings.document.acceptSuccessfulFetch("""{"lfo":{"tap_hint":{"enabled":false}}}""")
        assertFalse(OnboardingSettings.resolve(config).language.tapHintEnabled)
    }

    @Test fun `a snapshot without delivery records counts only values that differ from their defaults`() {
        val config = onboardKitConfig { language = LanguageConfig(tapHintEnabled = false) }.getOrThrow()
        OnboardingSettings.acceptLegacy(RemoteFlags())
        assertFalse(OnboardingSettings.resolve(config).language.tapHintEnabled)
        OnboardingSettings.acceptLegacy(RemoteFlags(splashMinDisplayMs = 4_500))
        assertEquals(4_500L, OnboardingSettings.resolve(config).splash.minDisplayTimeMs)
    }

    @Test fun `legacy step keys add and remove pages at their catalog position`() {
        val config = onboardKitConfig {
            steps(ContentStepDefinition(StepId.OB1), ContentStepDefinition(StepId.OB2, enabled = false),
                ContentStepDefinition(StepId.OB3))
        }.getOrThrow()
        val legacy = RemoteFlags(enableStepOb2 = true, enableStepOb3 = false,
            supplied = setOf("ob_enable_step_ob2", "ob_enable_step_ob3"))
        OnboardingSettings.acceptLegacy(legacy)
        assertEquals(listOf(StepId.OB1, StepId.OB2), OnboardingSettings.resolve(config).steps.map { it.id })
        assertTrue(OnboardingSettings.resolveFlags(legacy).enableStepOb2)
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"order":["ob3","ob2"]}}""")
        assertEquals(listOf(StepId.OB3, StepId.OB2), OnboardingSettings.resolve(config).steps.map { it.id })
        assertTrue(OnboardingSettings.resolveFlags(legacy).enableStepOb3)
    }

    @Test fun `ad units written in code are only the fallback for a key ad_config declares`() {
        val config = onboardKitConfig { ads = AdsConfig(languageNative = NativeAdUnit("host")) }.getOrThrow()
        assertEquals(listOf("host"), OnboardingSettings.resolve(config).ads.languageNative!!.loadOrder)
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_lang" to AdUnitConfig("remote", true))), fromRemote = true)
        assertEquals(listOf("remote"), OnboardingSettings.resolve(config).ads.languageNative!!.loadOrder)
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_lang" to AdUnitConfig("remote", false))), fromRemote = true)
        assertTrue(OnboardingSettings.resolve(config).ads.languageNative!!.loadOrder.isEmpty())
    }

    @Test fun `ad units written in code outrank the app's own ad_config`() {
        val config = onboardKitConfig {
            ads = AdsConfig(languageNative = NativeAdUnit("host"), splashInterstitialOldUser = InterstitialAdUnit("host_old"))
        }.getOrThrow()
        AdRemoteConfig.update(AdRemoteConfig(mapOf(
            "native_lang" to AdUnitConfig("asset", true),
            "inter_splash" to AdUnitConfig("asset_inter", true),
        )), fromRemote = false)
        val resolved = OnboardingSettings.resolve(config).ads
        assertEquals(listOf("host"), resolved.languageNative!!.loadOrder)
        assertEquals(listOf("host_old"), resolved.splashInterstitialOldUser!!.loadOrder)
    }

    @Test fun `returning users follow a declared inter_splash until _o is declared`() {
        val config = onboardKitConfig {
            ads = AdsConfig.fromAdConfig().copy(splashInterstitialOldUser = InterstitialAdUnit("host_old"))
        }.getOrThrow()
        assertEquals(listOf("host_old"), OnboardingSettings.resolve(config).ads.splashInterstitialOldUser!!.loadOrder)
        AdRemoteConfig.update(AdRemoteConfig(mapOf("inter_splash" to AdUnitConfig("remote", false))), fromRemote = true)
        val killed = OnboardingSettings.resolve(config).ads
        assertNull(killed.splashInterstitialOldUser)
        assertTrue(killed.splashInterstitial!!.loadOrder.isEmpty())
        AdRemoteConfig.update(AdRemoteConfig(mapOf(
            "inter_splash" to AdUnitConfig("remote", false),
            "inter_splash_o" to AdUnitConfig("returning", true),
        )), fromRemote = true)
        assertEquals(listOf("returning"), OnboardingSettings.resolve(config).ads.splashInterstitialOldUser!!.loadOrder)
    }

    @Test fun `LFO2 without units of its own reads LFO1's ad_config key`() {
        val config = onboardKitConfig { }.getOrThrow()
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_lang" to AdUnitConfig("l1", true))), fromRemote = true)
        assertEquals("native_lang", OnboardingSettings.resolve(config).ads.placementKeyFor(AdPlacement.Language2))
        AdRemoteConfig.update(AdRemoteConfig(mapOf(
            "native_lang" to AdUnitConfig("l1", true),
            "native_lang_alt" to AdUnitConfig("l2", true),
        )), fromRemote = true)
        assertEquals("native_lang_alt", OnboardingSettings.resolve(config).ads.placementKeyFor(AdPlacement.Language2))
    }

    @Test fun `a host default language the remote list leaves out is not preselected`() {
        val base = onboardKitConfig { }.getOrThrow().language.languages
        val config = onboardKitConfig { language = LanguageConfig(defaultCode = base[0].code) }.getOrThrow()
        OnboardingSettings.document.acceptSuccessfulFetch(
            """{"lfo":{"languages":{"supported_codes":["${base[1].code}","${base[2].code}"]}}}""")
        val offered = OnboardingSettings.resolve(config).language
        assertEquals(listOf(base[1].code, base[2].code), offered.languages.map { it.code })
        assertNull(offered.defaultCode)
        OnboardingSettings.document.acceptSuccessfulFetch(
            """{"lfo":{"languages":{"supported_codes":["${base[1].code}","${base[2].code}"],"default_code":"${base[2].code}"}}}""")
        assertEquals(base[2].code, OnboardingSettings.resolve(config).language.defaultCode)
    }

    @Test fun `remote question options clamp the host minimum and stand on their own`() {
        val host = QuestionConfig(
            selectionMode = SelectionMode.MULTIPLE,
            minSelection = 3,
            options = listOf("a", "b", "c", "d").map { QuestionOption(it, title = it) },
        )
        val remote = """{"title":"Goal","options":[{"id":"x","title":"X"},{"id":"y","title":"Y"}]}"""
        val question = OnboardingSettings.questionContent(host, remote)!!
        assertEquals(listOf("x", "y"), question.options.map { it.id })
        assertEquals(2, question.minSelection)
        assertEquals("Goal", question.title)
        assertNotNull(OnboardingSettings.questionContent(null, remote))
        assertNull(OnboardingSettings.questionContent(null, ""))
        assertEquals(3, OnboardingSettings.questionContent(host, "")!!.minSelection)
    }
}
