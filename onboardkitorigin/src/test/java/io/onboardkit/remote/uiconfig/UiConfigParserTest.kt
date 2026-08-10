package io.onboardkit.remote.uiconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiConfigParserTest {

    @Test
    fun `empty payloads produce empty config without errors`() {
        val config = UiConfigParser.parse("", "")
        assertTrue(config.steps.isEmpty())
        assertTrue(config.errors.isEmpty())
    }

    @Test
    fun `steps parse as ordered array`() {
        val json = """
            {"steps":[
              {"id":"ob2","order":2,"title":"Second"},
              {"id":"ob1","order":1,"title":"First"}
            ]}
        """.trimIndent()
        val config = UiConfigParser.parse(json, "")
        assertEquals(listOf("ob1", "ob2"), config.steps.map { it.stepId })
        assertEquals("First", config.styleFor("ob1")?.title)
    }

    @Test
    fun `one broken step drops only itself`() {
        val json = """
            {"steps":[
              {"id":"ob1","title":"Good"},
              {"title":"No id — dropped"},
              {"id":"ob4","title":"Also good"}
            ]}
        """.trimIndent()
        val config = UiConfigParser.parse(json, "")
        assertEquals(listOf("ob1", "ob4"), config.steps.map { it.stepId })
        assertTrue(config.errors.any { it.contains("missing id") })
    }

    @Test
    fun `lenient boolean accepts console typos`() {
        val json = """
            {"steps":[
              {"id":"a","is_image":"TRUE "},
              {"id":"b","is_image":true},
              {"id":"c","is_image":"1"}
            ]}
        """.trimIndent()
        val config = UiConfigParser.parse(json, "")
        assertTrue(config.steps.all { it.isImage })
    }

    @Test
    fun `garbage boolean becomes unknown and is guessed from extension`() {
        val json = """
            {"steps":[
              {"id":"img","is_image":"maybe","content":"https://cdn/x.png"},
              {"id":"vid","is_image":"maybe","content":"https://cdn/x.mp4"}
            ]}
        """.trimIndent()
        val config = UiConfigParser.parse(json, "")
        assertEquals(true, config.styleFor("img")?.isImage)
        assertEquals(false, config.styleFor("vid")?.isImage)
    }

    @Test
    fun `extension conflicting with declared type drops the step`() {
        val json = """
            {"steps":[{"id":"bad","is_image":"true","content":"https://cdn/x.mp4"}]}
        """.trimIndent()
        val config = UiConfigParser.parse(json, "")
        assertNull(config.styleFor("bad"))
        assertTrue(config.errors.any { it.contains("is_image") })
    }

    @Test
    fun `disabled step is removed silently`() {
        val json = """{"steps":[{"id":"off","enabled":"false"}]}"""
        val config = UiConfigParser.parse(json, "")
        assertNull(config.styleFor("off"))
    }

    @Test
    fun `two-state button labels stay independent`() {
        val json = """
            {"steps":[{"id":"ob1","button_next_content":"Next","button_last_content":"Get Started"}]}
        """.trimIndent()
        val style = UiConfigParser.parse(json, "").styleFor("ob1")
        assertEquals("Next", style?.buttonNextText)
        assertEquals("Get Started", style?.buttonLastText)
    }

    @Test
    fun `token without id or value is dropped with error`() {
        val tokens = """
            {"custom_colors":[
              {"color_id":"primary","color_value":"#FF375E"},
              {"color_id":"","color_value":"#000000"},
              {"color_id":"noValue"}
            ]}
        """.trimIndent()
        val config = UiConfigParser.parse("""{"steps":[{"id":"s"}]}""", tokens)
        assertEquals(2, config.errors.size)
    }

    @Test
    fun `malformed json reports error but does not throw`() {
        val config = UiConfigParser.parse("{not json", "{also broken")
        assertTrue(config.steps.isEmpty())
        assertEquals(2, config.errors.size)
    }
}
