package io.onboardkit.remote.uiconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteQuestionParserTest {

    @Test
    fun `blank or broken payload returns null`() {
        assertNull(RemoteQuestionParser.parse(""))
        assertNull(RemoteQuestionParser.parse("{broken"))
    }

    @Test
    fun `bad options drop individually instead of killing the screen`() {
        val json = """
            {"title":"Pick","options":[
              {"id":"a","title":"Alpha"},
              {"id":"","title":"No id"},
              {"id":"c"},
              {"id":"d","title":"Delta","image_url":"https://cdn/d.png"}
            ]}
        """.trimIndent()
        val question = RemoteQuestionParser.parse(json)
        assertEquals(listOf("a", "d"), question?.options?.map { it.id })
        assertEquals("Pick", question?.title)
    }

    @Test
    fun `duplicate ids keep first occurrence`() {
        val json = """
            {"options":[
              {"id":"a","title":"First"},
              {"id":"a","title":"Second"}
            ]}
        """.trimIndent()
        val question = RemoteQuestionParser.parse(json)
        assertEquals(1, question?.options?.size)
        assertEquals("First", question?.options?.first()?.title)
    }

    @Test
    fun `all options invalid returns null so local fallback applies`() {
        val json = """{"options":[{"id":"","title":""}]}"""
        assertNull(RemoteQuestionParser.parse(json))
    }
}
