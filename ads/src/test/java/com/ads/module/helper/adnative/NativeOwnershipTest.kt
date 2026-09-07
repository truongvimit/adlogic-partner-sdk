package com.ads.module.helper.adnative

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.ads.ERainAd
import com.ads.module.admob.AppOpenManager
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02FacebookShadow
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.google.android.gms.ads.*
import com.google.android.gms.ads.nativead.NativeAd
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowNetworkInfo
import org.mockito.Mockito.*
import org.mockito.MockedConstruction

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Int02Application::class,
    shadows = [Int02MobileAdsShadow::class, Int02FacebookShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeOwnershipTest {
    private lateinit var controller: ActivityController<NativeHostActivity>
    private val activity get() = controller.get()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val requests = mutableListOf<NativeRequest>()
    private lateinit var builders: MockedConstruction<AdLoader.Builder>
    private val preload get() = NativeAdPreload.getInstance()
    private val config get() = NativeAdConfig(listOf("native-unit"), true, false,
        com.ads.module.R.layout.custom_native_admob_medium)

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        requests.clear()
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
        preload.releaseAll()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply { setFacebookClientToken("native-test") })
        AppOpenManager.getInstance().disableAppResume()
        controller = Robolectric.buildActivity(NativeHostActivity::class.java).setup()
        main.idle()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        preload.releaseAll()
        main.idleFor(31, java.util.concurrent.TimeUnit.SECONDS)
        preload.releaseAll()
        NativeHostActivity.onCreated = null
        builders.close()
        ConsentCenter.clearHostConsent()
        requests.clear()
    }

    @Test fun `repeated preload does not queue new loads behind an existing fill`() {
        repeat(5) { preload.preloadWithKey("a", activity, config) }
        assertEquals(1, requests.size)
        val ad = NativeVendorAd()
        requests.single().fill(ad)
        main.idle()
        assertEquals("a successful fill must not start the queued duplicate", 1, requests.size)
        repeat(5) { preload.preloadWithKey("a", activity, config) }
        assertEquals(1, requests.size)
        assertSame(ad, preload.getAdNative("a")?.admobNativeAd)
    }
    private fun helper(key: String = "a", reload: Boolean = false): NativeAdHelper =
        NativeAdHelper(activity, activity, NativeAdConfig(config.adUnitIds, true, reload, config.layoutId))
            .also { it.placement = key }
            .setNativeAdBinder { _, ad, container, _ -> container.tag = ad }
            .setNativeContentView(android.widget.FrameLayout(activity).also(activity::setContentView))

    @Test fun `ordinary show and preload share a request and showing consumes the cache`() {
        val helper = helper()
        helper.requestAds(NativeAdParam.Request)
        preload.preloadWithKey("a", activity, config)
        helper.requestAds(NativeAdParam.Request)
        assertEquals("ordinary helper must join the placement store", 1, requests.size)
        val first = NativeVendorAd()
        requests.single().fill(first)
        assertSame(first, helper.nativeAd?.admobNativeAd)
        assertNull(preload.getAdNative("a"))
        assertFalse(first.destroyed)
        helper.requestAds(NativeAdParam.Request)
        assertEquals("a show after the previous bind is a new request", 2, requests.size)
        val second = NativeVendorAd()
        requests.last().fill(second)
        assertSame(second, helper.nativeAd?.admobNativeAd)
        assertTrue(first.destroyed)
        assertNull(preload.getAdNative("a"))
    }

    @Test fun `leaving while loading detaches old screen and returning joins the same request`() {
        val old = helper()
        old.requestAds(NativeAdParam.Request)
        val request = requests.single()
        assertSame(activity.applicationContext, request.context)
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(NativeHostActivity::class.java).setup()
        val returned = helper()
        returned.requestAds(NativeAdParam.Request)
        assertEquals(1, requests.size)
        val ad = NativeVendorAd()
        request.fill(ad)
        assertFalse(ad.destroyed)
        assertNull(old.nativeAd)
        assertSame(ad, returned.nativeAd?.admobNativeAd)
    }

    @Test fun `refresh keeps survivor on failure and leaving ends the displayed ad and timer`() {
        val helper = helper(reload = true).applyReloadByTime(10_000)
        helper.requestAds(NativeAdParam.Request)
        val first = NativeVendorAd()
        requests.single().fill(first)
        main.idleFor(10, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(2, requests.size)
        assertSame(first, helper.nativeAd?.admobNativeAd)
        requests.last().fail()
        assertSame(first, helper.nativeAd?.admobNativeAd)
        assertFalse(first.destroyed)
        controller.pause().stop()
        assertTrue("departure ends the displayed ad even if Activity stays on back stack", first.destroyed)
        main.idleFor(30, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(2, requests.size)
        controller.restart().start().resume()
        helper.requestAds(NativeAdParam.Request)
        assertEquals(3, requests.size)
    }

    @Test fun `rotation restores the displayed ad without a new request or resetting refresh time`() {
        val old = helper(reload = true).applyReloadByTime(10_000)
        old.requestAds(NativeAdParam.Request)
        val first = NativeVendorAd()
        requests.single().fill(first)
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        main.idleFor(3, java.util.concurrent.TimeUnit.SECONDS)
        val oldActivity = activity
        controller.configurationChange(android.content.res.Configuration(activity.resources.configuration).apply {
            orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
        })
        assertNotSame(oldActivity, activity)
        controller.visible()
        val restored = helper(reload = true).applyReloadByTime(10_000)
        restored.requestAds(NativeAdParam.Request)
        assertFalse("configuration recreation must not dispose the current presentation", first.destroyed)
        assertSame(first, restored.nativeAd?.admobNativeAd)
        assertNull(preload.getAdNative("a"))
        assertEquals(1, requests.size)
        assertTrue(activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0).isShown)
        val remaining = deadline - android.os.SystemClock.elapsedRealtime()
        assertTrue(remaining > 0)
        main.idleFor(remaining - 1, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
        main.idleFor(1, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("the original 10 second deadline survives rotation", 2, requests.size)
    }

    @Test fun `failed replacement bind keeps the current ad and disposes the failed candidate`() {
        val helper = helper()
        helper.requestAds(NativeAdParam.Request)
        val first = NativeVendorAd()
        requests.single().fill(first)
        helper.setNativeAdBinder { _, _, _, _ -> error("invalid host layout") }
        helper.requestAds(NativeAdParam.Request)
        val second = NativeVendorAd()
        requests.last().fill(second)
        assertSame(first, helper.nativeAd?.admobNativeAd)
        assertFalse(first.destroyed)
        assertTrue(second.destroyed)
        assertTrue(helper.nativeAdState.value is AdNativeState.Loaded)
    }

    @Test fun `show without a container keeps the unused fill for later attachment`() {
        val helper = NativeAdHelper(activity, activity, config).also { it.placement = "a" }
            .setNativeAdBinder { _, ad, container, _ -> container.tag = ad }
        helper.requestAds(NativeAdParam.Request)
        val ad = NativeVendorAd()
        requests.single().fill(ad)
        assertTrue(NativeAdManager.isReady("a"))
        assertFalse(ad.destroyed)
        helper.setNativeContentView(android.widget.FrameLayout(activity).also(activity::setContentView))
        assertSame(ad, helper.nativeAd?.admobNativeAd)
        assertFalse(NativeAdManager.isReady("a"))
        assertEquals(1, requests.size)
    }

    @Test fun `onCreate show after rotation restores presentation only when resumed without recaching it`() {
        val old = helper()
        old.show()
        val first = NativeVendorAd()
        requests.single().fill(first)
        var restored: NativeAdHelper? = null
        NativeHostActivity.onCreated = { host ->
            restored = NativeAdHelper(host, host, config).also { it.placement = "a" }
                .setNativeAdBinder { _, ad, container, _ -> container.tag = ad }
                .setNativeContentView(android.widget.FrameLayout(host))
            restored!!.show()
            assertFalse("a consumed presentation never returns to the unused cache", NativeAdManager.isReady("a"))
        }
        controller.configurationChange(android.content.res.Configuration(activity.resources.configuration).apply {
            orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
        })
        assertSame(first, restored?.nativeAd?.admobNativeAd)
        assertEquals(1, requests.size)
        assertFalse(first.destroyed)
    }

    @Test fun `rotation while replacement loads keeps old presentation and joins the replacement`() {
        val old = helper(reload = true).applyReloadByTime(10_000)
        old.show()
        val first = NativeVendorAd()
        requests.single().fill(first)
        main.idleFor(10, java.util.concurrent.TimeUnit.SECONDS)
        val pending = requests.last()
        controller.configurationChange(android.content.res.Configuration(activity.resources.configuration).apply {
            orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
        })
        val restored = helper(reload = true).applyReloadByTime(10_000)
        restored.show()
        assertSame(first, restored.nativeAd?.admobNativeAd)
        assertEquals(2, requests.size)
        val second = NativeVendorAd()
        pending.fill(second)
        assertSame(second, restored.nativeAd?.admobNativeAd)
        assertTrue(first.destroyed)
        assertFalse(NativeAdManager.isReady("a"))
    }

    @Test fun `fill arriving after departure is cached for a new visit and not sent to old view`() {
        val old = helper()
        old.show()
        controller.pause().stop().destroy()
        val ad = NativeVendorAd()
        requests.single().fill(ad)
        assertTrue(NativeAdManager.isReady("a"))
        assertNull(old.nativeAd)
        controller = Robolectric.buildActivity(NativeHostActivity::class.java).setup()
        val returned = helper()
        returned.show()
        assertSame(ad, returned.nativeAd?.admobNativeAd)
        assertEquals(1, requests.size)
    }

    @Test fun `expired unused fill is disposed and placements remain independent even with same unit`() {
        preload.preloadWithKey("a", activity, config)
        val ad = NativeVendorAd()
        requests.single().fill(ad)
        main.idleFor(61, java.util.concurrent.TimeUnit.MINUTES)
        assertFalse(NativeAdManager.isReady("a"))
        assertTrue(ad.destroyed)
        preload.preloadWithKey("a", activity, config, buffer = 4)
        preload.preloadWithKey("b", activity, config)
        assertEquals(3, requests.size)
        requests[1].fill(NativeVendorAd())
        requests[2].fill(NativeVendorAd())
        assertEquals("legacy buffer count cannot start extra batches", 3, requests.size)
        assertTrue(NativeAdManager.isReady("a"))
        assertTrue(NativeAdManager.isReady("b"))
    }

    @Test fun `explicit release keeps pending work deduplicated and discards its late fill`() {
        preload.preloadWithKey("a", activity, config)
        preload.release("a")
        preload.preloadWithKey("a", activity, config)
        assertEquals(1, requests.size)
        val discarded = NativeVendorAd()
        requests.single().fill(discarded)
        assertTrue(discarded.destroyed)
        assertFalse(NativeAdManager.isReady("a"))
        preload.preloadWithKey("a", activity, config)
        assertEquals(2, requests.size)
    }

    @Test fun `consent revoked during replacement removes both the old and arriving ad`() {
        val helper = helper()
        helper.show()
        val first = NativeVendorAd()
        requests.single().fill(first)
        helper.show()
        ConsentCenter.setHostConsent(false, false)
        val second = NativeVendorAd()
        requests.last().fill(second)
        assertTrue(second.destroyed)
        assertTrue(first.destroyed)
        assertNull(helper.nativeAd)
    }

    @Test fun `refresh does not request while the native slot is hidden`() {
        val helper = helper(reload = true).applyReloadByTime(10_000)
        helper.show()
        requests.single().fill(NativeVendorAd())
        val slot = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        slot.visibility = android.view.View.GONE
        main.idleFor(10, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(1, requests.size)
        slot.visibility = android.view.View.VISIBLE
        main.idleFor(10, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(2, requests.size)
    }

    @Test fun `offline refresh can use a buffered ad and keeps its survivor when no replacement is available`() {
        val helper = helper(reload = true).applyReloadByTime(10_000)
        helper.show()
        requests.single().fill(NativeVendorAd())
        preload.preloadWithKey("a", activity, config)
        val second = NativeVendorAd()
        requests.last().fill(second)
        val connectivity = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, NetworkCapabilities())
        main.idleFor(10, java.util.concurrent.TimeUnit.SECONDS)
        assertSame(second, helper.nativeAd?.admobNativeAd)
        main.idleFor(10, java.util.concurrent.TimeUnit.SECONDS)
        assertSame(second, helper.nativeAd?.admobNativeAd)
        assertTrue(helper.nativeAdState.value is AdNativeState.Loaded)
        assertEquals(2, requests.size)
    }

    @Test fun `legacy Ready binding also removes the supplied ad from unused cache`() {
        preload.preloadWithKey("a", activity, config)
        requests.single().fill(NativeVendorAd())
        val ad = checkNotNull(preload.getAdNative("a"))
        val helper = helper()
        helper.requestAds(NativeAdParam.Ready(ad))
        assertSame(ad, helper.nativeAd)
        assertFalse(NativeAdManager.isReady("a"))
    }

}

class NativeRequest(val context: Context, val unit: String,
    val loaded: NativeAd.OnNativeAdLoadedListener, val listener: AdListener) {
    fun fill(ad: NativeVendorAd) { loaded.onNativeAdLoaded(ad) }
    fun fail() { listener.onAdFailedToLoad(LoadAdError(3, "No fill", "test", null, null)) }
}

class NativeVendorAd : NativeAd() {
    var destroyed = false
    override fun destroy() { destroyed = true }
    override fun getHeadline() = "Native test ad"
    override fun getImages() = emptyList<Image>()
    override fun getBody(): String? = null
    override fun getIcon(): Image? = null
    override fun getCallToAction() = "Open"
    override fun getAdvertiser(): String? = null
    override fun getStarRating(): Double? = null
    override fun getStore(): String? = null
    override fun getPrice(): String? = null
    override fun getAdChoicesInfo(): AdChoicesInfo? = null
    override fun isCustomMuteThisAdEnabled() = false
    override fun getMuteThisAdReasons() = emptyList<MuteThisAdReason>()
    override fun muteThisAd(reason: MuteThisAdReason) {}
    override fun setMuteThisAdListener(listener: MuteThisAdListener) {}
    override fun getExtras() = Bundle()
    override fun setUnconfirmedClickListener(listener: UnconfirmedClickListener) {}
    override fun cancelUnconfirmedClick() {}
    override fun enableCustomClickGesture() {}
    override fun isCustomClickGestureEnabled() = false
    override fun recordCustomClickGesture() {}
    override fun performClick(bundle: Bundle) {}
    override fun recordImpression(bundle: Bundle) = true
    override fun reportTouchEvent(bundle: Bundle) {}
    override fun getMediaContent(): MediaContent? = null
    override fun getResponseInfo(): ResponseInfo? = null
    override fun setOnPaidEventListener(listener: OnPaidEventListener?) {}
    override fun recordEvent(bundle: Bundle) {}
    override fun getPlacementId() = 0L
    override fun setPlacementId(id: Long) {}
    override fun zza(): Any? = null
}

class NativeHostActivity : androidx.appcompat.app.AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(com.ads.module.R.style.AppTheme)
        super.onCreate(savedInstanceState)
        onCreated?.invoke(this)
    }
    companion object { var onCreated: ((NativeHostActivity) -> Unit)? = null }
}
