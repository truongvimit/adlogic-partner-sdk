package io.paykit.config

import io.paykit.PaywallPlacement
import io.paykit.design.TokenResolver
import io.paykit.model.PackageType
import io.paykit.model.PaywallPackage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** A validated paywall definition. [problems] lists everything that was dropped or corrected. */
data class PaywallConfig(
    val configVersion: Int,
    val placements: Set<PaywallPlacement>,
    val packages: List<PaywallPackage>,
    /** Literal copy from the console. Non-null wins over [headlineKey] and is not localised. */
    val headline: String?,
    val headlineKey: String?,
    val benefits: List<String>,
    val benefitKeys: List<String>,
    val cta: String?,
    val ctaKey: String?,
    val tokens: Map<String, JsonElement>,
    val exitButtonEnabled: Boolean,
    /** Null when the document says nothing, which leaves the host's own delay in charge. */
    val exitButtonDelayMs: Long?,
    val continueWithAdsEnabled: Boolean,
    val restoreEnabled: Boolean,
    val problems: List<String>,
) {

    /** Falls back to the first row, so the UI always has something selected to buy. */
    val preselectedId: String?
        get() = packages.firstOrNull { it.preselected }?.id ?: packages.firstOrNull()?.id
}

sealed interface ConfigParseResult {
    data class Success(val config: PaywallConfig) : ConfigParseResult
    data class Failure(val reason: String) : ConfigParseResult
}

/**
 * Parses the wire format into [PaywallConfig]. Never throws: a document this cannot use returns
 * [ConfigParseResult.Failure] with a reason and the store drops one level down the chain.
 */
object PaywallConfigParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = true
    }

    private val PACKAGE_TYPES = mapOf(
        "subs" to PackageType.SUBSCRIPTION,
        "inapp" to PackageType.IN_APP,
        "consumable" to PackageType.CONSUMABLE,
    )

    private const val MAX_DISCOUNT = 99

    fun parse(raw: String): ConfigParseResult {
        if (raw.isBlank()) return ConfigParseResult.Failure("empty document")

        val dto = runCatching { json.decodeFromString(PaywallConfigDto.serializer(), raw) }
            .getOrElse { error ->
                val detail = error.message ?: error.javaClass.simpleName
                return ConfigParseResult.Failure("decode failed: $detail")
            }

        val problems = mutableListOf<String>()
        val packages = readPackages(dto.packages, problems)
        if (packages.isEmpty()) {
            val detail = problems.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: "packages empty"
            return ConfigParseResult.Failure("no usable package: $detail")
        }

        problems += TokenResolver.problems(dto.tokens)

        val config = PaywallConfig(
            configVersion = dto.configVersion,
            placements = readPlacements(dto.placements, problems),
            packages = packages,
            headline = dto.copyBlock?.headline?.trimOrNull(),
            headlineKey = dto.copyBlock?.headlineKey?.trimOrNull(),
            benefits = dto.copyBlock?.benefits.orEmpty().mapNotNull { it.trimOrNull() },
            benefitKeys = dto.copyBlock?.benefitKeys.orEmpty().mapNotNull { it.trimOrNull() },
            cta = dto.copyBlock?.cta?.trimOrNull(),
            ctaKey = dto.copyBlock?.ctaKey?.trimOrNull(),
            tokens = dto.tokens,
            exitButtonEnabled = dto.exitButton?.enabled ?: true,
            exitButtonDelayMs = dto.exitButton?.delayMs?.coerceAtLeast(0),
            continueWithAdsEnabled = dto.continueWithAds?.enabled ?: false,
            restoreEnabled = dto.restore?.enabled ?: false,
            problems = problems.toList(),
        )
        return ConfigParseResult.Success(config)
    }

    private fun readPlacements(
        keys: List<String>,
        problems: MutableList<String>,
    ): Set<PaywallPlacement> = keys.mapNotNullTo(LinkedHashSet()) { key ->
        PaywallPlacement.fromKey(key.trim()).also {
            if (it == null) problems += "placement '$key' ignored: unknown key"
        }
    }

    // Broken rows drop individually. Throwing the whole document away over one bad package would
    // hand the user a paywall with nothing to buy.
    private fun readPackages(
        dtos: List<PaywallPackageDto>,
        problems: MutableList<String>,
    ): List<PaywallPackage> {
        val seen = mutableSetOf<String>()
        var preselectedTaken = false

        return dtos.mapIndexedNotNull { index, dto ->
            val id = dto.id?.trim().orEmpty()
            if (id.isBlank()) {
                problems += "package[$index] dropped: blank id"
                return@mapIndexedNotNull null
            }
            if (!seen.add(id)) {
                problems += "package '$id' dropped: duplicate id"
                return@mapIndexedNotNull null
            }
            val type = PACKAGE_TYPES[dto.type?.trim()?.lowercase()]
            if (type == null) {
                problems += "package '$id' dropped: unknown type '${dto.type}'"
                return@mapIndexedNotNull null
            }

            val discount = if (dto.discountPercent in 0..MAX_DISCOUNT) {
                dto.discountPercent
            } else {
                problems += "package '$id': discount_percent ${dto.discountPercent} out of range"
                0
            }

            val preselected = dto.preselected && !preselectedTaken
            if (dto.preselected && preselectedTaken) {
                problems += "package '$id': preselected ignored, an earlier package claimed it"
            }
            if (preselected) preselectedTaken = true

            PaywallPackage(
                id = id,
                type = type,
                basePlanId = dto.basePlanId?.trimOrNull(),
                offerId = dto.offerId?.trimOrNull(),
                title = dto.title?.trimOrNull(),
                titleKey = dto.titleKey?.trimOrNull(),
                subtitle = dto.subtitle?.trimOrNull(),
                subtitleKey = dto.subtitleKey?.trimOrNull(),
                badge = dto.badge?.trimOrNull(),
                discountPercent = discount,
                preselected = preselected,
            )
        }
    }

    private fun String.trimOrNull(): String? = trim().takeIf { it.isNotEmpty() }
}
