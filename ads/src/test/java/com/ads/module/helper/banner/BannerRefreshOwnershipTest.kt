package com.ads.module.helper.banner

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.ads.module.R
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.Int02Activity
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02FacebookShadow
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.BaseAdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.ResponseInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.TimeUnit

/** Real public helper/ERain/Admob; only BaseAdView's external dispatch/storage is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Int02Application::class,
    shadows = [BannerVendorViewShadow::class, Int02MobileAdsShadow::class, Int02FacebookShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class BannerRefreshOwnershipTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var activity: Int02Activity
    private lateinit var host: FrameLayout
    private var helper: BannerAdHelper? = null
    private val requests get() = BannerVendorViewShadow.requests
    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val container get() = host.findViewById<FrameLayout>(R.id.banner_container)

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        requests.clear()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply { setFacebookClientToken("ban04-test-token") })
        AppOpenManager.getInstance().disableAppResume()
        controller = Robolectric.buildActivity(Int02Activity::class.java).setup()
        activity = controller.get()
        host = FrameLayout(activity)
        activity.setContentView(host)
        mainLooper.idle()
    }

    @After
    fun tearDown() {
        helper?.flagUserEnableReload = false
        helper?.cancel()
        if (::controller.isInitialized) {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            controller.destroy()
            mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        }
        ConsentCenter.clearHostConsent()
        requests.clear()
    }

    @Test
    fun `initial collapsible request uses documented extras and preserves nonpersonalized request`() {
        createHelper(canReload = true).requestAds(BannerAdParam.Request)
        val first = requests.single()
        assertEquals("bottom", extras(first).getString("collapsible"))
        assertEquals("1", extras(first).getString("npa"))
        assertFalse(extras(first).containsKey("collapsible_request_id"))
        assertFalse(extras(first).containsKey("_noRefresh"))
    }

    @Test
    fun `every initial tier is collapsible but every Reload tier is ordinary and survivor remains until fill`() {
        val helper = createHelper(canReload = true, tiers = listOf("high", "normal"))
        helper.requestAds(BannerAdParam.Request)
        val first = requests.single()
        assertEquals("bottom", extras(first).getString("collapsible"))
        fail(first)
        val initialFallback = requests.last()
        assertEquals(2, requests.size)
        assertEquals("bottom", extras(initialFallback).getString("collapsible"))
        fill(initialFallback)
        assertSame(initialFallback.view, container.getChildAt(0))

        helper.requestAds(BannerAdParam.Reload)
        val refreshHigh = requests.last()
        assertFalse(extras(refreshHigh).containsKey("collapsible"))
        assertEquals(initialFallback.view.adSize, refreshHigh.view.adSize)
        assertFalse(vendor(initialFallback.view).destroyed)
        assertSame(container, initialFallback.view.parent)
        fail(refreshHigh)
        val refreshFallback = requests.last()
        assertEquals(4, requests.size)
        assertFalse(extras(refreshFallback).containsKey("collapsible"))
        assertFalse(vendor(initialFallback.view).destroyed)
        assertEquals(View.VISIBLE, container.visibility)
        fill(refreshFallback)
        assertTrue(vendor(initialFallback.view).destroyed)
        assertEquals(1, container.childCount)
        assertSame(refreshFallback.view, container.getChildAt(0))

        // Explicit first-load intent still lets the host ask for another collapsible creative.
        helper.requestAds(BannerAdParam.Request)
        assertEquals("bottom", extras(requests.last()).getString("collapsible"))
    }

    @Test
    fun `AdMob owner config never replaces its loaded view on timer resume or vendor auto refresh`() {
        val helper = createHelper(canReload = false)
        assertFalse(helper.config.enableAutoReload)
        helper.requestAds(BannerAdParam.Request)
        val live = requests.single()
        fill(live)
        mainLooper.idleFor(65, TimeUnit.SECONDS)
        assertEquals(1, requests.size)
        controller.pause().stop()
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        controller.restart().start().resume()
        mainLooper.idleFor(501, TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
        helper.requestAds(BannerAdParam.Reload)
        assertEquals("Explicit Reload remains subject to canReloadAds", 1, requests.size)
        fill(live) // Real GMA can refresh the same AdView without a new helper load.
        fail(live)
        assertEquals(1, requests.size)
        assertEquals(AdBannerState.Loaded, helper.bannerAdState.value)
        assertSame(live.view, container.getChildAt(0))
        assertEquals(View.VISIBLE, container.visibility)
        assertFalse(vendor(live.view).destroyed)
    }

    @Test
    fun `SDK timer opt in retains configured cadence but its automatic reload is ordinary`() {
        val helper = createHelper(canReload = true, autoReload = true)
        helper.requestAds(BannerAdParam.Request)
        val initial = requests.single()
        fill(initial)
        mainLooper.idleFor(999, TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
        mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(2, requests.size)
        val refresh = requests.last()
        assertFalse(extras(refresh).containsKey("collapsible"))
        fill(refresh)
        controller.pause().stop()
        mainLooper.idleFor(2, TimeUnit.SECONDS)
        assertEquals("Existing timer pauses while stopped", 2, requests.size)
    }

    @Test
    fun `failed ordinary refresh keeps the original collapsible creative visible`() {
        val helper = createHelper(canReload = true)
        helper.requestAds(BannerAdParam.Request)
        val initial = requests.single()
        fill(initial)
        helper.requestAds(BannerAdParam.Reload)
        val refresh = requests.last()
        assertFalse(extras(refresh).containsKey("collapsible"))
        fail(refresh)
        assertEquals(AdBannerState.Loaded, helper.bannerAdState.value)
        assertEquals(View.VISIBLE, container.visibility)
        assertEquals(1, container.childCount)
        assertSame(initial.view, container.getChildAt(0))
        assertFalse(vendor(initial.view).destroyed)
        assertTrue(vendor(refresh.view).destroyed)
    }

    private fun createHelper(canReload: Boolean, tiers: List<String> = listOf("banner"), autoReload: Boolean = false): BannerAdHelper {
        val config = BannerAdConfig(tiers, true, canReload, BannerType.Collapsible()).apply {
            enableAutoReload = autoReload
            autoReloadTime = 1_000 // Existing supported range is intentionally unchanged.
        }
        return BannerAdHelper(activity, activity, config).attachInto(host).also { helper = it }
    }

    private fun extras(request: BannerVendorRequest) =
        checkNotNull(request.request.getNetworkExtrasBundle(AdMobAdapter::class.java))

    // Dispatch callbacks after loadAd returns: current collapsible listener ordering is outside
    // this change, so a synchronous fake delivery would test an unrelated timing defect.
    private fun fill(request: BannerVendorRequest) = checkNotNull(request.view.adListener).onAdLoaded()
    private fun fail(request: BannerVendorRequest) = checkNotNull(request.view.adListener).onAdFailedToLoad(
        LoadAdError(3, "test no fill", "com.google.android.gms.ads", null, null),
    )
    private fun vendor(view: AdView): BannerVendorViewShadow = Shadow.extract(view)
}

data class BannerVendorRequest(val view: AdView, val request: AdRequest)

@Implements(value = BaseAdView::class, isInAndroidSdk = false)
class BannerVendorViewShadow : org.robolectric.shadows.ShadowViewGroup() {
    @RealObject private lateinit var actual: BaseAdView
    private var listener: AdListener? = null
    private var size: AdSize? = null
    private var unit: String? = null
    private var paid: OnPaidEventListener? = null
    var destroyed = false
        private set

    @Implementation fun setAdListener(value: AdListener?) { listener = value }
    @Implementation fun getAdListener(): AdListener? = listener
    @Implementation fun setAdSize(value: AdSize) { size = value }
    @Implementation fun getAdSize(): AdSize? = size
    @Implementation fun setAdUnitId(value: String) { unit = value }
    @Implementation fun getAdUnitId(): String? = unit
    @Implementation fun setOnPaidEventListener(value: OnPaidEventListener?) { paid = value }
    @Implementation fun getOnPaidEventListener(): OnPaidEventListener? = paid
    @Implementation fun loadAd(request: AdRequest) { requests += BannerVendorRequest(actual as AdView, request) }
    @Implementation fun destroy() { destroyed = true }
    // External GMA 25.3.0 factory: zzc(null) creates a real empty ResponseInfo; zzb(null) does not.
    // Its mediation getter safely returns null. No SDK-owned object or field is reflected/reset.
    @Implementation fun getResponseInfo(): ResponseInfo = ResponseInfo.zzc(null)

    companion object {
        val requests = mutableListOf<BannerVendorRequest>()
    }
}
