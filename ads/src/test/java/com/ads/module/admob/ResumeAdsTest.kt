package com.ads.module.admob

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.ads.module.ads.AdsSdk
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdsSdkConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.Int02Activity
import com.ads.module.helper.interstitial.Int02FacebookShadow
import com.ads.module.helper.interstitial.Int02InterstitialShadow
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.bytedance.sdk.openadsdk.fake.FakeLandingPageActivity
import com.facebook.ads.fake.FakeTranslucentAdActivity
import com.google.android.gms.ads.AdActivity
import io.trackkit.TrackSink
import io.trackkit.Tracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Application::class,
    shadows = [Int02InterstitialShadow::class, Int02MobileAdsShadow::class, Int02FacebookShadow::class,
        ResumeLoadGmaShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class ResumeAdsTest {
    private val manager get() = AppOpenManager.getInstance()
    private val owner get() = ProcessLifecycleOwner.get()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var host: Activity
    private val calls = mutableListOf<String>()
    private val screen = object : ResumeAds.NativeScreen {
        override fun show(host: Activity) { calls += "show" }
        override fun show(host: Activity, afterAdClick: Boolean) { calls += if (afterAdClick) "show:click" else "show" }
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Forget any stay or Activity an earlier test left behind.
        ResumeAds.resetForTest()
        AdBehavior.document.install(null, null)
        InterstitialAdManager.releaseAll()
        Int02InterstitialShadow.requests.clear()
        ResumeLoadGmaShadow.requests.clear()
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
        AdsSdk.getInstance().init(app, AdsSdkConfig(app).apply { setFacebookClientToken("resume-test-token") })
        manager.enableAppResume()
        manager.setInterstitialShowing(false)
        manager.setDisableAdResumeByClickAction(false)
        manager.setResumeSkipPolicy(null)
        AdRemoteConfig.initializeFromJson(
            """{"native_resume":{"ids":[{"id":"native-unit"}],"isEnable":true},""" +
                """"inter_resume":{"ids":[{"id":"inter-unit"}],"isEnable":true},""" +
                """"open_resume":{"ids":[{"id":"open-unit"}],"isEnable":true}}""",
        )
        ResumeAds.nativeScreen = screen
        // Robolectric gives every test a new Application; the manager's hooks sit on the first one.
        app.unregisterActivityLifecycleCallbacks(manager)
        app.registerActivityLifecycleCallbacks(manager)
        controller = Robolectric.buildActivity(Int02Activity::class.java).setup()
        host = controller.get()
        manager.onActivityStarted(host)
        main.idle()
        calls.clear()
    }

    @After
    fun tearDown() {
        ResumeAds.nativeScreen = null
        AdBehavior.document.install(null, null)
        manager.enableAppResumeWithActivity(Int02Activity::class.java)
        manager.setDisableAdResumeByClickAction(false)
        manager.onActivityDestroyed(host)
        InterstitialAdManager.releaseAll()
        AdRemoteConfig.reset()
        ConsentCenter.clearHostConsent()
        manager.setInterstitialShowing(false)
        val state = controller.get().lifecycle.currentState
        if (state.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
        if (state.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
        controller.destroy()
        ResumeAds.resetForTest()
        ApplicationProvider.getApplicationContext<Application>().unregisterActivityLifecycleCallbacks(manager)
    }

    @Test
    fun `type defaults to native_resume and an unknown value keeps the lower tier`() {
        assertEquals(ResumeAdType.NATIVE, ResumeAdType.current())
        AdBehavior.document.acceptSuccessfulFetch("""{"app_resume":{"type":"banner"}}""")
        assertEquals(ResumeAdType.NATIVE, ResumeAdType.current())
        AdBehavior.document.acceptSuccessfulFetch("""{"app_resume":{"type":"inter_resume"}}""")
        assertEquals(ResumeAdType.INTER, ResumeAdType.current())
        AdBehavior.document.acceptSuccessfulFetch(null)
    }

    /** Home, recents or the screen off: every Activity stops. */
    private fun leave() {
        controller.pause().stop()
    }

    private fun back() {
        controller.restart().start().resume()
    }

    /** An Activity in front of the host; it resumes before the host stops, as on a device. */
    private fun <T : Activity> cover(type: Class<T>, stopsHost: Boolean = true): ActivityController<T> {
        controller.pause()
        val over = Robolectric.buildActivity(type).setup()
        if (stopsHost) controller.stop()
        return over
    }

    /** Back from [over] to the host. */
    private fun uncover(over: ActivityController<*>, hostStopped: Boolean = true) {
        over.pause()
        if (hostStopped) controller.restart().start()
        controller.resume()
        over.stop().destroy()
    }

    @Test
    fun `native requests nothing as the user leaves and opens its screen on every return`() {
        leave()
        main.idleFor(Duration.ofMillis(60_000))
        assertEquals("The screen requests its own ad as it shows", emptyList<String>(), calls)
        back()
        assertEquals(listOf("show"), calls)
    }

    @Test
    fun `a return faster than the process lifecycle's stop still opens native resume`() {
        leave()
        back()
        main.idleFor(Duration.ofMillis(5))
        assertEquals(listOf("show"), calls)
        // The process callbacks that arrive late, or never, change nothing for native.
        ResumeAds.onStop(owner)
        ResumeAds.onStart(owner)
        main.idle()
        assertEquals(listOf("show"), calls)
    }

    @Test
    fun `native_resume's app_resume_load_delay_ms is ignored`() {
        AdRemoteConfig.initializeFromJson(
            """{"native_resume":{"app_resume_load_delay_ms":60000,"ids":[{"id":"native-unit"}],"isEnable":true},""" +
                """"open_resume":{"ids":[{"id":"open-unit"}],"isEnable":true}}""",
        )
        leave()
        back()
        assertEquals(listOf("show"), calls)
    }

    @Test
    fun `native ignores open_resume's delay and inter keeps it`() {
        AdRemoteConfig.initializeFromJson(
            """{"native_resume":{"ids":[{"id":"native-unit"}],"isEnable":true},""" +
                """"inter_resume":{"ids":[{"id":"inter-unit"}],"isEnable":true},""" +
                """"open_resume":{"app_resume_load_delay_ms":5000,"isEnable":false}}""",
        )
        leave()
        back()
        assertEquals(listOf("show"), calls)

        AdBehavior.document.install("""{"app_resume":{"type":"inter_resume"}}""", null)
        ResumeAds.onStop(owner)
        main.idleFor(Duration.ofMillis(4_999))
        assertEquals(0, Int02InterstitialShadow.requests.size)
        main.idleFor(Duration.ofMillis(1))
        assertEquals(1, Int02InterstitialShadow.requests.size)
    }

    @Test
    fun `an overlay with no ad Activity is no trip, after a native click too`() {
        // A permission prompt or a dialog Activity of another app.
        controller.pause().resume()
        ResumeAds.noteNativeClick()
        controller.pause().resume()
        uncover(cover(Activity::class.java, stopsHost = false), hostStopped = false)
        main.idle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a see-through ad Activity after a native click is a trip that opens once it has closed`() {
        // GMA's AdActivity carrying Play's install sheet, or a translucent landing page.
        ResumeAds.noteNativeClick()
        val sheet = cover(FakeTranslucentAdActivity::class.java, stopsHost = false)
        assertEquals("Nothing requested as the ad comes in front", emptyList<String>(), calls)
        sheet.pause()
        controller.resume()
        main.idle()
        assertEquals("Still visible while it closes: never over it", emptyList<String>(), calls)
        sheet.stop()
        assertEquals(listOf("show:click"), calls)
        sheet.destroy()
        assertEquals(listOf("show:click"), calls)
    }

    @Test
    fun `a return is decided as the host comes back, before the click moves its page on`() {
        ResumeAds.noteNativeClick()
        val sheet = cover(FakeTranslucentAdActivity::class.java, stopsHost = false)
        sheet.pause()
        controller.resume()
        // The page's click-return auto next starts while the ad Activity is still closing.
        manager.setResumeSkipPolicy(ResumeSkipPolicy { "suppressed_by_flow" })
        sheet.stop()
        assertEquals(listOf("show:click"), calls)
        manager.setResumeSkipPolicy(null)
    }

    @Test
    fun `an excused return stays excused after its one-shot snapshot clears`() {
        ResumeAds.noteNativeClick()
        val sheet = cover(FakeTranslucentAdActivity::class.java, stopsHost = false)
        manager.skipNextResume("external_link")
        sheet.pause()
        controller.resume()
        main.idle()
        sheet.stop()
        main.idle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `an admitted return still never opens over an ad shown before the ad Activity closed`() {
        ResumeAds.noteNativeClick()
        val sheet = cover(FakeTranslucentAdActivity::class.java, stopsHost = false)
        sheet.pause()
        controller.resume()
        manager.setInterstitialShowing(true)
        sheet.stop()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the host leaving before the ad Activity closes drops the admitted return`() {
        ResumeAds.noteNativeClick()
        val sheet = cover(FakeTranslucentAdActivity::class.java, stopsHost = false)
        sheet.pause()
        controller.resume()
        controller.pause()
        sheet.stop()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `Home from a see-through ad page opens nothing until the page itself has closed`() {
        ResumeAds.noteNativeClick()
        val page = cover(FakeTranslucentAdActivity::class.java, stopsHost = false)
        page.pause().stop()
        controller.stop()
        page.restart().start().resume()
        main.idle()
        assertEquals(emptyList<String>(), calls)
        uncover(page)
        assertEquals(listOf("show:click"), calls)
    }

    @Test
    fun `the app's own screens on top of the host are no trip, a native click before them included`() {
        ResumeAds.noteNativeClick()
        uncover(cover(Activity::class.java))
        main.idle()
        assertEquals(emptyList<String>(), calls)
        leave()
        back()
        assertEquals("The click died with the next host resume", listOf("show"), calls)
    }

    @Test
    fun `a configuration change is no trip`() {
        controller.recreate()
        main.idle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `back out of the last screen closes the app, so the next launch opens nothing`() {
        host.finish()
        leave()
        main.idle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a native ad's landing page inside the process is a trip and opens native resume on the way back`() {
        ResumeAds.noteNativeClick()
        val landing = cover(FakeLandingPageActivity::class.java)
        assertEquals("Nothing requested as the page covers the host", emptyList<String>(), calls)
        uncover(landing)
        assertEquals(listOf("show:click"), calls)
    }

    @Test
    fun `a click reported after the landing page covered the host still makes the trip`() {
        val landing = cover(FakeLandingPageActivity::class.java)
        assertEquals(emptyList<String>(), calls)
        ResumeAds.noteNativeClick()
        assertEquals(emptyList<String>(), calls)
        uncover(landing)
        assertEquals(listOf("show:click"), calls)
    }

    @Test
    fun `a native click reported once the app is already gone still marks that trip`() {
        leave()
        ResumeAds.noteNativeClick()
        back()
        assertEquals(listOf("show:click"), calls)
    }

    @Test
    fun `an excused native click opens nothing on the way back, a later plain trip still does`() {
        // An onboarding page whose click action is auto next.
        ResumeAds.noteNativeClick()
        ResumeAds.excuseNativeClick()
        uncover(cover(FakeLandingPageActivity::class.java))
        assertEquals(emptyList<String>(), calls)

        // The excuse ends with the host's resume: the next click, on another screen, opens it.
        ResumeAds.noteNativeClick()
        uncover(cover(FakeTranslucentAdActivity::class.java, stopsHost = false))
        assertEquals(listOf("show:click"), calls)

        calls.clear()
        ResumeAds.noteNativeClick()
        ResumeAds.excuseNativeClick()
        controller.pause().stop()
        back()
        assertEquals("An excused click's trip to another app opens nothing", emptyList<String>(), calls)

        leave()
        back()
        assertEquals(listOf("show"), calls)
    }

    @Test
    fun `an excuse reported after the trip began ends it, whichever callback comes first`() {
        val landing = cover(FakeLandingPageActivity::class.java)
        ResumeAds.noteNativeClick()
        assertEquals(emptyList<String>(), calls)
        ResumeAds.excuseNativeClick()
        uncover(landing)
        assertEquals(emptyList<String>(), calls)

        calls.clear()
        leave()
        ResumeAds.excuseNativeClick()
        ResumeAds.noteNativeClick()
        back()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `an ad Activity over the host without a native click is no trip`() {
        // A banner's or an interstitial's in-app landing page.
        manager.noteAdClick()
        uncover(cover(FakeLandingPageActivity::class.java))
        main.idle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `leaving from the landing page waits for the host before opening`() {
        ResumeAds.noteNativeClick()
        val landing = cover(FakeLandingPageActivity::class.java)
        landing.pause().stop()
        assertEquals(emptyList<String>(), calls)
        landing.restart().start().resume()
        main.idle()
        assertEquals("Never over the landing page itself", emptyList<String>(), calls)
        uncover(landing)
        assertEquals(listOf("show:click"), calls)
    }

    @Test
    fun `a native click through a translucent ad Activity to another app opens native resume once the host is back`() {
        ResumeAds.noteNativeClick()
        val proxy = cover(FakeTranslucentAdActivity::class.java, stopsHost = false)
        assertEquals(emptyList<String>(), calls)
        // The browser or the store opens: the proxy and the host stop.
        proxy.pause().stop()
        controller.stop()
        assertEquals(emptyList<String>(), calls)
        proxy.restart().start().resume()
        main.idle()
        assertEquals(emptyList<String>(), calls)
        uncover(proxy)
        assertEquals(listOf("show:click"), calls)
    }

    @Test
    fun `a click through AdActivity opens native resume once the host is back in front`() {
        val adActivity = Robolectric.buildActivity(AdActivity::class.java).get()
        ResumeAds.noteNativeClick()
        controller.pause()
        manager.onActivityStarted(adActivity)
        manager.onActivityResumed(adActivity)
        assertEquals(emptyList<String>(), calls)
        manager.onActivityPaused(adActivity)
        manager.onActivityStopped(adActivity)
        controller.stop()
        assertEquals(emptyList<String>(), calls)
        manager.onActivityStarted(adActivity)
        manager.onActivityResumed(adActivity)
        assertEquals(emptyList<String>(), calls)
        manager.onActivityPaused(adActivity)
        back()
        manager.onActivityStopped(adActivity)
        manager.onActivityDestroyed(adActivity)
        assertEquals(listOf("show:click"), calls)
    }

    @Test
    fun `any other click leaves a plain return`() {
        // A banner, an interstitial, app-open or a host's own external link marked as an ad click.
        manager.disableAdResumeByClickAction()
        manager.setResumeSkipPolicy(ResumeSkipPolicy { AppOpenManager.AD_CLICK_RETURN })
        leave()
        back()
        assertEquals(listOf("show"), calls)
        manager.setResumeSkipPolicy(null)
    }

    @Test
    fun `a native click marks only the trip it starts`() {
        ResumeAds.noteNativeClick()
        leave()
        back()
        assertEquals(listOf("show:click"), calls)

        calls.clear()
        leave()
        back()
        assertEquals(listOf("show"), calls)
    }

    @Test
    fun `a fullscreen ad while away is its own return, not native resume's`() {
        // A native click that opened nothing, then a rewarded ad in the network's own Activity.
        ResumeAds.noteNativeClick()
        val reward = cover(FakeLandingPageActivity::class.java)
        manager.setInterstitialShowing(true)
        manager.setInterstitialShowing(false)
        uncover(reward)
        main.idle()
        assertEquals(emptyList<String>(), calls)

        calls.clear()
        manager.setInterstitialShowing(true)
        leave()
        back()
        manager.setInterstitialShowing(false)
        main.idle()
        assertEquals("Home while the ad is up", emptyList<String>(), calls)
    }

    @Test
    fun `the selected type's key switched off means no resume, with no fallback`() {
        AdRemoteConfig.initializeFromJson(
            """{"native_resume":{"ids":[{"id":"native-unit"}],"isEnable":false},""" +
                """"open_resume":{"ids":[{"id":"open-unit"}],"isEnable":true}}""",
        )
        leave()
        manager.onStop()
        main.idleFor(Duration.ofMillis(3_000))
        back()
        manager.onResume()
        main.idle()
        assertEquals(emptyList<String>(), calls)
        assertEquals(0, ResumeLoadGmaShadow.requests.size)
        assertEquals(0, Int02InterstitialShadow.requests.size)
    }

    @Test
    fun `a type changed while away cancels the return`() {
        leave()
        main.idleFor(Duration.ofMillis(2_000))
        AdBehavior.document.install("""{"app_resume":{"type":"none"}}""", null)
        back()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `excluded screens, excused trips and the process switch keep native resume idle`() {
        manager.disableAppResumeWithActivity(Int02Activity::class.java)
        leave()
        main.idleFor(Duration.ofMillis(3_000))
        back()
        manager.enableAppResumeWithActivity(Int02Activity::class.java)

        manager.skipNextResume("external_link")
        leave()
        main.idleFor(Duration.ofMillis(3_000))
        back()

        manager.disableAppResume()
        leave()
        main.idleFor(Duration.ofMillis(3_000))
        back()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a return onto an excluded screen opens nothing`() {
        leave()
        manager.disableAppResumeWithActivity(Int02Activity::class.java)
        back()
        main.idle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a blocked return is reported once, under the selected type's placement`() {
        val skipped = mutableListOf<Any?>()
        val sink = object : TrackSink {
            override val id = "resume-ads-skip"
            override fun onEvent(name: String, params: Map<String, Any?>) {
                if (name == "ad_skipped") skipped += params["placement"]
            }
        }
        Tracker.install(ApplicationProvider.getApplicationContext<Application>())
        Tracker.addSink(sink)
        try {
            leave()
            main.idleFor(Duration.ofMillis(2_000))
            manager.setResumeSkipPolicy(ResumeSkipPolicy { "suppressed_by_flow" })
            back()
            assertEquals(listOf<Any?>("native_resume"), skipped)
            assertEquals(emptyList<String>(), calls)
        } finally {
            Tracker.removeSink(sink)
            manager.setResumeSkipPolicy(null)
        }
    }

    @Test
    fun `app-open stays idle unless it is the selected type`() {
        manager.setAppResumeAdId("open-unit")
        manager.onResume()
        manager.onStop()
        main.idleFor(Duration.ofMillis(3_000))
        assertEquals(0, ResumeLoadGmaShadow.requests.size)

        AdBehavior.document.install("""{"app_resume":{"type":"open_resume"}}""", null)
        manager.onResume()
        manager.onStop()
        main.idleFor(Duration.ofMillis(3_000))
        assertEquals(1, ResumeLoadGmaShadow.requests.size)
        manager.onResume()
    }

    @Test
    fun `inter requests in the background and the return joins that load`() {
        AdBehavior.document.install("""{"app_resume":{"type":"inter_resume"}}""", null)
        ResumeAds.onStop(owner)
        main.idleFor(Duration.ofMillis(1_999))
        assertEquals(0, Int02InterstitialShadow.requests.size)
        main.idleFor(Duration.ofMillis(1))
        assertEquals(1, Int02InterstitialShadow.requests.size)
        ResumeAds.onStart(owner)
        main.idleFor(Duration.ofMillis(8_000))
        assertEquals(1, Int02InterstitialShadow.requests.size)
        assertEquals(emptyList<String>(), calls)
    }
}
