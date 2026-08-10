package io.onboardkit.remote.uiconfig

import io.onboardkit.config.QuestionOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class RemoteQuestionDto(
    val title: String? = null,
    @SerialName("cta_text") val ctaText: String? = null,
    val options: List<RemoteQuestionOptionDto> = emptyList(),
)

@Serializable
internal data class RemoteQuestionOptionDto(
    val id: String? = null,
    val title: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)

data class RemoteQuestion(
    val title: String?,
    val ctaText: String?,
    val options: List<QuestionOption>,
)

/**
 * Remote question content. Broken elements are dropped individually (the original threw the
 * whole screen away on one bad option); duplicates dedupe by id keeping first occurrence.
 * Returns null when nothing usable remains — the compile-time config then applies.
 */
object RemoteQuestionParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): RemoteQuestion? {
        if (raw.isBlank()) return null
        val dto = runCatching { json.decodeFromString(RemoteQuestionDto.serializer(), raw) }
            .getOrNull() ?: return null

        val options = dto.options
            .mapNotNull { option ->
                val id = option.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = option.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                QuestionOption(id = id, title = title, imageUrl = option.imageUrl)
            }
            .distinctBy { it.id }

        if (options.isEmpty()) return null
        return RemoteQuestion(title = dto.title, ctaText = dto.ctaText, options = options)
    }
}
