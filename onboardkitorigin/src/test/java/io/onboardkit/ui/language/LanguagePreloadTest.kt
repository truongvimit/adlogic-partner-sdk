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
    }
    private var controller: ActivityController<ObLanguageActivity>? = null
    private val main get() = shadowOf(Looper.getMainLooper())

    @Before fun setup() {
        preloads.clear()
        binds.clear()
        val provider = Mockito.mock(OnboardingAdProvider::class.java) { call ->
            when (call.method.name) {
                "preloadNative" -> { preloads += call.getArgument<NativeAdRequest>(1).placement; null }
                "bindNative" -> { binds += call.getArgument<AdPlacement>(1); true }
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }
        OnboardingSdk.install(ApplicationProvider.getApplicationContext()) {
            adProvider = provider
            trackkitAutoTracking(false)
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

    @Test fun `selection still warms onboarding when LFO2 is disabled`() {
        launch(secondSlot = false)
        assertEquals(emptyList<AdPlacement>(), preloads)
        tapLanguage()
        tapLanguage()
        assertEquals(listOf(AdPlacement.StepNative(StepId.OB1), AdPlacement.StepNative(StepId.OB2)), preloads)
        assertEquals(listOf(AdPlacement.Language1), binds)
    }
}
