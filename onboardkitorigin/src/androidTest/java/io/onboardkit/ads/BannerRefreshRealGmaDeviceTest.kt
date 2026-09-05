package io.onboardkit.ads

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.banner.AdBannerState
import com.ads.module.helper.banner.BannerAdConfig
import com.ads.module.helper.banner.BannerAdHelper
import com.ads.module.helper.banner.BannerAdParam
import com.ads.module.helper.banner.BannerType
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One real Google banner flow. No loader, AdView, callback, clock or request is faked. */
@RunWith(AndroidJUnit4::class)
class BannerRefreshRealGmaDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun adMobOwnerKeepsItsViewAndExplicitSdkReloadReplacesItWithAnOrdinaryBanner() {
        val initialized = CountDownLatch(1)
        val helpers = mutableListOf<BannerAdHelper>()
        instrumentation.runOnMainSync {
            ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
            Entitlement.install(object : EntitlementSource {
                override fun isPremium(context: Context) = false
            })
            ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
                setFacebookClientToken("123456789")
                setIdAdResume("")
            })
            AppOpenManager.getInstance().disableAppResume()
            MobileAds.initialize(app) { initialized.countDown() }
        }
        try {
            assertTrue("Real GMA initialization must finish", initialized.await(45, TimeUnit.SECONDS))
            ActivityScenario.launch<InterstitialRealGmaDeviceActivity>(
                Intent(app, InterstitialRealGmaDeviceActivity::class.java),
            ).use { scenario ->
                lateinit var activity: InterstitialRealGmaDeviceActivity
                lateinit var slot: FrameLayout
                scenario.onActivity {
                    activity = it
                    val page = FrameLayout(it)
                    page.addView(TextView(it).apply {
                        gravity = Gravity.CENTER
                        text = "BAN-04/05 real banner\nKeep this screen visible during the 65-second check."
                    }, FrameLayout.LayoutParams(-1, -1))
                    slot = FrameLayout(it)
                    page.addView(slot, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                    it.setContentView(page)
                }
                eventually("Host and process resumed before load") {
                    onMain {
                        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    }
                }
                val consoleEvents = BannerEvents()
                val consoleHelper = onMain {
                    BannerAdHelper(activity, activity,
                        BannerAdConfig(TEST_UNIT, true, false, BannerType.Collapsible()))
                        .attachInto(slot).also {
                            helpers += it
                            it.registerAdListener(consoleEvents)
                            it.requestAds(BannerAdParam.Request)
                        }
                }
                awaitFilled(consoleHelper, consoleEvents)
                val original = onMain { onlyAd(slot) }
                eventually("First real creative has visible dimensions") { onMain { visible(original) } }
                Log.i(TAG, "AD_MOB_OWNER_FILLED view=${System.identityHashCode(original)} collapsible=${onMain { original.isCollapsible }}")
                screenshot("ban04-admob-owner-initial.png")
                repeat(13) { tick ->
                    SystemClock.sleep(5_000)
                    onMain {
                        assertSame("No SDK replacement during 65 seconds", original, onlyAd(slot))
                        assertTrue("Banner stays attached and visible", visible(original))
                    }
                    if ((tick + 1) % 3 == 0) Log.i(TAG, "SAME_AD_VIEW elapsed_seconds=${(tick + 1) * 5}")
                }
                assertTrue("Android must accept Home", instrumentation.uiAutomation.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_HOME,
                ))
                eventually("Host actually stopped after Home") {
                    onMain { !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                }
                instrumentation.runOnMainSync {
                    app.getSystemService(ActivityManager::class.java).appTasks
                        .single { it.taskInfo.taskId == activity.taskId }.moveToFront()
                }
                eventually("Return the same host to resumed") {
                    onMain { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                }
                SystemClock.sleep(1_000) // Pass the existing500ms resume debounce.
                onMain {
                    assertSame(original, onlyAd(slot))
                    assertTrue(visible(original))
                    consoleHelper.requestAds(BannerAdParam.Reload)
                    assertSame("canReloadAds=false gates an explicit Reload too", original, onlyAd(slot))
                }
                Log.i(TAG, "AD_MOB_OWNER_65S_AND_HOME same_view=true loaded_callbacks=${consoleEvents.loaded.size}")
                screenshot("ban04-admob-owner-after-home.png")

                // Keep all existing SDK-owner switches, with a long interval so this short
                // explicit-reload phase cannot accidentally wait for the automatic timer.
                val sdkEvents = BannerEvents()
                val sdkHelper = onMain {
                    consoleHelper.flagUserEnableReload = false
                    consoleHelper.cancel()
                    val config = BannerAdConfig(TEST_UNIT, true, true, BannerType.Collapsible()).apply {
                        enableAutoReload = true
                        autoReloadTime = 120_000
                    }
                    BannerAdHelper(activity, activity, config).attachInto(slot).also {
                        helpers += it
                        it.registerAdListener(sdkEvents)
                        it.requestAds(BannerAdParam.Request)
                    }
                }
                awaitFilled(sdkHelper, sdkEvents)
                val beforeReload = onMain { onlyAd(slot) }
                eventually("SDK-owner initial creative is visible") { onMain { visible(beforeReload) } }
                onMain { sdkHelper.requestAds(BannerAdParam.Reload) }
                eventually("Explicit Reload must fill a new live AdView; errors=${sdkEvents.errors}", 45_000) {
                    onMain {
                        sdkHelper.bannerAdState.value is AdBannerState.Loaded &&
                            ads(slot).singleOrNull()?.let { it !== beforeReload && visible(it) } == true
                    }
                }
                val replacement = onMain { onlyAd(slot) }
                assertNotSame(beforeReload, replacement)
                assertFalse("Reload does not ask for collapsible", onMain { replacement.isCollapsible })
                assertTrue("Old view removed only after successful replacement", onMain { beforeReload.parent == null })
                assertEquals(1, onMain { ads(slot).size })
                SystemClock.sleep(500)
                screenshot("ban04-sdk-explicit-reload-ordinary.png")
                Log.i(TAG, "SDK_EXPLICIT_RELOAD old=${System.identityHashCode(beforeReload)} new=${System.identityHashCode(replacement)} ordinary=true visible=true")
            }
        } finally {
            instrumentation.runOnMainSync {
                helpers.forEach { it.flagUserEnableReload = false; it.cancel() }
                ConsentCenter.clearHostConsent()
            }
        }
    }

    private fun awaitFilled(helper: BannerAdHelper, events: BannerEvents) {
        eventually("Real test banner fill required; failures are fixture/network outcomes: ${events.errors}", 45_000) {
            onMain { helper.bannerAdState.value is AdBannerState.Loaded }
        }
    }

    private fun ads(slot: FrameLayout): List<AdView> {
        val container = slot.findViewById<FrameLayout>(com.ads.module.R.id.banner_container)
        return (0 until container.childCount).mapNotNull { container.getChildAt(it) as? AdView }
    }
    private fun onlyAd(slot: FrameLayout): AdView = ads(slot).single()
    private fun visible(view: View): Boolean = view.isShown && view.width > 0 && view.height > 0

    private fun screenshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(app.cacheDir, name)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        Log.i(TAG, "SCREENSHOT ${file.absolutePath}")
    }

    private fun <T : Any> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        return checkNotNull(value)
    }
    private fun eventually(message: String, timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, predicate())
    }
    private class BannerEvents : AdCallback() {
        val loaded = CopyOnWriteArrayList<Long>()
        val errors = CopyOnWriteArrayList<String>()
        override fun onAdLoaded() { loaded += SystemClock.elapsedRealtime() }
        override fun onAdFailedToLoad(error: LoadAdError?) { errors += error?.toString() ?: "No fill" }
    }
    companion object {
        private const val TAG = "BAN04_DEVICE"
        // Google's official Android banner test unit; not a partner monetized unit.
        private const val TEST_UNIT = "ca-app-pub-3940256099942544/9214589741"
    }
}
