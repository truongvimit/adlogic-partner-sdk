package io.onboardkit.ui.onboarding

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.AdGate
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.*
import io.onboardkit.config.*
import io.onboardkit.core.StepId
import io.onboardkit.flow.FlowNavigator
import io.onboardkit.remote.OnboardingSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Pixel pager/lifecycle with deterministic ads; run each obCase in a fresh process. */
@RunWith(AndroidJUnit4::class)
class FlexibleOnboardingDeviceTest {
    @Test fun catalogOrderPreloadAndSwipe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val case = InstrumentationRegistry.getArguments().getString("obCase") ?: "default"
        require(case in setOf("default", "reorder", "missing", "disabled", "hold", "empty"))
        val app = ApplicationProvider.getApplicationContext<Application>()
        val provider = CatalogProvider()
        val ids = listOf(StepId.OB1, StepId.FULL1, StepId.OB2, StepId.FULL2, StepId.OB3, StepId.OB4)
        fun placement(id: StepId): AdPlacement = if (id.value.startsWith("full")) AdPlacement.StepFullScreen(id) else AdPlacement.StepNative(id)
        instrumentation.runOnMainSync {
            OnboardingSdk.install(app) { adProvider = provider; trackkitAutoTracking(false) }
            OnboardingSettings.document.acceptSuccessfulFetch(null)
            OnboardingSdk.configure(onboardKitConfig {
                steps(*ids.map { id ->
                    if (id.value.startsWith("full")) AdFullScreenStepDefinition(id, autoNextEnabled = false)
                    else ContentStepDefinition(id, title = id.value, enabled = !(case == "disabled" && id == StepId.OB2))
                }.toTypedArray())
                behavior = BehaviorConfig(lockPagerSwipe = false)
                ads = AdsConfig.fromAdConfig().copy(afterOnboardingInterstitialEnabled = false)
            }.getOrThrow()).getOrThrow()
            AdRemoteConfig.update(AdRemoteConfig(ids.filterNot { case == "missing" && it in listOf(StepId.FULL2, StepId.OB3) }
                .associate { id -> "native_${id.value}" to AdUnitConfig("test-${id.value}", true, enableUaCheck = false) }))
            if (case == "reorder") OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"order":["ob4","full2","ob1","ob3"]}}""")
            if (case == "empty") OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"order":[]}}""")
            ConsentCenter.setHostConsent(true, false)
            OnboardingSdk.setCanRequestAds(true)
        }
        runBlocking { OnboardingSdk.reset() }
        val expectedPages = when (case) {
            "reorder" -> listOf(StepId.OB4, StepId.FULL2, StepId.OB1, StepId.OB3)
            "missing" -> ids - StepId.FULL2
            "disabled" -> ids - StepId.OB2
            "empty" -> emptyList()
            else -> ids
        }
        val expectedRequests = expectedPages.filterNot { case == "missing" && it == StepId.OB3 }.map(::placement)
        try {
            instrumentation.runOnMainSync {
                val host = Activity()
                if (case == "hold") AdGate.holdRequests().use {
                    OnboardingSdk.preload().onLanguageSelected(host)
                    assertTrue(provider.requests.isEmpty())
                }
                OnboardingSdk.preload().onLanguageSelected(host)
                OnboardingSdk.preload().onLanguageSelected(host)
                assertEquals(expectedRequests, provider.requests)
                assertEquals(expectedPages, FlowNavigator.enabledSteps(OnboardingSdk.requireConfig(), OnboardingSdk.flags(),
                    canShowAdStep = OnboardingSdk::canFillAdOnlyStep))
            }
            if (expectedPages.isNotEmpty()) ActivityScenario.launch<ObOnboardingHostActivity>(
                Intent(app, ObOnboardingHostActivity::class.java),
            ).use { scenario ->
                expectedPages.forEachIndexed { index, id ->
                    scenario.onActivity { it.findViewById<ViewPager2>(R.id.ob_step_pager).setCurrentItem(index, false) }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { host ->
                        val pager = host.findViewById<ViewPager2>(R.id.ob_step_pager)
                        assertEquals(expectedPages.size, pager.adapter!!.itemCount)
                        if (id.value.startsWith("full")) {
                            assertFalse("Fullscreen bind is not an impression", pager.isUserInputEnabled)
                            requireNotNull(provider.listeners[placement(id)]).onImpression()
                            assertTrue("Impression unlocks fullscreen", pager.isUserInputEnabled)
                        } else assertEquals("Swipe eligibility $id", id != StepId.OB1, pager.isUserInputEnabled)
                    }
                }
                scenario.onActivity { it.findViewById<ViewPager2>(R.id.ob_step_pager).setCurrentItem(0, false) }
                instrumentation.waitForIdleSync()
                assertEquals("Returning must not reload any slot", expectedRequests, provider.requests)
            }
            Log.i("OB_DEVICE_TEST", "PASS case=$case pages=$expectedPages requests=$expectedRequests")
        } finally {
            instrumentation.runOnMainSync {
                ConsentCenter.clearHostConsent()
                OnboardingSettings.document.acceptSuccessfulFetch(null)
                AdRemoteConfig.reset()
            }
        }
    }
}

private class CatalogProvider : OnboardingAdProvider {
    val requests = mutableListOf<AdPlacement>()
    val listeners = mutableMapOf<AdPlacement, AdEventListener>()
    private val ready = mutableSetOf<AdPlacement>()
    override fun isPremium(context: Context) = false
    override fun isNativeReady(placement: AdPlacement) = placement in ready
    override fun isNativeLoading(placement: AdPlacement) = false
    override fun preloadNative(activity: Activity, request: NativeAdRequest) {
        requests += request.placement
        ready += request.placement
    }
    override fun bindNative(activity: Activity, placement: AdPlacement, container: ViewGroup, shimmer: View?, listener: AdEventListener?): Boolean {
        listener?.let { listeners[placement] = it }
        if (!ready.remove(placement)) return false
        container.removeAllViews()
        container.addView(TextView(activity).apply { text = "Test native ${placement.key}" })
        return true
    }
    override fun releaseNative(placement: AdPlacement) = Unit
    override fun loadInterstitial(context: Context, placement: AdPlacement, unit: InterstitialAdUnit, listener: AdEventListener?) = Unit
    override fun isInterstitialReady(placement: AdPlacement) = false
    override fun loadAndShowInterstitial(activity: AppCompatActivity, placement: AdPlacement, unit: InterstitialAdUnit, callback: ObInterstitialCallback, timeoutMs: Long) = Unit
    override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) = Unit
    override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) = Unit
    override fun suppressAppResume(activityClass: Class<out Activity>) = Unit
    override fun releaseAll() { ready.clear() }
}
