package io.onboardkit.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.onboardKitConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteSilentPlacementKeysTest {

    @Before fun setup() {
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
        OnboardingSdk.install(ApplicationProvider.getApplicationContext()) {
            adProvider = FakeAdProvider()
            trackkitAutoTracking(false)
        }
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
        OnboardingSdk.configure(onboardKitConfig { defaultSteps(); ads = AdsConfig.fromAdConfig() }.getOrThrow())
    }

    @After fun cleanup() {
        AdRemoteConfig.reset()
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
    }

    private fun units(vararg keys: String) = AdRemoteConfig(keys.associateWith { AdUnitConfig(listOf("unit-$it"), true, heightCTA = 50) })

    @Test fun `a remote document under other key names leaves every app placement it omits listed`() {
        AdRemoteConfig.update(units("native_lang", "native_ob1", "inter_splash"), fromRemote = false)
        assertEquals(emptyList<String>(), OnboardingSdk.remoteSilentPlacementKeys())

        AdRemoteConfig.update(units("native_language_1", "native_onboarding_1_1", "inter_splash"), fromRemote = true)
        assertEquals(listOf("native_lang", "native_ob1"), OnboardingSdk.remoteSilentPlacementKeys())

        // A retired `_high` key no longer speaks for its base placement.
        AdRemoteConfig.update(units("native_lang_high", "native_ob1", "inter_splash"), fromRemote = true)
        assertEquals(listOf("native_lang"), OnboardingSdk.remoteSilentPlacementKeys())
    }
}
