package io.onboardkit.ui.onboarding

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.ads.NativeStatus
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.QuestionConfig
import io.onboardkit.config.QuestionOption
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.core.StepId
import io.onboardkit.paywall.PaywallGate
import io.onboardkit.paywall.PaywallOutcome
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.remote.RemoteFlags
import io.onboardkit.ui.ob5.ObFullScreenAdActivity
import io.onboardkit.ui.question.ObQuestionActivity
import io.onboardkit.ui.splash.ObSplashActivity
import io.onboardkit.ui.splash.SplashEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
        val outcomes = mutableListOf<OnboardingOutcome>()
        var paywallPresents = 0
        var paywallEnabled = false
        var paywallResult: CompletableDeferred<PaywallOutcome>? = null
        var ob5Ready = false
        val unit = InterstitialAdUnit(tiers = listOf("after-high", "after-base"))
    }

    @Before
    fun setUp() {
        // SDK installation is process-scoped; each test must own its provider/listener.
        // Other Robolectric classes may share this sandbox before this class runs.
        org.robolectric.util.ReflectionHelpers.setStaticField(OnboardingSdk::class.java, "application", null)
        loads.clear()
        waits = 0
        bufferedShows = 0
        presentation = null
        outcomes.clear()
        paywallPresents = 0
        paywallEnabled = false
        paywallResult = null
        ob5Ready = false
        install()
        OnboardingSdk.remoteOrNull()?.applySnapshot(RemoteFlags())
        ConsentCenter.setHostConsent(true, false)
        runBlocking { OnboardingSdk.reset() }
    }

    private fun install() {
        val provider = object : FakeAdProvider() {
            override fun loadInterstitial(
                activity: Activity,
                placement: AdPlacement,
                unit: InterstitialAdUnit,
                adConfigKey: String?,
                listener: AdEventListener?,
            ) { loads += placement }

            override fun loadAndShowInterstitial(
                activity: androidx.appcompat.app.AppCompatActivity,
                placement: AdPlacement,
                unit: InterstitialAdUnit,
                callback: ObInterstitialCallback,
                timeoutMs: Long,
            ) {
                assertEquals(AdPlacement.AfterOnboardingInterstitial, placement)
                assertEquals(OnboardingExitAdTest.unit, unit)
                assertEquals(8_000L, timeoutMs)
                waits++
                presentation = callback
            }

            override fun showInterstitial(
                activity: android.app.Activity,
                placement: AdPlacement,
                callback: ObInterstitialCallback,
            ) { bufferedShows++ }

            // A spare splash must not replace the dedicated end-of-onboarding request.
            override fun isInterstitialReady(placement: AdPlacement) = placement == AdPlacement.SplashInterstitial

            override fun nativeStatus(placement: AdPlacement) =
                if (ob5Ready && placement == AdPlacement.Ob5) NativeStatus.READY else NativeStatus.IDLE
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        OnboardingSdk.install(app) {
            adProvider = provider
            paywallGate = object : PaywallGate {
                override suspend fun shouldShow(placement: PaywallPlacement) =
                    paywallEnabled && placement == PaywallPlacement.AFTER_ONBOARDING

                override suspend fun present(activity: android.app.Activity, placement: PaywallPlacement): PaywallOutcome {
                    paywallPresents++
                    return paywallResult?.await() ?: PaywallOutcome.Dismissed
                }
            }
            listener = OnboardingListener { _, outcome -> outcomes += outcome }
            trackkitAutoTracking(false)
        }
    }

    @After
    fun tearDown() {
        presentation?.onAdSkipped(AdSkipReason.NOT_READY)
        controller?.pause()?.stop()?.destroy()
        main.idle()
        ConsentCenter.setHostConsent(false, false)
        org.robolectric.util.ReflectionHelpers.setStaticField(OnboardingSdk::class.java, "application", null)
    }

    @Test
    fun `entry preloads and the app starts under the ad while the host waits for dismissal`() {
        val activity = launch()
        assertEquals(listOf(AdPlacement.AfterOnboardingInterstitial), loads)
        activity.next(null)
        activity.next(null)
        assertEquals(1, waits)
        assertEquals(0, bufferedShows)
        assertFalse(activity.isFinishing)
        assertTrue(outcomes.isEmpty())
        requireNotNull(presentation).onNextAction()
        main.idle()
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is OnboardingOutcome.Completed)
        assertFalse("The host must outlive the ad it presented", activity.isFinishing)
        requireNotNull(presentation).onAdClosed()
        main.idle()
        assertTrue(activity.isFinishing)
        assertEquals("Completion is delivered once", 1, outcomes.size)
    }

    @Test
    fun `AFTER_AD timing completes onboarding only after dismissal`() {
        val activity = launch(timing = NextScreenTiming.AFTER_AD)
        activity.next(null)
        requireNotNull(presentation).onNextAction()
        main.idle()
        assertTrue(outcomes.isEmpty())
        assertFalse(activity.isFinishing)
        requireNotNull(presentation).onAdClosed()
        main.idle()
        assertEquals(1, outcomes.size)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a paywall after onboarding starts under the ad`() {
        paywallEnabled = true
        val activity = launch()
        activity.next(null)
        main.idle()
        requireNotNull(presentation).onNextAction()
        assertEquals("The paywall must start before the vendor shows the ad", 1, paywallPresents)
        main.idle()
        assertEquals(1, outcomes.size)
        assertFalse("The host must outlive the ad it presented", activity.isFinishing)
        requireNotNull(presentation).onAdClosed()
        main.idle()
        assertEquals(1, paywallPresents)
        assertEquals(1, outcomes.size)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a paywall that closes itself under the ad opens the app only after dismissal`() {
        paywallEnabled = true
        val result = CompletableDeferred<PaywallOutcome>().also { paywallResult = it }
        val activity = launch()
        activity.next(null)
        main.idle()
        requireNotNull(presentation).onNextAction()
        assertEquals(1, paywallPresents)
        requireNotNull(controller).pause()
        result.complete(PaywallOutcome.Dismissed)
        main.idle()
        assertTrue("The app must not open on top of the ad", outcomes.isEmpty())
        requireNotNull(presentation).onAdClosed()
        main.idle()
        assertEquals(1, outcomes.size)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a question without options forwards only once its screen is resumed`() {
        launch(question = QuestionConfig(options = emptyList()))
        val question = Robolectric.buildActivity(ObQuestionActivity::class.java).create().start()
        main.idle()
        assertTrue("Nothing may open while the screen is behind an ad", outcomes.isEmpty())
        question.resume()
        main.idle()
        assertEquals(1, outcomes.size)
        assertTrue(question.get().isFinishing)
        question.pause().stop().destroy()
    }

    @Test
    fun `AFTER_AD timing presents the paywall only after dismissal`() {
        paywallEnabled = true
        val activity = launch(timing = NextScreenTiming.AFTER_AD)
        activity.next(null)
        requireNotNull(presentation).onNextAction()
        main.idle()
        assertEquals(0, paywallPresents)
        assertTrue(outcomes.isEmpty())
        requireNotNull(presentation).onAdClosed()
        main.idle()
        assertEquals(1, paywallPresents)
        assertEquals(1, outcomes.size)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `an entry launch waits for dismissal before completing`() {
        val activity = launch()
        val app = ApplicationProvider.getApplicationContext<Application>()
        OnboardingSdk.session.passthrough = SplashEntry.WIDGET.intent(app, ObSplashActivity::class.java).extras
        activity.next(null)
        requireNotNull(presentation).onNextAction()
        main.idle()
        assertTrue("The entry destination must not start under the ad", outcomes.isEmpty())
        requireNotNull(presentation).onAdClosed()
        main.idle()
        val completed = outcomes.single() as OnboardingOutcome.Completed
        assertEquals(SplashEntry.WIDGET, SplashEntry.from(completed.passthrough))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `OB5 with a ready native starts under the ad`() {
        ob5Ready = true
        val activity = launch(flags = RemoteFlags(enableStepOb5 = true))
        activity.next(null)
        main.idle()
        requireNotNull(presentation).onNextAction()
        assertEquals(
            ObFullScreenAdActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
        main.idle()
        assertTrue(outcomes.isEmpty())
        assertFalse(activity.isFinishing)
        requireNotNull(presentation).onAdClosed()
        main.idle()
        assertEquals(null, shadowOf(activity).peekNextStartedActivity())
        assertTrue(outcomes.isEmpty())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a failed show after the app started completes once`() {
        val activity = launch()
        activity.next(null)
        requireNotNull(presentation).onNextAction()
        requireNotNull(presentation).onAdSkipped(AdSkipReason.FAILED_TO_SHOW)
        main.idle()
        assertEquals(1, outcomes.size)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `the question screen starts under the ad`() {
        val activity = launch(question = QuestionConfig(options = listOf(QuestionOption("a", title = "A"))))
        activity.next(null)
        main.idle()
        requireNotNull(presentation).onNextAction()
        assertEquals(
            ObQuestionActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
        main.idle()
        assertTrue(outcomes.isEmpty())
        assertFalse(activity.isFinishing)
        requireNotNull(presentation).onAdClosed()
        main.idle()
        assertEquals(null, shadowOf(activity).peekNextStartedActivity())
        assertTrue(outcomes.isEmpty())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a paused host completes after it resumes`() {
        val activity = launch()
        activity.next(null)
        requireNotNull(controller).pause()
        requireNotNull(presentation).onAdSkipped(AdSkipReason.NOT_READY)
        main.idle()
        assertTrue("No activity start from a paused host", outcomes.isEmpty())
        assertFalse(activity.isFinishing)
        requireNotNull(controller).resume()
        main.idle()
        assertEquals(1, outcomes.size)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `timeout skips the ad and completes onboarding`() {
        val activity = launch()
        activity.next(null)
        requireNotNull(presentation).onAdSkipped(AdSkipReason.NOT_READY)
        main.idle()
        assertTrue(activity.isFinishing)
        assertEquals(1, outcomes.size)
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

    @Test
    fun `partner switch disables preload and show even with a configured placement`() {
        val activity = launch(automatic = false)
        activity.next(null)
        main.idle()
        assertTrue(loads.isEmpty())
        assertEquals(0, waits)
        assertEquals(0, bufferedShows)
        assertTrue(activity.isFinishing)
    }

    private fun launch(
        enabled: Boolean = true,
        automatic: Boolean = true,
        timing: NextScreenTiming? = null,
        question: QuestionConfig? = null,
        flags: RemoteFlags = RemoteFlags(),
    ): ObOnboardingHostActivity {
        OnboardingSdk.remoteOrNull()?.applySnapshot(flags)
        OnboardingSdk.configure(onboardKitConfig {
            step(ContentStepDefinition(StepId.OB1, title = "Introduction"))
            this.question = question
            ads = AdsConfig(afterOnboardingInterstitial = unit.takeIf { enabled },
                afterOnboardingInterstitialEnabled = automatic)
                .let { if (timing == null) it else it.copy(afterOnboardingInterstitialTiming = timing) }
        }.getOrThrow()).getOrThrow()
        controller =
            Robolectric.buildActivity(ObOnboardingHostActivity::class.java).setup().visible()
        main.idle()
        return requireNotNull(controller).get()
    }
}
