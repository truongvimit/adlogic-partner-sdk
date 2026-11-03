package io.onboardkit.ads

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ads.module.admob.AppOpenManager
import com.ads.module.admob.ResumeAds
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.bytedance.sdk.openadsdk.fake.LandingPageDeviceActivity
import com.facebook.ads.fake.TranslucentAdDeviceActivity
import io.onboardkit.ui.pool.PoolCoverDeviceActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** An opaque screen of the app itself, opened over the host. */
class NativeResumeOwnScreenDeviceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "The app's own screen" })
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var current: NativeResumeOwnScreenDeviceActivity? = null
    }
}

/**
 * Native resume trips on a real device, through the Activity callbacks the OS delivers. The screen
 * is a recorder, so no ad is requested or shown. Run on an unlocked device.
 */
@RunWith(AndroidJUnit4::class)
class NativeResumeTripDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = AppOpenManager.getInstance()
    private val calls = CopyOnWriteArrayList<String>()
    private val processStopped = AtomicBoolean(false)
    private val processWatch = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) = processStopped.set(true)
    }
    private val screen = object : ResumeAds.NativeScreen {
        override fun prepare(context: Context) {
            calls += "prepare"
            mark("prepare")
        }

        override fun show(host: Activity) = show(host, false)

        override fun show(host: Activity, afterAdClick: Boolean) {
            calls += if (afterAdClick) "show:click" else "show"
            mark("show afterAdClick=$afterAdClick host=${host.javaClass.simpleName}")
        }
    }

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            if (!manager.isInitialized) manager.init(app, "")
            AdBehavior.document.acceptSuccessfulFetch("""{"app_resume":{"type":"native_resume"}}""")
            AdRemoteConfig.initializeFromJson("""{"native_resume":{"ids":[{"id":"native-unit"}],"isEnable":true}}""")
            manager.enableAppResume()
            manager.setResumeSkipPolicy(null)
            manager.setDisableAdResumeByClickAction(false)
            manager.setInterstitialShowing(false)
            ConsentCenter.setHostConsent(true, false)
            ResumeAds.nativeScreen = screen
            ProcessLifecycleOwner.get().lifecycle.addObserver(processWatch)
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(processWatch)
            ResumeAds.nativeScreen = null
            manager.disableAppResume()
            AdBehavior.document.acceptSuccessfulFetch(null)
            ConsentCenter.clearHostConsent()
        }
    }

    @Test
    fun aReturnFasterThanTheProcessStopOpensNativeResume() = withHost { scenario, host ->
        home()
        eventually("Home stops the host") { scenario.state == Lifecycle.State.CREATED }
        assertEquals("Requested the moment the host stopped", listOf("prepare"), calls.toList())
        processStopped.set(false)
        bringToFront(host)
        eventually("Back in front") { scenario.state == Lifecycle.State.RESUMED }
        instrumentation.waitForIdleSync()
        assertEquals(listOf("prepare", "show"), calls.toList())
        mark("fast return processStopped=${processStopped.get()}")
        assertFalse("The process lifecycle never saw this trip", processStopped.get())
    }

    @Test
    fun aSlowReturnOpensNativeResumeOnce() = withHost { scenario, host ->
        home()
        eventually("The process stops too") { processState() == Lifecycle.State.CREATED }
        bringToFront(host)
        eventually("Back in front") { scenario.state == Lifecycle.State.RESUMED }
        instrumentation.waitForIdleSync()
        assertEquals(listOf("prepare", "show"), calls.toList())
    }

    @Test
    fun aNativeAdsLandingPageInsideTheProcessOpensNativeResumeOnTheWayBack() = withHost { scenario, host ->
        instrumentation.runOnMainSync { ResumeAds.noteNativeClick() }
        host.startActivity(Intent(host, LandingPageDeviceActivity::class.java))
        eventually("The landing page covers the host") { scenario.state == Lifecycle.State.CREATED }
        assertEquals(listOf("prepare"), calls.toList())
        assertEquals("No process stop for an in-process page", Lifecycle.State.RESUMED, processState())
        finish { LandingPageDeviceActivity.current }
        eventually("Back on the host") { scenario.state == Lifecycle.State.RESUMED }
        mark("HOST_RESUMED")
        eventually("Opened once the page is gone") { calls.toList() == listOf("prepare", "show:click") }
    }

    @Test
    fun anAdLandingPageWithoutANativeClickOpensNothing() = withHost { scenario, _ ->
        // A banner's or an interstitial's page: the generic click mark only.
        instrumentation.runOnMainSync { manager.noteAdClick() }
        scenario.onActivity { it.startActivity(Intent(it, LandingPageDeviceActivity::class.java)) }
        eventually("The landing page covers the host") { scenario.state == Lifecycle.State.CREATED }
        finish { LandingPageDeviceActivity.current }
        eventually("Back on the host") { scenario.state == Lifecycle.State.RESUMED }
        instrumentation.waitForIdleSync()
        assertEquals(emptyList<String>(), calls.toList())
    }

    @Test
    fun homeFromTheLandingPageWaitsForTheHost() = withHost { scenario, host ->
        instrumentation.runOnMainSync { ResumeAds.noteNativeClick() }
        host.startActivity(Intent(host, LandingPageDeviceActivity::class.java))
        eventually("The landing page covers the host") { scenario.state == Lifecycle.State.CREATED }
        home()
        eventually("The process stops") { processState() == Lifecycle.State.CREATED }
        bringToFront(host)
        eventually("The landing page is back") { processState() == Lifecycle.State.RESUMED }
        instrumentation.waitForIdleSync()
        assertEquals("Never over the landing page", listOf("prepare"), calls.toList())
        finish { LandingPageDeviceActivity.current }
        eventually("Back on the host") { scenario.state == Lifecycle.State.RESUMED }
        mark("HOST_RESUMED")
        eventually("Opened once the page is gone") { calls.toList() == listOf("prepare", "show:click") }
    }

    @Test
    fun aSeeThroughAdPageAfterANativeClickOpensNativeResumeOnlyOnceItHasClosed() = withHost { scenario, host ->
        instrumentation.runOnMainSync { ResumeAds.noteNativeClick() }
        host.startActivity(Intent(host, TranslucentAdDeviceActivity::class.java))
        eventually("The host pauses") { scenario.state == Lifecycle.State.STARTED }
        eventually("Requested as the ad page comes in front") { calls.toList() == listOf("prepare") }
        assertEquals("The host under a see-through page is not stopped", Lifecycle.State.STARTED, scenario.state)
        finish { TranslucentAdDeviceActivity.current }
        eventually("Back on the host") { scenario.state == Lifecycle.State.RESUMED }
        eventually("Opened once the page is gone") { calls.toList() == listOf("prepare", "show:click") }
        val gone = AtomicBoolean(false)
        instrumentation.runOnMainSync { gone.set(TranslucentAdDeviceActivity.current == null || TranslucentAdDeviceActivity.current!!.isFinishing) }
        assertTrue(gone.get())
    }

    @Test
    fun homeFromASeeThroughAdPageNeverOpensOverIt() = withHost { scenario, host ->
        instrumentation.runOnMainSync { ResumeAds.noteNativeClick() }
        host.startActivity(Intent(host, TranslucentAdDeviceActivity::class.java))
        eventually("The host pauses") { scenario.state == Lifecycle.State.STARTED }
        home()
        eventually("Home stops the host") { scenario.state == Lifecycle.State.CREATED }
        bringToFront(host)
        eventually("The ad page is back in front") { processState() == Lifecycle.State.RESUMED }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        assertEquals("Never over the ad page", listOf("prepare"), calls.toList())
        finish { TranslucentAdDeviceActivity.current }
        eventually("Back on the host") { scenario.state == Lifecycle.State.RESUMED }
        eventually("Opened once the page is gone") { calls.toList() == listOf("prepare", "show:click") }
    }

    @Test
    fun aSeeThroughAdPageWithoutANativeClickOpensNothing() = withHost { scenario, host ->
        host.startActivity(Intent(host, TranslucentAdDeviceActivity::class.java))
        eventually("The host pauses") { scenario.state == Lifecycle.State.STARTED }
        SystemClock.sleep(500)
        finish { TranslucentAdDeviceActivity.current }
        eventually("Back on the host") { scenario.state == Lifecycle.State.RESUMED }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        assertEquals(emptyList<String>(), calls.toList())
    }

    @Test
    fun anOverlayOfTheAppOpensNothing() = withHost { scenario, host ->
        host.startActivity(Intent(host, PoolCoverDeviceActivity::class.java))
        eventually("The host pauses") { scenario.state == Lifecycle.State.STARTED }
        SystemClock.sleep(1_000)
        instrumentation.runOnMainSync { (manager.currentActivity as? PoolCoverDeviceActivity)?.finish() }
        eventually("Back on the host") { scenario.state == Lifecycle.State.RESUMED }
        instrumentation.waitForIdleSync()
        assertEquals(emptyList<String>(), calls.toList())
    }

    @Test
    fun theAppsOwnScreenOverTheHostOpensNothingEvenAfterANativeClick() = withHost { scenario, host ->
        instrumentation.runOnMainSync { ResumeAds.noteNativeClick() }
        host.startActivity(Intent(host, NativeResumeOwnScreenDeviceActivity::class.java))
        eventually("The app's screen covers the host") { scenario.state == Lifecycle.State.CREATED }
        finish { NativeResumeOwnScreenDeviceActivity.current }
        eventually("Back on the host") { scenario.state == Lifecycle.State.RESUMED }
        instrumentation.waitForIdleSync()
        assertEquals(emptyList<String>(), calls.toList())
    }

    @Test
    fun aRotationOpensNothing() = withHost { scenario, _ ->
        val before = AtomicReference<Activity>()
        scenario.onActivity {
            before.set(it)
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        eventually("Recreated") {
            var recreated = false
            scenario.onActivity { recreated = it !== before.get() }
            recreated && scenario.state == Lifecycle.State.RESUMED
        }
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1_000)
        assertEquals(emptyList<String>(), calls.toList())
    }

    @Test
    fun anExcusedTripOpensNothing() = withHost { scenario, host ->
        instrumentation.runOnMainSync { manager.skipNextResume("external_link") }
        home()
        eventually("Home stops the host") { scenario.state == Lifecycle.State.CREATED }
        bringToFront(host)
        eventually("Back in front") { scenario.state == Lifecycle.State.RESUMED }
        instrumentation.waitForIdleSync()
        assertEquals(emptyList<String>(), calls.toList())
    }

    @Test
    fun homeWhileAFullscreenAdIsUpOpensNothing() = withHost { scenario, host ->
        instrumentation.runOnMainSync { manager.setInterstitialShowing(true) }
        home()
        eventually("Home stops the host") { scenario.state == Lifecycle.State.CREATED }
        bringToFront(host)
        eventually("Back in front") { scenario.state == Lifecycle.State.RESUMED }
        instrumentation.runOnMainSync { manager.setInterstitialShowing(false) }
        instrumentation.waitForIdleSync()
        assertEquals(emptyList<String>(), calls.toList())
    }

    private fun withHost(block: (ActivityScenario<AppOpenResumeDeviceActivity>, AppOpenResumeDeviceActivity) -> Unit) {
        ActivityScenario.launch<AppOpenResumeDeviceActivity>(Intent(app, AppOpenResumeDeviceActivity::class.java))
            .use { scenario ->
                eventually("Host resumed") { scenario.state == Lifecycle.State.RESUMED }
                instrumentation.waitForIdleSync()
                calls.clear()
                lateinit var host: AppOpenResumeDeviceActivity
                scenario.onActivity { host = it }
                block(scenario, host)
            }
    }

    private fun home() {
        mark("HOME")
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
    }

    private fun bringToFront(host: Activity) {
        mark("FRONT")
        instrumentation.runOnMainSync {
            app.getSystemService(ActivityManager::class.java).appTasks
                .single { it.taskInfo.taskId == host.taskId }
                .moveToFront()
        }
    }

    private fun finish(activity: () -> Activity?) {
        eventually("The covering Activity exists") { activity() != null }
        instrumentation.runOnMainSync { activity()?.finish() }
    }

    private fun processState(): Lifecycle.State {
        val state = AtomicReference<Lifecycle.State>()
        instrumentation.runOnMainSync { state.set(ProcessLifecycleOwner.get().lifecycle.currentState) }
        return state.get()
    }

    private fun eventually(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue(message, predicate())
    }

    private fun mark(message: String) {
        Log.i("NATIVE_RESUME_TRIP", "pid=${Process.myPid()} elapsed=${SystemClock.elapsedRealtime()} $message")
    }
}
