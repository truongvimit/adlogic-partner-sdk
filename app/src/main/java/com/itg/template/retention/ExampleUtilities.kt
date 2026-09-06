package com.itg.template.retention

/** Small, truthful offline features for this SDK example; this is not a general translation engine. */
data class ExamplePhrase(val id: String, val english: String, val vietnamese: String)
data class TextAnalysis(val normalized: String, val words: Int, val characters: Int)

object ExampleUtilities {
    val phrases = listOf(
        ExamplePhrase("hello", "Hello", "Xin chào"),
        ExamplePhrase("thanks", "Thank you", "Cảm ơn"),
        ExamplePhrase("please", "Please help me", "Làm ơn giúp tôi"),
        ExamplePhrase("station", "Where is the train station?", "Ga tàu ở đâu?"),
        ExamplePhrase("price", "How much does this cost?", "Cái này giá bao nhiêu?"),
        ExamplePhrase("water", "I would like some water", "Tôi muốn một ít nước"),
        ExamplePhrase("vegetarian", "I am vegetarian", "Tôi ăn chay"),
        ExamplePhrase("bill", "The bill, please", "Cho tôi xin hóa đơn"),
        ExamplePhrase("understand", "I do not understand", "Tôi không hiểu"),
        ExamplePhrase("slowly", "Please speak slowly", "Xin nói chậm lại"),
        ExamplePhrase("morning", "Good morning", "Chào buổi sáng"),
        ExamplePhrase("goodbye", "Goodbye", "Tạm biệt"),
    )
    fun phrase(id: String): ExamplePhrase = phrases.firstOrNull { it.id == id } ?: error("Unknown phrase")
    fun translate(id: String, toVietnamese: Boolean): String = phrase(id).let { if (toVietnamese) it.vietnamese else it.english }
    fun analyze(input: String): TextAnalysis {
        require(input.isNotBlank() && input.length <= 10_000) { "Enter between 1 and 10000 characters" }
        val normalized = input.trim().replace(Regex("\\s+"), " ")
        return TextAnalysis(normalized, normalized.split(' ').size, normalized.codePointCount(0, normalized.length))
    }
    fun readingMinutes(input: String): Int = ((analyze(input).words + 199) / 200).coerceAtLeast(1)
}
