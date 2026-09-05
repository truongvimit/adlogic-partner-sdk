package io.onboardkit.ui.ob5

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.AnalyticsPlugin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Run this class alone in a fresh instrumentation process after clearing the test APK's data.
 * That preserves the shipped 3-second Skip / 15-second exit defaults instead of inheriting
 * a remote snapshot from RemoteConfigCacheDeviceTest. Device must be awake and unlocked.
 *
 * The concrete provider is the public host-supplied vendor seam; this test exercises the real
 * Activity, lifecycle, timers, flow completion and view tree without making a live ad request.
 */
@RunWith(AndroidJUnit4::class)
class ObFullScreenAdPauseDeviceTest {
    @Test
    fun homePausesBothCountdownsAndReturnStartsThemAgainBeforeOneExit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val finished = CountDownLatch(1)
        val outcomes = CopyOnWriteArrayList<OnboardingOutcome>()
        val completions = CopyOnWriteArrayList<AnalyticsEvent.StepCompleted>()
        val provider = BufferedHostNativeProvider()
        val config = onboardKitConfig {
            ads = AdsConfig(ob5Native = NativeAdUnit("host-test-native"))
            // No question or paywall: observe the existing terminal route via the host listener.
        }.getOrThrow()

        assertFalse("Run this class in a fresh process", OnboardingSdk.isReady())
        instrumentation.runOnMainSync {
            OnboardingSdk.install(app) {
                adProvider = provider
                trackkitAutoTracking(false)
                listener = OnboardingListener { _, outcome ->
                    outcomes += outcome
                    finished.countDown()
                }
                analyticsPlugin(AnalyticsPlugin { event ->
                    if (event is AnalyticsEvent.StepCompleted && event.stepId == StepId.OB5) {
                        completions += event
                    }
                })
            }
            OnboardingSdk.configure(config).getOrThrow()
            ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
            OnboardingSdk.setCanRequestAds(true)
        }
        runBlocking { OnboardingSdk.reset() }

        try {
            ActivityScenario.launch<ObFullScreenAdActivity>(
                Intent(app, ObFullScreenAdActivity::class.java),
            ).use { scenario ->
                lateinit var originalActivity: ObFullScreenAdActivity
                scenario.onActivity { activity ->
                    originalActivity = activity
                    assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.ob_skip_button).visibility)
                    assertTrue(activity.findViewById<View>(R.id.ob_native_container).isShown)
                    assertEquals(1, provider.binds.get())
                }
                // Spend part of the Skip interval so an accumulated countdown differs from restart.
                SystemClock.sleep(2_000)
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                eventually("OB5 must stop after Home") { scenario.state == Lifecycle.State.CREATED }

                // Longer than the entire shipped auto-dismiss interval, with a real stopped Activity.
                SystemClock.sleep(16_000)
                assertEquals("No terminal callback while the user is away", 0, outcomes.size)
                assertTrue("No step completion from the background", completions.isEmpty())
                instrumentation.runOnMainSync {
                    assertFalse(originalActivity.isFinishing)
                    assertFalse(originalActivity.isDestroyed)
                    assertEquals(View.GONE, originalActivity.findViewById<View>(R.id.ob_skip_button).visibility)
                }

                // Home changes task focus; ActivityScenario's lifecycle request alone cannot
                // bring it back on this device. Return the existing task, as Recents would.
                instrumentation.runOnMainSync {
                    app.getSystemService(ActivityManager::class.java).appTasks
                        .single { it.taskInfo.taskId == originalActivity.taskId }
                        .moveToFront()
                }
                eventually("Existing OB5 task must resume") { scenario.state == Lifecycle.State.RESUMED }
                SystemClock.sleep(1_500)
                scenario.onActivity { activity ->
                    assertSame("Return retains the screen and its ad", originalActivity, activity)
                    assertEquals("Skip starts a fresh 3-second delay", View.GONE, activity.findViewById<View>(R.id.ob_skip_button).visibility)
                    assertTrue(activity.findViewById<View>(R.id.ob_native_container).isShown)
                    assertEquals(1, provider.binds.get())
                }
                assertEquals(0, outcomes.size)
                eventually("Skip must unlock while resumed") {
                    var visible = false
                    scenario.onActivity { visible = it.findViewById<View>(R.id.ob_skip_button).isShown }
                    visible
                }
                assertTrue("The existing auto-dismiss still provides an exit", finished.await(16, TimeUnit.SECONDS))
                eventually("OB5 must finish after completion") { scenario.state == Lifecycle.State.DESTROYED }
                SystemClock.sleep(1_000)
                assertEquals(1, outcomes.size)
                assertTrue(outcomes.single() is OnboardingOutcome.Completed)
                assertEquals(1, completions.size)
                assertEquals("auto_dismiss", completions.single().exitReason)
            }
        } finally {
            instrumentation.runOnMainSync {
                ConsentCenter.clearHostConsent()
                provider.releaseAll()
            }
        }
    }

    private fun eventually(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, predicate())
    }
}

/** Host implementation of the documented ad-provider extension point, with one buffered native. */
private class BufferedHostNativeProvider : OnboardingAdProvider {
    val binds = AtomicInteger()

    override fun isPremium(context: Context) = false
    override fun isNativeReady(placement: AdPlacement) = placement == AdPlacement.Ob5
    override fun isNativeLoading(placement: AdPlacement) = false
    override fun preloadNative(activity: Activity, request: NativeAdRequest) = Unit

    override fun bindNative(
        activity: Activity,
        placement: AdPlacement,
        container: ViewGroup,
        shimmer: View?,
        listener: AdEventListener?,
    ): Boolean {
        if (placement != AdPlacement.Ob5) return false
        binds.incrementAndGet()
        container.removeAllViews()
        container.addView(TextView(activity).apply { text = "Buffered host native" })
        container.visibility = View.VISIBLE
        return true
    }

    override fun releaseNative(placement: AdPlacement) = Unit
    override fun loadInterstitial(context: Context, placement: AdPlacement, unit: InterstitialAdUnit, listener: AdEventListener?) = Unit
    override fun isInterstitialReady(placement: AdPlacement) = false
    override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) = Unit
    override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) = Unit
    override fun suppressAppResume(activityClass: Class<out Activity>) = Unit
    override fun releaseAll() = Unit
}
