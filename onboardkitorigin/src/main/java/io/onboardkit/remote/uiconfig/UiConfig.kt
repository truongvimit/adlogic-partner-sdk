package io.onboardkit.remote.uiconfig

import android.graphics.Color
import android.util.Log
import kotlinx.serialization.json.Json

/** Resolved, validated remote UI for one step. Colors are already token-resolved ARGB ints. */
data class UiStepStyle(
    val stepId: String,
    val order: Int,
    val contentUrl: String?,
    val isImage: Boolean,
    val title: String?,
    val titleColor: Int?,
    val subtitle: String?,
    val subtitleColor: Int?,
    val buttonNextText: String?,
    val buttonLastText: String?,
    val buttonTextColor: Int?,
    val textBackgroundColor: Int?,
    val textBackgroundEnabled: Boolean,
    val sliderColor: Int?,
)

data class UiConfig(
    val steps: List<UiStepStyle>,
    val errors: List<String>,
) {
    fun styleFor(stepId: String): UiStepStyle? = steps.firstOrNull { it.stepId == stepId }
}

/**
 * Parses + validates the two remote JSON payloads. Validation is PER ELEMENT: one broken
 * step drops that step only — the original dropped the whole screen set.
 */
object UiConfigParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif")
    private val VIDEO_EXT = setOf("mp4", "webm", "mkv", "3gp", "m4v")

    fun parse(contentJson: String, tokensJson: String): UiConfig {
        val errors = mutableListOf<String>()

        val tokens: Map<String, Int> = parseTokens(tokensJson, errors)
        val content: UiContentDto? = decode(contentJson, UiContentDto.serializer(), errors)

        val steps = content?.steps.orEmpty().mapIndexedNotNull { index, dto ->
            toStyle(dto, index, tokens, errors)
        }.sortedBy { it.order }

        return UiConfig(steps = steps, errors = errors)
    }

    private fun <T> decode(
        raw: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        errors: MutableList<String>,
    ): T? {
        if (raw.isBlank()) return null
        return runCatching { json.decodeFromString(serializer, raw) }
            .onFailure { errors += "decode: ${it.message}" }
            .getOrNull()
    }

    private fun parseTokens(raw: String, errors: MutableList<String>): Map<String, Int> {
        val dto = decode(raw, DesignTokensDto.serializer(), errors) ?: return emptyMap()
        return dto.customColors.mapNotNull { token ->
            val id = token.colorId?.takeIf { it.isNotBlank() }
            val value = token.colorValue?.let(::parseColor)
            if (id == null || value == null) {
                errors += "token dropped: id=${token.colorId} value=${token.colorValue}"
                null
            } else {
                id to value
            }
        }.toMap()
    }

    private fun toStyle(
        dto: UiStepDto,
        index: Int,
        tokens: Map<String, Int>,
        errors: MutableList<String>,
    ): UiStepStyle? {
        val id = dto.id?.takeIf { it.isNotBlank() } ?: run {
            errors += "step[$index] dropped: missing id"
            return null
        }
        if (dto.enabled == false) return null

        val contentUrl = dto.content?.takeIf { it.isNotBlank() }
        val isImage = dto.isImage ?: contentUrl?.let(::guessIsImage) ?: true
        if (contentUrl != null && !matchesDeclaredType(contentUrl, isImage)) {
            errors += "step[$id] dropped: content extension does not match is_image=$isImage"
            return null
        }

        fun color(idOrHex: String?): Int? =
            idOrHex?.takeIf { it.isNotBlank() }?.let { tokens[it] ?: parseColor(it) }

        return UiStepStyle(
            stepId = id,
            order = dto.order ?: index,
            contentUrl = contentUrl,
            isImage = isImage,
            title = dto.title,
            titleColor = color(dto.titleColor),
            subtitle = dto.subtitle,
            subtitleColor = color(dto.subtitleColor),
            buttonNextText = dto.buttonNextContent,
            buttonLastText = dto.buttonLastContent,
            buttonTextColor = color(dto.buttonContentColor),
            textBackgroundColor = color(dto.backgroundTextColor),
            textBackgroundEnabled = dto.enableBackgroundText ?: true,
            sliderColor = color(dto.sliderColor),
        )
    }

    private fun guessIsImage(url: String): Boolean? {
        val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
        return when (ext) {
            in IMAGE_EXT -> true
            in VIDEO_EXT -> false
            else -> null
        }
    }

    private fun matchesDeclaredType(url: String, isImage: Boolean): Boolean {
        val guessed = guessIsImage(url) ?: return true
        return guessed == isImage
    }

    /** Bad colors log and return null — a typo in the console must never crash the flow. */
    private fun parseColor(value: String): Int? = runCatching {
        Color.parseColor(value.trim())
    }.onFailure { Log.w("OnboardKit.UiConfig", "Unparsable color '$value'") }.getOrNull()
}
