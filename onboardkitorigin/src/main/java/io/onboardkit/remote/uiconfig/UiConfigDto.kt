package io.onboardkit.remote.uiconfig

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tri-state boolean tolerant of console typos: accepts true/"true"/"TRUE "/"1"/"yes"
 * and their negatives; anything else decodes to null ("unknown") instead of silently false.
 */
object LenientBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ObLenientBoolean", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Boolean? {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return null
        if (element is JsonNull) return null
        val raw = (element as? JsonPrimitive)?.content?.trim()?.lowercase() ?: return null
        return when (raw) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean?) {
        encoder.encodeString(value?.toString() ?: "")
    }
}

/**
 * Server-driven UI schema. Steps are an ORDERED ARRAY (id/order/enabled per element) —
 * the capability the original SDK advertised but its fixed 3-slot schema could not deliver.
 */
@Serializable
data class UiContentDto(
    val steps: List<UiStepDto> = emptyList(),
    val lfo: UiLfoDto? = null,
)

@Serializable
data class UiStepDto(
    val id: String? = null,
    val order: Int? = null,
    @Serializable(with = LenientBooleanSerializer::class)
    val enabled: Boolean? = null,
    /** Image or video URL used as the step background. */
    val content: String? = null,
    @SerialName("is_image")
    @Serializable(with = LenientBooleanSerializer::class)
    val isImage: Boolean? = null,
    val title: String? = null,
    @SerialName("title_color") val titleColor: String? = null,
    val subtitle: String? = null,
    @SerialName("subtitle_color") val subtitleColor: String? = null,
    /** Label for the not-last steps; the original could not tell "Next" from "Get Started". */
    @SerialName("button_next_content") val buttonNextContent: String? = null,
    @SerialName("button_last_content") val buttonLastContent: String? = null,
    @SerialName("button_content_color") val buttonContentColor: String? = null,
    @SerialName("background_text_color") val backgroundTextColor: String? = null,
    @SerialName("enable_background_text")
    @Serializable(with = LenientBooleanSerializer::class)
    val enableBackgroundText: Boolean? = null,
    @SerialName("slider_color") val sliderColor: String? = null,
)

@Serializable
data class UiLfoDto(
    @SerialName("button_image") val buttonImage: String? = null,
    @SerialName("button_tint_color") val buttonTintColor: String? = null,
)

@Serializable
data class DesignTokensDto(
    @SerialName("custom_colors") val customColors: List<ColorTokenDto> = emptyList(),
)

@Serializable
data class ColorTokenDto(
    @SerialName("color_id") val colorId: String? = null,
    @SerialName("color_value") val colorValue: String? = null,
)
