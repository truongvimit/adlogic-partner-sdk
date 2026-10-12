package io.onboardkit.ui.onboarding

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.viewpager2.widget.ViewPager2
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.nativead.NativeAdView
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.*
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.config.*
import io.onboardkit.core.StepId
import io.onboardkit.remote.OnboardingSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real pager + Google native test unit; `-e pagerPaddingPx` sets the peek. Run in a fresh process. */
@RunWith(AndroidJUnit4::class)
class PagerImpressionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun measureRealImpressions() {
        val padding = InstrumentationRegistry.getArguments().getString("pagerPaddingPx")?.toInt() ?: 0
        require(padding in 0..1)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val ids = listOf(StepId.OB1, StepId.FULL1, StepId.OB2, StepId.FULL2, StepId.OB3, StepId.OB4)
        val provider = RecordingProvider(padding, ids)
        val initialized = CountDownLatch(1)
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val observer = ActivityLifecycleCallback { activity, stage ->
            if (activity is ObOnboardingHostActivity && stage == Stage.CREATED) {
                // Must precede first layout, or the old scroll offset shifts the peek to one side.
                val pager = activity.findViewById<ViewPager2>(R.id.ob_step_pager)
                val recycler = pager.getChildAt(0) as RecyclerView
                recycler.setPadding(padding, 0, padding, 0)
                recycler.clipToPadding = padding == 0
                pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) { provider.selected(position) }
                })
                provider.selected(pager.currentItem)
            }
        }
        instrumentation.runOnMainSync {
            monitor.addLifecycleCallback(observer)
            ConsentCenter.setHostConsent(true, false)
            ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
                setFacebookClientToken("123456789")
                setIdAdResume("")
            })
            AppOpenManager.getInstance().disableAppResume()
            MobileAds.initialize(app) { initialized.countDown() }
            OnboardingSdk.install(app) { adProvider = provider; trackkitAutoTracking(false) }
            OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"fullscreen":{"auto_next":{"enabled":false}}}}""")
            AdRemoteConfig.update(AdRemoteConfig(ids.associate {
                "native_${it.value}" to AdUnitConfig(listOf(TEST_UNIT), true, enableUaCheck = false)
            }))
            OnboardingSdk.configure(onboardKitConfig {
                steps(*ids.map { id ->
                    if (id.value.startsWith("full")) AdFullScreenStepDefinition(id, autoNextEnabled = false)
                    else ContentStepDefinition(id, title = id.value)
                }.toTypedArray())
                ads = AdsConfig.fromAdConfig().copy(afterOnboardingInterstitialEnabled = false)
            }.getOrThrow()).getOrThrow()
        }
        try {
            assertTrue("GMA initialization", initialized.await(45, TimeUnit.SECONDS))
            ActivityScenario.launch<ObOnboardingHostActivity>(Intent(app, ObOnboardingHostActivity::class.java)).use { scenario ->
                scenario.onActivity { host ->
                    OnboardingSdk.preload().onLanguageSelected(host)
                }
                // All six real fills are required; no-fill is inconclusive, never a passing result.
                val deadline = SystemClock.elapsedRealtime() + 60_000
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { ready = ids.all { provider.ready(it) } }
                    SystemClock.sleep(100)
                }
                assertTrue("All six real test ads must fill", ready)
                ids.forEachIndexed { index, id ->
                    scenario.onActivity { host -> host.findViewById<ViewPager2>(R.id.ob_step_pager).setCurrentItem(index, false) }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(4_000)
                    scenario.onActivity {
                        provider.snapshot("DWELL_${id.value}")
                        provider.checkNeighborGeometry(index)
                        assertTrue("Visible $id must produce a real impression", provider.impressions.containsKey(id))
                    }
                }
                scenario.onActivity { provider.summary() }
            }
        } finally {
            instrumentation.runOnMainSync {
                monitor.removeLifecycleCallback(observer)
                provider.releaseAll()
                ConsentCenter.clearHostConsent()
                AdRemoteConfig.reset()
                OnboardingSettings.document.acceptSuccessfulFetch(null)
            }
        }
    }

    private class RecordingProvider(val padding: Int, val ids: List<StepId>) : FakeAdProvider() {
        private val real = ERainAdProvider()
        private val start = SystemClock.elapsedRealtime()
        private val containers = mutableMapOf<StepId, FrameLayout>()
        private val selectionTimes = mutableMapOf<StepId, Long>()
        private val impressionCounts = mutableMapOf<StepId, Int>()
        val impressions = mutableMapOf<StepId, Long>()
        private var current = 0
        private fun now() = SystemClock.elapsedRealtime() - start
        private fun log(message: String) = Log.i(TAG, "padding=$padding t=${now()} $message")
        private fun id(placement: AdPlacement): StepId = when (placement) {
            is AdPlacement.StepNative -> placement.stepId
            is AdPlacement.StepFullScreen -> placement.stepId
            else -> error("Unexpected placement $placement")
        }
        private fun placement(id: StepId): AdPlacement = if (id.value.startsWith("full")) AdPlacement.StepFullScreen(id) else AdPlacement.StepNative(id)
        fun ready(id: StepId) = containers[id]?.let { native(it) != null } == true || real.nativeStatus(placement(id)) == NativeStatus.READY
        fun selected(position: Int) {
            current = position
            selectionTimes.putIfAbsent(ids[position], now())
            log("SELECT step=${ids[position].value}")
        }
        override fun preloadNative(activity: Activity, request: NativeAdRequest) {
            log("PRELOAD step=${id(request.placement).value}")
            real.preloadNative(activity, request)
        }
        override fun nativeStatus(placement: AdPlacement) = real.nativeStatus(placement)
        override fun bindNative(activity: ComponentActivity, request: NativeAdRequest, container: FrameLayout, listener: AdEventListener): Boolean {
            val step = id(request.placement)
            containers[step] = container
            log("BIND_REQUEST step=${step.value} selected=${ids[current].value}")
            return real.bindNative(activity, request, container, object : AdEventListener by listener {
                override fun onLoaded() { log("BOUND step=${step.value}"); listener.onLoaded() }
                override fun onFailedToLoad() { log("FAILED step=${step.value}"); listener.onFailedToLoad() }
                override fun onImpression() {
                    impressionCounts[step] = (impressionCounts[step] ?: 0) + 1
                    impressions.putIfAbsent(step, now())
                    log("IMPRESSION step=${step.value} selected=${ids[current].value} before_selection=${step !in selectionTimes} ${geometry(container)}")
                    listener.onImpression()
                }
            }).also { if (it) log("BOUND_SYNC step=${step.value}") }
        }
        override fun releaseNative(placement: AdPlacement) = real.releaseNative(placement)
        override fun pendingClickAction(placement: AdPlacement) = real.pendingClickAction(placement)
        override fun releaseAll() = real.releaseAll()
        fun snapshot(label: String) {
            containers.forEach { (id, view) -> log("$label step=${id.value} ${geometry(view)}") }
        }
        fun summary() {
            ids.forEach { id ->
                val delta = impressions.getValue(id) - selectionTimes.getValue(id)
                log("RESULT step=${id.value} selected_ms=${selectionTimes[id]} impression_ms=${impressions[id]} delta_ms=$delta count=${impressionCounts[id]}")
                assertEquals("One impression per ad", 1, impressionCounts[id])
                if (padding == 0) assertTrue("Fully offscreen $id must not count early", delta >= 0)
            }
        }
        fun checkNeighborGeometry(index: Int) {
            val next = ids.getOrNull(index + 1) ?: return
            val view = checkNotNull(containers[next]?.let(::native))
            val rect = Rect()
            val visible = view.getGlobalVisibleRect(rect)
            assertEquals("Next native $next must expose exactly $padding pixels", padding, if (visible) rect.width() else 0)
        }
        private fun native(view: View): NativeAdView? {
            if (view is NativeAdView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) native(view.getChildAt(index))?.let { return it }
            return null
        }
        private fun geometry(container: View): String {
            val view = native(container) ?: return "native=false"
            val rect = Rect()
            val visible = view.getGlobalVisibleRect(rect)
            val location = IntArray(2).also(view::getLocationOnScreen)
            return "native=true attached=${view.isAttachedToWindow} shown=${view.isShown} size=${view.width}x${view.height} xy=${location.joinToString()} visible=$visible visible_px=${if (visible) rect.width() else 0}x${if (visible) rect.height() else 0}"
        }
    }

    companion object {
        private const val TAG = "PAGER_IMPRESSION"
        private const val TEST_UNIT = "ca-app-pub-3940256099942544/2247696110"
    }
}
