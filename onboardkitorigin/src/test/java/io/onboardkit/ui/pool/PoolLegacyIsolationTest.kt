package io.onboardkit.ui.pool

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.AnimationUtils
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.helper.adnative.pool.NativePoolEngine
import com.ads.module.helper.adnative.pool.SharedNativePool
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.StartOptions
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.ObNativeResume
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.ads.pool.ApFakeLoader
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolGlueViewShadow
import io.onboardkit.ads.pool.allowAds
import io.onboardkit.ads.pool.installTestEngine
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.SplashConfig
import io.onboardkit.config.StepDefinition
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.core.StepId
import io.onboardkit.flow.FlowDestination
import io.onboardkit.flow.NativeFlowType
import io.onboardkit.flow.StartDecision
import io.onboardkit.remote.ObRemoteKeys
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.remote.RemoteFlags
import io.onboardkit.ui.language.LanguageAdapter
import io.onboardkit.ui.language.LanguageScreenMode
import io.onboardkit.ui.language.ObLanguageActivity
import io.onboardkit.ui.onboarding.ObOnboardingHostActivity
import io.onboardkit.ui.resume.ObResumeActivity
import io.onboardkit.ui.splash.ObSplashActivity
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * SHARED_POOL must leave PER_SCREEN as it was: routing, resume, the splash's requests, config
 * identity and completion are compared or checked across both types through the real entry points.
 */
@RunWith(RobolectricTestRunner::class)
// Own sandbox: install() keeps the first provider.
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h780dp-xxhdpi",
    shadows = [PoolGlueViewShadow::class],
    instrumentedPackages = ["io.onboardkit.ui.pool", "io.onboardkit.ui.pager", "io.onboardkit.ui.resume"])
@LooperMode(LooperMode.Mode.PAUSED)
class PoolLegacyIsolationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var loader: ApFakeLoader
    private lateinit var locale: Locale
    private val controllers = mutableListOf<ActivityController<*>>()
    private val ob1 = ContentStepDefinition(StepId.OB1, title = "One")
    private val ob2 = ContentStepDefinition(StepId.OB2, title = "Two")
    private val vendor get() = ScenarioSdk.vendor

    @Before fun setUp() {
        locale = Locale.getDefault()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(FRAME_MS))
        AnimationUtils.currentAnimationTimeMillis()
        loader = ApFakeLoader()
    }

    @After fun tearDown() {
        controllers.asReversed().forEach(::close)
        controllers.clear()
        ReflectionHelpers.getField<Deferred<Unit>?>(OnboardingSdk, "remoteRefresh")?.cancel()
        ScenarioSdk.tearDown()
        ShadowChoreographer.reset()
        Locale.setDefault(locale)
    }

    // ── Harness ──

    /** One install per test: a second one would open a second store on the same file. */
    private fun installVendor(steps: List<StepDefinition> = listOf(ob1)): NativePoolEngine<ApNativeAd> {
        ScenarioSdk.install(app)
        ScenarioSdk.configure(steps)
        vendor.interstitialReady = false
        return installTestEngine(loader)
    }

    private fun pooledByRemote() {
        OnboardingSettings.document.acceptSuccessfulFetch("""{"native_flow":{"type":"SHARED_POOL"}}""")
        assertEquals(NativeFlowType.SHARED_POOL, OnboardingSettings.nativeFlowTypeOrDefault())
    }

    private fun classicByRemote() {
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        assertEquals(NativeFlowType.PER_SCREEN, OnboardingSettings.nativeFlowTypeOrDefault())
    }

    private fun close(controller: ActivityController<*>) {
        val owner = controller.get() as? LifecycleOwner
        runCatching {
            if (owner == null) {
                controller.pause().stop().destroy()
            } else {
                val state = owner.lifecycle.currentState
                if (state.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
                if (state.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
                if (state != Lifecycle.State.DESTROYED) controller.destroy()
            }
        }
        main.idle()
    }

    private fun pump() {
        main.idle()
        repeat(4) { main.idleFor(FRAME_MS, MILLISECONDS) }
    }

    private fun waitMs(ms: Long) {
        main.idleFor(ms, MILLISECONDS)
        pump()
    }

    private fun settle(pager: ViewPager2) {
        var frames = 0
        do main.idleFor(FRAME_MS, MILLISECONDS)
        while (pager.scrollState != ViewPager2.SCROLL_STATE_IDLE && ++frames < 1_000)
        pump()
    }

    /** The host app's own screen the flow starts from and native resume opens over. */
    private fun appScreen(): Activity = Robolectric.buildActivity(Activity::class.java).also { controllers += it }.setup().get()

    private fun startFlow(from: Activity) {
        OnboardingSdk.startResolved(from, StartDecision.Start(FlowDestination.LANGUAGE, 0))
        main.idle()
    }

    private fun <T : Activity> open(type: Class<T>, intent: Intent): ActivityController<T> {
        assertEquals(type.name, intent.component?.className)
        return Robolectric.buildActivity(type, intent).also {
            controllers += it
            it.get().setTheme(R.style.ob_Theme_OnboardKit)
            it.setup().visible()
            pump()
        }
    }

    private fun tapRow(activity: Activity, index: Int) {
        val list = activity.findViewById<RecyclerView>(R.id.ob_language_list)
        list.itemAnimator = null
        val adapter = list.adapter as LanguageAdapter
        adapter.onCreateViewHolder(list, 0).also { adapter.onBindViewHolder(it, index) }.itemView.performClick()
        pump()
    }

    private fun done(activity: Activity) {
        waitMs(3_000)
        activity.findViewById<View>(R.id.ob_language_confirm).performClick()
        pump()
    }

    private fun pageCta(fragments: FragmentManager, pager: ViewPager2) {
        fragments.fragments.first { it.arguments?.getInt("ob_arg_position", -1) == pager.currentItem }
            .requireView().findViewById<View>(R.id.ob_primary_cta).performClick()
        settle(pager)
    }

    /** A classic run from its LFO to completion, every page by its CTA. */
    private fun runClassic(lfoIntent: Intent) = finishClassic(open(ObLanguageActivity::class.java, lfoIntent))

    private fun finishClassic(lfo: ActivityController<ObLanguageActivity>) {
        tapRow(lfo.get(), 1)
        done(lfo.get())
        val host = open(ObOnboardingHostActivity::class.java, requireNotNull(shadowOf(lfo.get()).nextStartedActivity))
        val pager = host.get().findViewById<ViewPager2>(R.id.ob_step_pager)
        repeat(requireNotNull(pager.adapter).itemCount) { pageCta(host.get().supportFragmentManager, pager) }
        assertTrue(host.get().isFinishing)
        close(host)
        close(lfo)
    }

    /** A pooled run from its LFO to completion. */
    private fun runPooled(poolIntent: Intent) {
        val run = open(ObPoolFlowActivity::class.java, poolIntent)
        tapRow(run.get(), 1)
        done(run.get())
        val pager = run.get().findViewById<ViewPager2>(R.id.ob_pool_pager)
        val stage = run.get().supportFragmentManager.findFragmentByTag("ob_pool_stage") as PoolPagerFragment
        repeat(stage.totalSteps.value) { pageCta(stage.childFragmentManager, pager) }
        assertTrue(run.get().isFinishing)
        close(run)
    }

    private fun extras(intent: Intent): Map<String, Any?> =
        intent.extras?.let { bundle -> bundle.keySet().associateWith { @Suppress("DEPRECATION") bundle.get(it) } }.orEmpty()

    // ── Routing ──

    @Test fun `a PER_SCREEN run creates no pool engine and opens the classic LFO`() {
        SharedNativePool.resetForTest()
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        OnboardingSdk.install(app) {
            adProvider = ERainAdProvider()
            trackkitAutoTracking(false)
        }
        OnboardingSdk.configure(onboardKitConfig { step(ob1) }.getOrThrow()).getOrThrow()
        allowAds(app)
        assertTrue("The ERain stack could run the pool", OnboardingPool.canRun())

        val from = appScreen()
        startFlow(from)
        val started = requireNotNull(shadowOf(from).nextStartedActivity)
        assertEquals(ObLanguageActivity::class.java.name, started.component?.className)
        assertEquals(extras(ObLanguageActivity.intentFor(from, LanguageScreenMode.FIRST_OPEN)), extras(started))
        assertNull("No engine for a classic run", SharedNativePool.engineOrNull())
        assertFalse(OnboardingPool.routeResume)
        assertFalse(OnboardingPool.isActive)
        assertNull(OnboardingSdk.session.nativeFlow)
    }

    @Test fun `a classic run through its screens never builds a pool`() {
        ScenarioSdk.install(app)
        ScenarioSdk.configure(listOf(ob1, ob2))
        vendor.interstitialReady = false
        val from = appScreen()
        startFlow(from)
        runClassic(requireNotNull(shadowOf(from).nextStartedActivity))
        assertTrue(ScenarioSdk.outcomes.single() is OnboardingOutcome.Completed)
        assertNull(SharedNativePool.engineOrNull())
        assertFalse(OnboardingPool.routeResume)
        ObNativeResume.show(from)
        assertEquals(ObResumeActivity::class.java.name, shadowOf(from).nextStartedActivity.component?.className)
    }

    @Test fun `a pool run followed by a forceRestart PER_SCREEN run restores the classic flow`() {
        val engine = installVendor()
        // Adoption off still spends the splash's LFO1 handoff.
        OnboardingSettings.document.acceptSuccessfulFetch(
            """{"native_flow":{"type":"SHARED_POOL","pool":{"adopt_splash_language1":false}}}""")
        val from = appScreen()
        // The splash's LFO1 preload left its handoff for the LFO.
        ReflectionHelpers.setField(OnboardingSdk.preload(), "language1HandoffPending", true)
        runBlocking { OnboardingSdk.start(from) }
        main.idle()
        assertEquals(NativeFlowType.SHARED_POOL, OnboardingSdk.session.nativeFlow?.type)
        assertFalse(OnboardingSdk.session.nativeFlow!!.adoptSplashLanguage1)
        runPooled(requireNotNull(shadowOf(from).nextStartedActivity))
        assertTrue("OC5: the pooled run keeps resume routing", OnboardingPool.routeResume)
        assertTrue(loader.callsFor(AdPlacement.Language1.key).isNotEmpty())
        loader.fill(AdPlacement.Language1.key)
        main.idle()
        val poolRequests = loader.attempts.size

        classicByRemote()
        runBlocking { OnboardingSdk.start(from, StartOptions(forceRestart = true)) }
        main.idle()
        val lfoIntent = requireNotNull(shadowOf(from).nextStartedActivity)
        assertEquals(ObLanguageActivity::class.java.name, lfoIntent.component?.className)
        assertNull(OnboardingSdk.session.nativeFlow)
        assertFalse(OnboardingPool.routeResume)
        assertTrue("The classic run released the pooled leftovers", engine.debugSnapshot().loaders.isEmpty())

        ObNativeResume.show(from)
        assertEquals(ObResumeActivity::class.java.name, shadowOf(from).nextStartedActivity.component?.className)

        val lfo = open(ObLanguageActivity::class.java, lfoIntent)
        assertFalse("No stale LFO1 handoff reaches the classic LFO",
            ReflectionHelpers.getField<Boolean>(lfo.get(), "reuseLfo1Preload"))
        finishClassic(lfo)
        assertEquals(2, ScenarioSdk.outcomes.size)
        assertTrue(ScenarioSdk.outcomes.all { it is OnboardingOutcome.Completed })
        assertEquals("The classic run made no pool request", poolRequests, loader.attempts.size)
        assertTrue(engine.debugSnapshot().loaders.isEmpty())
        assertFalse(OnboardingPool.routeResume)
    }

    // ── Config identity ──

    @Test fun `configOrNull returns the same instance when only native_flow is overridden`() {
        installVendor()
        val configured = ReflectionHelpers.getField<Any>(OnboardingSdk, "config")
        val before = OnboardingSdk.configOrNull()
        assertSame(configured, before)

        OnboardingSettings.document.acceptSuccessfulFetch(
            """{"native_flow":{"type":"SHARED_POOL","pool":{"locale_apply":"RECREATE","parity":{"keep_after_flow":false}}}}""")
        assertEquals(NativeFlowType.SHARED_POOL, OnboardingSettings.nativeFlowTypeOrDefault())
        assertFalse(OnboardingSettings.nativeFlowSnapshot().parity.keepAfterFlow)
        assertSame(before, OnboardingSdk.configOrNull())

        OnboardingSettings.document.acceptSuccessfulFetch(null)
        OnboardingSettings.acceptLegacy(RemoteFlags(nativeReuseEnabled = true,
            supplied = setOf(ObRemoteKeys.NATIVE_REUSE_ENABLED.key)))
        assertEquals("The flat alias", NativeFlowType.SHARED_POOL, OnboardingSettings.nativeFlowTypeOrDefault())
        assertSame(before, OnboardingSdk.configOrNull())

        // Control: a field the config resolves from does make a new instance.
        OnboardingSettings.document.acceptSuccessfulFetch("""{"lfo":{"native2":{"enabled":false}}}""")
        assertNotSame(before, OnboardingSdk.configOrNull())
    }

    // ── Splash ──

    @Test fun `the splash requests the same natives in both types up to the LFO`() {
        installVendor()
        OnboardingSdk.configure(onboardKitConfig {
            splash = SplashConfig(noInternetPromptEnabled = false, notificationPermissionEnabled = false,
                remoteFetchTimeoutMs = 100)
            step(ob1)
            language = POOL_UI_LANGUAGES
            ads = POOL_UI_ADS.copy(splashNative = NativeAdUnit("splash-native"))
        }.getOrThrow()).getOrThrow()
        vendor.interstitialReady = true

        classicByRemote()
        val classic = runSplash()
        assertEquals(ObLanguageActivity::class.java.name, classic.next)
        assertNull(OnboardingSdk.session.nativeFlow)

        runBlocking { OnboardingSdk.reset() }
        main.idle()
        pooledByRemote()
        val pooled = runSplash()
        assertEquals(ObPoolFlowActivity::class.java.name, pooled.next)
        assertEquals(NativeFlowType.SHARED_POOL, OnboardingSdk.session.nativeFlow?.type)

        assertEquals(listOf(AdPlacement.Language1, AdPlacement.SplashNative), classic.natives.map { it.placement })
        assertEquals(classic.natives, pooled.natives)
        assertEquals(classic.interstitials, pooled.interstitials)
        assertTrue("Both splashes left the LFO1 handoff", classic.handoff && pooled.handoff)
        assertTrue("The pool requested nothing before its LFO", loader.attempts.isEmpty())
    }

    private class SplashRun(
        val natives: List<NativeAdRequest>,
        val interstitials: List<AdPlacement>,
        val next: String?,
        val handoff: Boolean,
    )

    /** One real splash to its handoff: interstitial loaded, shown and closed. */
    private fun runSplash(): SplashRun {
        vendor.nativeRequests.clear()
        vendor.interstitialLoads.clear()
        vendor.presentations.clear()
        val splash = Robolectric.buildActivity(IsolationSplashActivity::class.java).also { controllers += it }
        splash.setup().visible()
        splash.get().onWindowFocusChanged(true)
        drainUntil("The splash requests its interstitial") { vendor.interstitialLoads.isNotEmpty() }
        requireNotNull(vendor.interstitialLoads.single().second).onLoaded()
        drainUntil("The splash shows its interstitial") { vendor.presentations.isNotEmpty() }
        val presentation = vendor.presentations.single()
        presentation.onNextAction()
        presentation.onAdClosed()
        drainUntil("The splash hands off") { shadowOf(splash.get()).peekNextStartedActivity() != null }
        val run = SplashRun(
            natives = vendor.nativeRequests.toList(),
            interstitials = vendor.interstitialLoads.map { it.first },
            next = shadowOf(splash.get()).nextStartedActivity.component?.className,
            handoff = ReflectionHelpers.getField<Boolean>(OnboardingSdk.preload(), "language1HandoffPending"),
        )
        close(splash)
        return run
    }

    private fun drainUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            val end = SystemClock.uptimeMillis() + 50
            while (SystemClock.uptimeMillis() < end) main.idleFor(FRAME_MS, MILLISECONDS)
            // The splash reads its state store on an IO thread.
            Thread.sleep(5)
        }
        assertTrue(message, condition())
    }

    // ── Completion ──

    @Test fun `completeFlow calls the provider's releaseAll exactly once in both types`() {
        installVendor()
        val from = appScreen()
        classicByRemote()
        startFlow(from)
        runClassic(requireNotNull(shadowOf(from).nextStartedActivity))
        assertTrue(ScenarioSdk.outcomes.single() is OnboardingOutcome.Completed)
        assertEquals(1, vendor.releaseAllCalls)
        OnboardingSdk.completeFlow(from)
        assertEquals("CAS-guarded", 1, vendor.releaseAllCalls)

        pooledByRemote()
        startFlow(from)
        runPooled(requireNotNull(shadowOf(from).nextStartedActivity))
        assertEquals(2, ScenarioSdk.outcomes.size)
        assertEquals(2, vendor.releaseAllCalls)
        OnboardingSdk.completeFlow(from)
        assertEquals("CAS-guarded", 2, vendor.releaseAllCalls)
    }

    // ── reset() ──

    /** A pooled run on its first page with ads held and LFO2 still loading. */
    private fun pooledRunOnPage0(): ActivityController<ObPoolFlowActivity> {
        pooledByRemote()
        val from = appScreen()
        startFlow(from)
        val run = open(ObPoolFlowActivity::class.java, requireNotNull(shadowOf(from).nextStartedActivity))
        loader.fill(AdPlacement.Language1.key)
        pump()
        tapRow(run.get(), 1)
        done(run.get())
        assertEquals(ObPoolFlowActivity.Stage.PAGER, run.get().stage)
        assertTrue(OnboardingPool.isActive)
        return run
    }

    @Test fun `OnboardingSdk reset during a pool run deactivates it safely`() {
        val engine = installVendor(listOf(ob1, ob2))
        val run = pooledRunOnPage0()
        assertTrue(requireNotNull(engine.debugSnapshot().loader(AdPlacement.Language2.key)).inFlight)

        runBlocking { OnboardingSdk.reset() }
        main.idle()
        assertFalse(OnboardingPool.routeResume)
        assertFalse(OnboardingPool.isActive)
        assertNull(OnboardingSdk.session.nativeFlow)
        assertTrue("The pooled ads are released", engine.debugSnapshot().loaders.isEmpty())
        assertNull(engine.owner)

        val from = appScreen()
        ObNativeResume.show(from)
        assertEquals(ObResumeActivity::class.java.name, shadowOf(from).nextStartedActivity.component?.className)
        ObNativeResume.prepare(app)
        assertEquals("Classic resume warms its own native", listOf<AdPlacement>(AdPlacement.ResumeNative),
            vendor.backgroundPreloads)

        loader.last(AdPlacement.Language2.key).fill()
        pump()
        assertNull("The released chain's late fill is dropped", engine.debugSnapshot().loader(AdPlacement.Language2.key)?.ad)

        // The screen still on top survives the reset and can be left.
        run.pause().stop()
        pump()
        run.restart().start().resume()
        pump()
        val stage = run.get().supportFragmentManager.findFragmentByTag("ob_pool_stage") as PoolPagerFragment
        val pager = run.get().findViewById<ViewPager2>(R.id.ob_pool_pager)
        repeat(stage.totalSteps.value) { pageCta(stage.childFragmentManager, pager) }
        assertTrue(run.get().isFinishing)
        assertTrue(ScenarioSdk.outcomes.single() is OnboardingOutcome.Completed)
        assertFalse("Leaving the reset run never turns pooled routing back on", OnboardingPool.routeResume)
    }

    @Test fun `a recreation after reset keeps the pool deactivated`() {
        installVendor(listOf(ob1, ob2))
        val run = pooledRunOnPage0()
        runBlocking { OnboardingSdk.reset() }
        main.idle()
        assertFalse(OnboardingPool.routeResume)

        // A configuration change in the same process: the saved state comes back to a new instance.
        val state = Bundle()
        run.pause().saveInstanceState(state).stop().destroy()
        pump()
        val recreated = Robolectric.buildActivity(ObPoolFlowActivity::class.java).also { controllers += it }
        recreated.get().setTheme(R.style.ob_Theme_OnboardKit)
        recreated.setup(state)
        pump()
        assertEquals(ObPoolFlowActivity.Stage.PAGER, recreated.get().stage)
        assertFalse("A configuration change is not a new run", OnboardingPool.routeResume)
        val from = appScreen()
        ObNativeResume.show(from)
        assertEquals(ObResumeActivity::class.java.name, shadowOf(from).nextStartedActivity.component?.className)
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}

/** The SDK splash with the consent step answered by the host's own CMP. */
class IsolationSplashActivity : ObSplashActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.ob_Theme_OnboardKit)
        super.onCreate(savedInstanceState)
    }

    override suspend fun onConsentRequired(): Boolean = true
}
