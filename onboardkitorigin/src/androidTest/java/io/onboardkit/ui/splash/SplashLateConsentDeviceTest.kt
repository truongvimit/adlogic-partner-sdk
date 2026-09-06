package io.onboardkit.ui.splash

import android.app.Activity
import android.app.Application
import android.content.Context
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
import io.onboardkit.ads.OnboardingAdProvider
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
            OnboardingSdk.setCanRequestAds(true)
            ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)
        }
        // Public persisted state selects the existing skipped-flow host callback after splash.
        runBlocking {
            OnboardingSdk.reset()
            OnboardingSdk.markCompleted()
        }
    }

    @Test
    fun authorityPublishedByRemoteHookRequestsEachSplashSlotExactlyOnce() {
        runSplash(finalConsentAllowed = true, hostAllowsAds = true)
        assertEquals(1, LateConsentFixture.provider.bannerLoads)
        assertEquals(1, LateConsentFixture.provider.interstitialLoads)
        assertEquals(listOf(true, true), LateConsentFixture.provider.requestAuthorizations.toList())
    }

    @Test
    fun finalDeniedAuthoritySettlesWithoutWaitingForTheAdBudget() {
        runSplash(finalConsentAllowed = false, hostAllowsAds = true)
        assertEquals(0, LateConsentFixture.provider.bannerLoads)
        assertEquals(0, LateConsentFixture.provider.interstitialLoads)
        assertFalse(OnboardingSdk.canRequestAds())
    }

    @Test
    fun hostOffRemainsOffWhenTheRemoteHookPublishesConsent() {
        runSplash(finalConsentAllowed = true, hostAllowsAds = false)
        assertEquals(0, LateConsentFixture.provider.bannerLoads)
        assertEquals(0, LateConsentFixture.provider.interstitialLoads)
        assertFalse(OnboardingSdk.canRequestAds())
    }

    private fun runSplash(finalConsentAllowed: Boolean, hostAllowsAds: Boolean) {
        instrumentation.runOnMainSync {
            LateConsentFixture.finalConsentAllowed = finalConsentAllowed
            OnboardingSdk.setCanRequestAds(hostAllowsAds)
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
                assertEquals(1, LateConsentFixture.remoteHookCalls)
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

/** A real host splash subclass using only its supported consent/remote hooks. */
class LateConsentSplashDeviceActivity : ObSplashActivity() {
    override suspend fun onConsentRequired(): Boolean {
        ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)
        return false
    }

    override fun onRemoteFetched() {
        LateConsentFixture.remoteHookCalls++
        LateConsentFixture.bannerLoadsBeforeAuthority = LateConsentFixture.provider.bannerLoads
        LateConsentFixture.interstitialLoadsBeforeAuthority = LateConsentFixture.provider.interstitialLoads
        // Represents a host CMP result arriving before the final request checkpoint.
        // It deliberately does not change OnboardingSdk's separate host-off policy.
        ConsentCenter.setHostConsent(LateConsentFixture.finalConsentAllowed, personalized = false)
    }
}

private object LateConsentFixture {
    var installed = false
    var finalConsentAllowed = false
    var bannerLoadsBeforeAuthority = -1
    var interstitialLoadsBeforeAuthority = -1
    var remoteHookCalls = 0
    var finished = CountDownLatch(1)
    val outcomes = CopyOnWriteArrayList<OnboardingOutcome>()
    val provider = SettlingSplashHostProvider()

    fun reset() {
        finalConsentAllowed = false
        bannerLoadsBeforeAuthority = -1
        interstitialLoadsBeforeAuthority = -1
        remoteHookCalls = 0
        finished = CountDownLatch(1)
        outcomes.clear()
        provider.reset()
    }
}

/** Public host-provider seam: records admission, then returns no fill to settle the real barriers. */
private class SettlingSplashHostProvider : OnboardingAdProvider {
    var bannerLoads = 0
    var interstitialLoads = 0
    val requestAuthorizations = CopyOnWriteArrayList<Boolean>()
    fun reset() {
        bannerLoads = 0
        interstitialLoads = 0
        requestAuthorizations.clear()
    }
    override fun isPremium(context: Context) = false
    override fun preloadNative(activity: Activity, request: NativeAdRequest) = Unit
    override fun isNativeReady(placement: AdPlacement) = false
    override fun isNativeLoading(placement: AdPlacement) = false
    override fun bindNative(activity: Activity, placement: AdPlacement, container: ViewGroup, shimmer: View?, listener: AdEventListener?) = false
    override fun releaseNative(placement: AdPlacement) = Unit
    override fun loadInterstitial(context: Context, placement: AdPlacement, unit: InterstitialAdUnit, listener: AdEventListener?) {
        interstitialLoads++
        requestAuthorizations += OnboardingSdk.canRequestAds()
        listener?.onFailedToLoad()
    }
    override fun isInterstitialReady(placement: AdPlacement) = false
    override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) = Unit
    override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) {
        bannerLoads++
        requestAuthorizations += OnboardingSdk.canRequestAds()
        listener?.onFailedToLoad()
    }
    override fun suppressAppResume(activityClass: Class<out Activity>) = Unit
    override fun releaseAll() = Unit
}
