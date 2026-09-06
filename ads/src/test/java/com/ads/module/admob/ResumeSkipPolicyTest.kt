package com.ads.module.admob

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import com.google.android.gms.ads.AdActivity
import io.trackkit.TrackSink
import io.trackkit.Tracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * Public policy and framework callback contracts on real Android objects. These cases do not
 * substitute AppOpenManager or GMA, and do not claim to exercise an actual OS/vendor overlay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ResumeSkipPolicyTest {
    private val manager get() = AppOpenManager.getInstance()
    private lateinit var controller: ActivityController<Activity>
    private lateinit var host: Activity
    private val skipped = mutableListOf<Map<String, Any?>>()
    private val sink = object : TrackSink {
        override val id = "resume-policy-contract"
        override fun onEvent(name: String, params: Map<String, Any?>) {
            if (name == "ad_skipped") skipped += params
        }
    }

    @Before
    fun setUp() {
        manager.disableAppResume()
        manager.setInitialized(false)
        manager.setAppResumeAdId("")
        manager.releaseCachedAds()
        manager.setDisableAdResumeByClickAction(false)
        manager.setInterstitialShowing(false)
        manager.setResumeSkipPolicy(null)
        ConsentCenter.setHostConsent(true, false)
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        host = controller.get()
        manager.onActivityStarted(host)
        val app = ApplicationProvider.getApplicationContext<Application>()
        Tracker.install(app)
        Tracker.addSink(sink)
        skipped.clear()
    }

    @After
    fun tearDown() {
        manager.disableAppResume()
        manager.setResumeSkipPolicy(null)
        manager.setDisableAdResumeByClickAction(false)
        manager.setInterstitialShowing(false)
        manager.onActivityDestroyed(host)
        Tracker.removeSink(sink)
        ConsentCenter.clearHostConsent()
        controller.pause().stop().destroy()
    }

    @Test
    fun `two observers querying one return do not spend each other's click reason`() {
        manager.disableAdResumeByClickAction()
        assertEquals("returning_from_ad_click", manager.resumeSkipReasonFor(host))
        assertEquals("returning_from_ad_click", manager.resumeSkipReasonFor(host))
        manager.onResume() // Durable OPEN off must still consume this host return.
        assertEquals("returning_from_ad_click", manager.resumeSkipReasonFor(host))
        assertEquals("returning_from_ad_click", manager.resumeSkipReasonFor(host))
        assertEquals(0, skipped.size) // Querying is not an opportunity/event producer.
        manager.onStop()
        manager.onResume()
        assertNull(manager.resumeSkipReasonFor(host))
    }

    @Test
    fun `overlay host resume consumes click even while interstitial busy`() {
        manager.setInterstitialShowing(true)
        manager.disableAdResumeByClickAction()
        manager.onActivityResumed(host) // No process ON_START: the existing Activity resumes.
        assertEquals("returning_from_ad_click", manager.resumeSkipReasonFor(host))
        manager.setInterstitialShowing(false)
        manager.onStop()
        manager.onResume()
        assertNull(manager.resumeSkipReasonFor(host))
    }

    @Test
    fun `vendor AdActivity resume is not the host return that spends a click`() {
        val vendorActivity = Robolectric.buildActivity(AdActivity::class.java).get()
        manager.disableAdResumeByClickAction()
        manager.onActivityResumed(vendorActivity)
        manager.onResume()
        assertEquals("ad_activity", manager.resumeSkipReasonFor(vendorActivity))
        manager.onStop()
        manager.onActivityStarted(host)
        manager.onResume()
        assertEquals("returning_from_ad_click", manager.resumeSkipReasonFor(host))
        manager.onStop()
        manager.onResume()
        assertNull(manager.resumeSkipReasonFor(host))
    }

    @Test
    fun `shared host policy applies to both readers while default OPEN hook adds nothing`() {
        manager.setResumeSkipPolicy(ResumeSkipPolicy { "suppressed_by_flow" })
        assertEquals("suppressed_by_flow", manager.resumeSkipReasonFor(host))
        assertEquals("suppressed_by_flow", manager.resumeSkipReasonFor(host))
        manager.enableAppResume()
        manager.onResume()
        assertEquals(listOf("suppressed_by_flow"), skipped.map { it["reason"] })
        assertEquals(listOf("app_resume"), skipped.map { it["placement"] })
    }

    @Test
    fun `OPEN-only unit decline does not block the shared welcome query`() {
        manager.setResumeSkipPolicy(object : ResumeSkipPolicy {
            override fun skipReasonFor(activity: Activity): String? = null
            override fun appOpenSkipReasonFor(activity: Activity): String = "no_ad_unit"
        })
        assertNull(manager.resumeSkipReasonFor(host))
        manager.enableAppResume()
        manager.onResume()
        assertEquals(listOf("no_ad_unit"), skipped.map { it["reason"] })
        assertNull(manager.resumeSkipReasonFor(host))
    }

    @Test
    fun `consumed overlay snapshot ends after observers without waiting for process stop`() {
        manager.disableAdResumeByClickAction()
        manager.onActivityResumed(host)
        assertEquals("returning_from_ad_click", manager.resumeSkipReasonFor(host))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("A later explicit request in the same foreground is not the clicked return", manager.resumeSkipReasonFor(host))
    }

    @Test
    fun `older posted snapshot clear cannot erase a newer return`() {
        manager.skipNextResume("first_return")
        manager.onActivityResumed(host)
        var seenBetweenClears: String? = null
        Handler(Looper.getMainLooper()).post { seenBetweenClears = manager.resumeSkipReasonFor(host) }
        manager.skipNextResume("second_return")
        manager.onActivityResumed(host)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("second_return", seenBetweenClears)
        assertNull(manager.resumeSkipReasonFor(host))
    }

    @Test
    fun `throwing extension fallback cannot grant core consent`() {
        manager.setResumeSkipPolicy(ResumeSkipPolicy { throw IllegalStateException("host extension") })
        assertNull(manager.resumeSkipReasonFor(host))
        ConsentCenter.setHostConsent(false, false)
        assertEquals("consent_not_granted", manager.resumeSkipReasonFor(host))
        assertEquals(0, skipped.size)
    }

    @Test
    fun `explicit resume show obeys policy with legacy pre-show completion semantics`() {
        manager.enableAppResume()
        manager.setResumeSkipPolicy(ResumeSkipPolicy { "suppressed_by_flow" })
        var completions = 0
        manager.setEnableScreenContentCallback(true)
        manager.setFullScreenContentCallback(object : com.google.android.gms.ads.FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { completions++ }
        })
        try {
            manager.showAdIfAvailable(false)
            assertEquals(1, completions)
            assertEquals(listOf("suppressed_by_flow"), skipped.map { it["reason"] })
        } finally {
            manager.removeFullScreenContentCallback()
            manager.setEnableScreenContentCallback(false)
        }
    }

    @Test
    fun `owned hold gates both resume modes without replacing existing policy or changing mode`() {
        manager.setResumeSkipPolicy(ResumeSkipPolicy { "onboarding_policy" })
        val first = manager.suppressResume("feedback", "feedback_open", 5_000)
        val second = manager.suppressResume("review", "review_open", 5_000)
        try {
            // Same shared query that the host WELCOME observer uses; OPEN is still disabled.
            assertEquals("feedback_open", manager.resumeSkipReasonFor(host))
            manager.onResume()
            assertEquals(0, skipped.size)
            manager.enableAppResume()
            manager.onResume()
            assertEquals(listOf("feedback_open"), skipped.map { it["reason"] })
            first.close()
            assertEquals("review_open", manager.resumeSkipReasonFor(host))
            second.close()
            assertEquals("onboarding_policy", manager.resumeSkipReasonFor(host))
            manager.onResume()
            assertEquals(listOf("feedback_open", "onboarding_policy"), skipped.map { it["reason"] })
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `failed external operation cancels only its lease and leaves legacy click intact`() {
        val operation = manager.skipNextResume("widget", "widget_return", 5_000)
        manager.disableAdResumeByClickAction()
        operation.close()
        assertEquals("returning_from_ad_click", manager.resumeSkipReasonFor(host))
        manager.onActivityResumed(host)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(manager.resumeSkipReasonFor(host))
    }

    @Test
    fun `owned system return survives legacy reset and is consumed once by real host`() {
        val operation = manager.skipNextResume("widget", "widget_return", 5_000)
        try {
            manager.setDisableAdResumeByClickAction(false)
            val vendor = Robolectric.buildActivity(AdActivity::class.java).get()
            manager.onActivityResumed(vendor)
            assertEquals("widget_return", manager.resumeSkipReasonFor(host))
            manager.onActivityStarted(host)
            manager.onActivityResumed(host)
            assertEquals("widget_return", manager.resumeSkipReasonFor(host))
            assertEquals("widget_return", manager.resumeSkipReasonFor(host))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(manager.resumeSkipReasonFor(host))
        } finally {
            operation.close()
        }
    }

    @Test
    fun `abandoned suppression expires without a return or owner callback`() {
        manager.suppressResume("review", "review_open", 100)
        assertEquals("review_open", manager.resumeSkipReasonFor(host))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101))
        assertNull(manager.resumeSkipReasonFor(host))
    }
}
