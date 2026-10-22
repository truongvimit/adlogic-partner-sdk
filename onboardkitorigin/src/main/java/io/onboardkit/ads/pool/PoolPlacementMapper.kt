package io.onboardkit.ads.pool

import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.helper.adnative.pool.PoolRequest
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.carouselBase
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.config.StepDefinition
import io.onboardkit.core.StepType
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.remote.OnboardingSettings
import io.trackkit.AdFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a placement into the pool request the classic flow would make for it: same ad_config key,
 * template and per-id wait. Remembers the placement behind each key, because the engine and its
 * replays hand back only the key while the policy and analytics need the placement.
 */
internal object PoolPlacementMapper {
    private val placements = ConcurrentHashMap<String, AdPlacement>()

    /** Null for a placement that is not a native; the pool never requests those. */
    fun requestFor(placement: AdPlacement, snapshot: NativeFlowSnapshot): PoolRequest? {
        if (placement.format != AdFormat.NATIVE && placement.format != AdFormat.NATIVE_FULL_SCREEN) return null
        placements[placement.key] = placement
        val adConfigKey = OnboardingSdk.configuredPlacementKey(placement)
        return PoolRequest(
            key = placement.key,
            ids = idsFor(placement, adConfigKey),
            layoutRes = NativeTemplates.layoutForPlacement(placement),
            adConfigKey = adConfigKey,
            tierDeadlineMs = OnboardingSettings.behavior(placement)
                .long("load.tier_timeout_ms", AdBehavior.number("native.load.tier_timeout_ms")),
            trackingKey = placement.carouselBase.key,
        )
    }

    /** The placement a request of [key] was built from; null before any request named it. */
    fun placementFor(key: String): AdPlacement? = placements[key]

    /** A content page without a unit of its own has no slot; a full page always has one. */
    fun pagePlacement(step: StepDefinition): AdPlacement? = when (step.type) {
        StepType.CONTENT -> AdPlacement.StepNative(step.id).takeIf { placement ->
            (OnboardingSdk.configOrNull()?.ads?.nativeUnitFor(placement)?.tierCount ?: 0) > 0
        }
        StepType.AD_FULL_SCREEN -> AdPlacement.StepFullScreen(step.id)
    }

    /**
     * DashDrama requests the declared list as is, a repeated id included. The parser already
     * dropped disabled and blank floors; only the dedup of the classic waterfall is skipped.
     */
    private fun idsFor(placement: AdPlacement, adConfigKey: String?): List<String> {
        val adConfig = AdRemoteConfig.getInstance()
        if (adConfigKey != null && adConfig.declares(adConfigKey)) {
            return adConfig.ads[adConfigKey]?.takeIf { it.isEnable }?.ids.orEmpty()
        }
        return OnboardingSdk.configOrNull()?.ads?.nativeUnitFor(placement)?.tiers.orEmpty()
    }
}
