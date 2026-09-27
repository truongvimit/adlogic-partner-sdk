package io.onboardkit.ads

import android.view.View

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
    private val requestedUnits = mutableListOf<String>()
    private val vendorEvents = mutableListOf<AdListener>()
    private val placement = AdPlacement.Language1
    private val request = NativeAdRequest(placement, NativeAdUnit(listOf("native-test")),
        com.ads.module.R.layout.custom_native_admob_medium)
    private val main get() = shadowOf(Looper.getMainLooper())
    private val silent = object : AdEventListener {}

    private fun bind(
        container: FrameLayout,
        page: AdPlacement = placement,
        listener: AdEventListener = silent,
        activity: androidx.activity.ComponentActivity = controller.get(),
        layoutRes: Int = request.layoutRes,
    ) = provider.bindNative(activity, request.copy(placement = page, layoutRes = layoutRes), container, listener)

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
        requestedUnits.clear()
        builders = mockConstruction(AdLoader.Builder::class.java) { builder, construction ->
            var loaded: NativeAd.OnNativeAdLoadedListener? = null
            doAnswer { loaded = it.getArgument(0); builder }.`when`(builder).forNativeAd(any())
            doAnswer { vendorEvents += it.getArgument<AdListener>(0); builder }.`when`(builder).withAdListener(any(AdListener::class.java))
            doReturn(builder).`when`(builder).withNativeAdOptions(any())
            val loader = mock(AdLoader::class.java)
            doReturn(loader).`when`(builder).build()
            doAnswer {
                requests += checkNotNull(loaded)
                requestedUnits += construction.arguments()[1] as String
                null
            }.`when`(loader).loadAd(any(AdRequest::class.java))
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
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(null)
    }

    @Test fun `remote template and CTA radius reach a native filled before splash fetch without another load`() {
        verifyTemplateAfterPreload(fillBeforeFetch = true)
    }

    @Test fun `remote template reaches a waiting helper when the fill arrives after splash fetch`() {
        verifyTemplateAfterPreload(fillBeforeFetch = false)
    }

    @Test fun `an existing helper refreshes presentation for the next preloaded fill`() {
        verifyTemplateAfterPreload(fillBeforeFetch = true, refreshForNextFill = true)
    }

    private fun verifyTemplateAfterPreload(fillBeforeFetch: Boolean, refreshForNextFill: Boolean = false) {
        val sdk = io.onboardkit.OnboardingSdk
        val settings = io.onboardkit.remote.OnboardingSettings
        val adConfig = com.ads.module.config.AdRemoteConfig
        val behavior = com.ads.module.config.settings.AdBehavior
        val host = controller.get()
        sdk.install(host.application) { adProvider = provider; trackkitAutoTracking(false) }
        sdk.configure(io.onboardkit.config.onboardKitConfig {
            ads = io.onboardkit.config.AdsConfig.fromAdConfig()
        }.getOrThrow())
        adConfig.update(com.ads.module.config.AdRemoteConfig(mapOf("native_lang" to
            com.ads.module.config.AdUnitConfig("native-test", true))))
        val container = FrameLayout(host).also(host::setContentView)
        val ad = mock(NativeAd::class.java)
        doReturn("Install").`when`(ad).callToAction
        try {
            settings.document.acceptSuccessfulFetch(null)
            behavior.document.acceptSuccessfulFetch(null)
            val template = io.onboardkit.R.layout.ob_layout_native_cta_bottom
            provider.preloadNative(host, request.copy(layoutRes = template))
            if (fillBeforeFetch) requests.single().onNativeAdLoaded(ad)
            else assertFalse(bind(container, layoutRes = template))
            settings.document.acceptSuccessfulFetch("""{"lfo":{"native_template":"CTA_TOP","native1":{"behavior":{"presentation":{"cta_corner_radius_dp":7}}}}}""")
            behavior.document.acceptSuccessfulFetch("""{"native":{"presentation":{"cta_corner_radius_dp":3}}}""")
            adConfig.update(com.ads.module.config.AdRemoteConfig(mapOf("native_lang" to
                com.ads.module.config.AdUnitConfig("native-test", true, colorCTA = "#ff0000"))))
            if (fillBeforeFetch) assertTrue(bind(container, layoutRes = template))
            else requests.single().onNativeAdLoaded(ad)
            assertEquals("Keep the already requested ad", 1, requests.size)
            val root = container.getChildAt(0) as com.google.android.gms.ads.nativead.NativeAdView
            val column = root.getChildAt(0) as android.widget.LinearLayout
            assertEquals("The CTA_TOP frame must actually be inflated", io.onboardkit.R.id.ad_call_to_action, column.getChildAt(0).id)
            val background = column.getChildAt(0).background as android.graphics.drawable.GradientDrawable
            assertEquals((7 * host.resources.displayMetrics.density).toInt().toFloat(), background.cornerRadius, 0f)
            assertEquals("Remote colorCTA reaches the bound ad", 0xFFFF0000.toInt(), background.color?.defaultColor)
            verify(ad, never()).destroy()
            if (refreshForNextFill) {
                provider.preloadNative(host, request.copy(layoutRes = io.onboardkit.R.layout.ob_layout_native_cta_top))
                val replacement = mock(NativeAd::class.java)
                doReturn("Install").`when`(replacement).callToAction
                requests.last().onNativeAdLoaded(replacement)
                settings.document.acceptSuccessfulFetch("""{"lfo":{"native_template":"COMPACT","native1":{"behavior":{"presentation":{"cta_corner_radius_dp":9}}}}}""")
                assertTrue(bind(container, layoutRes = template))
                val cta = container.findViewById<android.view.View>(io.onboardkit.R.id.ad_call_to_action)
                val ctaBackground = cta.background as android.graphics.drawable.GradientDrawable
                assertEquals((9 * host.resources.displayMetrics.density).toInt().toFloat(), ctaBackground.cornerRadius, 0f)
                assertEquals(0xFFFF0000.toInt(), ctaBackground.color?.defaultColor)
                assertEquals("Only the explicitly requested replacement was loaded", 2, requests.size)
                verify(ad).destroy()
                verify(replacement, never()).destroy()
            }
        } finally {
            settings.document.acceptSuccessfulFetch(null)
            behavior.document.acceptSuccessfulFetch(null)
            adConfig.reset()
        }
    }

    @Test fun `remote CTA colour reaches a native whose placement has no configured key`() {
        val sdk = io.onboardkit.OnboardingSdk
        val adConfig = com.ads.module.config.AdRemoteConfig
        val host = controller.get()
        sdk.install(host.application) { adProvider = provider; trackkitAutoTracking(false) }
        sdk.configure(io.onboardkit.config.onboardKitConfig {
            ads = io.onboardkit.config.AdsConfig(languageNative = request.unit)
        }.getOrThrow())
        val template = io.onboardkit.R.layout.ob_layout_native_cta_bottom
        val container = FrameLayout(host).also(host::setContentView)
        val ad = mock(NativeAd::class.java)
        doReturn("Install").`when`(ad).callToAction
        try {
            assertNull(sdk.configuredPlacementKey(placement))
            adConfig.update(com.ads.module.config.AdRemoteConfig(mapOf("some_native" to
                com.ads.module.config.AdUnitConfig("native-test", true, colorCTA = "#00ff00"))))
            provider.preloadNative(host, request.copy(layoutRes = template))
            requests.single().onNativeAdLoaded(ad)
            assertTrue(bind(container, layoutRes = template))
            val cta = container.findViewById<android.view.View>(io.onboardkit.R.id.ad_call_to_action)
            assertEquals(0xFF00FF00.toInt(),
                (cta.background as android.graphics.drawable.GradientDrawable).color?.defaultColor)
        } finally {
            adConfig.reset()
        }
    }

    @Test fun `remote SDK template cannot replace a custom layout supplied by the host`() {
        val sdk = io.onboardkit.OnboardingSdk
        val settings = io.onboardkit.remote.OnboardingSettings
        val host = controller.get()
        sdk.install(host.application) { adProvider = provider; trackkitAutoTracking(false) }
        sdk.configure(io.onboardkit.config.onboardKitConfig {
            ads = io.onboardkit.config.AdsConfig(languageNative = request.unit)
        }.getOrThrow())
        val container = FrameLayout(host).also(host::setContentView)
        try {
            settings.document.acceptSuccessfulFetch("""{"lfo":{"native_template":"CTA_TOP"}}""")
            provider.preloadNative(host, request)
            requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
            assertTrue(bind(container))
            val root = container.getChildAt(0) as com.google.android.gms.ads.nativead.NativeAdView
            assertEquals(com.ads.module.R.id.ad_container, root.getChildAt(0).id)
            assertEquals(1, requests.size)
        } finally {
            settings.document.acceptSuccessfulFetch(null)
        }
    }

    @Test fun `the splash banner reports its load, click and failure to the slot listener`() {
        val host = controller.get()
        val ads = mock(com.ads.module.ads.ERainAd::class.java)
        doReturn(true).`when`(ads).shouldDisplayForUa(anyBoolean())
        var callback: com.ads.module.funtion.AdCallback? = null
        doAnswer {
            callback = it.getArgument(2)
            null
        }.`when`(ads).loadBanner(eq(host), eq("banner-unit"), any(com.ads.module.funtion.AdCallback::class.java))
        val events = mutableListOf<String>()
        val listener = object : AdEventListener {
            override fun onLoaded() { events += "loaded" }
            override fun onFailedToLoad() { events += "failed" }
            override fun onClicked() { events += "clicked" }
        }
        mockStatic(com.ads.module.ads.ERainAd::class.java).use { singleton ->
            singleton.`when`<com.ads.module.ads.ERainAd> { com.ads.module.ads.ERainAd.getInstance() }.thenReturn(ads)
            provider.loadBanner(host, io.onboardkit.config.BannerAdUnit("banner-unit"), listener)
        }
        checkNotNull(callback).onAdLoaded()
        checkNotNull(callback).onAdClicked()
        checkNotNull(callback).onAdFailedToLoad(null)
        assertEquals(listOf("loaded", "clicked", "failed"), events)
    }

    @Test fun `the splash banner loads the unit ad_config declares under its configured key`() {
        val sdk = io.onboardkit.OnboardingSdk
        val adConfig = com.ads.module.config.AdRemoteConfig
        val host = controller.get()
        sdk.install(host.application) { adProvider = provider; trackkitAutoTracking(false) }
        sdk.configure(io.onboardkit.config.onboardKitConfig {
            ads = io.onboardkit.config.AdsConfig.fromAdConfig()
        }.getOrThrow())
        val ads = mock(com.ads.module.ads.ERainAd::class.java)
        doReturn(true).`when`(ads).shouldDisplayForUa(anyBoolean())
        val units = mutableListOf<String>()
        doAnswer {
            units += it.getArgument<String>(1)
            null
        }.`when`(ads).loadBanner(eq(host), anyString(), any(com.ads.module.funtion.AdCallback::class.java))
        try {
            val key = checkNotNull(sdk.configuredPlacementKey(AdPlacement.SplashBanner))
            adConfig.update(com.ads.module.config.AdRemoteConfig(mapOf(key to
                com.ads.module.config.AdUnitConfig("declared-banner", true))))
            mockStatic(com.ads.module.ads.ERainAd::class.java).use { singleton ->
                singleton.`when`<com.ads.module.ads.ERainAd> { com.ads.module.ads.ERainAd.getInstance() }.thenReturn(ads)
                provider.loadBanner(host, io.onboardkit.config.BannerAdUnit("banner-unit"), silent)
            }
            assertEquals(listOf("declared-banner"), units)
        } finally {
            adConfig.reset()
        }
    }

    @Test fun `a native tier gets thirty seconds before the next tier is requested`() {
        provider.preloadNative(controller.get(), request.copy(unit = NativeAdUnit(listOf("native-high", "native-base"))))
        assertEquals(1, requests.size)
        main.idleFor(29_999, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
        main.idleFor(1, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(2, requests.size)
    }

    @Test fun `new preload while stopped waits for foreground and remains deduplicated`() {
        val host = controller.get()
        io.onboardkit.OnboardingSdk.install(host.application) { adProvider = provider; trackkitAutoTracking(false) }
        io.onboardkit.OnboardingSdk.configure(io.onboardkit.config.onboardKitConfig {
            defaultSteps()
            ads = io.onboardkit.config.AdsConfig(languageNative = request.unit)
        }.getOrThrow())
        controller.pause().stop().windowFocusChanged(false)
        repeat(3) { provider.preloadNative(host, request) }
        main.idle()
        assertEquals(0, requests.size)
        assertEquals(NativeStatus.LOADING, provider.nativeStatus(placement))
        controller.restart().start()
        main.idle()
        assertEquals("Started is not enough for an ordinary request", 0, requests.size)
        controller.resume().visible()
        main.idle()
        assertEquals("Resumed without window focus is not enough either", 0, requests.size)
        controller.windowFocusChanged(true)
        main.idle()
        assertEquals(1, requests.size)
    }

    @Test fun `a queued request allowed under the splash prompt goes out once its host is started`() {
        val host = controller.get()
        configureLanguageNative(host)
        controller.pause().stop()
        provider.preloadNative(host, request.copy(allowWhileVisible = true))
        main.idle()
        assertEquals(0, requests.size)
        controller.restart().start()
        main.idle()
        assertEquals(1, requests.size)
        controller.resume().visible().windowFocusChanged(true)
        main.idle()
        assertEquals(1, requests.size)
    }

    @Test fun `a queued request rechecks consent at actual dispatch`() {
        val host = controller.get()
        configureLanguageNative(host)
        controller.pause().stop()
        provider.preloadNative(host, request)
        ConsentCenter.setHostConsent(false, false)
        try {
            controller.restart().start().resume().visible().windowFocusChanged(true)
            main.idle()
            assertEquals("A queue made before consent was withdrawn must not bypass it", 0, requests.size)
            assertNotEquals(NativeStatus.LOADING, provider.nativeStatus(placement))
            assertEquals("A refused request is not a failed one", NativeStatus.IDLE, provider.nativeStatus(placement))
        } finally {
            ConsentCenter.setHostConsent(true, false)
        }
    }

    @Test fun `a slot whose queued request is refused at dispatch ends unavailable once without a request`() {
        configureLanguageNative(controller.get())
        val unfocused = Robolectric.buildActivity(NativeProviderHost::class.java).setup()
        val reasons = mutableListOf<AdSkipReason>()
        try {
            val screen = unfocused.get()
            screen.showNativeAd(placement, request.unit, FrameLayout(screen).also(screen::setContentView),
                onBound = { fail("A refused request cannot bind") }, onUnavailable = { reasons += it })
            main.idle()
            assertEquals(0, requests.size)
            ConsentCenter.setHostConsent(false, false)
            unfocused.windowFocusChanged(true)
            main.idle()
            assertEquals(listOf(AdSkipReason.NO_FILL), reasons)
            assertEquals(0, requests.size)
            assertEquals(NativeStatus.IDLE, provider.nativeStatus(placement))
        } finally {
            ConsentCenter.setHostConsent(true, false)
            unfocused.pause().stop().destroy()
        }
    }

    @Test fun `a queued preload dies with its owner and reads idle before the next splash exists`() {
        val host = controller.get()
        configureLanguageNative(host)
        controller.pause().stop()
        provider.preloadNative(host, request)
        assertEquals(NativeStatus.LOADING, provider.nativeStatus(placement))
        controller.destroy()
        assertEquals(NativeStatus.IDLE, provider.nativeStatus(placement))
        val next = Robolectric.buildActivity(NativeProviderHost::class.java).setup().visible().windowFocusChanged(true)
        try {
            main.idle()
            assertEquals("Nothing dispatches from the dead queue", 0, requests.size)
            assertEquals(NativeStatus.IDLE, provider.nativeStatus(placement))
        } finally {
            controller = next
        }
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

    @Test fun `queued OB preload rechecks disabled removed and blank placement IDs before spending a request`() {
        val sdk = io.onboardkit.OnboardingSdk
        val host = controller.get()
        sdk.install(host.application) { adProvider = provider; trackkitAutoTracking(false) }
        sdk.configure(io.onboardkit.config.onboardKitConfig { defaultSteps() }.getOrThrow()).getOrThrow()
        val adConfig = com.ads.module.config.AdRemoteConfig
        val slot = AdPlacement.StepNative(io.onboardkit.core.StepId.OB2)
        val oldRequest = request.copy(placement = slot)
        try {
            val blocked = listOf(
                emptyMap(),
                mapOf("native_ob2" to com.ads.module.config.AdUnitConfig("native-test", false),
                    "native_ob2_high" to com.ads.module.config.AdUnitConfig("high", true)),
                mapOf("native_ob2" to com.ads.module.config.AdUnitConfig(" ", true)),
            )
            blocked.forEach { entries ->
                adConfig.update(com.ads.module.config.AdRemoteConfig(mapOf("native_ob2" to
                    com.ads.module.config.AdUnitConfig("native-test", true))))
                controller.pause().stop()
                provider.preloadNative(host, oldRequest)
                assertEquals(NativeStatus.LOADING, provider.nativeStatus(slot))
                adConfig.update(com.ads.module.config.AdRemoteConfig(entries))
                controller.restart().start().resume().visible().windowFocusChanged(true)
                main.idle()
                assertEquals("A queued placement that cannot show must never reach GMA", 0, requests.size)
                assertNotEquals(NativeStatus.LOADING, provider.nativeStatus(slot))
                assertEquals("A refused request is not a failed one", NativeStatus.IDLE, provider.nativeStatus(slot))
                provider.releaseNative(slot)
            }
        } finally {
            adConfig.reset()
        }
    }

    @Test fun `every eligible OB preload can bind its fill without issuing another vendor request`() {
        val sdk = io.onboardkit.OnboardingSdk
        val host = controller.get()
        sdk.install(host.application) { adProvider = provider; trackkitAutoTracking(false) }
        sdk.configure(io.onboardkit.config.onboardKitConfig { defaultSteps() }.getOrThrow()).getOrThrow()
        sdk.preload().beginSplashAttempt("all-six-bind")
        val ids = listOf("ob1", "full1", "ob2", "full2", "ob3", "ob4")
        val adConfig = com.ads.module.config.AdRemoteConfig
        try {
            adConfig.update(com.ads.module.config.AdRemoteConfig(ids.associate {
                "native_$it" to com.ads.module.config.AdUnitConfig("native-$it", true)
            }))
            sdk.preload().onLanguageSelected(host)
            assertEquals(6, requests.size)
            ids.forEachIndexed { index, id ->
                val ad = mock(NativeAd::class.java)
                requests[index].onNativeAdLoaded(ad)
                val slot = if (id.startsWith("full")) AdPlacement.StepFullScreen(io.onboardkit.core.StepId(id))
                    else AdPlacement.StepNative(io.onboardkit.core.StepId(id))
                val container = FrameLayout(host).also(host::setContentView)
                var bound = false
                host.showNativeAd(slot, sdk.requireConfig().ads.nativeUnitFor(slot), container,
                    onBound = { bound = true }, onUnavailable = { fail("$id was preloaded but rejected: $it") })
                assertTrue("$id must bind the preloaded ad", bound)
                assertEquals(6, requests.size)
                provider.releaseNative(slot)
            }
        } finally {
            adConfig.reset()
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
            override fun onLoaded() { binds++ }
            override fun onImpression() { shown++ }
        }
        assertFalse(bind(container, listener = listener))
        provider.preloadNative(host, request)
        val ad = mock(NativeAd::class.java)
        doReturn("Native ad").`when`(ad).headline
        requests.single().onNativeAdLoaded(ad)
        assertEquals(org.robolectric.shadows.ShadowLog.getLogs().filter { it.tag == "NativeAdHelper" }.joinToString("\n") { it.throwable?.stackTraceToString().orEmpty() }, 1, binds)
        assertEquals(0, shown)
        vendorEvents.single().onAdImpression()
        assertEquals(1, shown)
        assertEquals(1, container.childCount)
        assertNotEquals(NativeStatus.READY, provider.nativeStatus(placement))
        verify(ad, never()).destroy()
        controller.pause().stop()
        verify(ad, never()).destroy()
        controller.restart().start().resume()
        assertEquals(1, requests.size)
        provider.releaseNative(placement)
        verify(ad).destroy()
    }

    @Test fun `a retry after the units changed binds a slot built from the current units`() {
        val host = controller.get()
        val container = FrameLayout(host).also(host::setContentView)
        val stale = request.copy(unit = NativeAdUnit(listOf("native-stale")))
        val current = request.copy(unit = NativeAdUnit(listOf("native-current")))
        assertFalse(provider.bindNative(host, stale, container, silent))
        provider.preloadNative(host, stale)
        vendorEvents.single().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        provider.preloadNative(host, current)
        requests.last().onNativeAdLoaded(mock(NativeAd::class.java))
        assertTrue(provider.bindNative(host, current, container, silent))
        vendorEvents.last().onAdClicked()
        assertEquals(listOf("native-stale", "native-current", "native-current"), requestedUnits)
    }

    @Test fun `a retry with another layout binds a slot built from that layout`() {
        val host = controller.get()
        val container = FrameLayout(host).also(host::setContentView)
        val medium = com.ads.module.R.layout.custom_native_admob_medium
        val freeSize = com.ads.module.R.layout.custom_native_admob_free_size
        assertFalse(bind(container, layoutRes = medium))
        provider.preloadNative(host, request)
        vendorEvents.single().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        provider.preloadNative(host, request)
        requests.last().onNativeAdLoaded(mock(NativeAd::class.java))
        assertTrue(bind(container, layoutRes = freeSize))
        assertNotNull(container.findViewById<View>(com.ads.module.R.id.ad_media))
    }

    @Test fun `bound native forwards each vendor click and open once`() {
        val host = controller.get()
        val container = FrameLayout(host).also(host::setContentView)
        var clicks = 0
        var opens = 0
        provider.preloadNative(host, request)
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        assertTrue(bind(container, listener = object : AdEventListener {
            override fun onClicked() { clicks++ }
            override fun onAdOpened() { opens++ }
        }))
        vendorEvents.single().onAdClicked()
        vendorEvents.first().onAdOpened()
        assertEquals(1, clicks)
        assertEquals(1, opens)
    }

    @Test fun `all step placements disable click replacement on pause-only and stopped returns`() {
        val host = controller.get()
        val pages = listOf(
            AdPlacement.StepNative(io.onboardkit.core.StepId.OB1),
            AdPlacement.StepNative(io.onboardkit.core.StepId.OB2),
            AdPlacement.StepFullScreen(io.onboardkit.core.StepId.OB3),
            AdPlacement.StepNative(io.onboardkit.core.StepId.OB4),
        )
        pages.forEach { page ->
            val container = FrameLayout(host).also(host::setContentView)
            provider.preloadNative(host, request.copy(placement = page))
            val first = mock(NativeAd::class.java)
            requests.last().onNativeAdLoaded(first)
            var shown = 0
            assertTrue(bind(container, page, object : AdEventListener {
                override fun onImpression() { shown++ }
            }))
            val count = requests.size
            val events = vendorEvents.last()
            events.onAdImpression()
            events.onAdClicked()
            controller.pause().resume()
            main.idleFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)
            assertEquals(page.key, count, requests.size)
            events.onAdOpened()
            controller.pause().stop().restart().start().resume()
            main.idleFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)
            assertEquals(page.key, count, requests.size)
            assertEquals(page.key, 1, shown)
            provider.releaseNative(page)
            verify(first).destroy()
        }
    }

    @Test fun `language popup and question preload at click and bind only on return`() {
        val host = controller.get()
        listOf(AdPlacement.Language1, AdPlacement.Language2, AdPlacement.LanguageConfirm,
            AdPlacement.QuestionNative, AdPlacement.SplashNative, AdPlacement.Ob5).forEach { page ->
            val container = FrameLayout(host).also(host::setContentView)
            provider.preloadNative(host, request.copy(placement = page))
            requests.last().onNativeAdLoaded(mock(NativeAd::class.java))
            var shown = 0
            assertTrue(bind(container, page, object : AdEventListener {
                override fun onImpression() { shown++ }
            }))
            val count = requests.size
            vendorEvents.last().onAdImpression()
            vendorEvents.last().onAdClicked()
            assertEquals(page.key, count + 1, requests.size)
            requests.last().onNativeAdLoaded(mock(NativeAd::class.java))
            assertEquals("No bind before departure: ${page.key}", 1, shown)
            assertEquals(NativeStatus.READY, provider.nativeStatus(page))
            controller.pause().stop().restart().start().resume()
            assertEquals("Replacement bind alone is not another impression: ${page.key}", 1, shown)
            vendorEvents.last().onAdImpression()
            assertEquals(page.key, 2, shown)
            assertEquals(page.key, count + 1, requests.size)
            assertNotEquals(NativeStatus.READY, provider.nativeStatus(page))
            provider.releaseNative(page)
        }
    }

    @Test fun `LFO2 auto next emits no replacement requests even with legacy reload and refresh enabled`() {
        val host = controller.get()
        val page = AdPlacement.Language2
        for (timerEnabled in listOf(false, true)) {
            assertTrue(io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch("""
                {"lfo":{"native2":{"behavior":{
                  "click":{"action":"auto_next"},
                  "reload":{"on_ad_click":true,"allowed":true,"timer_enabled":$timerEnabled,"interval_ms":5000}
                }}}}
            """.trimIndent()))
            val container = FrameLayout(host).also(host::setContentView)
            provider.preloadNative(host, request.copy(placement = page))
            requests.last().onNativeAdLoaded(mock(NativeAd::class.java))
            assertTrue(bind(container, page))
            val baseline = requests.size
            val events = vendorEvents.last()
            for (stopped in listOf(false, true)) {
                events.onAdClicked()
                events.onAdOpened()
                assertEquals("No click/open replacement", baseline, requests.size)
                controller.pause()
                if (stopped) controller.stop()
                main.idleFor(20, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals("No background replacement", baseline, requests.size)
                if (stopped) controller.restart().start()
                controller.resume()
                // Leave the helper alive longer than both timer and resume debounce;
                // real auto-next navigation releases it much earlier.
                main.idleFor(20, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals("No resume or delayed replacement", baseline, requests.size)
            }
            provider.releaseNative(page)
        }
    }

    @Test fun `language click replacement failure keeps the displayed slot and can retry`() {
        val host = controller.get()
        io.onboardkit.OnboardingSdk.install(host.application) {
            adProvider = provider
            trackkitAutoTracking(false)
        }
        io.onboardkit.OnboardingSdk.configure(io.onboardkit.config.onboardKitConfig {
            defaultSteps()
            ads = io.onboardkit.config.AdsConfig(languageNative = request.unit)
        }.getOrThrow())
        val container = FrameLayout(host).also(host::setContentView)
        val page = AdPlacement.Language1
        provider.preloadNative(host, request.copy(placement = page))
        val first = mock(NativeAd::class.java)
        requests.last().onNativeAdLoaded(first)
        var unavailable = 0
        host.showNativeAd(page, request.unit, container, onUnavailable = {
            unavailable++
            container.visibility = View.GONE
        })
        val oldView = container.getChildAt(0)
        assertNotNull(oldView)
        vendorEvents.first().onAdClicked()
        controller.pause().stop().restart().start().resume()
        assertSame(oldView, container.getChildAt(0))
        assertEquals(View.VISIBLE, container.visibility)
        vendorEvents.last().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        assertEquals(0, unavailable)
        assertSame(oldView, container.getChildAt(0))
        assertEquals(View.VISIBLE, container.visibility)
        verify(first, never()).destroy()
        vendorEvents.first().onAdClicked()
        controller.pause().resume()
        requests.last().onNativeAdLoaded(mock(NativeAd::class.java))
        assertNotSame(oldView, container.getChildAt(0))
        verify(first).destroy()
        assertEquals(0, unavailable)
    }

    @Test fun `step click never reloads even when remote requests reload`() {
        val host = controller.get()
        val page = AdPlacement.StepNative(io.onboardkit.core.StepId.OB1)
        val settings = io.onboardkit.remote.OnboardingSettings.document
        val container = FrameLayout(host).also(host::setContentView)
        provider.preloadNative(host, request.copy(placement = page))
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        assertTrue(bind(container, page))
        settings.acceptSuccessfulFetch("""{"onboarding":{"steps":{"ob1":{"behavior":{"click":{"action":"auto_next"},"reload":{"on_ad_click":true}}}}}}""")
        vendorEvents.single().onAdClicked()
        assertEquals(1, requests.size)
        assertEquals(com.ads.module.helper.adnative.NativeClickAction.AUTO_NEXT, provider.pendingClickAction(page))
        settings.acceptSuccessfulFetch("""{"onboarding":{"steps":{"ob1":{"behavior":{"click":{"action":"reload"}}}}}}""")
        vendorEvents.single().onAdOpened()
        assertEquals(com.ads.module.helper.adnative.NativeClickAction.AUTO_NEXT, provider.pendingClickAction(page))
        controller.pause().stop().restart().start().resume()
        assertEquals(1, requests.size)
        vendorEvents.single().onAdClicked()
        assertEquals("Onboarding never reloads its consumed slot", 1, requests.size)
        assertEquals(com.ads.module.helper.adnative.NativeClickAction.NONE, provider.pendingClickAction(page))
        settings.acceptSuccessfulFetch("""{"onboarding":{"steps":{"ob1":{"behavior":{"click":{"action":"none"}}}}}}""")
        controller.pause().resume()
        assertEquals(1, requests.size)
        assertNotEquals(NativeStatus.READY, provider.nativeStatus(page))
    }

    @Test fun `remote preload switches at every scope leave a preloaded language native on its placement key`() {
        val host = controller.get()
        io.onboardkit.OnboardingSdk.install(host.application) { adProvider = provider; trackkitAutoTracking(false) }
        io.onboardkit.OnboardingSdk.configure(io.onboardkit.config.onboardKitConfig {
            defaultSteps()
            ads = io.onboardkit.config.AdsConfig(languageNative = request.unit)
        }.getOrThrow())
        val behavior = com.ads.module.config.settings.AdBehavior.document
        behavior.acceptSuccessfulFetch("""{"native":{"preload":{"enabled":true}},""" +
            """"placement_overrides":{"native_lang":{"native":{"preload":{"enabled":true}}}}}""")
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(
            """{"lfo":{"native1":{"behavior":{"preload":{"enabled":true}}}}}""")
        val language = Robolectric.buildActivity(NativeProviderHost::class.java)
        try {
            provider.preloadNative(host, request)
            requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
            language.create().start()
            val container = FrameLayout(language.get()).also(language.get()::setContentView)
            bind(container, activity = language.get())
            language.resume().visible().windowFocusChanged(true)
            main.idle()
            assertEquals(1, requests.size)
            assertNotEquals("The first resume must bind the preloaded fill", NativeStatus.READY, provider.nativeStatus(placement))
            assertTrue(container.getChildAt(0) is com.google.android.gms.ads.nativead.NativeAdView)
        } finally {
            language.pause().stop().destroy()
            behavior.acceptSuccessfulFetch(null)
        }
    }

    @Test fun `a fill that cannot be drawn ends the native attempt as unavailable`() {
        val host = controller.get()
        val broken = android.R.layout.simple_list_item_1
        val container = FrameLayout(host).also(host::setContentView)
        val outcomes = Outcomes()
        provider.preloadNative(host, request.copy(layoutRes = broken))
        assertFalse(bind(container, listener = outcomes, layoutRes = broken))
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        main.idle()
        assertEquals(0, outcomes.loaded)
        assertEquals(1, outcomes.failures)
        assertEquals(1, requests.size)
    }

    @Test fun `a buffered fill that cannot be drawn is answered by the bind result alone`() {
        val host = controller.get()
        val broken = android.R.layout.simple_list_item_1
        provider.preloadNative(host, request.copy(layoutRes = broken))
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        val outcomes = Outcomes()
        assertFalse(bind(FrameLayout(host).also(host::setContentView), listener = outcomes, layoutRes = broken))
        main.idle()
        assertEquals(0, outcomes.loaded)
        assertEquals(0, outcomes.failures)
    }

    @Test fun `a cached fill that cannot be drawn on the first resume ends the attempt as unavailable`() {
        val host = controller.get()
        val broken = android.R.layout.simple_list_item_1
        provider.preloadNative(host, request.copy(layoutRes = broken))
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        val language = Robolectric.buildActivity(NativeProviderHost::class.java).create().start()
        try {
            val container = FrameLayout(language.get()).also(language.get()::setContentView)
            val outcomes = Outcomes()
            assertFalse(bind(container, listener = outcomes, activity = language.get(), layoutRes = broken))
            language.resume().visible().windowFocusChanged(true)
            main.idle()
            assertEquals(0, outcomes.loaded)
            assertEquals(1, outcomes.failures)
            assertEquals(1, requests.size)
        } finally {
            language.pause().stop().destroy()
        }
    }

    @Test fun `a buffered native binds once without a request`() {
        val host = controller.get()
        configureLanguageNative(host)
        provider.preloadNative(host, request)
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        var bound = 0
        host.showNativeAd(placement, request.unit, FrameLayout(host).also(host::setContentView),
            onBound = { bound++ }, onUnavailable = { fail("A buffered native was refused: $it") })
        main.idle()
        assertEquals(1, bound)
        assertEquals(1, requests.size)
    }

    @Test fun `a slot that ended unavailable ignores a later fill of its placement`() {
        val host = controller.get()
        configureLanguageNative(host)
        val container = FrameLayout(host).also(host::setContentView)
        var bound = 0
        var unavailable = 0
        host.showNativeAd(placement, request.unit, container,
            onBound = { bound++ }, onUnavailable = { unavailable++ })
        vendorEvents.single().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        main.idle()
        assertEquals(1, unavailable)
        provider.preloadNative(host, request)
        requests.last().onNativeAdLoaded(mock(NativeAd::class.java))
        main.idle()
        assertEquals(0, bound)
        assertEquals(1, unavailable)
        assertFalse(container.getChildAt(0) is com.google.android.gms.ads.nativead.NativeAdView)
        assertEquals("The late fill stays unused", NativeStatus.READY, provider.nativeStatus(placement))
    }

    @Test fun `an unavailability waiting for resume is dropped when its screen is destroyed`() {
        val host = controller.get()
        configureLanguageNative(host)
        provider.preloadNative(host, request)
        val paused = Robolectric.buildActivity(NativeProviderHost::class.java).create().start()
        var unavailable = 0
        paused.get().showNativeAd(placement, request.unit, FrameLayout(paused.get()).also(paused.get()::setContentView),
            onUnavailable = { unavailable++ })
        vendorEvents.single().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        main.idle()
        assertEquals(0, unavailable)
        paused.stop().destroy()
        main.idle()
        assertEquals(0, unavailable)
        assertEquals(1, requests.size)
    }

    @Test fun `a buffered native reports one bound event for its placement`() {
        val host = controller.get()
        configureLanguageNative(host)
        val events = recordAnalytics()
        provider.preloadNative(host, request)
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        host.showNativeAd(placement, request.unit, FrameLayout(host).also(host::setContentView))
        main.idle()
        assertEquals(listOf(placement.key to io.trackkit.AdFormat.NATIVE), boundEvents(events))
    }

    @Test fun `a cold native bound on resume reports one bound event and its replacement none`() {
        val host = controller.get()
        configureLanguageNative(host)
        val events = recordAnalytics()
        val container = FrameLayout(host).also(host::setContentView)
        host.showNativeAd(placement, request.unit, container)
        controller.pause()
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        main.idle()
        assertEquals(emptyList<Pair<String, io.trackkit.AdFormat>>(), boundEvents(events))
        controller.resume()
        main.idle()
        val firstView = container.getChildAt(0)
        assertTrue(firstView is com.google.android.gms.ads.nativead.NativeAdView)
        vendorEvents.first().onAdClicked()
        controller.pause().resume()
        requests.last().onNativeAdLoaded(mock(NativeAd::class.java))
        main.idle()
        assertNotSame("The click replacement was bound", firstView, container.getChildAt(0))
        assertEquals(listOf(placement.key to io.trackkit.AdFormat.NATIVE), boundEvents(events))
    }

    @Test fun `a fullscreen step reports its bound event as a fullscreen native and the impression adds none`() {
        val host = controller.get()
        val fullscreen = AdPlacement.StepFullScreen(io.onboardkit.core.StepId.OB3)
        io.onboardkit.OnboardingSdk.install(host.application) { adProvider = provider; trackkitAutoTracking(false) }
        io.onboardkit.OnboardingSdk.configure(io.onboardkit.config.onboardKitConfig {
            defaultSteps()
            ads = io.onboardkit.config.AdsConfig(fullScreenStepNative = request.unit)
        }.getOrThrow())
        val events = recordAnalytics()
        provider.preloadNative(host, request.copy(placement = fullscreen,
            layoutRes = io.onboardkit.R.layout.ob_layout_native_fullscreen))
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        host.showNativeAd(fullscreen, request.unit, FrameLayout(host).also(host::setContentView))
        vendorEvents.single().onAdImpression()
        main.idle()
        assertEquals(listOf(fullscreen.key to io.trackkit.AdFormat.NATIVE_FULL_SCREEN), boundEvents(events))
    }

    @Test fun `only a native load counts as a bound event`() {
        val events = recordAnalytics()
        AdPlacement.SplashInterstitial.tracked().onLoaded()
        AdPlacement.SplashBanner.tracked().onLoaded()
        AdPlacement.Language1.tracked().onImpression()
        assertEquals(emptyList<Pair<String, io.trackkit.AdFormat>>(), boundEvents(events))
    }

    @Test fun `a failure waiting for resume reports one failed ad when its screen resumes`() {
        val host = controller.get()
        configureLanguageNative(host)
        val events = recordAnalytics()
        var unavailable = 0
        host.showNativeAd(placement, request.unit, FrameLayout(host).also(host::setContentView),
            onUnavailable = { unavailable++ })
        controller.pause()
        vendorEvents.single().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        main.idle()
        assertEquals(0, events.filterIsInstance<io.onboardkit.core.analytics.AnalyticsEvent.AdFailed>().size)
        controller.resume()
        main.idle()
        assertEquals(1, unavailable)
        assertEquals(listOf(placement.key),
            events.filterIsInstance<io.onboardkit.core.analytics.AnalyticsEvent.AdFailed>().map { it.placementName })
    }

    @Test fun `a failure whose screen is destroyed before resuming reports no failed ad`() {
        val host = controller.get()
        configureLanguageNative(host)
        val events = recordAnalytics()
        provider.preloadNative(host, request)
        val paused = Robolectric.buildActivity(NativeProviderHost::class.java).create().start()
        paused.get().showNativeAd(placement, request.unit, FrameLayout(paused.get()).also(paused.get()::setContentView))
        vendorEvents.single().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        main.idle()
        paused.stop().destroy()
        main.idle()
        assertEquals(0, events.filterIsInstance<io.onboardkit.core.analytics.AnalyticsEvent.AdFailed>().size)
    }

    @Test fun `releasing a slot drops an unavailability waiting for resume`() {
        val host = controller.get()
        configureLanguageNative(host)
        var unavailable = 0
        host.showNativeAd(placement, request.unit, FrameLayout(host).also(host::setContentView),
            onUnavailable = { unavailable++ })
        controller.pause()
        vendorEvents.single().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        provider.releaseNative(placement)
        controller.resume()
        main.idle()
        assertEquals(0, unavailable)
    }

    @Test fun `a new attempt on a slot drops the previous attempt's unavailability waiting for resume`() {
        val host = controller.get()
        configureLanguageNative(host)
        val container = FrameLayout(host).also(host::setContentView)
        var stale = 0
        host.showNativeAd(placement, request.unit, container, onUnavailable = { stale++ })
        controller.pause()
        vendorEvents.single().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        host.showNativeAd(placement, request.unit, container)
        controller.resume()
        main.idle()
        assertEquals(0, stale)
    }

    @Test fun `a new attempt on a paused host removes the previous attempt's wait for resume`() {
        val host = controller.get()
        configureLanguageNative(host)
        val container = FrameLayout(host).also(host::setContentView)
        val lifecycle = host.lifecycle as androidx.lifecycle.LifecycleRegistry
        host.showNativeAd(placement, request.unit, container)
        controller.pause()
        val idle = lifecycle.observerCount
        vendorEvents.single().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        assertEquals("The failure waits for resume", idle + 1, lifecycle.observerCount)
        host.showNativeAd(placement, request.unit, container, bufferedOnly = true)
        assertEquals(idle, lifecycle.observerCount)
    }

    @Test fun `a screen callback that throws does not escape the slot's lifecycle or vendor events`() {
        val host = controller.get()
        val container = FrameLayout(host).also(host::setContentView)
        val calls = mutableListOf<String>()
        val throwing = object : AdEventListener {
            override fun onLoaded() { calls += "loaded"; error("screen bug") }
            override fun onImpression() { calls += "impression"; error("screen bug") }
            override fun onClicked() { calls += "clicked"; error("screen bug") }
        }
        assertFalse(bind(container, listener = throwing))
        provider.preloadNative(host, request)
        controller.pause()
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        controller.resume()
        vendorEvents.single().onAdImpression()
        vendorEvents.single().onAdClicked()
        main.idle()
        assertEquals(listOf("loaded", "impression", "clicked"), calls)
    }

    @Test fun `releasing a slot cancels its queued request`() {
        val host = controller.get()
        configureLanguageNative(host)
        controller.pause().stop()
        provider.preloadNative(host, request)
        provider.releaseNative(placement)
        controller.restart().start().resume().visible().windowFocusChanged(true)
        main.idle()
        assertEquals(0, requests.size)
    }

    @Test fun `releasing everything drops the flow's unused natives and leaves a host key alone`() {
        val host = controller.get()
        configureLanguageNative(host)
        provider.preloadNative(host, request)
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        NativeAdManager.preload(host, "host_native", com.ads.module.helper.adnative.NativeAdConfig(
            listOf("native-host"), true, false, com.ads.module.R.layout.custom_native_admob_medium))
        requests.last().onNativeAdLoaded(mock(NativeAd::class.java))
        provider.releaseAll()
        assertFalse(NativeAdManager.isReady(placement.key))
        assertTrue(NativeAdManager.isReady("host_native"))
    }

    private class Outcomes : AdEventListener {
        var loaded = 0
        var failures = 0
        override fun onLoaded() { loaded++ }
        override fun onFailedToLoad() { failures++ }
    }

    private fun recordAnalytics(): List<io.onboardkit.core.analytics.AnalyticsEvent> =
        mutableListOf<io.onboardkit.core.analytics.AnalyticsEvent>().also { events ->
            io.onboardkit.core.analytics.AnalyticsHub.addPlugin { events += it }
        }

    private fun boundEvents(events: List<io.onboardkit.core.analytics.AnalyticsEvent>) =
        events.filterIsInstance<io.onboardkit.core.analytics.AnalyticsEvent.AdImpression>()
            .map { it.placementName to it.format }

    private fun configureLanguageNative(host: NativeProviderHost) {
        io.onboardkit.OnboardingSdk.install(host.application) { adProvider = provider; trackkitAutoTracking(false) }
        io.onboardkit.OnboardingSdk.configure(io.onboardkit.config.onboardKitConfig {
            defaultSteps()
            ads = io.onboardkit.config.AdsConfig(languageNative = request.unit)
        }.getOrThrow())
    }

    @Test fun `releasing everything destroys bound slots and silences waiting and queued ones`() {
        val host = controller.get()
        configureLanguageNative(host)
        val shown = AdPlacement.Language2
        provider.preloadNative(host, request.copy(placement = shown))
        val boundAd = mock(NativeAd::class.java)
        requests.single().onNativeAdLoaded(boundAd)
        assertTrue(bind(FrameLayout(host).also(host::setContentView), shown))
        var outcomes = 0
        host.showNativeAd(placement, request.unit, FrameLayout(host),
            onBound = { outcomes++ }, onUnavailable = { outcomes++ })
        assertEquals(2, requests.size)
        val unfocused = Robolectric.buildActivity(NativeProviderHost::class.java).setup()
        try {
            provider.preloadNative(unfocused.get(), request.copy(placement = AdPlacement.LanguageConfirm))
            assertEquals(2, requests.size)
            provider.releaseAll()
            verify(boundAd).destroy()
            vendorEvents.last().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
            unfocused.windowFocusChanged(true)
            main.idle()
            assertEquals("A released slot hears nothing", 0, outcomes)
            assertEquals("A released queue never dispatches", 2, requests.size)
        } finally {
            unfocused.pause().stop().destroy()
        }
    }

    @Test fun `a new attempt on a slot takes over its impression, replacement and click callbacks`() {
        val host = controller.get()
        configureLanguageNative(host)
        provider.preloadNative(host, request)
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        val container = FrameLayout(host).also(host::setContentView)
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        fun attempt(log: MutableList<String>) = host.showNativeAd(placement, request.unit, container,
            onBound = { log += "bound" }, onShown = { log += "shown" },
            onUnavailable = { log += "unavailable" }, onAdEngaged = { log += "engaged" })
        attempt(first)
        attempt(second)
        vendorEvents.first().onAdImpression()
        requests.last().onNativeAdLoaded(mock(NativeAd::class.java))
        vendorEvents.last().onAdClicked()
        main.idle()
        assertEquals(listOf("bound"), first)
        assertEquals(listOf("shown", "bound", "engaged"), second)
    }

    @Test fun `step never refills after show even with the reload timer enabled`() {
        val host = controller.get()
        val page = AdPlacement.StepFullScreen(io.onboardkit.core.StepId.FULL1)
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"steps":{"full1":{"behavior":{"reload":{"allowed":true,"timer_enabled":true,"interval_ms":1000}}}}}}""")
        val container = FrameLayout(host).also(host::setContentView)
        provider.preloadNative(host, request.copy(placement = page))
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        assertTrue(bind(container, page))
        main.idleFor(3, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(1, requests.size)
    }

    @Test fun `content pager departure consumes old ad and return joins a pending preload`() {
        verifyPagerReturn(io.onboardkit.ui.onboarding.ContentStepFragment.newInstance(io.onboardkit.core.StepId.OB1, 0),
            AdPlacement.StepNative(io.onboardkit.core.StepId.OB1))
    }

    @Test fun `full screen pager departure consumes old ad and return joins a pending preload`() {
        verifyPagerReturn(io.onboardkit.ui.onboarding.AdStepFragment.newInstance(io.onboardkit.core.StepId.FULL1, 0),
            AdPlacement.StepFullScreen(io.onboardkit.core.StepId.FULL1))
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
        kotlinx.coroutines.runBlocking { io.onboardkit.OnboardingSdk.reset() }
        val parent = FrameLayout(host).apply { id = android.view.View.generateViewId() }
        host.setContentView(parent)
        host.supportFragmentManager.beginTransaction().add(parent.id, fragment).commitNow()
        fragment.dispatchSelected()
        val first = mock(NativeAd::class.java)
        requests.single().onNativeAdLoaded(first)
        assertNotEquals(NativeStatus.READY, provider.nativeStatus(page))
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
        assertNotEquals("return must bind and consume the new fill", NativeStatus.READY, provider.nativeStatus(page))
        verify(second, never()).destroy()
    }

    @Test fun `cold fill while paused waits for resume without binding the old view`() {
        val host = controller.get()
        val container = FrameLayout(host).also(host::setContentView)
        val outcomes = Outcomes()
        assertFalse(bind(container, listener = outcomes))
        provider.preloadNative(host, request)
        controller.pause()
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        assertEquals(0, outcomes.loaded)
        assertEquals(NativeStatus.READY, provider.nativeStatus(placement))
        controller.resume()
        assertEquals(1, outcomes.loaded)
        assertNotEquals(NativeStatus.READY, provider.nativeStatus(placement))
        assertEquals(1, requests.size)
    }

    @Test fun `cold failure while paused is delivered once after resume`() {
        val host = controller.get()
        configureLanguageNative(host)
        var failures = 0
        host.showNativeAd(placement, request.unit, FrameLayout(host).also(host::setContentView),
            onUnavailable = { failures++ })
        assertEquals(1, requests.size)
        controller.pause()
        vendorEvents.single().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        assertEquals(0, failures)
        controller.resume()
        assertEquals(1, failures)
        controller.pause().resume()
        assertEquals(1, failures)
    }

    @Test fun `a purchase while a slot waits ends it unavailable once`() {
        val host = controller.get()
        configureLanguageNative(host)
        val reasons = mutableListOf<AdSkipReason>()
        host.showNativeAd(placement, request.unit, FrameLayout(host).also(host::setContentView),
            onBound = { fail("A purchased user never gets the fill") }, onUnavailable = { reasons += it })
        assertEquals(1, requests.size)
        Entitlement.install(object : EntitlementSource { override fun isPremium(context: Context) = true })
        try {
            com.ads.module.helper.AdGate.releaseBufferedAds()
            requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
            main.idle()
            assertEquals(listOf(AdSkipReason.NO_FILL), reasons)
        } finally {
            Entitlement.install(object : EntitlementSource { override fun isPremium(context: Context) = false })
        }
    }

    @Test fun `a fill parked for resume and taken by another consumer ends the slot unavailable without a request`() {
        val host = controller.get()
        configureLanguageNative(host)
        val reasons = mutableListOf<AdSkipReason>()
        host.showNativeAd(placement, request.unit, FrameLayout(host).also(host::setContentView),
            onBound = { fail("The parked fill was taken") }, onUnavailable = { reasons += it })
        controller.pause()
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        main.idle()
        assertNotNull(com.ads.module.helper.adnative.NativeAdPreload.getInstance().pollAdNative(placement.key))
        controller.resume()
        main.idle()
        assertEquals(listOf(AdSkipReason.NO_FILL), reasons)
        assertEquals(1, requests.size)
    }

    @Test fun `a fill landing after a paused slot failed stays unused and the slot ends unavailable`() {
        val host = controller.get()
        configureLanguageNative(host)
        val reasons = mutableListOf<AdSkipReason>()
        val container = FrameLayout(host).also(host::setContentView)
        host.showNativeAd(placement, request.unit, container,
            onBound = { fail("A failed attempt is not revived by a later fill") }, onUnavailable = { reasons += it })
        controller.pause()
        vendorEvents.single().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        val other = Robolectric.buildActivity(NativeProviderHost::class.java).setup().windowFocusChanged(true)
        try {
            provider.preloadNative(other.get(), request)
            requests.last().onNativeAdLoaded(mock(NativeAd::class.java))
            controller.resume()
            main.idle()
            assertEquals(listOf(AdSkipReason.NO_FILL), reasons)
            assertFalse(container.getChildAt(0) is com.google.android.gms.ads.nativead.NativeAdView)
            assertEquals(NativeStatus.READY, provider.nativeStatus(placement))
        } finally {
            other.pause().stop().destroy()
        }
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
        assertNotEquals(NativeStatus.READY, provider.nativeStatus(AdPlacement.StepNative(io.onboardkit.core.StepId.OB1)))
        verify(ad, never()).destroy()
        assertEquals(1, restored.requireView().findViewById<FrameLayout>(io.onboardkit.R.id.ob_native_container).childCount)
    }

    @Test fun `rotation pending native failure resolves only once`() = verifyPendingRotationOutcome(fail = true)

    @Test fun `helper load failure during pause is deferred until resume`() =
        verifyPendingRotationOutcome(fail = true, pauseBeforeOutcome = true)

    @Test fun `rotation pending native success resolves only once`() = verifyPendingRotationOutcome(fail = false)

    private fun verifyPendingRotationOutcome(fail: Boolean, pauseBeforeOutcome: Boolean = false) {
        val host = controller.get()
        configureLanguageNative(host)
        host.showNativeAd(placement, request.unit, FrameLayout(host).also(host::setContentView))
        var loaded = 0
        var failures = 0
        NativeProviderHost.onCreated = { recreated ->
            recreated.showNativeAd(placement, request.unit, FrameLayout(recreated).also(recreated::setContentView),
                onBound = { loaded++ }, onUnavailable = { failures++ })
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

    @Test fun `fullscreen does not announce display until the vendor records an impression`() {
        val host = controller.get()
        val fullscreen = AdPlacement.StepFullScreen(io.onboardkit.core.StepId.OB3)
        val fullscreenRequest = request.copy(placement = fullscreen,
            layoutRes = io.onboardkit.R.layout.ob_layout_native_fullscreen)
        io.onboardkit.OnboardingSdk.install(host.application) { adProvider = provider; trackkitAutoTracking(false) }
        io.onboardkit.OnboardingSdk.configure(io.onboardkit.config.onboardKitConfig {
            defaultSteps()
            ads = io.onboardkit.config.AdsConfig(fullScreenStepNative = request.unit)
        }.getOrThrow())
        val container = FrameLayout(host).also(host::setContentView)
        var impressions = 0
        var bound = 0
        provider.preloadNative(host, fullscreenRequest)
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        host.showNativeAd(fullscreen, request.unit, container, onBound = { bound++ }, onShown = { impressions++ })
        assertEquals("The bind is reported before the vendor impression", 1, bound)
        assertEquals("A filled/bound view must not unlock fullscreen swipe", 0, impressions)
        vendorEvents.first().onAdImpression()
        assertEquals("Only the displayed ad may unlock fullscreen swipe", 1, impressions)
    }

    @Test fun `preloading after display keeps the next fill unused until an explicit bind`() {
        val host = controller.get()
        val container = FrameLayout(host).also(host::setContentView)
        val outcomes = Outcomes()
        assertFalse(bind(container, listener = outcomes))
        provider.preloadNative(host, request)
        val first = mock(NativeAd::class.java)
        requests.single().onNativeAdLoaded(first)
        assertEquals(1, outcomes.loaded)
        provider.preloadNative(host, request)
        vendorEvents.last().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        assertEquals("prewarm failure cannot fail a completed display attempt", 0, outcomes.failures)
        provider.preloadNative(host, request)
        val second = mock(NativeAd::class.java)
        requests.last().onNativeAdLoaded(second)
        assertEquals(NativeStatus.READY, provider.nativeStatus(placement))
        verify(first, never()).destroy()
        assertTrue(bind(container, listener = outcomes))
        assertNotEquals(NativeStatus.READY, provider.nativeStatus(placement))
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
        assertTrue(bind(container))
        vendorEvents.single().onAdImpression()
        var newContainer: FrameLayout? = null
        var restoredBinds = 0
        var restoredImpressions = 0
        NativeProviderHost.onCreated = { recreated ->
            newContainer = FrameLayout(recreated).also(recreated::setContentView)
            val listener = object : AdEventListener {
                override fun onLoaded() { restoredBinds++ }
                override fun onImpression() { restoredImpressions++ }
            }
            if (!bind(checkNotNull(newContainer), listener = listener, activity = recreated)) {
                provider.preloadNative(recreated, request)
            }
        }
        controller.configurationChange(android.content.res.Configuration(host.resources.configuration).apply {
            orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
        })
        assertEquals(1, requests.size)
        assertEquals(1, restoredBinds)
        assertEquals("An already impressed ad can be shown after rotation without another vendor event", 1, restoredImpressions)
        assertEquals(1, newContainer!!.childCount)
        assertNotEquals(NativeStatus.READY, provider.nativeStatus(placement))
        verify(ad, never()).destroy()
    }

}

class NativeProviderHost : AppCompatActivity(), io.onboardkit.core.StepHost {
    override val currentIndex = kotlinx.coroutines.flow.MutableStateFlow(0)
    override val totalSteps = kotlinx.coroutines.flow.MutableStateFlow(4)
    override fun next(exitReason: String?) = Unit
    override fun back() = false
    override fun finishFlow(reason: io.onboardkit.core.FinishReason) = Unit
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
