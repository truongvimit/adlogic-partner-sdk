package io.onboardkit.ui.language

import java.util.Locale
import io.onboardkit.config.ObLanguage

/**
 * Picks the row that gets the animated "tap here" hand on the LFO.
 *
 * Deliberately simple: the device locale is matched against the list that is actually on screen,
 * first on the exact tag (`pt-BR`), then on the language part alone (`pt`). Anything unmatched
 * falls back to English, so the hint always has a row to sit on.
 */
internal object DeviceLanguageHint {

    private const val FALLBACK_BASE = "en"

    fun resolve(
        languages: List<ObLanguage>,
        locale: Locale = Locale.getDefault(),
    ): String? {
        if (languages.isEmpty()) return null

        deviceMatch(languages, locale)?.let { return it }

        // Unsupported locale -> default to English, exact "en" first, then any en-* variant
        languages.firstOrNull { it.code.equalsIgnoreCase(FALLBACK_BASE) }?.let { return it.code }
        return languages.firstOrNull { it.baseKey.equalsIgnoreCase(FALLBACK_BASE) }?.code
    }

    /**
     * Moves the device-language row up to position 2 so the hinted row is on screen without
     * scrolling — a partner list that puts the device language last would otherwise hide the
     * hint below the fold. Only a genuine device match moves: the English fallback row stays
     * where the partner ordered it, and a row already at position 1 or 2 is never demoted.
     */
    fun promote(
        languages: List<ObLanguage>,
        locale: Locale = Locale.getDefault(),
    ): List<ObLanguage> {
        val code = deviceMatch(languages, locale) ?: return languages
        val index = languages.indexOfFirst { it.code == code }
        if (index <= 1) return languages
        return buildList(languages.size) {
            addAll(languages)
            add(1, removeAt(index))
        }
    }

    private fun deviceMatch(languages: List<ObLanguage>, locale: Locale): String? {
        val language = locale.language.lowercase(Locale.ROOT)
            // Indonesian: modern tags report "id", the catalog (and old Android) uses "in"
            .let { if (it == "id") "in" else it }
        val country = locale.country.uppercase(Locale.ROOT)

        // "pt-BR" beats "pt" when the device names a region
        if (country.isNotEmpty()) {
            val tag = "$language-$country"
            languages.firstOrNull { it.code.equalsIgnoreCase(tag) }?.let { return it.code }
        }

        languages.firstOrNull { it.code.equalsIgnoreCase(language) }?.let { return it.code }
        return languages.firstOrNull { it.baseKey.equalsIgnoreCase(language) }?.code
    }

    private fun String.equalsIgnoreCase(other: String): Boolean = equals(other, ignoreCase = true)
}
