package io.onboardkit.ads

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.adnative.NativeAdManager
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.remote.RemoteFlags
import io.onboardkit.ui.language.LanguageAdapter
import io.onboardkit.ui.language.ObLanguageActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo

/** Real LFO Activity, provider, helper and XML; only the GMA loader/registration is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [NativeProviderViewShadow::class],
    instrumentedPackages = ["io.onboardkit.ui.language"])
@LooperMode(LooperMode.Mode.PAUSED)
class LfoNativeShowAuditTest {
    private lateinit var host: ActivityController<NativeProviderHost>
    private var language: ActivityController<ObLanguageActivity>? = null
    private lateinit var provider: ERainAdProvider
    private lateinit var builders: MockedConstruction<AdLoader.Builder>
    private lateinit var connectivity: ConnectivityManager
    private val requests = mutableListOf<Pair<String, NativeAd.OnNativeAdLoadedListener>>()
    private val events = mutableMapOf<String, AdListener>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val activity get() = requireNotNull(language).get()
    private fun block(id: Int = R.id.ob_ad_block) = activity.findViewById<View>(id)
    private fun container() = activity.findViewById<FrameLayout>(R.id.ob_native_container)

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        NativeAdManager.releaseAll()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource { override fun isPremium(context: Context) = false })
        connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        network(true)
        builders = mockConstruction(AdLoader.Builder::class.java) { builder, context ->
            val id = context.arguments()[1] as String
            var loaded: NativeAd.OnNativeAdLoadedListener? = null
            doAnswer { loaded = it.getArgument(0); builder }.`when`(builder).forNativeAd(any())
            doAnswer { events[id] = it.getArgument(0); builder }.`when`(builder).withAdListener(any(AdListener::class.java))
            doReturn(builder).`when`(builder).withNativeAdOptions(any())
            val loader = mock(AdLoader::class.java)
            doReturn(loader).`when`(builder).build()
            doAnswer { requests += id to checkNotNull(loaded); null }.`when`(loader).loadAd(any(AdRequest::class.java))
        }
        provider = OnboardingSdk.provider() as? ERainAdProvider ?: ERainAdProvider()
        provider.releaseAll()
        OnboardingSdk.install(app) { adProvider = provider; trackkitAutoTracking(false) }
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        com.ads.module.config.settings.AdBehavior.document.acceptSuccessfulFetch(null)
        OnboardingSdk.setCanRequestAds(true)
        runBlocking { OnboardingSdk.reset() }
        OnboardingSdk.remoteOrNull()?.applySnapshot(RemoteFlags())
        OnboardingSdk.configure(onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(languageNative = NativeAdUnit("audit-lfo1"), languageDupNative = NativeAdUnit("audit-lfo2"))
        }.getOrThrow()).getOrThrow()
        host = Robolectric.buildActivity(NativeProviderHost::class.java).setup().visible().windowFocusChanged(true)
    }

    @After fun cleanup() {
        language?.pause()?.stop()?.destroy()
        host.pause().stop().destroy()
        provider.releaseAll()
        main.idleFor(61, java.util.concurrent.TimeUnit.SECONDS)
        NativeAdManager.releaseAll()
        builders.close()
        ConsentCenter.clearHostConsent()
        OnboardingSettings.document.acceptSuccessfulFetch(null)
    }

    private fun network(connected: Boolean) {
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            if (connected) NetworkInfo.DetailedState.CONNECTED else NetworkInfo.DetailedState.DISCONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, connected, connected))
        if (connected) shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
    }

    private fun preload() {
        provider.preloadNative(host.get(), NativeAdRequest(AdPlacement.Language1,
            NativeAdUnit("audit-lfo1"), R.layout.ob_layout_native_cta_bottom))
        assertEquals(1, requests.count { it.first == "audit-lfo1" })
    }

    private fun fill(id: String = "audit-lfo1"): NativeAd = mock(NativeAd::class.java).also {
        doReturn("Audit native").`when`(it).headline
        doReturn("Install").`when`(it).callToAction
        requests.single { request -> request.first == id }.second.onNativeAdLoaded(it)
        main.idle()
    }

    private fun create() {
        language = Robolectric.buildActivity(ObLanguageActivity::class.java).create().start()
        main.idle()
    }

    private fun resume() { requireNotNull(language).resume().visible(); main.idle() }

    private fun assertFirstBound() {
        assertEquals(View.VISIBLE, block().visibility)
        assertEquals(View.VISIBLE, container().visibility)
        assertTrue("Real native XML replaces the shimmer", container().getChildAt(0) is NativeAdView)
        assertEquals("No second LFO1 vendor request", 1, requests.count { it.first == "audit-lfo1" })
    }

    private fun tapLanguage() {
        val list = activity.findViewById<RecyclerView>(R.id.ob_language_list)
        val adapter = list.adapter as LanguageAdapter
        adapter.onCreateViewHolder(list, 0).also { adapter.onBindViewHolder(it, 0) }.itemView.performClick()
        main.idle()
    }

    @Test fun `cached LFO1 binds on first resume without focus or advancing a delay`() {
        preload()
        val ad = fill()
        create()
        assertFalse(container().getChildAt(0) is NativeAdView)
        assertTrue(provider.isNativeReady(AdPlacement.Language1))
        resume()
        assertFalse(activity.hasWindowFocus())
        assertFirstBound()
        verify(ad, never()).destroy()
    }

    @Test fun `fill arriving before first resume is retained and bound on resume`() {
        preload()
        create()
        val ad = fill()
        assertFalse(container().getChildAt(0) is NativeAdView)
        resume()
        assertFirstBound()
        verify(ad, never()).destroy()
    }

    @Test fun `fill arriving after resume replaces shimmer immediately`() {
        preload()
        create()
        resume()
        assertFalse(container().getChildAt(0) is NativeAdView)
        fill()
        assertFirstBound()
    }

    @Test fun `fill arriving in background is retained for return`() {
        preload()
        create()
        resume()
        requireNotNull(language).pause().stop()
        val ad = fill()
        assertFalse(container().getChildAt(0) is NativeAdView)
        requireNotNull(language).restart().start()
        resume()
        assertFirstBound()
        verify(ad, never()).destroy()
    }

    @Test fun `cached native still binds if network drops before LFO entry`() {
        preload()
        fill()
        network(false)
        create()
        resume()
        assertFirstBound()
    }

    @Test fun `background return preserves already bound LFO1 without a new request`() {
        preload()
        val first = fill()
        create()
        resume()
        assertFirstBound()
        requireNotNull(language).pause().stop()
        requireNotNull(language).restart().start()
        resume()
        assertFirstBound()
        verify(first, never()).destroy()
    }

    @Test fun `reload enabled preserves pending LFO1 view through stop and consumes background fill once`() {
        OnboardingSettings.document.acceptSuccessfulFetch(
            """{"lfo":{"native1":{"behavior":{"reload":{"allowed":true}}}}}""")
        preload()
        create()
        resume()
        val pendingView = container().getChildAt(0)
        requireNotNull(language).pause().stop()
        assertSame("Stop must not clear a pending native slot", pendingView, container().getChildAt(0))
        val first = fill()
        requireNotNull(language).restart().start()
        resume()
        assertFirstBound()
        main.idleFor(1, java.util.concurrent.TimeUnit.SECONDS)
        assertFirstBound()
        verify(first, never()).destroy()
    }

    @Test fun `reload enabled does not request twice when LFO1 fills while stopped`() {
        OnboardingSettings.document.acceptSuccessfulFetch(
            """{"lfo":{"native1":{"behavior":{"reload":{"allowed":true}}}}}""")
        preload()
        create()
        resume()
        requireNotNull(language).pause().stop()
        fill()
        requireNotNull(language).restart().start()
        resume()
        assertFirstBound()
    }

    @Test fun `click reload retains LFO1 view on stop and return until the same replacement fills`() {
        preload()
        val first = fill()
        create()
        resume()
        val oldView = container().getChildAt(0)
        val clickEvents = events.getValue("audit-lfo1")
        clickEvents.onAdClicked()
        clickEvents.onAdOpened()
        assertEquals("Click and open start only one replacement", 2, requests.count { it.first == "audit-lfo1" })
        requireNotNull(language).pause().stop()
        assertSame(oldView, container().getChildAt(0))
        requireNotNull(language).restart().start()
        resume()
        assertSame(oldView, container().getChildAt(0))
        assertEquals(View.VISIBLE, block().visibility)
        verify(first, never()).destroy()
        assertEquals(2, requests.count { it.first == "audit-lfo1" })
        val replacement = mock(NativeAd::class.java)
        requests.last { it.first == "audit-lfo1" }.second.onNativeAdLoaded(replacement)
        main.idle()
        assertTrue(container().getChildAt(0) is NativeAdView)
        assertNotSame(oldView, container().getChildAt(0))
        verify(first).destroy()
        verify(replacement, never()).destroy()
        requireNotNull(language).pause().stop().restart().start()
        resume()
        assertEquals("Returning again cannot repeat the click reload", 2, requests.count { it.first == "audit-lfo1" })
    }

    @Test fun `native filled after the tier timeout is discarded while LFO remains open`() {
        preload()
        create()
        resume()
        main.idleFor(31, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(View.GONE, block().visibility)
        val late = fill()
        verify(late).destroy()
        assertFalse(provider.isNativeReady(AdPlacement.Language1))
        assertEquals(View.GONE, block().visibility)
        assertFalse(container().getChildAt(0) is NativeAdView)
    }

    @Test fun `fast selection can replace filled LFO1 without a vendor impression`() {
        preload()
        val first = fill()
        create()
        resume()
        requireNotNull(language).windowFocusChanged(true)
        main.idle()
        fill("audit-lfo2")
        assertFirstBound()
        // Deliberately do not emit onAdImpression: a successful bind does not guarantee it.
        tapLanguage()
        assertEquals(View.INVISIBLE, block().visibility)
        assertEquals(View.VISIBLE, block(R.id.ob_ad_block_2).visibility)
        verify(first).destroy()
    }

    @Test fun `selection keeps LFO1 visible until LFO2 actually binds`() {
        preload()
        val first = fill()
        create()
        resume()
        requireNotNull(language).windowFocusChanged(true)
        main.idle()
        tapLanguage()
        assertFirstBound()
        assertEquals(View.GONE, block(R.id.ob_ad_block_2).visibility)
        verify(first, never()).destroy()
        fill("audit-lfo2")
        assertEquals(View.INVISIBLE, block().visibility)
        assertEquals(View.VISIBLE, block(R.id.ob_ad_block_2).visibility)
    }

    @Test fun `native and media receive visible area in the default portrait layout`() {
        preload()
        fill()
        create()
        resume()
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 1920)
        assertFirstBound()
        assertTrue(block().height > 0)
        assertTrue(container().isShown)
        val media = container().findViewById<View>(R.id.ad_media)
        assertTrue("Media must not collapse to zero height", media.height > 0)
        assertTrue("Media is inside the native area", media.height <= container().height)
    }
}
