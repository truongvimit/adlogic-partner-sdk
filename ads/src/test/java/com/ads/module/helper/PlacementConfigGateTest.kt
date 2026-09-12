package com.ads.module.helper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.helper.interstitial.InterstitialAutoBuffer
import com.ads.module.helper.interstitial.InterstitialBufferOptions
import com.ads.module.helper.interstitial.InterstitialFrequency
import com.google.android.gms.ads.LoadAdError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every load/show path resolves the placement's own configuration, not the caller's opinion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlacementConfigGateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
        InterstitialAdManager.releaseAll()
    }

    @After
    fun tearDown() {
        InterstitialAutoBuffer.stop()
        InterstitialAutoBuffer.configure(InterstitialBufferOptions())
        InterstitialFrequency.reset()
        InterstitialAdManager.releaseAll()
        ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)
        AdRemoteConfig.reset()
    }

    private fun install(vararg units: Pair<String, AdUnitConfig>) =
        AdRemoteConfig.update(AdRemoteConfig(units.toMap()))

    private fun unit(id: String, enabled: Boolean = true, ua: Boolean = false) =
        AdUnitConfig(id = id, isEnable = enabled, enableUaCheck = ua)

    /** Records what the load path decided without reaching the vendor SDK. */
    private fun loadOutcome(placement: String): Boolean {
        var failed = false
        InterstitialAdManager.load(context, placement, listener = object : AdCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError?) { failed = true }
        })
        return failed
    }

    @Test
    fun `a disabled base key switches off every floor above it`() {
        install(
            "inter_back" to unit("all-price", enabled = false),
            "inter_back_high" to unit("high"),
        )

        assertEquals(emptyList<String>(), AdRemoteConfig.getInstance().tiersFor("inter_back"))
        assertTrue(loadOutcome("inter_back"))
        assertFalse(InterstitialAdManager.isLoading("inter_back"))
        assertEquals(
            AdSkipReason.DISABLED_CONFIG,
            InterstitialAdManager.showSkipReason(context, "inter_back"),
        )
    }

    @Test
    fun `an enabled base key still serves its floors`() {
        install(
            "inter_back" to unit("all-price"),
            "inter_back_high" to unit("high"),
        )

        assertEquals(listOf("high", "all-price"), AdRemoteConfig.getInstance().tiersFor("inter_back"))
        assertNull(AdGate.placementSkipReason(context, "inter_back", checkNetwork = false))
    }

    @Test
    fun `enable_ua_check blocks an organic install`() {
        // No attribution has landed in this process, so the module reports the install organic.
        install("inter_back" to unit("all-price", ua = true))

        assertEquals(
            AdSkipReason.UA_GATE,
            AdGate.placementSkipReason(context, "inter_back", checkNetwork = false),
        )
        assertTrue(loadOutcome("inter_back"))
        assertEquals(
            AdSkipReason.UA_GATE,
            InterstitialAdManager.showSkipReason(context, "inter_back"),
        )
    }

    @Test
    fun `consent not yet granted blocks the placement`() {
        install("inter_back" to unit("all-price"))
        ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)

        assertEquals(
            AdSkipReason.CONSENT_NOT_GRANTED,
            AdGate.placementSkipReason(context, "inter_back", checkNetwork = false),
        )
        assertTrue(loadOutcome("inter_back"))
    }

    @Test
    fun `a placement absent from the payload never requests`() {
        install("inter_back" to unit("all-price"))

        assertEquals(emptyList<String>(), AdGate.adUnitIds("not_in_json"))
        assertEquals(
            AdSkipReason.DISABLED_CONFIG,
            AdGate.placementSkipReason(context, "not_in_json", checkNetwork = false),
        )
        assertTrue(loadOutcome("not_in_json"))
        assertFalse(InterstitialAdManager.isLoading("not_in_json"))
    }

    @Test
    fun `a placement absent from the payload leaves an explicitly loaded key alone`() {
        install("inter_back" to unit("all-price"))

        assertNull(
            "Only declared placements take the config's authority at show time",
            AdRemoteConfig.getInstance().takeIf { it.declares("host_owned") },
        )
        assertEquals(
            AdSkipReason.NOT_READY,
            InterstitialAdManager.showSkipReason(context, "host_owned"),
        )
    }

    @Test
    fun `an unelapsed placement interval blocks the show`() {
        install("inter_all" to unit("all-price"))
        InterstitialAutoBuffer.configure(
            InterstitialBufferOptions(
                independentIntervalPlacements = setOf("inter_all"),
                placements = listOf("inter_all"),
                intervalMsByPlacement = mapOf("inter_all" to 30_000L),
            ),
        )
        InterstitialAutoBuffer.start(context)
        InterstitialFrequency.recordAction("inter_all")
        InterstitialFrequency.recordAction("inter_all")

        assertTrue(InterstitialFrequency.remainingMs(context, "inter_all") > 0L)
        assertEquals(
            AdSkipReason.CAPPED_BY_MODULE,
            InterstitialAdManager.showSkipReason(context, "inter_all"),
        )
    }
}
