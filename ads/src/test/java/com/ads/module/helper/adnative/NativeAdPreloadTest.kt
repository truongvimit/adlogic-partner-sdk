package com.ads.module.helper.adnative

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import com.ads.module.consent.ConsentCenter
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeAdPreloadTest {
    private lateinit var activity: ComponentActivity
    private lateinit var vendor: MockedConstruction<AdLoader.Builder>
    private val requests = mutableListOf<NativeRequest>()
    private val preloader get() = NativeAdPreload.getInstance()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        preloader.releaseAll()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context): Boolean = false
        })
        val connectivity = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        vendor = Mockito.mockConstruction(AdLoader.Builder::class.java) { builder, _ ->
            val request = NativeRequest()
            Mockito.`when`(builder.forNativeAd(Mockito.any(NativeAd.OnNativeAdLoadedListener::class.java)))
                .thenAnswer { invocation ->
                    request.onLoaded = invocation.getArgument(0)
                    builder
                }
            Mockito.`when`(builder.withAdListener(Mockito.any(AdListener::class.java)))
                .thenAnswer { invocation ->
                    request.adListener = invocation.getArgument(0)
                    builder
                }
            Mockito.`when`(builder.withNativeAdOptions(Mockito.any(NativeAdOptions::class.java))).thenReturn(builder)
            val loader = Mockito.mock(AdLoader::class.java)
            Mockito.`when`(builder.build()).thenReturn(loader)
            Mockito.doAnswer { requests += request; null }.`when`(loader).loadAd(Mockito.any(AdRequest::class.java))
        }
    }

    @After
    fun tearDown() {
        preloader.releaseAll()
        vendor.close()
        activity.finish()
    }

    @Test
    fun `native preload refuses network requests without consent authority`() {
        ConsentCenter.setHostConsent(false, false)

        assertFalse(preloader.canRequestLoad(activity))
    }

    @Test
    fun `late unauthorized native fill is destroyed and drains the remaining batch waiters`() {
        assertTrue(preloader.preloadWithKey("native", activity, NativeAdConfig("unit", true, true, 0), 3))
        val results = mutableListOf<ApNativeAd?>()
        preloader.awaitNext("native", results::add)
        preloader.awaitNext("native", results::add)
        ConsentCenter.setHostConsent(false, false)
        val ad = Mockito.mock(NativeAd::class.java)

        requests.single().onLoaded.onNativeAdLoaded(ad)

        assertEquals(listOf<ApNativeAd?>(null, null), results)
        assertFalse(preloader.isPreloadInProgress("native"))
        assertTrue(preloader.getNativeAdBuffer("native").isEmpty())
        assertEquals(1, requests.size)
        Mockito.verify(ad).destroy()
    }

    @Test
    fun `throwing cancelled waiter cannot strand another waiter`() {
        preloader.preloadWithKey("native", activity, NativeAdConfig("unit", true, true, 0))
        var secondCalls = 0
        preloader.awaitNext("native") { throw IllegalStateException("host callback") }
        preloader.awaitNext("native") { result ->
            assertEquals(null, result)
            secondCalls++
        }

        preloader.releaseAll()

        assertEquals(1, secondCalls)
        assertFalse(preloader.isPreloadInProgress("native"))
    }

    @Test
    fun `changed personalization destroys an obsolete native cache on read`() {
        ConsentCenter.setHostConsent(true, true)
        preloader.preloadWithKey("native", activity, NativeAdConfig("unit", true, true, 0))
        val ad = Mockito.mock(NativeAd::class.java)
        requests.single().onLoaded.onNativeAdLoaded(ad)
        ConsentCenter.setHostConsent(true, false)

        assertNull(preloader.getAdNative("native"))
        assertNull(preloader.pollAdNative("native"))
        assertTrue(preloader.getNativeAdBuffer("native").isEmpty())
        assertFalse(preloader.isPreloadAvailable("native"))
        Mockito.verify(ad).destroy()
    }

    @Test
    fun `global buffer invalidation preserves subscriptions and rejects the earlier fill`() {
        var observed = 0
        preloader.registerAdCallback("native", object : AdCallback() {
            override fun onNativeAdLoaded(nativeAd: ApNativeAd) { observed++ }
        })
        preloader.preloadWithKey("native", activity, NativeAdConfig("unit", true, true, 0))
        var cancelledWaiters = 0
        preloader.awaitNext("native") { if (it == null) cancelledWaiters++ }

        AdGate.releaseBufferedAds()
        preloader.preloadWithKey("native", activity, NativeAdConfig("unit", true, true, 0))
        val oldAd = Mockito.mock(NativeAd::class.java)
        requests[0].onLoaded.onNativeAdLoaded(oldAd)
        requests[1].onLoaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))

        assertEquals(1, cancelledWaiters)
        assertEquals(1, observed)
        Mockito.verify(oldAd).destroy()
    }

    @Test
    fun `direct helper cannot bind a late native fill after consent is revoked`() {
        val container = FrameLayout(activity)
        val config = NativeAdConfig("unit", true, true, 0).apply { autoShimmer = false }
        var binds = 0
        val helper = NativeAdHelper(activity, activity, config).setNativeContentView(container)
            .setNativeAdBinder { _, _, host, _ ->
                binds++
                host.visibility = View.VISIBLE
            }
        helper.requestAds(NativeAdParam.Request)
        ConsentCenter.setHostConsent(false, false)
        val ad = Mockito.mock(NativeAd::class.java)

        requests.single().onLoaded.onNativeAdLoaded(ad)

        assertEquals(0, binds)
        assertEquals(AdNativeState.Fail, helper.nativeAdState.value)
        assertEquals(View.GONE, container.visibility)
        Mockito.verify(ad).destroy()
    }

    private class NativeRequest {
        lateinit var onLoaded: NativeAd.OnNativeAdLoadedListener
        lateinit var adListener: AdListener
    }
}
