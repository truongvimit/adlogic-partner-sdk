package io.paykit.design

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns the remote `tokens` block into a [PaywallTheme].
 *
 * Resolution is per key against the bundled palette, so one unparsable hex string costs that
 * one colour instead of blanking the screen — the failure mode the three-hop indirection had.
 */
object TokenResolver {

    private val TEXT_PRIMARY = ColorToken("text_primary")
    private val TEXT_SECONDARY = ColorToken("text_secondary")
    private val ACCENT = ColorToken("accent")
    private val BACKGROUND = ColorToken("background")
    private val SURFACE = ColorToken("surface")
    private val ON_ACCENT = ColorToken("on_accent")
    private val CTA_GRADIENT = ColorToken("cta_gradient")

    private val SOLID_TOKENS = listOf(
        TEXT_PRIMARY,
        TEXT_SECONDARY,
        ACCENT,
        BACKGROUND,
        SURFACE,
        ON_ACCENT,
    )

    private val ALLOWED: Set<String> = (SOLID_TOKENS + CTA_GRADIENT).map { it.key }.toSet()

    private val HEX = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

    private const val MIN_GRADIENT_STOPS = 2

    fun resolve(context: Context, tokens: Map<String, JsonElement>): PaywallTheme {
        val bundled = PaywallTheme.bundled(context)
        return PaywallTheme(
            textPrimary = solid(tokens, TEXT_PRIMARY) ?: bundled.textPrimary,
            textSecondary = solid(tokens, TEXT_SECONDARY) ?: bundled.textSecondary,
            accent = solid(tokens, ACCENT) ?: bundled.accent,
            background = solid(tokens, BACKGROUND) ?: bundled.background,
            surface = solid(tokens, SURFACE) ?: bundled.surface,
            onAccent = solid(tokens, ON_ACCENT) ?: bundled.onAccent,
            ctaGradient = gradient(tokens) ?: bundled.ctaGradient,
        )
    }

    /** Human-readable reasons a token was ignored; the parser records these without failing. */
    internal fun problems(tokens: Map<String, JsonElement>): List<String> = buildList {
        tokens.forEach { (key, element) ->
            when {
                key !in ALLOWED -> add("token '$key' ignored: not on the allow-list")
                key == CTA_GRADIENT.key && gradient(tokens) == null ->
                    add("token '$key' ignored: needs $MIN_GRADIENT_STOPS or more valid hex stops")
                key != CTA_GRADIENT.key && hex(element) == null ->
                    add("token '$key' ignored: not a #RRGGBB or #AARRGGBB string")
            }
        }
    }

    private fun solid(tokens: Map<String, JsonElement>, token: ColorToken): Int? =
        tokens[token.key]?.let(::hex)

    private fun gradient(tokens: Map<String, JsonElement>): IntArray? {
        val stops = tokens[CTA_GRADIENT.key] as? JsonArray ?: return null
        val colors = stops.mapNotNull(::hex)
        return if (colors.size >= MIN_GRADIENT_STOPS && colors.size == stops.size) {
            colors.toIntArray()
        } else {
            null
        }
    }

    private fun hex(element: JsonElement): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        val raw = primitive.content.trim()
        if (!HEX.matches(raw)) return null
        return parse(raw)
    }

    // Parsed by hand rather than via Color.parseColor: the regex has already guaranteed the
    // shape, and this pins the 8-digit form to #AARRGGBB instead of the CSS #RRGGBBAA.
    private fun parse(validated: String): Int? {
        val digits = validated.removePrefix("#")
        val value = digits.toLongOrNull(radix = 16) ?: return null
        return if (digits.length == 6) (0xFF000000L or value).toInt() else value.toInt()
    }
}
