package io.onboardkit.ui.ob5

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.AppOpenManager
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.ads.ObPresentationApplication
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.StepExit
import io.onboardkit.core.analytics.TrackkitPlugin
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.paywall.PaywallGate
import io.onboardkit.paywall.PaywallOutcome
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.remote.RemoteFlags
import io.trackkit.TrackSink
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowViewGroup
import java.util.concurrent.TimeUnit

/** Public OB5 lifecycle with real provider/preload/waterfall; fake only external GMA boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Ob5LifecycleApplication::class,
    shadows = [Ob5NativeAdViewShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ObFullScreenAdActivityTest {
    private lateinit var app: Application
    private lateinit var provider: ERainAdProvider
    private lateinit var seedHost: ActivityController<ComponentActivity>
    private var seedHostDestroyed = false
    private var screen: ActivityController<ObFullScreenAdActivity>? = null
    private lateinit var vendor: MockedConstruction<AdLoader.Builder>
    private val requests = mutableListOf<NativeRequest>()
    private val outcomes = mutableListOf<OnboardingOutcome>()
    private val uiEvents = mutableListOf<OnboardingEvent>()
    private val telemetry = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var eventJob: Job? = null
    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val placement = AdPlacement.Ob5
    private val unit = NativeAdUnit("ob5-native-unit")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Ob5LifecycleApplication.paywall.reset()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        AppOpenManager.getInstance().disableAppResume()
        provider = (OnboardingSdk.provider() as? ERainAdProvider) ?: ERainAdProvider().also { installed ->
            OnboardingSdk.install(app) { adProvider = installed }
        }
        assertSame(provider, OnboardingSdk.provider())
        provider.releaseAll()
        runBlocking { OnboardingSdk.reset() }
        OnboardingSdk.setCanRequestAds(true)
        OnboardingSdk.configure(onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(ob5Native = unit)
        }.getOrThrow()).getOrThrow()
        // Explicit opt-in for this fixture; production/default configuration remains false.
        assertFalse(RemoteFlags().enableStepOb5)
        OnboardingSdk.remoteOrNull()!!.applySnapshot(RemoteFlags(
            enableStepOb5 = true, enableQuestion = false,
        ))
        assertEquals(3L, OnboardingSdk.flags().skipButtonDelaySec)
        assertEquals(15L, OnboardingSdk.flags().fullScreenAutoDismissSec)
        OnboardingSdk.setListener(OnboardingListener { _, outcome -> outcomes += outcome })
        OnboardingSdk.addAnalyticsPlugin(TrackkitPlugin)
        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id = "ob5-lifecycle"
            override fun onEvent(name: String, params: Map<String, Any?>) { telemetry += name to params }
        })
        Tracker.install(app, TrackerConfig(strictValidation = true, logLevel = 0))
        eventJob = eventScope.launch(start = CoroutineStart.UNDISPATCHED) {
            OnboardingSdk.events.collect { uiEvents += it }
        }
        Ob5NativeAdViewShadow.boundAds.clear()
        vendor = Mockito.mockConstruction(AdLoader.Builder::class.java) { builder, _ ->
            val request = NativeRequest()
            Mockito.`when`(builder.forNativeAd(Mockito.any(NativeAd.OnNativeAdLoadedListener::class.java)))
                .thenAnswer { request.onLoaded = it.getArgument(0); builder }
            Mockito.`when`(builder.withAdListener(Mockito.any(AdListener::class.java)))
                .thenAnswer { request.adListener = it.getArgument(0); builder }
            Mockito.`when`(builder.withNativeAdOptions(Mockito.any(NativeAdOptions::class.java))).thenReturn(builder)
            val loader = Mockito.mock(AdLoader::class.java)
            Mockito.`when`(builder.build()).thenReturn(loader)
            Mockito.doAnswer { requests += request; null }.`when`(loader).loadAd(Mockito.any(AdRequest::class.java))
        }
        seedHost = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }

    @After
    fun tearDown() {
        screen?.let { controller ->
            val state = controller.get().lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (controller.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            if (controller.get().lifecycle.currentState != Lifecycle.State.DESTROYED) controller.destroy()
        }
        closeSeedHost()
        provider.releaseAll()
        OnboardingSdk.remoteOrNull()!!.applySnapshot(RemoteFlags())
        OnboardingSdk.setListener(OnboardingListener { _, _ -> })
        eventJob?.cancel()
        eventScope.cancel()
        mainLooper.idleFor(1, TimeUnit.SECONDS)
        vendor.close()
        Ob5NativeAdViewShadow.boundAds.clear()
        Tracker.resetForTesting()
    }

    @Test
    fun `background does not dismiss OB5 and resume resets the full countdown while keeping visible Skip`() {
        val ad = seedReady()
        val controller = createScreen().start().resume()
        mainLooper.idle()
        assertEquals(listOf(ad), Ob5NativeAdViewShadow.boundAds)
        mainLooper.idleFor(4, TimeUnit.SECONDS)
        assertEquals(View.VISIBLE, controller.get().findViewById<View>(R.id.ob_skip_button).visibility)

        controller.pause().stop()
        mainLooper.idleFor(30, TimeUnit.SECONDS)

        assertTrue("The ad destination/background must not complete onboarding", outcomes.isEmpty())
        assertTrue(stepCompletions().isEmpty())
        assertFalse(controller.get().isFinishing)
        controller.restart().start().resume()
        mainLooper.idle()
        assertEquals(View.VISIBLE, controller.get().findViewById<View>(R.id.ob_skip_button).visibility)
        mainLooper.idleFor(14, TimeUnit.SECONDS)
        assertTrue("Resume restarts the full 15 seconds rather than the prior remaining 11", outcomes.isEmpty())
        mainLooper.idleFor(1, TimeUnit.SECONDS)
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is OnboardingOutcome.Completed)
        assertEquals(StepExit.AUTO_DISMISS, stepCompletions().single()["exit_reason"])
        assertEquals(1, requests.size)
        mainLooper.idleFor(20, TimeUnit.SECONDS)
        assertEquals(1, outcomes.size)
        assertEquals(1, stepCompletions().size)
    }

    @Test
    fun `a ready slot released before OB5 exits at first safe resume without a replacement request`() {
        seedReady()
        assertTrue(provider.isNativeReady(placement))
        provider.releaseNative(placement)
        assertFalse(provider.isNativeReady(placement))

        val controller = createScreen()

        assertTrue("CREATED is too early to navigate or present a paywall", outcomes.isEmpty())
        assertFalse(controller.get().isFinishing)
        assertEquals("OB5 must not request another fill for an ad-only page whose fill vanished", 1, requests.size)
        controller.start().resume()
        mainLooper.idle()
        assertEquals(1, outcomes.size)
        assertTrue(controller.get().isFinishing)
        assertEquals(StepExit.AD_FAILED, stepCompletions().single()["exit_reason"])
        assertEquals(0, Ob5NativeAdViewShadow.boundAds.size)
        mainLooper.idleFor(20, TimeUnit.SECONDS)
        assertEquals(1, outcomes.size)
        assertEquals(1, stepCompletions().size)
    }

    @Test
    fun `a late pending vendor fill cannot bind or navigate after the empty OB5 has exited`() {
        seedReady()
        provider.releaseNative(placement)
        provider.preloadNative(seedHost.get(), NativeAdRequest(placement, unit,
            NativeTemplates.layoutForPlacement(placement)))
        assertEquals(2, requests.size)
        assertTrue(provider.isNativeLoading(placement))
        val oldRequest = requests[1]
        val controller = createScreen().start().resume()
        mainLooper.idle()
        assertEquals(1, outcomes.size)
        assertTrue(controller.get().isFinishing)
        assertEquals(0, Ob5NativeAdViewShadow.boundAds.size)

        val late = Mockito.mock(NativeAd::class.java)
        oldRequest.onLoaded.onNativeAdLoaded(late)
        oldRequest.adListener.onAdFailedToLoad(LoadAdError(3, "obsolete no fill", "test-vendor", null, null))
        oldRequest.adListener.onAdImpression()
        mainLooper.idle()

        assertEquals(0, Ob5NativeAdViewShadow.boundAds.size)
        assertEquals(0, shownEvents().size)
        assertEquals(1, outcomes.size)
        assertEquals(1, stepCompletions().size)
        assertEquals(2, requests.size)
        Mockito.verify(late).destroy()
    }

    @Test
    fun `OB5 binding alone is not shown and one vendor impression forwards one UI and canonical show`() {
        val ad = seedReady()
        val request = requests.single()
        createScreen().start().resume()
        mainLooper.idle()

        assertEquals(listOf(ad), Ob5NativeAdViewShadow.boundAds)
        assertEquals(1, telemetry.count { it.first == "ad_bound" && it.second["placement"] == "ob5" })
        assertEquals(0, shownEvents().size)
        assertEquals(0, telemetry.count { it.first == "ad_show" && it.second["placement"] == "ob5" })
        assertTrue(outcomes.isEmpty())

        request.adListener.onAdImpression()
        request.adListener.onAdImpression()
        mainLooper.idle()

        assertEquals(1, shownEvents().size)
        assertEquals(1, telemetry.count { it.first == "ad_show" && it.second["placement"] == "ob5" })
        assertEquals(1, requests.size)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `paywall result received while paused completes only on safe return without presenting again`() {
        seedReady()
        val paywall = Ob5LifecycleApplication.paywall
        paywall.enabled = true
        val controller = createScreen().start().resume()
        mainLooper.idleFor(3, TimeUnit.SECONDS)
        controller.get().findViewById<View>(R.id.ob_skip_button).performClick()
        mainLooper.idle()
        assertEquals(1, paywall.presentations)
        assertTrue(outcomes.isEmpty())

        controller.pause().stop()
        paywall.resolve()
        mainLooper.idle()

        assertTrue("Resolving a host paywall in the background must not complete the flow", outcomes.isEmpty())
        assertFalse(controller.get().isFinishing)
        assertEquals(1, paywall.presentations)

        controller.restart().start().resume()
        mainLooper.idle()
        assertEquals(1, paywall.presentations)
        assertEquals(1, outcomes.size)
        assertTrue(controller.get().isFinishing)
        assertEquals(StepExit.SKIP, stepCompletions().single()["exit_reason"])
        mainLooper.idleFor(20, TimeUnit.SECONDS)
        assertEquals(1, outcomes.size)
        assertEquals(1, stepCompletions().size)
    }

    @Test
    fun `a deferred positive paywall decision waits for safe return before presentation and telemetry`() {
        seedReady()
        val paywall = Ob5LifecycleApplication.paywall
        paywall.deferDecision()
        val controller = createScreen().start().resume()
        mainLooper.idleFor(3, TimeUnit.SECONDS)
        controller.get().findViewById<View>(R.id.ob_skip_button).performClick()
        mainLooper.idle()
        assertEquals(0, paywall.presentations)

        controller.pause().stop()
        paywall.decide(true)
        mainLooper.idle()

        assertEquals("A host decision may finish in the background, but its paywall must wait", 0, paywall.presentations)
        assertEquals(0, telemetry.count { it.first == "iap_paywall_view" })
        assertEquals(0, uiEvents.filterIsInstance<OnboardingEvent.PaywallShown>().size)
        assertTrue(outcomes.isEmpty())
        assertFalse(controller.get().isFinishing)

        controller.restart().start().resume()
        mainLooper.idle()
        assertEquals(1, paywall.presentations)
        assertEquals(1, telemetry.count { it.first == "iap_paywall_view" })
        assertEquals(1, uiEvents.filterIsInstance<OnboardingEvent.PaywallShown>().size)
        assertTrue(outcomes.isEmpty())
        paywall.resolve()
        mainLooper.idle()
        assertEquals(1, outcomes.size)
        assertEquals(1, stepCompletions().size)
    }

    @Test
    fun `a deferred negative paywall decision queues completion without presenting on safe return`() {
        seedReady()
        val paywall = Ob5LifecycleApplication.paywall
        paywall.deferDecision()
        val controller = createScreen().start().resume()
        mainLooper.idleFor(3, TimeUnit.SECONDS)
        controller.get().findViewById<View>(R.id.ob_skip_button).performClick()
        mainLooper.idle()

        controller.pause().stop()
        paywall.decide(false)
        mainLooper.idle()
        assertTrue(outcomes.isEmpty())
        assertFalse(controller.get().isFinishing)
        assertEquals(0, paywall.presentations)

        controller.restart().start().resume()
        mainLooper.idle()
        assertEquals(1, outcomes.size)
        assertTrue(controller.get().isFinishing)
        assertEquals(0, paywall.presentations)
        assertEquals(0, telemetry.count { it.first == "iap_paywall_view" })
        assertEquals(0, uiEvents.filterIsInstance<OnboardingEvent.PaywallShown>().size)
        assertEquals(1, stepCompletions().size)
    }

    @Test
    fun `remote hiding preserves unlocked Skip and the original ad until a full foreground timeout`() {
        val ad = seedReady()
        val controller = createScreen().start().resume()
        val skip = controller.get().findViewById<View>(R.id.ob_skip_button)
        mainLooper.idleFor(4, TimeUnit.SECONDS)
        assertEquals(View.VISIBLE, skip.visibility)

        controller.pause().stop()
        OnboardingSdk.remoteOrNull()!!.applySnapshot(RemoteFlags(
            enableStepOb5 = true, enableQuestion = false, showSkipOb5 = false,
        ))
        controller.restart().start().resume()
        mainLooper.idle()
        assertEquals("Remote false must hide even a previously unlocked Skip", View.GONE, skip.visibility)
        mainLooper.idleFor(4, TimeUnit.SECONDS)
        assertEquals(View.GONE, skip.visibility)
        assertEquals(listOf(ad), Ob5NativeAdViewShadow.boundAds)
        assertEquals(1, requests.size)
        assertTrue(outcomes.isEmpty())
        assertTrue(stepCompletions().isEmpty())

        controller.pause().stop()
        OnboardingSdk.remoteOrNull()!!.applySnapshot(RemoteFlags(
            enableStepOb5 = true, enableQuestion = false, showSkipOb5 = true,
        ))
        controller.restart().start().resume()
        mainLooper.idle()
        assertEquals("Re-enabling must restore the unlocked Skip without another delay", View.VISIBLE, skip.visibility)
        assertEquals(listOf(ad), Ob5NativeAdViewShadow.boundAds)
        assertEquals(1, requests.size)
        mainLooper.idleFor(14, TimeUnit.SECONDS)
        assertTrue("Remote changes must not shorten the full resumed auto-dismiss countdown", outcomes.isEmpty())
        mainLooper.idleFor(1, TimeUnit.SECONDS)
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is OnboardingOutcome.Completed)
        assertTrue(controller.get().isFinishing)
        assertEquals(StepExit.AUTO_DISMISS, stepCompletions().single()["exit_reason"])
        assertEquals(1, requests.size)
        mainLooper.idleFor(20, TimeUnit.SECONDS)
        assertEquals(1, outcomes.size)
        assertEquals(1, stepCompletions().size)
    }

    private fun seedReady(): NativeAd {
        provider.preloadNative(seedHost.get(), NativeAdRequest(placement, unit,
            NativeTemplates.layoutForPlacement(placement)))
        val ad = Mockito.mock(NativeAd::class.java)
        requests.last().onLoaded.onNativeAdLoaded(ad)
        mainLooper.idle()
        assertTrue(provider.isNativeReady(placement))
        return ad
    }

    private fun createScreen(): ActivityController<ObFullScreenAdActivity> {
        closeSeedHost()
        return Robolectric.buildActivity(ObFullScreenAdActivity::class.java).also {
            screen = it
            it.get().setTheme(R.style.ob_Theme_OnboardKit_FullScreenAd)
            it.create()
        }
    }

    private fun closeSeedHost() {
        if (!::seedHost.isInitialized || seedHostDestroyed) return
        seedHost.pause().stop().destroy()
        seedHostDestroyed = true
        mainLooper.idleFor(1, TimeUnit.SECONDS)
    }

    private fun shownEvents() = uiEvents.filterIsInstance<OnboardingEvent.AdShown>()
        .filter { it.placementName == "ob5" }

    private fun stepCompletions() = telemetry.filter {
        it.first == "fo_step_complete" && it.second["step"] == StepId.OB5.value
    }.map { it.second }

    private class NativeRequest {
        lateinit var onLoaded: NativeAd.OnNativeAdLoadedListener
        lateinit var adListener: AdListener
    }
}

/** Host installation seam: one real provider and a configurable app-owned paywall gate. */
class Ob5LifecycleApplication : ObPresentationApplication() {
    override fun onCreate() {
        super.onCreate()
        OnboardingSdk.install(this) {
            adProvider = ERainAdProvider()
            paywallGate = paywall
        }
    }

    companion object {
        internal val paywall = Ob5HostPaywallGate()
    }
}

internal class Ob5HostPaywallGate : PaywallGate {
    var enabled = false
    var presentations = 0
        private set
    private var result = CompletableDeferred<PaywallOutcome>()
    private var decision: CompletableDeferred<Boolean>? = null

    override suspend fun shouldShow(placement: PaywallPlacement) = decision?.await() ?: enabled

    override suspend fun present(activity: Activity, placement: PaywallPlacement): PaywallOutcome {
        presentations++
        return result.await()
    }

    fun resolve() { result.complete(PaywallOutcome.Dismissed) }

    fun deferDecision() { decision = CompletableDeferred() }

    fun decide(allowed: Boolean) { checkNotNull(decision).complete(allowed) }

    fun reset() {
        result.cancel()
        result = CompletableDeferred()
        decision?.cancel()
        decision = null
        enabled = false
        presentations = 0
    }
}

/** Real SDK view inflation/population remains intact; only GMA's opaque native token is faked. */
@Implements(NativeAdView::class)
class Ob5NativeAdViewShadow : ShadowViewGroup() {
    @Implementation
    fun setNativeAd(ad: NativeAd) { boundAds += ad }

    companion object {
        val boundAds = mutableListOf<NativeAd>()
    }
}
