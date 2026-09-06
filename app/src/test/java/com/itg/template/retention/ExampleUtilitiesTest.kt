package com.itg.template.retention

import org.junit.Assert.*
import org.junit.Test

class ExampleUtilitiesTest {
    @Test fun phrasesHaveDistinctStableIdsAndRealTranslationsInBothDirections() {
        assertEquals(ExampleUtilities.phrases.size, ExampleUtilities.phrases.map { it.id }.toSet().size)
        assertEquals("Xin chào", ExampleUtilities.translate("hello", true))
        assertEquals("Where is the train station?", ExampleUtilities.translate("station", false))
        ExampleUtilities.phrases.forEach { assertNotEquals(it.english, it.vietnamese) }
    }
    @Test fun textToolsNormalizeWhitespaceAndCountUnicodeCodePoints() {
        val result = ExampleUtilities.analyze("  Xin   chào\n🌏  ")
        assertEquals("Xin chào 🌏", result.normalized)
        assertEquals(3, result.words)
        assertEquals(10, result.characters)
    }
    @Test fun readingEstimateUsesTheActualDocumentWordCount() {
        assertEquals(1, ExampleUtilities.readingMinutes("one word"))
        assertEquals(2, ExampleUtilities.readingMinutes(List(201) { "word" }.joinToString(" ")))
    }
    @Test(expected = IllegalArgumentException::class) fun blankTextIsNotSuccessfulWork() { ExampleUtilities.analyze("  \n") }
    @Test(expected = IllegalStateException::class) fun unknownPhraseDoesNotInventATranslation() { ExampleUtilities.translate("missing", true) }
}
