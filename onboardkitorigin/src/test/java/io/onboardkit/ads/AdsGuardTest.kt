package io.onboardkit.ads

import android.content.Context
import android.content.ContextWrapper
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import io.onboardkit.config.AdsConfig
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
        // Remote off as well: entitlement still has to be the reported cause
        val guard = guard(flags = RemoteFlags(enableAllAds = false))
        Entitlement.install(premium(true))
        try {
            assertEquals(AdSkipReason.PREMIUM, guard.skipReason(context, AdPlacement.Language1))
        } finally {
            Entitlement.install(premium(false))
        }
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
        val guard = guard(providerInstalled = false, flags = RemoteFlags(enableAllAds = false))
        assertEquals(AdSkipReason.NO_PROVIDER, guard.skipReason(context, AdPlacement.Language1))
    }

    @Test
    fun `a premium user without a provider is reported as missing the provider`() {
        val guard = guard(providerInstalled = false)
        Entitlement.install(premium(true))
        try {
            assertEquals(AdSkipReason.NO_PROVIDER, guard.skipReason(context, AdPlacement.Language1))
        } finally {
            Entitlement.install(premium(false))
        }
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
    fun `the confirm modal answers to its own remote switch, not the language one`() {
        val ads = AdsConfig(
            languageNative = NativeAdUnit("language-native"),
            languageConfirmNative = NativeAdUnit("confirm-native"),
        )
        // Turning the LFO natives off must not take the modal's slot with it: they are sold
        // separately, and one key doing both jobs makes the second unsellable.
        val langOff = guard(config = config(ads), flags = RemoteFlags(adsLanguageNative = false))
        assertEquals(
            AdSkipReason.PLACEMENT_OFF_BY_REMOTE,
            langOff.skipReason(context, AdPlacement.Language1),
        )
        assertNull(langOff.skipReason(context, AdPlacement.LanguageConfirm))

        val confirmOff =
            guard(config = config(ads), flags = RemoteFlags(adsLanguageConfirmNative = false))
        assertEquals(
            AdSkipReason.PLACEMENT_OFF_BY_REMOTE,
            confirmOff.skipReason(context, AdPlacement.LanguageConfirm),
        )
        assertNull(confirmOff.skipReason(context, AdPlacement.Language1))
    }

    @Test
    fun `the confirm modal never borrows the language ad unit`() {
        // languageConfirmNative unset must report NO_AD_UNIT rather than silently spending
        // `native_lang` — the mistake AdsConfig's doc records OB5 making against OB3.
        assertEquals(
            AdSkipReason.NO_AD_UNIT,
            guard().skipReason(context, AdPlacement.LanguageConfirm),
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
        // The splash resolves the returning-user slot, which the caller passes explicitly
        assertNull(
            guard().skipReason(
                context,
                AdPlacement.QuestionInterstitial,
                InterstitialAdUnit("remote-override"),
            ),
        )
    }

    private fun guard(
        providerInstalled: Boolean = true,
        config: OnboardKitConfig = config(),
        flags: RemoteFlags = RemoteFlags(),
        canRequestAds: Boolean = true,
    ): AdsGuard = AdsGuard(providerInstalled, { config }, { flags }, { canRequestAds })

    private fun premium(value: Boolean) = object : EntitlementSource {
        override fun isPremium(context: Context) = value
    }

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
