package com.ads.module.config.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SettingsDocumentTest {
    private fun document(name: String = "settings_test") = SettingsDocument(name, BundledAdBehavior.JSON)

    @Test fun `missing null wrong type and invalid enum use concrete defaults`() {
        val d = document()
        assertTrue(d.acceptSuccessfulFetch("""{"native":{"reload":{"on_ad_click":null,"resume_debounce_ms":"9"}},"banner":{"presentation":{"type":"TYPO"}}}"""))
        assertTrue(d.snapshot.boolean("native.reload.on_ad_click"))
        assertEquals(500L, d.snapshot.long("native.reload.resume_debounce_ms"))
        assertEquals("NORMAL", d.snapshot.string("banner.presentation.type"))
        assertEquals(8_000L, d.snapshot.long("interstitial.load_and_show.wait_timeout_ms"))
    }

    @Test fun `false and zero are explicit values and local options remain fallback`() {
        val d = document()
        assertTrue(d.acceptSuccessfulFetch("""{"native":{"reload":{"on_ad_click":false,"resume_debounce_ms":0}}}"""))
        assertFalse(d.snapshot.boolean("native.reload.on_ad_click", true))
        assertEquals(0L, d.snapshot.long("native.reload.resume_debounce_ms", 750L))
        assertEquals(900L, d.snapshot.long("banner.reload.resume_debounce_ms", 900L))
        assertEquals(500L, d.localSnapshot.long("native.reload.resume_debounce_ms"))
    }

    @Test fun `corrupt or unsupported document preserves last success and removal restores local`() {
        val d = document()
        d.acceptSuccessfulFetch("""{"banner":{"reload":{"interval_ms":21000}}}""")
        assertFalse(d.acceptSuccessfulFetch("{broken"))
        assertFalse(d.acceptSuccessfulFetch("""{"schema_version":2}"""))
        assertEquals(21_000L, d.snapshot.long("banner.reload.interval_ms"))
        d.acceptSuccessfulFetch("{}")
        assertEquals(15_000L, d.snapshot.long("banner.reload.interval_ms"))
        d.acceptSuccessfulFetch(null)
        assertEquals(18_000L, d.snapshot.long("banner.reload.interval_ms", 18_000L))
    }

    @Test fun `last valid remote survives restart and deletion removes disk assignment`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("adlogic_settings_settings_restart", 0).edit().clear().commit()
        val d = document("settings_restart").also { it.initialize(context) }
        d.acceptSuccessfulFetch("""{"native":{"reload":{"resume_debounce_ms":0,"on_ad_click":false}}}""")
        val restarted = document("settings_restart").also { it.initialize(context) }
        assertEquals(0L, restarted.snapshot.long("native.reload.resume_debounce_ms"))
        assertFalse(restarted.snapshot.boolean("native.reload.on_ad_click"))
        restarted.acceptSuccessfulFetch(null)
        assertTrue(document("settings_restart").also { it.initialize(context) }.snapshot.boolean("native.reload.on_ad_click"))
    }

    @Test fun `bundled asset numbers do not override custom host defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("adlogic_settings_ad_behavior_config", 0).edit().clear().commit()
        val d = SettingsDocument("ad_behavior_config", BundledAdBehavior.VALUES)
        d.initialize(context)
        assertFalse(d.snapshot.hasOverride("banner"))
        assertFalse(d.snapshot.hasOverride("native"))
        assertFalse(d.snapshot.hasOverride("app_open"))
        assertEquals(22_000L, d.snapshot.long("banner.reload.interval_ms", 22_000L))
    }

    @Test fun `identical accepted payload preserves the immutable snapshot`() {
        val d = document()
        val json = """{"native":{"reload":{"on_ad_click":false}}}"""
        d.acceptSuccessfulFetch(json)
        val previous = d.snapshot
        d.acceptSuccessfulFetch(json)
        assertSame(previous, d.snapshot)
        d.acceptSuccessfulFetch("{broken")
        assertSame(previous, d.snapshot)
        d.acceptSuccessfulFetch(null)
        assertNotSame(previous, d.snapshot)
        assertFalse(previous.boolean("native.reload.on_ad_click"))
        assertTrue(d.snapshot.boolean("native.reload.on_ad_click"))
    }

    @Test fun `invalid numeric ranges do not reach scheduling or enum constructors`() {
        val d = document()
        d.acceptSuccessfulFetch("""{"native":{"load":{"tier_timeout_ms":0}},"banner":{"reload":{"interval_ms":1}},"app_open":{"cache":{"max_age_ms":999999999}},"interstitial":{"presentation":{"next_screen_timing":"AUTO"}}}""")
        assertEquals(30_000L, d.snapshot.long("native.load.tier_timeout_ms"))
        assertEquals(15_000L, d.snapshot.long("banner.reload.interval_ms"))
        assertEquals(14_400_000L, d.snapshot.long("app_open.cache.max_age_ms"))
        assertEquals("AFTER_AD", d.snapshot.string("interstitial.presentation.next_screen_timing"))
    }
}
