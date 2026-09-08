package io.onboardkit.ads

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ads.module.admob.AppOpenManager
import com.ads.module.config.AdRemoteConfig
import com.ads.module.consent.ConsentCenter
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.MobileAds
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.ui.language.ObLanguageActivity
import io.onboardkit.ui.onboarding.ObOnboardingHostActivity
import io.trackkit.TrackSink
import io.trackkit.Tracker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Run alone for each earlyScreen=language/onboarding; close the real test ad at CLOSE_ALLOWED. */
@RunWith(AndroidJUnit4::class)
class AppOpenEarlyFlowDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val manager get() = AppOpenManager.getInstance()

    @Test fun realBackgroundReturnShowsReadyResumeDuringTheEarlyFlow() {
        val screen = InstrumentationRegistry.getArguments().getString("earlyScreen") ?: "language"
        require(screen in setOf("language", "onboarding"))
        val hostClass = if (screen == "language") ObLanguageActivity::class.java else ObOnboardingHostActivity::class.java
        val app = ApplicationProvider.getApplicationContext<Application>()
        val initialized = CountDownLatch(1)
        val requests = AtomicInteger()
        val shown = AtomicInteger()
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        val sink = object : TrackSink {
            override val id = "early-flow-device"
            override fun onEvent(name: String, params: Map<String, Any?>) {
                if (name == "ad_request" && params["ad_format"] == "app_open") requests.incrementAndGet()
            }
        }
        onMain {
            Tracker.install(app)
            Tracker.addSink(sink)
            OnboardingSdk.install(app) { adProvider = ERainAdProvider(); trackkitAutoTracking(false) }
            OnboardingSdk.configure(onboardKitConfig {
                step(ContentStepDefinition(StepId.OB1, title = "Resume device verification"))
                ads = AdsConfig(appResume = InterstitialAdUnit(UNIT))
            }.getOrThrow()).getOrThrow()
            OnboardingSdk.setCanRequestAds(true)
            ConsentCenter.setHostConsent(true, false)
            manager.disableAppResume()
            manager.init(app, "")
            manager.setAppResumeAdId(UNIT)
            manager.enableAppResume()
            AdRemoteConfig.initializeFromJson("""{"app_resume_load_delay_ms":500}""")
            manager.setEnableScreenContentCallback(true)
            manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() { shown.incrementAndGet() }
                override fun onAdDismissedFullScreenContent() { closes.incrementAndGet(); closed.countDown() }
            })
            MobileAds.initialize(app) { initialized.countDown() }
        }
        try {
            assertTrue(initialized.await(45, TimeUnit.SECONDS))
            ActivityScenario.launch<Activity>(Intent(app, hostClass)).use { scenario ->
                lateinit var host: Activity
                scenario.onActivity { host = it }
                eventually("Early flow is foreground") {
                    onMain { ProcessLifecycleOwner.get().lifecycle.currentState == Lifecycle.State.RESUMED }
                }
                assertFalse(onMain { manager.isResumeSuppressedFor(host) })
                assertNull(onMain { manager.resumeSkipReasonFor(host) })
                SystemClock.sleep(1_000)
                assertEquals("No startup request", 0, requests.get())
                assertFalse(onMain { manager.isAdAvailable(false) })
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                eventually("Physical Home stops the process") {
                    onMain { ProcessLifecycleOwner.get().lifecycle.currentState == Lifecycle.State.CREATED }
                }
                eventually("Real background fill", 90_000) { onMain { manager.isAdAvailable(false) } }
                val requestsAtFill = requests.get()
                assertTrue("The real background must dispatch", requestsAtFill > 0)
                assertEquals(0, shown.get())
                onMain {
                    app.getSystemService(ActivityManager::class.java).appTasks
                        .single { it.taskInfo.taskId == host.taskId }.moveToFront()
                }
                eventually("Automatic return shows the real ad", 15_000) { shown.get() == 1 }
                Log.i(TAG, "CLOSE_ALLOWED screen=$screen startupRequests=0; close real app-open through UI")
                assertTrue("Close real test ad", closed.await(90, TimeUnit.SECONDS))
                SystemClock.sleep(1_000)
                assertEquals(1, shown.get())
                assertEquals(1, closes.get())
                assertFalse(onMain { manager.isShowingAd })
                assertEquals("No post-close refill", requestsAtFill, requests.get())
                assertFalse(onMain { manager.isAdAvailable(false) })
                Log.i(TAG, "COMPLETE screen=$screen shown=1 closed=1 noRefill=true")
            }
        } finally {
            onMain {
                manager.removeFullScreenContentCallback()
                manager.disableAppResume()
                manager.releaseCachedAds()
                Tracker.removeSink(sink)
                ConsentCenter.clearHostConsent()
            }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
    private fun eventually(message: String, timeoutMs: Long = 10_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline && !predicate()) SystemClock.sleep(50)
        assertTrue(message, predicate())
    }
    private companion object {
        const val TAG = "EARLY_RESUME"
        const val UNIT = "ca-app-pub-3940256099942544/9257395921"
    }
}
