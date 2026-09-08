package io.onboardkit.ads

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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

    @Test fun realNativeOwnershipSurvivesRecreationAndConsumesEachPresentation() {
        f.refresh = InstrumentationRegistry.getArguments().getString("nativeRefresh") == "true"
        val initialized = CountDownLatch(1)
        onMain {
            ConsentCenter.setHostConsent(true, false)
            Entitlement.install(object : EntitlementSource { override fun isPremium(context: Context) = false })
            ERainAd.getInstance().init(app, ERainAdConfig(app).apply { setFacebookClientToken("123456789"); setIdAdResume("") })
            AppOpenManager.getInstance().disableAppResume()
            MobileAds.initialize(app) { initialized.countDown() }
            NativeAdPreload.getInstance().registerAdCallback(f.key, object : AdCallback() {
                override fun onNativeAdLoaded(nativeAd: ApNativeAd) { f.fills += nativeAd }
                override fun onAdFailedToLoad(error: LoadAdError?) { f.errors += error.toString() }
            })
        }
        assertTrue("GMA initialization", initialized.await(45, TimeUnit.SECONDS))
        try {
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
                    eventually("Rotation must rebind the same consumed native") { f.binds.size > before }
                    assertSame(first, f.binds.last())
                    assertEquals("Rotation must not load another ad", 1, f.fills.size)
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
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                eventually("Actual Home stops native host") { scenario.state == Lifecycle.State.CREATED }
                assertFalse("A shown ad is disposed on real departure", onMain { departing.isUsable })
                SystemClock.sleep(if (f.refresh) 3_500 else 300)
                assertEquals("No refresh loop while Home", fillsBeforeHome, f.fills.size)
                onMain { app.getSystemService(ActivityManager::class.java).appTasks.single { it.taskInfo.taskId == host.taskId }.moveToFront() }
                eventually("Returning starts a new presentation: ${f.errors}", 40_000) { f.binds.last() !== departing }
                assertTrue(onMain { f.binds.last().isUsable })
                assertFalse(onMain { NativeAdManager.isReady(f.key) })
                assertTrue("No vendor errors: ${f.errors}", f.errors.isEmpty())
            }
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
            override fun onNativeAdLoaded(nativeAd: ApNativeAd) { NativeDeviceFixture.binds += nativeAd }
            override fun onAdFailedToLoad(error: LoadAdError?) { NativeDeviceFixture.errors += error.toString() }
        })
    }
}

private object NativeDeviceFixture {
    const val key = "device_native_ownership"
    var refresh = false
    val fills = CopyOnWriteArrayList<ApNativeAd>()
    val binds = CopyOnWriteArrayList<ApNativeAd>()
    val errors = CopyOnWriteArrayList<String>()
    fun config() = NativeAdConfig("ca-app-pub-3940256099942544/2247696110", true, refresh,
        com.ads.module.R.layout.custom_native_admob_medium)
}
