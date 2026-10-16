package io.onboardkit.ads.pool

import android.content.Context
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.AdGate
import com.ads.module.helper.adnative.pool.PoolRequest
import com.ads.module.helper.adnative.pool.PoolRequestPolicy
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.isPlacementEnabled
import io.onboardkit.ads.isPrivacyGoalsNative
import io.onboardkit.config.OnboardKitConfig
import io.onboardkit.remote.RemoteFlags

/**
 * The SDK guard split the way DashDrama splits its refusals: a cause that holds for the whole run
 * keeps the caller from owning the slot, a passing one (offline, consent pending) does not. Every
 * reason is an [AdSkipReason] key, so [skipReasonOf] turns it back into the analytics value.
 *
 * Together the two answers refuse exactly when `AdsGuard.skipReason` or `AdGate.skipReason` would.
 */
internal class OnboardingPoolPolicy(
    context: Context,
    private val placementOf: (String) -> AdPlacement? = PoolPlacementMapper::placementFor,
    private val providerInstalled: () -> Boolean = { OnboardingSdk.provider() != null },
    private val config: () -> OnboardKitConfig? = OnboardingSdk::configOrNull,
    private val flags: () -> RemoteFlags = OnboardingSdk::flags,
    private val canRequestAds: () -> Boolean = ConsentCenter::canRequestAds,
    private val consentFormShowing: () -> Boolean = ConsentCenter::isFormShowing,
) : PoolRequestPolicy {
    private val appContext = context.applicationContext

    override fun configRefusal(request: PoolRequest): String? = configReason(request)?.key

    override fun transientRefusal(request: PoolRequest): String? = transientReason()?.key

    /** Most permanent first, as the guard orders them, so the reported reason is the root cause. */
    private fun configReason(request: PoolRequest): AdSkipReason? {
        if (AdGate.isPurchased(appContext)) return AdSkipReason.PREMIUM
        if (!providerInstalled()) return AdSkipReason.NO_PROVIDER
        if (!AdBehavior.bool("global.ads_enabled")) return AdSkipReason.ADS_OFF_BY_REMOTE
        val cfg = config() ?: return AdSkipReason.ADS_OFF_IN_CONFIG
        placementOf(request.key)?.let { placement ->
            if (!flags().isPlacementEnabled(placement)) return AdSkipReason.PLACEMENT_OFF_BY_REMOTE
            if (placement.isPrivacyGoalsNative && !OnboardingSdk.offersPrivacyGoals(cfg)) {
                return AdSkipReason.ADS_OFF_IN_CONFIG
            }
            if ((placement == AdPlacement.WelcomeBack1 || placement == AdPlacement.WelcomeBack2) &&
                !OnboardingSdk.offersWelcomeBack(cfg)
            ) {
                return AdSkipReason.ADS_OFF_IN_CONFIG
            }
        }
        if (request.ids.none(String::isNotBlank)) return AdSkipReason.NO_AD_UNIT
        // The key the loader re-checks before every id, so both sides judge the same entry.
        if (!AdGate.placementPassesUaGate(request.adConfigKey ?: request.key)) return AdSkipReason.UA_GATE
        return null
    }

    // AdSkipReason has no form-showing value and is not edited: a visible form reads as no consent yet.
    private fun transientReason(): AdSkipReason? = when {
        AdGate.areRequestsHeld() -> AdSkipReason.REQUESTS_HELD
        !AdGate.isNetworkAvailable(appContext) -> AdSkipReason.OFFLINE
        !canRequestAds() -> AdSkipReason.CONSENT_NOT_GRANTED
        consentFormShowing() -> AdSkipReason.CONSENT_NOT_GRANTED
        else -> null
    }
}

/** The analytics reason behind a pool refusal; null for a reason no [AdSkipReason] carries. */
internal fun skipReasonOf(reason: String): AdSkipReason? = AdSkipReason.entries.firstOrNull { it.key == reason }
