package com.ads.module.helper.banner

import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BannerAdConfigRemoteTest {
    @After fun cleanup() { AdRemoteConfig.reset() }

    private fun publish(unit: AdUnitConfig) = AdRemoteConfig.update(AdRemoteConfig(mapOf("banner_home" to unit)))

    @Test fun `a zero reload interval keeps the host cadence instead of reloading at once`() {
        val banner = BannerAdConfig.forPlacement("banner_home").apply { autoReloadTime = 22_000L }
        publish(AdUnitConfig("banner", true, reloadIntervalSeconds = 0))
        assertEquals(22_000L, banner.autoReloadTime)
        publish(AdUnitConfig("banner", true, reloadIntervalSeconds = 1))
        assertEquals(BannerAdConfig.MIN_AUTO_RELOAD_MS, banner.autoReloadTime)
        publish(AdUnitConfig("banner", true, reloadIntervalSeconds = null))
        assertEquals(22_000L, banner.autoReloadTime)
    }

    @Test fun `a placement banner reads enable_ua_check again after a refresh`() {
        publish(AdUnitConfig("banner", true, enableUaCheck = false))
        val banner = BannerAdConfig.forPlacement("banner_home")
        assertFalse(banner.forceUaCheck)
        publish(AdUnitConfig("banner", true, enableUaCheck = true))
        assertTrue(banner.forceUaCheck)
        publish(AdUnitConfig("banner", true, enableUaCheck = false))
        assertFalse(banner.forceUaCheck)
    }

    @Test fun `an explicit host UA choice outranks the placement value`() {
        publish(AdUnitConfig("banner", true, enableUaCheck = true))
        val banner = BannerAdConfig.forPlacement("banner_home").apply { forceUaCheck = false }
        assertFalse(banner.forceUaCheck)
        val raw = BannerAdConfig("banner", true, false)
        assertFalse(raw.forceUaCheck)
        raw.forceUaCheck = true
        assertTrue(raw.forceUaCheck)
    }
}
