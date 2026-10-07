package com.ads.module.config

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import android.graphics.Color
import com.ads.module.helper.adnative.NativeClickAction
import java.io.Reader

/**
 * Strict, sparse parser for `ad_config.json`.
 *
 * A remote document is a patch: an omitted field must remain absent so the resolver can fall back
 * to the app asset/code/default. Invalid fields are skipped individually and valid siblings keep
 * applying. The public [AdRemoteConfig] value still contains concrete defaults for compatibility;
 * [AdRemoteConfig.declaredFields] carries presence information for the layered resolver.
 */
internal object AdConfigParser {
    private const val TAG = "AdConfigParser"
    private const val DEFAULT_COLOR = "default"
    private val RETIRED_FLOOR_SUFFIX = Regex("_high\\d?$")

    fun parse(source: Reader): Map<String, AdUnitConfig> = parseConfig(source).ads

    fun parseConfig(source: Reader): AdRemoteConfig {
        JsonReader(source).use { reader ->
            reader.isLenient = true
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                throw IllegalArgumentException("ad_config root must be an object")
            }
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                throw IllegalArgumentException("ad_config root must be an object")
            }
            val units = LinkedHashMap<String, AdUnitConfig>()
            val fields = LinkedHashMap<String, Set<String>>()
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (key == "schema_version") {
                    val version = readLong(reader)
                    if (!version.valid) throw IllegalArgumentException("invalid schema_version")
                    if (version.value != 1L) throw IllegalArgumentException("unsupported schema_version=${version.value}")
                    continue
                }
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    warn("$key ignored (expected an object value=${readValueForLog(reader)})")
                    continue
                }
                val parsed = readAdUnit(key, reader)
                units[key] = parsed.value
                fields[key] = parsed.fields
            }
            reader.endObject()
            warnRetiredFloorKeys(units.keys)
            return AdRemoteConfig(units).also { it.declaredFields = fields }
        }
    }

    private data class ParsedUnit(val value: AdUnitConfig, val fields: Set<String>)
    private data class Parsed<T>(val value: T?, val valid: Boolean, val raw: String? = null)

    private fun readAdUnit(key: String, reader: JsonReader): ParsedUnit {
        val fields = linkedSetOf<String>()
        var isEnable = false
        var enableUaCheck = false
        var reloadIntervalSeconds: Int? = null
        var appResumeLoadDelayMs = AdRemoteConfig.DEFAULT_APP_RESUME_LOAD_DELAY_MS
        var colorCTA = DEFAULT_COLOR
        var colorBackground = DEFAULT_COLOR
        var colorAdBadge = DEFAULT_COLOR
        var colorAdBadgeText = DEFAULT_COLOR
        var heightCTA = AdUnitConfig.DEFAULT_HEIGHT_CTA
        var components: List<String> = AdUnitConfig.DEFAULT_COMPONENTS
        var ids: List<String> = emptyList()
        var clickAction: NativeClickAction? = null
        var templateId: Int? = null

        reader.beginObject()
        while (reader.hasNext()) {
            val field = reader.nextName()
            when (field) {
                "isEnable", "enable_ua_check" -> readBoolean(reader).also { parsed ->
                    if (parsed.valid) {
                        if (field == "isEnable") isEnable = parsed.value!! else enableUaCheck = parsed.value!!
                        fields += field
                    } else invalid(key, field, parsed.raw)
                }
                "reloadIntervalSeconds" -> readNullableInt(reader, min = 0).also { parsed ->
                    if (parsed.valid) { reloadIntervalSeconds = parsed.value; fields += field } else invalid(key, field, parsed.raw)
                }
                "app_resume_load_delay_ms" -> readLongCompat(reader, min = 0, max = AdRemoteConfig.MAX_APP_RESUME_LOAD_DELAY_MS).also { parsed ->
                    if (parsed.valid) { appResumeLoadDelayMs = parsed.value!!; fields += field } else invalid(key, field, parsed.raw)
                }
                "colorCTA" -> readString(reader).also { parsed ->
                    if (parsed.valid && parsed.value!!.isValidColorToken()) { colorCTA = parsed.value; fields += field } else invalid(key, field, parsed.raw)
                }
                "colorBackground", "colorAdBadge", "colorAdBadgeText" -> readClearableColor(reader).also { parsed ->
                    if (parsed.valid) {
                        when (field) {
                            "colorBackground" -> colorBackground = parsed.value!!
                            "colorAdBadge" -> colorAdBadge = parsed.value!!
                            else -> colorAdBadgeText = parsed.value!!
                        }
                        fields += field
                    } else invalid(key, field, parsed.raw)
                }
                "heightCTA" -> readInt(reader, min = 0).also { parsed ->
                    if (parsed.valid) { heightCTA = parsed.value!!; fields += field } else invalid(key, field, parsed.raw)
                }
                "click_action" -> readString(reader).also { parsed ->
                    val action = parsed.value?.let(NativeClickAction::fromRemote)
                    if (action != null) { clickAction = action; fields += field } else invalid(key, field, parsed.raw ?: parsed.value)
                }
                "templateId" -> readNullableInt(reader, min = 1).also { parsed ->
                    if (parsed.valid) { templateId = parsed.value; fields += field } else invalid(key, field, parsed.raw)
                }
                "components" -> readStringList(reader).also { parsed ->
                    if (parsed.valid && parsed.value!!.all { it in AdUnitConfig.DEFAULT_COMPONENTS }) {
                        components = parsed.value
                        fields += field
                    } else invalid(key, field, parsed.raw)
                }
                "ids" -> readFloors(key, reader).also { parsed ->
                    if (parsed.valid) { ids = parsed.value!!; fields += field } else invalid(key, field, parsed.raw)
                }
                else -> {
                    warn("$key.$field ignored (unknown field value=${readValueForLog(reader)})")
                }
            }
        }
        reader.endObject()
        return ParsedUnit(AdUnitConfig(
            ids = ids,
            isEnable = isEnable,
            enableUaCheck = enableUaCheck,
            reloadIntervalSeconds = reloadIntervalSeconds,
            colorCTA = colorCTA,
            colorBackground = colorBackground,
            colorAdBadge = colorAdBadge,
            colorAdBadgeText = colorAdBadgeText,
            heightCTA = heightCTA,
            components = components,
            appResumeLoadDelayMs = appResumeLoadDelayMs,
            clickAction = clickAction,
            templateId = templateId,
        ), fields)
    }

    private fun String.isValidColorToken(): Boolean =
        isEmpty() || equals("default", ignoreCase = true) || runCatching { Color.parseColor(this) }.isSuccess

    // null is a clear, like "": back to what the layout draws over any lower tier
    private fun readClearableColor(reader: JsonReader): Parsed<String> =
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            Parsed(DEFAULT_COLOR, valid = true)
        } else {
            readString(reader).let { parsed ->
                if (parsed.valid && parsed.value!!.isValidColorToken()) parsed else parsed.copy(valid = false)
            }
        }

    private fun readString(reader: JsonReader): Parsed<String> = when (reader.peek()) {
        JsonToken.STRING -> Parsed(reader.nextString(), true)
        else -> Parsed(null, false, readValueForLog(reader))
    }

    private fun readBoolean(reader: JsonReader): Parsed<Boolean> = when (reader.peek()) {
        JsonToken.BOOLEAN -> Parsed(reader.nextBoolean(), true)
        else -> Parsed(null, false, readValueForLog(reader))
    }

    private fun readLong(reader: JsonReader, min: Long? = null, max: Long? = null): Parsed<Long> {
        if (reader.peek() != JsonToken.NUMBER) return Parsed(null, false, readValueForLog(reader))
        val raw = reader.nextString()
        val value = raw.toLongOrNull()
        val valid = value != null && (min == null || value >= min) && (max == null || value <= max)
        return Parsed(value, valid, raw)
    }

    /** Existing ad assets used quoted numbers for this legacy field; keep that representation. */
    private fun readLongCompat(reader: JsonReader, min: Long? = null, max: Long? = null): Parsed<Long> {
        val token = reader.peek()
        if (token == JsonToken.STRING) {
            val raw = reader.nextString()
            val value = raw.toLongOrNull()
            return Parsed(value, value != null && (min == null || value >= min) && (max == null || value <= max), raw)
        }
        return readLong(reader, min, max)
    }

    private fun readInt(reader: JsonReader, min: Long? = null, max: Long? = Int.MAX_VALUE.toLong()): Parsed<Int> {
        val parsed = readLong(reader, min, max)
        return Parsed(parsed.value?.toInt(), parsed.valid, parsed.raw)
    }

    /** `reloadIntervalSeconds` and `templateId` are nullable in the public schema; null explicitly clears a lower tier. */
    private fun readNullableInt(reader: JsonReader, min: Long? = null, max: Long? = Int.MAX_VALUE.toLong()): Parsed<Int?> {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return Parsed(null, true)
        }
        val parsed = readInt(reader, min, max)
        return Parsed(parsed.value, parsed.valid, parsed.raw)
    }

    private fun readStringList(reader: JsonReader): Parsed<List<String>> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) return Parsed(null, false, readValueForLog(reader))
        val result = mutableListOf<String>()
        var valid = true
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.STRING) result += reader.nextString()
            else {
                // A malformed element makes the list field invalid. The containing document and
                // all valid sibling fields still apply, but the bad list falls through as a unit.
                valid = false
                reader.skipValue()
            }
        }
        reader.endArray()
        return Parsed(result, valid, if (valid) null else "array element has wrong type")
    }

    /**
     * `ids`: the waterfall as `[{"id": …, "isEnable": …}, …]`, highest floor first, reduced to the
     * ids of its enabled floors. `isEnable` defaults to true, since the placement's own `isEnable`
     * is the master switch. A malformed floor is skipped alone; a non-empty list with no valid
     * floor is invalid as a whole, so a broken patch falls back to the lower tier, not to no ad.
     */
    private fun readFloors(key: String, reader: JsonReader): Parsed<List<String>> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) return Parsed(null, false, readValueForLog(reader))
        val enabled = mutableListOf<String>()
        var floors = 0
        var valid = 0
        reader.beginArray()
        while (reader.hasNext()) {
            val at = "$key.ids[${floors++}]"
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                warn("$at ignored (a floor is {\"id\": …, \"isEnable\": …}, got ${readValueForLog(reader)})")
                continue
            }
            var id: String? = null
            var isEnable = true
            var broken = false
            reader.beginObject()
            while (reader.hasNext()) {
                when (val field = reader.nextName()) {
                    "id" -> readString(reader).also { parsed ->
                        if (parsed.valid && parsed.value!!.isNotBlank()) id = parsed.value
                        else { broken = true; invalid(at, field, parsed.raw ?: "\"${parsed.value}\"") }
                    }
                    "isEnable" -> readBoolean(reader).also { parsed ->
                        if (parsed.valid) isEnable = parsed.value!! else { broken = true; invalid(at, field, parsed.raw) }
                    }
                    else -> warn("$at.$field ignored (unknown field value=${readValueForLog(reader)})")
                }
            }
            reader.endObject()
            if (id == null && !broken) warn("$at ignored (no \"id\")")
            if (id == null || broken) continue
            valid++
            if (isEnable) enabled += id!!
        }
        reader.endArray()
        if (floors > 0 && valid == 0) return Parsed(null, false, "no valid floor")
        if (valid > 0 && enabled.isEmpty()) warn("$key.ids has every floor off; the placement requests nothing")
        return Parsed(enabled, true)
    }

    private fun readValueForLog(reader: JsonReader): String = when (reader.peek()) {
        JsonToken.STRING -> "\"${reader.nextString()}\""
        JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        JsonToken.NULL -> { reader.nextNull(); "null" }
        else -> { val token = reader.peek(); reader.skipValue(); token.name }
    }

    /**
     * A `<key>_high`/`<key>_highN` entry is no longer a floor of `<key>`; read as a placement of
     * its own nobody requests, it would drop the high floor without a sound.
     */
    private fun warnRetiredFloorKeys(keys: Set<String>) {
        val retired = keys.filter(RETIRED_FLOOR_SUFFIX::containsMatchIn)
        if (retired.isNotEmpty()) warn("$retired not read as floors; move their ids into the base key's \"ids\"")
    }

    private fun invalid(key: String, field: String, raw: String?) =
        warn("$key.$field ignored (invalid value=${raw ?: "<field>"})")
    private fun warn(message: String) = runCatching { Log.w(TAG, message) }
}
