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

    /**
     * Whether an ad-only page is worth putting in the flow at all.
     *
     * A page whose whole content is an ad has nothing to fall back on: when the placement is off
     * — no ad unit configured, the remote flag down, ads compiled off — the page renders as an
     * empty screen with a Skip button. `false` here takes it out of the step list instead.
     *
     * [AdSkipReason.PREMIUM] deliberately answers `true`: a paying user's ad-only pages are
     * governed by [io.onboardkit.config.AdsConfig.skipAdOnlyStepsWhenPremium], which a host may
     * leave off on purpose, and answering `false` here would silently override that choice.
     *
     * Only the permanent reasons are known this early. A configured placement that later fails to
     * fill is a runtime answer, handled by the page itself.
     */
    fun canFillAdOnlyStep(
        context: Context,
        placement: AdPlacement,
        unit: AdUnitTiers? = null,
    ): Boolean {
        val reason = evaluate(context, placement, unit)
        if (reason == null || reason == AdSkipReason.PREMIUM) return true
        ObLog.w(ObLog.Section.GATE, "${placement.key} step removed reason=${reason.key}")
        return false
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

        // Interstitial interval and click cap are enforced by the ads module, which owns the
        // counters both rules read. Enforcing them here as well meant one impression could be
        // subtracted by either layer under a different remote key, and no dashboard could tell
        // which. The skip still surfaces here, reported by the module as `capped_by_module`.
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
    AdPlacement.LanguageConfirm -> adsLanguageConfirmNative
    is AdPlacement.StepNative -> adsContentNative
    is AdPlacement.StepFullScreen, AdPlacement.Ob5 -> adsFullScreenNative
    AdPlacement.QuestionNative -> adsQuestionNative
    AdPlacement.QuestionInterstitial -> adsQuestionInter
    AdPlacement.AppResume -> adsAppResume
}
