package com.ads.module.admob

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.appopen.AppOpenAd
import io.trackkit.TrackSink
import io.trackkit.Tracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/**
 * `open_resume` reports its own load lifecycle, so its client-side funnel exists.
 *
 * Before this the manager emitted policy skips and nothing else, so a console showing many
 * matched requests against few impressions could not be attributed to a stage. The last test
 * covers a vendor fill the buffer refuses after its request has been superseded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [ResumeLoadGmaShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AppOpenResumeTelemetryTest {
    private val manager get() = AppOpenManager.getInstance()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val requests get() = ResumeLoadGmaShadow.requests
    private lateinit var app: Application
    private val sink = RecordingSink()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        manager.disableAppResume()
        manager.currentActivity?.let { manager.onActivityDestroyed(it) }
        manager.releaseCachedAds()
        manager.init(app, "")
        manager.setInterstitialShowing(false)
        manager.setDisableAdResumeByClickAction(false)
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context): Boolean = false
        })
        requests.clear()
        ResumeLoadGmaShadow.throwOnLoad = false
        Tracker.install(app)
        Tracker.addSink(sink)
        sink.events.clear()
    }

    @After
    fun tearDown() {
        manager.disableAppResume()
        manager.setAppResumeAdId("")
        manager.releaseCachedAds()
        ConsentCenter.clearHostConsent()
        requests.clear()
        ResumeLoadGmaShadow.throwOnLoad = false
        Tracker.removeSink(sink)
    }

    @Test
    fun `a background dispatch reports one request against the open_resume placement`() {
        enable()
        background()
        assertEquals(1, requests.size)
        val request = named(AD_REQUEST).single()
        assertEquals("open_resume", request["placement"])
        assertEquals("app_open", request["ad_format"])
        assertEquals(UNIT, request["ad_unit_id"])
    }

    @Test
    fun `a fill reports loaded`() {
        enable()
        background()
        fill(0)
        assertEquals(1, named(AD_LOADED).size)
        assertTrue(named(AD_LOAD_FAILED).isEmpty())
    }

    @Test
    fun `a no fill reports load_failed with the vendor code`() {
        enable()
        background()
        fail(0)
        val failed = named(AD_LOAD_FAILED).single()
        assertEquals("open_resume", failed["placement"])
        assertEquals("app_open", failed["ad_format"])
        assertEquals(3, failed["error_code"])
    }

    @Test
    fun `a superseded fill is reported as loaded and discarded`() {
        enable()
        background()
        main.idleFor(35_000, TimeUnit.MILLISECONDS)
        sink.events.clear()
        fill(0)
        // The vendor returned an ad, but a newer request now owns the buffer.
        assertEquals(1, named(AD_LOADED).size)
        val skipped = named(AD_SKIPPED).single()
        assertEquals("open_resume", skipped["placement"])
        assertEquals("app_open", skipped["ad_format"])
        assertEquals("fill_discarded", skipped["reason"])
    }

    @Test
    fun `timeout followed by a valid late fill reports one vendor outcome without discard`() {
        enable()
        background()
        manager.onResume()
        main.idleFor(60_000, TimeUnit.MILLISECONDS)
        assertEquals("load_timeout", named(AD_SKIPPED).single()["reason"])
        assertTrue(named(AD_LOAD_FAILED).isEmpty())
        fill(0)
        fill(0)
        fail(0)
        assertEquals(1, named(AD_LOADED).size)
        assertTrue(named(AD_LOAD_FAILED).isEmpty())
        assertTrue(manager.isAdAvailable(false))
        assertEquals(1, named(AD_SKIPPED).size)
    }

    @Test
    fun `a dispatch that throws still closes its reported request`() {
        ResumeLoadGmaShadow.throwOnLoad = true
        enable()
        background()
        assertEquals(1, named(AD_REQUEST).size)
        // Otherwise the funnel keeps a request no outcome answers, and every rate built on it
        // silently understates the fill and show stages.
        assertEquals(1, named(AD_LOAD_FAILED).size)
    }

    @Test
    fun `a suppressed background stay reports neither a request nor a fill`() {
        enable()
        manager.skipNextResume("leaving_for_system_screen")
        background()
        assertTrue(requests.isEmpty())
        assertTrue(named(AD_REQUEST).isEmpty())
        assertTrue(named(AD_LOADED).isEmpty())
    }

    private fun enable() {
        manager.setAppResumeAdId(UNIT)
        manager.enableAppResume()
    }

    /** Arms the one background opportunity and lets its delayed dispatch run. */
    private fun background() {
        manager.onStop()
        main.idleFor(2_100, TimeUnit.MILLISECONDS)
    }

    private fun fill(index: Int) {
        requests[index].callback.onAdLoaded(TelemetryGmaAd(requests[index].unit))
    }

    private fun fail(index: Int) {
        requests[index].callback.onAdFailedToLoad(
            LoadAdError(3, "Telemetry no fill", "test.gma", null, null),
        )
    }

    private fun named(name: String) = sink.events.filter { it.first == name }.map { it.second }

    private class RecordingSink : TrackSink {
        override val id = "app-resume-telemetry"
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun onEvent(name: String, params: Map<String, Any?>) {
            events += name to params.toMap()
        }
    }

    private companion object {
        const val UNIT = "ca-app-pub-0000000000000000/1111111111"
        const val AD_REQUEST = "ad_request"
        const val AD_LOADED = "ad_loaded"
        const val AD_LOAD_FAILED = "ad_load_failed"
        const val AD_SKIPPED = "ad_skipped"
    }
}

/** Load-side only: showing is covered by the presentation suite, which owns a showable ad. */
private class TelemetryGmaAd(private val unit: String) : AppOpenAd() {
    private var content: FullScreenContentCallback? = null
    private var paid: OnPaidEventListener? = null
    private var placement = 0L
    override fun show(activity: Activity): Unit = error("Telemetry load tests must never show an ad")
    override fun getAdUnitId() = unit
    override fun getResponseInfo(): ResponseInfo = error("No impression is produced here")
    override fun setFullScreenContentCallback(callback: FullScreenContentCallback?) { content = callback }
    override fun getFullScreenContentCallback() = content
    override fun setOnPaidEventListener(listener: OnPaidEventListener?) { paid = listener }
    override fun getOnPaidEventListener() = paid
    override fun setImmersiveMode(immersiveMode: Boolean) = Unit
    override fun getPlacementId() = placement
    override fun setPlacementId(value: Long) { placement = value }
}
