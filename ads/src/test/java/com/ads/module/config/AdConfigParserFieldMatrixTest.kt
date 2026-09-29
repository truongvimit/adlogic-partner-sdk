package com.ads.module.config

import com.ads.module.helper.adnative.NativeClickAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Data-driven coverage for every field in the ad_config.json document. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AdConfigParserFieldMatrixTest {
    @Test
    fun `explicit empty false zero and empty lists survive parsing`() {
        val config = AdRemoteConfig.fromJson("""
            {
              "matrix": {
                "id":"",
                "isEnable":false,
                "enable_ua_check":true,
                "reloadIntervalSeconds":0,
                "app_resume_load_delay_ms":0,
                "colorCTA":"",
                "heightCTA":0,
                "positionCTA":"",
                "components":[],
                "ids":[],
                "click_action":"none"
              }
            }
        """.trimIndent())!!
        val unit = config.unit("matrix")
        assertEquals("", unit.id)
        assertFalse(unit.isEnable)
        assertTrue(unit.enableUaCheck)
        assertEquals(0, unit.reloadIntervalSeconds)
        assertEquals(0L, unit.appResumeLoadDelayMs)
        assertEquals("", unit.colorCTA)
        assertEquals(0, unit.heightCTA)
        assertEquals("", unit.positionCTA)
        assertEquals(emptyList<String>(), unit.components)
        assertEquals(emptyList<String>(), unit.ids)
        assertEquals(NativeClickAction.NONE, unit.clickAction)
    }

    @Test
    fun `missing fields use parser defaults without affecting sibling values`() {
        val unit = AdRemoteConfig.fromJson("""{"matrix":{"id":"asset","isEnable":true}}""")!!.unit("matrix")
        assertEquals("asset", unit.id)
        assertTrue(unit.isEnable)
        assertFalse(unit.enableUaCheck)
        assertEquals(null, unit.reloadIntervalSeconds)
        assertEquals(AdRemoteConfig.DEFAULT_APP_RESUME_LOAD_DELAY_MS, unit.appResumeLoadDelayMs)
        assertEquals("default", unit.colorCTA)
        assertEquals(40, unit.heightCTA)
        assertEquals(null, unit.positionCTA)
        assertEquals(listOf("icon_headline", "body", "media", "cta"), unit.components)
        assertEquals(emptyList<String>(), unit.ids)
        assertEquals(null, unit.clickAction)
    }

    @Test
    fun `wrong types and unknown fields fall back field by field`() {
        val unit = AdRemoteConfig.fromJson("""
            {
              "matrix": {
                "id":"valid",
                "isEnable":true,
                "enable_ua_check":"not-a-boolean",
                "reloadIntervalSeconds":"not-a-number",
                "app_resume_load_delay_ms":"bad",
                "colorCTA":false,
                "heightCTA":{},
                "positionCTA":123,
                "components":["media",3,false],
                "ids":["high",3,null],
                "click_action":3,
                "ignored":{"value":"drop"}
              }
            }
        """.trimIndent())!!.unit("matrix")
        assertEquals("valid", unit.id)
        assertTrue(unit.isEnable)
        assertFalse(unit.enableUaCheck)
        assertEquals(null, unit.reloadIntervalSeconds)
        assertEquals(AdRemoteConfig.DEFAULT_APP_RESUME_LOAD_DELAY_MS, unit.appResumeLoadDelayMs)
        assertEquals("default", unit.colorCTA)
        assertEquals(40, unit.heightCTA)
        assertEquals(null, unit.positionCTA)
        // A list containing a wrong element is one invalid field; the whole field falls through
        // to its lower-tier default instead of silently filtering the payload.
        assertEquals(listOf("icon_headline", "body", "media", "cta"), unit.components)
        assertEquals(emptyList<String>(), unit.ids)
        assertEquals(null, unit.clickAction)
    }

    @Test
    fun `every click action value parses and an unknown one is ignored alone`() {
        NativeClickAction.entries.forEach { action ->
            val config = AdRemoteConfig.fromJson("""{"matrix":{"click_action":"${action.remoteValue}"}}""")!!
            assertEquals(action, config.unit("matrix").clickAction)
            assertTrue(config.fieldsFor("matrix").contains("click_action"))
        }
        for (raw in listOf("\"typo\"", "\"RELOAD\"", "\"\"", "null", "true")) {
            val config = AdRemoteConfig.fromJson("""{"matrix":{"id":"kept","click_action":$raw}}""")!!
            assertEquals(raw, null, config.unit("matrix").clickAction)
            assertEquals(raw, "kept", config.unit("matrix").id)
            assertFalse(raw, config.fieldsFor("matrix").contains("click_action"))
        }
        val absent = AdRemoteConfig.fromJson("""{"matrix":{"id":"kept"}}""")!!
        assertEquals(null, absent.unit("matrix").clickAction)
        assertFalse(absent.fieldsFor("matrix").contains("click_action"))
    }

    @Test
    fun `remote empty document is representable and unknown top-level values are skipped`() {
        assertTrue(AdRemoteConfig.fromJson("{}")!!.ads.isEmpty())
        assertTrue(AdRemoteConfig.fromJson("""{"ignored":false,"matrix":null}""")!!.ads.isEmpty())
    }

    @Test
    fun `nullable reload interval keeps presence so it can clear a lower tier`() {
        val config = AdRemoteConfig.fromJson(
            """{"matrix":{"id":"remote","isEnable":true,"reloadIntervalSeconds":null}}""",
        )!!
        assertEquals(null, config.unit("matrix").reloadIntervalSeconds)
        assertTrue(config.fieldsFor("matrix").contains("reloadIntervalSeconds"))
    }
}
