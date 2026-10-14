package com.ads.module.helper.adnative.pool

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.R
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.helper.adnative.NativeAdConfig
import com.ads.module.helper.adnative.NativeAdManager
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02FacebookShadow
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Int02Application::class,
    shadows = [Int02MobileAdsShadow::class, Int02FacebookShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SharedNativePoolTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var gma: GmaRequests
    private val destroyed = mutableListOf<ApNativeAd>()
    private val events = mutableListOf<PoolEvent<ApNativeAd>>()

    /** Ads the fake loader produced, per pool key, so tests can fill an attempt. */
    private class ApFakeLoader : PoolAdLoader<ApNativeAd> {
        val sinks = mutableListOf<Pair<String, PoolLoadSink<ApNativeAd>>>()
        override fun load(request: PoolRequest, id: String, sink: PoolLoadSink<ApNativeAd>) {
            sinks += request.key to sink
        }

        fun fill(key: String): ApNativeAd =
            ApNativeAd(R.layout.custom_native_admob_medium, PoolVendorAd()).also { ad ->
                sinks.last { it.first == key }.second.onLoaded(ad)
            }
    }

    private val fakeLoader = ApFakeLoader()

    private val request = PoolRequest("language1", listOf("unit-a"), R.layout.custom_native_admob_medium, "native_lang")

    private fun installEngine(): NativePoolEngine<ApNativeAd> {
        val clock = FakeClock()
        return NativePoolEngine(
            loader = fakeLoader,
            policy = PoolRequestPolicy.ALLOW_ALL,
            parity = SYNC,
            clock = clock,
            scheduler = ManualScheduler(clock),
            expiry = PoolExpiry { 0L },
            derivedDispatcher = Dispatchers.Unconfined,
            destroyAd = { destroyed += it; it.destroy() },
        ).also {
            it.addEventListener { event -> events += event }
            SharedNativePool.installForTest(it)
        }
    }

    private fun startSplashLoad() {
        val config = NativeAdConfig(listOf("unit-a"), true, false, R.layout.custom_native_admob_medium)
        assertTrue(NativeAdManager.preload(app, request.key, config, reportTelemetry = false))
        assertTrue(NativeAdManager.isLoading(request.key))
    }

    private fun loader(key: String = request.key) =
        SharedNativePool.engineOrNull()!!.debugSnapshot().loader(key)

    /** Whether NativeAdManager still keeps a listener entry, and so a reference, for [ad]. */
    private fun managerObserves(ad: ApNativeAd): Boolean {
        val field = NativeAdManager::class.java.getDeclaredField("adListeners").apply { isAccessible = true }
        return (field.get(NativeAdManager) as Map<*, *>).containsKey(ad)
    }

    /** Counts events without keeping the ads they carry. */
    private class CountingSink : PoolLoadSink<ApNativeAd> {
        var impressions = 0
        var clicks = 0
        override fun onLoaded(ad: ApNativeAd) = Unit
        override fun onFailed() = Unit
        override fun onImpression(ad: ApNativeAd) { impressions++ }
        override fun onClicked(ad: ApNativeAd) { clicks++ }
    }

    /** Built in its own frame, so only the callback's reference to the ad is left on return. */
    private fun observedCallback(sink: CountingSink): Pair<AdCallback, WeakReference<ApNativeAd>> {
        val ad = ApNativeAd(R.layout.custom_native_admob_medium, PoolVendorAd())
        val callback = SharedNativePool.eventsInto(ad, sink)
        callback.onAdImpression()
        callback.onAdClicked()
        callback.onAdOpened()
        return callback to WeakReference(ad)
    }

    private fun awaitCollected(ref: WeakReference<*>) {
        repeat(50) {
            if (ref.get() == null) return
            System.gc()
            Thread.sleep(10)
        }
        assertNull("Still strongly reachable", ref.get())
    }

    @Before fun setUp() {
        SharedNativePool.resetForTest()
        NativeAdManager.releaseAll()
        AdBehavior.document.install(null, null)
        allowAds(app)
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply { setFacebookClientToken("pool-test") })
        gma = GmaRequests()
        main.idle()
    }

    @After fun tearDown() {
        SharedNativePool.resetForTest()
        NativeAdManager.releaseAll()
        gma.close()
        ConsentCenter.clearHostConsent()
        main.idleFor(31, TimeUnit.SECONDS)
    }

    @Test fun `configure builds the engine once and later calls only update it`() {
        assertNull(SharedNativePool.engineOrNull())
        val firstPolicy = PoolRequestPolicy.ALLOW_ALL
        val first = SharedNativePool.configure(app, SYNC, firstPolicy, PoolTraceSink.NONE, 60_000L)
        assertSame(first, SharedNativePool.engineOrNull())

        val policy = object : PoolRequestPolicy {
            override fun configRefusal(request: PoolRequest): String? = "premium"
            override fun transientRefusal(request: PoolRequest): String? = null
        }
        val trace = PoolTraceSink { }
        val second = SharedNativePool.configure(
            app, PoolParity(borrowShowing = false, asyncGate = true, asyncSelector = true), policy, trace, 5_000L)
        assertSame(first, second)
        assertFalse(second.parity.borrowShowing)
        assertFalse("Fixed at construction", second.parity.asyncGate)
        assertFalse("Fixed at construction", second.parity.asyncSelector)
        assertSame(policy, second.policy)
        assertSame(trace, second.trace)
        assertEquals(5_000L, second.stuckLoadingLogMs)
        assertEquals(LoadResult.SkippedByConfig("premium"), second.load(request, LoadMode.FORCED))
    }

    @Test fun `the production engine expires ads by the shared native cache age`() {
        AdBehavior.document.install("""{"native":{"cache":{"max_age_ms":1234}}}""", null)
        val engine = SharedNativePool.configure(app, SYNC, PoolRequestPolicy.ALLOW_ALL, PoolTraceSink.NONE, 0L)
        assertEquals(1234L, AdBehavior.number("native.cache.max_age_ms"))
        val traces = mutableListOf<String>()
        engine.trace = PoolTraceSink { traces += it }
        engine.load(request, LoadMode.FORCED)
        gma.last().fill()
        assertTrue(engine.debugSnapshot().loader(request.key)!!.state is PoolState.Loaded)
        main.idleFor(1234, TimeUnit.MILLISECONDS)
        assertEquals(PoolState.None, engine.debugSnapshot().loader(request.key)!!.state)
        assertTrue(traces.any { it.startsWith("expired key=language1") })
    }

    @Test fun `releaseAllIfCreated with no pool creates nothing`() {
        SharedNativePool.releaseAllIfCreated()
        AdGate.releaseBufferedAds()
        assertNull(SharedNativePool.engineOrNull())
    }

    @Test fun `releasing buffered ads releases the pool`() {
        val engine = installEngine()
        engine.load(request, LoadMode.FORCED)
        val ad = fakeLoader.fill(request.key)
        AdGate.releaseBufferedAds()
        assertTrue(engine.debugSnapshot().loaders.isEmpty())
        assertEquals(listOf(ad), destroyed)
        assertSame("The pool itself stays", engine, SharedNativePool.engineOrNull())
    }

    @Test fun `a release from another thread is posted to main`() {
        val engine = installEngine()
        engine.load(request, LoadMode.FORCED)
        fakeLoader.fill(request.key)
        Thread { SharedNativePool.releaseAllIfCreated() }.apply { start(); join() }
        assertNotNull(loader())
        main.idle()
        assertTrue(engine.debugSnapshot().loaders.isEmpty())
    }

    @Test fun `mutating entry points refuse other threads`() {
        installEngine()
        val errors = mutableListOf<Throwable?>()
        Thread {
            errors += runCatching {
                SharedNativePool.configure(app, SYNC, PoolRequestPolicy.ALLOW_ALL, PoolTraceSink.NONE, 0L)
            }.exceptionOrNull()
            errors += runCatching { SharedNativePool.adoptReadyFromManager(request) }.exceptionOrNull()
            errors += runCatching { SharedNativePool.adoptPendingFromManager(request) }.exceptionOrNull()
        }.apply { start(); join() }
        assertEquals(3, errors.size)
        assertTrue(errors.toString(), errors.all { it is IllegalStateException })
    }

    @Test fun `a ready splash fill is polled into the pool and its events arrive through observe`() {
        val engine = installEngine()
        startSplashLoad()
        val vendor = gma.last().fill()
        assertTrue(NativeAdManager.isReady(request.key))

        assertTrue(SharedNativePool.adoptReadyFromManager(request))
        assertFalse("Polled, so no other screen can spend it", NativeAdManager.isReady(request.key))
        val held = loader()!!
        assertSame(vendor, held.ad!!.admobNativeAd)
        assertTrue(held.state is PoolState.Loaded)
        assertTrue(held.adopted)
        assertTrue(engine.isAdoptedUnshown(request.key))

        gma.last().listener.onAdImpression()
        gma.last().listener.onAdClicked()
        gma.last().listener.onAdOpened()
        assertTrue(loader()!!.state is PoolState.Showing)
        assertEquals(
            listOf(PoolEvent.Impression(request.key, held.ad!!), PoolEvent.Clicked(request.key, held.ad!!),
                PoolEvent.Clicked(request.key, held.ad!!)),
            events.filter { it is PoolEvent.Impression || it is PoolEvent.Clicked },
        )
        assertTrue("Adoption requests nothing itself", fakeLoader.sinks.isEmpty())
        assertTrue(managerObserves(held.ad!!))
    }

    @Test fun `the manager's listener for an adopted ad holds that ad weakly`() {
        val sink = CountingSink()
        val (callback, ad) = observedCallback(sink)
        assertEquals("Forwarded while the ad lives", 1, sink.impressions)
        assertEquals(2, sink.clicks)

        awaitCollected(ad)
        callback.onAdImpression()
        callback.onAdClicked()
        assertEquals(1, sink.impressions)
        assertEquals(2, sink.clicks)
    }

    @Test fun `ready adoption needs a pool and a ready fill`() {
        assertFalse(SharedNativePool.adoptReadyFromManager(request))
        startSplashLoad()
        gma.last().fill()
        assertFalse("No pool: the manager keeps its ad", SharedNativePool.adoptReadyFromManager(request))
        assertTrue(NativeAdManager.isReady(request.key))

        installEngine()
        NativeAdManager.releaseAll()
        assertFalse(SharedNativePool.adoptReadyFromManager(request))
        assertNull(loader())
    }

    @Test fun `a splash load in flight is joined and its fill lands in the pool`() {
        val engine = installEngine()
        startSplashLoad()
        assertTrue(SharedNativePool.adoptPendingFromManager(request))
        assertTrue(loader()!!.inFlight)
        assertFalse("The joined load closes the gate", engine.gateOpen.value)

        val vendor = gma.last().fill()
        val held = loader()!!
        assertSame(vendor, held.ad!!.admobNativeAd)
        assertTrue(held.state is PoolState.Loaded)
        assertFalse(held.inFlight)
        assertTrue(engine.gateOpen.value)
        assertFalse("The waiter took the fill", NativeAdManager.isReady(request.key))

        gma.last().listener.onAdImpression()
        assertTrue(loader()!!.state is PoolState.Showing)
        assertEquals(PoolEvent.Impression(request.key, held.ad!!), events.last())
        assertTrue(fakeLoader.sinks.isEmpty())
    }

    @Test fun `a joined splash load that fails ends as a load error`() {
        installEngine()
        startSplashLoad()
        assertTrue(SharedNativePool.adoptPendingFromManager(request))
        gma.last().fail()
        assertEquals(PoolState.LoadError, loader()!!.state)
        assertFalse(loader()!!.inFlight)
    }

    @Test fun `releasing the placement while the pool waits ends it as a load error`() {
        installEngine()
        startSplashLoad()
        assertTrue(SharedNativePool.adoptPendingFromManager(request))
        NativeAdManager.release(request.key)
        assertEquals(PoolState.LoadError, loader()!!.state)
        assertFalse(loader()!!.inFlight)

        val late = gma.last().fill() as PoolVendorAd
        assertNull("A discarded fill never reaches the pool", loader()!!.ad)
        assertEquals(1, late.destroyed)
    }

    @Test fun `a fill that lands after the pool released the joined load is destroyed and not observed`() {
        val engine = installEngine()
        startSplashLoad()
        assertTrue(SharedNativePool.adoptPendingFromManager(request))
        engine.releaseAll()

        gma.last().fill()
        assertTrue(engine.debugSnapshot().loaders.isEmpty())
        val stale = destroyed.single()
        assertNull("Destroyed unshown", stale.admobNativeAd)
        assertFalse("Nothing pins the stale fill", managerObserves(stale))
        assertFalse(NativeAdManager.isReady(request.key))
    }

    @Test fun `a release from another thread completes the joined load on main`() {
        installEngine()
        startSplashLoad()
        assertTrue(SharedNativePool.adoptPendingFromManager(request))
        val exhaustedOnMain = mutableListOf<Boolean>()
        SharedNativePool.engineOrNull()!!.addEventListener { event ->
            if (event is PoolEvent.Exhausted) exhaustedOnMain += Looper.myLooper() === Looper.getMainLooper()
        }

        Thread { NativeAdManager.release(request.key) }.apply { start(); join() }
        assertTrue("Not completed off main", loader()!!.inFlight)
        main.idle()
        assertEquals(PoolState.LoadError, loader()!!.state)
        assertFalse(loader()!!.inFlight)
        assertEquals(listOf(true), exhaustedOnMain)
    }

    @Test fun `joining the same running load again keeps its fill`() {
        val engine = installEngine()
        startSplashLoad()
        assertTrue(SharedNativePool.adoptPendingFromManager(request))
        assertTrue(SharedNativePool.adoptPendingFromManager(request))
        val vendor = gma.last().fill()
        assertSame(vendor, loader()!!.ad!!.admobNativeAd)
        assertTrue(loader()!!.state is PoolState.Loaded)
        assertTrue(destroyed.isEmpty())

        // A release while waiting, then a new join of the same load: that join gets the fill.
        NativeAdManager.releaseAll()
        engine.releaseAll()
        destroyed.clear()
        startSplashLoad()
        assertTrue(SharedNativePool.adoptPendingFromManager(request))
        engine.releaseAll()
        assertTrue(SharedNativePool.adoptPendingFromManager(request))
        val second = gma.last().fill()
        assertSame(second, loader()!!.ad!!.admobNativeAd)
        assertTrue(destroyed.isEmpty())
        assertTrue(managerObserves(loader()!!.ad!!))
    }

    @Test fun `pending adoption needs a pool and a running load`() {
        assertFalse(SharedNativePool.adoptPendingFromManager(request))
        installEngine()
        assertFalse(SharedNativePool.adoptPendingFromManager(request))
        assertNull(loader())
    }

    @Test fun `resetForTest releases and forgets the pool`() {
        val engine = installEngine()
        engine.load(request, LoadMode.FORCED)
        val ad = fakeLoader.fill(request.key)
        SharedNativePool.resetForTest()
        assertNull(SharedNativePool.engineOrNull())
        assertEquals(listOf(ad), destroyed)
    }
}
