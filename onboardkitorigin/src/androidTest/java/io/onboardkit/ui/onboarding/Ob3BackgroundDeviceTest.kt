package io.onboardkit.ui.onboarding

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
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.AnalyticsPlugin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.FullScreenSkipStyle
/** Real pager and device Home; fake provider avoids serving a live advertisement. */
@RunWith(AndroidJUnit4::class)
class Ob3BackgroundDeviceTest {
    @Test fun homeStillAdvancesOb3AndReturnKeepsTheNextPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val completions = CopyOnWriteArrayList<AnalyticsEvent.StepCompleted>()
        val provider = Ob3BufferedNativeProvider()
        instrumentation.runOnMainSync {
            OnboardingSdk.install(app) {
                adProvider = provider
                trackkitAutoTracking(false)
                analyticsPlugin(AnalyticsPlugin { event ->
                    if (event is AnalyticsEvent.StepCompleted && event.stepId == StepId.OB3) completions += event
                })
            }
            OnboardingSdk.configure(onboardKitConfig {
                steps(AdFullScreenStepDefinition(StepId.OB3), ContentStepDefinition(StepId.OB4, title = "Next page"))
                ads = AdsConfig(fullScreenStepNative = NativeAdUnit("test-native"),
                    fullScreenSkipStyle = FullScreenSkipStyle.CLOSE_ICON,
                    afterOnboardingInterstitialEnabled = false)
            }.getOrThrow()).getOrThrow()
            ConsentCenter.setHostConsent(true, false)
            OnboardingSdk.setCanRequestAds(true)
        }
        runBlocking { OnboardingSdk.reset() }
        try {
            ActivityScenario.launch<ObOnboardingHostActivity>(Intent(app, ObOnboardingHostActivity::class.java)).use { scenario ->
                lateinit var original: ObOnboardingHostActivity
                eventually("OB3 must bind its native") { provider.binds.get() == 1 }
                scenario.onActivity { activity ->
                    original = activity
                    assertEquals(0, activity.currentIndex.value)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.ob_skip_button).visibility)
                }
                eventually("Close control must unlock around 1 second") {
                    var visible = false
                    scenario.onActivity { visible = it.findViewById<View>(R.id.ob_skip_button)?.isShown == true }
                    visible
                }
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                eventually("Activity must stop") { scenario.state == Lifecycle.State.CREATED }
                eventually("OB3 must complete while stopped") { completions.size == 1 }
                assertEquals("auto_next", completions.single().exitReason)
                instrumentation.runOnMainSync {
                    assertEquals(Lifecycle.State.CREATED, original.lifecycle.currentState)
                    assertEquals("Pager advanced while Home is in front", 1, original.currentIndex.value)
                    app.getSystemService(ActivityManager::class.java).appTasks
                        .single { it.taskInfo.taskId == original.taskId }.moveToFront()
                }
                eventually("Task must resume") { scenario.state == Lifecycle.State.RESUMED }
                SystemClock.sleep(3500)
                scenario.onActivity { assertEquals(1, it.currentIndex.value) }
                assertEquals("Returning cannot complete OB3 twice", 1, completions.size)
            }
        } finally {
            instrumentation.runOnMainSync { ConsentCenter.clearHostConsent(); provider.releaseAll() }
        }
    }

    private fun eventually(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue(message, predicate())
    }
}

/** Host implementation of the documented ad-provider extension point, with one buffered native. */
private class Ob3BufferedNativeProvider : OnboardingAdProvider {
    val binds = AtomicInteger()

    override fun isPremium(context: Context) = false
    override fun isNativeReady(placement: AdPlacement) = placement == AdPlacement.StepFullScreen(StepId.OB3)
    override fun isNativeLoading(placement: AdPlacement) = false
    override fun preloadNative(activity: Activity, request: NativeAdRequest) = Unit

    override fun bindNative(
        activity: Activity,
        placement: AdPlacement,
        container: ViewGroup,
        shimmer: View?,
        listener: AdEventListener?,
    ): Boolean {
        if (placement != AdPlacement.StepFullScreen(StepId.OB3)) return false
        binds.incrementAndGet()
        container.removeAllViews()
        container.addView(TextView(activity).apply { text = "Buffered host native" })
        container.visibility = View.VISIBLE
        return true
    }

    override fun releaseNative(placement: AdPlacement) = Unit
    override fun loadInterstitial(context: Context, placement: AdPlacement, unit: InterstitialAdUnit, listener: AdEventListener?) = Unit
    override fun isInterstitialReady(placement: AdPlacement) = false
    override fun loadAndShowInterstitial(
        activity: androidx.appcompat.app.AppCompatActivity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        callback: ObInterstitialCallback,
        timeoutMs: Long,
    ) {
        throw AssertionError("This fixture does not expect a loadAndShow request: ${placement.key}")
    }

    override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) = Unit
    override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) = Unit
    override fun suppressAppResume(activityClass: Class<out Activity>) = Unit
    override fun releaseAll() = Unit
}
