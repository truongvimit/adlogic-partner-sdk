package io.onboardkit.ads.pool

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.settings.AdBehavior
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.core.StepId
import io.onboardkit.remote.OnboardingSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, instrumentedPackages = ["io.onboardkit.ads.pool"])
@LooperMode(LooperMode.Mode.PAUSED)
class PoolPlacementMapperTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val keep = poolSnapshot()

    @Before fun setUp() {
        PoolTestSdk.install(app)
    }

    @After fun tearDown() {
        PoolTestSdk.tearDown()
    }

    private fun declare(json: String) = AdRemoteConfig.initializeFromJson(json)

    private fun request(placement: AdPlacement, snapshot: io.onboardkit.flow.NativeFlowSnapshot = keep) =
        requireNotNull(PoolPlacementMapper.requestFor(placement, snapshot))

    @Test fun `ad_config ids are requested raw, a repeated id included`() {
        declare("""{"native_lang":{"ids":[{"id":"a"},{"id":"a"},{"id":"off","isEnable":false},{"id":"b"}],"isEnable":true}}""")
        val request = request(AdPlacement.Language1)
        assertEquals(listOf("a", "a", "b"), request.ids)
        assertEquals("native_lang", request.adConfigKey)
        assertEquals(AdPlacement.Language1.key, request.key)
        assertEquals(NativeTemplates.layoutForPlacement(AdPlacement.Language1), request.layoutRes)
        assertSame(AdPlacement.Language1, PoolPlacementMapper.placementFor("language1"))
    }

    @Test fun `a switched-off ad_config entry has no ids`() {
        declare("""{"native_lang":{"ids":[{"id":"a"}],"isEnable":false}}""")
        assertEquals(emptyList<String>(), request(AdPlacement.Language1).ids)
    }

    @Test fun `units set in code are requested in their declared order`() {
        PoolTestSdk.configure(POOL_ADS.copy(languageNative = NativeAdUnit(listOf("y", "x"))))
        val raw = request(AdPlacement.Language1)
        assertEquals(listOf("y", "x"), raw.ids)
        assertNull("No ad_config key declares it", raw.adConfigKey)
    }

    @Test fun `Language2 without units of its own reads Language1's key`() {
        PoolTestSdk.configure(AdsConfig(languageNative = LFO1))
        declare("""{"native_lang":{"ids":[{"id":"l1"},{"id":"l1"}],"isEnable":true}}""")
        val second = request(AdPlacement.Language2)
        assertEquals("language2", second.key)
        assertEquals("native_lang", second.adConfigKey)
        assertEquals(listOf("l1", "l1"), second.ids)

        declare("""{"native_lang":{"ids":[{"id":"l1"}],"isEnable":true},"native_lang_alt":{"ids":[{"id":"l2"}],"isEnable":true}}""")
        assertEquals("native_lang_alt", request(AdPlacement.Language2).adConfigKey)
        assertEquals(listOf("l2"), request(AdPlacement.Language2).ids)
    }

    @Test fun `the per-id deadline follows the placement's behaviour chain`() {
        declare("""{"native_lang":{"ids":[{"id":"a"}],"isEnable":true}}""")
        assertEquals(AdBehavior.number("native.load.tier_timeout_ms"), request(AdPlacement.Language1).tierDeadlineMs)
        assertEquals(30_000L, request(AdPlacement.Language1).tierDeadlineMs)

        AdBehavior.document.acceptSuccessfulFetch("""{"native":{"load":{"tier_timeout_ms":4321}}}""")
        assertEquals(4321L, request(AdPlacement.Language1).tierDeadlineMs)

        AdBehavior.document.acceptSuccessfulFetch(
            """{"native":{"load":{"tier_timeout_ms":4321}},"placement_overrides":{"native_lang":{"native":{"load":{"tier_timeout_ms":2345}}}}}""")
        assertEquals(2345L, request(AdPlacement.Language1).tierDeadlineMs)
        assertEquals("Another placement keeps the format value", 4321L, request(AdPlacement.ResumeNative).tierDeadlineMs)

        OnboardingSettings.document.acceptSuccessfulFetch("""{"lfo":{"native1":{"behavior":{"load":{"tier_timeout_ms":1111}}}}}""")
        assertEquals(1111L, request(AdPlacement.Language1).tierDeadlineMs)
    }

    @Test fun `only native placements map to a request`() {
        assertNull(PoolPlacementMapper.requestFor(AdPlacement.SplashInterstitial, keep))
        assertNull(PoolPlacementMapper.requestFor(AdPlacement.SplashBanner, keep))
    }

    @Test fun `a content page needs a unit for a slot and a full page always has one`() {
        assertEquals(AdPlacement.StepNative(StepId.OB1), PoolPlacementMapper.pagePlacement(ContentStepDefinition(StepId.OB1)))
        assertEquals(AdPlacement.StepFullScreen(StepId.FULL1),
            PoolPlacementMapper.pagePlacement(AdFullScreenStepDefinition(StepId.FULL1)))

        PoolTestSdk.configure(AdsConfig(languageNative = LFO1, stepNatives = mapOf(StepId.OB2 to NativeAdUnit("ob2"))))
        assertNull(PoolPlacementMapper.pagePlacement(ContentStepDefinition(StepId.OB1)))
        assertEquals(AdPlacement.StepNative(StepId.OB2), PoolPlacementMapper.pagePlacement(ContentStepDefinition(StepId.OB2)))
        assertEquals(AdPlacement.StepFullScreen(StepId.FULL1),
            PoolPlacementMapper.pagePlacement(AdFullScreenStepDefinition(StepId.FULL1)))
    }
}
