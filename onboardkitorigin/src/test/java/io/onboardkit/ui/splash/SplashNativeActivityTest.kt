package io.onboardkit.ui.splash

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.onboardKitConfig
import org.junit.After
import org.junit.Assert.*
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SplashNativeActivityTest {
    private var controller: ActivityController<ObSplashNativeActivity>? = null
    private var ready = true
    private var binds = true
    private var loads = 0
    private val released = mutableListOf<AdPlacement>()
    private val main get() = shadowOf(Looper.getMainLooper())

    @Before fun setup() {
        org.robolectric.util.ReflectionHelpers.setField(OnboardingSdk, "application", null)
        val provider = Mockito.mock(OnboardingAdProvider::class.java) { call ->
            when (call.method.name) {
                "isNativeReady" -> ready
                "bindNative" -> binds
                "preloadNative" -> { loads++; null }
                "releaseNative" -> { released += call.getArgument<AdPlacement>(0); null }
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }
        OnboardingSdk.install(ApplicationProvider.getApplicationContext()) {
            adProvider = provider
            trackkitAutoTracking(false)
        }
        ConsentCenter.setHostConsent(true, false)
        OnboardingSdk.setCanRequestAds(true)
        OnboardingSdk.configure(onboardKitConfig {
            ads = AdsConfig(splashNative = NativeAdUnit("splash-native"))
        }.getOrThrow()).getOrThrow()
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        ConsentCenter.clearHostConsent()
        org.robolectric.util.ReflectionHelpers.setField(OnboardingSdk, "application", null)
        main.idle()
    }

    private fun launch(): ObSplashNativeActivity {
        controller = Robolectric.buildActivity(ObSplashNativeActivity::class.java)
        val host = requireNotNull(controller).get()
        host.setTheme(R.style.ob_Theme_OnboardKit_FullScreenAd)
        requireNotNull(controller).setup().visible()
        main.idle()
        return host
    }

    @Test fun `ready ad closes to splash result without completing the onboarding session`() {
        val host = launch()
        val skip = host.findViewById<View>(R.id.ob_skip_button)
        assertEquals(View.GONE, skip.visibility)
        main.idleFor(Duration.ofSeconds(3))
        assertEquals(View.VISIBLE, skip.visibility)
        skip.performClick()
        assertTrue(host.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(host).resultCode)
        assertNull(shadowOf(host).nextStartedActivity)
        assertEquals(0, loads)
    }

    @Test fun `lost buffered ad returns immediately without a new network load`() {
        binds = false
        val host = launch()
        assertTrue(host.isFinishing)
        assertEquals(0, loads)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun `unready ad does not leave an empty full screen`() {
        ready = false
        assertTrue(launch().isFinishing)
        assertEquals(0, loads)
    }

    @Test fun `auto dismiss pauses while another screen is on top`() {
        val host = launch()
        requireNotNull(controller).pause().stop()
        main.idleFor(Duration.ofSeconds(30))
        assertFalse(host.isFinishing)
        requireNotNull(controller).restart().start().resume()
        main.idleFor(Duration.ofSeconds(15))
        assertTrue(host.isFinishing)
    }
}
