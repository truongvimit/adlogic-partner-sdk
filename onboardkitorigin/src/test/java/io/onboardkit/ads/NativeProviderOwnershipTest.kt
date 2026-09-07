package io.onboardkit.ads

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Bundle
import android.os.Looper
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.adnative.NativeAdManager
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.nativead.NativeAd
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.config.NativeAdUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [NativeProviderViewShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeProviderOwnershipTest {
    private lateinit var controller: ActivityController<NativeProviderHost>
    private lateinit var provider: ERainAdProvider
    private lateinit var builders: MockedConstruction<AdLoader.Builder>
    private val requests = mutableListOf<NativeAd.OnNativeAdLoadedListener>()
    private val placement = AdPlacement.Language1
    private val request = NativeAdRequest(placement, NativeAdUnit(listOf("native-test")),
        com.ads.module.R.layout.custom_native_admob_medium)
    private val main get() = shadowOf(Looper.getMainLooper())

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        NativeAdManager.releaseAll()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource { override fun isPremium(context: Context) = false })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        builders = mockConstruction(AdLoader.Builder::class.java) { builder, _ ->
            var loaded: NativeAd.OnNativeAdLoadedListener? = null
            doAnswer { loaded = it.getArgument(0); builder }.`when`(builder).forNativeAd(any())
            doReturn(builder).`when`(builder).withAdListener(any(AdListener::class.java))
            doReturn(builder).`when`(builder).withNativeAdOptions(any())
            val loader = mock(AdLoader::class.java)
            doReturn(loader).`when`(builder).build()
            doAnswer { requests += checkNotNull(loaded); null }.`when`(loader).loadAd(any(AdRequest::class.java))
        }
        provider = ERainAdProvider()
        controller = Robolectric.buildActivity(NativeProviderHost::class.java).setup()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        provider.releaseAll()
        main.idleFor(61, java.util.concurrent.TimeUnit.SECONDS)
        NativeAdManager.releaseAll()
        NativeProviderHost.onCreated = null
        builders.close()
        ConsentCenter.clearHostConsent()
    }

    @Test fun `cold native show receives fill binds once and consumes placement cache`() {
        val host = controller.get()
        val container = FrameLayout(host)
        host.setContentView(container)
        var binds = 0
        var shown = 0
        val listener = object : AdEventListener {
            override fun onLoaded() {
                if (provider.bindNative(host, placement, container, null)) binds++
            }
            override fun onImpression() { shown++ }
        }
        assertFalse(provider.bindNative(host, placement, container, null, listener))
        provider.preloadNative(host, request)
        val ad = mock(NativeAd::class.java)
        doReturn("Native ad").`when`(ad).headline
        requests.single().onNativeAdLoaded(ad)
        assertEquals(org.robolectric.shadows.ShadowLog.getLogs().filter { it.tag == "NativeAdHelper" }.joinToString("\n") { it.throwable?.stackTraceToString().orEmpty() }, 1, binds)
        assertEquals(1, shown)
        assertEquals(1, container.childCount)
        assertFalse(provider.isNativeReady(placement))
        verify(ad, never()).destroy()
        controller.pause().stop()
        verify(ad).destroy()
        controller.restart().start().resume()
    }
    @Test fun `onboarding rotation restores a consumed ad even when show starts in onCreate`() {
        val host = controller.get()
        val container = FrameLayout(host)
        host.setContentView(container)
        provider.preloadNative(host, request)
        val ad = mock(NativeAd::class.java)
        doReturn("Native ad").`when`(ad).headline
        requests.single().onNativeAdLoaded(ad)
        assertTrue(provider.bindNative(host, placement, container, null))
        var newContainer: FrameLayout? = null
        var restoredBinds = 0
        NativeProviderHost.onCreated = { recreated ->
            newContainer = FrameLayout(recreated).also(recreated::setContentView)
            val listener = object : AdEventListener {
                override fun onLoaded() {
                    if (provider.bindNative(recreated, placement, newContainer!!, null)) restoredBinds++
                }
            }
            if (!provider.bindNative(recreated, placement, newContainer!!, null, listener)) {
                provider.preloadNative(recreated, request)
            }
        }
        controller.configurationChange(android.content.res.Configuration(host.resources.configuration).apply {
            orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
        })
        assertEquals(1, requests.size)
        assertEquals(1, restoredBinds)
        assertEquals(1, newContainer!!.childCount)
        assertFalse(provider.isNativeReady(placement))
        verify(ad, never()).destroy()
    }

}

class NativeProviderHost : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(com.ads.module.R.style.AppTheme)
        super.onCreate(savedInstanceState)
        onCreated?.invoke(this)
    }
    companion object { var onCreated: ((NativeProviderHost) -> Unit)? = null }
}

/** Replace only Google's view registration; Android layout inflation and binding stay real. */
@org.robolectric.annotation.Implements(value = com.google.android.gms.ads.nativead.NativeAdView::class, isInAndroidSdk = false)
class NativeProviderViewShadow : org.robolectric.shadows.ShadowViewGroup() {
    private val assets = mutableMapOf<String, android.view.View?>()
    @org.robolectric.annotation.Implementation fun setNativeAd(ad: NativeAd) {}
    @org.robolectric.annotation.Implementation fun destroy() {}
    @org.robolectric.annotation.Implementation fun setHeadlineView(view: android.view.View?) { assets["Headline"] = view }
    @org.robolectric.annotation.Implementation fun getHeadlineView(): android.view.View? = assets["Headline"]
    @org.robolectric.annotation.Implementation fun setBodyView(view: android.view.View?) { assets["Body"] = view }
    @org.robolectric.annotation.Implementation fun getBodyView(): android.view.View? = assets["Body"]
    @org.robolectric.annotation.Implementation fun setCallToActionView(view: android.view.View?) { assets["CallToAction"] = view }
    @org.robolectric.annotation.Implementation fun getCallToActionView(): android.view.View? = assets["CallToAction"]
    @org.robolectric.annotation.Implementation fun setIconView(view: android.view.View?) { assets["Icon"] = view }
    @org.robolectric.annotation.Implementation fun getIconView(): android.view.View? = assets["Icon"]
    @org.robolectric.annotation.Implementation fun setPriceView(view: android.view.View?) { assets["Price"] = view }
    @org.robolectric.annotation.Implementation fun getPriceView(): android.view.View? = assets["Price"]
    @org.robolectric.annotation.Implementation fun setStarRatingView(view: android.view.View?) { assets["StarRating"] = view }
    @org.robolectric.annotation.Implementation fun getStarRatingView(): android.view.View? = assets["StarRating"]
    @org.robolectric.annotation.Implementation fun setAdvertiserView(view: android.view.View?) { assets["Advertiser"] = view }
    @org.robolectric.annotation.Implementation fun getAdvertiserView(): android.view.View? = assets["Advertiser"]
    @org.robolectric.annotation.Implementation fun setMediaView(view: com.google.android.gms.ads.nativead.MediaView?) {}
}
