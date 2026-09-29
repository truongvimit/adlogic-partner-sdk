package com.ads.module.config.settings

import com.ads.module.helper.adnative.NativeAdConfig
import com.ads.module.helper.adnative.NativeClickAction
import com.ads.module.helper.banner.BannerAdConfig
import com.ads.module.helper.banner.BannerType
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BehaviorConfigTest {
    @Test fun `ad behavior carries no native click action at any scope`() {
        val config = NativeAdConfig.forUnits(listOf("native"), 1, adConfigKey = "native_home")
        assertTrue(AdBehavior.document.acceptSuccessfulFetch(
            """{"native":{"click":{"action":"none"},"reload":{"on_ad_click":false}},""" +
                """"placement_overrides":{"native_home":{"native":{"click":{"action":"auto_next"}}}}}"""))
        assertFalse(AdBehavior.document.snapshot.hasRemoteOverride("native.click.action"))
        assertFalse(AdBehavior.document.snapshot.hasRemoteOverride("native.reload.on_ad_click"))
        assertFalse(AdBehavior.supportsPlacementField("native", "click.action"))
        assertEquals(NativeClickAction.RELOAD, config.resolvedClickAction)
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
        val native = NativeAdConfig("test", true, false, 1).apply { autoShimmer = false }
        AdBehavior.document.acceptSuccessfulFetch("""{"banner":{"reload":{"allowed":true,"interval_ms":12000},"presentation":{"type":"LARGE_ANCHORED"}},"native":{"presentation":{"auto_shimmer":true}}}""")
        assertTrue(banner.canReloadAds)
        assertEquals(22_000L, banner.autoReloadTime) // Removed JSON alias cannot override ad_config/local cadence.
        assertEquals(BannerType.LargeAnchored, banner.bannerType)
        assertTrue(native.autoShimmer)
        AdBehavior.document.acceptSuccessfulFetch(null)
        assertFalse(banner.canReloadAds)
        assertEquals(22_000L, banner.autoReloadTime)
        assertFalse(native.autoShimmer)
    }

    @Test fun `new config defaults do not capture remote as sticky fallback`() {
        AdBehavior.document.acceptSuccessfulFetch("""{"native":{"presentation":{"auto_shimmer":false}}}""")
        val native = NativeAdConfig("test", true, false, 1)
        assertFalse(native.autoShimmer)
        AdBehavior.document.acceptSuccessfulFetch(null)
        assertTrue(native.autoShimmer)
    }

    @Test fun `an empty placement_overrides object leaves the format value in charge`() {
        assertTrue(AdBehavior.document.acceptSuccessfulFetch(
            """{"native":{"load":{"tier_timeout_ms":5000}},"placement_overrides":{}}"""))
        assertEquals(5000L, AdBehavior.values("native", "native_ob1").long("load.tier_timeout_ms", 30_000L))
        assertTrue(AdBehavior.values("native", "native_ob1").hasOverride("load.tier_timeout_ms"))
    }

    @Test fun `an empty screen behavior object leaves the ad behavior scopes in charge`() {
        val screen = SettingsDocument("screen_empty_scope", """{"splash":{"ads":{"banner":{"behavior":{}}}}}""")
        assertTrue(screen.acceptSuccessfulFetch("""{"splash":{"ads":{"banner":{"behavior":{}}}}}"""))
        assertTrue(AdBehavior.document.acceptSuccessfulFetch(
            """{"banner":{"presentation":{"type":"COLLAPSIBLE"}},"placement_overrides":{}}"""))
        val values = AdBehavior.values("banner", "banner_splash", screen.snapshot, "splash.ads.banner.behavior")
        assertEquals("COLLAPSIBLE", values.string("presentation.type", "NORMAL"))
        val banner = BannerAdConfig.forPlacement("banner_splash").apply { behavior = values }
        assertTrue(banner.bannerType is BannerType.Collapsible)
    }

    @Test fun `a pasted empty screen scope keeps the app asset value of that screen`() {
        val screen = SettingsDocument("screen_asset_scope", """{"slot":{"behavior":{}}}""") { path ->
            if (path == "slot.behavior.reload.resume_debounce_ms") 500L else null
        }
        screen.install("""{"slot":{"behavior":{"reload":{"resume_debounce_ms":900}}}}""", null)
        assertTrue(screen.acceptSuccessfulFetch("""{"slot":{"behavior":{}}}"""))
        assertEquals(900L, AdBehavior.values("native", "native_home", screen.snapshot, "slot.behavior")
            .long("reload.resume_debounce_ms", 500L))
    }

    @Test fun `the shipped sample published with edits reaches keyed placements`() {
        val sample = org.json.JSONObject(java.io.File("src/main/assets/adlogic_defaults/ad_behavior_config.json").readText())
        sample.getJSONObject("banner").getJSONObject("presentation").put("type", "COLLAPSIBLE")
        sample.getJSONObject("native").getJSONObject("presentation").put("auto_shimmer", false)
        sample.getJSONObject("interstitial").getJSONObject("load").put("tier_timeout_ms", 5000)
        assertTrue(sample.getJSONObject("placement_overrides").length() == 0)
        assertTrue(AdBehavior.document.acceptSuccessfulFetch(sample.toString()))
        assertEquals("COLLAPSIBLE", AdBehavior.values("banner", "banner_splash").string("presentation.type", "NORMAL"))
        assertFalse(AdBehavior.values("native", "native_lang").boolean("presentation.auto_shimmer", true))
        assertEquals(5000L, AdBehavior.values("interstitial", "inter_splash").long("load.tier_timeout_ms", 30_000L))
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
