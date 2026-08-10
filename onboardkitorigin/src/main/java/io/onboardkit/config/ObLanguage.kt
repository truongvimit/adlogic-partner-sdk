package io.onboardkit.config

import android.os.Parcelable
import androidx.annotation.DrawableRes
import io.onboardkit.R
import kotlinx.parcelize.Parcelize

/** One selectable language. [baseKey] groups regional variants (en-US, en-GB → "en"). */
@Parcelize
data class ObLanguage(
    val code: String,
    val displayName: String,
    @DrawableRes val flagRes: Int,
) : Parcelable {
    val baseKey: String get() = code.substringBefore('-')
}

/** Built-in catalog matching the flag assets shipped with the SDK. */
object ObLanguages {

    val ALL: List<ObLanguage> = listOf(
        ObLanguage("en-US", "English (US)", R.drawable.ob_flag_en_us),
        ObLanguage("en-GB", "English (UK)", R.drawable.ob_flag_en_gb),
        ObLanguage("en-CA", "English (Canada)", R.drawable.ob_flag_en_ca),
        ObLanguage("en-IN", "English (India)", R.drawable.ob_flag_en_in),
        ObLanguage("en-PH", "English (Philippines)", R.drawable.ob_flag_en_ph),
        ObLanguage("en-ZA", "English (South Africa)", R.drawable.ob_flag_en_za),
        ObLanguage("es", "Española", R.drawable.ob_flag_es),
        ObLanguage("es-US", "Español (US)", R.drawable.ob_flag_es_us),
        ObLanguage("fr", "Français", R.drawable.ob_flag_fr),
        ObLanguage("fr-CA", "Français (Canada)", R.drawable.ob_flag_fr_ca),
        ObLanguage("hi", "हिन्दी", R.drawable.ob_flag_hi),
        ObLanguage("pt-PT", "Português", R.drawable.ob_flag_pt_pt),
        ObLanguage("pt-BR", "Português (Brasil)", R.drawable.ob_flag_pt_br),
        ObLanguage("zh", "中文", R.drawable.ob_flag_zh),
        ObLanguage("ru", "Русский", R.drawable.ob_flag_ru),
        ObLanguage("in", "Indonesia", R.drawable.ob_flag_in),
        ObLanguage("bn", "বাংলা", R.drawable.ob_flag_bn),
        ObLanguage("de", "Deutsch", R.drawable.ob_flag_de),
        ObLanguage("ko", "한국어", R.drawable.ob_flag_ko),
        ObLanguage("af-ZA", "Afrikaans", R.drawable.ob_flag_af_za),
        ObLanguage("nl", "Nederlands", R.drawable.ob_flag_nl),
    )

    private val byCode = ALL.associateBy { it.code }

    fun find(code: String): ObLanguage? = byCode[code.trim()]

    /**
     * Resolves a CSV of codes against the catalog, keeping remote order.
     * Never returns an empty list — falls back to the full catalog.
     */
    fun resolve(csv: String?): List<ObLanguage> {
        val resolved = csv.orEmpty()
            .split(',')
            .mapNotNull { find(it) }
            .distinctBy { it.code }
        return resolved.ifEmpty { ALL }
    }
}
