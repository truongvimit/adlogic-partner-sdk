package io.onboardkit.ui.splash

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.NativeStatus
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.onboardKitConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
        val provider = object : FakeAdProvider() {
            override fun nativeStatus(placement: AdPlacement) = if (ready) NativeStatus.READY else NativeStatus.IDLE
            override fun bindNative(
                activity: ComponentActivity,
                request: NativeAdRequest,
                container: FrameLayout,
                listener: AdEventListener,
            ) = ready && binds
            override fun preloadNative(activity: Activity, request: NativeAdRequest) { loads++ }
            override fun releaseNative(placement: AdPlacement) { released += placement }
        }
        OnboardingSdk.install(ApplicationProvider.getApplicationContext()) {
            adProvider = provider
            trackkitAutoTracking(false)
        }
        ConsentCenter.setHostConsent(true, false)
        OnboardingSdk.configure(onboardKitConfig {
            ads = AdsConfig(splashNative = NativeAdUnit("splash-native"))
        }.getOrThrow()).getOrThrow()
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        ConsentCenter.clearHostConsent()
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(null)
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

    @Test fun `filled native never auto dismisses even with a legacy remote timeout`() {
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(
            """{"splash":{"native":{"auto_dismiss_ms":5000}}}""")
        val host = launch()
        requireNotNull(controller).pause().stop()
        main.idleFor(Duration.ofSeconds(30))
        assertFalse(host.isFinishing)
        requireNotNull(controller).restart().start().resume()
        main.idleFor(Duration.ofSeconds(60))
        assertFalse(host.isFinishing)
        host.findViewById<View>(R.id.ob_skip_button).performClick()
        assertTrue(host.isFinishing)
    }
}
