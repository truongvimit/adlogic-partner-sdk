package com.ads.module.ads

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.AppOpenManager
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.ERainAdConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02FacebookShadow
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.ads.module.helper.interstitial.Tel02RewardedAd
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Int02Application::class,
    shadows = [Int02MobileAdsShadow::class, Int02FacebookShadow::class, WaterfallRewardLoadShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class AdWaterfallCharacterizationTest {
    private lateinit var context: Application
    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val requests get() = WaterfallRewardLoadShadow.requests

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AdBehavior.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
        requests.clear()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context): Boolean = false
        })
        ERainAd.getInstance().init(context, ERainAdConfig(context).apply {
            facebookClientToken = "waterfall-characterization-token"
        })
        AppOpenManager.getInstance().disableAppResume()
        mainLooper.idle()
    }

    @After
    fun tearDown() {
        mainLooper.idleFor(31, TimeUnit.SECONDS)
        ConsentCenter.setHostConsent(false, false)
        AdBehavior.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
        requests.clear()
    }

    @Test
    fun `the next tier is requested exactly when the caller's tier timeout elapses`() {
        AdWaterfall.loadReward(context, listOf(HIGH, MID), 5_000L, RecordingLoad())
        assertEquals("only the first tier before any time passes", 1, requests.size)

        mainLooper.idleFor(4_999, TimeUnit.MILLISECONDS)
        assertEquals("tier advanced before the caller's 5 000 ms timeout", 1, requests.size)

        mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals("tier did not advance at the caller's 5 000 ms timeout", 2, requests.size)
    }

    @Test
    fun `each tier timeout requests the following tier's own ad unit`() {
        AdWaterfall.loadReward(context, listOf(HIGH, MID, ALL), 5_000L, RecordingLoad())
        mainLooper.idleFor(5_000, TimeUnit.MILLISECONDS)
        mainLooper.idleFor(5_000, TimeUnit.MILLISECONDS)

        assertEquals(listOf(HIGH, MID, ALL), requests.map { it.first })
    }

    @Test
    fun `a fill from a tier that already timed out never reaches the caller`() {
        val caller = RecordingLoad()
        AdWaterfall.loadReward(context, listOf(HIGH, MID), 5_000L, caller)
        mainLooper.idleFor(5_000, TimeUnit.MILLISECONDS)
        assertEquals(listOf(HIGH, MID), requests.map { it.first })

        requests.first().second.onAdLoaded(Tel02RewardedAd(HIGH))
        mainLooper.idle()

        assertEquals("late fill from the timed-out tier was delivered", emptyList<String>(), caller.loadedUnits)
    }

    private class RecordingLoad : AdCallback() {
        val loadedUnits = mutableListOf<String?>()
        override fun onRewardAdLoaded(rewardedAd: RewardedAd?) { loadedUnits += rewardedAd?.adUnitId }
        override fun onAdFailedToLoad(adError: LoadAdError?) = Unit
    }

    private companion object {
        const val HIGH = "waterfall-high-floor"
        const val MID = "waterfall-mid-floor"
        const val ALL = "waterfall-all-price"
    }
}

@Implements(value = RewardedAd::class, isInAndroidSdk = false)
class WaterfallRewardLoadShadow {
    companion object {
        val requests = mutableListOf<Pair<String, RewardedAdLoadCallback>>()

        @JvmStatic
        @Implementation
        fun load(context: Context, adUnitId: String, request: AdRequest, callback: RewardedAdLoadCallback) {
            requests += adUnitId to callback
        }
    }
}
