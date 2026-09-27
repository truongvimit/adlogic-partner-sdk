package io.onboardkit.ui.splash

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.config.AdLoadStrategy
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.SplashConfig
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run this class alone in a fresh instrumentation process after clearing the test APK's data. */
@RunWith(AndroidJUnit4::class)
class SplashLateConsentDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun installPublicHost() {
        instrumentation.runOnMainSync {
            if (!LateConsentFixture.installed) {
                assertFalse("Use a fresh instrumentation process", OnboardingSdk.isReady())
                OnboardingSdk.install(app) {
                    adProvider = LateConsentFixture.provider
                    trackkitAutoTracking(false)
                    listener = OnboardingListener { _, outcome ->
                        LateConsentFixture.outcomes += outcome
                        LateConsentFixture.finished.countDown()
                    }
                }
                LateConsentFixture.installed = true
            }
            LateConsentFixture.reset()
            OnboardingSdk.configure(onboardKitConfig {
                splash = SplashConfig(
                    minDisplayTimeMs = 0,
                    remoteFetchTimeoutMs = 100,
                    adLoadStrategy = AdLoadStrategy.SAME_TIME,
                    noInternetPromptEnabled = false,
                    notificationPermissionEnabled = false,
                )
                ads = AdsConfig(
                    splashBanner = BannerAdUnit("host-banner"),
                    splashInterstitial = InterstitialAdUnit("host-interstitial"),
                )
            }.getOrThrow()).getOrThrow()
            ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)
        }
        // Public persisted state selects the existing skipped-flow host callback after splash.
        runBlocking {
            OnboardingSdk.reset()
            OnboardingSdk.markCompleted()
        }
    }

    @Test
    fun consentCompletionRequestsEachSplashSlotExactlyOnce() {
        runSplash(finalConsentAllowed = true)
        assertEquals(1, LateConsentFixture.provider.bannerLoads)
        assertEquals(1, LateConsentFixture.provider.interstitialLoads)
        assertEquals(listOf(true, true), LateConsentFixture.provider.requestAuthorizations.toList())
    }

    @Test
    fun finalDeniedAuthoritySettlesWithoutWaitingForTheAdBudget() {
        runSplash(finalConsentAllowed = false)
        assertEquals(0, LateConsentFixture.provider.bannerLoads)
        assertEquals(0, LateConsentFixture.provider.interstitialLoads)
        assertFalse(ConsentCenter.canRequestAds())
    }

    private fun runSplash(finalConsentAllowed: Boolean) {
        instrumentation.runOnMainSync {
            LateConsentFixture.finalConsentAllowed = finalConsentAllowed
        }
        try {
            ActivityScenario.launch<LateConsentSplashDeviceActivity>(
                Intent(app, LateConsentSplashDeviceActivity::class.java),
            ).use {
                // Remote defaults retain a 3s minimum display and 60s ad budget. Settled slots
                // must hand off promptly; this timeout is far below the ad budget.
                assertTrue("Final attempt must settle, not wait for the 60s ad budget", LateConsentFixture.finished.await(8, TimeUnit.SECONDS))
                assertEquals(0, LateConsentFixture.bannerLoadsBeforeAuthority)
                assertEquals(0, LateConsentFixture.interstitialLoadsBeforeAuthority)
                assertEquals(1, LateConsentFixture.outcomes.size)
                assertTrue(LateConsentFixture.outcomes.single() is OnboardingOutcome.Skipped)
                SystemClock.sleep(250)
                assertEquals("No additional late trigger after handoff", 1, LateConsentFixture.outcomes.size)
            }
        } finally {
            instrumentation.runOnMainSync { ConsentCenter.clearHostConsent() }
        }
    }
}

/** A real host splash subclass using its supported consent hook. */
class LateConsentSplashDeviceActivity : ObSplashActivity() {
    override suspend fun onConsentRequired(): Boolean {
        ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)
        kotlinx.coroutines.delay(100)
        LateConsentFixture.bannerLoadsBeforeAuthority = LateConsentFixture.provider.bannerLoads
        LateConsentFixture.interstitialLoadsBeforeAuthority = LateConsentFixture.provider.interstitialLoads
        ConsentCenter.setHostConsent(LateConsentFixture.finalConsentAllowed, personalized = false)
        return LateConsentFixture.finalConsentAllowed
    }
}

private object LateConsentFixture {
    var installed = false
    var finalConsentAllowed = false
    var bannerLoadsBeforeAuthority = -1
    var interstitialLoadsBeforeAuthority = -1
    var finished = CountDownLatch(1)
    val outcomes = CopyOnWriteArrayList<OnboardingOutcome>()
    val provider = SettlingSplashHostProvider()

    fun reset() {
        finalConsentAllowed = false
        bannerLoadsBeforeAuthority = -1
        interstitialLoadsBeforeAuthority = -1
        finished = CountDownLatch(1)
        outcomes.clear()
        provider.reset()
    }
}

/** Records admission, then returns no fill to settle the real barriers. */
private class SettlingSplashHostProvider : FakeAdProvider() {
    var bannerLoads = 0
    var interstitialLoads = 0
    val requestAuthorizations = CopyOnWriteArrayList<Boolean>()
    fun reset() {
        bannerLoads = 0
        interstitialLoads = 0
        requestAuthorizations.clear()
    }
    override fun loadInterstitial(activity: Activity, placement: AdPlacement, unit: InterstitialAdUnit, adConfigKey: String?, listener: AdEventListener?) {
        interstitialLoads++
        requestAuthorizations += ConsentCenter.canRequestAds()
        listener?.onFailedToLoad()
    }
    override fun loadAndShowInterstitial(
        activity: androidx.appcompat.app.AppCompatActivity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        callback: ObInterstitialCallback,
        timeoutMs: Long,
    ) {
        throw AssertionError("This fixture does not expect a loadAndShow request: ${placement.key}")
    }

    override fun loadBanner(activity: androidx.appcompat.app.AppCompatActivity, unit: BannerAdUnit, listener: AdEventListener) {
        bannerLoads++
        requestAuthorizations += ConsentCenter.canRequestAds()
        listener.onFailedToLoad()
    }
}
