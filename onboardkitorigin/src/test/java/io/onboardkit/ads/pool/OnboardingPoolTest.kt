package io.onboardkit.ads.pool

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.helper.adnative.pool.LoadMode
import com.ads.module.helper.adnative.pool.LoadResult
import com.ads.module.helper.adnative.pool.NativePoolEngine
import com.ads.module.helper.adnative.pool.PoolEvent
import com.ads.module.helper.adnative.pool.PoolState
import com.ads.module.helper.adnative.pool.SharedNativePool
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.core.ObLog
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
import io.trackkit.AdFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, instrumentedPackages = ["io.onboardkit.ads.pool"])
@LooperMode(LooperMode.Mode.PAUSED)
class OnboardingPoolTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var loader: ApFakeLoader
    private lateinit var engine: NativePoolEngine<ApNativeAd>
    private val ob1 = AdPlacement.StepNative(StepId.OB1)
    private val ob2 = AdPlacement.StepNative(StepId.OB2)

    /** OB2 has no unit: its claims are config refusals. */
    private val ads = AdsConfig(
        languageNative = LFO1,
        languageDupNative = LFO2,
        stepNatives = mapOf(StepId.OB1 to NativeAdUnit("ob1-unit")),
        resumeNative = NativeAdUnit("resume"),
    )

    @Before fun setUp() {
        PoolTestSdk.install(app, ads)
        loader = ApFakeLoader()
        engine = installTestEngine(loader)
        ShadowLog.clear()
        activate()
    }

    @After fun tearDown() {
        PoolTestSdk.tearDown()
    }

    private fun activate(snapshot: io.onboardkit.flow.NativeFlowSnapshot = poolSnapshot()) {
        OnboardingPool.activate(app, snapshot)
        main.idle()
    }

    private fun poolLog(): List<String> =
        ShadowLog.getLogsForTag(ObLog.TAG).map { it.msg.substringAfter("| ", it.msg) }

    @Test fun `claim owns the slot on every outcome but a config refusal`() {
        assertEquals(LoadResult.Requested, OnboardingPool.claim(AdPlacement.Language1, LoadMode.POLITE))
        assertEquals("language1", engine.owner)

        val refused = OnboardingPool.claim(ob2, LoadMode.POLITE)
        assertEquals(LoadResult.SkippedByConfig(AdSkipReason.NO_AD_UNIT.key), refused)
        assertEquals("language1", engine.owner)

        assertEquals(LoadResult.SkippedByGate, OnboardingPool.claim(AdPlacement.Language2, LoadMode.POLITE))
        assertEquals("A gate skip still reports success", "language2", engine.owner)

        setOnline(app, false)
        assertEquals(LoadResult.SkippedTransient(AdSkipReason.OFFLINE.key), OnboardingPool.claim(ob1, LoadMode.FORCED))
        assertEquals("step_ob1", engine.owner)
    }

    @Test fun `preload never moves the owner`() {
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        loader.fill("language1")
        assertEquals(LoadResult.Requested, OnboardingPool.preload(ob1))
        assertEquals("language1", engine.owner)
        assertEquals(LoadResult.SkippedByConfig(AdSkipReason.NO_AD_UNIT.key), OnboardingPool.preload(ob2))
        assertEquals("language1", engine.owner)
    }

    @Test fun `requests and refusals reach analytics as in the classic flow`() {
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        OnboardingPool.preload(ob2)
        setOnline(app, false)
        OnboardingPool.claim(AdPlacement.Language2, LoadMode.FORCED)

        assertEquals("A no-op load requests nothing", listOf("language1"), PoolTestSdk.requested())
        assertEquals(
            listOf("step_ob2" to AdSkipReason.NO_AD_UNIT.key, "language2" to AdSkipReason.OFFLINE.key),
            PoolTestSdk.skipped(),
        )
    }

    @Test fun `a chain that ends holding nothing reports one ad failure, a failed refresh none`() {
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        loader.exhaust("language1")
        main.idle()
        val failed = PoolTestSdk.events<AnalyticsEvent.AdFailed>()
        assertEquals(listOf("language1" to AdFormat.NATIVE), failed.map { it.placementName to it.format })

        assertEquals(LoadResult.Requested, OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED))
        loader.fill("language1")
        assertTrue(OnboardingPool.refresh(AdPlacement.Language1))
        loader.exhaust("language1")
        main.idle()
        assertEquals("A failed refresh keeps its ad", 1, PoolTestSdk.events<AnalyticsEvent.AdFailed>().size)
    }

    @Test fun `a gate skip is traced and counted but is no skipped-ad event`() {
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        assertEquals(LoadResult.SkippedByGate, OnboardingPool.preload(AdPlacement.ResumeNative))

        assertTrue(PoolTestSdk.skipped().isEmpty())
        assertEquals(listOf("language1"), PoolTestSdk.requested())
        assertTrue(poolLog().toString(), "pool | gate_skip key=native_resume" in poolLog())
        assertEquals("One line per skip", 1, poolLog().count { it.startsWith("pool | gate_skip") })
        assertTrue(OnboardingPool.dump(), OnboardingPool.dump().contains("runGateSkips=1 {native_resume=1}"))
        assertTrue(OnboardingPool.dump().contains("gateSkipCount=1"))
    }

    @Test fun `a replayed polite load reports its request like a direct one`() {
        activate(poolSnapshot(SYNC_PARITY.copy(retrySkippedPolite = true)))
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        assertEquals(LoadResult.SkippedByGate, OnboardingPool.preload(ob1))
        assertEquals(listOf("language1"), PoolTestSdk.requested())

        loader.fill("language1")
        assertEquals(listOf("ob1-unit"), loader.callsFor("step_ob1"))
        assertEquals(listOf("language1", "step_ob1"), PoolTestSdk.requested())
        assertEquals(LoadResult.Requested, OnboardingPool.lastResult(ob1))
        assertEquals("language1", engine.owner)
    }

    @Test fun `a replay refused for now reports its skip`() {
        activate(poolSnapshot(SYNC_PARITY.copy(retrySkippedPolite = true)))
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        OnboardingPool.preload(ob1)
        setOnline(app, false)
        loader.fill("language1")
        assertEquals(listOf("step_ob1" to AdSkipReason.OFFLINE.key), PoolTestSdk.skipped())
        assertEquals(LoadResult.SkippedTransient(AdSkipReason.OFFLINE.key), OnboardingPool.lastResult(ob1))
    }

    @Test fun `activating again starts a new run on the same pool`() {
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        val ad = loader.fill("language1")
        assertEquals("language1", engine.owner)

        activate()
        assertNull("A new run has no owner", engine.owner)
        assertSame(engine, OnboardingPool.engineOrNull())
        assertSame("DashDrama keeps loaders across runs", ad, engine.debugSnapshot().loader("language1")?.ad)
        assertNull(OnboardingPool.lastResult(AdPlacement.Language1))
        assertTrue(OnboardingPool.isActive)
        assertTrue(OnboardingPool.routeResume)

        activate(poolSnapshot(SYNC_PARITY.copy(resetOnNewRun = true)))
        assertTrue(engine.debugSnapshot().loaders.isEmpty())
    }

    @Test fun `a classic run turns resume routing off and releases the pool`() {
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        loader.fill("language1")
        OnboardingPool.onClassicRun()
        assertFalse(OnboardingPool.routeResume)
        assertFalse(OnboardingPool.isActive)
        assertTrue(engine.debugSnapshot().loaders.isEmpty())
        assertNull(OnboardingPool.lastResult(AdPlacement.Language1))

        activate()
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        OnboardingPool.deactivate()
        assertFalse(OnboardingPool.routeResume)
        assertTrue(engine.debugSnapshot().loaders.isEmpty())
    }

    @Test fun `run switches from another thread hop to main and the latest one wins`() {
        OnboardingPool.onClassicRun()
        main.idle()
        Thread { OnboardingPool.activate(app, poolSnapshot()) }.apply { start(); join() }
        assertFalse("Not applied before main runs it", OnboardingPool.routeResume)
        main.idle()
        assertTrue(OnboardingPool.routeResume)

        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        Thread { OnboardingPool.deactivate() }.apply { start(); join() }
        assertFalse("Routing stops at once", OnboardingPool.routeResume)
        assertEquals(1, engine.debugSnapshot().loaders.size)
        main.idle()
        assertTrue(engine.debugSnapshot().loaders.isEmpty())

        Thread { OnboardingPool.activate(app, poolSnapshot()) }.apply { start(); join() }
        OnboardingPool.onClassicRun()
        main.idle()
        assertFalse("A queued activation never overrides a later classic run", OnboardingPool.routeResume)
        assertFalse(OnboardingPool.isActive)
    }

    @Test fun `a stop begun before a later activation never turns that run off`() {
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        loader.fill("language1")
        Thread { OnboardingPool.deactivate() }.apply { start(); join() }
        assertFalse(OnboardingPool.isActive)

        activate()
        assertTrue(OnboardingPool.routeResume)
        assertTrue(OnboardingPool.isActive)
        assertEquals("The stale stop released nothing", 1, engine.debugSnapshot().loaders.size)

        OnboardingPool.resetForTest()
        assertFalse(OnboardingPool.routeResume)
        assertFalse(OnboardingPool.isActive)
    }

    @Test fun `releaseAll drops the ads but keeps the run`() {
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        loader.fill("language1")
        OnboardingPool.releaseAll()
        assertTrue(engine.debugSnapshot().loaders.isEmpty())
        assertTrue(OnboardingPool.routeResume)
        assertTrue(OnboardingPool.isActive)
    }

    @Test fun `lastResult is the latest outcome per placement`() {
        assertNull(OnboardingPool.lastResult(AdPlacement.Language1))
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        assertEquals(LoadResult.Requested, OnboardingPool.lastResult(AdPlacement.Language1))
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        assertEquals(LoadResult.NoOpState(PoolState.None, true), OnboardingPool.lastResult(AdPlacement.Language1))
        OnboardingPool.preload(ob2)
        assertEquals(LoadResult.SkippedByConfig(AdSkipReason.NO_AD_UNIT.key), OnboardingPool.lastResult(ob2))
    }

    @Test fun `a listener hears a refusal after lastResult already holds it`() {
        val seen = mutableListOf<LoadResult?>()
        val subscription = OnboardingPool.addEventListener { event ->
            if (event is PoolEvent.Skipped) seen += OnboardingPool.lastResult(ob2)
        }
        OnboardingPool.claim(ob2, LoadMode.POLITE)
        assertEquals(listOf<LoadResult?>(LoadResult.SkippedByConfig(AdSkipReason.NO_AD_UNIT.key)), seen)
        subscription.cancel()
        OnboardingPool.claim(ob2, LoadMode.POLITE)
        assertEquals(1, seen.size)
    }

    @Test fun `the pool runs only on the ERain stack or a test engine`() {
        assertTrue(OnboardingPool.canRun())
        SharedNativePool.resetForTest()
        assertFalse("A fake provider cannot drive AdMob", OnboardingPool.canRun())
        assertFalse(OnboardingPool.isActive)
        assertEquals(LoadResult.SkippedByConfig(AdSkipReason.NO_PROVIDER.key),
            OnboardingPool.claim(AdPlacement.Language1, LoadMode.POLITE))
    }
}
