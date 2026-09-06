package io.onboardkit.ui.splash

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
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
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.AdLoadStrategy
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.SplashConfig
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.AnalyticsPlugin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Run notificationPhase=allow|deny|recreate|off|granted|home_after_result with fresh test-APK data.
 * Phase handled instead uses a new process after deny, retaining that test APK's data and denial.
 * Root prepares permission externally; do not click until NOTIFICATION_DEVICE says ANSWER_NOW.
 * This proves Android permission/lifecycle ordering, not GMA display or a cross-process retry policy.
 */
@RunWith(AndroidJUnit4::class)
class SplashNotificationPermissionDeviceTest {
    @Test
    fun actualNotificationResultGatesSplashAfterConsent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val phase = InstrumentationRegistry.getArguments().getString("notificationPhase") ?: "deny"
        require(phase in setOf("allow", "deny", "recreate", "off", "granted", "home_after_result", "handled"))
        assumeTrue(Build.VERSION.SDK_INT >= 33 && app.applicationInfo.targetSdkVersion >= 33)
        val granted = app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        assertEquals("Prepare only this test APK's permission before running phase=$phase", phase == "granted", granted)
        val fixture = NotificationFixture
        fixture.requiresResult = phase !in setOf("off", "granted", "handled")
        fixture.holdInterstitial = phase == "home_after_result"
        instrumentation.runOnMainSync {
            assertFalse("Fresh instrumentation process required", OnboardingSdk.isReady())
            ConsentCenter.setHostConsent(false, false)
            OnboardingSdk.install(app) {
                adProvider = fixture.provider
                trackkitAutoTracking(false)
                analyticsPlugin(AnalyticsPlugin {
                    if (it is AnalyticsEvent.SplashCompleted) fixture.splashCompleted.incrementAndGet()
                })
                listener = OnboardingListener { _, _ ->
                    if (fixture.requiresResult && fixture.results.get() == 0) fixture.violations += "navigation before permission callback"
                    fixture.completions.incrementAndGet()
                }
            }
            OnboardingSdk.configure(onboardKitConfig {
                splash = SplashConfig(minDisplayTimeMs = 2_000, remoteFetchTimeoutMs = 100,
                    consentTimeoutMs = 20_000, adLoadStrategy = AdLoadStrategy.SAME_TIME,
                    noInternetPromptEnabled = false, notificationPermissionEnabled = phase != "off")
                ads = AdsConfig(splashBanner = BannerAdUnit("notification-host-banner"),
                    splashInterstitial = InterstitialAdUnit("notification-host-interstitial"))
            }.getOrThrow()).getOrThrow()
            OnboardingSdk.setCanRequestAds(true)
        }
        runBlocking { OnboardingSdk.reset(); OnboardingSdk.markCompleted() }
        try {
            ActivityScenario.launch<NotificationSplashDeviceActivity>(
                Intent(app, NotificationSplashDeviceActivity::class.java),
            ).use { scenario ->
                assertTrue("The actual host consent hook must be entered", fixture.consentEntered.await(10, TimeUnit.SECONDS))
                hold(3_500) {
                    assertFalse("Notification UI must not precede the actual consent hook result", permissionDialogVisible())
                    assertEquals(0, fixture.completions.get())
                    assertEquals(0, fixture.shows.get())
                    assertEquals(0, fixture.loads.get())
                }
                mark("CONSENT_RELEASE phase=$phase")
                instrumentation.runOnMainSync { fixture.consent.complete(Unit) }
                if (fixture.requiresResult) {
                    eventually("Expected the actual Android notification permission dialog") { permissionDialogVisible() }
                    assertEquals(0, fixture.results.get())
                    hold(4_500) { assertHeld() } // Beyond both configured 2s and default remote 3s minimum.
                    assertTrue("Ad loading may proceed while permission UI is pending", fixture.loads.get() >= 2)
                    if (phase == "recreate") {
                        val before = fixture.creates.get()
                        mark("RECREATE_WITH_REAL_PERMISSION_PENDING")
                        // ActivityScenario.recreate() first forces RESUMED, which cannot happen
                        // under Android's permission UI. onActivity has no such state transition;
                        // call the real Activity API on main while the host is still paused.
                        scenario.onActivity { it.recreate() }
                        eventually("Android must actually create the replacement Activity") {
                            fixture.creates.get() > before
                        }
                        assertEquals(before + 1, fixture.creates.get())
                        mark("ACTUAL_RECREATED creates=${fixture.creates.get()} permissionCallbacks=${fixture.results.get()}")
                        // Android may cancel a pending request during recreation. An actual empty
                        // result is a valid completion; it must not require a second permission UI.
                        if (fixture.results.get() == 0) hold(1_000) { assertHeld() }
                    }
                    mark("ANSWER_NOW phase=$phase; use the real Android ${if (phase == "deny") "Don't allow" else "Allow"} button")
                    eventually("Waiting for actual onRequestPermissionsResult; operator must answer real UI", 120_000) {
                        fixture.results.get() == 1
                    }
                }
                if (phase == "home_after_result") {
                    lateinit var host: NotificationSplashDeviceActivity
                    scenario.onActivity { host = it }
                    eventually("Permission result must first return focus to the resumed splash") {
                        var focused = false
                        instrumentation.runOnMainSync {
                            focused = host.lifecycle.currentState == Lifecycle.State.RESUMED && host.hasWindowFocus()
                        }
                        focused
                    }
                    assertEquals(0, fixture.splashCompleted.get())
                    mark("HOME_AFTER_RESULT loadStillPending=true")
                    assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                    eventually("Actual Home must stop the splash") { scenario.state == Lifecycle.State.CREATED }
                    instrumentation.runOnMainSync {
                        fixture.interstitialReady = true
                        requireNotNull(fixture.pendingInterstitial).onLoaded()
                        fixture.pendingInterstitial = null
                    }
                    hold(2_000) {
                        assertEquals("Do not enter proceed while the permission-owning splash is in Home", 0, fixture.splashCompleted.get())
                        assertEquals(0, fixture.shows.get())
                        assertEquals(0, fixture.completions.get())
                    }
                    instrumentation.runOnMainSync {
                        app.getSystemService(ActivityManager::class.java).appTasks
                            .single { it.taskInfo.taskId == host.taskId }.moveToFront()
                    }
                }
                eventually("Splash must complete once after permission result/focus returns", 15_000) {
                    if (phase == "handled") {
                        assertFalse("A prior handled denial must not prompt again in a new process", permissionDialogVisible())
                        assertEquals("No new permission request after the handled denial", 0, fixture.results.get())
                    }
                    fixture.completions.get() == 1
                }
                assertTrue(fixture.violations.toString(), fixture.violations.isEmpty())
                assertEquals(1, fixture.shows.get())
                if (phase == "home_after_result") assertEquals(1, fixture.splashCompleted.get())
                assertEquals(if (fixture.requiresResult) 1 else 0, fixture.results.get())
                hold(1_500) {
                    assertEquals("No duplicate navigation after recreation/result", 1, fixture.completions.get())
                    assertEquals(1, fixture.shows.get())
                    assertFalse("No second permission dialog after completion", permissionDialogVisible())
                }
                if (phase == "allow" || phase == "granted") assertTrue(fixture.permissionGranted(app))
                if (phase in setOf("deny", "off", "handled")) assertFalse(fixture.permissionGranted(app))
                mark("COMPLETE phase=$phase callbacks=${fixture.results.get()} shows=${fixture.shows.get()} navigation=1")
            }
        } finally {
            instrumentation.runOnMainSync { ConsentCenter.clearHostConsent() }
        }
    }

    private fun assertHeld() {
        assertEquals("An unanswered real permission request must still be pending", 0, NotificationFixture.results.get())
        assertEquals("No provider show under permission UI", 0, NotificationFixture.shows.get())
        assertEquals("No navigation under permission UI", 0, NotificationFixture.completions.get())
    }

    private fun permissionDialogVisible(): Boolean {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return false
        return try {
            val pkg = root.packageName?.toString().orEmpty()
            pkg.contains("permissioncontroller") &&
                setOf(pkg, "com.android.permissioncontroller", "com.google.android.permissioncontroller").any {
                    root.findAccessibilityNodeInfosByViewId("$it:id/permission_allow_button").isNotEmpty()
                }
        } finally { root.recycle() }
    }

    private fun hold(ms: Long, check: () -> Unit) {
        val until = SystemClock.elapsedRealtime() + ms
        do { check(); SystemClock.sleep(100) } while (SystemClock.elapsedRealtime() < until)
    }
    private fun eventually(message: String, ms: Long = 10_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + ms
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertTrue(message, condition())
    }
    private fun mark(message: String) = Log.i("NOTIFICATION_DEVICE", message)
}

/** Supported host hooks and the actual Android callback, without replacing ActivityResultRegistry. */
class NotificationSplashDeviceActivity : ObSplashActivity() {
    override fun onCreateSafe(savedInstanceState: Bundle?) {
        NotificationFixture.creates.incrementAndGet()
        super.onCreateSafe(savedInstanceState)
    }
    override suspend fun onConsentRequired(): Boolean {
        NotificationFixture.consentEntered.countDown()
        NotificationFixture.consent.await()
        ConsentCenter.setHostConsent(true, false)
        return true
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        // Capture before super dispatches the real result to ActivityResultRegistry's callback.
        // Empty permissions after Android cancellation also belongs to this fixture's sole request.
        if (Manifest.permission.POST_NOTIFICATIONS in permissions || permissions.isEmpty()) {
            NotificationFixture.results.incrementAndGet()
            Log.i("NOTIFICATION_DEVICE", "ACTUAL_RESULT permissions=${permissions.toList()} grants=${grants.toList()}")
        }
        super.onRequestPermissionsResult(code, permissions, grants)
    }
}

private object NotificationFixture {
    var requiresResult = true
    var holdInterstitial = false
    var interstitialReady = false
    var pendingInterstitial: AdEventListener? = null
    val consent = CompletableDeferred<Unit>()
    val consentEntered = CountDownLatch(1)
    val creates = AtomicInteger()
    val results = AtomicInteger()
    val loads = AtomicInteger()
    val shows = AtomicInteger()
    val completions = AtomicInteger()
    val splashCompleted = AtomicInteger()
    val violations = CopyOnWriteArrayList<String>()
    fun permissionGranted(context: Context) = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val provider = object : OnboardingAdProvider {
        override fun isPremium(context: Context) = false
        override fun preloadNative(activity: Activity, request: NativeAdRequest) = Unit
        override fun isNativeReady(placement: AdPlacement) = false
        override fun isNativeLoading(placement: AdPlacement) = false
        override fun bindNative(activity: Activity, placement: AdPlacement, container: ViewGroup, shimmer: View?, listener: AdEventListener?) = false
        override fun releaseNative(placement: AdPlacement) = Unit
        override fun loadInterstitial(context: Context, placement: AdPlacement, unit: InterstitialAdUnit, listener: AdEventListener?) {
            loads.incrementAndGet()
            if (holdInterstitial) pendingInterstitial = listener else listener?.onLoaded()
        }
        override fun isInterstitialReady(placement: AdPlacement) = !holdInterstitial || interstitialReady
        override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) {
            shows.incrementAndGet()
            if (requiresResult && results.get() == 0) violations += "provider show before permission callback"
            if (activity !is NotificationSplashDeviceActivity || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !activity.hasWindowFocus()) {
                violations += "provider show before resumed host window focus"
            }
            // Host-provider seam only: settle without pretending a GMA ad was displayed.
            callback.onAdSkipped(AdSkipReason.NOT_READY)
        }
        override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) {
            loads.incrementAndGet(); listener?.onFailedToLoad()
        }
        override fun suppressAppResume(activityClass: Class<out Activity>) = Unit
        override fun releaseAll() = Unit
    }
}
