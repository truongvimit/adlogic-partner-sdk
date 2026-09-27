package io.onboardkit.ads

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
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
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.InterLoadAndShowOptions
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.helper.interstitial.InterstitialAutoBuffer
import com.ads.module.helper.interstitial.InterstitialBufferOptions
import com.google.android.gms.ads.AdActivity
import com.google.android.gms.ads.MobileAds
import io.trackkit.TrackSink
import io.trackkit.Tracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Public partner screen reports, real lifecycle/scheduler and Google test requests on a device.
 * Run each method in a fresh instrumentation process. No ad is presented or clicked.
 * The one-minute polling period makes an immediate request evidence of the screen-change wakeup.
 */
@RunWith(AndroidJUnit4::class)
class InterstitialPreloadScreensDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val requests = CopyOnWriteArrayList<Long>()
    private val registrations = mutableListOf<AutoCloseable>()
    private lateinit var scenario: ActivityScenario<InterstitialRealGmaDeviceActivity>
    private lateinit var host: InterstitialRealGmaDeviceActivity
    private var previousBehaviorRemote: String? = null
    private val sink = object : TrackSink {
        override val id = "preload-screens-device"
        override fun onEvent(name: String, params: Map<String, Any?>) {
            if (name == "ad_request" && params["ad_format"] == "interstitial" &&
                params["placement"] == BACK
            ) requests += SystemClock.elapsedRealtime()
        }
    }

    @Before
    fun setUp() {
        previousBehaviorRemote = app.getSharedPreferences(
            "adlogic_settings_ad_behavior_config", Context.MODE_PRIVATE,
        ).getString("remote", null)
        val initialized = CountDownLatch(1)
        onMain {
            InterstitialAutoBuffer.stop()
            InterstitialAutoBuffer.configure(InterstitialBufferOptions())
            InterstitialAutoBuffer.setPreloadScreens(emptyMap())
            reportScreen(null)
            InterstitialAdManager.releaseAll()
            Tracker.install(app)
            Tracker.addSink(sink)
            ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
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
            AdRemoteConfig.initializeFromJson("""{"inter_back":{"id":"$UNIT","isEnable":true}}""")
            // Normalize test-package remote state, preserving/restoring the original document.
            // Host options below provide the per-case clock and tap count.
            assertTrue(AdBehavior.document.acceptSuccessfulFetch("""{
                "interstitial_auto_buffer":{
                    "enabled":true,"shared_config":false,
                    "tick_ms":60000,"idle_tick_ms":60000,"preload_lead_ms":2000
                }
            }"""))
            MobileAds.initialize(app) { initialized.countDown() }
        }
        assertTrue("Google test SDK initialization must finish", initialized.await(45, TimeUnit.SECONDS))
        scenario = ActivityScenario.launch(Intent(app, InterstitialRealGmaDeviceActivity::class.java))
        scenario.onActivity { host = it }
        eventually("Host and process must be foreground", 10_000) {
            onMain {
                host.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
        }
        requests.clear()
    }

    @After
    fun tearDown() {
        onMain {
            InterstitialAutoBuffer.stop()
            registrations.asReversed().forEach { it.close() }
            registrations.clear()
            InterstitialAutoBuffer.setCurrentScreen(null).close()
            InterstitialAutoBuffer.setPreloadScreens(emptyMap())
            InterstitialAutoBuffer.configure(InterstitialBufferOptions())
            InterstitialAdManager.releaseAll()
            Tracker.removeSink(sink)
            AdRemoteConfig.reset()
            AdBehavior.document.acceptSuccessfulFetch(previousBehaviorRemote)
            ConsentCenter.clearHostConsent()
            AppOpenManager.getInstance().disableAppResume()
        }
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun earlyEntryKeepsTheOriginalPreloadDeadline() {
        val activatedAt = onMain { arm(intervalMs = 8_000) }
        sleepUntil(activatedAt + 2_500)
        onMain { reportScreen(TRANSLATE) }
        sleepUntil(activatedAt + 5_700)
        assertTrue("Entering a function must still wait until interval minus lead", requests.isEmpty())
        eventually("Request at the original six-second deadline, not 6s after entry", 1_300) {
            requests.isNotEmpty()
        }
        val elapsed = requests.first() - activatedAt
        assertTrue("Original preload deadline: observed ${elapsed}ms", elapsed in 5_950..7_000)
        assertNoAdActivity()
        Log.i(TAG, "PASS early_entry original_preload_ms=$elapsed")
    }

    @Test
    fun lateEntryLoadsImmediatelyAndReadyBackRemainsUsableOnHome() {
        val activatedAt = onMain { arm(intervalMs = 6_000) }
        sleepUntil(activatedAt + 6_500)
        assertTrue("Home must hold an already satisfied preload deadline", requests.isEmpty())
        lateinit var functionRegistration: AutoCloseable
        val enteredAt = onMain {
            val now = SystemClock.elapsedRealtime()
            functionRegistration = reportScreen(TRANSLATE)
            now
        }
        eventually("Entry must wake the buffer instead of waiting for the minute tick", 1_000) {
            requests.isNotEmpty()
        }
        val dispatchDelay = requests.first() - enteredAt
        assertTrue("Immediate screen-triggered request: ${dispatchDelay}ms", dispatchDelay in 0..1_000)
        eventually("The Google test request must actually fill", 45_000) {
            onMain { InterstitialAdManager.isReady(BACK) }
        }
        val countWithCache = requests.size
        onMain {
            reportScreen(HOME)
            functionRegistration.close() // A late outgoing dispose must not disturb the new owner.
            repeat(3) { InterstitialAutoBuffer.topUpNow() }
        }
        SystemClock.sleep(1_000)
        assertTrue("Leaving the function retains a ready Back ad", onMain { InterstitialAdManager.isReady(BACK) })
        assertTrue("The preload whitelist must not block Back presentation on Home", onMain {
            InterstitialAdManager.canShow(host, BACK)
        })
        onMain {
            repeat(3) { reportScreen(TRANSLATE) }
            InterstitialAutoBuffer.topUpNow()
        }
        SystemClock.sleep(500)
        assertEquals("Screen changes must not replace a ready ad", countWithCache, requests.size)
        assertNoAdActivity()
        Log.i(TAG, "PASS late_entry dispatch_ms=$dispatchDelay ready_back_on_home=true requests=$countWithCache")
    }

    @Test
    fun leavingBeforeDeadlineHoldsEligibilityUntilReentry() {
        val activatedAt = onMain { arm(intervalMs = 8_000) }
        sleepUntil(activatedAt + 1_000)
        val outgoing = onMain { reportScreen(TRANSLATE) }
        sleepUntil(activatedAt + 2_500)
        val home = onMain { reportScreen(HOME).also { outgoing.close() } }
        sleepUntil(activatedAt + 7_000)
        assertTrue("No request after leaving the function, even after the original deadline", requests.isEmpty())
        val reenteredAt = onMain {
            val now = SystemClock.elapsedRealtime()
            reportScreen(TRANSLATE)
            home.close() // A stale owner's disposal cannot close the current function gate.
            now
        }
        eventually("Reentry uses the elapsed original clock", 1_000) { requests.isNotEmpty() }
        val delay = requests.first() - reenteredAt
        assertTrue("Reentry must not restart the interval: ${delay}ms", delay in 0..1_000)
        assertNoAdActivity()
        Log.i(TAG, "PASS leave_reenter dispatch_ms=$delay stale_owner_ignored=true")
    }

    @Test
    fun completedTapRemainsHeldOnHomeUntilFunctionEntry() {
        onMain { arm(intervalMs = 0, taps = 1) }
        onMain { reportScreen(TRANSLATE) }
        SystemClock.sleep(1_000)
        assertTrue("Screen entry must not manufacture a Back tap", requests.isEmpty())
        val outcome = RecordingShow()
        onMain {
            reportScreen(HOME)
            // Zero UI wait records the user's action without buying/presenting an ad itself.
            InterstitialAdManager.loadAndShow(host, BACK, listOf(UNIT), outcome,
                InterLoadAndShowOptions(allowWaitForAutoBuffer = true, timeoutMs = 0))
        }
        assertEquals(1, outcome.completed)
        assertEquals(listOf(AdSkipReason.NOT_READY), outcome.skips.toList())
        SystemClock.sleep(1_000)
        assertTrue("A satisfied tap remains held while the screen is outside the whitelist", requests.isEmpty())
        val enteredAt = onMain {
            val now = SystemClock.elapsedRealtime()
            reportScreen(TRANSLATE)
            now
        }
        eventually("Function entry consumes the already satisfied eligibility", 1_000) { requests.isNotEmpty() }
        assertTrue(requests.first() - enteredAt in 0..1_000)
        assertEquals("The expired UI trigger cannot complete twice", 1, outcome.completed)
        assertNoAdActivity()
        Log.i(TAG, "PASS tap_held_at_home=true screen_entry_did_not_add_tap=true")
    }

    private fun arm(intervalMs: Long, taps: Int = 0): Long {
        InterstitialAutoBuffer.configure(InterstitialBufferOptions(
            independentIntervalPlacements = setOf(BACK),
            placements = listOf(BACK),
            tapThresholds = mapOf(BACK to taps),
            intervalMsByPlacement = mapOf(BACK to intervalMs),
            tickMs = 60_000,
        ))
        InterstitialAutoBuffer.setPreloadScreens(mapOf(BACK to setOf(TRANSLATE)))
        reportScreen(HOME)
        val activatedAt = SystemClock.elapsedRealtime()
        InterstitialAutoBuffer.start(host)
        return activatedAt
    }

    private fun reportScreen(screen: String?): AutoCloseable =
        InterstitialAutoBuffer.setCurrentScreen(screen).also { registrations += it }

    private fun assertNoAdActivity() = assertFalse("Preload must never present an ad", onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is AdActivity }
    })

    private fun sleepUntil(deadline: Long) {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining > 0) SystemClock.sleep(remaining)
    }

    private fun <T> onMain(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }

    private fun eventually(message: String, timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue(message, predicate())
    }

    private class RecordingShow : InterShowCallback() {
        @Volatile var completed = 0
        val skips = CopyOnWriteArrayList<AdSkipReason>()
        override fun onSkipped(reason: AdSkipReason) { skips += reason }
        override fun onComplete() { completed++ }
    }

    private companion object {
        const val TAG = "PRELOAD_SCREEN_DEVICE"
        const val BACK = "inter_back"
        const val HOME = "home"
        const val TRANSLATE = "translate"
        const val UNIT = "ca-app-pub-3940256099942544/1033173712"
    }
}
