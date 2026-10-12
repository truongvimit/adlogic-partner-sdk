package io.onboardkit.ads

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.adnative.NativeAdManager
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.TimeUnit

/**
 * Real provider, helper and native store; only the GMA loader and view registration are replaced.
 * A pager page releases its placement only while its own container still holds the slot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [NativeProviderViewShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ScopedNativeReleaseTest {
    private lateinit var controller: ActivityController<NativeProviderHost>
    private lateinit var provider: ERainAdProvider
    private lateinit var builders: MockedConstruction<AdLoader.Builder>
    private val requests = mutableListOf<NativeAd.OnNativeAdLoadedListener>()
    private val vendorEvents = mutableListOf<AdListener>()
    private val page = AdPlacement.StepNative(StepId.OB2)
    private val request = NativeAdRequest(page, NativeAdUnit(listOf("scoped-unit")),
        com.ads.module.R.layout.custom_native_admob_medium)
    private val main get() = shadowOf(Looper.getMainLooper())
    private val host get() = controller.get()

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
        provider = OnboardingSdk.provider() as? ERainAdProvider ?: ERainAdProvider()
        provider.releaseAll()
        OnboardingSdk.install(app) { adProvider = provider; trackkitAutoTracking(false) }
        OnboardingSdk.configure(onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(stepNatives = mapOf(StepId.OB2 to request.unit))
        }.getOrThrow()).getOrThrow()
        controller = Robolectric.buildActivity(NativeProviderHost::class.java).setup().visible().windowFocusChanged(true)
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        provider.releaseAll()
        main.idleFor(61, TimeUnit.SECONDS)
        NativeAdManager.releaseAll()
        builders.close()
        ConsentCenter.clearHostConsent()
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(null)
    }

    private class Heard : AdEventListener {
        val events = mutableListOf<String>()
        override fun onLoaded() { events += "loaded" }
        override fun onFailedToLoad() { events += "failed" }
        override fun onImpression() { events += "impression" }
        override fun onClicked() { events += "clicked" }
        override fun onAdOpened() { events += "opened" }
        override fun onDetached() { events += "detached" }
    }

    /** A new fill bound in a new page container of the host. */
    private fun boundPage(listener: AdEventListener): Pair<FrameLayout, NativeAd> {
        val container = FrameLayout(host).also(host::setContentView)
        provider.preloadNative(host, request)
        val ad = mock(NativeAd::class.java)
        requests.last().onNativeAdLoaded(ad)
        assertTrue(provider.bindNative(host, request, container, listener))
        assertTrue(container.getChildAt(0) is NativeAdView)
        return container to ad
    }

    private fun destroyed(ad: NativeAd) = mockingDetails(ad).invocations.any { it.method.name == "destroy" }

    @Test fun `a late release from a page whose slot was taken over leaves the new page's ad untouched`() {
        val (oldContainer, _) = boundPage(Heard())
        val heard = Heard()
        val (container, ad) = boundPage(heard)
        val shown = container.getChildAt(0)
        assertEquals(2, requests.size)

        provider.releaseNative(page, oldContainer)
        verify(ad, never()).destroy()
        assertSame("The new page keeps its ad view", shown, container.getChildAt(0))
        assertEquals("Its ad stays bound, nothing went back to the store", NativeStatus.IDLE, provider.nativeStatus(page))
        assertTrue(heard.events.isEmpty())

        vendorEvents.last().onAdImpression()
        vendorEvents.last().onAdClicked()
        main.idle()
        assertEquals("Later events still reach the new page", listOf("impression", "clicked"), heard.events)
        verify(ad, never()).destroy()

        provider.releaseNative(page, container)
        verify(ad).destroy()
        assertEquals("The slot was still the new page's own", 0, container.childCount)
    }

    @Test fun `a release scoped to the page's own container releases exactly as the unscoped one`() {
        fun outcome(release: (FrameLayout) -> Unit): List<Any> {
            val heard = Heard()
            val (container, ad) = boundPage(heard)
            release(container)
            val result = listOf(destroyed(ad), container.childCount, provider.nativeStatus(page))
            vendorEvents.last().onAdImpression()
            vendorEvents.last().onAdClicked()
            main.idle()
            return result + listOf(heard.events.toList())
        }
        val unscoped = outcome { provider.releaseNative(page) }
        val scoped = outcome { provider.releaseNative(page, it) }
        assertEquals(listOf(true, 0, NativeStatus.IDLE, listOf("detached")), unscoped)
        assertEquals(unscoped, scoped)
        assertEquals(2, requests.size)
    }

    @Test fun `a release scoped to another container does not cancel the queued preload of the waiting page`() {
        val container = FrameLayout(host).also(host::setContentView)
        val heard = Heard()
        controller.pause().stop()
        assertFalse(provider.bindNative(host, request, container, heard))
        provider.preloadNative(host, request)
        assertEquals(NativeStatus.LOADING, provider.nativeStatus(page))

        provider.releaseNative(page, FrameLayout(host))
        assertEquals("The queue stands", NativeStatus.LOADING, provider.nativeStatus(page))
        controller.restart().start().resume().visible().windowFocusChanged(true)
        main.idle()
        assertEquals(1, requests.size)
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        main.idle()
        assertTrue("The fill reaches the page that waits for it", container.getChildAt(0) is NativeAdView)
        assertEquals(listOf("loaded"), heard.events)
    }

    @Test fun `a release scoped to the waiting page's own container cancels its queued preload as the unscoped one does`() {
        val container = FrameLayout(host).also(host::setContentView)
        val heard = Heard()
        controller.pause().stop()
        assertFalse(provider.bindNative(host, request, container, heard))
        provider.preloadNative(host, request)
        provider.releaseNative(page, container)
        assertEquals(NativeStatus.IDLE, provider.nativeStatus(page))
        controller.restart().start().resume().visible().windowFocusChanged(true)
        main.idle()
        assertEquals(0, requests.size)
        assertEquals(listOf("detached"), heard.events)
    }

    @Test fun `with no slot under the key neither a scoped nor a keeping release cancels a queued preload`() {
        controller.pause().stop()
        provider.preloadNative(host, request)
        provider.releaseNative(page, FrameLayout(host))
        provider.releaseNativeKeepingUnseen(page)
        assertEquals(NativeStatus.LOADING, provider.nativeStatus(page))
        controller.restart().start().resume().visible().windowFocusChanged(true)
        main.idle()
        assertEquals(1, requests.size)
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        assertEquals("The fill waits in the store for the page", NativeStatus.READY, provider.nativeStatus(page))
    }
}
