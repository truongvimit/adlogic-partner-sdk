package io.retentionkit

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.ads.module.admob.AppOpenManager
import com.ads.module.admob.ResumeSkipPolicy
import com.ads.module.consent.ConsentCenter
import com.ads.module.event.ERainLogEventManager
import io.onboardkit.OnboardingSdk
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.core.SkipReason
import io.onboardkit.ui.splash.ObSplashActivity
import io.retentionkit.core.*
import io.retentionkit.feedback.RetentionFeedbackActivity
import io.retentionkit.integration.OnboardRetentionBridge
import io.retentionkit.integration.RetentionEntryAdPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardBridgeTest {
    abstract class Splash : ObSplashActivity()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ads get() = AppOpenManager.getInstance()
    @After fun after() { RetentionRuntime.uninstallForTests(); ConsentCenter.clearHostConsent(); shadowOf(Looper.getMainLooper()).idle() }
    private fun fixture(): Pair<RetentionRuntime, OnboardRetentionBridge> {
        val bridge = OnboardRetentionBridge(Splash::class.java)
        val result = RetentionRuntime.install(app, RetentionOptions(modules = listOf(bridge), uiHost = bridge,
            router = bridge.router, store = SharedPreferencesRetentionStore(app, "bridge_${UUID.randomUUID()}"))) as RetentionInstallResult.Installed
        return result.runtime to bridge
    }
    private fun foreground(runtime: RetentionRuntime): Activity {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        runtime.signal(RetentionSignal.ProcessForeground)
        ads.onActivityStarted(activity)
        return activity
    }

    @Test fun bothProcessObserverOrdersPreserveReturnForOpenAndWelcomeReaders() {
        listOf(true, false).forEach { coreFirst ->
            RetentionRuntime.uninstallForTests()
            val (runtime, _) = fixture()
            val activity = foreground(runtime)
            val token = "test-${UUID.randomUUID()}"
            runtime.signal(RetentionSignal.ExternalTransitionStarted(token, "widget_pin", 5000))
            runtime.signal(RetentionSignal.ProcessBackground)
            ads.onStop()
            runtime.subscribe("finish") { if (it == RetentionSignal.ProcessForeground) runtime.signal(RetentionSignal.ExternalTransitionFinished(token)) }
            val owner = object : LifecycleOwner {
                val registry = LifecycleRegistry(this)
                override val lifecycle: Lifecycle get() = registry
            }
            val coreObserver = object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { runtime.signal(RetentionSignal.ProcessForeground) }
            }
            if (coreFirst) { owner.registry.addObserver(coreObserver); owner.registry.addObserver(ads) }
            else { owner.registry.addObserver(ads); owner.registry.addObserver(coreObserver) }
            var welcomeReason: String? = null
            owner.registry.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { welcomeReason = ads.resumeSkipReasonFor(activity) }
            })
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            // OPEN and later WELCOME both read this shared gate in the same lifecycle dispatch.
            assertEquals("retention_widget_pin", ads.resumeSkipReasonFor(activity))
            assertEquals("retention_widget_pin", welcomeReason)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotEquals("retention_widget_pin", ads.resumeSkipReasonFor(activity))
        }
    }

    @Test fun failedHandoffWithoutDepartureCancelsOnlyItsHandleBeforeFutureReturn() {
        val (runtime, bridge) = fixture()
        val activity = foreground(runtime)
        val foreign = ads.suppressResume("other", "other_owner", 5000)
        val operation = bridge.beginExternal("permission", 5000)
        operation.close(); operation.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("other_owner", ads.resumeSkipReasonFor(activity))
        foreign.close()
        runtime.signal(RetentionSignal.ProcessBackground)
        ads.onStop()
        ads.onResume()
        assertNotEquals("retention_permission", ads.resumeSkipReasonFor(activity))
    }

    @Test fun synchronousAdBusyGateRejectsAndStaleLeaseResourceDoesNotCloseNewOwner() {
        val (runtime, _) = fixture()
        val activity = foreground(runtime)
        ads.setInterstitialShowing(true)
        assertTrue(runtime.ui.acquire("review") is RetentionUiLeaseResult.Blocked)
        ads.setInterstitialShowing(false)
        val lease = (runtime.ui.acquire("review") as RetentionUiLeaseResult.Acquired).lease
        assertEquals("retention_sdk_ui", ads.resumeSkipReasonFor(activity))
        ads.setInterstitialShowing(true)
        assertNull(lease.activity())
        ads.setInterstitialShowing(false)
        val next = (runtime.ui.acquire("widget") as RetentionUiLeaseResult.Acquired).lease
        lease.close()
        assertEquals("retention_sdk_ui", ads.resumeSkipReasonFor(activity))
        next.close()
        assertNotEquals("retention_sdk_ui", ads.resumeSkipReasonFor(activity))
    }

    @Test fun routeAndTerminalPassthroughPreserveTypedEnvelopeWithoutReplacingHostPolicy() {
        ConsentCenter.setHostConsent(true, false)
        val (runtime, bridge) = fixture()
        val activity = foreground(runtime)
        ads.setResumeSkipPolicy(object : ResumeSkipPolicy {
            override fun skipReasonFor(activity: Activity) = "host_policy"
        })
        val original = RetentionEntry(RetentionEntrySource.WIDGET, "notes", "notes", mode = RetentionEntryMode.REUSABLE)
        val intent = requireNotNull(runtime.createEntryIntent(original))
        assertFalse(intent.getBooleanExtra("ob_without_splash_ads", false))
        val accepted = bridge.capture(intent) as RetentionEntryAcceptance.Accepted
        val extras = bridge.onOutcome(OnboardingOutcome.Skipped(SkipReason.ALREADY_COMPLETED, intent.extras))
        val finalIntent = Intent().putExtras(requireNotNull(extras))
        assertEquals(accepted.entry, (RetentionEntryCodec.read(finalIntent) as RetentionEntryDecodeResult.Valid).entry)
        assertEquals("host_policy", ads.resumeSkipReasonFor(activity))
        assertTrue(runtime.userState.setupCompleted)
        val sdkActivity = Robolectric.buildActivity(RetentionFeedbackActivity::class.java).get()
        assertTrue(ads.isResumeSuppressedFor(sdkActivity))
        ads.setResumeSkipPolicy(null)
    }

    @Test fun actualVendorClickIsForwardedOnceAndDetachedBridgeCannotReplayOrForward() {
        val (runtime, bridge) = fixture()
        foreground(runtime)
        val clickIds = mutableListOf<String>()
        runtime.subscribe("click-test") { if (it is RetentionSignal.AdClicked) clickIds.add(it.clickId) }
        ERainLogEventManager.logClickAdsEvent(app, "unit")
        assertEquals(1, clickIds.size)
        bridge.shutdown()
        ERainLogEventManager.logClickAdsEvent(app, "unit")
        assertEquals(1, clickIds.size)
    }
    class Main : Activity()
    @Test fun explicitNoAdPolicyRemainsOptInAndAllSourcesKeepEntryPlacement() {
        val standard = OnboardRetentionBridge(Splash::class.java) { true } // Legacy trailing-lambda source compatibility.
        val noAds = OnboardRetentionBridge(Splash::class.java, entryAdPolicy = RetentionEntryAdPolicy.WITHOUT_SPLASH_ADS)
        for ((source, key) in listOf(RetentionEntrySource.DAILY to "inter_noti", RetentionEntrySource.WIDGET to "inter_widget",
            RetentionEntrySource.SHORTCUT to "inter_widget", RetentionEntrySource.FEEDBACK to "inter_uninstall")) {
            val entry = RetentionEntry(source, "notes", "open")
            val normal = standard.router.createIntent(app, entry)!!
            val explicitSkip = noAds.router.createIntent(app, entry)!!
            assertFalse(normal.getBooleanExtra("ob_without_splash_ads", false))
            assertTrue(explicitSkip.getBooleanExtra("ob_without_splash_ads", false))
            assertEquals(key, io.onboardkit.ui.splash.SplashEntry.from(normal)?.interKey)
            assertTrue(normal.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        }
        val uninstallShortcut = RetentionEntry(RetentionEntrySource.SHORTCUT, io.retentionkit.feedback.RetentionFeedbackModule.DESTINATION, "open")
        assertEquals("inter_uninstall", io.onboardkit.ui.splash.SplashEntry.from(standard.router.createIntent(app, uninstallShortcut))?.interKey)
    }

    @Test fun configuredMainAllowsOnlySelectedEntryAndHonorsHostModalGateAtFinalRecheck() {
        var ready = true
        val bridge = OnboardRetentionBridge(Splash::class.java, hostCanPresent = { ready }, mainActivity = Main::class.java)
        val rt = (RetentionRuntime.install(app, RetentionOptions(modules = listOf(bridge), uiHost = bridge,
            store = SharedPreferencesRetentionStore(app, "main_bridge_${UUID.randomUUID()}"))) as RetentionInstallResult.Installed).runtime
        val activity = Robolectric.buildActivity(Main::class.java).setup()
        rt.signal(RetentionSignal.ProcessForeground)
        assertTrue(rt.ui.acquire("review") is RetentionUiLeaseResult.Blocked)
        assertTrue(rt.ui.acquire("entry", 5000, RetentionUiPurpose.ENTRY) is RetentionUiLeaseResult.Blocked)
        val entry = RetentionEntry(RetentionEntrySource.FEEDBACK, "notes", "rescue")
        rt.entries.capture(RetentionEntryCodec.write(activity.get().intent, entry))
        val lease = (rt.ui.acquire("entry", 5000, RetentionUiPurpose.ENTRY) as RetentionUiLeaseResult.Acquired).lease
        ready = false
        assertNull(lease.activity())
        ready = true
        val terminal = bridge.mainIntent(app, Main::class.java, OnboardingOutcome.Skipped(SkipReason.ALREADY_COMPLETED, activity.get().intent.extras))!!
        assertEquals(entry, (RetentionEntryCodec.read(terminal) as RetentionEntryDecodeResult.Valid).entry)
        assertNull(bridge.mainIntent(app, Main::class.java, OnboardingOutcome.Aborted(null)))
        rt.entries.consume(entry.token)
        assertFalse(bridge.canPresentEntry(activity.get()))
        activity.pause().stop().destroy()
    }

}
