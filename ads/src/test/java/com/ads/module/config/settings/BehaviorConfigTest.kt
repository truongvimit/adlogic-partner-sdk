package com.ads.module.config.settings

import com.ads.module.helper.adnative.NativeAdConfig
import com.ads.module.helper.banner.BannerAdConfig
import com.ads.module.helper.banner.BannerType
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BehaviorConfigTest {
    @After fun clearRemote() { AdBehavior.document.acceptSuccessfulFetch(null) }

    @Test fun `existing config receives remote then restores explicit local options`() {
        val banner = BannerAdConfig("test", true, false).apply { autoReloadTime = 22_000L }
        val native = NativeAdConfig("test", true, false, 1).apply { reloadOnAdClick = false }
        AdBehavior.document.acceptSuccessfulFetch("""{"banner":{"reload":{"allowed":true,"interval_ms":12000},"presentation":{"type":"LARGE_ANCHORED"}},"native":{"reload":{"on_ad_click":true}}}""")
        assertTrue(banner.canReloadAds)
        assertEquals(12_000L, banner.autoReloadTime)
        assertEquals(BannerType.LargeAnchored, banner.bannerType)
        assertTrue(native.reloadOnAdClick)
        AdBehavior.document.acceptSuccessfulFetch(null)
        assertFalse(banner.canReloadAds)
        assertEquals(22_000L, banner.autoReloadTime)
        assertFalse(native.reloadOnAdClick)
    }

    @Test fun `new config defaults do not capture remote as sticky fallback`() {
        AdBehavior.document.acceptSuccessfulFetch("""{"native":{"reload":{"on_ad_click":false}}}""")
        val native = NativeAdConfig("test", true, false, 1)
        assertFalse(native.reloadOnAdClick)
        AdBehavior.document.acceptSuccessfulFetch(null)
        assertTrue(native.reloadOnAdClick)
    }

    @Test fun `screen then placement then format override specificity`() {
        AdBehavior.document.acceptSuccessfulFetch("""{"native":{"reload":{"resume_debounce_ms":600}},"placement_overrides":{"native_home":{"native":{"reload":{"resume_debounce_ms":700}}}}}""")
        val screen = SettingsDocument("screen", """{"slot":{"reload":{"resume_debounce_ms":500}}}""")
        screen.acceptSuccessfulFetch("""{"slot":{"reload":{"resume_debounce_ms":0}}}""")
        assertEquals(0L, AdBehavior.values("native", "native_home", screen.snapshot, "slot").long("reload.resume_debounce_ms", 500L))
        assertEquals(700L, AdBehavior.values("native", "native_home").long("reload.resume_debounce_ms", 500L))
        assertEquals(600L, AdBehavior.values("native", "another").long("reload.resume_debounce_ms", 500L))
    }
}
