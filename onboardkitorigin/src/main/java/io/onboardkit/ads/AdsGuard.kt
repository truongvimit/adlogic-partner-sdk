package io.onboardkit.ads

import android.content.Context
import io.onboardkit.config.AdUnitTiers
import io.onboardkit.config.OnboardKitConfig
import io.onboardkit.core.ObLog
import io.onboardkit.core.StepId
import io.onboardkit.core.StepType
import io.onboardkit.remote.RemoteFlags
import io.trackkit.AdFormat

/**
 * The single place that answers "may this placement show".
 *
 * Screens ask the guard and act on the answer; they never re-check entitlement, remote flags or
 * frequency themselves. Checks run most-permanent-first so the reported reason is the root cause
 * rather than whichever test happened to run last — a premium user with a broken ad unit is
 * reported as premium, which is the truth a dashboard can act on.
 */
class AdsGuard internal constructor(
    private val provider: OnboardingAdProvider?,
    private val config: () -> OnboardKitConfig?,
    private val flags: () -> RemoteFlags,
    private val canRequestAds: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun isPremium(context: Context): Boolean = provider?.isPremium(context) == true

    /**
     * `null` means the ad may show.
     *
     * @param unit the ad units actually about to be requested, when the caller resolved them
     *   itself. A remote id override is invisible in [OnboardKitConfig], so judging the compiled
     *   slot would report [AdSkipReason.NO_AD_UNIT] for a placement that does have one.
     */
    fun skipReason(
        context: Context,
        placement: AdPlacement,
        unit: AdUnitTiers? = null,
    ): AdSkipReason? {
        val reason = evaluate(context, placement, unit)
        log(placement, reason)
        return reason
    }

    private fun evaluate(
        context: Context,
        placement: AdPlacement,
        unit: AdUnitTiers?,
    ): AdSkipReason? {
        if (isPremium(context)) return AdSkipReason.PREMIUM
        // Before anything the app can configure: no ad request may go out until consent answers.
        if (!canRequestAds()) return AdSkipReason.CONSENT_NOT_GRANTED
        if (provider == null) return AdSkipReason.NO_PROVIDER

        val cfg = config() ?: return AdSkipReason.ADS_OFF_IN_CONFIG
        if (!cfg.ads.enabled) return AdSkipReason.ADS_OFF_IN_CONFIG

        val remote = flags()
        if (!remote.enableAllAds) return AdSkipReason.ADS_OFF_BY_REMOTE
        if (!remote.isPlacementEnabled(placement)) return AdSkipReason.PLACEMENT_OFF_BY_REMOTE

        val slot = unit ?: cfg.ads.unitFor(placement)
        if (slot == null || slot.tierCount == 0) return AdSkipReason.NO_AD_UNIT

        // Both counters live in the ads module's SharedPreferences. Reading them on every call
        // would put disk I/O on the main thread for rules that ship disabled, so they are only
        // touched once a partner turns the rule on.
        if (placement.format == AdFormat.INTERSTITIAL && remote.interstitialIntervalSec > 0) {
            val lastShownAtMs = provider.lastInterstitialShownAtMs(context)
            val elapsed = clock() - lastShownAtMs
            val intervalMs = remote.interstitialIntervalSec * MILLIS_PER_SECOND
            if (lastShownAtMs > 0L && elapsed in 0 until intervalMs) {
                return AdSkipReason.INTERVAL_NOT_ELAPSED
            }
        }

        if (remote.clickCapPerDay > 0) {
            val topTierId = slot.topTierId
            if (topTierId.isNotBlank() &&
                provider.clicksToday(context, topTierId) >= remote.clickCapPerDay
            ) {
                return AdSkipReason.CLICK_CAP_REACHED
            }
        }

        return null
    }

    private fun log(placement: AdPlacement, reason: AdSkipReason?) {
        if (reason == null) {
            ObLog.d(ObLog.Section.GATE, "${placement.key} ALLOWED")
        } else {
            ObLog.w(ObLog.Section.GATE, "${placement.key} BLOCKED reason=${reason.key}")
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * The per-placement remote switch.
 *
 * An extension rather than a field on [AdPlacement] so adding a placement cannot silently default
 * to "enabled" — the `when` is exhaustive and a new entry breaks the build.
 */
internal fun RemoteFlags.isPlacementEnabled(placement: AdPlacement): Boolean = when (placement) {
    AdPlacement.SplashBanner -> adsSplashBanner
    AdPlacement.SplashInterstitial -> adsSplashInter
    AdPlacement.Language1, AdPlacement.Language2 -> adsLanguageNative
    is AdPlacement.StepNative -> adsContentNative
    is AdPlacement.StepFullScreen, AdPlacement.Ob5 -> adsFullScreenNative
    AdPlacement.QuestionNative -> adsQuestionNative
    AdPlacement.QuestionInterstitial -> adsQuestionInter
    AdPlacement.AppResume -> adsAppResume
}
