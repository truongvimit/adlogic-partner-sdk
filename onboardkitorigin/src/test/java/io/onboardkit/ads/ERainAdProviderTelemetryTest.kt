package io.onboardkit.ads

import android.content.Context
import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.adnative.NativeAdPreload
import com.ads.module.helper.adnative.NativeAdConfig
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.BaseAdView
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.BannerAdUnit
import com.facebook.shimmer.ShimmerFrameLayout
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.analytics.AnalyticsHub
import io.onboardkit.core.analytics.TrackkitPlugin
import io.trackkit.TrackSink
import io.trackkit.PlacementRegistry
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowViewGroup

/** Real provider, preload store, waterfall and Tracker; only the external GMA boundary is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ProviderNativeAdViewShadow::class, ProviderBannerVendorShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ERainAdProviderTelemetryTest {
    private lateinit var activity: ComponentActivity
    private lateinit var provider: ERainAdProvider
    private lateinit var vendor: MockedConstruction<AdLoader.Builder>
    private val requests = mutableListOf<NativeRequest>()
    private val sink = RecordingSink()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        NativeAdPreload.getInstance().releaseAll()
        ProviderBannerVendorShadow.requests.clear()
        PlacementRegistry.clear()
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
        Tracker.resetForTesting()
        Tracker.addSink(sink)
        Tracker.install(activity, TrackerConfig(strictValidation = true, logLevel = 0))
        AnalyticsHub.clear()
        AnalyticsHub.addPlugin(TrackkitPlugin)
        provider = ERainAdProvider()
        vendor = Mockito.mockConstruction(AdLoader.Builder::class.java) { builder, _ ->
            val request = NativeRequest()
            Mockito.`when`(builder.forNativeAd(Mockito.any(NativeAd.OnNativeAdLoadedListener::class.java)))
                .thenAnswer { invocation -> request.onLoaded = invocation.getArgument(0); builder }
            Mockito.`when`(builder.withAdListener(Mockito.any(AdListener::class.java)))
                .thenAnswer { invocation -> request.adListener = invocation.getArgument(0); builder }
            Mockito.`when`(builder.withNativeAdOptions(Mockito.any(NativeAdOptions::class.java))).thenReturn(builder)
            val loader = Mockito.mock(AdLoader::class.java)
            Mockito.`when`(builder.build()).thenReturn(loader)
            Mockito.doAnswer { requests += request; null }.`when`(loader).loadAd(Mockito.any(AdRequest::class.java))
        }
    }

    @After
    fun tearDown() {
        provider.releaseAll()
        NativeAdPreload.getInstance().releaseAll()
        ProviderBannerVendorShadow.requests.clear()
        PlacementRegistry.clear()
        vendor.close()
        AnalyticsHub.clear()
        Tracker.resetForTesting()
        activity.finish()
    }

    @Test
    fun `exhausted native waterfall records one failure and still answers the waiting screen`() {
        var screenFailures = 0
        val placement = AdPlacement.Language1
        assertFalse(provider.bindNative(activity, placement, FrameLayout(activity), null,
            placement.tracked(object : AdEventListener {
                override fun onFailedToLoad() { screenFailures++ }
            })))

        provider.preloadNative(activity, NativeAdRequest(
            placement, NativeAdUnit(listOf("high", "fallback")), 0,
        ))
        requests[0].adListener.onAdFailedToLoad(LoadAdError(3, "no fill", "test", null, null))
        requests[1].adListener.onAdFailedToLoad(LoadAdError(3, "no fill", "test", null, null))

        assertEquals(1, screenFailures)
        assertEquals(1, sink.events("ad_load_failed").size)
        assertEquals(1, sink.events("ad_request").size)
        assertEquals(0, sink.events("ad_loaded").size)
        assertEquals(2, sink.events("ad_tier_result").size)
        assertEquals("language1", sink.events("ad_load_failed").single()["placement"])
    }

    @Test
    fun `fullscreen preload retains its placement format through a fallback tier`() {
        provider.preloadNative(activity, NativeAdRequest(
            AdPlacement.Ob5, NativeAdUnit(listOf("high", "fallback")), 0,
        ))
        requests[0].adListener.onAdFailedToLoad(LoadAdError(3, "no fill", "test", null, null))
        requests[1].onLoaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))

        assertEquals(1, sink.events("ad_loaded").size)
        assertEquals("ob5", sink.events("ad_loaded").single()["placement"])
        assertEquals("native_full_screen", sink.events("ad_loaded").single()["ad_format"])
        assertEquals("native_full_screen", sink.events("ad_request").single()["ad_format"])
    }

    @Test
    fun `coalesced preload and cached screen bind do not create extra requests`() {
        val placement = AdPlacement.Language1
        val unit = NativeAdUnit("unit")
        OnboardingSdk.install(activity.application as Application) { adProvider = provider }
        OnboardingSdk.configure(onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(languageNative = unit)
        }.getOrThrow()).getOrThrow()
        val request = NativeAdRequest(placement, unit, NativeTemplates.layoutForPlacement(placement))
        provider.preloadNative(activity, request)
        provider.preloadNative(activity, request)
        assertEquals(1, requests.size)
        requests.single().onLoaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))
        provider.preloadNative(activity, request)
        assertEquals(1, requests.size)

        var bound = 0
        var unavailable = 0
        val container = FrameLayout(activity)
        activity.showNativeAd(placement, unit, container,
            onBound = { bound++ }, onUnavailable = { unavailable++ })

        assertEquals(1, bound)
        assertEquals(0, unavailable)
        assertEquals(1, container.childCount)
        assertFalse(provider.isNativeReady(placement))
        assertEquals(1, sink.events("ad_request").size)
        assertEquals(1, sink.events("ad_loaded").size)
    }

    @Test
    fun `each batch item has its own attempt and retains the context captured when queued`() {
        val preload = NativeAdPreload.getInstance()
        val config = NativeAdConfig("shared-unit", true, false, 0)
        PlacementRegistry.register("shared-unit", "first_placement")
        preload.preloadWithKey("inventory", activity, config, 2)
        PlacementRegistry.register("shared-unit", "queued_placement")
        preload.preloadWithKey("inventory", activity, config, 1)
        PlacementRegistry.register("shared-unit", "unrelated_screen")
        assertEquals(1, requests.size)

        requests[0].onLoaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))
        requests[1].onLoaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))
        requests[2].onLoaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))

        assertEquals(3, requests.size)
        assertFalse(preload.isPreloadInProgress("inventory"))
        assertEquals(3, preload.getNativeAdBuffer("inventory").size)
        assertEquals(listOf("first_placement", "first_placement", "queued_placement"),
            sink.events("ad_request").map { it["placement"] })
        assertEquals(listOf("first_placement", "first_placement", "queued_placement"),
            sink.events("ad_loaded").map { it["placement"] })
        assertEquals(3, sink.events("ad_request").map { it["attempt_id"] }.distinct().size)
        assertEquals(sink.events("ad_request").map { it["attempt_id"] },
            sink.events("ad_loaded").map { it["attempt_id"] })
    }

    @Test
    fun `interstitial provider reports one load terminal and preserves the waiting screen callback`() {
        val callbacks = mutableListOf<InterstitialAdLoadCallback>()
        Mockito.mockStatic(InterstitialAd::class.java).use { interstitialVendor ->
            interstitialVendor.`when`<Unit> {
                InterstitialAd.load(Mockito.any(Context::class.java), Mockito.anyString(),
                    Mockito.any(AdRequest::class.java), Mockito.any(InterstitialAdLoadCallback::class.java))
            }.thenAnswer { invocation -> callbacks += invocation.getArgument<InterstitialAdLoadCallback>(3); null }
            var screenFailures = 0
            val placement = AdPlacement.SplashInterstitial
            provider.loadInterstitial(activity, placement, InterstitialAdUnit(listOf("high", "fallback")),
                placement.tracked(object : AdEventListener {
                    override fun onFailedToLoad() { screenFailures++ }
                }))
            callbacks[0].onAdFailedToLoad(LoadAdError(3, "no fill", "test", null, null))
            callbacks[1].onAdFailedToLoad(LoadAdError(3, "no fill", "test", null, null))

            assertEquals(2, callbacks.size)
            assertEquals(1, screenFailures)
            assertEquals(1, sink.events("ad_request").size)
            assertEquals(1, sink.events("ad_load_failed").size)
            assertEquals(2, sink.events("ad_tier_result").size)
            assertEquals("splash_inter", sink.events("ad_request").single()["placement"])
        }
    }

    @Test
    fun `placement aware banner keeps its semantic placement and one terminal through the provider interface`() {
        val host = FrameLayout(activity)
        host.addView(FrameLayout(activity).apply { id = com.ads.module.R.id.banner_container })
        host.addView(ShimmerFrameLayout(activity).apply { id = com.ads.module.R.id.shimmer_container_banner })
        activity.setContentView(host)
        PlacementRegistry.register("banner-unit", "other_screen")
        val api: OnboardingAdProvider = provider
        val placement = AdPlacement.SplashBanner
        var screenFailures = 0

        api.loadBanner(activity, placement, BannerAdUnit("banner-unit"),
            placement.tracked(object : AdEventListener {
                override fun onFailedToLoad() { screenFailures++ }
            }))
        ProviderBannerVendorShadow.requests.single().onAdFailedToLoad(
            LoadAdError(3, "no fill", "test", null, null))

        assertEquals(1, screenFailures)
        assertEquals(1, sink.events("ad_request").size)
        assertEquals(1, sink.events("ad_load_failed").size)
        assertEquals(1, sink.events("ad_tier_result").size)
        assertEquals("splash_banner", sink.events("ad_request").single()["placement"])
        assertEquals("splash_banner", sink.events("ad_load_failed").single()["placement"])
    }

    @Test
    fun `placement aware banner bridge preserves the original custom provider callback without inventing a request`() {
        var originalCalls = 0
        var screenLoaded = 0
        // A host implementation of the original interface need not implement the new capability.
        val customProvider = object : OnboardingAdProvider by provider {
            override fun loadBanner(
                activity: android.app.Activity,
                unit: BannerAdUnit,
                listener: AdEventListener?,
            ) {
                originalCalls++
                listener?.onLoaded()
            }
        }

        customProvider.loadBanner(activity, AdPlacement.SplashBanner, BannerAdUnit("unit"),
            object : AdEventListener {
                override fun onLoaded() { screenLoaded++ }
            })

        assertEquals(1, originalCalls)
        assertEquals(1, screenLoaded)
        assertEquals(0, ProviderBannerVendorShadow.requests.size)
        assertEquals(0, sink.events("ad_request").size)
        assertEquals(0, sink.events("ad_loaded").size)
    }

    private class NativeRequest {
        lateinit var onLoaded: NativeAd.OnNativeAdLoadedListener
        lateinit var adListener: AdListener
    }

    private class RecordingSink : TrackSink {
        override val id = "provider-recording"
        private val received = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun onEvent(name: String, params: Map<String, Any?>) { received += name to params }
        fun events(name: String) = received.filter { it.first == name }.map { it.second }
    }
}

/** Keep SDK layout inflation/population real; the GMA binder requires a real vendor ad token. */
@Implements(NativeAdView::class)
class ProviderNativeAdViewShadow : ShadowViewGroup() {
    @Implementation
    fun setNativeAd(ad: NativeAd) = Unit
}

/** SDK banner loading remains real; replace only the external GMA dispatch and response token. */
@Implements(BaseAdView::class)
class ProviderBannerVendorShadow : ShadowViewGroup() {
    @RealObject private lateinit var view: BaseAdView

    @Implementation
    fun loadAd(request: AdRequest) { requests += view.adListener!! }

    @Implementation
    fun getResponseInfo(): ResponseInfo = Mockito.mock(ResponseInfo::class.java)

    companion object {
        val requests = mutableListOf<AdListener>()
    }
}
