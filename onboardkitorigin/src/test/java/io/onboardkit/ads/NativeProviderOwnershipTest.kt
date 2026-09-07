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
    private val vendorEvents = mutableListOf<AdListener>()
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
            doAnswer { vendorEvents += it.getArgument<AdListener>(0); builder }.`when`(builder).withAdListener(any(AdListener::class.java))
            doReturn(builder).`when`(builder).withNativeAdOptions(any())
            val loader = mock(AdLoader::class.java)
            doReturn(loader).`when`(builder).build()
            doAnswer { requests += checkNotNull(loaded); null }.`when`(loader).loadAd(any(AdRequest::class.java))
        }
        // OnboardingSdk is process-scoped and install is intentionally idempotent.
        provider = io.onboardkit.OnboardingSdk.provider() as? ERainAdProvider ?: ERainAdProvider()
        provider.releaseAll()
        controller = Robolectric.buildActivity(NativeProviderHost::class.java).setup().visible().windowFocusChanged(true)
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

    @Test fun `new preload while stopped waits for foreground and remains deduplicated`() {
        val host = controller.get()
        controller.pause().stop()
        repeat(3) { provider.preloadNative(host, request) }
        main.idle()
        assertEquals(0, requests.size)
        assertTrue(provider.isNativeLoading(placement))
        controller.restart().start().resume().visible().windowFocusChanged(true)
        main.idle()
        assertEquals(1, requests.size)
    }

    @Test fun `a queued preload transfers to the destination before its old owner dies`() {
        controller.pause().stop()
        provider.preloadNative(controller.get(), request)
        assertEquals(0, requests.size)
        val destination = Robolectric.buildActivity(NativeProviderHost::class.java).setup().visible().windowFocusChanged(true)
        try {
            provider.preloadNative(destination.get(), request)
            assertEquals(1, requests.size)
            controller.destroy()
            provider.preloadNative(destination.get(), request)
            assertEquals("The original queue is gone and the real request is joined", 1, requests.size)
        } finally {
            // Let the normal fixture tearDown destroy the surviving Activity.
            controller = destination
        }
    }

    @Test fun `a queued request rechecks host authorization at actual dispatch`() {
        val host = controller.get()
        io.onboardkit.OnboardingSdk.install(host.application) { adProvider = provider; trackkitAutoTracking(false) }
        io.onboardkit.OnboardingSdk.configure(io.onboardkit.config.onboardKitConfig {
            defaultSteps()
            ads = io.onboardkit.config.AdsConfig(languageNative = request.unit)
        }.getOrThrow())
        io.onboardkit.OnboardingSdk.setCanRequestAds(true)
        controller.pause().stop()
        provider.preloadNative(host, request)
        io.onboardkit.OnboardingSdk.setCanRequestAds(false)
        try {
            controller.restart().start().resume().visible().windowFocusChanged(true)
            main.idle()
            assertEquals("A formerly allowed queue must not bypass the current host gate", 0, requests.size)
            assertFalse(provider.isNativeLoading(placement))
            assertTrue(provider.isNativeLoadFailed(placement))
        } finally {
            io.onboardkit.OnboardingSdk.setCanRequestAds(true)
        }
    }

    @Test fun `language handoff reports failed preload without requesting the same ad again`() {
        val host = controller.get()
        io.onboardkit.OnboardingSdk.install(host.application) {
            adProvider = provider
            trackkitAutoTracking(false)
        }
        io.onboardkit.OnboardingSdk.configure(io.onboardkit.config.onboardKitConfig {
            defaultSteps()
            ads = io.onboardkit.config.AdsConfig(languageNative = request.unit)
        }.getOrThrow())
        provider.preloadNative(host, request)
        vendorEvents.single().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        var failed = 0
        host.showNativeAd(placement, request.unit, FrameLayout(host).also(host::setContentView),
            reuseFailedPreload = true, onUnavailable = { failed++ })
        assertEquals(1, failed)
        assertEquals("Screen entry cannot immediately retry a terminal preload", 1, requests.size)
        host.showNativeAd(placement, request.unit, FrameLayout(host).also(host::setContentView))
        assertEquals("A later explicit attempt remains permitted", 2, requests.size)
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
    @Test fun `bound native forwards each vendor click and open once`() {
        val host = controller.get()
        val container = FrameLayout(host).also(host::setContentView)
        var clicks = 0
        var opens = 0
        provider.preloadNative(host, request)
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        assertTrue(provider.bindNative(host, placement, container, null, object : AdEventListener {
            override fun onClicked() { clicks++ }
            override fun onAdOpened() { opens++ }
        }))
        vendorEvents.single().onAdClicked()
        vendorEvents.single().onAdOpened()
        assertEquals(1, clicks)
        assertEquals(1, opens)
    }

    @Test fun `content pager departure consumes old ad and return joins a pending preload`() {
        verifyPagerReturn(io.onboardkit.ui.onboarding.ContentStepFragment.newInstance(io.onboardkit.core.StepId.OB1, 0),
            AdPlacement.StepNative(io.onboardkit.core.StepId.OB1))
    }

    @Test fun `full screen pager departure consumes old ad and return joins a pending preload`() {
        verifyPagerReturn(io.onboardkit.ui.onboarding.AdStepFragment.newInstance(io.onboardkit.core.StepId.OB3, 0),
            AdPlacement.StepFullScreen(io.onboardkit.core.StepId.OB3))
    }

    private fun verifyPagerReturn(fragment: io.onboardkit.ui.pager.LazyStepFragment, page: AdPlacement) {
        val host = controller.get()
        io.onboardkit.OnboardingSdk.install(host.application) {
            adProvider = provider
            trackkitAutoTracking(false)
        }
        io.onboardkit.OnboardingSdk.configure(io.onboardkit.config.onboardKitConfig {
            defaultSteps()
            ads = io.onboardkit.config.AdsConfig(
                contentStepNative = request.unit, fullScreenStepNative = request.unit)
        }.getOrThrow())
        val parent = FrameLayout(host).apply { id = android.view.View.generateViewId() }
        host.setContentView(parent)
        host.supportFragmentManager.beginTransaction().add(parent.id, fragment).commitNow()
        fragment.dispatchSelected()
        val first = mock(NativeAd::class.java)
        requests.single().onNativeAdLoaded(first)
        assertFalse(provider.isNativeReady(page))
        fragment.dispatchUnselected()
        host.supportFragmentManager.beginTransaction()
            .setMaxLifecycle(fragment, androidx.lifecycle.Lifecycle.State.STARTED).commitNow()
        verify(first).destroy()
        provider.preloadNative(host, request.copy(placement = page))
        assertEquals(2, requests.size)
        host.supportFragmentManager.beginTransaction()
            .setMaxLifecycle(fragment, androidx.lifecycle.Lifecycle.State.RESUMED).commitNow()
        fragment.dispatchSelected()
        assertEquals("return must join the pending preload", 2, requests.size)
        val second = mock(NativeAd::class.java)
        requests.last().onNativeAdLoaded(second)
        assertFalse("return must bind and consume the new fill", provider.isNativeReady(page))
        verify(second, never()).destroy()
    }

    @Test fun `cold fill while paused waits for resume without binding the old view`() {
        val host = controller.get()
        val container = FrameLayout(host).also(host::setContentView)
        var binds = 0
        assertFalse(provider.bindNative(host, placement, container, null, object : AdEventListener {
            override fun onLoaded() {
                if (provider.bindNative(host, placement, container, null)) binds++
            }
        }))
        provider.preloadNative(host, request)
        controller.pause()
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        assertEquals(0, binds)
        assertTrue(provider.isNativeReady(placement))
        controller.resume()
        assertEquals(1, binds)
        assertFalse(provider.isNativeReady(placement))
        assertEquals(1, requests.size)
    }

    @Test fun `cold failure while paused is delivered once after resume`() {
        val host = controller.get()
        val container = FrameLayout(host).also(host::setContentView)
        var failures = 0
        assertFalse(provider.bindNative(host, placement, container, null, object : AdEventListener {
            override fun onFailedToLoad() { failures++ }
        }))
        provider.preloadNative(host, request)
        controller.pause()
        vendorEvents.single().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        assertEquals(0, failures)
        controller.resume()
        assertEquals(1, failures)
        controller.pause().resume()
        assertEquals(1, failures)
    }

    @Test fun `pager rotation restores consumed native through its view lifecycle`() {
        val host = controller.get()
        io.onboardkit.OnboardingSdk.install(host.application) {
            adProvider = provider
            trackkitAutoTracking(false)
        }
        io.onboardkit.OnboardingSdk.configure(io.onboardkit.config.onboardKitConfig {
            defaultSteps()
            ads = io.onboardkit.config.AdsConfig(contentStepNative = request.unit)
        }.getOrThrow())
        val parentId = android.view.View.generateViewId()
        host.setContentView(FrameLayout(host).apply { id = parentId })
        val page = io.onboardkit.ui.onboarding.ContentStepFragment.newInstance(io.onboardkit.core.StepId.OB1, 0)
        host.supportFragmentManager.beginTransaction().add(parentId, page, "native-page").commitNow()
        page.dispatchSelected()
        val ad = mock(NativeAd::class.java)
        requests.single().onNativeAdLoaded(ad)
        NativeProviderHost.onCreated = { recreated ->
            recreated.setContentView(FrameLayout(recreated).apply { id = parentId })
        }
        controller.configurationChange(android.content.res.Configuration(host.resources.configuration).apply {
            orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
        })
        val restored = controller.get().supportFragmentManager.findFragmentByTag("native-page")
            as io.onboardkit.ui.onboarding.ContentStepFragment
        restored.dispatchSelected()
        assertEquals(1, requests.size)
        assertFalse(provider.isNativeReady(AdPlacement.StepNative(io.onboardkit.core.StepId.OB1)))
        verify(ad, never()).destroy()
        assertEquals(1, restored.requireView().findViewById<FrameLayout>(io.onboardkit.R.id.ob_native_container).childCount)
    }

    @Test fun `rotation pending native failure resolves only once`() = verifyPendingRotationOutcome(fail = true)

    @Test fun `helper load failure during pause is deferred until resume`() =
        verifyPendingRotationOutcome(fail = true, pauseBeforeOutcome = true)

    @Test fun `rotation pending native success resolves only once`() = verifyPendingRotationOutcome(fail = false)

    private fun verifyPendingRotationOutcome(fail: Boolean, pauseBeforeOutcome: Boolean = false) {
        val host = controller.get()
        provider.preloadNative(host, request)
        assertFalse(provider.bindNative(host, placement, FrameLayout(host).also(host::setContentView), null))
        var loaded = 0
        var failures = 0
        NativeProviderHost.onCreated = { recreated ->
            val container = FrameLayout(recreated).also(recreated::setContentView)
            val listener = object : AdEventListener {
                override fun onLoaded() {
                    loaded++
                    provider.bindNative(recreated, placement, container, null)
                }
                override fun onFailedToLoad() { failures++ }
            }
            if (!provider.bindNative(recreated, placement, container, null, listener)) {
                provider.preloadNative(recreated, request)
            }
        }
        controller.configurationChange(android.content.res.Configuration(host.resources.configuration).apply {
            orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
        })
        assertEquals(1, requests.size)
        if (pauseBeforeOutcome) controller.pause()
        if (fail) vendorEvents.single().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        else requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        if (pauseBeforeOutcome) {
            assertEquals(0, failures)
            controller.resume()
        }
        assertEquals(if (fail) 0 else 1, loaded)
        assertEquals(if (fail) 1 else 0, failures)
    }

    @Test fun `preloading after display keeps the next fill unused until an explicit bind`() {
        val host = controller.get()
        val container = FrameLayout(host).also(host::setContentView)
        var failures = 0
        val listener = object : AdEventListener {
            override fun onLoaded() { provider.bindNative(host, placement, container, null) }
            override fun onFailedToLoad() { failures++ }
        }
        assertFalse(provider.bindNative(host, placement, container, null, listener))
        provider.preloadNative(host, request)
        val first = mock(NativeAd::class.java)
        requests.single().onNativeAdLoaded(first)
        provider.preloadNative(host, request)
        vendorEvents.last().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        assertEquals("prewarm failure cannot fail a completed display attempt", 0, failures)
        provider.preloadNative(host, request)
        val second = mock(NativeAd::class.java)
        requests.last().onNativeAdLoaded(second)
        assertTrue(provider.isNativeReady(placement))
        verify(first, never()).destroy()
        assertTrue(provider.bindNative(host, placement, container, null))
        assertFalse(provider.isNativeReady(placement))
        verify(first).destroy()
        verify(second, never()).destroy()
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
