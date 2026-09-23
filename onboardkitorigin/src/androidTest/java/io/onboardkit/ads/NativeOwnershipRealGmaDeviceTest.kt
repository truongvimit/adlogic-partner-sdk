package io.onboardkit.ads

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.adnative.NativeAdConfig
import com.ads.module.helper.adnative.NativeAdHelper
import com.ads.module.helper.adnative.NativeAdManager
import com.ads.module.helper.adnative.NativeAdPreload
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.nativead.NativeAdView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Google test native, real loader/cache/binder and real Activity recreation/departure. */
@RunWith(AndroidJUnit4::class)
class NativeOwnershipRealGmaDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val f get() = NativeDeviceFixture

    /** nativeRotation=true replaces the bound recreate with an Activity-requested orientation change. */
    @Test fun realNativeBindingSurvivesRecreationBackAndFinalDestroy() {
        val rotateBoundHost = InstrumentationRegistry.getArguments().getString("nativeRotation") == "true"
        val initialized = CountDownLatch(1)
        onMain {
            NativeAdManager.releaseAll()
            f.refresh = false
            f.fills.clear()
            f.binds.clear()
            f.errors.clear()
            f.hostEvents.clear()
            ConsentCenter.setHostConsent(true, false)
            Entitlement.install(object : EntitlementSource { override fun isPremium(context: Context) = false })
            ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
                facebookClientToken = "123456789"
                idAdResume = ""
            })
            AppOpenManager.getInstance().disableAppResume()
            MobileAds.initialize(app) { initialized.countDown() }
            NativeAdPreload.getInstance().registerAdCallback(f.key, object : AdCallback() {
                override fun onNativeAdLoaded(nativeAd: ApNativeAd) { f.fills += nativeAd }
                override fun onAdFailedToLoad(error: LoadAdError?) { f.errors += error.toString() }
            })
        }
        try {
            assertTrue("GMA initialization", initialized.await(45, TimeUnit.SECONDS))
            ActivityScenario.launch<NativeOwnershipDeviceActivity>(
                Intent(app, NativeOwnershipDeviceActivity::class.java),
            ).use { scenario ->
                lateinit var host: NativeOwnershipDeviceActivity
                scenario.onActivity { host = it }
                if (rotateBoundHost) {
                    onMain { host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                    eventually("The native host must start in portrait") {
                        onMain {
                            val current = AppOpenManager.getInstance().currentActivity
                            current is NativeOwnershipDeviceActivity &&
                                current.lifecycle.currentState == Lifecycle.State.RESUMED &&
                                current.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                        }
                    }
                    scenario.onActivity { host = it }
                }
                eventually("Native host focus") { onMain { host.hasWindowFocus() } }
                onMain {
                    host.helper.show()
                    assertTrue("The real request starts before recreation", NativeAdManager.isLoading(f.key))
                }
                val firstHost = host
                // GMA may complete before Android performs the recreation. Either a pending load
                // or its ready presentation must transfer without a second vendor request.
                scenario.recreate()
                scenario.onActivity { host = it; host.helper.show() }
                assertNotSame(firstHost, host)
                eventually("First real native must bind: ${f.errors}", 40_000) {
                    onMain { host.helper.nativeAd != null } || f.errors.isNotEmpty()
                }
                assertTrue("Need a real GMA fill: ${f.errors}", f.errors.isEmpty())
                val first = onMain { checkNotNull(host.helper.nativeAd) }
                assertEquals("Recreation must share the original load", 1, f.fills.size)
                assertFalse(onMain { NativeAdManager.isReady(f.key) })
                assertTrue(onMain {
                    first.isUsable && host.container.isShown &&
                        (0 until host.container.childCount).any { host.container.getChildAt(it) is NativeAdView }
                })
                eventually("The real native must report a vendor impression", 20_000) {
                    f.hostEvents.any { it.kind == "impression" }
                }

                val bindsBeforeDeparture = f.binds.size
                onMain { host.startActivity(Intent(host, InterstitialRealGmaDeviceActivity::class.java)) }
                eventually("The destination must stop the native host") { scenario.state == Lifecycle.State.CREATED }
                assertTrue("Stopping keeps the consumed native for a normal return", onMain { first.isUsable })
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                eventually("Back must return to the same native host") {
                    scenario.state == Lifecycle.State.RESUMED && onMain { host.hasWindowFocus() }
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertSame(host, it)
                    assertSame(first, it.helper.nativeAd)
                    assertTrue(it.container.isShown)
                }
                assertEquals("Ordinary return must retain its binding", bindsBeforeDeparture, f.binds.size)
                assertEquals("Ordinary return must not reload", 1, f.fills.size)

                val bindsBeforeRecreation = f.binds.size
                val boundHost = host
                if (rotateBoundHost) {
                    onMain { boundHost.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                    eventually("Android must recreate the bound host in landscape") {
                        onMain {
                            val current = AppOpenManager.getInstance().currentActivity
                            current is NativeOwnershipDeviceActivity && current !== boundHost &&
                                current.lifecycle.currentState == Lifecycle.State.RESUMED &&
                                current.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        }
                    }
                } else {
                    scenario.recreate()
                }
                scenario.onActivity { host = it; host.helper.show() }
                assertNotSame(boundHost, host)
                eventually("The recreated host must rebind the same consumed ad") {
                    onMain { host.helper.nativeAd === first && host.container.isShown }
                }
                assertEquals(bindsBeforeRecreation + 1, f.binds.size)
                assertSame(first, f.binds.last())
                assertEquals("A bound recreation must not request a replacement", 1, f.fills.size)
                assertEquals("One real impression for the one consumed native", 1,
                    f.hostEvents.count { it.kind == "impression" })

                // Finishing is a real destruction, unlike Home or opening another Activity.
                onMain { host.finish() }
                eventually("Finishing must destroy the native host") { scenario.state == Lifecycle.State.DESTROYED }
                instrumentation.waitForIdleSync()
                assertFalse("Final destruction releases the consumed ad", onMain { first.isUsable })
                assertFalse(onMain { NativeAdManager.isReady(f.key) })
                val destroyedHosts = mutableSetOf<Int>()
                for (event in f.hostEvents) {
                    if (event.kind == "destroy") destroyedHosts += event.hostId
                    else {
                        assertFalse("No late ${event.kind} callback on retired host ${event.hostId}",
                            event.hostId in destroyedHosts)
                        assertNotEquals("No callback may target a destroyed lifecycle", Lifecycle.State.DESTROYED, event.state)
                    }
                }
                assertTrue("No vendor errors: ${f.errors}", f.errors.isEmpty())
            }
        } finally {
            onMain {
                NativeAdManager.releaseAll()
                ConsentCenter.clearHostConsent()
                f.fills.clear()
                f.binds.clear()
                f.errors.clear()
                f.hostEvents.clear()
            }
        }
    }

    @Test fun realNativeOwnershipSurvivesRecreationAndConsumesEachPresentation() {
        f.refresh = InstrumentationRegistry.getArguments().getString("nativeRefresh") == "true"
        val initialized = CountDownLatch(1)
        onMain {
            NativeAdManager.releaseAll()
            f.fills.clear()
            f.binds.clear()
            f.errors.clear()
            f.hostEvents.clear()
            ConsentCenter.setHostConsent(true, false)
            Entitlement.install(object : EntitlementSource { override fun isPremium(context: Context) = false })
            ERainAd.getInstance().init(app, ERainAdConfig(app).apply { facebookClientToken = "123456789"; idAdResume = "" })
            AppOpenManager.getInstance().disableAppResume()
            MobileAds.initialize(app) { initialized.countDown() }
            NativeAdPreload.getInstance().registerAdCallback(f.key, object : AdCallback() {
                override fun onNativeAdLoaded(nativeAd: ApNativeAd) { f.fills += nativeAd }
                override fun onAdFailedToLoad(error: LoadAdError?) { f.errors += error.toString() }
            })
        }
        assertTrue("GMA initialization", initialized.await(45, TimeUnit.SECONDS))
        try {
            lateinit var finalPresentation: ApNativeAd
            ActivityScenario.launch<NativeOwnershipDeviceActivity>(Intent(app, NativeOwnershipDeviceActivity::class.java)).use { scenario ->
                lateinit var host: NativeOwnershipDeviceActivity
                scenario.onActivity { host = it }
                eventually("Native host focus") { onMain { host.hasWindowFocus() } }
                onMain {
                    assertTrue(NativeAdManager.preload(host, f.key, f.config()))
                    repeat(5) { assertFalse("Loading dedup", NativeAdManager.preload(host, f.key, f.config())) }
                }
                // Recreate while the original real request is still pending; a very fast fill
                // instead exercises the equivalent ready handoff, never a second request.
                scenario.recreate()
                scenario.onActivity { host = it; host.helper.show() }
                eventually("First real native must bind: ${f.errors}", 40_000) { f.binds.isNotEmpty() || f.errors.isNotEmpty() }
                assertTrue("Need real GMA fill: ${f.errors}", f.errors.isEmpty())
                val first = f.binds.last()
                assertEquals(1, f.fills.size)
                assertFalse(onMain { NativeAdManager.isReady(f.key) })
                assertTrue(onMain { first.isUsable && host.container.childCount > 0 })
                if (!f.refresh) {
                    val before = f.binds.size
                    scenario.recreate()
                    scenario.onActivity { host = it; host.helper.show() }
                    eventually("Recreation must rebind the same consumed native") { f.binds.size > before }
                    assertSame(first, f.binds.last())
                    assertEquals("Recreation must not load another ad", 1, f.fills.size)
                    onMain {
                        repeat(5) { host.helper.show() }
                        assertTrue("The bound ad remains usable while its replacement loads", first.isUsable)
                        assertTrue(NativeAdManager.isLoading(f.key))
                    }
                    eventually("An explicit show after bind must replace the consumed native", 40_000) { f.binds.last() !== first }
                    assertEquals("Repeated show while loading joins that one new request", 2, f.fills.size)
                    assertFalse(onMain { first.isUsable })
                } else {
                    eventually("Visible refresh loads and swaps a second real ad: ${f.errors}", 40_000) { f.fills.size >= 2 && f.binds.last() !== first }
                    assertFalse(onMain { first.isUsable })
                }
                val departing = f.binds.last()
                val fillsBeforeHome = f.fills.size
                val bindsBeforeHome = f.binds.size
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                eventually("Actual Home stops native host") { scenario.state == Lifecycle.State.CREATED }
                // Pause/stop suspend refresh; only replacement or final destruction consumes ownership.
                assertTrue("Home retains the consumed native", onMain { departing.isUsable })
                SystemClock.sleep(if (f.refresh) 3_500 else 300)
                assertEquals("No refresh loop while Home", fillsBeforeHome, f.fills.size)
                assertEquals("Home does not replace the binding", bindsBeforeHome, f.binds.size)
                assertTrue("The retained native stays usable while stopped", onMain { departing.isUsable })
                onMain { app.getSystemService(ActivityManager::class.java).appTasks.single { it.taskInfo.taskId == host.taskId }.moveToFront() }
                eventually("The same native host returns") {
                    scenario.state == Lifecycle.State.RESUMED && onMain { host.hasWindowFocus() }
                }
                if (f.refresh) {
                    // The 3-second refresh deadline elapsed while stopped. Its normal timer may
                    // resume now; Home itself must not have discarded the previous creative.
                    eventually("The overdue visible refresh replaces the retained native: ${f.errors}", 40_000) {
                        f.binds.last() !== departing
                    }
                    assertFalse("Replacement releases the previous native", onMain { departing.isUsable })
                } else {
                    instrumentation.waitForIdleSync()
                    assertSame("Ordinary return keeps the same consumed native", departing, onMain { host.helper.nativeAd })
                    assertEquals("Ordinary return does not rebind", bindsBeforeHome, f.binds.size)
                    assertEquals("Ordinary return does not reload", fillsBeforeHome, f.fills.size)
                }
                assertTrue(onMain { f.binds.last().isUsable })
                assertFalse(onMain { NativeAdManager.isReady(f.key) })
                assertTrue("No vendor errors: ${f.errors}", f.errors.isEmpty())
                finalPresentation = f.binds.last()
            }
            assertFalse("Final Activity destruction releases its consumed native", onMain { finalPresentation.isUsable })
        } finally {
            onMain { NativeAdManager.releaseAll(); ConsentCenter.clearHostConsent() }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun eventually(message: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }
}

class NativeOwnershipDeviceActivity : AppCompatActivity() {
    lateinit var helper: NativeAdHelper
    lateinit var container: FrameLayout
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        container = FrameLayout(this)
        setContentView(container)
        helper = NativeAdHelper(this, this, NativeDeviceFixture.config()).setNativeContentView(container)
        helper.placement = NativeDeviceFixture.key
        if (NativeDeviceFixture.refresh) helper.applyReloadByTime(3_000)
        helper.registerAdListener(object : AdCallback() {
            override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
                record("bind")
                NativeDeviceFixture.binds += nativeAd
            }
            override fun onAdImpression() { record("impression") }
            override fun onAdFailedToLoad(error: LoadAdError?) {
                record("failed")
                NativeDeviceFixture.errors += error.toString()
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        record("destroy")
    }

    private fun record(kind: String) {
        NativeDeviceFixture.hostEvents += NativeHostEvent(System.identityHashCode(this), kind, lifecycle.currentState)
    }
}

private data class NativeHostEvent(val hostId: Int, val kind: String, val state: Lifecycle.State)

private object NativeDeviceFixture {
    const val key = "device_native_ownership"
    var refresh = false
    val fills = CopyOnWriteArrayList<ApNativeAd>()
    val binds = CopyOnWriteArrayList<ApNativeAd>()
    val errors = CopyOnWriteArrayList<String>()
    val hostEvents = CopyOnWriteArrayList<NativeHostEvent>()
    fun config() = NativeAdConfig("ca-app-pub-3940256099942544/2247696110", true, refresh,
        com.ads.module.R.layout.custom_native_admob_medium)
}
