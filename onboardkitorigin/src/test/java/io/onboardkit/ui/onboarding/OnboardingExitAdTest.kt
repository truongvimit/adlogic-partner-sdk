package io.onboardkit.ui.onboarding

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OnboardingExitAdTest {
    private var controller: ActivityController<ObOnboardingHostActivity>? = null
    private val main get() = shadowOf(Looper.getMainLooper())

    private companion object {
        val loads = mutableListOf<AdPlacement>()
        var waits = 0
        var bufferedShows = 0
        var presentation: ObInterstitialCallback? = null
        val unit = InterstitialAdUnit(tiers = listOf("after-high", "after-base"))
    }

    @Before
    fun setUp() {
        loads.clear()
        waits = 0
        bufferedShows = 0
        presentation = null
        val provider = Mockito.mock(OnboardingAdProvider::class.java) { call ->
            when (call.method.name) {
                "loadInterstitial" -> {
                    loads += call.getArgument<AdPlacement>(1); null
                }

                "loadAndShowInterstitial" -> {
                    assertEquals(
                        AdPlacement.AfterOnboardingInterstitial,
                        call.getArgument<AdPlacement>(1)
                    )
                    assertEquals(unit, call.getArgument<InterstitialAdUnit>(2))
                    assertEquals(8_000L, call.getArgument<Long>(4))
                    waits++
                    presentation = call.getArgument(3)
                    null
                }

                "showInterstitial" -> {
                    bufferedShows++; null
                }
                // A spare splash must not replace the dedicated end-of-onboarding request.
                "isInterstitialReady" -> call.getArgument<AdPlacement>(0) == AdPlacement.SplashInterstitial
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        OnboardingSdk.install(app) {
            adProvider = provider
            trackkitAutoTracking(false)
        }
        ConsentCenter.setHostConsent(true, false)
        OnboardingSdk.setCanRequestAds(true)
        runBlocking { OnboardingSdk.reset() }
    }

    @After
    fun tearDown() {
        presentation?.onAdSkipped(AdSkipReason.NOT_READY)
        controller?.pause()?.stop()?.destroy()
        main.idle()
        ConsentCenter.setHostConsent(false, false)
    }

    @Test
    fun `entry preloads and finishing waits once before proceeding after dismissal`() {
        val activity = launch()
        assertEquals(listOf(AdPlacement.AfterOnboardingInterstitial), loads)
        activity.next(null)
        activity.next(null)
        assertEquals(1, waits)
        assertEquals(0, bufferedShows)
        assertFalse(activity.isFinishing)
        requireNotNull(presentation).onNextAction()
        main.idle()
        assertFalse("The next flow waits until the interstitial is dismissed", activity.isFinishing)
        requireNotNull(presentation).onAdClosed()
        main.idle()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `timeout skips the ad and completes onboarding`() {
        val activity = launch()
        activity.next(null)
        requireNotNull(presentation).onAdSkipped(AdSkipReason.NOT_READY)
        main.idle()
        assertTrue(activity.isFinishing)
        assertEquals(1, waits)
        assertEquals(0, bufferedShows)
    }

    @Test
    fun `missing placement skips load and show`() {
        val activity = launch(enabled = false)
        activity.next(null)
        main.idle()
        assertTrue(loads.isEmpty())
        assertEquals(0, waits)
        assertTrue(activity.isFinishing)
    }

    private fun launch(enabled: Boolean = true): ObOnboardingHostActivity {
        OnboardingSdk.configure(onboardKitConfig {
            step(ContentStepDefinition(StepId.OB1, title = "Introduction"))
            ads = AdsConfig(afterOnboardingInterstitial = unit.takeIf { enabled })
        }.getOrThrow()).getOrThrow()
        controller =
            Robolectric.buildActivity(ObOnboardingHostActivity::class.java).setup().visible()
        main.idle()
        return requireNotNull(controller).get()
    }
}
