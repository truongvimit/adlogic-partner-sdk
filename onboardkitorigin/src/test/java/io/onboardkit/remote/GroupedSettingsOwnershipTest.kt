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
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.config.*
import io.onboardkit.core.StepId
import io.onboardkit.flow.FlowNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
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
            adProvider = FakeAdProvider()
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

    @Test fun `the partner sample onboarding_config leaves ad_behavior native values reaching every onboarding native`() {
        OnboardingSdk.configure(onboardKitConfig { defaultSteps() }.getOrThrow())
        assertTrue(OnboardingSettings.document.acceptSuccessfulFetch(
            java.io.File("../partner-integration/examples/ads-onboarding/onboarding_config.json").readText()))
        AdBehavior.document.acceptSuccessfulFetch("""{"native":{"load":{"tier_timeout_ms":4321}},"placement_overrides":{"native_lang":{"native":{"load":{"tier_timeout_ms":2345}}}}}""")
        listOf(AdPlacement.Language1, AdPlacement.Language2).forEach {
            assertEquals(it.key, 2345L, OnboardingSettings.behavior(it).long("load.tier_timeout_ms", 30000L))
        }
        listOf(AdPlacement.SplashNative, AdPlacement.LanguageConfirm, AdPlacement.StepNative(StepId.OB1),
            AdPlacement.StepFullScreen(StepId.FULL1), AdPlacement.Ob5, AdPlacement.WelcomeBack1).forEach {
            assertEquals(it.key, 4321L, OnboardingSettings.behavior(it).long("load.tier_timeout_ms", 30000L))
        }
    }

    @Test fun `adding and disabling remote fullscreen updates page membership without configuring again`() {
        OnboardingSdk.configure(onboardKitConfig { defaultSteps() }.getOrThrow())
        fun pages() = FlowNavigator.enabledSteps(OnboardingSdk.requireConfig(), OnboardingSdk.flags(),
            canShowAdStep = OnboardingSdk::canFillAdOnlyStep)
        assertFalse(StepId.FULL1 in pages())
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_fs" to AdUnitConfig(listOf("splash_fs"), false), "native_full1" to AdUnitConfig(listOf("remote_fs"), true))))
        assertTrue(StepId.FULL1 in pages())
        assertEquals(listOf("remote_fs"), OnboardingSdk.requireConfig().ads.nativeUnitFor(AdPlacement.StepFullScreen(StepId.FULL1))!!.loadOrder)
        // Behavior JSON cannot re-enable or remap the unit declared off in ad_config.
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"steps":{"full1":{"enabled":true,"native_enabled":true,"native_placement":"another"}}}}""")
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_fs" to AdUnitConfig(listOf("splash_fs"), true), "native_full1" to AdUnitConfig(listOf("remote_fs"), false))))
        assertFalse(StepId.FULL1 in pages())
        assertEquals(0, OnboardingSdk.requireConfig().ads.nativeUnitFor(AdPlacement.StepFullScreen(StepId.FULL1))!!.tierCount)
    }

    @Test fun `neither removed JSON templates nor a removed positionCTA pick the frame`() {
        OnboardingSdk.configure(onboardKitConfig { defaultSteps() }.getOrThrow())
        OnboardingSettings.document.acceptSuccessfulFetch("""{"lfo":{"native_template":"CTA_TOP"},"onboarding":{"ads":{"content_template":"COMPACT"},"steps":{"ob1":{"native_template":"CTA_TOP"}}}}""")
        AdRemoteConfig.initializeFromJson("""{"native_lang":{"ids":[{"id":"lang"}],"isEnable":true,"positionCTA":"TOP"},"native_ob1":{"ids":[{"id":"ob1"}],"isEnable":true,"positionCTA":"TOP"},"native_welcome1":{"ids":[{"id":"welcome"}],"isEnable":true,"positionCTA":"TOP"}}""")
        val lfo = io.onboardkit.R.layout.ob_layout_native_lfo
        assertEquals(lfo, NativeTemplates.layoutForPlacement(AdPlacement.Language1))
        assertEquals(lfo, NativeTemplates.layoutForPlacement(AdPlacement.StepNative(StepId.OB1)))
        assertEquals(lfo, NativeTemplates.layoutForPlacement(AdPlacement.WelcomeBack1))
        assertEquals(AdUnitConfig.DEFAULT_COMPONENTS, AdRemoteConfig.getInstance().unit("native_ob1").components)
        assertFalse(OnboardingSettings.values.hasOverride("lfo.native_template"))
        assertFalse(OnboardingSettings.values.hasOverride("onboarding.ads.content_template"))
        assertFalse(OnboardingSettings.values.hasOverride("onboarding.steps.ob1.native_template"))
    }

    @Test fun `fixed ad hosts keep their geometry`() {
        OnboardingSdk.configure(onboardKitConfig { defaultSteps() }.getOrThrow())
        val fullscreen = io.onboardkit.R.layout.ob_layout_native_fullscreen
        assertEquals(fullscreen, NativeTemplates.layoutForPlacement(AdPlacement.StepFullScreen(StepId.OB3)))
        assertEquals(fullscreen, NativeTemplates.layoutForPlacement(AdPlacement.SplashNative))
        assertEquals(fullscreen, NativeTemplates.layoutForPlacement(AdPlacement.Ob5))
        assertEquals(io.onboardkit.R.layout.ob_layout_native_dialog, NativeTemplates.layoutForPlacement(AdPlacement.LanguageConfirm))
    }

    @Test fun `grouped fetch changes navigation but cannot publish app UI payloads`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val config = onboardKitConfig { behavior = BehaviorConfig(lockPagerSwipe = true); defaultSteps(); step(ContentStepDefinition(StepId("custom"), title = "App title", layoutRes = 0)) }.getOrThrow()
        OnboardingSdk.configure(config)
        val remote = OnboardingSdk.remoteOrNull()!!
        remote.applySnapshot(RemoteFlags(uiContentJson = """{"steps":[{"id":"ob1","title":"Legacy title"}]}"""))
        SettingsRegistry.acceptSuccessfulFetch(mapOf("onboarding_config" to """{"onboarding":{"navigation":{"lock_pager_swipe":false},"order":["ob1","full1","ob2","full2","ob3","ob4"]},"ui":{"content":{"steps":[{"id":"ob1","title":"Ignored"}]},"behavior":{"reload":{"allowed":true}}}}"""))
        assertFalse(OnboardingSdk.requireConfig().behavior.lockPagerSwipe)
        assertNull(OnboardingSdk.requireConfig().stepById(StepId("custom")))
        assertFalse(OnboardingSettings.values.hasOverride("ui"))
        assertEquals("Legacy title", remote.uiConfig.value.styleFor("ob1")?.title)
        SettingsRegistry.acceptSuccessfulFetch(mapOf("onboarding_config" to null))
        assertTrue(OnboardingSdk.requireConfig().behavior.lockPagerSwipe)
        assertNotNull(OnboardingSdk.requireConfig().stepById(StepId("custom")))
    }
}
