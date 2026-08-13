package io.onboardkit.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.OnboardKitConfig
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.remote.RemoteFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The permission matrix: one case per skip reason, plus the ordering that decides which reason
 * a caller is told about when more than one applies.
 */
class AdsGuardTest {

    private val context: Context = ContextWrapper(null)

    @Test
    fun `allows a fully configured placement`() {
        assertNull(guard().skipReason(context, AdPlacement.Language1))
    }

    @Test
    fun `premium beats every other reason`() {
        // Remote off and no provider as well: entitlement still has to be the reported cause
        val guard = guard(
            provider = FakeAdProvider(isPremium = true),
            flags = RemoteFlags(enableAllAds = false),
        )
        assertEquals(AdSkipReason.PREMIUM, guard.skipReason(context, AdPlacement.Language1))
    }

    @Test
    fun `no ad may be requested until consent answers`() {
        val guard = guard(canRequestAds = false)
        assertEquals(
            AdSkipReason.CONSENT_NOT_GRANTED,
            guard.skipReason(context, AdPlacement.Language1),
        )
    }

    @Test
    fun `missing provider is reported before config and remote`() {
        val guard = guard(provider = null, flags = RemoteFlags(enableAllAds = false))
        assertEquals(AdSkipReason.NO_PROVIDER, guard.skipReason(context, AdPlacement.Language1))
    }

    @Test
    fun `ads disabled in config`() {
        val guard = guard(config = config(AdsConfig(enabled = false, languageNative = NativeAdUnit("n"))))
        assertEquals(
            AdSkipReason.ADS_OFF_IN_CONFIG,
            guard.skipReason(context, AdPlacement.Language1),
        )
    }

    @Test
    fun `master remote switch is reported separately from the placement switch`() {
        val master = guard(flags = RemoteFlags(enableAllAds = false))
        assertEquals(
            AdSkipReason.ADS_OFF_BY_REMOTE,
            master.skipReason(context, AdPlacement.Language1),
        )

        val placement = guard(flags = RemoteFlags(adsLanguageNative = false))
        assertEquals(
            AdSkipReason.PLACEMENT_OFF_BY_REMOTE,
            placement.skipReason(context, AdPlacement.Language1),
        )
    }

    @Test
    fun `placement without an ad unit`() {
        assertEquals(
            AdSkipReason.NO_AD_UNIT,
            guard().skipReason(context, AdPlacement.QuestionNative),
        )
    }

    @Test
    fun `explicit unit overrides the compiled slot`() {
        // The splash resolves a remote id override the compiled config knows nothing about
        assertNull(
            guard().skipReason(
                context,
                AdPlacement.QuestionInterstitial,
                InterstitialAdUnit("remote-override"),
            ),
        )
    }

    @Test
    fun `interstitial interval blocks only inside the window`() {
        val provider = FakeAdProvider(lastInterstitialShownAtMs = 1_000L)
        val flags = RemoteFlags(interstitialIntervalSec = 30)

        val tooSoon = guard(provider = provider, flags = flags, nowMs = 10_000L)
        assertEquals(
            AdSkipReason.INTERVAL_NOT_ELAPSED,
            tooSoon.skipReason(context, AdPlacement.SplashInterstitial),
        )

        val elapsed = guard(provider = provider, flags = flags, nowMs = 40_000L)
        assertNull(elapsed.skipReason(context, AdPlacement.SplashInterstitial))
    }

    @Test
    fun `interval rule ignores non-interstitial formats`() {
        val guard = guard(
            provider = FakeAdProvider(lastInterstitialShownAtMs = 1_000L),
            flags = RemoteFlags(interstitialIntervalSec = 30),
            nowMs = 10_000L,
        )
        assertNull(guard.skipReason(context, AdPlacement.Language1))
    }

    @Test
    fun `interval of zero disables the rule`() {
        val guard = guard(
            provider = FakeAdProvider(lastInterstitialShownAtMs = 1_000L),
            flags = RemoteFlags(interstitialIntervalSec = 0),
            nowMs = 1_001L,
        )
        assertNull(guard.skipReason(context, AdPlacement.SplashInterstitial))
    }

    @Test
    fun `click cap blocks at the cap and is disabled at zero`() {
        val provider = FakeAdProvider(clicksToday = 3)
        val capped = guard(provider = provider, flags = RemoteFlags(clickCapPerDay = 3))
        assertEquals(
            AdSkipReason.CLICK_CAP_REACHED,
            capped.skipReason(context, AdPlacement.Language1),
        )

        val off = guard(provider = provider, flags = RemoteFlags(clickCapPerDay = 0))
        assertNull(off.skipReason(context, AdPlacement.Language1))
    }

    private fun guard(
        provider: OnboardingAdProvider? = FakeAdProvider(),
        config: OnboardKitConfig = config(),
        flags: RemoteFlags = RemoteFlags(),
        canRequestAds: Boolean = true,
        nowMs: Long = 0L,
    ): AdsGuard = AdsGuard(provider, { config }, { flags }, { canRequestAds }, { nowMs })

    private fun config(
        ads: AdsConfig = AdsConfig(
            languageNative = NativeAdUnit("language-native"),
            splashInterstitial = InterstitialAdUnit("splash-inter"),
        ),
    ): OnboardKitConfig = onboardKitConfig {
        defaultSteps()
        this.ads = ads
    }.getOrThrow()
}

/** Answers the guard's questions with fixed values; every ad operation is a no-op. */
private class FakeAdProvider(
    private val isPremium: Boolean = false,
    private val lastInterstitialShownAtMs: Long = 0L,
    private val clicksToday: Int = 0,
) : OnboardingAdProvider {

    override fun isPremium(context: Context): Boolean = isPremium

    override fun lastInterstitialShownAtMs(context: Context): Long = lastInterstitialShownAtMs

    override fun clicksToday(context: Context, adUnitId: String): Int = clicksToday

    override fun preloadNative(activity: Activity, request: NativeAdRequest) = Unit

    override fun isNativeReady(placement: AdPlacement): Boolean = false

    override fun isNativeLoading(placement: AdPlacement): Boolean = false

    override fun bindNative(
        activity: Activity,
        placement: AdPlacement,
        container: ViewGroup,
        shimmer: View?,
        listener: AdEventListener?,
    ): Boolean = false

    override fun releaseNative(placement: AdPlacement) = Unit

    override fun loadInterstitial(
        context: Context,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        listener: AdEventListener?,
    ) = Unit

    override fun isInterstitialReady(placement: AdPlacement): Boolean = false

    override fun showInterstitial(
        activity: Activity,
        placement: AdPlacement,
        callback: ObInterstitialCallback,
    ) = Unit

    override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) = Unit

    override fun suppressAppResume(activityClass: Class<out Activity>) = Unit

    override fun releaseAll() = Unit
}
