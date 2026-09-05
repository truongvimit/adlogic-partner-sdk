package com.ads.module.helper.banner

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.ads.module.consent.ConsentCenter
import com.ads.module.R
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.BaseAdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.ResponseInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowViewGroup

/** Public helper and real view hierarchy; only the external GMA request is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [BannerVendorShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BannerAdHelperTest {
    private lateinit var activity: Activity
    private lateinit var owner: BannerLifecycleOwner
    private lateinit var helper: BannerAdHelper
    private lateinit var host: FrameLayout
    private var failureCalls = 0
    private var loadedCalls = 0

    @Before
    fun setUp() {
        BannerVendorShadow.requests.clear()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        owner = BannerLifecycleOwner()
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context): Boolean = false
        })
        ConsentCenter.setHostConsent(true, false)
        val connectivity = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        host = FrameLayout(activity)
        activity.setContentView(host)
        helper = BannerAdHelper(activity, owner, BannerAdConfig(
            listOf("banner-high", "banner-low"), true, true, BannerType.Fixed(),
        )).attachInto(host)
        helper.registerAdListener(object : AdCallback() {
            override fun onAdFailedToLoad(error: LoadAdError?) { failureCalls++ }
            override fun onAdLoaded() { loadedCalls++ }
        })
    }

    @After
    fun tearDown() {
        owner.registry.currentState = Lifecycle.State.DESTROYED
        activity.finish()
        BannerVendorShadow.requests.clear()
    }

    @Test
    fun `revoked consent stops banner fallback and completes the load once`() {
        helper.requestAds(BannerAdParam.Request)
        assertEquals(1, BannerVendorShadow.requests.size)
        val first = BannerVendorShadow.requests.single()
        ConsentCenter.setHostConsent(false, false)

        first.listener.onAdFailedToLoad(LoadAdError(3, "no fill", "test", null, null))

        assertEquals(1, BannerVendorShadow.requests.size)
        assertEquals(AdBannerState.Fail, helper.bannerAdState.value)
        assertEquals(1, failureCalls)
        first.listener.onAdFailedToLoad(LoadAdError(3, "duplicate", "test", null, null))
        assertEquals(1, failureCalls)
    }

    @Test
    fun `late banner fill after consent revocation is removed without a loaded callback`() {
        helper.requestAds(BannerAdParam.Request)
        val first = BannerVendorShadow.requests.single()
        ConsentCenter.setHostConsent(false, false)

        first.listener.onAdLoaded()

        assertEquals(0, host.findViewById<FrameLayout>(R.id.banner_container).childCount)
        assertEquals(AdBannerState.Fail, helper.bannerAdState.value)
        assertEquals(0, loadedCalls)
        assertEquals(1, failureCalls)
        first.listener.onAdLoaded()
        assertEquals(1, failureCalls)
        assertEquals(0, loadedCalls)
    }

    @Test
    fun `offline fallback stops refresh but keeps the previously displayed banner`() {
        helper.requestAds(BannerAdParam.Request)
        val first = BannerVendorShadow.requests.single()
        first.listener.onAdLoaded()
        helper.requestAds(BannerAdParam.Reload)
        assertEquals(2, BannerVendorShadow.requests.size)
        val connectivity = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, null)

        BannerVendorShadow.requests.last().listener.onAdFailedToLoad(
            LoadAdError(3, "no fill", "test", null, null),
        )

        assertEquals(2, BannerVendorShadow.requests.size)
        val container = host.findViewById<FrameLayout>(R.id.banner_container)
        assertEquals(1, container.childCount)
        assertSame(first.view, container.getChildAt(0))
        assertEquals(AdBannerState.Loaded, helper.bannerAdState.value)
        assertEquals(1, failureCalls)
    }

    @Test
    fun `authorized fallback fills and live refresh misses retain the winning banner`() {
        helper.requestAds(BannerAdParam.Request)
        val high = BannerVendorShadow.requests.single()
        high.listener.onAdFailedToLoad(LoadAdError(3, "no fill", "test", null, null))
        assertEquals(2, BannerVendorShadow.requests.size)
        assertEquals(0, failureCalls)
        val low = BannerVendorShadow.requests.last()
        low.listener.onAdLoaded()
        high.listener.onAdLoaded()
        val container = host.findViewById<FrameLayout>(R.id.banner_container)
        assertEquals(1, container.childCount)
        assertSame(low.view, container.getChildAt(0))
        assertEquals(1, loadedCalls)

        repeat(2) {
            low.listener.onAdFailedToLoad(LoadAdError(3, "refresh miss", "test", null, null))
        }

        assertEquals(2, BannerVendorShadow.requests.size)
        assertEquals(2, failureCalls)
        assertSame(low.view, container.getChildAt(0))
        assertEquals(View.VISIBLE, container.visibility)
        assertEquals(AdBannerState.Loaded, helper.bannerAdState.value)
        low.listener.onAdLoaded()
        assertEquals(2, loadedCalls)
    }

    @Test
    fun `an obsolete rejected callback cannot erase a later authorized banner`() {
        helper.requestAds(BannerAdParam.Request)
        val obsolete = BannerVendorShadow.requests.single()
        ConsentCenter.setHostConsent(false, false)
        obsolete.listener.onAdLoaded()
        ConsentCenter.setHostConsent(true, false)
        helper.requestAds(BannerAdParam.Request)
        val replacement = BannerVendorShadow.requests.last()
        replacement.listener.onAdLoaded()

        obsolete.listener.onAdLoaded()
        obsolete.listener.onAdFailedToLoad(LoadAdError(3, "late", "test", null, null))

        val container = host.findViewById<FrameLayout>(R.id.banner_container)
        assertEquals(1, container.childCount)
        assertSame(replacement.view, container.getChildAt(0))
        assertEquals(View.VISIBLE, container.visibility)
        assertEquals(AdBannerState.Loaded, helper.bannerAdState.value)
        assertEquals(1, loadedCalls)
        assertEquals(1, failureCalls)
    }

    @Test
    fun `cancel prevents a pending banner from reattaching on late fill`() {
        helper.requestAds(BannerAdParam.Request)
        val pending = BannerVendorShadow.requests.single()
        helper.cancel()

        pending.listener.onAdLoaded()

        val container = host.findViewById<FrameLayout>(R.id.banner_container)
        assertEquals(0, container.childCount)
        assertEquals(View.GONE, container.visibility)
        assertEquals(AdBannerState.Cancel, helper.bannerAdState.value)
        assertEquals(0, loadedCalls)
    }

    private class BannerLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
        override val lifecycle: Lifecycle get() = registry
    }
}

@Implements(value = BaseAdView::class, isInAndroidSdk = false)
class BannerVendorShadow : ShadowViewGroup() {
    @RealObject private lateinit var view: BaseAdView

    @Implementation
    fun loadAd(request: AdRequest) {
        requests += Request(view, view.adListener!!)
    }

    @Implementation
    fun getResponseInfo(): ResponseInfo = Mockito.mock(ResponseInfo::class.java)

    data class Request(val view: BaseAdView, val listener: AdListener)

    companion object {
        val requests = mutableListOf<Request>()
    }
}
