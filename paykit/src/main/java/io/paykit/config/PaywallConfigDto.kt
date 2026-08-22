package io.paykit.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The wire format, kept apart from `io.paykit.model` on purpose: this shape is owned by whoever
 * edits the remote console, the domain model is owned by the UI.
 */
@Serializable
internal data class PaywallConfigDto(
    @SerialName("config_version") val configVersion: Int = 0,
    @SerialName("placements") val placements: List<String> = emptyList(),
    @SerialName("packages") val packages: List<PaywallPackageDto> = emptyList(),
    // Not named `copy`: a data class property of that name collides with the generated copy().
    @SerialName("copy") val copyBlock: PaywallCopyDto? = null,
    @SerialName("tokens") val tokens: Map<String, JsonElement> = emptyMap(),
    @SerialName("exit_button") val exitButton: ExitButtonDto? = null,
    @SerialName("continue_with_ads") val continueWithAds: ToggleDto? = null,
    @SerialName("restore") val restore: ToggleDto? = null,
)

// `type` stays a String rather than a serializable enum so an unknown value is reported as a
// dropped package instead of being silently coerced to a default.
@Serializable
internal data class PaywallPackageDto(
    @SerialName("id") val id: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("base_plan_id") val basePlanId: String? = null,
    @SerialName("offer_id") val offerId: String? = null,
    // Literal beats key so growth can change copy from the console; the key keeps the localised
    // default in charge whenever no literal is set.
    @SerialName("title") val title: String? = null,
    @SerialName("title_key") val titleKey: String? = null,
    @SerialName("subtitle") val subtitle: String? = null,
    @SerialName("subtitle_key") val subtitleKey: String? = null,
    @SerialName("badge") val badge: String? = null,
    @SerialName("discount_percent") val discountPercent: Int = 0,
    @SerialName("preselected") val preselected: Boolean = false,
)

// Each line comes in two forms: a literal that wins, and a resource key that stays localised.
// Setting the literal is how a copy experiment ships without an app update, at the cost of that
// experiment running in one language.
@Serializable
internal data class PaywallCopyDto(
    @SerialName("headline") val headline: String? = null,
    @SerialName("headline_key") val headlineKey: String? = null,
    @SerialName("benefits") val benefits: List<String> = emptyList(),
    @SerialName("benefit_keys") val benefitKeys: List<String> = emptyList(),
    @SerialName("cta") val cta: String? = null,
    @SerialName("cta_key") val ctaKey: String? = null,
)

// delayMs is nullable so an explicit 0 can turn the delay off remotely; a default of 0 would be
// indistinguishable from a document that omits the field and leaves the host value in charge.
@Serializable
internal data class ExitButtonDto(
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("delay_ms") val delayMs: Long? = null,
)

@Serializable
internal data class ToggleDto(
    @SerialName("enabled") val enabled: Boolean = true,
)
