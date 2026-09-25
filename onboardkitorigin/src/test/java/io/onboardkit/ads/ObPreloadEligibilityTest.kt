package io.onboardkit.ads

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.config.settings.AdBehavior
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.*
import io.onboardkit.core.StepId
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.remote.RemoteFlags
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise real ad_config resolution, not just manually supplied NativeAdUnit fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ObPreloadEligibilityTest {
    private val ids = listOf("ob1", "full1", "ob2", "full2", "ob3", "ob4")
    private val requests = mutableListOf<NativeAdRequest>()
    private lateinit var activity: Activity
    private lateinit var chain: PreloadChain
    private lateinit var guard: AdsGuard
    private var cfg = onboardKitConfig { defaultSteps() }.getOrThrow()
    private var flags = RemoteFlags()
    private var premium = false
    private var consent = true
    private val provider = Mockito.mock(OnboardingAdProvider::class.java) { call ->
        when (call.method.name) {
            "preloadNative" -> { requests += call.getArgument<NativeAdRequest>(1); null }
            "isPremium" -> premium
            else -> Mockito.RETURNS_DEFAULTS.answer(call)
        }
    }

    @Before fun setup() {
        OnboardingSdk.install(ApplicationProvider.getApplicationContext()) { trackkitAutoTracking(false) }
        activity = Robolectric.buildActivity(Activity::class.java).get()
        clearRemote()
        resetChain()
    }

    @After fun clearRemote() {
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdBehavior.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
    }

    private fun resetChain() {
        requests.clear()
        val config = { OnboardingSettings.resolve(cfg) }
        val remoteFlags = { OnboardingSettings.resolveFlags(flags) }
        guard = AdsGuard(provider, config, remoteFlags, { consent })
        chain = PreloadChain(provider, guard, config, remoteFlags) {
            guard.canFillAdOnlyStep(activity, AdPlacement.StepFullScreen(it))
        }
    }

    private fun adConfig(entries: Map<String, AdUnitConfig> = ids.associate { "native_$it" to AdUnitConfig("unit_$it", true) }) {
        AdRemoteConfig.update(AdRemoteConfig(entries))
    }

    private fun order(selected: List<String>) {
        val json = selected.joinToString(",") { "\"$it\"" }
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"order":[$json]}}""")
    }

    private fun assertRequested(expected: List<String>) {
        chain.onLanguageSelected(activity)
        assertEquals(expected.map { "unit_$it" }, requests.map { it.unit.topTierId })
        // Repeated taps cannot spend the same placement twice.
        chain.onLanguageSelected(activity)
        assertEquals(expected.size, requests.size)
    }

    @Test fun `all 64 remote subsets load only the selected slots even with all six ad IDs enabled`() {
        adConfig()
        repeat(64) { mask ->
            val selected = ids.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.reversed()
            order(selected)
            resetChain()
            assertRequested(selected)
        }
    }

    @Test fun `explicit order overrides legacy screen gates for both preload and pager`() {
        adConfig()
        flags = flags.copy(enableStepOb1 = false, enableStepOb2 = false, enableStepOb3 = false, enableStepOb4 = false)
        order(ids)
        resetChain()
        assertRequested(ids)
        assertEquals(ids, chain.stepDefinitions().map { it.id.value })
    }

    @Test fun `a remote order brings back app disabled screens but not ones the app never declared`() {
        cfg = onboardKitConfig {
            steps(ContentStepDefinition(StepId.OB1), ContentStepDefinition(StepId.OB2, enabled = false),
                AdFullScreenStepDefinition(StepId.FULL1, enabled = false), ContentStepDefinition(StepId.OB4))
        }.getOrThrow()
        adConfig()
        order(ids)
        assertRequested(listOf("ob1", "full1", "ob2", "ob4"))
    }

    @Test fun `each screen omitted from order is excluded before preload`() {
        adConfig()
        ids.forEach { disabled ->
            order(ids - disabled)
            resetChain()
            assertRequested(ids - disabled)
        }
    }

    @Test fun `every slot requires a usable enabled ID and base off disables even enabled high floors`() {
        ids.forEach { target ->
            val key = "native_$target"
            val blocked = listOf(
                emptyMap(),
                mapOf(key to AdUnitConfig("unit_$target", false)),
                mapOf(key to AdUnitConfig("   ", true)),
                mapOf(key to AdUnitConfig("unit_$target", false), "${key}_high" to AdUnitConfig("high", true)),
                mapOf("${key}_high" to AdUnitConfig("high", false)),
            )
            blocked.forEach { replacement ->
                adConfig(ids.filter { it != target }.associate { "native_$it" to AdUnitConfig("unit_$it", true) } + replacement)
                resetChain()
                assertRequested(ids - target)
            }
            // A usable high tier is a valid ID even if the all-price tier is absent.
            adConfig(mapOf("${key}_high" to AdUnitConfig("unit_$target", true)))
            resetChain()
            assertRequested(listOf(target))
        }
    }

    @Test fun `all master and native group switches block requests with list and IDs still present`() {
        adConfig()
        flags = RemoteFlags(adsContentNative = false)
        assertRequested(listOf("full1", "full2"))
        flags = RemoteFlags(adsFullScreenNative = false)
        resetChain()
        assertRequested(listOf("ob1", "ob2", "ob3", "ob4"))
        flags = RemoteFlags(enableAllAds = false)
        resetChain()
        assertRequested(emptyList())
        flags = RemoteFlags()
        OnboardingSettings.document.acceptSuccessfulFetch("""{"flow":{"ads_enabled":false}}""")
        resetChain()
        assertRequested(emptyList())
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdBehavior.document.acceptSuccessfulFetch("""{"global":{"ads_enabled":false}}""")
        resetChain()
        assertRequested(emptyList())
        AdBehavior.document.acceptSuccessfulFetch(null)
        cfg = onboardKitConfig { defaultSteps(); ads = AdsConfig.fromAdConfig().copy(enabled = false) }.getOrThrow()
        resetChain()
        assertRequested(emptyList())
    }

    @Test fun `premium consent and force update hold block real mapped placements`() {
        adConfig()
        premium = true
        assertRequested(emptyList())
        premium = false
        consent = false
        resetChain()
        assertRequested(emptyList())
        consent = true
        resetChain()
        com.ads.module.helper.AdGate.holdRequests().use { assertRequested(emptyList()) }
        assertRequested(ids)
    }

    @Test fun `UA gate blocks all mapped slots even with enabled screens and IDs`() {
        adConfig(ids.associate { "native_$it" to AdUnitConfig("unit_$it", true, enableUaCheck = true) })
        val erain = Mockito.mock(com.ads.module.ads.ERainAd::class.java)
        Mockito.mockStatic(com.ads.module.ads.ERainAd::class.java).use { singleton ->
            singleton.`when`<com.ads.module.ads.ERainAd> { com.ads.module.ads.ERainAd.getInstance() }.thenReturn(erain)
            Mockito.`when`(erain.shouldDisplayForUa(true)).thenReturn(false)
            assertRequested(emptyList())
            Mockito.`when`(erain.shouldDisplayForUa(true)).thenReturn(true)
            assertRequested(ids)
        }
    }

    @Test fun `language preload and pager share order snapshot until the next splash attempt`() {
        adConfig()
        chain.beginSplashAttempt("first")
        assertRequested(ids)
        OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"order":["ob4"]}}""")
        assertEquals(ids, chain.stepDefinitions().map { it.id.value })
        chain.beginSplashAttempt("first")
        assertEquals(ids, chain.stepDefinitions().map { it.id.value })
        chain.beginSplashAttempt("second")
        requests.clear()
        assertRequested(listOf("ob4"))
    }

    @Test fun `snapshot does not override a live ad placement kill switch`() {
        adConfig()
        assertRequested(ids)
        adConfig(ids.associate { "native_$it" to AdUnitConfig("unit_$it", false) })
        ids.forEach { id ->
            val placement = if (id.startsWith("full")) AdPlacement.StepFullScreen(StepId(id)) else AdPlacement.StepNative(StepId(id))
            assertEquals(AdSkipReason.NO_AD_UNIT, guard.skipReason(activity, placement))
        }
        assertEquals(listOf("ob1", "ob2", "ob3", "ob4"), chain.stepDefinitions().map { it.id.value })
    }
}
