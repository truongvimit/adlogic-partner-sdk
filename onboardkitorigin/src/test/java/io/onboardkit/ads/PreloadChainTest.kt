package io.onboardkit.ads

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.flow.FlowDestination
import io.onboardkit.remote.RemoteFlags
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PreloadChainTest {
    private val requests = mutableListOf<AdPlacement>()
    private lateinit var chain: PreloadChain
    private lateinit var activity: Activity
    private val steps = listOf(StepId.OB1, StepId.OB2, StepId.OB3, StepId.OB4)

    @Before fun setup() {
        OnboardingSdk.install(ApplicationProvider.getApplicationContext()) { trackkitAutoTracking(false) }
        val cfg = onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(languageNative = NativeAdUnit("language"),
                contentStepNative = NativeAdUnit("content"), fullScreenStepNative = NativeAdUnit("fullscreen"))
        }.getOrThrow()
        OnboardingSdk.configure(cfg).getOrThrow()
        val provider = Mockito.mock(OnboardingAdProvider::class.java) { call ->
            if (call.method.name == "preloadNative") {
                requests += call.getArgument<NativeAdRequest>(1).placement
                null
            } else Mockito.RETURNS_DEFAULTS.answer(call)
        }
        val flags = RemoteFlags()
        chain = PreloadChain(provider, AdsGuard(provider, { cfg }, { flags }, { true }), { cfg }, { flags })
        activity = Robolectric.buildActivity(Activity::class.java).get()
    }

    @Test fun `splash warms only LFO1 and LFO entry warms only LFO2`() {
        chain.onSplashRemoteReady(activity, FlowDestination.LANGUAGE, 0)
        assertEquals(listOf(AdPlacement.Language1), requests)
        requests.clear()
        chain.onLanguageShown(activity)
        assertEquals(listOf(AdPlacement.Language2), requests)
    }

    @Test fun `language selection warms OB1 and OB2 without fullscreen or OB3 content`() {
        chain.onLanguageSelected(activity)
        assertEquals(listOf(AdPlacement.StepNative(StepId.OB1), AdPlacement.StepNative(StepId.OB2)), requests)
    }

    @Test fun `pager warms fullscreen from OB1 then third content from fullscreen`() {
        chain.onStepSelected(activity, steps, 0)
        assertEquals(setOf(AdPlacement.StepNative(StepId.OB2), AdPlacement.StepFullScreen(StepId.OB3)), requests.toSet())
        requests.clear()
        chain.onStepSelected(activity, steps, 2)
        assertEquals(listOf(AdPlacement.StepNative(StepId.OB4)), requests)
    }

    @Test fun `resuming onboarding warms the actual destination`() {
        chain.onSplashRemoteReady(activity, FlowDestination.ONBOARDING, 2)
        assertEquals(listOf(AdPlacement.StepFullScreen(StepId.OB3)), requests)
    }
}
