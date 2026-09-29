package com.ads.module.config.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Data driven contract tests for the shared document resolver.  Keeping the matrix here means a
 * newly added primitive, collection, or object field gets the same presence semantics as every
 * existing field without a one-off test for a particular setting name.
 */
class SettingsFieldMatrixTest {
    private val defaults = """
        {
          "string":"sdk",
          "empty_string":"sdk",
          "flag":true,
          "count":7,
          "items":["sdk"],
          "empty_items":["sdk"],
          "object":{"value":"sdk"},
          "empty_object":{}
        }
    """.trimIndent()

    private val appAsset = """
        {
          "string":"asset",
          "empty_string":"asset",
          "flag":true,
          "count":11,
          "items":["asset"],
          "empty_items":[],
          "object":{"value":"asset"},
          "empty_object":{}
        }
    """.trimIndent()

    @Test
    fun `every supported value kind keeps explicit remote values including empty`() {
        val d = SettingsDocument("settings_matrix", defaults).also { it.install(appAsset, null) }
        assertTrue(d.acceptSuccessfulFetch("""
            {
              "string":"remote",
              "empty_string":"",
              "flag":false,
              "count":0,
              "items":["remote"],
              "empty_items":[],
              "object":{"value":"remote"},
              "empty_object":{}
            }
        """.trimIndent()))

        val s = d.snapshot
        assertEquals("remote", s.string("string"))
        assertEquals("", s.string("empty_string"))
        assertFalse(s.boolean("flag"))
        assertEquals(0L, s.long("count"))
        assertEquals(listOf("remote"), s.strings("items"))
        assertEquals(emptyList<String>(), s.strings("empty_items"))
        assertEquals("remote", s.json("object.value"))
        assertEquals("{}", s.json("empty_object"))

        // Presence is part of the value contract; none of these explicit values may be treated as
        // a missing field when a reader asks for a fallback.
        listOf("string", "empty_string", "flag", "count", "items", "empty_items", "object", "empty_object")
            .forEach { path -> assertTrue("remote presence: $path", s.hasRemoteOverride(path)) }
    }

    @Test
    fun `missing remote fields fall back to asset then host code then sdk default`() {
        val d = SettingsDocument("settings_matrix_missing", defaults).also { it.install(appAsset, null) }
        d.acceptSuccessfulFetch("""{"string":"remote"}""")
        val s = d.snapshot
        assertEquals("remote", s.string("string"))
        assertEquals("asset", s.string("empty_string"))
        assertTrue(s.boolean("flag"))
        assertEquals(11L, s.long("count"))
        assertEquals(listOf("asset"), s.strings("items"))
        assertEquals(emptyList<String>(), s.strings("empty_items"))
        assertEquals("asset", s.json("object.value"))
        // The empty object is an explicit asset assignment, distinct from a missing object.
        assertEquals("{}", s.json("empty_object"))

        val noAsset = SettingsDocument("settings_matrix_code", defaults).also { it.install(null, null) }
        noAsset.acceptSuccessfulFetch("{}")
        assertEquals("host", noAsset.snapshot.string("string", "host"))
        assertFalse(noAsset.snapshot.boolean("flag", false))
        assertEquals(0L, noAsset.snapshot.long("count", 0L))
        assertEquals(listOf("host"), noAsset.snapshot.strings("items", listOf("host")))
        assertEquals("{}", noAsset.snapshot.json("object", "{}"))
        // A missing host value finally resolves to the generated SDK default.
        assertEquals("sdk", noAsset.snapshot.string("string"))
        assertTrue(noAsset.snapshot.boolean("flag", true))
        assertEquals(9L, noAsset.snapshot.long("count", 9L))
    }

    @Test
    fun `invalid fields are discarded individually while valid siblings remain`() {
        val d = SettingsDocument("settings_matrix_invalid", defaults).also { it.install(appAsset, null) }
        assertTrue(d.acceptSuccessfulFetch("""
            {
              "string":123,
              "flag":"false",
              "count":"0",
              "items":false,
              "object":[],
              "empty_string":"valid"
            }
        """.trimIndent()))
        val s = d.snapshot
        assertEquals("asset", s.string("string"))
        assertTrue(s.boolean("flag"))
        assertEquals(11L, s.long("count"))
        assertEquals(listOf("asset"), s.strings("items"))
        assertEquals("asset", s.json("object.value"))
        assertEquals("valid", s.string("empty_string"))
        assertNotNull(s.remoteValue("empty_string"))
        assertFalse(s.hasRemoteOverride("flag"))
    }

    @Test
    fun `a successful sparse payload clears deleted fields while fetch failure keeps snapshot`() {
        val d = SettingsDocument("settings_matrix_clear", defaults).also { it.install(appAsset, null) }
        d.acceptSuccessfulFetch("""{"string":"remote","flag":false,"count":0}""")
        assertEquals("remote", d.snapshot.string("string"))
        assertFalse(d.snapshot.boolean("flag"))
        assertEquals(0L, d.snapshot.long("count"))

        // A successful payload is authoritative for the whole document: omitted remote fields are
        // removed, and each omitted field resolves through the lower tiers.
        d.acceptSuccessfulFetch("""{"string":"still-remote"}""")
        assertEquals("still-remote", d.snapshot.string("string"))
        assertTrue(d.snapshot.boolean("flag"))
        assertEquals(11L, d.snapshot.long("count"))

        // Malformed JSON is a failed fetch, so the last valid remote snapshot remains in place.
        assertFalse(d.acceptSuccessfulFetch("{broken"))
        assertEquals("still-remote", d.snapshot.string("string"))
        assertTrue(d.snapshot.boolean("flag"))

        // schema_version carries no field assignments. A successful payload containing only that
        // marker has the same whole-document clear semantics as {}.
        d.acceptSuccessfulFetch("""{"schema_version":1}""")
        assertEquals("asset", d.snapshot.string("string"))
        assertTrue(d.snapshot.boolean("flag"))
        assertEquals(11L, d.snapshot.long("count"))
    }

    @Test
    fun `source precedence beats scope breadth and keeps explicit empty at a broad scope`() {
        val d = SettingsDocument(
            "settings_matrix_scope",
            """{"wide":{"value":"sdk"},"narrow":{"value":"sdk"}}""",
        ).also {
            it.install("""{"narrow":{"value":"asset"}}}""", null)
        }
        d.acceptSuccessfulFetch("""{"wide":{"value":""}}""")
        assertEquals(
            "",
            d.snapshot.scoped("narrow.value", "wide.value").string("code"),
        )

        // The narrow remote scope wins over an asset value at the broad scope as well.
        d.install("""{"wide":{"value":"asset"}}}""", null)
        d.acceptSuccessfulFetch("""{"narrow":{"value":"remote"}}""")
        assertEquals(
            "remote",
            d.snapshot.scoped("narrow.value", "wide.value").string("code"),
        )
    }
}
