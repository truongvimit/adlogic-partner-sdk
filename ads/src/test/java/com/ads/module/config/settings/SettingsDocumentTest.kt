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
        assertTrue(d.acceptSuccessfulFetch("""{"native":{"presentation":{"auto_shimmer":null},"reload":{"resume_debounce_ms":"9"}},"banner":{"presentation":{"type":"TYPO"}}}"""))
        assertTrue(d.snapshot.boolean("native.presentation.auto_shimmer"))
        assertEquals(500L, d.snapshot.long("native.reload.resume_debounce_ms"))
        assertEquals("NORMAL", d.snapshot.string("banner.presentation.type"))
        assertEquals(8_000L, d.snapshot.long("interstitial.load_and_show.wait_timeout_ms"))
    }

    @Test fun `false and zero are explicit values and local options remain fallback`() {
        val d = document()
        assertTrue(d.acceptSuccessfulFetch("""{"native":{"presentation":{"auto_shimmer":false},"reload":{"resume_debounce_ms":0}}}"""))
        assertFalse(d.snapshot.boolean("native.presentation.auto_shimmer", true))
        assertEquals(0L, d.snapshot.long("native.reload.resume_debounce_ms", 750L))
        assertEquals(900L, d.snapshot.long("banner.reload.resume_debounce_ms", 900L))
        assertEquals(500L, d.localSnapshot.long("native.reload.resume_debounce_ms"))
    }

    @Test fun `corrupt or unsupported document preserves last success and removal restores local`() {
        val d = document()
        d.acceptSuccessfulFetch("""{"native":{"reload":{"interval_ms":21000}}}""")
        assertFalse(d.acceptSuccessfulFetch("{broken"))
        assertTrue(d.acceptSuccessfulFetch("""{"schema_version":2,"native":{"presentation":{"auto_shimmer":false}}}"""))
        assertEquals(15_000L, d.snapshot.long("native.reload.interval_ms"))
        d.acceptSuccessfulFetch("{}")
        assertEquals(15_000L, d.snapshot.long("native.reload.interval_ms"))
        d.acceptSuccessfulFetch(null)
        assertEquals(18_000L, d.snapshot.long("native.reload.interval_ms", 18_000L))
    }

    @Test fun `last valid remote survives restart and deletion removes disk assignment`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("adlogic_settings_settings_restart", 0).edit().clear().commit()
        val d = document("settings_restart").also { it.initialize(context) }
        d.acceptSuccessfulFetch("""{"native":{"reload":{"resume_debounce_ms":0},"presentation":{"auto_shimmer":false}}}""")
        val restarted = document("settings_restart").also { it.initialize(context) }
        assertEquals(0L, restarted.snapshot.long("native.reload.resume_debounce_ms"))
        assertFalse(restarted.snapshot.boolean("native.presentation.auto_shimmer"))
        restarted.acceptSuccessfulFetch(null)
        assertTrue(document("settings_restart").also { it.initialize(context) }.snapshot.boolean("native.presentation.auto_shimmer"))
    }

    @Test fun `bundled asset numbers do not override custom host defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("adlogic_settings_ad_behavior_config", 0).edit().clear().commit()
        val d = SettingsDocument("ad_behavior_config", BundledAdBehavior.VALUES)
        d.initialize(context)
        assertFalse(d.snapshot.hasOverride("banner"))
        assertFalse(d.snapshot.hasOverride("native"))
        assertFalse(d.snapshot.hasOverride("app_open"))
        assertEquals(22_000L, d.snapshot.long("native.reload.interval_ms", 22_000L))
    }

    @Test fun `identical accepted payload preserves the immutable snapshot`() {
        val d = document()
        val json = """{"native":{"presentation":{"auto_shimmer":false}}}"""
        d.acceptSuccessfulFetch(json)
        val previous = d.snapshot
        d.acceptSuccessfulFetch(json)
        assertSame(previous, d.snapshot)
        d.acceptSuccessfulFetch("{broken")
        assertSame(previous, d.snapshot)
        d.acceptSuccessfulFetch(null)
        assertNotSame(previous, d.snapshot)
        assertFalse(previous.boolean("native.presentation.auto_shimmer"))
        assertTrue(d.snapshot.boolean("native.presentation.auto_shimmer"))
    }

    private fun nativeFlowDocument() = SettingsDocument("native_flow_test", """
        {"schema_version":1,"native_flow":{"type":"PER_SCREEN","pool":{
          "locale_apply":"IN_PLACE","exit":{"next_screen_timing":"AFTER_AD"},
          "stuck_loading_log_ms":60000,"pager":{"offscreen_pages":0},
          "lfo":{"confirm_button":{"reveal_delay_ms":3000}},"select":{"next_reveal_delay_ms":3000},
          "onboarding":{"fullscreen":{"skip":{"delay_ms":2000},"auto_next":{"delay_ms":10000}}}}}}
    """.trimIndent())

    @Test fun `native flow type and locale apply accept only their own values`() {
        for (type in listOf("PER_SCREEN", "SHARED_POOL")) {
            val d = nativeFlowDocument()
            assertTrue(d.acceptSuccessfulFetch("""{"native_flow":{"type":"$type"}}"""))
            assertEquals(type, d.snapshot.remoteValue("native_flow.type"))
        }
        for (apply in listOf("IN_PLACE", "RECREATE")) {
            val d = nativeFlowDocument()
            assertTrue(d.acceptSuccessfulFetch("""{"native_flow":{"pool":{"locale_apply":"$apply"}}}"""))
            assertEquals(apply, d.snapshot.remoteValue("native_flow.pool.locale_apply"))
        }
        for (raw in listOf("shared_pool", "POOL", "", "native_resume")) {
            val d = nativeFlowDocument()
            assertTrue(d.acceptSuccessfulFetch("""{"native_flow":{"type":"$raw","pool":{"locale_apply":"$raw"}}}"""))
            assertNull(raw, d.snapshot.remoteValue("native_flow.type"))
            assertNull(raw, d.snapshot.remoteValue("native_flow.pool.locale_apply"))
            assertEquals("PER_SCREEN", d.snapshot.string("native_flow.type"))
            assertEquals("IN_PLACE", d.snapshot.string("native_flow.pool.locale_apply"))
        }
    }

    @Test fun `the pool exit timing follows the next_screen_timing suffix rule`() {
        val d = nativeFlowDocument()
        assertTrue(d.acceptSuccessfulFetch("""{"native_flow":{"pool":{"exit":{"next_screen_timing":"UNDER_AD"}}}}"""))
        assertEquals("UNDER_AD", d.snapshot.string("native_flow.pool.exit.next_screen_timing"))
        assertTrue(d.acceptSuccessfulFetch("""{"native_flow":{"pool":{"exit":{"next_screen_timing":"AUTO"}}}}"""))
        assertNull(d.snapshot.remoteValue("native_flow.pool.exit.next_screen_timing"))
        assertEquals("AFTER_AD", d.snapshot.string("native_flow.pool.exit.next_screen_timing"))
    }

    @Test fun `zero pool numbers are values, not rejected timeouts`() {
        val paths = listOf("stuck_loading_log_ms", "pager.offscreen_pages", "lfo.confirm_button.reveal_delay_ms",
            "select.next_reveal_delay_ms", "onboarding.fullscreen.skip.delay_ms", "onboarding.fullscreen.auto_next.delay_ms")
            .map { "native_flow.pool.$it" }
        val d = nativeFlowDocument()
        val payload = org.json.JSONObject()
        paths.forEach { path ->
            var node = payload
            path.split('.').dropLast(1).forEach { key -> node = node.optJSONObject(key) ?: org.json.JSONObject().also { node.put(key, it) } }
            node.put(path.substringAfterLast('.'), 0)
        }
        assertTrue(d.acceptSuccessfulFetch(payload.toString()))
        paths.forEach { assertEquals(it, 0L, (d.snapshot.remoteValue(it) as Number).toLong()) }
        assertTrue(d.acceptSuccessfulFetch("""{"native_flow":{"pool":{"stuck_loading_log_ms":-1}}}"""))
        assertNull(d.snapshot.remoteValue("native_flow.pool.stuck_loading_log_ms"))
    }

    @Test fun `invalid numeric ranges do not reach scheduling or enum constructors`() {
        val d = document()
        d.acceptSuccessfulFetch("""{"native":{"load":{"tier_timeout_ms":0},"reload":{"interval_ms":0}},"app_open":{"cache":{"max_age_ms":999999999}},"interstitial":{"presentation":{"next_screen_timing":"AUTO"}}}""")
        assertEquals(30_000L, d.snapshot.long("native.load.tier_timeout_ms"))
        assertEquals(15_000L, d.snapshot.long("native.reload.interval_ms"))
        assertEquals(14_400_000L, d.snapshot.long("app_open.cache.max_age_ms"))
        assertEquals("AFTER_AD", d.snapshot.string("interstitial.presentation.next_screen_timing"))
    }
}
