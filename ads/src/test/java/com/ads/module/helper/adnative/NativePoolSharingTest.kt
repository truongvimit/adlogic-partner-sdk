package com.ads.module.helper.adnative

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.AdsSdk
import com.ads.module.ads.wrapper.NativeAdWrapper
import com.ads.module.config.AdsSdkConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.adnative.pool.MainLooperPoolScheduler
import com.ads.module.helper.adnative.pool.NativePoolEngine
import com.ads.module.helper.adnative.pool.PoolAdLoader
import com.ads.module.helper.adnative.pool.PoolExpiry
import com.ads.module.helper.adnative.pool.PoolLoadSink
import com.ads.module.helper.adnative.pool.PoolParity
import com.ads.module.helper.adnative.pool.PoolRequest
import com.ads.module.helper.adnative.pool.PoolRequestPolicy
import com.ads.module.helper.adnative.pool.PoolSlotBinder
import com.ads.module.helper.adnative.pool.PoolState
import com.ads.module.helper.adnative.pool.SharedNativePool
import com.ads.module.helper.adnative.pool.SystemPoolClock
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02FacebookShadow
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.nativead.NativeAd
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo

/** A helper outside the pool lends from it while its own request runs and hands its ads back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Int02Application::class,
    shadows = [Int02MobileAdsShadow::class, Int02FacebookShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NativePoolSharingTest {
    private lateinit var controller: ActivityController<NativeHostActivity>
    private val activity get() = controller.get()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val requests = mutableListOf<NativeRequest>()
    private lateinit var builders: MockedConstruction<AdLoader.Builder>
    private lateinit var engine: NativePoolEngine<NativeAdWrapper>

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        builders = mockConstruction(AdLoader.Builder::class.java) { builder, construction ->
            var loaded: NativeAd.OnNativeAdLoadedListener? = null
            var listener: AdListener? = null
            doAnswer { loaded = it.getArgument(0); builder }.`when`(builder).forNativeAd(any())
            doAnswer { listener = it.getArgument(0); builder }.`when`(builder).withAdListener(any())
            doReturn(builder).`when`(builder).withNativeAdOptions(any())
            val loader = mock(AdLoader::class.java)
            doReturn(loader).`when`(builder).build()
            doAnswer {
                requests += NativeRequest(construction.arguments()[0] as Context,
                    construction.arguments()[1] as String, checkNotNull(loaded), checkNotNull(listener))
                null
            }.`when`(loader).loadAd(any(AdRequest::class.java))
        }
        NativeAdPreload.getInstance().releaseAll()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        AdsSdk.getInstance().init(app, AdsSdkConfig(app).apply { setFacebookClientToken("native-test") })
        AppOpenManager.getInstance().disableAppResume()
        controller = Robolectric.buildActivity(NativeHostActivity::class.java).setup()
        engine = NativePoolEngine(
            loader = object : PoolAdLoader<NativeAdWrapper> {
                override fun load(request: PoolRequest, id: String, sink: PoolLoadSink<NativeAdWrapper>) = Unit
            },
            policy = PoolRequestPolicy.ALLOW_ALL,
            parity = PoolParity(asyncGate = false, asyncSelector = false),
            clock = SystemPoolClock,
            scheduler = MainLooperPoolScheduler(),
            expiry = PoolExpiry { 0L },
            derivedDispatcher = Dispatchers.Unconfined,
            destroyAd = { it.destroy() },
            isDrawn = PoolSlotBinder::draws,
        )
        SharedNativePool.installForTest(engine)
        SharedNativePool.appPoolOpen = true
        engine.afterFlow = true
        main.idle()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        SharedNativePool.resetForTest()
        NativeAdPreload.getInstance().releaseAll()
        main.idleFor(31, java.util.concurrent.TimeUnit.SECONDS)
        builders.close()
        ConsentCenter.clearHostConsent()
        requests.clear()
    }

    private fun helper(
        key: String,
        joins: Boolean = true,
        reloads: Boolean = false,
        container: FrameLayout = FrameLayout(activity),
    ): NativeAdHelper =
        NativeAdHelper(activity, activity, NativeAdConfig(listOf("native-unit-$key"), true, reloads,
            com.ads.module.R.layout.custom_native_admob_medium))
            .also {
                it.placement = key
                it.joinsSharedPool = joins
            }
            .setNativeAdBinder { _, ad, container, _ -> container.tag = ad }
            .setNativeContentView(container.also(activity::setContentView))

    /** A helper of [key] that showed [vendor] and recorded its impression. */
    private fun shown(key: String, vendor: NativeVendorAd = NativeVendorAd()): NativeAdHelper =
        helper(key).also { helper ->
            helper.show()
            requests.last().fill(vendor)
            requests.last().listener.onAdImpression()
        }

    private fun NativeAdHelper.vendorAd() = nativeAd?.admobNativeAd

    @Test fun `a shown ad goes to the pool when its slot closes and the next slot shows it while its own request runs`() {
        val home = NativeVendorAd()
        shown("native_home", home).destroy()
        assertFalse("handed back, not disposed", home.destroyed)
        assertTrue(engine.debugSnapshot().loader("kept:native_home")?.state is PoolState.Showing)

        val next = helper("native_all")
        next.show()
        assertEquals("the slot still sends its own request", 2, requests.size)
        assertSame(home, next.vendorAd())
        assertTrue(next.nativeAdState.value is AdNativeState.Loading)
        assertNull("lent ads leave the pool", engine.debugSnapshot().loader("kept:native_home")?.ad)

        val own = NativeVendorAd()
        requests.last().fill(own)
        assertSame(own, next.vendorAd())
        assertTrue(next.nativeAdState.value is AdNativeState.Loaded)
        assertFalse(home.destroyed)
        assertSame("the lent ad is back in the pool, under the slot that let it go", home,
            engine.debugSnapshot().loader("kept:native_all")?.ad?.admobNativeAd)
    }

    @Test fun `a slot whose own request finds nothing fails as an empty slot would, the lent ad still drawn`() {
        val home = NativeVendorAd()
        shown("native_home", home).destroy()
        val failures = mutableListOf<String>()
        val slot = FrameLayout(activity)
        val next = helper("native_all", container = slot).apply {
            registerAdListener(object : com.ads.module.funtion.AdCallback() {
                override fun onAdFailedToLoad(error: com.google.android.gms.ads.LoadAdError?) { failures += "failed" }
            })
        }
        next.show()
        requests.last().fail()
        main.idle()
        assertSame(home, next.vendorAd())
        assertTrue(next.nativeAdState.value is AdNativeState.Fail)
        assertEquals(listOf("failed"), failures)
        assertTrue("the lent ad stays on screen", slot.isShown)

        // An app that shows again on Fail sends the request it sends today; the lent ad stays until it fills.
        next.show()
        assertEquals(3, requests.size)
        assertSame(home, next.vendorAd())
        val own = NativeVendorAd()
        requests.last().fill(own)
        assertSame(own, next.vendorAd())
        assertTrue(next.nativeAdState.value is AdNativeState.Loaded)
    }

    @Test fun `a failed slot drawing a lent ad sends no reload an empty failed slot would not send`() {
        shown("native_home").destroy()
        val next = helper("native_all", reloads = true).applyReloadByTime(1_000L)
        next.show()
        requests.last().fail()
        main.idle()
        val sent = requests.size
        repeat(2) { controller.pause(); controller.resume() }
        main.idleFor(31, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("neither the timer nor a return reloads it", sent, requests.size)
        assertTrue(next.nativeAdState.value is AdNativeState.Fail)
    }

    @Test fun `an ad replaced by its slot's next fill goes to the pool`() {
        val first = NativeVendorAd()
        val helper = shown("native_home", first)
        helper.cancel()
        assertFalse(first.destroyed)
        helper.show()
        assertSame("the slot lends its own ad back while it requests", first, helper.vendorAd())
    }

    @Test fun `a clicked ad is disposed, never handed back`() {
        val clicked = NativeVendorAd()
        val helper = shown("native_home", clicked)
        requests.last().listener.onAdClicked()
        helper.destroy()
        assertTrue(clicked.destroyed)
        assertNull(engine.debugSnapshot().loader("kept:native_home"))
    }

    @Test fun `an unshown ad goes back as unshown and is lent before a shown one`() {
        shown("native_home").destroy()
        val unseen = NativeVendorAd()
        helper("native_dialog").also { it.show(); requests.last().fill(unseen) }.destroy()
        val loaders = engine.debugSnapshot().loaders
        assertTrue(loaders.any { it.ad?.admobNativeAd === unseen && it.state is PoolState.Loaded })
        assertEquals("the lent shown ad is kept too", 2, loaders.count { it.ad != null })
        val next = helper("native_all")
        next.show()
        assertSame(unseen, next.vendorAd())
    }

    @Test fun `before the flow ends, or with the pool closed, ads are disposed and nothing is lent`() {
        engine.afterFlow = false
        val early = NativeVendorAd()
        shown("native_home", early).destroy()
        assertTrue(early.destroyed)

        engine.afterFlow = true
        SharedNativePool.appPoolOpen = false
        val closed = NativeVendorAd()
        shown("native_home", closed).destroy()
        assertTrue(closed.destroyed)
        val next = helper("native_all")
        next.show()
        assertNull(next.nativeAd)
    }

    @Test fun `a helper that does not join the pool keeps disposing its ads`() {
        val ad = NativeVendorAd()
        helper("native_home", joins = false).also {
            it.show()
            requests.last().fill(ad)
            requests.last().listener.onAdImpression()
        }.destroy()
        assertTrue(ad.destroyed)
    }

    @Test fun `a retaining helper hands its ad to the pool and the next one restores it from there`() {
        val resume = NativeVendorAd()
        val first = helper("native_resume").also { it.retainShownAdOnDestroy = true }
        first.show()
        requests.last().fill(resume)
        requests.last().listener.onAdImpression()
        controller.pause().stop().destroy()
        main.idle()
        assertFalse(resume.destroyed)

        controller = Robolectric.buildActivity(NativeHostActivity::class.java).setup()
        main.idle()
        val next = helper("native_resume").also { it.retainShownAdOnDestroy = true }
        assertTrue(next.bindAvailable())
        assertSame(resume, next.vendorAd())
    }

    @Test fun `a click on a lent ad reaches the slot, and its own fill replaces the clicked ad on return`() {
        val home = NativeVendorAd()
        shown("native_home", home).destroy()
        val clicks = mutableListOf<String>()
        val next = helper("native_all").apply {
            registerAdListener(object : com.ads.module.funtion.AdCallback() {
                override fun onAdClicked() { clicks += "clicked" }
            })
        }
        next.show()
        assertSame(home, next.vendorAd())

        requests.first().listener.onAdClicked()
        assertEquals(listOf("clicked"), clicks)
        assertEquals("the slot's own request is already running", 2, requests.size)
        controller.pause()
        val own = NativeVendorAd()
        requests.last().fill(own)
        assertSame("nothing binds behind the click destination", home, next.vendorAd())
        controller.resume()
        main.idle()
        assertSame(own, next.vendorAd())
        assertTrue("a clicked ad is never handed back", home.destroyed)
        assertNull(engine.debugSnapshot().loader("kept:native_all")?.ad)
    }

    @Test fun `a lent ad kept after the slot's request found nothing reloads on click`() {
        val home = NativeVendorAd()
        shown("native_home", home).destroy()
        val next = helper("native_all")
        next.show()
        requests.last().fail()
        main.idle()
        assertSame(home, next.vendorAd())

        requests.first().listener.onAdClicked()
        assertEquals("click_action reload sends a request", 3, requests.size)
        controller.pause()
        val own = NativeVendorAd()
        requests.last().fill(own)
        controller.resume()
        main.idle()
        assertSame(own, next.vendorAd())
        assertTrue(home.destroyed)
    }

    @Test fun `a request sent before the screen resumed shows a lent ad once it resumes`() {
        val home = NativeVendorAd()
        shown("native_home", home).destroy()
        controller.pause()
        val next = helper("native_all")
        next.show()
        assertNull("not resumed: nothing is lent yet", next.nativeAd)
        controller.resume()
        main.idle()
        assertSame(home, next.vendorAd())
        assertTrue(next.nativeAdState.value is AdNativeState.Loading)
    }
}
