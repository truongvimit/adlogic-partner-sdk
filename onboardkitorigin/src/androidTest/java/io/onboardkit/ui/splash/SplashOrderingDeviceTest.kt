package io.onboardkit.ui.splash

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.*
import io.onboardkit.config.*
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.AnalyticsPlugin
import io.onboardkit.remote.RemoteFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Each case/mode runs in its own instrumentation process; only vendor transport is controlled. */
@RunWith(AndroidJUnit4::class)
class SplashOrderingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val f get() = OrderingFixture

    @Test fun splashOrderingAcrossActualAndroidLifecycle() {
        val args = InstrumentationRegistry.getArguments()
        val case = args.getString("splashCase") ?: "success"
        require(case in setOf("success", "failure", "timeout", "late_fill", "banner_budget", "recreate", "mode_freeze", "rotate",
            "inter_off", "no_unit", "premium", "master_off", "host_off", "consent_denied", "same_time", "other_route",
            "home_pending", "home_expired", "under_ad_home", "after_ad"))
        val parallel = args.getString("lfoParallel") == "true"
        f.flags = RemoteFlags(splashLfoParallelPreloadEnabled = parallel, splashMinDisplayMs = 200,
            splashAdBudgetMs = 3_000, splashBannerWaitMs = if (case == "banner_budget") 2_200 else 0,
            adsSplashInter = case != "inter_off", enableAllAds = case != "master_off")
        f.premium = case == "premium"
        f.consentAllowed = case != "consent_denied"
        f.immediate = case == "same_time"
        f.bannerPending = case == "banner_budget"
        f.realPresentation = case in setOf("under_ad_home", "after_ad")
        f.afterAd = case == "after_ad"
        if (f.realPresentation) f.flags = f.flags.copy(splashMinDisplayMs = 5_000)
        onMain {
            assertFalse("Fresh instrumentation process required", OnboardingSdk.isReady())
            OnboardingSdk.install(app) {
                adProvider = f.provider
                trackkitAutoTracking(false)
                analyticsPlugin(AnalyticsPlugin {
                    if (it is AnalyticsEvent.SplashCompleted) f.handoffs.incrementAndGet()
                })
            }
            OnboardingSdk.configure(onboardKitConfig {
                splash = SplashConfig(noInternetPromptEnabled = false, notificationPermissionEnabled = false,
                    remoteFetchTimeoutMs = 100, minDisplayTimeMs = 0,
                    adLoadStrategy = if (case == "same_time") AdLoadStrategy.SAME_TIME else AdLoadStrategy.ALTERNATE)
                behavior = behavior.copy(lockPortrait = false)
                step(ContentStepDefinition(StepId.OB1, title = "Device test"))
                ads = AdsConfig(splashBanner = BannerAdUnit("device-banner"),
                    splashInterstitial = if (case == "no_unit") null else InterstitialAdUnit("device-inter"),
                    languageNative = NativeAdUnit("device-lfo1"), languageDupNative = NativeAdUnit("device-lfo2"),
                    contentStepNative = NativeAdUnit("device-ob1"))
            }.getOrThrow()).getOrThrow()
            OnboardingSdk.setCanRequestAds(case != "host_off")
        }
        runBlocking {
            OnboardingSdk.reset()
            if (case == "other_route") OnboardingSdk.markCompleted()
        }
        try {
            ActivityScenario.launch<OrderingSplashDeviceActivity>(Intent(app, OrderingSplashDeviceActivity::class.java)).use { scenario ->
                lateinit var host: OrderingSplashDeviceActivity
                scenario.onActivity { host = it }
                val blocked = case in setOf("premium", "master_off", "host_off", "consent_denied")
                if (blocked || case in setOf("inter_off", "no_unit")) {
                    eventually("Skip must hand off promptly") { f.handoffs.get() == 1 }
                    assertEquals(0, f.interLoads.get())
                    assertEquals(0, f.shows.get())
                    assertEquals(if (blocked) 0 else 1, f.splashLfo.get())
                    return@use
                }
                eventually("Interstitial request") { f.interLoads.get() == 1 }
                eventually("Splash regains actual focus") { onMain { host.hasWindowFocus() } }
                if (case == "same_time") {
                    eventually("Early inter terminal result must not be lost") { f.handoffs.get() == 1 }
                    assertEquals(1, f.splashLfo.get())
                    assertEquals(1, f.shows.get())
                    return@use
                }
                if (parallel && case != "other_route") eventually("Parallel starts LFO1") { f.splashLfo.get() == 1 }
                else assertEquals(0, f.splashLfo.get())
                assertEquals("OB1/LFO2 must not be pulled into the early request phase", emptyList<String>(), f.splashOther.toList())

                if (case in setOf("recreate", "mode_freeze", "rotate")) {
                    if (case == "mode_freeze") f.flags = f.flags.copy(splashLfoParallelPreloadEnabled = !parallel)
                    if (case == "rotate") {
                        scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                        eventually("Actual orientation change must recreate splash") { f.creates.get() == 2 }
                    } else scenario.recreate()
                    scenario.onActivity { host = it }
                    eventually("Recreated splash focus") { onMain { host.hasWindowFocus() } }
                    assertEquals(2, f.creates.get())
                    assertEquals(1, f.remoteHooks.get())
                    assertEquals(1, f.interLoads.get())
                    assertEquals(if (parallel) 1 else 0, f.splashLfo.get())
                }
                when (case) {
                    "home_pending", "home_expired" -> {
                        goHome()
                        eventually("Splash stopped by actual Home") { scenario.state == Lifecycle.State.CREATED }
                        if (case == "home_expired") SystemClock.sleep(3_500)
                        onMain { f.finishLoad(success = true) }
                        SystemClock.sleep(300)
                        assertEquals(0, f.handoffs.get())
                        assertEquals(0, f.shows.get())
                        assertEquals(if (parallel) 1 else 0, f.splashLfo.get())
                        returnTask(host.taskId)
                    }
                    "timeout", "late_fill", "banner_budget" -> Unit
                    "failure" -> onMain { f.finishLoad(success = false) }
                    else -> onMain { f.finishLoad(success = true) }
                }
                if (f.realPresentation) {
                    eventually("Separate ad Activity is resumed") { f.ad != null && onMain { f.ad?.hasWindowFocus() == true } }
                    if (case == "under_ad_home") {
                        goHome()
                        eventually("Ad no longer resumed") { onMain { f.ad?.hasWindowFocus() == false } }
                        SystemClock.sleep(5_500)
                        assertEquals("Minimum expiration while Home must not navigate", 0, f.handoffs.get())
                        returnTask(host.taskId)
                        eventually("UNDER_AD handoff after returning") { f.handoffs.get() == 1 }
                    } else {
                        SystemClock.sleep(5_500)
                        assertEquals("AFTER_AD must wait for actual close", 0, f.handoffs.get())
                    }
                    onMain { f.ad?.finish(); f.presentation?.onAdClosed() }
                }
                eventually("Exactly one handoff") { f.handoffs.get() == 1 }
                val noShow = case in setOf("failure", "timeout", "late_fill", "banner_budget", "home_expired")
                assertEquals(if (noShow) 0 else 1, f.shows.get())
                assertEquals(if (case == "other_route") 0 else 1, f.splashLfo.get())
                if (case in setOf("timeout", "late_fill", "banner_budget", "home_expired")) {
                    assertTrue("Banner cannot prepend another whole budget", SystemClock.elapsedRealtime() - f.requestAt < 5_500)
                    onMain { f.finishLoad(success = true) }
                }
                SystemClock.sleep(300)
                assertEquals(1, f.interLoads.get())
                assertEquals(1, f.handoffs.get())
                assertEquals(if (noShow) 0 else 1, f.shows.get())
                assertTrue(f.violations.toString(), f.violations.isEmpty())
            }
        } finally {
            onMain { f.ad?.finish(); f.presentation?.onAdClosed(); ConsentCenter.clearHostConsent() }
        }
    }

    private fun goHome() = assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
    private fun returnTask(id: Int) = onMain {
        app.getSystemService(ActivityManager::class.java).appTasks.single { it.taskInfo.taskId == id }.moveToFront()
    }
    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40)
        assertTrue(message, condition())
    }
}

class OrderingSplashDeviceActivity : ObSplashActivity() {
    override fun onResume() {
        super.onResume()
        android.util.Log.i("SPLASH_DEVICE", "resume host=${System.identityHashCode(this)} focus=${hasWindowFocus()}")
    }
    override fun onWindowFocusChanged(focused: Boolean) {
        super.onWindowFocusChanged(focused)
        android.util.Log.i("SPLASH_DEVICE", "focus host=${System.identityHashCode(this)} value=$focused")
    }
    override fun onCreateSafe(savedInstanceState: Bundle?) { OrderingFixture.creates.incrementAndGet(); super.onCreateSafe(savedInstanceState) }
    override suspend fun onConsentRequired(): Boolean {
        ConsentCenter.setHostConsent(OrderingFixture.consentAllowed, false)
        return OrderingFixture.consentAllowed
    }
    override fun onRemoteFetched() {
        OrderingFixture.remoteHooks.incrementAndGet()
        OnboardingSdk.remoteOrNull()?.applySnapshot(OrderingFixture.flags)
    }
    override fun nextScreenTiming() = if (OrderingFixture.afterAd) NextScreenTiming.AFTER_AD else NextScreenTiming.UNDER_AD
}

/** Real Android foreground owner for a controlled vendor presentation, with no production ad. */
class OrderingAdDeviceActivity : Activity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); OrderingFixture.ad = this }
    override fun onResume() { super.onResume(); OrderingFixture.presentation?.onNextAction() }
    override fun onDestroy() { if (OrderingFixture.ad === this) OrderingFixture.ad = null; super.onDestroy() }
}

private object OrderingFixture {
    var flags = RemoteFlags()
    var premium = false
    var consentAllowed = true
    var immediate = false
    var bannerPending = false
    var realPresentation = false
    var afterAd = false
    var requestAt = 0L
    val creates = AtomicInteger()
    val remoteHooks = AtomicInteger()
    val interLoads = AtomicInteger()
    val splashLfo = AtomicInteger()
    val shows = AtomicInteger()
    val handoffs = AtomicInteger()
    val splashOther = CopyOnWriteArrayList<String>()
    val violations = CopyOnWriteArrayList<String>()
    var pending: AdEventListener? = null
    var ready = false
    @Volatile var ad: Activity? = null
    var presentation: ObInterstitialCallback? = null
    fun finishLoad(success: Boolean) {
        ready = success
        if (success) pending?.onLoaded() else pending?.onFailedToLoad()
    }
    val provider = object : OnboardingAdProvider {
        override fun isPremium(context: Context) = premium
        override fun preloadNative(activity: Activity, request: NativeAdRequest) {
            if (activity is OrderingSplashDeviceActivity) {
                if (request.placement == AdPlacement.Language1) splashLfo.incrementAndGet()
                else splashOther += request.placement.key
            }
        }
        override fun isNativeReady(placement: AdPlacement) = false
        override fun isNativeLoading(placement: AdPlacement) = false
        override fun bindNative(activity: Activity, placement: AdPlacement, container: ViewGroup, shimmer: View?, listener: AdEventListener?) = false
        override fun releaseNative(placement: AdPlacement) = Unit
        override fun loadInterstitial(context: Context, placement: AdPlacement, unit: InterstitialAdUnit, listener: AdEventListener?) {
            requestAt = SystemClock.elapsedRealtime(); interLoads.incrementAndGet(); pending = listener
            if (immediate) finishLoad(true)
        }
        override fun isInterstitialReady(placement: AdPlacement) = ready
        override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) {
            shows.incrementAndGet()
            if (!activity.hasWindowFocus()) violations += "show without focus"
            if (realPresentation) {
                presentation = callback
                activity.startActivity(Intent(activity, OrderingAdDeviceActivity::class.java))
            } else callback.onAdSkipped(AdSkipReason.NOT_READY)
        }
        override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) {
            if (!bannerPending) listener?.onLoaded()
        }
        override fun suppressAppResume(activityClass: Class<out Activity>) = Unit
        override fun releaseAll() = Unit
    }
}
