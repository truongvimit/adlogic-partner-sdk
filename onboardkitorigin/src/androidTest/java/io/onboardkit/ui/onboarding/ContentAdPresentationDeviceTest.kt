package io.onboardkit.ui.onboarding

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.consent.ConsentCenter
import com.facebook.shimmer.ShimmerFrameLayout
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.*
import io.onboardkit.config.*
import io.onboardkit.core.StepId
import io.onboardkit.remote.OnboardingSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real views, shimmer, lifecycle and frame-by-frame transitions; deterministic vendor outcomes.
 * Run each contentCase in a fresh instrumentation process (see androidTest/README.md). */
@RunWith(AndroidJUnit4::class)
class ContentAdPresentationDeviceTest {
    @Test fun nativeOutcomesKeepOneViewTree() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val case = InstrumentationRegistry.getArguments().getString("contentCase") ?: "failure"
        require(case in setOf("disabled", "no_unit", "remote_off", "ready", "success", "failure",
            "sync_failure", "late_fill", "reverse", "background", "stale", "reentry", "recreate"))
        val provider = PresentationProvider(ready = case == "ready", synchronousFailure = case == "sync_failure")
        ins.runOnMainSync {
            ConsentCenter.setHostConsent(true, false)
            AdRemoteConfig.reset()
            OnboardingSdk.install(app) { adProvider = provider; trackkitAutoTracking(false) }
            OnboardingSettings.document.acceptSuccessfulFetch(null)
            OnboardingSdk.configure(onboardKitConfig {
                steps(*listOf(StepId.OB1, StepId.OB2).mapIndexed { i, id ->
                    ContentStepDefinition(id, title = "Onboarding ${i + 1}",
                        subtitle = "Keep the artwork and content in the same view tree.",
                        imageRes = app.resources.getIdentifier("ob_qa_art_${i + 1}", "drawable", app.packageName))
                }.toTypedArray())
                ads = if (case == "remote_off") AdsConfig.fromAdConfig().copy(afterOnboardingInterstitialEnabled = false)
                else AdsConfig(contentStepNative =
                    if (case in setOf("no_unit", "disabled")) null else NativeAdUnit("test-native"), afterOnboardingInterstitialEnabled = false)
                behavior = BehaviorConfig(lockPagerSwipe = false)
            }.getOrThrow()).getOrThrow()
            if (case == "remote_off") AdRemoteConfig.update(AdRemoteConfig(mapOf(
                "native_ob1" to AdUnitConfig("test-native", false),
            )))
        }
        runBlocking { OnboardingSdk.reset() }
        try {
            ActivityScenario.launch(ObOnboardingHostActivity::class.java).use { scenario ->
                fun page(host: ObOnboardingHostActivity) = host.supportFragmentManager.fragments
                    .filterIsInstance<ContentStepFragment>().first { it.isResumed }.requireView()
                fun settle() { ins.waitForIdleSync(); SystemClock.sleep(450) }
                fun assertGeometry(ad: Boolean) = scenario.onActivity { host ->
                    val root = page(host)
                    val image = root.findViewById<View>(R.id.ob_step_image)
                    val card = root.findViewById<View>(R.id.ob_step_card)
                    assertEquals(if (ad) View.VISIBLE else View.GONE, root.findViewById<View>(R.id.ob_ad_block).visibility)
                    assertEquals(root.height * (if (ad) .59f else .5f), image.height.toFloat(), 1f)
                    assertNotNull(card.background)
                    assertEquals(4 * root.resources.displayMetrics.density, card.elevation, .01f)
                    if (!ad) assertEquals(root.height * .75f - 25 * root.resources.displayMetrics.density,
                        (card.top + card.bottom) / 2f, 1f)
                }
                fun shot(name: String) {
                    val bitmap = ins.uiAutomation.takeScreenshot()
                    File(app.filesDir, "content-$case-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                scenario.onActivity { host ->
                    host.setTurnScreenOn(true)
                    host.setShowWhenLocked(true)
                    host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                settle()
                if (case in setOf("disabled", "no_unit", "remote_off", "sync_failure")) {
                    assertGeometry(false)
                    assertEquals(0, provider.requests)
                    if (case != "sync_failure") assertTrue(provider.listeners.isEmpty())
                    scenario.onActivity { assertEquals(0, page(it).findViewById<FrameLayout>(R.id.ob_native_container).childCount) }
                    shot("unavailable")
                    return@use
                }
                assertGeometry(true)
                if (case == "ready") { assertEquals(0, provider.requests); return@use }
                lateinit var original: View
                lateinit var card: View
                lateinit var image: View
                scenario.onActivity {
                    original = page(it)
                    card = original.findViewById(R.id.ob_step_card)
                    image = original.findViewById(R.id.ob_step_image)
                    val skeleton = original.findViewById<FrameLayout>(R.id.ob_native_container).getChildAt(0)
                    assertTrue(skeleton is ShimmerFrameLayout && skeleton.isShimmerStarted)
                }
                shot("loading")
                val listener = provider.listeners.getValue(AdPlacement.StepNative(StepId.OB1))
                if (case == "success") {
                    val before = card.top
                    scenario.onActivity { provider.fill(StepId.OB1) }
                    settle()
                    assertGeometry(true)
                    assertEquals(before, card.top)
                } else if (case == "stale") {
                    scenario.onActivity { it.findViewById<ViewPager2>(R.id.ob_step_pager).setCurrentItem(1, false) }
                    settle()
                    scenario.onActivity { listener.onFailedToLoad(); listener.onLoaded() }
                    settle()
                    assertGeometry(true)
                    scenario.onActivity { assertNotSame(original, page(it)) }
                    return@use
                } else {
                    if (case == "background") scenario.moveToState(Lifecycle.State.CREATED)
                    val positions = mutableListOf<Int>()
                    val frameTimes = mutableListOf<Long>()
                    var sampling = true
                    val frame = object : Choreographer.FrameCallback {
                        override fun doFrame(time: Long) {
                            if (!sampling) return
                            positions += card.top
                            frameTimes += time
                            Choreographer.getInstance().postFrameCallback(this)
                        }
                    }
                    scenario.onActivity {
                        positions += card.top
                        Choreographer.getInstance().postFrameCallback(frame)
                        listener.onFailedToLoad()
                    }
                    if (case == "reverse") {
                        SystemClock.sleep(100)
                        scenario.onActivity { provider.fill(StepId.OB1) }
                    }
                    if (case == "background") {
                        SystemClock.sleep(100)
                        assertEquals(View.VISIBLE, original.findViewById<View>(R.id.ob_ad_block).visibility)
                        scenario.moveToState(Lifecycle.State.RESUMED)
                    }
                    if (case == "recreate") {
                        SystemClock.sleep(80)
                        scenario.recreate()
                        settle()
                        scenario.onActivity { sampling = false; Choreographer.getInstance().removeFrameCallback(frame) }
                        assertGeometry(true)
                        return@use
                    }
                    settle()
                    scenario.onActivity { sampling = false; Choreographer.getInstance().removeFrameCallback(frame) }
                    assertGeometry(case == "reverse")
                    assertTrue("Transition must have intermediate positions: $positions", positions.distinct().size > 3)
                    if (case == "reverse") {
                        val maxStep = positions.zipWithNext().maxOf { (a, b) -> kotlin.math.abs(b - a) }
                        assertTrue("Reversing must continue from the current position: $positions", maxStep < original.height * .08f)
                    } else assertTrue("No position jump backwards: $positions", positions.zipWithNext().all { (a, b) -> b >= a })
                    Log.i("OB_PRESENTATION", "$case animatedPositions=${positions.distinct().size} frames=${frameTimes.size} maxFrameGapMs=${frameTimes.zipWithNext().maxOfOrNull { (a,b) -> (b-a)/1_000_000f }}")
                    shot("unavailable")
                    if (case == "late_fill") {
                        scenario.onActivity { provider.fill(StepId.OB1) }
                        settle()
                        assertGeometry(true)
                        scenario.onActivity { listener.onFailedToLoad() }
                        settle()
                        assertGeometry(true)
                    } else if (case == "reentry") {
                        scenario.onActivity { it.findViewById<ViewPager2>(R.id.ob_step_pager).setCurrentItem(1, false) }
                        settle()
                        provider.ready = true
                        scenario.onActivity { it.findViewById<ViewPager2>(R.id.ob_step_pager).setCurrentItem(0, false) }
                        settle()
                        assertGeometry(true)
                    }
                }
                scenario.onActivity {
                    assertSame(original, page(it))
                    assertSame(card, page(it).findViewById(R.id.ob_step_card))
                    assertSame(image, page(it).findViewById(R.id.ob_step_image))
                }
                shot("final")
            }
        } finally {
            ins.runOnMainSync { ConsentCenter.clearHostConsent(); AdRemoteConfig.reset() }
        }
        Log.i("OB_PRESENTATION", "PASS $case requests=${provider.requests}")
    }
}

private class PresentationProvider(var ready: Boolean, val synchronousFailure: Boolean) : FakeAdProvider() {
    val listeners = mutableMapOf<AdPlacement, AdEventListener>()
    private val containers = mutableMapOf<AdPlacement, FrameLayout>()
    var requests = 0
    override fun nativeStatus(placement: AdPlacement) = if (ready) NativeStatus.READY else NativeStatus.LOADING
    override fun preloadNative(activity: Activity, request: NativeAdRequest) { requests++ }
    override fun bindNative(activity: ComponentActivity, request: NativeAdRequest, container: FrameLayout, listener: AdEventListener): Boolean {
        listeners[request.placement] = listener
        containers[request.placement] = container
        if (synchronousFailure) listener.onFailedToLoad()
        if (ready) fill(request.placement.stepId())
        return ready
    }
    override fun releaseNative(placement: AdPlacement) { listeners[placement]?.onDetached() }
    fun fill(id: StepId) {
        val key = AdPlacement.StepNative(id)
        val container = containers.getValue(key)
        container.removeAllViews()
        container.visibility = View.VISIBLE
        container.addView(TextView(container.context).apply {
            text = "Native ad filled"; gravity = android.view.Gravity.CENTER; setBackgroundColor(Color.LTGRAY)
        }, FrameLayout.LayoutParams(-1, -1))
        listeners.getValue(key).onLoaded()
    }
    private fun AdPlacement.stepId() = (this as AdPlacement.StepNative).stepId
}
