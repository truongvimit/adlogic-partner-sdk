package io.onboardkit.ui.onboarding

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.FullScreenSkipStyle
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.FinishReason
import io.onboardkit.core.StepHost
import io.onboardkit.core.StepId
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit.MILLISECONDS

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TimingApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AdStepTimingTest {
    private lateinit var controller: ActivityController<TimingHost>
    private lateinit var fragment: AdStepFragment
    private val main get() = shadowOf(Looper.getMainLooper())

    private fun launch(definition: AdFullScreenStepDefinition = AdFullScreenStepDefinition(StepId.OB3)) {
        val provider = Mockito.mock(OnboardingAdProvider::class.java) { call ->
            when (call.method.name) {
                "bindNative" -> {
                    call.getArgument<AdEventListener?>(4)?.onImpression()
                    true
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }
        OnboardingSdk.install(ApplicationProvider.getApplicationContext()) {
            adProvider = provider
            trackkitAutoTracking(false)
        }
        OnboardingSdk.configure(onboardKitConfig {
            step(definition)
            ads = AdsConfig(fullScreenStepNative = NativeAdUnit("test-native"))
        }.getOrThrow()).getOrThrow()
        ConsentCenter.setHostConsent(true, false)
        OnboardingSdk.setCanRequestAds(true)
        controller = Robolectric.buildActivity(TimingHost::class.java).setup().visible()
        fragment = AdStepFragment.newInstance(StepId.OB3, 0)
        controller.get().supportFragmentManager.beginTransaction()
            .add(controller.get().parent.id, fragment).commitNow()
        fragment.dispatchSelected()
        main.idle()
    }

    @After fun tearDown() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        ConsentCenter.clearHostConsent()
    }

    @Test fun `default skip unlocks at one second`() {
        launch()
        val skip = fragment.requireView().findViewById<View>(R.id.ob_skip_button)
        main.idleFor(999, MILLISECONDS)
        assertEquals(View.GONE, skip.visibility)
        main.idleFor(1, MILLISECONDS)
        assertEquals(View.VISIBLE, skip.visibility)
    }

    @Test fun `default auto next completes once at three seconds including background time`() {
        launch()
        main.idleFor(500, MILLISECONDS)
        controller.pause().stop()
        main.idleFor(2499, MILLISECONDS)
        assertTrue(controller.get().exits.isEmpty())
        main.idleFor(1, MILLISECONDS)
        assertEquals(listOf("auto_next"), controller.get().exits)
        controller.restart().start().resume()
        main.idleFor(4000, MILLISECONDS)
        assertEquals(listOf("auto_next"), controller.get().exits)
    }

    @Test fun `custom enabled timer includes background time`() {
        launch(AdFullScreenStepDefinition(StepId.OB3, autoNextEnabled = true, autoNextDelayMs = 3000))
        main.idleFor(500, MILLISECONDS)
        controller.pause().stop()
        main.idleFor(2500, MILLISECONDS)
        assertEquals(listOf("auto_next"), controller.get().exits)
    }
    @Test
    fun `default close icon unlocks then tap wins over the auto timer`() {
        launch()
        val skip = fragment.requireView().findViewById<TextView>(R.id.ob_skip_button)
        assertEquals("", skip.text.toString())
        assertNotNull(skip.compoundDrawables[0])
        assertEquals(controller.get().getString(R.string.ob_skip), skip.contentDescription)
        main.idleFor(1000, MILLISECONDS)
        assertEquals(View.VISIBLE, skip.visibility)
        skip.performClick()
        skip.performClick()
        main.idleFor(3000, MILLISECONDS)
        assertEquals(listOf("skip"), controller.get().exits)
    }

    @Test fun `manual mode honors the local skip delay and never auto advances`() {
        launch(
            AdFullScreenStepDefinition(
                StepId.OB3, skipButtonDelaySec = 2,
                autoNextEnabled = false, skipButtonStyle = FullScreenSkipStyle.TEXT
            )
        )
        val skip = fragment.requireView().findViewById<TextView>(R.id.ob_skip_button)
        assertEquals(controller.get().getString(R.string.ob_skip), skip.text.toString())
        main.idleFor(1000, MILLISECONDS)
        assertEquals(View.GONE, skip.visibility)
        main.idleFor(1000, MILLISECONDS)
        assertEquals(View.VISIBLE, skip.visibility)
        main.idleFor(10000, MILLISECONDS)
        assertTrue(controller.get().exits.isEmpty())
    }

    @Test fun `leaving a page cancels its timer and a new visit starts a fresh one`() {
        launch()
        main.idleFor(500, MILLISECONDS)
        fragment.dispatchUnselected()
        main.idleFor(3000, MILLISECONDS)
        assertTrue(controller.get().exits.isEmpty())
        fragment.dispatchSelected()
        main.idleFor(2999, MILLISECONDS)
        assertTrue(controller.get().exits.isEmpty())
        main.idleFor(1, MILLISECONDS)
        assertEquals(listOf("auto_next"), controller.get().exits)
    }

}

class TimingHost : AppCompatActivity(), StepHost {
    lateinit var parent: FrameLayout
    val exits = mutableListOf<String?>()
    override val currentIndex = MutableStateFlow(0)
    override val totalSteps = MutableStateFlow(2)
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.ob_Theme_OnboardKit)
        super.onCreate(savedInstanceState)
        parent = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(parent)
    }
    override fun next(exitReason: String?) { exits += exitReason }
    override fun back() = false
    override fun finishFlow(reason: FinishReason) = Unit
}

class TimingApplication : Application()
