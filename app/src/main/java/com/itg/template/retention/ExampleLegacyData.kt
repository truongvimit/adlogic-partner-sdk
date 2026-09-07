package com.itg.template.retention

/** Decode only the retired example's persisted data/entries. Never part of the visible catalogue. */
internal object ExampleLegacyData {
    fun destination(id: String): String = when (id) {
        "translate" -> "notes"
        "saved_phrases" -> "saved_items"
        "document" -> "guide"
        else -> id
    }
    private val savedText = mapOf(
        "hello" to "Hello\nXin chào", "thanks" to "Thank you\nCảm ơn",
        "please" to "Please help me\nLàm ơn giúp tôi", "station" to "Where is the train station?\nGa tàu ở đâu?",
        "price" to "How much does this cost?\nCái này giá bao nhiêu?", "water" to "I would like some water\nTôi muốn một ít nước",
        "vegetarian" to "I am vegetarian\nTôi ăn chay", "bill" to "The bill, please\nCho tôi xin hóa đơn",
        "understand" to "I do not understand\nTôi không hiểu", "slowly" to "Please speak slowly\nXin nói chậm lại",
        "morning" to "Good morning\nChào buổi sáng", "goodbye" to "Goodbye\nTạm biệt",
    )
    fun text(id: String) = savedText[id] ?: id
}
