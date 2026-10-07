package com.ads.module.helper.adnative

import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.config.settings.AdBehavior
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeAdConfigForUnitsTest {
    @After fun clearRemote() {
        AdBehavior.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
    }

    @Test fun `explicit tiers are requested in order without reading ad_config ids`() {
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_lang" to AdUnitConfig(id = "from-config", isEnable = true))))
        val config = NativeAdConfig.forUnits(listOf("high", "", "low", "high"), 7, "native_lang")
        assertEquals(listOf("high", "low"), config.adUnitIds)
        assertEquals(7, config.layoutId)
        assertTrue(config.canShowAds)
    }

    @Test fun `a single fill slot never refills, reloads or reloads on click`() {
        AdBehavior.document.acceptSuccessfulFetch("""{"native":{"reload":{"allowed":true}}}""")
        val config = NativeAdConfig.forUnits(listOf("step"), 1, adConfigKey = "native_ob1", singleFill = true)
        assertFalse(config.canPreloadReplacement)
        assertFalse(config.canReloadAds)
        assertEquals(NativeClickAction.NONE, config.resolvedClickAction)
        clickAction("native_ob1", NativeClickAction.RELOAD)
        assertEquals(NativeClickAction.NONE, config.resolvedClickAction)
        clickAction("native_ob1", NativeClickAction.AUTO_NEXT)
        assertEquals(NativeClickAction.AUTO_NEXT, config.resolvedClickAction)
    }

    @Test fun `a replaceable slot keeps reload on click and follows the reload setting`() {
        val config = NativeAdConfig.forUnits(listOf("lang"), 1)
        assertTrue(config.canPreloadReplacement)
        assertFalse(config.canReloadAds)
        assertEquals(NativeClickAction.RELOAD, config.resolvedClickAction)
        AdBehavior.document.acceptSuccessfulFetch("""{"native":{"reload":{"allowed":true}}}""")
        assertTrue(config.canReloadAds)
    }

    @Test fun `the ad config key supplies the UA gate and the click action`() {
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_lang" to AdUnitConfig(id = "x", isEnable = true,
            enableUaCheck = true, clickAction = NativeClickAction.NONE))))
        val config = NativeAdConfig.forUnits(listOf("lang"), 1, "native_lang")
        assertTrue(config.forceUaCheck)
        assertEquals(NativeClickAction.NONE, config.resolvedClickAction)
        assertFalse(NativeAdConfig.forUnits(listOf("lang"), 1).forceUaCheck)
        assertEquals(NativeClickAction.RELOAD, NativeAdConfig.forUnits(listOf("lang"), 1).resolvedClickAction)
    }

    @Test fun `ad_config click action outranks the code default and a silent key keeps it`() {
        val config = NativeAdConfig.forPlacement("native_home", 1)
        assertEquals(NativeClickAction.RELOAD, config.resolvedClickAction)
        config.clickAction = NativeClickAction.NONE
        assertEquals(NativeClickAction.NONE, config.resolvedClickAction)
        clickAction("native_home", NativeClickAction.RELOAD)
        assertEquals(NativeClickAction.RELOAD, config.resolvedClickAction)
        clickAction("native_home", NativeClickAction.AUTO_NEXT)
        assertEquals(NativeClickAction.AUTO_NEXT, config.resolvedClickAction)
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_home" to AdUnitConfig(id = "x", isEnable = true))))
        assertEquals(NativeClickAction.NONE, config.resolvedClickAction)
    }

    @Test fun `a click action on a floor key never reaches the placement`() {
        AdRemoteConfig.update(AdRemoteConfig(mapOf(
            "native_home_high" to AdUnitConfig(id = "high", isEnable = true, clickAction = NativeClickAction.NONE),
            "native_home" to AdUnitConfig(id = "base", isEnable = true),
        )))
        assertEquals(NativeClickAction.RELOAD, NativeAdConfig.forPlacement("native_home", 1).resolvedClickAction)
        assertEquals(NativeClickAction.RELOAD,
            NativeAdConfig.forUnits(listOf("high"), 1, "native_home").resolvedClickAction)
    }

    @Test fun `placement UA gate follows a remote refresh`() {
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_lang" to AdUnitConfig(id = "x", isEnable = true, enableUaCheck = true))))
        val config = NativeAdConfig.forPlacement("native_lang", 1)
        assertTrue(config.shouldForceUaCheck())
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_lang" to AdUnitConfig(id = "x", isEnable = true, enableUaCheck = false))))
        assertFalse(config.shouldForceUaCheck())
    }

    @Test fun `unit ID cache keys preserve tier boundaries`() {
        val first = NativeAdManager.keyOf(NativeAdConfig.forUnits(listOf("ab", "c"), 1))
        val second = NativeAdManager.keyOf(NativeAdConfig.forUnits(listOf("a", "bc"), 1))
        assertNotEquals(first, second)
    }

    @Test fun `live sources are read on every access and still pass the single fill mapping`() {
        var layout = 3
        var action = NativeClickAction.RELOAD
        val replaceable = NativeAdConfig.forUnits(listOf("lang"), 1,
            liveLayoutId = { layout }, liveClickAction = { action })
        val step = NativeAdConfig.forUnits(listOf("step"), 1, singleFill = true, liveClickAction = { action })
        assertEquals(3, replaceable.layoutId)
        assertEquals(NativeClickAction.RELOAD, replaceable.resolvedClickAction)
        assertEquals(NativeClickAction.NONE, step.resolvedClickAction)
        action = NativeClickAction.RELOAD_WATERFALL
        assertEquals(NativeClickAction.RELOAD_WATERFALL, replaceable.resolvedClickAction)
        assertEquals(NativeClickAction.NONE, step.resolvedClickAction)
        layout = 4
        action = NativeClickAction.AUTO_NEXT
        assertEquals(4, replaceable.layoutId)
        assertEquals(NativeClickAction.AUTO_NEXT, step.resolvedClickAction)
    }

    private fun clickAction(key: String, action: NativeClickAction) = AdRemoteConfig.update(AdRemoteConfig(
        mapOf(key to AdUnitConfig(id = "x", isEnable = true, clickAction = action))))
}
