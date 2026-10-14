package io.onboardkit.ads.pool

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.helper.adnative.NativeAdConfig
import com.ads.module.helper.adnative.NativeAdManager
import com.ads.module.helper.adnative.pool.LoadMode
import com.ads.module.helper.adnative.pool.LoadResult
import com.ads.module.helper.adnative.pool.NativePoolEngine
import com.ads.module.helper.adnative.pool.PoolSelection
import com.ads.module.helper.adnative.pool.PoolState
import com.ads.module.helper.adnative.pool.SharedNativePool
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeStatus
import io.onboardkit.ads.pool.PoolSplashAdoption.Status
import io.onboardkit.core.ObLog
import io.onboardkit.flow.NativeFlowSnapshot
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.TimeUnit

/** The splash's real LFO1 preload in NativeAdManager, with only GMA's loader replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, instrumentedPackages = ["io.onboardkit.ads.pool"])
@LooperMode(LooperMode.Mode.PAUSED)
class PoolSplashAdoptionTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var builders: MockedConstruction<AdLoader.Builder>
    private val splashRequests = mutableListOf<NativeAd.OnNativeAdLoadedListener>()
    private val splashListeners = mutableListOf<AdListener>()
    private lateinit var loader: ApFakeLoader
    private lateinit var engine: NativePoolEngine<ApNativeAd>
    private val key = AdPlacement.Language1.key

    @Before fun setUp() {
        PoolTestSdk.install(app)
        NativeAdManager.releaseAll()
        builders = mockConstruction(AdLoader.Builder::class.java) { builder, _ ->
            var loaded: NativeAd.OnNativeAdLoadedListener? = null
            var listener: AdListener? = null
            doAnswer { loaded = it.getArgument(0); builder }.`when`(builder).forNativeAd(any())
            doAnswer { listener = it.getArgument(0); builder }.`when`(builder).withAdListener(any(AdListener::class.java))
            doReturn(builder).`when`(builder).withNativeAdOptions(any())
            val adLoader = mock(AdLoader::class.java)
            doReturn(adLoader).`when`(builder).build()
            doAnswer {
                splashRequests += checkNotNull(loaded)
                splashListeners += checkNotNull(listener)
                null
            }.`when`(adLoader).loadAd(any(AdRequest::class.java))
        }
        loader = ApFakeLoader()
        engine = installTestEngine(loader)
        OnboardingPool.activate(app, poolSnapshot())
        main.idle()
        ShadowLog.clear()
    }

    @After fun tearDown() {
        PoolTestSdk.tearDown()
        NativeAdManager.releaseAll()
        builders.close()
        main.idleFor(61, TimeUnit.SECONDS)
    }

    private fun startSplashLoad() {
        val config = NativeAdConfig(listOf("splash-lfo1"), true, false, R.layout.ob_layout_native_lfo)
        assertTrue(NativeAdManager.preload(app, key, config, reportTelemetry = false))
        assertTrue(NativeAdManager.isLoading(key))
    }

    private fun splashFill(): NativeAd = mock(NativeAd::class.java).also {
        splashRequests.last().onNativeAdLoaded(it)
        main.idle()
    }

    private fun splashFail() {
        splashListeners.last().onAdFailedToLoad(LoadAdError(3, "No fill", "test", null, null))
        main.idle()
    }

    private fun run(handoff: Boolean = true, snapshot: NativeFlowSnapshot = poolSnapshot()): Status =
        PoolSplashAdoption.run(handoff, snapshot).also { main.idle() }

    private fun lfo1() = engine.debugSnapshot().loader(key)

    private fun claimLfo1() = OnboardingPool.claim(AdPlacement.Language1, LoadMode.POLITE)

    private fun adoptLog(): List<String> = ShadowLog.getLogsForTag(ObLog.TAG).map { it.msg }
        .filter { it.contains("pool | adopt language1") }.map { it.substringAfter("pool | ") }

    @Test fun `a ready splash fill is adopted and the LFO claim requests nothing`() {
        startSplashLoad()
        val vendor = splashFill()
        assertTrue(NativeAdManager.isReady(key))

        assertEquals(Status.READY, run())
        assertFalse("Polled out of the manager", NativeAdManager.isReady(key))
        assertTrue(engine.isAdoptedUnshown(key))
        assertSame(vendor, lfo1()?.ad?.admobNativeAd)

        assertEquals(LoadResult.NoOpState(lfo1()!!.state, false), claimLfo1())
        assertTrue(lfo1()!!.state is PoolState.Loaded)
        assertEquals("language1", engine.owner)
        assertEquals(PoolSelection(key, lfo1()!!.ad, true), engine.currentSelection())
        assertEquals("One LFO1 chain: the splash's", 1, splashRequests.size)
        assertTrue(loader.callsFor(key).isEmpty())
        assertEquals(listOf("adopt language1 status=ready handoff=true"), adoptLog())
        assertEquals("The adopted chain is counted once, by the adoption", listOf(key), PoolTestSdk.requested())
    }

    @Test fun `a splash load in flight is joined and its fill lands in the pool`() {
        startSplashLoad()
        assertEquals(Status.PENDING, run())
        assertTrue(lfo1()!!.inFlight)

        assertEquals("The joined load closes the gate, as an LFO1 chain would", LoadResult.SkippedByGate, claimLfo1())
        assertEquals("language1", engine.owner)
        assertEquals(LoadResult.NoOpState(PoolState.None, true),
            OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED))
        val vendor = splashFill()
        assertSame(vendor, lfo1()?.ad?.admobNativeAd)
        assertTrue(lfo1()!!.state is PoolState.Loaded)
        assertFalse(lfo1()!!.inFlight)
        assertEquals(1, splashRequests.size)
        assertTrue(loader.callsFor(key).isEmpty())
        assertEquals(listOf("adopt language1 status=pending handoff=true"), adoptLog())
        assertEquals("The joined chain is counted once, by the adoption", listOf(key), PoolTestSdk.requested())
    }

    @Test fun `a load still queued for its request window is cancelled so the pool's chain is the only one`() {
        PoolTestProvider.language1Status = NativeStatus.LOADING
        assertEquals(Status.CANCELLED_QUEUE, run())
        assertEquals(listOf<AdPlacement>(AdPlacement.Language1), PoolTestProvider.released)
        assertNull("Nothing to adopt", lfo1())

        assertEquals(LoadResult.Requested, claimLfo1())
        assertEquals(listOf("lfo1-high"), loader.callsFor(key))
        assertTrue(splashRequests.isEmpty())
        assertEquals(listOf("adopt language1 status=cancelled_queue handoff=true"), adoptLog())
        assertEquals("Counted by the claim that requests", listOf(key), PoolTestSdk.requested())
    }

    @Test fun `a failed splash load leaves the LFO to its own chain`() {
        startSplashLoad()
        splashFail()
        assertTrue(NativeAdManager.isFailed(key))
        PoolTestProvider.language1Status = NativeStatus.FAILED

        assertEquals(Status.NONE, run())
        assertNull(lfo1())
        assertTrue(PoolTestProvider.released.isEmpty())
        assertEquals(LoadResult.Requested, claimLfo1())
        assertEquals(listOf("lfo1-high"), loader.callsFor(key))
        assertEquals(listOf("adopt language1 status=none handoff=true"), adoptLog())
        assertEquals(listOf(key), PoolTestSdk.requested())
    }

    @Test fun `without a handoff the splash's buffer is not the LFO's to take`() {
        startSplashLoad()
        splashFill()
        assertEquals(Status.NONE, run(handoff = false))
        assertTrue(NativeAdManager.isReady(key))
        assertNull(lfo1())
        assertEquals(LoadResult.Requested, claimLfo1())
        assertEquals(listOf("lfo1-high"), loader.callsFor(key))
        assertEquals(listOf("adopt language1 status=none handoff=false"), adoptLog())
    }

    @Test fun `with adoption off the splash preload is left alone`() {
        startSplashLoad()
        splashFill()
        PoolTestProvider.language1Status = NativeStatus.READY
        assertEquals(Status.OFF, run(snapshot = poolSnapshot(adoptSplashLanguage1 = false)))
        assertTrue(NativeAdManager.isReady(key))
        assertTrue(PoolTestProvider.released.isEmpty())
        assertNull(lfo1())
        assertEquals(LoadResult.Requested, claimLfo1())
        assertEquals(listOf("lfo1-high"), loader.callsFor(key))
        assertEquals(listOf("adopt language1 status=off handoff=true"), adoptLog())
        assertEquals(listOf(key), PoolTestSdk.requested())
    }

    @Test fun `without a pool nothing is adopted or cancelled`() {
        SharedNativePool.resetForTest()
        PoolTestProvider.language1Status = NativeStatus.LOADING
        assertEquals(Status.NONE, run())
        assertTrue(PoolTestProvider.released.isEmpty())
        assertTrue(PoolTestSdk.requested().isEmpty())
    }

    @Test fun `the handoff flag is the caller's to consume, whether or not adoption runs`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val preload = OnboardingSdk.preload()
        preload.preloadLanguage1(activity)
        assertEquals(Status.NONE, run(handoff = true))
        assertTrue("run() never reads or clears the flag", preload.takeLanguage1Preload())

        preload.preloadLanguage1(activity)
        assertEquals(Status.OFF, run(handoff = true, snapshot = poolSnapshot(adoptSplashLanguage1 = false)))
        assertTrue("Nor with adoption off: the LFO consumes it", preload.takeLanguage1Preload())
    }
}
