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
        assertEquals(AdUnitConfig.DEFAULT_HEIGHT_CTA, unit.heightCTA)
        assertEquals(AdUnitConfig.DEFAULT_COMPONENTS, unit.components)
        assertEquals(emptyList<String>(), unit.ids)
        assertEquals(null, unit.clickAction)
    }

    @Test
    fun `colorBackground takes a color, null or blank keep the XML background, anything else is ignored alone`() {
        fun parse(raw: String) = AdRemoteConfig.fromJson("""{"matrix":{"id":"kept","colorBackground":$raw}}""")!!

        assertEquals("#102030", parse("\"#102030\"").unit("matrix").colorBackground)
        for (raw in listOf("null", "\"\"", "\"default\"")) {
            val config = parse(raw)
            assertEquals(raw, null, config.unit("matrix").toNativeStyle().backgroundColor)
            assertTrue(raw, config.fieldsFor("matrix").contains("colorBackground"))
        }
        for (raw in listOf("\"not-a-color\"", "3", "true")) {
            val config = parse(raw)
            assertEquals(raw, "default", config.unit("matrix").colorBackground)
            assertEquals(raw, "kept", config.unit("matrix").id)
            assertFalse(raw, config.fieldsFor("matrix").contains("colorBackground"))
        }
        assertEquals("default", AdRemoteConfig.fromJson("""{"matrix":{"id":"kept"}}""")!!.unit("matrix").colorBackground)
    }

    @Test
    fun `remote colorBackground outranks the app's, an omitted one keeps it and null clears it`() {
        try {
            AdRemoteConfig.updateCodeFromJson("""{"native_lang":{"id":"a","isEnable":true,"colorBackground":"#111111"}}""")
            AdRemoteConfig.applyRemote(AdRemoteConfig.fromJson("""{"native_lang":{"colorBackground":"#222222"}}""")!!)
            assertEquals("#222222", AdRemoteConfig.getInstance().unit("native_lang").colorBackground)
            AdRemoteConfig.applyRemote(AdRemoteConfig.fromJson("""{"native_lang":{"colorCTA":"#000000"}}""")!!)
            assertEquals("#111111", AdRemoteConfig.getInstance().unit("native_lang").colorBackground)
            AdRemoteConfig.applyRemote(AdRemoteConfig.fromJson("""{"native_lang":{"colorBackground":null}}""")!!)
            assertEquals(null, AdRemoteConfig.getInstance().unit("native_lang").toNativeStyle().backgroundColor)
        } finally {
            AdRemoteConfig.reset()
        }
    }

    @Test
    fun `colorAdBadge and colorAdBadgeText parse like colorBackground and merge per field`() {
        for (field in listOf("colorAdBadge", "colorAdBadgeText")) {
            fun parse(raw: String) = AdRemoteConfig.fromJson("""{"matrix":{"id":"kept","$field":$raw}}""")!!
            fun AdUnitConfig.value() = if (field == "colorAdBadge") colorAdBadge else colorAdBadgeText

            assertEquals(field, "#102030", parse("\"#102030\"").unit("matrix").value())
            for (raw in listOf("null", "\"\"", "\"default\"")) {
                val style = parse(raw).unit("matrix").toNativeStyle()
                assertEquals("$field=$raw", null, if (field == "colorAdBadge") style.adBadgeColor else style.adBadgeTextColor)
            }
            val invalid = parse("\"not-a-color\"")
            assertEquals(field, "default", invalid.unit("matrix").value())
            assertFalse(field, invalid.fieldsFor("matrix").contains(field))
        }
        try {
            AdRemoteConfig.updateCodeFromJson("""{"native_lang":{"id":"a","isEnable":true,"colorAdBadge":"#111111","colorAdBadgeText":"#FFFFFF"}}""")
            AdRemoteConfig.applyRemote(AdRemoteConfig.fromJson("""{"native_lang":{"colorAdBadge":"#222222"}}""")!!)
            val unit = AdRemoteConfig.getInstance().unit("native_lang")
            assertEquals("#222222", unit.colorAdBadge)
            assertEquals("#FFFFFF", unit.colorAdBadgeText)
        } finally {
            AdRemoteConfig.reset()
        }
    }

    @Test
    fun `the default components put the CTA last`() {
        assertEquals("cta", AdUnitConfig.DEFAULT_COMPONENTS.last())
    }

    @Test
    fun `an absent heightCTA is the design's 44dp`() {
        assertEquals(44, AdUnitConfig.DEFAULT_HEIGHT_CTA)
        assertEquals(44, AdUnitConfig("x", true).heightCTA)
        assertEquals(44, AdRemoteConfig.fromJson("""{"matrix":{"id":"x"}}""")!!.unit("matrix").heightCTA)
    }

    @Test
    fun `a removed positionCTA is an unknown field and leaves its siblings applied`() {
        for (raw in listOf("\"TOP\"", "\"BOTTOM\"", "null")) {
            val config = AdRemoteConfig.fromJson("""{"matrix":{"id":"kept","positionCTA":$raw,"components":["cta","media"]}}""")!!
            assertEquals(raw, "kept", config.unit("matrix").id)
            assertEquals(raw, listOf("cta", "media"), config.unit("matrix").components)
            assertFalse(raw, config.fieldsFor("matrix").contains("positionCTA"))
        }
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
        assertEquals(AdUnitConfig.DEFAULT_HEIGHT_CTA, unit.heightCTA)
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
    fun `templateId takes a positive number, null clears it and anything else is ignored alone`() {
        fun parse(raw: String) = AdRemoteConfig.fromJson("""{"matrix":{"id":"kept","templateId":$raw}}""")!!

        assertEquals(3, parse("3").unit("matrix").templateId)
        assertTrue(parse("3").fieldsFor("matrix").contains("templateId"))
        assertEquals(null, parse("null").unit("matrix").templateId)
        assertTrue(parse("null").fieldsFor("matrix").contains("templateId"))
        for (raw in listOf("0", "-1", "\"3\"", "true", "2.5", "{}")) {
            val config = parse(raw)
            assertEquals(raw, null, config.unit("matrix").templateId)
            assertEquals(raw, "kept", config.unit("matrix").id)
            assertFalse(raw, config.fieldsFor("matrix").contains("templateId"))
        }
        val absent = AdRemoteConfig.fromJson("""{"matrix":{"id":"kept"}}""")!!
        assertEquals(null, absent.unit("matrix").templateId)
        assertFalse(absent.fieldsFor("matrix").contains("templateId"))
    }

    @Test
    fun `remote templateId outranks the app's and an omitted one keeps it`() {
        try {
            AdRemoteConfig.updateCodeFromJson("""{"native_lang":{"id":"a","isEnable":true,"templateId":2}}""")
            assertEquals(2, AdRemoteConfig.getInstance().unit("native_lang").templateId)
            AdRemoteConfig.applyRemote(AdRemoteConfig.fromJson("""{"native_lang":{"templateId":3}}""")!!)
            assertEquals(3, AdRemoteConfig.getInstance().unit("native_lang").templateId)
            AdRemoteConfig.applyRemote(AdRemoteConfig.fromJson("""{"native_lang":{"colorCTA":"#000000"}}""")!!)
            assertEquals(2, AdRemoteConfig.getInstance().unit("native_lang").templateId)
            AdRemoteConfig.applyRemote(AdRemoteConfig.fromJson("""{"native_lang":{"templateId":null}}""")!!)
            assertEquals(null, AdRemoteConfig.getInstance().unit("native_lang").templateId)
        } finally {
            AdRemoteConfig.reset()
        }
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
