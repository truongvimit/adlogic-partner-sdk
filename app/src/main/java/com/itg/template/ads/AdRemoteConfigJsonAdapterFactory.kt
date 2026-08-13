package com.itg.template.ads

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type
import java.util.Locale

class AdRemoteConfigJsonAdapterFactory : JsonAdapter.Factory {
    override fun create(type: Type, annotations: MutableSet<out Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (Types.getRawType(type) != AdRemoteConfig::class.java) {
            return null
        }
        val mapType: Type = Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            AdUnitConfig::class.java
        )
        val mapAdapter: JsonAdapter<Map<String, AdUnitConfig>> =
            moshi.adapter<Map<String, AdUnitConfig>>(mapType).lenient()
        return object : JsonAdapter<AdRemoteConfig>() {
            override fun fromJson(reader: JsonReader): AdRemoteConfig {
                reader.isLenient = true
                if (reader.peek() == JsonReader.Token.NULL) {
                    reader.nextNull<Unit>()
                    return AdRemoteConfig()
                }
                val adsMap = mutableMapOf<String, AdUnitConfig>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    adsMap[key] = readAdUnitConfig(reader)
                }
                reader.endObject()
                return AdRemoteConfig(ads = adsMap)
            }

            override fun toJson(writer: JsonWriter, value: AdRemoteConfig?) {
                if (value == null) {
                    writer.nullValue()
                    return
                }
                mapAdapter.toJson(writer, value.ads)
            }

            private fun readAdUnitConfig(reader: JsonReader): AdUnitConfig {
                var id = ""
                var isEnable = false
                var enableUaCheck = false
                var reloadIntervalSeconds: Int? = null
                var colorCTA = "default"
                var heightCTA = 40
                var positionCTA = "BOTTOM"
                var components: List<String> = listOf("icon_headline", "body", "media", "cta")
                var ids: List<String> = emptyList()
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "id" -> id = reader.nextString()
                        "isEnable" -> isEnable = reader.nextBoolean()
                        "enable_ua_check" -> enableUaCheck = reader.nextBoolean()
                        "reloadIntervalSeconds" -> reloadIntervalSeconds = reader.nextInt()
                        "colorCTA" -> colorCTA = safeNextString(reader, "default")
                        "heightCTA" -> heightCTA = readHeightValue(reader)
                        "positionCTA" -> positionCTA = safeNextString(reader, "BOTTOM").uppercase(Locale.US)
                        "components" -> components = readComponentsList(reader)
                        // Waterfall tiers, highest floor first. Absent in legacy payloads, where
                        // "id" alone remains the single tier.
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
                    JsonReader.Token.BEGIN_ARRAY -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (reader.peek() == JsonReader.Token.STRING) {
                                list.add(reader.nextString())
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endArray()
                    }

                    JsonReader.Token.STRING -> list.add(reader.nextString())
                    else -> reader.skipValue()
                }
                return list.filter { it.isNotBlank() }
            }

            private fun readComponentsList(reader: JsonReader): List<String> {
                val list = mutableListOf<String>()
                if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() == JsonReader.Token.STRING) {
                            list.add(reader.nextString())
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
                return list.ifEmpty { listOf("icon_headline", "body", "media", "cta") }
            }

            private fun readHeightValue(reader: JsonReader): Int {
                return when (reader.peek()) {
                    JsonReader.Token.NUMBER -> reader.nextInt()
                    JsonReader.Token.STRING -> {
                        val value = reader.nextString()
                        if (value.equals("default", ignoreCase = true)) 40 else value.toIntOrNull() ?: 40
                    }
                    JsonReader.Token.NULL -> {
                        reader.nextNull<Unit>()
                        40
                    }
                    else -> {
                        reader.skipValue()
                        40
                    }
                }
            }

            private fun safeNextString(reader: JsonReader, fallback: String): String {
                return when (reader.peek()) {
                    JsonReader.Token.STRING -> reader.nextString()
                    JsonReader.Token.NULL -> {
                        reader.nextNull<Unit>()
                        fallback
                    }
                    else -> {
                        reader.skipValue()
                        fallback
                    }
                }
            }
        }
    }
}
