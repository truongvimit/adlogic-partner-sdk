package com.ads.module.update

import org.junit.Assert.*
import org.junit.Test

class ForceUpdateConfigTest {
    @Test fun `only explicit enabled true can turn update on`() {
        assertFalse(ForceUpdateConfig(minVersionCode = 101, force = true).needsUpdate(100))
        for (json in listOf(
            """{"minVersionCode":101,"force":true}""",
            """{"enabled":false,"minVersionCode":101,"force":true}""",
            """{}""",
        )) assertFalse(ForceUpdateConfig.fromJson(json)!!.needsUpdate(100))
        assertNull(ForceUpdateConfig.fromJson("""{"enabled":"true","minVersionCode":101}"""))
    }

    @Test fun `force never blocks an up to date app`() {
        val config = ForceUpdateConfig.fromJson("""{"enabled":true,"minVersionCode":101,"force":true}""")!!
        assertTrue(config.isRequired(100))
        assertFalse(config.needsUpdate(101))
        assertFalse(config.isRequired(101))
        assertFalse(config.isRequired(102))
    }
    @Test fun `optional policy prompts only older versions and zero disables`() {
        val config = ForceUpdateConfig(enabled = true, minVersionCode = 101)
        assertTrue(config.needsUpdate(100))
        assertFalse(config.isRequired(100))
        assertFalse(config.needsUpdate(101))
        assertFalse(ForceUpdateConfig(force = true).needsUpdate(100))
    }
    @Test fun `blank store is valid and version codes support long`() {
        val config = ForceUpdateConfig.fromJson("""{"enabled":true,"minVersionCode":3000000000,"force":true}""")!!
        assertEquals("", config.storeLink)
        assertTrue(config.isRequired(100))
        assertFalse(config.isRequired(3000000000L))
    }
    @Test fun `malformed policies rejected rather than coerced`() {
        listOf("", "[]", "null", """{"force":"true"}""", """{"minVersionCode":-1}""",
            """{"minVersionCode":1.5}""", """{"minVersionCode":"101"}""",
            """{"storeLink":true}""").forEach { assertNull(it, ForceUpdateConfig.fromJson(it)) }
        assertEquals(ForceUpdateConfig(), ForceUpdateConfig.fromJson("{}"))
    }
}
