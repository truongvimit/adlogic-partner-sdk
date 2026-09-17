package io.onboardkit.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.config.settings.SettingsRegistry
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.*
import io.onboardkit.core.StepId
import io.onboardkit.flow.FlowNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GroupedSettingsOwnershipTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setup() {
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
        OnboardingSdk.install(app) {
            adProvider = mock(OnboardingAdProvider::class.java)
            trackkitAutoTracking(false)
        }
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdBehavior.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
        ConsentCenter.setHostConsent(true, false)
    }

    @After fun cleanup() {
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdBehavior.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
        Dispatchers.resetMain()
    }

    @Test fun `standard and custom associations select behavior by the ad config key`() {
        OnboardingSdk.configure(onboardKitConfig { defaultSteps() }.getOrThrow())
        AdBehavior.document.acceptSuccessfulFetch("""{"placement_overrides":{"native_lang":{"native":{"load":{"tier_timeout_ms":2345}}},"partner_native":{"native":{"load":{"tier_timeout_ms":3456}}}}}""")
        assertEquals(2345L, OnboardingSettings.behavior(AdPlacement.Language1).long("load.tier_timeout_ms", 30000L))
        OnboardingSdk.configure(onboardKitConfig {
            ads = AdsConfig.fromAdConfig(mapOf(AdPlacement.Language1 to "partner_native"))
        }.getOrThrow())
        assertEquals(3456L, OnboardingSettings.behavior(AdPlacement.Language1).long("load.tier_timeout_ms", 30000L))
    }

    @Test fun `adding and disabling remote fullscreen updates page membership without configuring again`() {
        OnboardingSdk.configure(onboardKitConfig { defaultSteps() }.getOrThrow())
        fun pages() = FlowNavigator.enabledSteps(OnboardingSdk.requireConfig(), OnboardingSdk.flags(),
            canShowAdStep = OnboardingSdk::canFillAdOnlyStep)
        assertFalse(StepId.FULL1 in pages())
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_fs" to AdUnitConfig("splash_fs", false), "native_full1" to AdUnitConfig("remote_fs", true))))
        assertTrue(StepId.FULL1 in pages())
        assertEquals(listOf("remote_fs"), OnboardingSdk.requireConfig().ads.nativeUnitFor(AdPlacement.StepFullScreen(StepId.FULL1))!!.loadOrder)
        // Behavior JSON cannot re-enable or remap the unit declared off in ad_config.
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"steps":{"full1":{"enabled":true,"native_enabled":true,"native_placement":"another"}}}}""")
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_fs" to AdUnitConfig("splash_fs", true), "native_full1" to AdUnitConfig("remote_fs", false))))
        assertFalse(StepId.FULL1 in pages())
        assertEquals(0, OnboardingSdk.requireConfig().ads.nativeUnitFor(AdPlacement.StepFullScreen(StepId.FULL1))!!.tierCount)
    }

    @Test fun `remote template overrides the frame and removal restores ad config then host`() {
        OnboardingSdk.configure(onboardKitConfig { ads = AdsConfig.fromAdConfig().copy(languageTemplate = NativeTemplate.COMPACT) }.getOrThrow())
        assertEquals(NativeTemplate.COMPACT, NativeTemplates.templateForPlacement(AdPlacement.Language1))
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_lang" to AdUnitConfig("lang", true, positionCTA = "TOP"))))
        assertEquals(NativeTemplate.CTA_TOP, NativeTemplates.templateForPlacement(AdPlacement.Language1))
        OnboardingSettings.document.acceptSuccessfulFetch("""{"lfo":{"native_template":"CTA_BOTTOM"}}""")
        assertEquals(NativeTemplate.CTA_BOTTOM, NativeTemplates.templateForPlacement(AdPlacement.Language1))
        assertEquals(NativeTemplate.CTA_BOTTOM, NativeTemplates.templateForPlacement(AdPlacement.Language2))
        assertFalse(OnboardingSettings.document.acceptSuccessfulFetch("{broken"))
        assertEquals(NativeTemplate.CTA_BOTTOM, NativeTemplates.templateForPlacement(AdPlacement.Language1))
        OnboardingSettings.document.acceptSuccessfulFetch("""{"lfo":{"native_template":"invalid"}}""")
        assertEquals(NativeTemplate.CTA_TOP, NativeTemplates.templateForPlacement(AdPlacement.Language1))
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_lang" to AdUnitConfig("lang", true))))
        assertEquals(NativeTemplate.COMPACT, NativeTemplates.templateForPlacement(AdPlacement.Language1))
    }

    @Test fun `per step templates inherit group defaults and fixed ad hosts keep their geometry`() {
        OnboardingSdk.configure(onboardKitConfig { defaultSteps() }.getOrThrow())
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"ads":{"content_template":"COMPACT"},"steps":{"ob1":{"native_template":"CTA_BOTTOM"},"custom":{"native_template":"CTA_TOP"},"ob3":{"native_template":"COMPACT"}}},"question":{"native":{"template":"CTA_TOP"}}}""")
        assertEquals(NativeTemplate.CTA_BOTTOM, NativeTemplates.templateForPlacement(AdPlacement.StepNative(StepId.OB1)))
        assertEquals(NativeTemplate.COMPACT, NativeTemplates.templateForPlacement(AdPlacement.StepNative(StepId.OB2)))
        assertEquals(NativeTemplate.CTA_TOP, NativeTemplates.templateForPlacement(AdPlacement.StepNative(StepId("custom"))))
        assertEquals(NativeTemplate.CTA_TOP, NativeTemplates.templateForPlacement(AdPlacement.QuestionNative))
        assertEquals(NativeTemplate.FULL_SCREEN, NativeTemplates.templateForPlacement(AdPlacement.StepFullScreen(StepId.OB3)))
        assertEquals(NativeTemplate.DIALOG, NativeTemplates.templateForPlacement(AdPlacement.LanguageConfirm))
        assertEquals(NativeTemplate.FULL_SCREEN, NativeTemplates.templateForPlacement(AdPlacement.SplashNative))
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"ads":{"content_template":"COMPACT"},"steps":{"ob1":{"native_template":""},"ob2":{"native_template":"INVALID"}}}}""")
        assertEquals(NativeTemplate.COMPACT, NativeTemplates.templateForPlacement(AdPlacement.StepNative(StepId.OB1)))
        assertEquals(NativeTemplate.COMPACT, NativeTemplates.templateForPlacement(AdPlacement.StepNative(StepId.OB2)))
    }

    @Test fun `grouped fetch changes navigation but cannot publish app UI payloads`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val config = onboardKitConfig { behavior = BehaviorConfig(lockPagerSwipe = true); defaultSteps(); step(ContentStepDefinition(StepId("custom"), title = "App title", layoutRes = 0)) }.getOrThrow()
        OnboardingSdk.configure(config)
        val remote = OnboardingSdk.remoteOrNull()!!
        remote.applySnapshot(RemoteFlags(uiContentJson = """{"steps":[{"id":"ob1","title":"Legacy title"}]}"""))
        SettingsRegistry.acceptSuccessfulFetch(mapOf("onboarding_config" to """{"onboarding":{"navigation":{"lock_pager_swipe":false},"steps":{"custom":{"enabled":false}}},"ui":{"content":{"steps":[{"id":"ob1","title":"Ignored"}]},"behavior":{"reload":{"allowed":true}}},"question":{"content":{"title":"Ignored","options":[]}}}"""))
        assertFalse(OnboardingSdk.requireConfig().behavior.lockPagerSwipe)
        assertNull(OnboardingSdk.requireConfig().stepById(StepId("custom")))
        assertFalse(OnboardingSettings.values.hasOverride("ui"))
        assertFalse(OnboardingSettings.values.hasOverride("question.content"))
        assertEquals("Legacy title", remote.uiConfig.value.styleFor("ob1")?.title)
        SettingsRegistry.acceptSuccessfulFetch(mapOf("onboarding_config" to null))
        assertTrue(OnboardingSdk.requireConfig().behavior.lockPagerSwipe)
        assertNotNull(OnboardingSdk.requireConfig().stepById(StepId("custom")))
    }
}
