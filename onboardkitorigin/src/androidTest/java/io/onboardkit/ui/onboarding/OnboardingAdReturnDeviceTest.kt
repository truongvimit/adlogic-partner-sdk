package io.onboardkit.ui.onboarding

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
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
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.AnalyticsPlugin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Run each returnCase in a fresh instrumentation process; see src/androidTest/README.md. */
@RunWith(AndroidJUnit4::class)
class OnboardingAdReturnDeviceTest {
    @Test fun adDestinationReturnCompletesOnlyItsSourcePage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val case = InstrumentationRegistry.getArguments().getString("returnCase") ?: "content_click"
        require(case in setOf("content_click", "content_open", "full_click", "full_timeout",
            "next_full_zero", "next_full_no_fill"))
        val fullscreen = case.startsWith("full_")
        val nextFullscreen = case.startsWith("next_full_")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val completions = CopyOnWriteArrayList<AnalyticsEvent.StepCompleted>()
        val provider = ReturnNativeProvider(openOnly = case == "content_open",
            failNextFullscreen = case == "next_full_no_fill")
        assertFalse("Run each case in a fresh process", OnboardingSdk.isReady())
        instrumentation.runOnMainSync {
            OnboardingSdk.install(app) {
                adProvider = provider
                trackkitAutoTracking(false)
                analyticsPlugin(AnalyticsPlugin { event ->
                    if (event is AnalyticsEvent.StepCompleted) completions += event
                })
            }
            OnboardingSdk.configure(onboardKitConfig {
                step(if (fullscreen) AdFullScreenStepDefinition(StepId.OB1)
                    else ContentStepDefinition(StepId.OB1, title = "Source page"))
                step(if (nextFullscreen) AdFullScreenStepDefinition(StepId.OB2,
                    autoNextEnabled = case == "next_full_zero", autoNextDelayMs = 0)
                    else ContentStepDefinition(StepId.OB2, title = "Next page"))
                step(ContentStepDefinition(StepId.OB4, title = "Must not skip to here"))
                ads = AdsConfig(contentStepNative = NativeAdUnit("test-content"),
                    fullScreenStepNative = NativeAdUnit("test-fullscreen"),
                    afterOnboardingInterstitialEnabled = false)
            }.getOrThrow()).getOrThrow()
            ConsentCenter.setHostConsent(true, false)
            OnboardingSdk.setCanRequestAds(true)
        }
        runBlocking { OnboardingSdk.reset() }
        try {
            ActivityScenario.launch<ObOnboardingHostActivity>(
                Intent(app, ObOnboardingHostActivity::class.java),
            ).use { scenario ->
                eventually("The native must bind") { provider.sourceView != null }
                scenario.onActivity {
                    assertEquals(0, it.currentIndex.value)
                    assertTrue(requireNotNull(provider.sourceView).performClick())
                }
                eventually("Ad destination must stop its pager host") {
                    scenario.state == Lifecycle.State.CREATED && ReturnAdDestinationActivity.current != null
                }
                if (case == "full_timeout") {
                    eventually("Fullscreen deadline must complete while away") { completions.size == 1 }
                    assertEquals("auto_next", completions.single().exitReason)
                } else {
                    assertTrue(completions.isEmpty())
                }
                instrumentation.runOnMainSync { requireNotNull(ReturnAdDestinationActivity.current).finish() }
                eventually("The pager host must return") { scenario.state == Lifecycle.State.RESUMED }
                eventually("Expected pages must complete") { completions.size == if (nextFullscreen) 2 else 1 }
                // Outwait OB fullscreen's original deadline to catch a second navigation.
                SystemClock.sleep(3_500)
                scenario.onActivity { assertEquals(if (nextFullscreen) 2 else 1, it.currentIndex.value) }
                if (nextFullscreen) {
                    assertEquals(listOf(StepId.OB1, StepId.OB2), completions.map { it.stepId })
                    assertEquals(listOf("ad_click_return",
                        if (case == "next_full_zero") "auto_next" else "ad_failed"),
                        completions.map { it.exitReason })
                } else {
                    assertEquals(listOf(StepId.OB1), completions.map { it.stepId })
                    assertEquals(if (case == "full_timeout") "auto_next" else "ad_click_return",
                        completions.single().exitReason)
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                ReturnAdDestinationActivity.current?.finish()
                ConsentCenter.clearHostConsent()
                provider.releaseAll()
            }
        }
    }

    private fun eventually(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue(message, predicate())
    }
}

/** Exercises a real stop/resume round trip without requesting or clicking live ads. */
class ReturnAdDestinationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        setContentView(TextView(this).apply { text = "Test ad destination" })
    }
    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
    companion object { @Volatile var current: ReturnAdDestinationActivity? = null }
}

private class ReturnNativeProvider(
    private val openOnly: Boolean,
    private val failNextFullscreen: Boolean,
) : OnboardingAdProvider {
    @Volatile var sourceView: View? = null
    override fun isPremium(context: Context) = false
    override fun isNativeReady(placement: AdPlacement) = true
    override fun isNativeLoading(placement: AdPlacement) = false
    override fun preloadNative(activity: Activity, request: NativeAdRequest) = Unit
    override fun bindNative(activity: Activity, placement: AdPlacement, container: ViewGroup,
        shimmer: View?, listener: AdEventListener?): Boolean {
        if (failNextFullscreen && placement == AdPlacement.StepFullScreen(StepId.OB2)) {
            listener?.onFailedToLoad()
            return true
        }
        val native = TextView(activity).apply {
            text = "Test native: open destination"
            setOnClickListener {
                if (openOnly) listener?.onAdOpened() else listener?.onClicked()
                activity.startActivity(Intent(activity, ReturnAdDestinationActivity::class.java))
            }
        }
        container.removeAllViews()
        container.addView(native)
        container.visibility = View.VISIBLE
        if (placement == AdPlacement.StepNative(StepId.OB1) ||
            placement == AdPlacement.StepFullScreen(StepId.OB1)) sourceView = native
        return true
    }
    override fun releaseNative(placement: AdPlacement) = Unit
    override fun loadInterstitial(context: Context, placement: AdPlacement,
        unit: InterstitialAdUnit, listener: AdEventListener?) = Unit
    override fun isInterstitialReady(placement: AdPlacement) = false
    override fun loadAndShowInterstitial(activity: AppCompatActivity, placement: AdPlacement,
        unit: InterstitialAdUnit, callback: ObInterstitialCallback, timeoutMs: Long) {
        error("Unexpected exit interstitial: $placement")
    }
    override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) = Unit
    override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) = Unit
    override fun suppressAppResume(activityClass: Class<out Activity>) = Unit
    override fun releaseAll() { sourceView = null }
}
