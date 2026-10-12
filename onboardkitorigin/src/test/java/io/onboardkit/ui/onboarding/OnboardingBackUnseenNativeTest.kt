package io.onboardkit.ui.onboarding

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.ads.module.config.AdRemoteConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.adnative.NativeAdManager
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeStatus
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.LanguageConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.ObLanguages
import io.onboardkit.config.StepDefinition
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.remote.RemoteFlags
import io.onboardkit.ui.language.LanguageAdapter
import io.onboardkit.ui.language.LanguageScreenMode
import io.onboardkit.ui.language.ObLanguageActivity
import io.onboardkit.ui.pager.StepPagerAdapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowViewGroup
import java.util.concurrent.TimeUnit

/**
 * Real pager host, LFO, provider, helper and native store; only the GMA loader and view
 * registration are replaced. Pages pre-bind their neighbour offscreen, so the first page going
 * back to the LFO used to spend the neighbour's ad before anyone saw it.
 */
@RunWith(RobolectricTestRunner::class)
// Own sandbox: install() keeps the first provider, and this one must be the real one.
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h780dp-xxhdpi",
    shadows = [PagerNativeViewShadow::class],
    instrumentedPackages = ["io.onboardkit.ui.onboarding", "io.onboardkit.ui.language"])
@LooperMode(LooperMode.Mode.PAUSED)
class OnboardingBackUnseenNativeTest {
    private class VendorRequest(val unit: String, val loaded: NativeAd.OnNativeAdLoadedListener, val events: AdListener)

    private val ob1 get() = ContentStepDefinition(StepId.OB1, title = "One")
    private val ob2 get() = ContentStepDefinition(StepId.OB2, title = "Two")
    private val ob3 get() = ContentStepDefinition(StepId.OB3, title = "Three")
    private val full1 get() = AdFullScreenStepDefinition(StepId.FULL1, autoNextEnabled = false)

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var provider: ERainAdProvider
    private lateinit var builders: MockedConstruction<AdLoader.Builder>
    private val requests = mutableListOf<VendorRequest>()
    private var controller: ActivityController<ObOnboardingHostActivity>? = null
    private var language: ActivityController<ObLanguageActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private val back get() = activity.findViewById<View>(R.id.ob_step_back)

    @Before fun setup() {
        NativeAdManager.releaseAll()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource { override fun isPremium(context: Context) = false })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        builders = mockConstruction(AdLoader.Builder::class.java) { builder, construction ->
            val unit = construction.arguments()[1] as String
            var loaded: NativeAd.OnNativeAdLoadedListener? = null
            var events: AdListener? = null
            doAnswer { loaded = it.getArgument(0); builder }.`when`(builder).forNativeAd(any())
            doAnswer { events = it.getArgument(0); builder }.`when`(builder).withAdListener(any(AdListener::class.java))
            doReturn(builder).`when`(builder).withNativeAdOptions(any())
            val loader = mock(AdLoader::class.java)
            doReturn(loader).`when`(builder).build()
            doAnswer {
                requests += VendorRequest(unit, checkNotNull(loaded), checkNotNull(events))
                null
            }.`when`(loader).loadAd(any(AdRequest::class.java))
        }
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        OnboardingSdk.install(app) { adProvider = ERainAdProvider(); trackkitAutoTracking(false) }
        provider = checkNotNull(OnboardingSdk.provider() as? ERainAdProvider) {
            "Another test class installed its provider in this sandbox first"
        }
        provider.releaseAll()
        runBlocking { OnboardingSdk.reset() }
        OnboardingSdk.remoteOrNull()?.applySnapshot(RemoteFlags())
    }

    @After fun cleanup() {
        language?.pause()?.stop()?.destroy()
        controller?.pause()?.stop()?.destroy()
        provider.releaseAll()
        main.idleFor(61, TimeUnit.SECONDS)
        NativeAdManager.releaseAll()
        builders.close()
        ConsentCenter.clearHostConsent()
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
    }

    private fun configure(steps: List<StepDefinition>, settings: String? = null) {
        OnboardingSettings.document.acceptSuccessfulFetch(settings)
        OnboardingSdk.configure(onboardKitConfig {
            steps.forEach(::step)
            language = LanguageConfig(languages = listOf("en-US", "es").map { requireNotNull(ObLanguages.find(it)) })
            ads = AdsConfig(stepNatives = steps.associate { it.id to NativeAdUnit("${it.id.value}-unit") })
        }.getOrThrow()).getOrThrow()
    }

    private fun units(step: StepId) = requests.count { it.unit == "${step.value}-unit" }

    private fun request(step: StepId) = requests.last { it.unit == "${step.value}-unit" }

    private fun fill(step: StepId): NativeAd = mock(NativeAd::class.java).also {
        doReturn("Native ${step.value}").`when`(it).headline
        doReturn("Install").`when`(it).callToAction
        request(step).loaded.onNativeAdLoaded(it)
        main.idle()
    }

    /** The LFO pick that warms every page, from a screen opened with [intent]. */
    private fun pickLanguage(intent: Intent = ObLanguageActivity.intentFor(app, LanguageScreenMode.FIRST_OPEN)) {
        val lfo = Robolectric.buildActivity(ObLanguageActivity::class.java, intent).setup().visible().windowFocusChanged(true)
        language = lfo
        main.idle()
        val list = lfo.get().findViewById<RecyclerView>(R.id.ob_language_list)
        val adapter = list.adapter as LanguageAdapter
        adapter.onCreateViewHolder(list, 0).also { adapter.onBindViewHolder(it, 0) }.itemView.performClick()
        main.idle()
        lfo.pause().stop().destroy()
        language = null
        main.idle()
    }

    private fun launchHost() {
        controller = Robolectric.buildActivity(ObOnboardingHostActivity::class.java)
        activity.setTheme(R.style.ob_Theme_OnboardKit)
        requireNotNull(controller).setup().visible().windowFocusChanged(true)
        main.idle()
        layout()
    }

    private fun layout() {
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2340)
        main.idle()
    }

    private fun settle() { main.idleFor(2_000, TimeUnit.MILLISECONDS); layout() }

    /** Every vendor ad bound to a view of the host, offscreen pages included. */
    private fun boundAds(root: View = activity.window.decorView): List<NativeAd> =
        nativeViews(root).mapNotNull { Shadow.extract<PagerNativeViewShadow>(it).nativeAd }

    private fun nativeViews(root: View = activity.window.decorView): List<NativeAdView> {
        val found = mutableListOf<NativeAdView>()
        fun walk(view: View) {
            if (view is NativeAdView) found += view
            if (view is ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        walk(root)
        return found
    }

    private val pager get() = activity.findViewById<ViewPager2>(R.id.ob_step_pager)
    private val pages get() = pager.adapter as StepPagerAdapter

    /**
     * The remote moves [steps] to native template 3 in ad_config and delivers a new remote
     * version, which the running pager answers by rebuilding its pages that are not on screen.
     */
    private fun remoteTemplate3(steps: List<StepId>) {
        AdRemoteConfig.initializeFromJson(steps.joinToString(prefix = "{", postfix = "}") {
            """"native_${it.value}":{"ids":[{"id":"${it.value}-unit"}],"isEnable":true,"templateId":3}"""
        })
        val remote = checkNotNull(OnboardingSdk.remoteOrNull())
        remote.applySnapshot(remote.flags.value.let { it.copy(configVersion = it.configVersion + 1) })
        main.idle()
        layout()
    }

    private fun startedLanguage(): Intent {
        val started = requireNotNull(shadowOf(activity).nextStartedActivity) { "The LFO was not reopened" }
        assertEquals(ObLanguageActivity::class.java.name, started.component?.className)
        return started
    }

    private fun destroyHost() {
        requireNotNull(controller).pause().stop().destroy()
        controller = null
        main.idle()
    }

    private fun status(step: StepId) = provider.nativeStatus(AdPlacement.StepNative(step))

    /** Picks a language, fills every page and opens the pager on OB1 with OB2 pre-bound offscreen. */
    private fun openPagerWithPrebound(settings: String? = null): Map<StepId, NativeAd> {
        configure(listOf(ob1, ob2, ob3), settings)
        pickLanguage()
        assertEquals(listOf(1, 1, 1), listOf(units(StepId.OB1), units(StepId.OB2), units(StepId.OB3)))
        val ads = listOf(StepId.OB1, StepId.OB2, StepId.OB3).associateWith(::fill)
        listOf(StepId.OB1, StepId.OB2, StepId.OB3).forEach { assertEquals(NativeStatus.READY, status(it)) }
        launchHost()
        val bound = boundAds()
        assertTrue("OB1 shows its ad", ads.getValue(StepId.OB1) in bound)
        assertTrue("OB2 is pre-bound offscreen", ads.getValue(StepId.OB2) in bound)
        assertEquals(NativeStatus.IDLE, status(StepId.OB2))
        // OB3 is laid out too when the peek reaches into it; either way it is unseen.
        assertEquals(ads.getValue(StepId.OB3) !in bound, status(StepId.OB3) == NativeStatus.READY)
        return ads
    }

    @Test fun `back to the LFO keeps OB2's unseen pre-bound ad and the next pager binds it without a request`() {
        val ads = openPagerWithPrebound()
        val ob1Ad = ads.getValue(StepId.OB1)
        val ob2Ad = ads.getValue(StepId.OB2)
        val ob3Ad = ads.getValue(StepId.OB3)

        back.performClick()
        assertEquals("OB2's ad went back to its store", NativeStatus.READY, status(StepId.OB2))
        verify(ob2Ad, never()).destroy()
        assertFalse("It left the old page's view", ob2Ad in boundAds())
        assertTrue("The page on screen keeps its own ad until the host goes", ob1Ad in boundAds())
        val intent = startedLanguage()
        destroyHost()
        verify(ob1Ad).destroy()
        assertEquals("OB1's own ad is spent as before", NativeStatus.IDLE, status(StepId.OB1))
        assertEquals(NativeStatus.READY, status(StepId.OB2))
        verify(ob2Ad, never()).destroy()
        assertEquals(NativeStatus.READY, status(StepId.OB3))
        verify(ob3Ad, never()).destroy()

        pickLanguage(intent)
        assertEquals("OB1 is requested again", 2, units(StepId.OB1))
        assertEquals("OB2 is not", 1, units(StepId.OB2))
        assertEquals(1, units(StepId.OB3))

        val newOb1 = fill(StepId.OB1)
        launchHost()
        val bound = boundAds()
        assertTrue(newOb1 in bound)
        assertTrue("The new pager binds that very OB2 ad", ob2Ad in bound)
        assertEquals("No request was spent on it", 1, units(StepId.OB2))
        verify(ob2Ad, never()).destroy()
        assertEquals(NativeStatus.IDLE, status(StepId.OB2))
        assertEquals(1, units(StepId.OB3))
        verify(ob3Ad, never()).destroy()
    }

    @Test fun `the old host destroyed only after the new pager bound the kept ad leaves it on screen`() {
        val ads = openPagerWithPrebound()
        val ob2Ad = ads.getValue(StepId.OB2)
        val old = requireNotNull(controller)
        back.performClick()
        val intent = startedLanguage()
        // Android may destroy the finishing host late, once the next screens report idle.
        old.pause()
        pickLanguage(intent)
        val newOb1 = fill(StepId.OB1)
        controller = null
        launchHost()
        assertTrue(ob2Ad in boundAds())
        assertTrue(newOb1 in boundAds())
        old.stop().destroy()
        main.idle()
        fun destroyed(ad: NativeAd) = org.mockito.Mockito.mockingDetails(ad).invocations.any { it.method.name == "destroy" }
        assertEquals("Neither ad on the new pager is destroyed", listOf(false, false), listOf(destroyed(newOb1), destroyed(ob2Ad)))
        assertTrue("The new pager still shows the kept ad", ob2Ad in boundAds())
    }

    @Test fun `a page rebuilt for a remote template change binds its unseen pre-bound ad without a request`() {
        val ads = openPagerWithPrebound()
        val ob1Ad = ads.getValue(StepId.OB1)
        val ob2Ad = ads.getValue(StepId.OB2)
        val shownPage = pages.fragmentAt(0)
        val oldOb2Page = pages.fragmentAt(1)
        remoteTemplate3(listOf(StepId.OB1, StepId.OB2))

        assertEquals("OB2 is rebuilt for its new template", "TEMPLATE_3", pages.pageAt(1)?.variantKey)
        val rebuilt = checkNotNull(pages.fragmentAt(1))
        assertNotSame(oldOb2Page, rebuilt)
        assertEquals("The rebuilt page binds that very ad", listOf(ob2Ad), boundAds(checkNotNull(rebuilt.view)))
        val view = nativeViews().single { Shadow.extract<PagerNativeViewShadow>(it).nativeAd === ob2Ad }
        assertNotNull("in the new template", view.findViewById<View>(R.id.ob_native_med_1_91_media_well))
        verify(ob2Ad, never()).destroy()
        assertEquals("No request was spent on it", 1, units(StepId.OB2))
        assertEquals(NativeStatus.IDLE, status(StepId.OB2))

        assertEquals("The visible page keeps its template", "TEMPLATE_1", pages.pageAt(0)?.variantKey)
        assertSame(shownPage, pages.fragmentAt(0))
        assertTrue(ob1Ad in boundAds(checkNotNull(shownPage?.view)))
        verify(ob1Ad, never()).destroy()
        assertEquals("Its ad never went back to the store", NativeStatus.IDLE, status(StepId.OB1))

        activity.next(null)
        settle()
        assertEquals(1, pager.currentItem)
        assertTrue("OB2 shows the kept ad", ob2Ad in boundAds(checkNotNull(rebuilt.view)))
        verify(ob2Ad, never()).destroy()
        assertEquals(listOf(1, 1, 1), listOf(units(StepId.OB1), units(StepId.OB2), units(StepId.OB3)))
    }

    @Test fun `a page rebuilt for a remote template change after its ad recorded an impression spends it and requests nothing`() {
        val ads = openPagerWithPrebound()
        val ob1Ad = ads.getValue(StepId.OB1)
        val ob2Ad = ads.getValue(StepId.OB2)
        request(StepId.OB2).events.onAdImpression()
        val shownPage = pages.fragmentAt(0)
        remoteTemplate3(listOf(StepId.OB1, StepId.OB2))

        assertEquals("TEMPLATE_3", pages.pageAt(1)?.variantKey)
        val rebuilt = checkNotNull(pages.fragmentAt(1))
        verify(ob2Ad).destroy()
        assertFalse(ob2Ad in boundAds())
        assertTrue("A seen ad is not kept", status(StepId.OB2) != NativeStatus.READY)
        assertEquals("The rebuilt page asks for nothing, as before", 1, units(StepId.OB2))

        assertSame(shownPage, pages.fragmentAt(0))
        assertTrue(ob1Ad in boundAds(checkNotNull(shownPage?.view)))
        verify(ob1Ad, never()).destroy()

        activity.next(null)
        settle()
        assertEquals(1, pager.currentItem)
        assertTrue("OB2 shows no ad", boundAds(checkNotNull(rebuilt.view)).isEmpty())
        assertEquals(listOf(1, 1, 1), listOf(units(StepId.OB1), units(StepId.OB2), units(StepId.OB3)))
    }

    @Test fun `a remote template change of only the visible page rebuilds and releases nothing`() {
        val ads = openPagerWithPrebound()
        val before = listOf(pages.fragmentAt(0), pages.fragmentAt(1))
        val boundBefore = boundAds()
        remoteTemplate3(listOf(StepId.OB1))

        assertEquals(listOf("TEMPLATE_1", "TEMPLATE_1"), listOf(pages.pageAt(0)?.variantKey, pages.pageAt(1)?.variantKey))
        assertEquals(before, listOf(pages.fragmentAt(0), pages.fragmentAt(1)))
        assertEquals(boundBefore, boundAds())
        ads.values.forEach { verify(it, never()).destroy() }
        assertEquals("Neither bound ad went back to the store", listOf(NativeStatus.IDLE, NativeStatus.IDLE),
            listOf(status(StepId.OB1), status(StepId.OB2)))
        assertEquals(listOf(1, 1, 1), listOf(units(StepId.OB1), units(StepId.OB2), units(StepId.OB3)))
    }

    @Test fun `system back on the first page keeps the unseen neighbour as the button does`() {
        val ads = openPagerWithPrebound()
        activity.onBackPressedDispatcher.onBackPressed()
        startedLanguage()
        destroyHost()
        assertEquals(NativeStatus.READY, status(StepId.OB2))
        verify(ads.getValue(StepId.OB2), never()).destroy()
        verify(ads.getValue(StepId.OB1)).destroy()
    }

    @Test fun `a neighbour whose ad recorded an impression is spent and requested again`() {
        val ads = openPagerWithPrebound()
        val ob2Ad = ads.getValue(StepId.OB2)
        request(StepId.OB2).events.onAdImpression()
        back.performClick()
        val intent = startedLanguage()
        assertTrue(ob2Ad !in boundAds())
        verify(ob2Ad).destroy()
        assertEquals(NativeStatus.IDLE, status(StepId.OB2))
        destroyHost()
        assertEquals(NativeStatus.IDLE, status(StepId.OB2))

        pickLanguage(intent)
        assertEquals("A seen ad is replaced by a new request", 2, units(StepId.OB2))
        assertEquals(2, units(StepId.OB1))
        assertEquals(1, units(StepId.OB3))
    }

    @Test fun `a neighbour whose ad was clicked is spent and requested again`() {
        val ads = openPagerWithPrebound()
        val ob2Ad = ads.getValue(StepId.OB2)
        request(StepId.OB2).events.onAdClicked()
        back.performClick()
        val intent = startedLanguage()
        verify(ob2Ad).destroy()
        destroyHost()
        assertTrue(status(StepId.OB2) != NativeStatus.READY)
        pickLanguage(intent)
        assertEquals(2, units(StepId.OB2))
    }

    @Test fun `an unseen fullscreen page next to the first page is kept under its fullscreen placement`() {
        configure(listOf(ob1, full1, ob2))
        pickLanguage()
        val ob1Ad = fill(StepId.OB1)
        val fullAd = fill(StepId.FULL1)
        fill(StepId.OB2)
        val fullscreen = AdPlacement.StepFullScreen(StepId.FULL1)
        assertEquals(NativeStatus.READY, provider.nativeStatus(fullscreen))
        launchHost()
        assertTrue("The fullscreen page is pre-bound offscreen", fullAd in boundAds())
        assertEquals(NativeStatus.IDLE, provider.nativeStatus(fullscreen))

        back.performClick()
        val intent = startedLanguage()
        assertEquals(NativeStatus.READY, provider.nativeStatus(fullscreen))
        destroyHost()
        verify(fullAd, never()).destroy()
        verify(ob1Ad).destroy()
        assertEquals(NativeStatus.READY, provider.nativeStatus(fullscreen))

        pickLanguage(intent)
        assertEquals(1, units(StepId.FULL1))
        fill(StepId.OB1)
        launchHost()
        assertTrue(fullAd in boundAds())
        assertEquals(1, units(StepId.FULL1))
        verify(fullAd, never()).destroy()
    }

    @Test fun `going one page back keeps nothing and the pages keep their own ads`() {
        val ads = openPagerWithPrebound()
        activity.next(null)
        settle()
        assertEquals(1, pager.currentItem)
        activity.onBackPressedDispatcher.onBackPressed()
        settle()
        assertEquals(0, pager.currentItem)
        assertFalse(activity.isFinishing)
        ads.values.forEach { verify(it, never()).destroy() }
        assertTrue(ads.getValue(StepId.OB1) in boundAds())
        assertTrue(ads.getValue(StepId.OB2) in boundAds())
        assertEquals(NativeStatus.IDLE, status(StepId.OB2))
    }

    @Test fun `a forward run through the last page releases every ad as before`() {
        val ads = openPagerWithPrebound()
        activity.next(null)
        settle()
        assertTrue("OB3 binds its buffered ad", ads.getValue(StepId.OB3) in boundAds())
        activity.next(null)
        settle()
        activity.next(null)
        settle()
        main.idleFor(1, TimeUnit.SECONDS)
        destroyHost()
        ads.values.forEach { verify(it).destroy() }
        listOf(StepId.OB1, StepId.OB2, StepId.OB3).forEach {
            assertTrue("$it keeps nothing", status(it) != NativeStatus.READY)
        }
        assertEquals(listOf(1, 1, 1), listOf(units(StepId.OB1), units(StepId.OB2), units(StepId.OB3)))
    }

    @Test fun `a host destroyed without going back releases the unseen neighbour as before`() {
        val ads = openPagerWithPrebound()
        val ob3Bound = ads.getValue(StepId.OB3) in boundAds()
        activity.finish()
        destroyHost()
        listOf(StepId.OB1, StepId.OB2).forEach { verify(ads.getValue(it)).destroy() }
        assertTrue("A host destroyed without going back keeps nothing", status(StepId.OB2) != NativeStatus.READY)
        if (ob3Bound) {
            verify(ads.getValue(StepId.OB3)).destroy()
            assertTrue(status(StepId.OB3) != NativeStatus.READY)
        } else {
            verify(ads.getValue(StepId.OB3), never()).destroy()
            assertEquals("A page never created keeps its buffered fill as before", NativeStatus.READY, status(StepId.OB3))
        }
    }

    @Test fun `without the peek only the next page is pre-bound and the page after keeps its buffered fill`() {
        val ads = openPagerWithPrebound("""{"onboarding":{"navigation":{"peek_px":0}}}""")
        assertFalse("OB3 is not created yet", ads.getValue(StepId.OB3) in boundAds())
        assertEquals(NativeStatus.READY, status(StepId.OB3))
        back.performClick()
        val intent = startedLanguage()
        destroyHost()
        assertEquals(NativeStatus.READY, status(StepId.OB2))
        assertEquals(NativeStatus.READY, status(StepId.OB3))
        ads.filterKeys { it != StepId.OB1 }.values.forEach { verify(it, never()).destroy() }
        pickLanguage(intent)
        assertEquals(listOf(2, 1, 1), listOf(units(StepId.OB1), units(StepId.OB2), units(StepId.OB3)))
        fill(StepId.OB1)
        launchHost()
        assertTrue(ads.getValue(StepId.OB2) in boundAds())
        assertEquals(1, units(StepId.OB2))
    }

    @Test fun `back that finishes the flow from the first page destroys the unseen neighbour`() {
        configure(listOf(ob1, ob2, ob3), """{"onboarding":{"steps":{"ob1":{"back":{"enabled":false}}}}}""")
        pickLanguage()
        val ob1Ad = fill(StepId.OB1)
        val ob2Ad = fill(StepId.OB2)
        fill(StepId.OB3)
        launchHost()
        assertTrue(ob2Ad in boundAds())
        assertEquals(View.GONE, back.visibility)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        generateSequence { shadowOf(activity).nextStartedActivity }.forEach {
            assertFalse(it.component?.className == ObLanguageActivity::class.java.name)
        }
        destroyHost()
        verify(ob1Ad).destroy()
        verify(ob2Ad).destroy()
        assertTrue(status(StepId.OB2) != NativeStatus.READY)
    }
}

/** Google's view registration only, recording the ad each view was given. */
@Implements(value = NativeAdView::class, isInAndroidSdk = false)
class PagerNativeViewShadow : ShadowViewGroup() {
    var nativeAd: NativeAd? = null
        private set
    private val assets = mutableMapOf<String, View?>()
    @Implementation fun setNativeAd(ad: NativeAd) { nativeAd = ad }
    @Implementation fun destroy() {}
    @Implementation fun setHeadlineView(view: View?) { assets["Headline"] = view }
    @Implementation fun getHeadlineView(): View? = assets["Headline"]
    @Implementation fun setBodyView(view: View?) { assets["Body"] = view }
    @Implementation fun getBodyView(): View? = assets["Body"]
    @Implementation fun setCallToActionView(view: View?) { assets["CallToAction"] = view }
    @Implementation fun getCallToActionView(): View? = assets["CallToAction"]
    @Implementation fun setIconView(view: View?) { assets["Icon"] = view }
    @Implementation fun getIconView(): View? = assets["Icon"]
    @Implementation fun setPriceView(view: View?) { assets["Price"] = view }
    @Implementation fun getPriceView(): View? = assets["Price"]
    @Implementation fun setStarRatingView(view: View?) { assets["StarRating"] = view }
    @Implementation fun getStarRatingView(): View? = assets["StarRating"]
    @Implementation fun setAdvertiserView(view: View?) { assets["Advertiser"] = view }
    @Implementation fun getAdvertiserView(): View? = assets["Advertiser"]
    @Implementation fun setMediaView(view: com.google.android.gms.ads.nativead.MediaView?) {}
}
