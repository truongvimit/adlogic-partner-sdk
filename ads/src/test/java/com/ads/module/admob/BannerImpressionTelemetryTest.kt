package com.ads.module.admob

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import com.ads.module.ads.ERainAd
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.banner.BannerAdHelper
import com.ads.module.tracking.AdTracking
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.BaseAdView
import com.google.android.gms.ads.ResponseInfo
import io.trackkit.TrackSink
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowViewGroup

/** Public ERain → actual Admob listener → external AdView fake → real Tracker sink. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [BannerImpressionVendorShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BannerImpressionTelemetryTest {
    private lateinit var activity: Activity
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()

    @Before
    fun setUp() {
        BannerImpressionVendorShadow.requests.clear()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        val host = FrameLayout(activity)
        BannerAdHelper.resetPlaceholder(activity, host)
        activity.setContentView(host)
        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id = "banner-impression-recording"
            override fun onEvent(name: String, params: Map<String, Any?>) {
                events += name to params
            }
        })
        Tracker.install(activity, TrackerConfig(strictValidation = true, logLevel = 0))
        AdTracking.registerPlacement("banner-unit", "banner-slot")
    }

    @After
    fun tearDown() {
        BannerImpressionVendorShadow.requests.forEach { it.view.destroy() }
        BannerImpressionVendorShadow.requests.clear()
        Tracker.resetForTesting()
        activity.finish()
    }

    @Test
    fun `normal banner reports a show only for the vendor impression`() {
        assertImpressionContract { callback ->
            ERainAd.getInstance().loadBanner(activity, "banner-unit", callback)
        }
    }

    @Test
    fun `collapsible adaptive banner reports a show only for the vendor impression`() {
        assertImpressionContract { callback ->
            ERainAd.getInstance().loadCollapsibleBanner(activity, "banner-unit", "bottom", callback)
        }
    }

    @Test
    fun `collapsible medium banner reports a show only for the vendor impression`() {
        assertImpressionContract { callback ->
            ERainAd.getInstance().loadCollapsibleBannerSizeMedium(
                activity, "banner-unit", "bottom", AdSize.MEDIUM_RECTANGLE, callback,
            )
        }
    }

    private fun assertImpressionContract(load: (AdCallback) -> Unit) {
        var loadedCalls = 0
        var impressionCalls = 0
        load(object : AdCallback() {
            override fun onAdLoaded() { loadedCalls++ }
            override fun onAdImpression() { impressionCalls++ }
        })

        // The fake fills synchronously inside loadAd. A listener attached afterwards loses it.
        assertEquals(1, BannerImpressionVendorShadow.requests.size)
        assertEquals(1, loadedCalls)
        assertEquals(0, impressionCalls)
        assertEquals(0, params("ad_show").size)
        val listener = BannerImpressionVendorShadow.requests.single().listener
        assertNotNull(listener)

        listener!!.onAdImpression()

        assertEquals(1, impressionCalls)
        assertEquals(1, params("ad_show").size)
        assertEquals("banner-slot", params("ad_show").single()["placement"])
        assertEquals("banner-unit", params("ad_show").single()["ad_unit_id"])
        listener.onAdImpression()
        assertEquals(1, params("ad_show").size)
    }

    private fun params(name: String) = events.filter { it.first == name }.map { it.second }
}

@Implements(value = BaseAdView::class, isInAndroidSdk = false)
class BannerImpressionVendorShadow : ShadowViewGroup() {
    @RealObject private lateinit var view: BaseAdView

    @Implementation
    fun loadAd(request: AdRequest) {
        val captured = Request(view, view.adListener)
        requests += captured
        captured.listener?.onAdLoaded()
    }

    @Implementation
    fun getResponseInfo(): ResponseInfo = Mockito.mock(ResponseInfo::class.java)

    data class Request(val view: BaseAdView, val listener: AdListener?)

    companion object {
        val requests = mutableListOf<Request>()
    }
}
