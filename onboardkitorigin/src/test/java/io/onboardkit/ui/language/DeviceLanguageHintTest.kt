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

    @Test
    fun `promote lifts the device language from the bottom to position 2`() {
        val promoted = DeviceLanguageHint.promote(languages, Locale.forLanguageTag("id-ID"))
        assertEquals(listOf("en", "in", "fr", "pt-PT", "pt-BR"), promoted.map { it.code })
    }

    @Test
    fun `promote keeps the rest of the list in configured order`() {
        val promoted = DeviceLanguageHint.promote(languages, Locale.forLanguageTag("pt-BR"))
        assertEquals(listOf("en", "pt-BR", "fr", "pt-PT", "in"), promoted.map { it.code })
    }

    @Test
    fun `promote lifts a row from position 3, the first position below the fold line`() {
        val promoted = DeviceLanguageHint.promote(languages, Locale.forLanguageTag("pt-PT"))
        assertEquals(listOf("en", "pt-PT", "fr", "pt-BR", "in"), promoted.map { it.code })
    }

    @Test
    fun `promote lifts the regional variant matched from a bare base language`() {
        val promoted = DeviceLanguageHint.promote(languages, Locale.forLanguageTag("pt"))
        assertEquals(listOf("en", "pt-PT", "fr", "pt-BR", "in"), promoted.map { it.code })
    }

    @Test
    fun `promote never demotes a row already at position 1`() {
        assertEquals(languages, DeviceLanguageHint.promote(languages, Locale.US))
    }

    @Test
    fun `promote leaves a row already at position 2 alone`() {
        assertEquals(languages, DeviceLanguageHint.promote(languages, Locale.FRANCE))
    }

    @Test
    fun `promote leaves the english fallback of an unsupported locale where it was configured`() {
        val bottomEnglish = listOf(
            ObLanguage("hi", "हिन्दी", 0),
            ObLanguage("fr", "Français", 0),
            ObLanguage("pt-BR", "Português (Brasil)", 0),
            ObLanguage("en", "English", 0),
        )
        assertEquals(
            bottomEnglish,
            DeviceLanguageHint.promote(bottomEnglish, Locale.forLanguageTag("vi-VN")),
        )
    }

    @Test
    fun `promote of an empty list is a no-op`() {
        assertEquals(emptyList<ObLanguage>(), DeviceLanguageHint.promote(emptyList(), Locale.US))
    }
}
