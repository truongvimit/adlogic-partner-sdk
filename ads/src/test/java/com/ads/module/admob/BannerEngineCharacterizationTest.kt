package com.ads.module.admob

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.ads.module.R
import com.ads.module.ads.ERainAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.engine.BannerEngine
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.banner.BannerAdHelper
import com.ads.module.helper.banner.BannerType
import com.ads.module.helper.banner.BannerVendorViewShadow
import com.ads.module.helper.interstitial.Int02Activity
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02FacebookShadow
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.google.android.gms.ads.AdListener
import io.trackkit.TrackSink
import io.trackkit.Tracker
import io.trackkit.TrackkitEvents
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Int02Application::class,
    shadows = [BannerVendorViewShadow::class, Int02MobileAdsShadow::class, Int02FacebookShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class BannerEngineCharacterizationTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var activity: Int02Activity
    private lateinit var host: FrameLayout
    private val requests get() = BannerVendorViewShadow.requests
    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val order = mutableListOf<String>()
    private val sink = object : TrackSink {
        override val id = "banner-engine-characterization"
        override fun onEvent(name: String, params: Map<String, Any?>) {
            if (name == TrackkitEvents.AD_CLICK) order += "$CLICK_LOG:${params[TrackkitEvents.PARAM_AD_UNIT_ID]}"
        }
    }
    private val recording = object : AdCallback() {
        override fun onAdClicked() { order += CALLBACK_CLICK }
        override fun onAdImpression() { order += CALLBACK_IMPRESSION }
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        requests.clear()
        AdBehavior.document.acceptSuccessfulFetch("{}")
        ConsentCenter.setHostConsent(true, false)
        installPremium(false)
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply { facebookClientToken = "banner-engine-token" })
        ERainAd.getInstance().setMaxClickAdsPerDay(0)
        AppOpenManager.getInstance().disableAppResume()
        Tracker.install(app)
        Tracker.setConsent(true, true)
        Tracker.addSink(sink)
        controller = Robolectric.buildActivity(Int02Activity::class.java).setup()
        activity = controller.get()
        host = FrameLayout(activity)
        activity.setContentView(host)
        BannerAdHelper.resetPlaceholder(activity, host)
        mainLooper.idle()
        order.clear()
    }

    @After
    fun tearDown() {
        Tracker.removeSink(sink)
        installPremium(false)
        if (::controller.isInitialized) {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            controller.destroy()
            mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        }
        ConsentCenter.clearHostConsent()
        AdBehavior.document.acceptSuccessfulFetch("{}")
        requests.clear()
    }

    @Test
    fun `gate decline on the plain banner loader ends with one null load failure and no request`() {
        installPremium(true)
        val callback = mock(AdCallback::class.java)
        loadPlain(callback)
        assertTrue("gate must stop the plain banner before any AdView request", requests.isEmpty())
        verify(callback).onAdFailedToLoad(null)
        verifyNoMoreInteractions(callback)
    }

    @Test
    fun `gate decline on the collapsible banner loader ends with one null load failure and no request`() {
        installPremium(true)
        val callback = mock(AdCallback::class.java)
        loadCollapsible(callback)
        assertTrue("gate must stop the collapsible banner before any AdView request", requests.isEmpty())
        verify(callback).onAdFailedToLoad(null)
        verifyNoMoreInteractions(callback)
    }

    @Test
    fun `plain banner click logs one ad_click for its own unit`() {
        loadPlain(AdCallback())
        filledListener().onAdClicked()
        assertEquals("plain banner click must reach logClickAdsEvent once", listOf("$CLICK_LOG:$UNIT"), order)
    }

    @Test
    fun `collapsible banner click logs one ad_click for its own unit`() {
        loadCollapsible(AdCallback())
        filledListener().onAdClicked()
        assertEquals("collapsible banner click must reach logClickAdsEvent once", listOf("$CLICK_LOG:$UNIT"), order)
    }

    @Test
    fun `collapsible banner click logs ad_click before notifying the callback`() {
        loadCollapsible(recording)
        filledListener().onAdClicked()
        assertEquals("collapsible click order", listOf("$CLICK_LOG:$UNIT", CALLBACK_CLICK), order)
    }

    @Test
    fun `plain banner click notifies the callback before logging ad_click`() {
        loadPlain(recording)
        filledListener().onAdClicked()
        assertEquals("plain banner click order", listOf(CALLBACK_CLICK, "$CLICK_LOG:$UNIT"), order)
    }

    @Test
    fun `collapsible banner listener does not forward onAdImpression to the callback`() {
        loadCollapsible(recording)
        filledListener().onAdImpression()
        assertEquals("collapsible loader must not forward impressions", emptyList<String>(), order)
    }

    @Test
    fun `plain banner listener forwards onAdImpression to the callback once`() {
        loadPlain(recording)
        filledListener().onAdImpression()
        assertEquals("plain banner loader must forward impressions", listOf(CALLBACK_IMPRESSION), order)
    }

    private fun loadPlain(callback: AdCallback) = BannerEngine.load(
        activity, UNIT, host.findViewById(R.id.banner_container),
        host.findViewById(R.id.shimmer_container_banner), BannerType.Normal, callback,
    )

    private fun loadCollapsible(callback: AdCallback) = BannerEngine.load(
        activity, UNIT, host.findViewById(R.id.banner_container),
        host.findViewById(R.id.shimmer_container_banner), BannerType.Collapsible("bottom"), callback,
    )

    private fun filledListener(): AdListener {
        val listener = checkNotNull(requests.single().view.adListener)
        listener.onAdLoaded()
        return listener
    }

    private fun installPremium(premium: Boolean) = Entitlement.install(object : EntitlementSource {
        override fun isPremium(context: Context) = premium
    })

    private companion object {
        const val UNIT = "ca-app-pub-0000000000000000/2222222222"
        const val CLICK_LOG = "ad_click"
        const val CALLBACK_CLICK = "callback.onAdClicked"
        const val CALLBACK_IMPRESSION = "callback.onAdImpression"
    }
}
