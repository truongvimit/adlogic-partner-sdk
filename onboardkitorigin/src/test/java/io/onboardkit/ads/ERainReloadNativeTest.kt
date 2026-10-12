package io.onboardkit.ads

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.adnative.AdNativeState
import com.ads.module.helper.adnative.NativeAdHelper
import com.ads.module.helper.adnative.NativeAdManager
import com.ads.module.helper.adnative.NativeClickAction
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.onboardKitConfig
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
import org.mockito.MockedConstruction
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.TimeUnit

/** Real provider, helper and native store; only the GMA loader and view registration are replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [NativeProviderViewShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ERainReloadNativeTest {
    private lateinit var controller: ActivityController<NativeProviderHost>
    private lateinit var provider: ERainAdProvider
    private lateinit var builders: MockedConstruction<AdLoader.Builder>
    private val requests = mutableListOf<Pair<String, NativeAd.OnNativeAdLoadedListener>>()
    private val vendorEvents = mutableListOf<AdListener>()
    private val lfo1 = NativeAdUnit(listOf("reload-high", "reload-mid", "reload-all"))
    private val lfo2 = NativeAdUnit(listOf("reload2-high", "reload2-all"))
    private val outcomes = Outcomes()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val host get() = controller.get()

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
        builders = mockConstruction(AdLoader.Builder::class.java) { builder, construction ->
            val id = construction.arguments()[1] as String
            var loaded: NativeAd.OnNativeAdLoadedListener? = null
            doAnswer { loaded = it.getArgument(0); builder }.`when`(builder).forNativeAd(any())
            doAnswer { vendorEvents += it.getArgument<AdListener>(0); builder }.`when`(builder).withAdListener(any(AdListener::class.java))
            doReturn(builder).`when`(builder).withNativeAdOptions(any())
            val loader = mock(AdLoader::class.java)
            doReturn(loader).`when`(builder).build()
            doAnswer { requests += id to checkNotNull(loaded); null }.`when`(loader).loadAd(any(AdRequest::class.java))
        }
        // OnboardingSdk is process-scoped and install is intentionally idempotent.
        provider = OnboardingSdk.provider() as? ERainAdProvider ?: ERainAdProvider()
        provider.releaseAll()
        OnboardingSdk.install(app) { adProvider = provider; trackkitAutoTracking(false) }
        OnboardingSdk.configure(onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(languageNative = lfo1, languageDupNative = lfo2)
        }.getOrThrow()).getOrThrow()
        controller = Robolectric.buildActivity(NativeProviderHost::class.java).setup().visible().windowFocusChanged(true)
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        provider.releaseAll()
        // Every floor of a load left in flight must time out here: NativeAdManager is process-wide
        // and a key still loading would make the next test join it instead of requesting.
        main.idleFor(lfo1.tiers.size * 31L, TimeUnit.SECONDS)
        NativeAdManager.releaseAll()
        builders.close()
        ConsentCenter.clearHostConsent()
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(null)
        if (clickActionSet) AdRemoteConfig.reset()
    }

    private var clickActionSet = false

    private fun lfo1ClickAction(action: NativeClickAction) {
        clickActionSet = true
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_lang" to AdUnitConfig(lfo1.tiers, true, clickAction = action))))
    }

    private class Outcomes : AdEventListener {
        var loaded = 0
        var failures = 0
        var clicks = 0
        override fun onLoaded() { loaded++ }
        override fun onFailedToLoad() { failures++ }
        override fun onClicked() { clicks++ }
    }

    private fun request(page: AdPlacement, unit: NativeAdUnit) =
        NativeAdRequest(page, unit, com.ads.module.R.layout.custom_native_admob_medium)

    private fun units() = requests.map { it.first }

    private fun noFill() = LoadAdError(3, "No fill", "test", null, null)

    /** A placement whose top floor filled and whose ad is bound in its slot. */
    private fun showFilled(page: AdPlacement = AdPlacement.Language1, unit: NativeAdUnit = lfo1): Pair<FrameLayout, NativeAd> {
        val container = FrameLayout(host).also(host::setContentView)
        provider.preloadNative(host, request(page, unit))
        val ad = mock(NativeAd::class.java)
        requests.last().second.onNativeAdLoaded(ad)
        assertTrue(provider.bindNative(host, request(page, unit), container, outcomes))
        assertTrue(container.getChildAt(0) is NativeAdView)
        return container to ad
    }

    @Test fun `reload asks the top floor first and keeps the shown ad until the replacement binds`() {
        val (container, shown) = showFilled()
        val shownView = container.getChildAt(0)
        assertEquals(listOf("reload-high"), units())

        assertTrue(provider.reloadNative(AdPlacement.Language1))
        assertEquals("The reload starts at the top floor", listOf("reload-high", "reload-high"), units())
        assertSame("The shown ad stays while the replacement loads", shownView, container.getChildAt(0))
        assertEquals(View.VISIBLE, container.visibility)
        verify(shown, never()).destroy()

        val replacement = mock(NativeAd::class.java)
        requests.last().second.onNativeAdLoaded(replacement)
        main.idle()
        assertTrue(container.getChildAt(0) is NativeAdView)
        assertNotSame("The replacement binds in the same slot", shownView, container.getChildAt(0))
        verify(shown).destroy()
        verify(replacement, never()).destroy()

        main.idleFor(lfo1.tiers.size * 31L, TimeUnit.SECONDS)
        assertEquals("A filled top floor ends the reload", listOf("reload-high", "reload-high"), units())
        assertEquals(0, outcomes.failures)
        vendorEvents.last().onAdClicked()
        assertEquals("The replacement reports to the same screen", 1, outcomes.clicks)
    }

    @Test fun `a reload walks down its waterfall on no fill and binds the first floor that fills`() {
        val (container, shown) = showFilled()
        val shownView = container.getChildAt(0)
        assertTrue(provider.reloadNative(AdPlacement.Language1))

        vendorEvents.last().onAdFailedToLoad(noFill())
        main.idle()
        assertEquals(listOf("reload-high", "reload-high", "reload-mid"), units())
        assertSame(shownView, container.getChildAt(0))
        vendorEvents.last().onAdFailedToLoad(noFill())
        main.idle()
        assertEquals(listOf("reload-high", "reload-high", "reload-mid", "reload-all"), units())
        assertSame("Misses on higher floors keep the shown ad", shownView, container.getChildAt(0))
        verify(shown, never()).destroy()
        assertEquals(0, outcomes.failures)

        requests.last().second.onNativeAdLoaded(mock(NativeAd::class.java))
        main.idle()
        assertNotSame("The all-price fill replaces it", shownView, container.getChildAt(0))
        assertTrue(container.getChildAt(0) is NativeAdView)
        verify(shown).destroy()
        assertEquals(0, outcomes.failures)
    }

    @Test fun `LFO2 reloads over its own waterfall`() {
        val (container, shown) = showFilled(AdPlacement.Language2, lfo2)
        val shownView = container.getChildAt(0)
        assertTrue(provider.reloadNative(AdPlacement.Language2))
        assertEquals(listOf("reload2-high", "reload2-high"), units())
        vendorEvents.last().onAdFailedToLoad(noFill())
        main.idle()
        assertEquals(listOf("reload2-high", "reload2-high", "reload2-all"), units())
        assertSame(shownView, container.getChildAt(0))
        requests.last().second.onNativeAdLoaded(mock(NativeAd::class.java))
        main.idle()
        assertNotSame(shownView, container.getChildAt(0))
        verify(shown).destroy()
    }

    @Test fun `a replacement that does not fill keeps the shown ad and tells the screen nothing`() {
        val (container, shown) = showFilled()
        val shownView = container.getChildAt(0)
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        lfo1.tiers.forEach { _ ->
            vendorEvents.last().onAdFailedToLoad(noFill())
            main.idle()
        }
        assertEquals("Every floor was asked before giving up",
            listOf("reload-high", "reload-high", "reload-mid", "reload-all"), units())
        assertSame(shownView, container.getChildAt(0))
        assertEquals(View.VISIBLE, container.visibility)
        verify(shown, never()).destroy()
        assertEquals("The screen hears no failure while its ad stays", 0, outcomes.failures)

        assertTrue("The slot can reload again", provider.reloadNative(AdPlacement.Language1))
        assertEquals(listOf("reload-high", "reload-high", "reload-mid", "reload-all", "reload-high"), units())
    }

    @Test fun `a screen that joins a running reload gets its fill`() {
        showFilled()
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        provider.releaseNative(AdPlacement.Language1)
        val reopened = FrameLayout(host).also(host::setContentView)
        var unavailable = 0
        host.showNativeAd(AdPlacement.Language1, lfo1, reopened, onUnavailable = { unavailable++ })
        assertEquals("The reopened screen joins the running reload", listOf("reload-high", "reload-high"), units())

        vendorEvents.last().onAdFailedToLoad(noFill())
        main.idle()
        assertEquals("The joined reload walks on", listOf("reload-high", "reload-high", "reload-mid"), units())
        assertEquals(0, unavailable)

        requests.last().second.onNativeAdLoaded(mock(NativeAd::class.java))
        main.idle()
        assertTrue(reopened.getChildAt(0) is NativeAdView)
        assertEquals(listOf("reload-high", "reload-high", "reload-mid"), units())
        assertEquals(0, unavailable)
    }

    @Test fun `nothing bound means no reload and no request`() {
        assertFalse(provider.reloadNative(AdPlacement.Language1))
        assertEquals(emptyList<String>(), units())

        showFilled()
        provider.releaseNative(AdPlacement.Language1)
        assertFalse("A released slot cannot reload", provider.reloadNative(AdPlacement.Language1))
        assertEquals(listOf("reload-high"), units())
    }

    @Test fun `a slot whose only load failed is not reloaded`() {
        val container = FrameLayout(host).also(host::setContentView)
        var unavailable = 0
        host.showNativeAd(AdPlacement.Language1, lfo1, container, onUnavailable = { unavailable++ })
        lfo1.tiers.forEachIndexed { tier, id ->
            assertEquals(id, units()[tier])
            vendorEvents.last().onAdFailedToLoad(noFill())
            main.idle()
        }
        assertEquals(1, unavailable)
        val requested = units()
        assertFalse(provider.reloadNative(AdPlacement.Language1))
        assertEquals(requested, units())
    }

    @Test fun `a slot still shimmering on its first load is not reloaded`() {
        val container = FrameLayout(host).also(host::setContentView)
        host.showNativeAd(AdPlacement.Language1, lfo1, container)
        assertEquals(listOf("reload-high"), units())
        assertFalse(provider.reloadNative(AdPlacement.Language1))
        assertEquals(listOf("reload-high"), units())
    }

    @Test fun `a load already running for the placement blocks the reload`() {
        val (container, _) = showFilled()
        val shownView = container.getChildAt(0)
        provider.preloadNative(host, request(AdPlacement.Language1, lfo1))
        assertEquals(listOf("reload-high", "reload-high"), units())
        assertFalse(provider.reloadNative(AdPlacement.Language1))
        assertEquals(listOf("reload-high", "reload-high"), units())
        assertSame(shownView, container.getChildAt(0))
    }

    @Test fun `a reload in flight absorbs the next one`() {
        showFilled()
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        assertFalse(provider.reloadNative(AdPlacement.Language1))
        assertFalse(provider.reloadNative(AdPlacement.Language1))
        assertEquals(listOf("reload-high", "reload-high"), units())
        vendorEvents.last().onAdFailedToLoad(noFill())
        main.idle()
        assertFalse("A reload still walking its waterfall absorbs it too", provider.reloadNative(AdPlacement.Language1))
        assertEquals(listOf("reload-high", "reload-high", "reload-mid"), units())
    }

    @Test fun `a queued preload blocks the reload`() {
        showFilled()
        controller.pause().stop()
        provider.preloadNative(host, request(AdPlacement.Language1, lfo1))
        assertEquals(NativeStatus.LOADING, provider.nativeStatus(AdPlacement.Language1))
        assertFalse(provider.reloadNative(AdPlacement.Language1))
        assertEquals(listOf("reload-high"), units())
    }

    @Test fun `a host without window focus is not reloaded until focus returns`() {
        val (container, shown) = showFilled()
        val shownView = container.getChildAt(0)
        controller.windowFocusChanged(false)
        assertFalse(provider.reloadNative(AdPlacement.Language1))
        assertFalse(provider.reloadNative(AdPlacement.Language1))
        assertEquals("No request leaves while the window is unfocused", listOf("reload-high"), units())
        assertSame(shownView, container.getChildAt(0))
        verify(shown, never()).destroy()

        controller.windowFocusChanged(true)
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        assertEquals(listOf("reload-high", "reload-high"), units())
        assertSame("The shown ad stays until the reload binds", shownView, container.getChildAt(0))
    }

    @Test fun `a paused or stopped host with a bound ad is not reloaded until it resumes with focus`() {
        val (container, shown) = showFilled()
        val shownView = container.getChildAt(0)
        controller.pause()
        assertFalse("Paused", provider.reloadNative(AdPlacement.Language1))
        controller.stop()
        assertFalse("Stopped", provider.reloadNative(AdPlacement.Language1))
        main.idleFor(lfo1.tiers.size * 31L, TimeUnit.SECONDS)
        assertEquals("Nothing is sent from the background", listOf("reload-high"), units())
        assertSame(shownView, container.getChildAt(0))
        verify(shown, never()).destroy()

        controller.restart().start().resume().windowFocusChanged(true)
        main.idle()
        assertEquals("The return requests nothing by itself", listOf("reload-high"), units())
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        assertEquals(listOf("reload-high", "reload-high"), units())
        assertSame(shownView, container.getChildAt(0))
    }

    @Test fun `with click_action none a click blocks the reload until the screen returns`() {
        lfo1ClickAction(NativeClickAction.NONE)
        val (container, shown) = showFilled()
        val shownView = container.getChildAt(0)
        vendorEvents.last().onAdClicked()
        assertEquals(1, outcomes.clicks)
        assertEquals(NativeClickAction.NONE, provider.pendingClickAction(AdPlacement.Language1))
        assertEquals("none asks for no replacement", listOf("reload-high"), units())
        assertFalse(NativeAdManager.isLoading(AdPlacement.Language1.key))

        assertFalse("The click destination is open", provider.reloadNative(AdPlacement.Language1))
        assertFalse(provider.reloadNative(AdPlacement.Language1))
        assertEquals(listOf("reload-high"), units())
        assertSame(shownView, container.getChildAt(0))

        controller.pause().stop().restart().start().resume()
        main.idle()
        assertNull(provider.pendingClickAction(AdPlacement.Language1))
        assertEquals("The return requests nothing by itself", listOf("reload-high"), units())
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        assertEquals(listOf("reload-high", "reload-high"), units())
        assertSame("The shown ad stays until the reload binds", shownView, container.getChildAt(0))
        verify(shown, never()).destroy()
    }

    @Test fun `with the default click reload a click blocks the reload, even once its replacement filled`() {
        val (container, shown) = showFilled()
        val shownView = container.getChildAt(0)
        vendorEvents.last().onAdClicked()
        assertEquals(1, outcomes.clicks)
        assertEquals(NativeClickAction.RELOAD, provider.pendingClickAction(AdPlacement.Language1))
        assertEquals("The click asks all price for its own replacement", listOf("reload-high", "reload-all"), units())

        assertFalse(provider.reloadNative(AdPlacement.Language1))
        val replacement = mock(NativeAd::class.java)
        requests.last().second.onNativeAdLoaded(replacement)
        main.idle()
        assertFalse("Only the open click holds it now", NativeAdManager.isLoading(AdPlacement.Language1.key))
        assertFalse("A filled replacement still waits for the return", provider.reloadNative(AdPlacement.Language1))
        assertEquals(listOf("reload-high", "reload-all"), units())
        assertSame("Nothing binds while the destination is open", shownView, container.getChildAt(0))
        verify(shown, never()).destroy()

        controller.pause().stop().restart().start().resume()
        main.idle()
        assertNull(provider.pendingClickAction(AdPlacement.Language1))
        assertNotSame("The click's replacement binds on return", shownView, container.getChildAt(0))
        assertEquals(listOf("reload-high", "reload-all"), units())
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        assertEquals(listOf("reload-high", "reload-all", "reload-high"), units())
    }

    @Test fun `a reload the ads guard refuses starts nothing and keeps the ad`() {
        val (container, shown) = showFilled()
        val shownView = container.getChildAt(0)
        ConsentCenter.setHostConsent(false, false)
        assertFalse(provider.reloadNative(AdPlacement.Language1))
        assertEquals(listOf("reload-high"), units())
        assertSame(shownView, container.getChildAt(0))
        verify(shown, never()).destroy()
    }

    /** The slot's helper; the provider exposes no other handle on its state. */
    private fun helperOf(page: AdPlacement): NativeAdHelper {
        val slots = ERainAdProvider::class.java.getDeclaredField("slots").apply { isAccessible = true }
            .get(provider) as Map<*, *>
        val slot = checkNotNull(slots[page.key]) { "No slot for ${page.key}" }
        return slot.javaClass.getDeclaredMethod("getHelper").apply { isAccessible = true }
            .invoke(slot) as NativeAdHelper
    }

    @Test fun `the helper stays Loaded with the shown ad through a whole reload`() {
        val (container, _) = showFilled()
        val helper = helperOf(AdPlacement.Language1)
        val shownAd = checkNotNull(helper.nativeAd)
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        assertTrue(helper.nativeAdState.value is AdNativeState.Loaded)
        assertSame(shownAd, helper.nativeAd)
        vendorEvents.last().onAdFailedToLoad(noFill())
        main.idle()
        assertTrue("Walking down the waterfall stays Loaded", helper.nativeAdState.value is AdNativeState.Loaded)
        assertSame(shownAd, helper.nativeAd)
        requests.last().second.onNativeAdLoaded(mock(NativeAd::class.java))
        main.idle()
        val state = helper.nativeAdState.value
        assertTrue(state is AdNativeState.Loaded)
        assertNotSame(shownAd, (state as AdNativeState.Loaded).nativeAd)
        assertSame(state.nativeAd, helper.nativeAd)
        assertTrue(container.getChildAt(0) is NativeAdView)
        assertEquals(NativeStatus.IDLE, provider.nativeStatus(AdPlacement.Language1))
    }

    private fun verifyReplacementParkedInBackground(stop: Boolean) {
        val (container, shown) = showFilled()
        val shownView = container.getChildAt(0)
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        controller.pause()
        if (stop) controller.stop()
        val replacement = mock(NativeAd::class.java)
        requests.last().second.onNativeAdLoaded(replacement)
        main.idle()
        assertEquals("The fill waits in the store", NativeStatus.READY, provider.nativeStatus(AdPlacement.Language1))
        assertSame("Nothing binds in the background", shownView, container.getChildAt(0))
        verify(shown, never()).destroy()
        verify(replacement, never()).destroy()

        if (stop) controller.restart().start()
        controller.resume().windowFocusChanged(true)
        main.idleFor(1, TimeUnit.SECONDS)
        assertSame("The return binds nothing by itself", shownView, container.getChildAt(0))
        assertEquals(NativeStatus.READY, provider.nativeStatus(AdPlacement.Language1))
        assertEquals(listOf("reload-high", "reload-high"), units())

        assertTrue(provider.reloadNative(AdPlacement.Language1))
        assertEquals("The parked fill binds without a request", listOf("reload-high", "reload-high"), units())
        assertTrue(container.getChildAt(0) is NativeAdView)
        assertNotSame(shownView, container.getChildAt(0))
        verify(shown).destroy()
        verify(replacement, never()).destroy()
        assertEquals(NativeStatus.IDLE, provider.nativeStatus(AdPlacement.Language1))
        assertEquals(0, outcomes.failures)
    }

    @Test fun `a replacement that fills while the host is paused is parked and the next reload binds it without a request`() =
        verifyReplacementParkedInBackground(stop = false)

    @Test fun `a replacement that fills while the host is stopped is parked and the next reload binds it without a request`() =
        verifyReplacementParkedInBackground(stop = true)

    private fun verifyClickParksReplacement(fillWhileAway: Boolean) {
        lfo1ClickAction(NativeClickAction.NONE)
        val (container, shown) = showFilled()
        val shownView = container.getChildAt(0)
        val shownEvents = vendorEvents.single()
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        shownEvents.onAdClicked()
        assertEquals(1, outcomes.clicks)
        assertEquals(NativeClickAction.NONE, provider.pendingClickAction(AdPlacement.Language1))
        val replacement = mock(NativeAd::class.java)
        controller.pause().stop()
        if (fillWhileAway) {
            requests.last().second.onNativeAdLoaded(replacement)
            main.idle()
        }
        controller.restart().start().resume().windowFocusChanged(true)
        main.idle()
        assertNull(provider.pendingClickAction(AdPlacement.Language1))
        if (!fillWhileAway) {
            requests.last().second.onNativeAdLoaded(replacement)
            main.idle()
        }
        main.idleFor(1, TimeUnit.SECONDS)
        assertSame("click_action none binds nothing on the return", shownView, container.getChildAt(0))
        assertEquals(NativeStatus.READY, provider.nativeStatus(AdPlacement.Language1))
        verify(shown, never()).destroy()
        verify(replacement, never()).destroy()
        assertEquals(listOf("reload-high", "reload-high"), units())

        assertTrue(provider.reloadNative(AdPlacement.Language1))
        assertEquals("The parked fill binds without a request", listOf("reload-high", "reload-high"), units())
        assertNotSame(shownView, container.getChildAt(0))
        assertTrue(container.getChildAt(0) is NativeAdView)
        verify(shown).destroy()
        verify(replacement, never()).destroy()
    }

    @Test fun `with click_action none a click during a reload parks a fill landing while away and the next reload binds it`() =
        verifyClickParksReplacement(fillWhileAway = true)

    @Test fun `with click_action none a click during a reload parks a fill landing after the return and the next reload binds it`() =
        verifyClickParksReplacement(fillWhileAway = false)

    @Test fun `an open reported during a reload also parks the fill`() {
        lfo1ClickAction(NativeClickAction.NONE)
        val (container, _) = showFilled()
        val shownView = container.getChildAt(0)
        val shownEvents = vendorEvents.single()
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        shownEvents.onAdOpened()
        controller.pause().stop().restart().start().resume().windowFocusChanged(true)
        main.idle()
        requests.last().second.onNativeAdLoaded(mock(NativeAd::class.java))
        main.idle()
        assertSame(shownView, container.getChildAt(0))
        assertEquals(NativeStatus.READY, provider.nativeStatus(AdPlacement.Language1))
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        assertNotSame(shownView, container.getChildAt(0))
        assertEquals(listOf("reload-high", "reload-high"), units())
    }

    @Test fun `with the default click reload a click during a reload joins it and the return binds its fill`() {
        val (container, shown) = showFilled()
        val shownView = container.getChildAt(0)
        val shownEvents = vendorEvents.single()
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        shownEvents.onAdClicked()
        assertEquals(NativeClickAction.RELOAD, provider.pendingClickAction(AdPlacement.Language1))
        assertEquals("The click joins the running reload", listOf("reload-high", "reload-high"), units())
        controller.pause().stop()
        val replacement = mock(NativeAd::class.java)
        requests.last().second.onNativeAdLoaded(replacement)
        main.idle()
        assertSame(shownView, container.getChildAt(0))
        controller.restart().start().resume().windowFocusChanged(true)
        main.idle()
        assertNotSame("The click return binds the fill as before", shownView, container.getChildAt(0))
        assertTrue(container.getChildAt(0) is NativeAdView)
        verify(shown).destroy()
        verify(replacement, never()).destroy()
        assertEquals(listOf("reload-high", "reload-high"), units())
    }

    @Test fun `a slot released during a reload leaves its fill for the re-entered screen`() {
        val (_, shown) = showFilled()
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        provider.releaseNative(AdPlacement.Language1)
        verify(shown).destroy()
        val replacement = mock(NativeAd::class.java)
        requests.last().second.onNativeAdLoaded(replacement)
        main.idle()
        assertEquals("The fill stays unused in the store", NativeStatus.READY, provider.nativeStatus(AdPlacement.Language1))
        verify(replacement, never()).destroy()

        val reopened = FrameLayout(host).also(host::setContentView)
        var unavailable = 0
        host.showNativeAd(AdPlacement.Language1, lfo1, reopened, onUnavailable = { unavailable++ })
        assertTrue("The re-entered screen binds it at once", reopened.getChildAt(0) is NativeAdView)
        assertEquals(listOf("reload-high", "reload-high"), units())
        assertEquals(0, unavailable)
        verify(replacement, never()).destroy()
        assertEquals(NativeStatus.IDLE, provider.nativeStatus(AdPlacement.Language1))
    }

    @Test fun `a parked fill is bound by the next reload even after more floors were asked`() {
        val (container, _) = showFilled()
        val shownView = container.getChildAt(0)
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        vendorEvents.last().onAdFailedToLoad(noFill())
        main.idle()
        controller.pause()
        requests.last().second.onNativeAdLoaded(mock(NativeAd::class.java))
        main.idle()
        controller.resume().windowFocusChanged(true)
        main.idle()
        assertEquals(listOf("reload-high", "reload-high", "reload-mid"), units())
        assertTrue(provider.reloadNative(AdPlacement.Language1))
        assertEquals(listOf("reload-high", "reload-high", "reload-mid"), units())
        assertNotSame(shownView, container.getChildAt(0))
    }
}
