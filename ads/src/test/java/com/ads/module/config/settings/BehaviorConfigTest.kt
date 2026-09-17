package com.ads.module.config.settings

import com.ads.module.helper.adnative.NativeAdConfig
import com.ads.module.helper.banner.BannerAdConfig
import com.ads.module.helper.banner.BannerType
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BehaviorConfigTest {
    @Test fun `native click action wins over legacy reload flag and rejects invalid actions`() {
        val config = NativeAdConfig("native", true, false, 1)
        val actions = com.ads.module.helper.adnative.NativeClickAction.entries
        actions.forEach { action ->
            AdBehavior.document.acceptSuccessfulFetch("""{"native":{"click":{"action":"${action.remoteValue}"},"reload":{"on_ad_click":${action != com.ads.module.helper.adnative.NativeClickAction.RELOAD}}}}""")
            assertEquals(action, config.resolvedClickAction)
        }
        AdBehavior.document.acceptSuccessfulFetch("""{"native":{"click":{"action":"typo"}}}""")
        assertEquals(com.ads.module.helper.adnative.NativeClickAction.RELOAD, config.resolvedClickAction)
    }

    @After fun clearRemote() { AdBehavior.document.acceptSuccessfulFetch(null) }

    @Test fun `auto buffer rules resolve under the dedicated top level group`() {
        assertTrue(AdBehavior.defaultBool("interstitial_auto_buffer.enabled"))
        AdBehavior.document.acceptSuccessfulFetch("""{"interstitial_auto_buffer":{"enabled":false,"rules":{"inter_home":{"enabled":true,"interval_ms":12000}}}}""")
        assertFalse(AdBehavior.bool("interstitial_auto_buffer.enabled"))
        assertTrue(AdBehavior.bool("interstitial_auto_buffer.rules.inter_home.enabled"))
        assertEquals(12000L, AdBehavior.number("interstitial_auto_buffer.rules.inter_home.interval_ms"))
        AdBehavior.document.acceptSuccessfulFetch(null)
        assertTrue(AdBehavior.bool("interstitial_auto_buffer.enabled"))
    }

    @Test fun `existing config receives remote then restores explicit local options`() {
        val banner = BannerAdConfig("test", true, false).apply { autoReloadTime = 22_000L }
        val native = NativeAdConfig("test", true, false, 1).apply { reloadOnAdClick = false }
        AdBehavior.document.acceptSuccessfulFetch("""{"banner":{"reload":{"allowed":true,"interval_ms":12000},"presentation":{"type":"LARGE_ANCHORED"}},"native":{"reload":{"on_ad_click":true}}}""")
        assertTrue(banner.canReloadAds)
        assertEquals(22_000L, banner.autoReloadTime) // Removed JSON alias cannot override ad_config/local cadence.
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
