package com.ads.module.config

import android.util.JsonReader
import android.util.JsonToken
import java.io.Reader
import java.util.Locale

/**
 * Reads `ad_config.json` into [AdUnitConfig] entries.
 *
 * Hand-written on the platform's streaming reader rather than a JSON library: the payload is a flat
 * map of known field names, so a mapper would only add a dependency to every partner APK.
 *
 * Deliberately tolerant. Remote config is edited by hand in a console, and one mistyped field must
 * degrade to a default rather than take the whole ad configuration down with it: unknown keys are
 * skipped, a string where a number belongs is coerced, and null falls back.
 */
internal object AdConfigParser {

    private val DEFAULT_COMPONENTS = listOf("icon_headline", "body", "media", "cta")
    private const val DEFAULT_HEIGHT_CTA = 40
    private const val DEFAULT_COLOR_CTA = "default"

    fun parse(source: Reader): Map<String, AdUnitConfig> {
        JsonReader(source).use { reader ->
            reader.isLenient = true
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                return emptyMap()
            }
            val units = LinkedHashMap<String, AdUnitConfig>()
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                    units[key] = readAdUnit(reader)
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            return units
        }
    }

    private fun readAdUnit(reader: JsonReader): AdUnitConfig {
        var id = ""
        var isEnable = false
        var enableUaCheck = false
        var reloadIntervalSeconds: Int? = null
        var colorCTA = DEFAULT_COLOR_CTA
        var heightCTA = DEFAULT_HEIGHT_CTA
        var positionCTA: String? = null
        var components: List<String> = DEFAULT_COMPONENTS
        var ids: List<String> = emptyList()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = safeNextString(reader, "")
                "isEnable" -> isEnable = safeNextBoolean(reader)
                "enable_ua_check" -> enableUaCheck = safeNextBoolean(reader)
                "reloadIntervalSeconds" -> reloadIntervalSeconds = safeNextInt(reader)
                "colorCTA" -> colorCTA = safeNextString(reader, DEFAULT_COLOR_CTA)
                "heightCTA" -> heightCTA = readHeight(reader)
                // Absent or null: the placement has no opinion, and `components` orders the
                // blocks instead. A value only means something to a screen that ships one layout
                // per position.
                "positionCTA" ->
                    positionCTA = safeNextString(reader, "").uppercase(Locale.US).ifBlank { null }

                "components" -> components = readComponents(reader)
                // Waterfall tiers, highest floor first. Absent in single-tier payloads, where
                // "id" alone remains the only tier.
                "ids" -> ids = readStringList(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        return AdUnitConfig(
            id = id,
            isEnable = isEnable,
            enableUaCheck = enableUaCheck,
            reloadIntervalSeconds = reloadIntervalSeconds,
            colorCTA = colorCTA,
            heightCTA = heightCTA,
            positionCTA = positionCTA,
            components = components,
            ids = ids,
        )
    }

    /** Tolerates a bare string so `"ids": "single-id"` is not silently dropped. */
    private fun readStringList(reader: JsonReader): List<String> {
        val list = mutableListOf<String>()
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() == JsonToken.STRING) list.add(reader.nextString())
                    else reader.skipValue()
                }
                reader.endArray()
            }

            JsonToken.STRING -> list.add(reader.nextString())
            else -> reader.skipValue()
        }
        return list.filter { it.isNotBlank() }
    }

    private fun readComponents(reader: JsonReader): List<String> {
        val list = mutableListOf<String>()
        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() == JsonToken.STRING) list.add(reader.nextString())
                else reader.skipValue()
            }
            reader.endArray()
        } else {
            reader.skipValue()
        }
        return list.ifEmpty { DEFAULT_COMPONENTS }
    }

    private fun readHeight(reader: JsonReader): Int = when (reader.peek()) {
        JsonToken.NUMBER -> reader.nextInt()
        JsonToken.STRING -> {
            val value = reader.nextString()
            if (value.equals("default", ignoreCase = true)) {
                DEFAULT_HEIGHT_CTA
            } else {
                value.toIntOrNull() ?: DEFAULT_HEIGHT_CTA
            }
        }

        JsonToken.NULL -> {
            reader.nextNull()
            DEFAULT_HEIGHT_CTA
        }

        else -> {
            reader.skipValue()
            DEFAULT_HEIGHT_CTA
        }
    }

    private fun safeNextString(reader: JsonReader, fallback: String): String =
        when (reader.peek()) {
            JsonToken.STRING -> reader.nextString()
            // A console that typed the id without quotes still means the id.
            JsonToken.NUMBER -> reader.nextString()
            JsonToken.NULL -> {
                reader.nextNull()
                fallback
            }

            else -> {
                reader.skipValue()
                fallback
            }
        }

    private fun safeNextBoolean(reader: JsonReader): Boolean = when (reader.peek()) {
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.STRING -> reader.nextString().equals("true", ignoreCase = true)
        JsonToken.NULL -> {
            reader.nextNull()
            false
        }

        else -> {
            reader.skipValue()
            false
        }
    }

    private fun safeNextInt(reader: JsonReader): Int? = when (reader.peek()) {
        JsonToken.NUMBER -> reader.nextInt()
        JsonToken.STRING -> reader.nextString().toIntOrNull()
        JsonToken.NULL -> {
            reader.nextNull()
            null
        }

        else -> {
            reader.skipValue()
            null
        }
    }
}
