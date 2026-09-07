package com.itg.template.retention

import org.junit.Assert.*
import org.junit.Test

class ExampleUtilitiesTest {
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

}
