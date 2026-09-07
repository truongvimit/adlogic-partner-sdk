package com.itg.template.retention

/** Real offline operations; UI labels and chosen app locale do not change the computation. */
data class TextAnalysis(val normalized: String, val words: Int, val characters: Int)

object ExampleUtilities {
    fun analyze(input: String): TextAnalysis {
        require(input.isNotBlank() && input.length <= 10_000) { "Enter between 1 and 10000 characters" }
        val normalized = input.trim().replace(Regex("\\s+"), " ")
        return TextAnalysis(normalized, normalized.split(' ').size, normalized.codePointCount(0, normalized.length))
    }
    fun readingMinutes(input: String): Int = ((analyze(input).words + 199) / 200).coerceAtLeast(1)
}
