package io.onboardkit.ads

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.*
import io.onboardkit.core.StepId
import io.onboardkit.flow.FlowDestination
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.remote.RemoteFlags
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
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
    private val interstitials = mutableListOf<AdPlacement>()
    private lateinit var chain: PreloadChain
    private lateinit var activity: Activity
    private lateinit var cfg: OnboardKitConfig
    private var flags = RemoteFlags()
    private var premium = false
    private var allowed = true
    private val steps = listOf(StepId.OB1, StepId.FULL1, StepId.OB2, StepId.FULL2, StepId.OB3, StepId.OB4)
    private val expected = steps.map { if (it in listOf(StepId.FULL1, StepId.FULL2)) AdPlacement.StepFullScreen(it) else AdPlacement.StepNative(it) }

    @Before fun setup() {
        OnboardingSdk.install(ApplicationProvider.getApplicationContext()) { trackkitAutoTracking(false) }
        cfg = onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(languageNative = NativeAdUnit("language"),
                contentStepNative = NativeAdUnit("content"), fullScreenStepNative = NativeAdUnit("fullscreen"),
                afterOnboardingInterstitial = InterstitialAdUnit("exit"))
        }.getOrThrow()
        OnboardingSdk.configure(cfg).getOrThrow()
        val provider = Mockito.mock(OnboardingAdProvider::class.java) { call ->
            when (call.method.name) {
                "preloadNative" -> { requests += call.getArgument<NativeAdRequest>(1).placement; null }
                "loadInterstitial" -> { interstitials += call.getArgument<AdPlacement>(1); null }
                "isPremium" -> premium
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }
        chain = PreloadChain(provider, AdsGuard(provider, { cfg }, { flags }, { allowed }), { cfg }, { flags })
        activity = Robolectric.buildActivity(Activity::class.java).get()
    }

    @After fun cleanup() { OnboardingSettings.document.acceptSuccessfulFetch(null) }

    @Test fun `splash and LFO entry do not warm onboarding natives`() {
        chain.onSplashRemoteReady(activity, FlowDestination.LANGUAGE, 0)
        assertEquals(listOf(AdPlacement.Language1), requests)
        requests.clear()
        chain.onLanguageShown(activity)
        assertEquals(listOf(AdPlacement.Language2), requests)
    }

    @Test fun `language selection warms all six slots once and exit inter only on pager entry`() {
        chain.onLanguageSelected(activity)
        assertEquals(expected, requests)
        assertTrue(interstitials.isEmpty())
        chain.onLanguageSelected(activity)
        steps.indices.forEach { chain.onStepSelected(activity, steps, it) }
        assertEquals(expected, requests)
        chain.onOnboardingShown(activity)
        assertEquals(listOf(AdPlacement.AfterOnboardingInterstitial), interstitials)
    }

    @Test fun `remote order omits slots and app disabled always wins`() {
        cfg = onboardKitConfig {
            steps(ContentStepDefinition(StepId.OB1), ContentStepDefinition(StepId.OB2, enabled = false),
                AdFullScreenStepDefinition(StepId.FULL2), ContentStepDefinition(StepId.OB4))
            ads = AdsConfig(contentStepNative = NativeAdUnit("content"), fullScreenStepNative = NativeAdUnit("full"))
        }.getOrThrow()
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"order":["full2","ob2","unknown","ob1"]}}""")
        cfg = OnboardingSettings.resolve(cfg)
        chain.onLanguageSelected(activity)
        assertEquals(listOf(AdPlacement.StepFullScreen(StepId.FULL2), AdPlacement.StepNative(StepId.OB1)), requests)
    }

    @Test fun `missing units and remote disabled steps never preload`() {
        cfg = onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(stepNatives = mapOf(StepId.OB1 to NativeAdUnit("one"), StepId.OB2 to NativeAdUnit("two")))
        }.getOrThrow()
        flags = flags.copy(enableStepOb2 = false)
        chain.onLanguageSelected(activity)
        assertEquals(listOf(AdPlacement.StepNative(StepId.OB1)), requests)
    }

    @Test fun `premium consent and force update hold prevent every native request`() {
        premium = true
        chain.onLanguageSelected(activity)
        premium = false
        allowed = false
        chain.onLanguageSelected(activity)
        allowed = true
        com.ads.module.helper.AdGate.holdRequests().use { chain.onLanguageSelected(activity) }
        assertTrue(requests.isEmpty())
        chain.onLanguageSelected(activity)
        assertEquals(expected, requests)
    }

    @Test fun `failed or consumed slot is not requested again until a new attempt`() {
        chain.beginSplashAttempt("one")
        chain.onLanguageSelected(activity)
        val request = NativeAdRequest(AdPlacement.StepNative(StepId.OB1), NativeAdUnit("content"), 0)
        assertFalse(chain.requestNativeOnce(activity, request))
        chain.beginSplashAttempt("one") // Activity recreation
        chain.onLanguageSelected(activity)
        assertEquals(expected, requests)
        chain.beginSplashAttempt("two")
        chain.onLanguageSelected(activity)
        assertEquals(expected + expected, requests)
    }
}
