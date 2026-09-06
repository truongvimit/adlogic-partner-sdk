package io.retentionkit.core

import org.json.JSONObject

/** Versioned patch document. Missing keys preserve defaults/cache; removal must be explicit. */
object RetentionConfigParser {
    @JvmStatic fun parse(raw: String): RetentionConfigParseResult = try {
        require(raw.length <= 128 * 1024) { "oversized_document" }
        val root = JSONObject(raw)
        require(root.keys().asSequence().all { it in setOf("version", "overrides", "removeKeys") }) { "unknown_document_field" }
        require(root.opt("version") is Number && root.get("version").toString() == "1") { "unsupported_version" }
        val overrides = if (root.has("overrides")) root.getJSONObject("overrides") else JSONObject()
        require(overrides.length() <= 1000) { "too_many_overrides" }
        val values = overrides.keys().asSequence().associateWith { key ->
            require(validId(key) && !key.startsWith("__")) { "invalid_key" }
            val value = overrides.get(key)
            require(value is String || value is Boolean || value is Number) { "invalid_value_type" }
            value.toString().also { require(it.length <= 8192) { "oversized_value" } }
        }
        val removals = if (root.has("removeKeys")) root.getJSONArray("removeKeys").let { array ->
            require(array.length() <= 1000) { "too_many_removals" }
            (0 until array.length()).map { index ->
                val value = array.get(index)
                require(value is String && validId(value) && !value.startsWith("__")) { "invalid_remove_key" }
                value
            }.toSet()
        } else emptySet()
        require(values.keys.intersect(removals).isEmpty()) { "conflicting_key_operations" }
        RetentionConfigParseResult.Valid(RetentionConfigDocument(values, removals))
    } catch (error: Exception) {
        RetentionConfigParseResult.Invalid(error.message ?: "malformed_document")
    }
}
