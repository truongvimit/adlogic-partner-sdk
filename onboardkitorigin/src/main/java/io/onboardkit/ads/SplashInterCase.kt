package io.onboardkit.ads

import com.ads.module.config.AdRemoteConfig
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.ui.splash.SplashEntry

/**
 * The splash interstitial one launch spends.
 *
 * Each user segment has a master position: `inter_splash` for new users, `inter_splash_o` for
 * returning users (`inter_splash` while `_o` is not declared). Switched off, it silences every
 * splash interstitial for that segment, entries included. An entry launch (`inter_noti`,
 * `inter_widget`, `inter_uninstall`) spends its own key while that key is declared and on, and
 * otherwise falls back to its segment's position. The load and every show of its fill resolve the
 * same case, against the config current at that moment, so they cannot disagree about it.
 */
internal class SplashInterCase(
    val entry: SplashEntry?,
    val returningUser: Boolean,
    /** What the host's `splashInterstitialOverride()` returned for this launch. */
    private val hostUnit: InterstitialAdUnit?,
) {

    /** [adConfigKey] governs the UA gate and behavior overrides; a null or empty [unit] means no ad. */
    data class Resolved(val adConfigKey: String?, val unit: InterstitialAdUnit?)

    fun resolve(): Resolved {
        val ads = OnboardingSdk.configOrNull()?.ads ?: return Resolved(null, null)
        val config = AdRemoteConfig.getInstance()
        val splashKey = ads.placementKeyFor(AdPlacement.SplashInterstitial)
        val oldKey = splashKey?.plus("_o")?.takeIf { returningUser && config.declares(it) }
        val segmentKey = oldKey ?: splashKey
        val segmentUnit = if (returningUser) ads.splashInterstitialOldUser ?: ads.splashInterstitial else ads.splashInterstitial
        // The segment's master switch: off, no splash interstitial for these users at all.
        if (segmentUnit != null && segmentUnit.tierCount == 0) return Resolved(segmentKey, segmentUnit)
        val entryKey = entry?.interKey
        val entryUnit = entryKey?.takeIf(config::declares)
            ?.let(config::tiersFor)?.takeIf { it.isNotEmpty() }?.let(::InterstitialAdUnit)
        if (entryUnit != null && AdRemoteConfig.remoteDeclares(checkNotNull(entryKey))) return Resolved(entryKey, entryUnit)
        // The host's unit is a fallback: remote declaring the key this launch spends outranks it.
        if (hostUnit != null) {
            val remoteSpeaks = entryKey?.let(AdRemoteConfig::remoteDeclares) == true ||
                segmentKey?.let(AdRemoteConfig::remoteDeclares) == true
            if (!remoteSpeaks) return Resolved(entryKey?.takeIf { entryUnit != null } ?: segmentKey, hostUnit)
        }
        if (entryUnit != null) return Resolved(entryKey, entryUnit)
        return Resolved(segmentKey, segmentUnit)
    }
}
