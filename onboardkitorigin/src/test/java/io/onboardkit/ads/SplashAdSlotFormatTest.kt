package io.onboardkit.ads

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.SplashAdSlotFormat
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.remote.OnboardingSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The splash bottom slot takes one occupant, chosen remotely: `banner_splash` or `native_splash`.
 *
 * What is worth pinning is the wiring between the two documents — a flag in `onboarding_config`
 * and an ad unit in `ad_config` — because a break there is silent: the slot simply stays empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SplashAdSlotFormatTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun slotFormat() =
        SplashAdSlotFormat.valueOf(OnboardingSettings.text("splash.ads.slot_format"))

    @Before fun setup() {
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
        OnboardingSdk.install(app) {
            adProvider = mock(OnboardingAdProvider::class.java)
            trackkitAutoTracking(false)
        }
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdBehavior.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
        ConsentCenter.setHostConsent(true, false)
        OnboardingSdk.configure(onboardKitConfig { defaultSteps() }.getOrThrow())
    }

    @After fun cleanup() {
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdBehavior.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
        Dispatchers.resetMain()
    }

    @Test fun `the bundled default leaves the slot on the banner`() {
        assertEquals(SplashAdSlotFormat.BANNER, slotFormat())
    }

    @Test fun `remote picks the native and a typo cannot`() {
        OnboardingSettings.document.acceptSuccessfulFetch("""{"splash":{"ads":{"slot_format":"NATIVE"}}}""")
        assertEquals(SplashAdSlotFormat.NATIVE, slotFormat())
        // An unlisted value is dropped by SettingsDocument rather than reaching valueOf, which
        // would throw on the launch path every user passes through.
        OnboardingSettings.document.acceptSuccessfulFetch("""{"splash":{"ads":{"slot_format":"native"}}}""")
        assertEquals(SplashAdSlotFormat.BANNER, slotFormat())
        OnboardingSettings.document.acceptSuccessfulFetch("""{"splash":{"ads":{"slot_format":"INTERSTITIAL"}}}""")
        assertEquals(SplashAdSlotFormat.BANNER, slotFormat())
    }

    @Test fun `the native_splash entry feeds the inline native and nothing else`() {
        AdRemoteConfig.update(
            AdRemoteConfig(
                mapOf(
                    "native_splash" to AdUnitConfig("splash_native_unit", true),
                    "native_fs" to AdUnitConfig("splash_fs_unit", true),
                ),
            ),
        )
        val ads = OnboardingSdk.requireConfig().ads
        assertEquals(
            listOf("splash_native_unit"),
            ads.unitFor(AdPlacement.SplashInlineNative)!!.loadOrder,
        )
        // The full-screen native keeps its own entry: one key must never feed both slots.
        assertEquals(listOf("splash_fs_unit"), ads.unitFor(AdPlacement.SplashNative)!!.loadOrder)
    }

    @Test fun `an absent native_splash entry leaves the slot empty rather than borrowing one`() {
        AdRemoteConfig.update(AdRemoteConfig(mapOf("banner_splash" to AdUnitConfig("banner_unit", true))))
        assertNull(OnboardingSdk.requireConfig().ads.unitFor(AdPlacement.SplashInlineNative))
    }

    @Test fun `the slot native renders with the fixed media-left frame`() {
        // Not a NativeTemplate: the frame is fixed, so no remote template string can re-skin it.
        assertEquals(
            R.layout.ob_layout_native_media_left,
            NativeTemplates.layoutForPlacement(AdPlacement.SplashInlineNative),
        )
    }

    @Test fun `behavior for the slot native is its own scope, not the full-screen one`() {
        OnboardingSettings.document.acceptSuccessfulFetch(
            """{"splash":{"ads":{"native":{"behavior":{"load":{"tier_timeout_ms":2345}}}},"native":{"behavior":{"load":{"tier_timeout_ms":6789}}}}}""",
        )
        assertEquals(
            2345L,
            OnboardingSettings.behavior(AdPlacement.SplashInlineNative).long("load.tier_timeout_ms", 30000L),
        )
        assertEquals(
            6789L,
            OnboardingSettings.behavior(AdPlacement.SplashNative).long("load.tier_timeout_ms", 30000L),
        )
    }

    @Test fun `the standard key map carries native_splash`() {
        assertEquals(
            "native_splash",
            AdsConfig.fromAdConfig().placementKeys[AdPlacement.SplashInlineNative],
        )
    }
}
