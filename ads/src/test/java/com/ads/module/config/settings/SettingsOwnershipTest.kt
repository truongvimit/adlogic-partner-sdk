package com.ads.module.config.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.helper.banner.BannerAdConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SettingsOwnershipTest {
    @After fun cleanup() { AdBehavior.document.acceptSuccessfulFetch(null); AdRemoteConfig.reset() }

    @Test fun `ad config solely owns banner cadence and app open initial delay`() {
        val banner = BannerAdConfig.forPlacement("banner_home").apply { autoReloadTime = 22000 }
        AdRemoteConfig.update(AdRemoteConfig(mapOf(
            "banner_home" to AdUnitConfig("banner", true, reloadIntervalSeconds = 31),
            "open_resume" to AdUnitConfig("open", true, appResumeLoadDelayMs = 7000),
        )))
        AdBehavior.document.acceptSuccessfulFetch("""{"banner":{"reload":{"interval_ms":1000}},"app_open":{"load":{"background_delay_ms":1},"enabled":false,"presentation":{"excluded_hosts":["android.app.Activity"]}},"native":{"presentation":{"cta_corner_radius_dp":0}}}""")
        assertEquals(31000L, banner.autoReloadTime)
        assertEquals(7000L, AdRemoteConfig.getInstance().appResumeLoadDelayMs)
        assertFalse(AdBehavior.document.snapshot.hasOverride("banner.reload.interval_ms"))
        assertFalse(AdBehavior.document.snapshot.hasOverride("app_open.enabled"))
        assertEquals(0L, AdBehavior.number("native.presentation.cta_corner_radius_dp"))
        AdRemoteConfig.update(AdRemoteConfig(mapOf("banner_home" to AdUnitConfig("banner", true, reloadIntervalSeconds = -1))))
        assertEquals(22000L, banner.autoReloadTime)
        assertEquals(2000L, AdRemoteConfig.getInstance().appResumeLoadDelayMs)
    }

    @Test fun `same named sparse app asset preserves explicit defaults and falls back after remote removal`() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val assets = mock(AssetManager::class.java)
        val assetJson = """{"banner":{"reload":{"allowed":false}},"native":{"reload":{"resume_debounce_ms":0}}}"""
        `when`(assets.open("partner_settings.json")).thenAnswer { assetJson.byteInputStream() }
        val context = object : ContextWrapper(app) { override fun getAssets(): AssetManager = assets }
        app.getSharedPreferences("adlogic_settings_partner_settings", 0).edit().clear().commit()
        val d = SettingsDocument("partner_settings", BundledAdBehavior.VALUES)
        d.initialize(context)
        assertFalse(d.snapshot.boolean("banner.reload.allowed", true)) // Explicit false equals SDK default.
        assertEquals(0L, d.snapshot.long("native.reload.resume_debounce_ms", 999))
        assertEquals(30000L, d.snapshot.long("native.load.tier_timeout_ms"))
        d.acceptSuccessfulFetch("""{"banner":{"reload":{"allowed":true}},"native":{"reload":{"resume_debounce_ms":300}}}""")
        assertTrue(d.snapshot.boolean("banner.reload.allowed"))
        assertFalse(d.acceptSuccessfulFetch("{broken"))
        assertEquals(300L, d.snapshot.long("native.reload.resume_debounce_ms"))
        d.acceptSuccessfulFetch(null)
        assertFalse(d.snapshot.boolean("banner.reload.allowed", true))
        assertEquals(0L, d.snapshot.long("native.reload.resume_debounce_ms"))
    }
}
