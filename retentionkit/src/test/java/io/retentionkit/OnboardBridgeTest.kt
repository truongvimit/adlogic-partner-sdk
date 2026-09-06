package io.retentionkit

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.AppOpenManager
import com.ads.module.admob.ResumeSkipPolicy
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.core.SkipReason
import io.onboardkit.ui.splash.ObSplashActivity
import io.retentionkit.core.*
import io.retentionkit.feedback.RetentionFeedbackActivity
import io.retentionkit.integration.OnboardRetentionBridge
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
            if (coreFirst) {
                runtime.signal(RetentionSignal.ProcessForeground)
                ads.onResume()
            } else {
                ads.onResume()
                runtime.signal(RetentionSignal.ProcessForeground)
            }
            // OPEN and later WELCOME both read this shared gate in the same lifecycle dispatch.
            assertEquals("retention_widget_pin", ads.resumeSkipReasonFor(activity))
            assertEquals("retention_widget_pin", ads.resumeSkipReasonFor(activity))
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
        val original = RetentionEntry(RetentionEntrySource.WIDGET, "translate", "translate", mode = RetentionEntryMode.REUSABLE)
        val intent = requireNotNull(runtime.createEntryIntent(original))
        assertTrue(intent.getBooleanExtra("ob_without_splash_ads", false))
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
}
