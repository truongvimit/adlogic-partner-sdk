package io.onboardkit.ui.splash

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.ads.NativeStatus
import io.onboardkit.config.AdLoadStrategy
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.SplashConfig
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.AnalyticsPlugin
import io.onboardkit.remote.RemoteFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        require(case in setOf("success", "failure", "timeout", "late_fill", "banner_budget", "recreate", "mode_refresh", "rotate",
            "inter_off", "no_unit", "premium", "master_off", "consent_denied", "same_time", "other_route",
            "home_pending", "home_expired", "under_ad", "under_ad_slow", "under_ad_home", "under_ad_recreate", "after_ad", "after_ad_recreate", "native_ready", "native_loading", "native_failed", "silent_banner", "remote_pending"))
        val parallel = args.getString("lfoParallel") == "true"
        f.flags = RemoteFlags(splashLfoParallelPreloadEnabled = parallel, splashMinDisplayMs = 200,
            splashAdBudgetMs = 3_000, splashSlotMinVisibleMs = if (case == "silent_banner") 60_000 else if (case == "banner_budget") 2_200 else 0,
            adsSplashInter = case != "inter_off" && case != "master_off")
        f.nativeBehavior = case.takeIf { it.startsWith("native_") }
        f.premium = case == "premium"
        f.consentAllowed = case != "consent_denied"
        f.immediate = case == "same_time"
        f.bannerPending = case in setOf("banner_budget", "silent_banner")
        f.realPresentation = case in setOf("under_ad", "under_ad_slow", "under_ad_home", "under_ad_recreate", "after_ad", "after_ad_recreate")
        f.afterAd = case in setOf("after_ad", "after_ad_recreate")
        if (f.realPresentation && case != "under_ad_slow") f.flags = f.flags.copy(splashMinDisplayMs = 5_000)
        onMain {
            assertFalse("Fresh instrumentation process required", OnboardingSdk.isReady())
            Entitlement.install(object : EntitlementSource {
                override fun isPremium(context: Context) = f.premium
            })
            OnboardingSdk.install(app) {
                adProvider = f.provider
                trackkitAutoTracking(false)
                analyticsPlugin(AnalyticsPlugin {
                    if (it is AnalyticsEvent.SplashCompleted) f.handoffs.incrementAndGet()
                })
            }
            OnboardingSdk.remoteOrNull()?.applySnapshot(f.flags)
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
        }
        runBlocking {
            OnboardingSdk.reset()
            if (case == "other_route") OnboardingSdk.markCompleted()
        }
        val remoteResult = kotlinx.coroutines.CompletableDeferred<Unit>()
        if (case == "remote_pending") com.ads.module.config.AdConfig.install(
            object : com.ads.module.config.AdConfigSource, com.ads.module.config.settings.SettingsConfigSource {
                override val id = "device-delayed-remote"
                override suspend fun fetch(timeoutMs: Long): String? = null
                override suspend fun fetchSettings(timeoutMs: Long): Map<String, String?> {
                    remoteResult.await()
                    return mapOf("onboarding_config" to """{"lfo":{"tap_hint":{"enabled":false}}}""")
                }
            },
        )
        try {
            ActivityScenario.launch<OrderingSplashDeviceActivity>(Intent(app, OrderingSplashDeviceActivity::class.java)).use { scenario ->
                lateinit var host: OrderingSplashDeviceActivity
                scenario.onActivity { host = it }
                val blocked = case in setOf("premium", "master_off", "consent_denied")
                if (blocked || case in setOf("inter_off", "no_unit")) {
                    eventually("Skip must hand off promptly") { f.handoffs.get() == 1 }
                    assertEquals(0, f.interLoads.get())
                    assertEquals(0, f.shows.get())
                    assertEquals(if (blocked) 0 else 1, f.splashLfo.get())
                    return@use
                }
                eventually("Interstitial request") { f.interLoads.get() == 1 }
                if (case == "same_time") {
                    // The immediate provider can finish the 200ms splash during ActivityScenario
                    // startup. Its destination then owns focus; do not wait on the finished host.
                    eventually("Early inter terminal result must not be lost") { f.handoffs.get() == 1 }
                    assertEquals(1, f.splashLfo.get())
                    assertEquals(1, f.shows.get())
                    return@use
                }
                eventually("Splash regains actual focus") { onMain { host.hasWindowFocus() } }
                if (parallel && case != "other_route") eventually("Parallel starts LFO1") { f.splashLfo.get() == 1 }
                else assertEquals(0, f.splashLfo.get())
                assertEquals("OB1/LFO2 must not be pulled into the early request phase", emptyList<String>(), f.splashOther.toList())

                if (case in setOf("recreate", "mode_refresh", "rotate")) {
                    if (case == "mode_refresh") onMain {
                        f.flags = f.flags.copy(splashLfoParallelPreloadEnabled = !parallel)
                        OnboardingSdk.remoteOrNull()?.applySnapshot(f.flags)
                    }
                    if (case == "rotate") {
                        scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                        eventually("Actual orientation change must recreate splash") { f.creates.get() == 2 }
                    } else scenario.recreate()
                    scenario.onActivity { host = it }
                    eventually("Recreated splash focus") { onMain { host.hasWindowFocus() } }
                    assertEquals(2, f.creates.get())
                    assertEquals(1, f.remoteHooks.get())
                    assertEquals(1, f.interLoads.get())
                    assertEquals(if (parallel || case == "mode_refresh") 1 else 0, f.splashLfo.get())
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
                    "under_ad_slow" -> { SystemClock.sleep(500); onMain { f.finishLoad(success = true) } }
                    "timeout", "late_fill", "banner_budget" -> Unit
                    "failure" -> onMain { f.finishLoad(success = false) }
                    else -> onMain { f.finishLoad(success = true) }
                }
                if (f.realPresentation) {
                    eventually("Separate ad Activity is resumed") { f.ad != null && onMain { f.ad?.hasWindowFocus() == true } }
                    if (case in setOf("under_ad_recreate", "after_ad_recreate")) {
                        // ActivityScenario.recreate() first forces RESUMED, which cannot happen
                        // beneath a fullscreen ad. Request recreation without foregrounding it.
                        onMain { host.recreate() }
                        eventually("Splash owner must be recreated while the ad remains visible") { f.creates.get() == 2 }
                        assertTrue("Recreation must not cover the ad", onMain { f.ad?.hasWindowFocus() == true })
                        assertEquals(if (f.afterAd) 0 else 1, f.handoffs.get())
                    }
                    if (case == "under_ad_home") {
                        goHome()
                        eventually("Ad no longer resumed") { onMain { f.ad?.hasWindowFocus() == false } }
                        SystemClock.sleep(5_500)
                        assertEquals("UnderAd already navigated before showing the ad", 1, f.handoffs.get())
                        returnTask(host.taskId)
                        eventually("Ad must remain above the destination after returning") {
                            onMain { f.ad?.hasWindowFocus() == true }
                        }
                    } else if (f.afterAd) {
                        SystemClock.sleep(5_500)
                        assertEquals("AFTER_AD must wait for actual close", 0, f.handoffs.get())
                    }
                    if (!f.afterAd) {
                        assertEquals(1, f.handoffs.get())
                        SystemClock.sleep(500)
                        assertTrue("Destination must stay underneath the ad", onMain { f.ad?.hasWindowFocus() == true })
                    }
                    onMain { f.ad?.finish(); f.presentation?.onAdClosed() }
                }
                eventually("Exactly one handoff") { f.handoffs.get() == 1 }
                if (case == "silent_banner") {
                    assertTrue("Ready inter ignores the 60-second slot minimum",
                        SystemClock.elapsedRealtime() - f.requestAt < 2_500)
                }
                if (case == "remote_pending") {
                    assertEquals("Remote is still pending after splash handoff", 0, f.remoteHooks.get())
                    scenario.close()
                    remoteResult.complete(Unit)
                    eventually("Remote settings apply after splash destruction") {
                        onMain { !OnboardingSdk.requireConfig().language.tapHintEnabled }
                    }
                    assertEquals("No callback into a dead Activity", 0, f.remoteHooks.get())
                }
                val noShow = case in setOf("failure", "timeout", "late_fill", "banner_budget", "home_expired")
                assertEquals(if (noShow) 0 else 1, f.shows.get())
                assertEquals(if (case == "other_route") 0 else 1, f.splashLfo.get())
                if (case in setOf("timeout", "late_fill", "banner_budget", "home_expired")) {
                    assertTrue("Banner cannot prepend another whole budget", SystemClock.elapsedRealtime() - f.requestAt < 5_500)
                    onMain { f.finishLoad(success = true) }
                }
                if (f.nativeBehavior != null) {
                    if (case == "native_failed") {
                        eventually("Failed preload must finish the slot without retry") { f.nativeReleases.get() > 0 }
                        assertEquals(0, f.nativeBinds.get())
                    } else {
                        if (case == "native_loading") {
                            eventually("Language must subscribe to the original pending native") { f.nativeListener != null }
                            assertEquals(1, f.nativeRequests.get())
                            onMain {
                                f.nativeState = "ready"
                                if (f.bindReady()) f.nativeListener?.onLoaded()
                            }
                        }
                        eventually("The ready native must bind exactly once") { f.nativeBinds.get() == 1 }
                        assertEquals("consumed", f.nativeState)
                    }
                    assertEquals("Screen entry cannot duplicate or retry this preload", 1, f.nativeRequests.get())
                }
                SystemClock.sleep(300)
                assertEquals(1, f.interLoads.get())
                assertEquals(1, f.handoffs.get())
                assertEquals(if (noShow) 0 else 1, f.shows.get())
                assertTrue(f.violations.toString(), f.violations.isEmpty())
            }
        } finally {
            remoteResult.complete(Unit)
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
    }
    override fun nextScreenTiming() = if (OrderingFixture.afterAd) NextScreenTiming.AFTER_AD else NextScreenTiming.UNDER_AD
}

/** Real Android foreground owner for a controlled vendor presentation, with no production ad. */
class OrderingAdDeviceActivity : Activity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); OrderingFixture.ad = this }
    override fun onDestroy() { if (OrderingFixture.ad === this) OrderingFixture.ad = null; super.onDestroy() }
}

private object OrderingFixture {
    var nativeBehavior: String? = null
    var nativeState: String? = null
    val nativeRequests = AtomicInteger()
    val nativeBinds = AtomicInteger()
    val nativeReleases = AtomicInteger()
    @Volatile var nativeListener: AdEventListener? = null
    private var nativeSlot: Pair<Activity, FrameLayout>? = null
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
    fun bindReady(): Boolean {
        val (activity, container) = nativeSlot ?: return false
        if (nativeState != "ready") return false
        nativeSlot = null
        container.removeAllViews()
        container.addView(android.widget.TextView(activity).apply { text = "Device native" })
        nativeState = "consumed"
        nativeBinds.incrementAndGet()
        return true
    }
    val provider = object : FakeAdProvider() {
        override fun preloadNative(activity: Activity, request: NativeAdRequest) = sendInRequestWindow(activity, request) {
            if (request.placement == AdPlacement.Language1 && nativeBehavior != null && nativeState !in setOf("ready", "loading")) {
                nativeRequests.incrementAndGet()
                nativeState = nativeBehavior!!.removePrefix("native_")
            }
            if (activity is OrderingSplashDeviceActivity) {
                if (request.placement == AdPlacement.Language1) {
                    splashLfo.incrementAndGet()
                    if (nativeState == null) nativeState = "loading"
                } else splashOther += request.placement.key
            }
        }
        override fun nativeStatus(placement: AdPlacement) = when {
            placement != AdPlacement.Language1 -> NativeStatus.IDLE
            nativeState == "ready" -> NativeStatus.READY
            nativeState == "loading" -> NativeStatus.LOADING
            nativeState == "failed" -> NativeStatus.FAILED
            else -> NativeStatus.IDLE
        }
        override fun bindNative(activity: ComponentActivity, request: NativeAdRequest, container: FrameLayout, listener: AdEventListener): Boolean {
            if (request.placement != AdPlacement.Language1 || nativeBehavior == null) return false
            nativeListener = listener
            nativeSlot = activity to container
            return bindReady()
        }
        override fun releaseNative(placement: AdPlacement) {
            if (placement == AdPlacement.Language1) { nativeListener = null; nativeSlot = null; nativeReleases.incrementAndGet() }
        }
        override fun loadInterstitial(activity: Activity, placement: AdPlacement, unit: InterstitialAdUnit, adConfigKey: String?, listener: AdEventListener?) {
            requestAt = SystemClock.elapsedRealtime(); interLoads.incrementAndGet(); pending = listener
            if (immediate) finishLoad(true)
        }
        override fun isInterstitialReady(placement: AdPlacement) = ready
        override fun loadAndShowInterstitial(
            activity: androidx.appcompat.app.AppCompatActivity,
            placement: AdPlacement,
            unit: InterstitialAdUnit,
            callback: ObInterstitialCallback,
            timeoutMs: Long,
        ) {
            throw AssertionError("This fixture does not expect a loadAndShow request: ${placement.key}")
        }

        override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) {
            shows.incrementAndGet()
            if (!activity.hasWindowFocus()) violations += "show without focus"
            if (realPresentation) {
                presentation = callback
                // Match the production provider: next is synchronous, immediately BEFORE show.
                callback.onNextAction()
                if (handoffs.get() != if (afterAd) 0 else 1) violations += "navigation did not run before vendor show"
                activity.startActivity(Intent(activity, OrderingAdDeviceActivity::class.java))
            } else callback.onAdSkipped(AdSkipReason.NOT_READY)
        }
        override fun loadBanner(activity: androidx.appcompat.app.AppCompatActivity, unit: BannerAdUnit, listener: AdEventListener) {
            if (!bannerPending) listener.onLoaded()
        }
    }
}
