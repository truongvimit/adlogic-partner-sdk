package io.onboardkit.ui.pool

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.helper.adnative.pool.NativePoolEngine
import com.ads.module.helper.adnative.pool.PoolState
import com.facebook.shimmer.ShimmerFrameLayout
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.pool.ApFakeLoader
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolGlueViewShadow
import io.onboardkit.ads.pool.setPremium
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.flow.NativeFlowType
import io.onboardkit.ui.splash.ObSplashNativeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.util.ReflectionHelpers

/** A SHARED_POOL run whose pool starts at the splash's native_fs, then the LFO borrows from it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [PoolGlueViewShadow::class],
    instrumentedPackages = ["io.onboardkit.ui.pool"])
@LooperMode(LooperMode.Mode.PAUSED)
class PoolSplashNativeTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var loader: ApFakeLoader
    private lateinit var engine: NativePoolEngine<ApNativeAd>
    private val controllers = mutableListOf<ActivityController<out ComponentActivity>>()
    private val nativeFs = AdPlacement.SplashNative.key

    @Before fun setUp() {
        loader = ApFakeLoader()
        engine = PoolUiSdk.install(app, loader)
        PoolUiSdk.configure(POOL_UI_ADS.copy(splashNative = NativeAdUnit("native-fs")))
        ReflectionHelpers.setField(OnboardingSdk, "plannedNativeFlow", NativeFlowType.SHARED_POOL)
    }

    @After fun tearDown() {
        controllers.asReversed().forEach { c ->
            val state = c.get().lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) c.pause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) c.stop()
            if (state != Lifecycle.State.DESTROYED) c.destroy()
        }
        ReflectionHelpers.setField(OnboardingSdk, "plannedNativeFlow", null)
        setPremium(false)
        AdRemoteConfig.reset()
        PoolUiSdk.tearDown()
    }

    private fun splash(focused: Boolean = true): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(focused)
            .also { controllers += it; main.idle() }

    private fun requestAtSplash(): Boolean =
        OnboardingSdk.requestSplashNativeInPool(splash().get(), allowWhileVisible = false).also { main.idle() }

    private fun openScreen(): ObSplashNativeActivity {
        val controller = Robolectric.buildActivity(
            ObSplashNativeActivity::class.java, ObSplashNativeActivity.intent(app, pooled = true),
        ).also { controllers += it }
        controller.get().setTheme(R.style.ob_Theme_OnboardKit_FullScreenAd)
        controller.setup().visible()
        main.idle()
        return controller.get()
    }

    private fun closeScreen(screen: ObSplashNativeActivity) {
        screen.onBackPressedDispatcher.onBackPressed()
        main.idle()
        assertTrue(screen.isFinishing)
        val controller = controllers.last()
        controller.pause().stop().destroy()
        main.idle()
    }

    private fun openLfo(): FrameLayout =
        launchPoolFlow(app).also { controllers += it }.get().findViewById(R.id.ob_native_container)

    private fun ObSplashNativeActivity.container(): FrameLayout = findViewById(R.id.ob_native_container)

    @Test fun `a classic run keeps native_fs off the pool`() {
        ReflectionHelpers.setField(OnboardingSdk, "plannedNativeFlow", NativeFlowType.PER_SCREEN)
        assertFalse(requestAtSplash())
        assertTrue(loader.callsFor(nativeFs).isEmpty())
    }

    @Test fun `the pooled request waits for the splash's request window, as the provider queue does`() {
        val splash = splash(focused = false)
        assertTrue(OnboardingSdk.requestSplashNativeInPool(splash.get(), allowWhileVisible = false))
        main.idle()
        assertTrue("No focus, no request", loader.callsFor(nativeFs).isEmpty())
        splash.windowFocusChanged(true)
        main.idle()
        assertEquals(1, loader.callsFor(nativeFs).size)
    }

    @Test fun `under the notification prompt a started splash may send it`() {
        val splash = splash(focused = false)
        splash.pause()
        main.idle()
        assertTrue(OnboardingSdk.requestSplashNativeInPool(splash.get(), allowWhileVisible = true))
        main.idle()
        assertEquals(1, loader.callsFor(nativeFs).size)
    }

    @Test fun `a window wait that dies with the splash sends nothing`() {
        val splash = splash(focused = false)
        assertTrue(OnboardingSdk.requestSplashNativeInPool(splash.get(), allowWhileVisible = false))
        splash.pause().stop().destroy()
        main.idle()
        assertTrue(loader.callsFor(nativeFs).isEmpty())
        assertFalse(OnboardingPool.holdsOrLoads(AdPlacement.SplashNative))
    }

    @Test fun `the splash asks native_fs into the pool and its screen shows the pool's ad`() {
        assertTrue(requestAtSplash())
        assertEquals("One FORCED request, at the splash", listOf("native-fs"), loader.callsFor(nativeFs))
        assertTrue(OnboardingPool.holdsOrLoads(AdPlacement.SplashNative))

        val screen = openScreen()
        assertEquals(nativeFs, engine.owner)
        assertTrue("Shimmer while it loads", screen.container().getChildAt(0) is ShimmerFrameLayout)
        val ad = loader.fill(nativeFs)
        main.idle()
        assertSame(ad.admobNativeAd, screen.container().shownAd())
        assertEquals("The screen requests nothing itself", 1, loader.callsFor(nativeFs).size)
    }

    @Test fun `closed while loading, native_fs stays in flight in the pool and the LFO shows it until LFO1 fills`() {
        requestAtSplash()
        closeScreen(openScreen())
        assertTrue("Still loading in the pool", engine.debugSnapshot().loader(nativeFs)!!.inFlight)

        val lfo = openLfo()
        assertEquals("LFO1 goes out past the gate native_fs closes", 1, loader.callsFor("language1").size)
        assertEquals("language1", engine.owner)

        val ad = loader.fill(nativeFs)
        main.idle()
        assertTrue(engine.debugSnapshot().loader(nativeFs)!!.state is PoolState.Loaded)
        assertSame("Lent while LFO1 loads", ad.admobNativeAd, lfo.shownAd())

        val own = loader.fill("language1")
        main.idle()
        assertSame(own.admobNativeAd, lfo.shownAd())
    }

    @Test fun `a native_fs already shown stays in the pool and fills the LFO until LFO1 fills`() {
        requestAtSplash()
        val screen = openScreen()
        val ad = loader.fill(nativeFs)
        main.idle()
        loader.impress(ad)
        main.idle()
        closeScreen(screen)
        assertTrue(engine.debugSnapshot().loader(nativeFs)!!.state is PoolState.Showing)

        val lfo = openLfo()
        assertEquals(1, loader.callsFor("language1").size)
        assertSame("No empty view: the shown native_fs is borrowed", ad.admobNativeAd, lfo.shownAd())
        assertEquals(View.VISIBLE, lfo.visibility)

        val own = loader.fill("language1")
        main.idle()
        assertSame(own.admobNativeAd, lfo.shownAd())
    }

    @Test fun `native_fs switched off in ad_config is refused at request, as every pooled placement`() {
        AdRemoteConfig.initializeFromJson("""{"native_fs":{"ids":[{"id":"fs"}],"isEnable":false}}""")
        assertTrue(requestAtSplash())
        assertTrue(loader.callsFor(nativeFs).isEmpty())
        assertNull("A config refusal leaves no loader", OnboardingPool.loaderState(AdPlacement.SplashNative))
        assertFalse("So the splash opens no native_fs screen", OnboardingPool.holdsOrLoads(AdPlacement.SplashNative))
    }

    @Test fun `ad_config ids replace the host unit for the pooled request`() {
        AdRemoteConfig.initializeFromJson("""{"native_fs":{"ids":[{"id":"fs-high"},{"id":"off","isEnable":false},{"id":"fs-all"}],"isEnable":true}}""")
        requestAtSplash()
        assertEquals(listOf("fs-high"), loader.callsFor(nativeFs))
        loader.last(nativeFs).sink.onFailed()
        main.idle()
        assertEquals("Disabled floors are skipped", listOf("fs-high", "fs-all"), loader.callsFor(nativeFs))
    }

    @Test fun `the screen re-checks the guard, so native_fs switched off after it loaded closes it unshown`() {
        requestAtSplash()
        val ad = loader.fill(nativeFs)
        main.idle()
        AdRemoteConfig.initializeFromJson("""{"native_fs":{"ids":[{"id":"fs"}],"isEnable":false}}""")
        val screen = openScreen()
        assertTrue(screen.isFinishing)
        assertNull(screen.findViewById<FrameLayout>(R.id.ob_native_container)?.shownAd())
        assertSame("The ad stays in the pool for later slots", ad, engine.debugSnapshot().loader(nativeFs)?.ad)
    }

    @Test fun `a premium user gets no pooled native_fs request and no screen`() {
        setPremium(true)
        assertTrue(requestAtSplash())
        assertTrue(loader.callsFor(nativeFs).isEmpty())
        assertTrue(openScreen().isFinishing)
    }
}
