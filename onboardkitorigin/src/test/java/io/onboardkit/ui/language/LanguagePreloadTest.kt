package io.onboardkit.ui.language

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdEventListener
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.LanguageConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.remote.RemoteFlags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
@Config(sdk = [34], application = Application::class,
    instrumentedPackages = ["io.onboardkit.ui.language"])
@LooperMode(LooperMode.Mode.PAUSED)
class LanguagePreloadTest {
    private companion object {
        val preloads = mutableListOf<AdPlacement>()
        val binds = mutableListOf<AdPlacement>()
        val listeners = mutableMapOf<AdPlacement, AdEventListener>()
        val completions = mutableListOf<io.onboardkit.core.analytics.AnalyticsEvent.LanguageFlowCompleted>()
    }
    private var controller: ActivityController<ObLanguageActivity>? = null
    private val main get() = shadowOf(Looper.getMainLooper())

    @Before fun setup() {
        preloads.clear()
        binds.clear()
        listeners.clear()
        completions.clear()
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        val provider = Mockito.mock(OnboardingAdProvider::class.java) { call ->
            when (call.method.name) {
                "nativeClickAction" -> io.onboardkit.remote.OnboardingSettings.nativeClickAction(call.getArgument(0))
                "preloadNative" -> { preloads += call.getArgument<NativeAdRequest>(1).placement; null }
                "bindNative" -> {
                    val placement = call.getArgument<AdPlacement>(1)
                    binds += placement
                    call.getArgument<AdEventListener?>(4)?.let { listeners[placement] = it }
                    true
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }
        OnboardingSdk.install(ApplicationProvider.getApplicationContext()) {
            adProvider = provider
            trackkitAutoTracking(false)
            analyticsPlugin(io.onboardkit.core.analytics.AnalyticsPlugin { event ->
                if (event is io.onboardkit.core.analytics.AnalyticsEvent.LanguageFlowCompleted) completions += event
            })
        }
        ConsentCenter.setHostConsent(true, false)
        OnboardingSdk.setCanRequestAds(true)
        runBlocking { OnboardingSdk.reset() }
        OnboardingSdk.remoteOrNull()?.applySnapshot(RemoteFlags())
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        main.idle()
        ConsentCenter.clearHostConsent()
        OnboardingSettings.document.acceptSuccessfulFetch(null)
    }

    private fun launch(secondSlot: Boolean = true) {
        OnboardingSdk.configure(onboardKitConfig {
            defaultSteps()
            language = LanguageConfig(secondNativeOnSelectEnabled = secondSlot)
            ads = AdsConfig(languageNative = NativeAdUnit("language"),
                contentStepNative = NativeAdUnit("content"), fullScreenStepNative = NativeAdUnit("fullscreen"))
        }.getOrThrow()).getOrThrow()
        controller = Robolectric.buildActivity(ObLanguageActivity::class.java).setup()
        main.idle()
    }

    private fun tapLanguage() {
        val list = requireNotNull(controller).get().findViewById<RecyclerView>(R.id.ob_language_list)
        val adapter = list.adapter as LanguageAdapter
        adapter.onCreateViewHolder(list, 0).also { adapter.onBindViewHolder(it, 0) }.itemView.performClick()
    }

    @Test fun `first actual language tap warms OB1 OB2 and shows LFO2 once`() {
        launch()
        assertEquals(listOf(AdPlacement.Language2), preloads)
        tapLanguage()
        tapLanguage()
        assertEquals(listOf(AdPlacement.Language2, AdPlacement.StepNative(StepId.OB1),
            AdPlacement.StepNative(StepId.OB2)), preloads)
        assertEquals(listOf(AdPlacement.Language1, AdPlacement.Language2), binds)
        assertEquals(View.VISIBLE, requireNotNull(controller).get().findViewById<View>(R.id.ob_ad_block_2).visibility)
    }

    @Test fun `LFO2 auto next confirms selected language once on actual click return`() {
        OnboardingSettings.document.acceptSuccessfulFetch("""{"lfo":{"native2":{"behavior":{"click":{"action":"auto_next"},"reload":{"on_ad_click":true}}}}}""")
        launch()
        tapLanguage()
        val listener = requireNotNull(listeners[AdPlacement.Language2])
        listener.onClicked()
        listener.onAdOpened()
        assertEquals(0, completions.size)
        requireNotNull(controller).pause().resume()
        main.idle()
        assertEquals(1, completions.size)
        listener.onClicked()
        requireNotNull(controller).pause().resume()
        main.idle()
        assertEquals(1, completions.size)
    }

    @Test fun `LFO2 reload and none never confirm after ad return`() {
        launch()
        tapLanguage()
        for (action in listOf("reload", "none")) {
            OnboardingSettings.document.acceptSuccessfulFetch("""{"lfo":{"native2":{"behavior":{"click":{"action":"$action"}}}}}""")
            requireNotNull(listeners[AdPlacement.Language2]).onClicked()
            requireNotNull(controller).pause().stop().restart().start().resume()
            main.idle()
            assertEquals(action, 0, completions.size)
        }
    }

    @Test fun `LFO2 auto next does not confirm on ordinary resume or failed ad launch`() {
        OnboardingSettings.document.acceptSuccessfulFetch("""{"lfo":{"native2":{"behavior":{"click":{"action":"auto_next"}}}}}""")
        launch()
        tapLanguage()
        requireNotNull(controller).pause().stop().restart().start().resume()
        main.idle()
        assertEquals(0, completions.size)
        requireNotNull(listeners[AdPlacement.Language2]).onClicked()
        android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 0f, 0f, 0).also {
            requireNotNull(controller).get().dispatchTouchEvent(it)
            it.recycle()
        }
        requireNotNull(controller).pause().resume()
        main.idle()
        assertEquals(0, completions.size)
    }

    @Test fun `selection still warms onboarding when LFO2 is disabled`() {
        launch(secondSlot = false)
        assertEquals(emptyList<AdPlacement>(), preloads)
        tapLanguage()
        tapLanguage()
        assertEquals(listOf(AdPlacement.StepNative(StepId.OB1), AdPlacement.StepNative(StepId.OB2)), preloads)
        assertEquals(listOf(AdPlacement.Language1), binds)
    }
}
