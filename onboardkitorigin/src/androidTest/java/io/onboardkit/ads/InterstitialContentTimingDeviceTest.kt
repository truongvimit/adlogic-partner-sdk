package io.onboardkit.ads

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.*
import com.google.android.gms.ads.AdActivity
import com.google.android.gms.ads.MobileAds
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real GMA device coverage; exact millisecond boundary checks remain in the unit suite. */
@RunWith(AndroidJUnit4::class)
class InterstitialContentTimingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var scenario: ActivityScenario<InterstitialRealGmaDeviceActivity>
    private lateinit var host: InterstitialRealGmaDeviceActivity

    @Before fun setUp() {
        val initialized = CountDownLatch(1)
        onMain {
            ConsentCenter.setHostConsent(true, false)
            Entitlement.install(object : EntitlementSource {
                override fun isPremium(context: Context) = false
            })
            ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
                setFacebookClientToken("123456789")
                setIdAdResume("")
            })
            ERainAd.getInstance().setIntervalInterstitialAd(0)
            ERainAd.getInstance().setMaxClickAdsPerDay(0)
            ERainAd.getInstance().setCountClickToShowAds(1, 0)
            ERainAd.getInstance().setOpenActivityAfterShowInterAds(false)
            AppOpenManager.getInstance().disableAppResume()
            AdRemoteConfig.initializeFromJson("""{"inter_all":{"id":"$UNIT","isEnable":true}}""")
            MobileAds.initialize(app) { initialized.countDown() }
        }
        assertTrue(initialized.await(45, TimeUnit.SECONDS))
        scenario = ActivityScenario.launch(Intent(app, InterstitialRealGmaDeviceActivity::class.java))
        scenario.onActivity { host = it }
    }

    @After fun tearDown() {
        onMain {
            InterstitialAutoBuffer.stop()
            InterstitialAutoBuffer.configure(InterstitialBufferOptions())
            InterstitialAdManager.release(ALL)
            AdRemoteConfig.reset()
            ConsentCenter.clearHostConsent()
        }
        if (::scenario.isInitialized) scenario.close()
    }

    @Test fun realPreloadStartsTwoSecondsEarlyWithoutTimerPresentation() {
        val started = onMain {
            arm(5_000)
            click(0)
            click(0)
            SystemClock.elapsedRealtime()
        }
        SystemClock.sleep((started + 2_700 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        assertFalse(onMain { InterstitialAdManager.isLoading(ALL) || InterstitialAdManager.isReady(ALL) })
        eventually("Preload should start at approximately 3 seconds", 1_000) {
            onMain { InterstitialAdManager.isLoading(ALL) || InterstitialAdManager.isReady(ALL) }
        }
        val observedAt = SystemClock.elapsedRealtime() - started
        assertTrue(observedAt in 2_950..3_700)
        assertFalse(onMain { InterstitialAdManager.canShow(host, ALL) })
        eventually("The Google test request must fill", 40_000) { onMain { InterstitialAdManager.isReady(ALL) } }
        SystemClock.sleep((started + 5_200 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        assertTrue(onMain { InterstitialAdManager.canShow(host, ALL) })
        assertNoAdActivity()
        Log.i(TAG, "PASS preload_observed_ms=$observedAt show_interval_ms=5000 timer_did_not_show=true")
    }

    @Test fun joiningRealPreloadThenBackgroundCancelsOnlyTheUiWait() {
        lateinit var result: Result
        onMain {
            arm(0)
            click(0)
            click(0)
            InterstitialAdManager.load(host, ALL, listOf(UNIT))
            assertTrue(InterstitialAdManager.isLoading(ALL))
            result = click(5_000)
            assertEquals(0, result.completions)
        }
        scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(1, result.completions)
        assertEquals(listOf(AdSkipReason.SHOW_IN_BACKGROUND), result.skips)
        scenario.moveToState(Lifecycle.State.RESUMED)
        eventually("The shared real preload must still fill", 40_000) { onMain { InterstitialAdManager.isReady(ALL) } }
        SystemClock.sleep(1_000)
        assertEquals(1, result.completions)
        assertNoAdActivity()
        Log.i(TAG, "PASS joined_preload=true background_complete_once=true late_fill_cache_only=true")
    }

    private fun arm(interval: Long) {
        InterstitialAutoBuffer.configure(InterstitialBufferOptions(
            independentIntervalPlacements = setOf(ALL), placements = listOf(ALL),
            tapThresholds = mapOf(ALL to 2), intervalMsByPlacement = mapOf(ALL to interval),
        ))
        InterstitialAutoBuffer.start(host)
    }
    private fun click(timeout: Long) = Result().also {
        InterstitialAdManager.loadAndShow(host, ALL, listOf(UNIT), it,
            InterLoadAndShowOptions(allowWaitForAutoBuffer = true, timeoutMs = timeout))
    }
    private class Result : InterShowCallback() {
        @Volatile var completions = 0
        val skips = java.util.concurrent.CopyOnWriteArrayList<AdSkipReason>()
        override fun onComplete() { completions++ }
        override fun onSkipped(reason: AdSkipReason) { skips += reason }
    }
    private fun assertNoAdActivity() = assertFalse(onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is AdActivity }
    })
    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun eventually(message: String, timeout: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue(message, predicate())
    }
    companion object {
        const val ALL = "inter_all"
        const val UNIT = "ca-app-pub-3940256099942544/1033173712"
        const val TAG = "INTER_TIMING_DEVICE"
    }
}
