package io.onboardkit.ui.language

import io.onboardkit.config.ObLanguage
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceLanguageHintTest {

    // flagRes is irrelevant here; only codes drive the matching
    private val languages = listOf(
        ObLanguage("en", "English", 0),
        ObLanguage("fr", "Français", 0),
        ObLanguage("pt-PT", "Português", 0),
        ObLanguage("pt-BR", "Português (Brasil)", 0),
        ObLanguage("in", "Indonesia", 0),
    )

    @Test
    fun `exact language match wins`() {
        assertEquals("fr", DeviceLanguageHint.resolve(languages, Locale.FRANCE))
    }

    @Test
    fun `region variant is preferred over the base language`() {
        assertEquals(
            "pt-BR",
            DeviceLanguageHint.resolve(languages, Locale.forLanguageTag("pt-BR")),
        )
    }

    @Test
    fun `base language falls back to the first regional variant`() {
        assertEquals("pt-PT", DeviceLanguageHint.resolve(languages, Locale.forLanguageTag("pt")))
    }

    @Test
    fun `indonesian id maps onto the catalog's in`() {
        assertEquals("in", DeviceLanguageHint.resolve(languages, Locale.forLanguageTag("id-ID")))
    }

    @Test
    fun `unsupported locale falls back to english`() {
        assertEquals("en", DeviceLanguageHint.resolve(languages, Locale.forLanguageTag("vi-VN")))
    }

    @Test
    fun `english fallback accepts a regional variant when plain en is absent`() {
        val regional = listOf(
            ObLanguage("en-US", "English (US)", 0),
            ObLanguage("fr", "Français", 0),
        )
        assertEquals(
            "en-US",
            DeviceLanguageHint.resolve(regional, Locale.forLanguageTag("vi-VN")),
        )
    }

    @Test
    fun `empty list has no hint`() {
        assertNull(DeviceLanguageHint.resolve(emptyList(), Locale.US))
    }
}
