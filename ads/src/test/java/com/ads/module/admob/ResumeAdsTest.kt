package com.ads.module.admob

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.ads.module.ads.ERainAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.ERainAdConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.Int02Activity
import com.ads.module.helper.interstitial.Int02FacebookShadow
import com.ads.module.helper.interstitial.Int02InterstitialShadow
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.ads.module.helper.interstitial.InterstitialAdManager
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
import org.robolectric.shadows.ShadowSystemClock
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
        override fun prepare(context: Context) { calls += "prepare" }
        override fun show(host: Activity) { calls += "show" }
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Close any stay an earlier test left open before choosing this test's type.
        AdBehavior.document.install("""{"app_resume":{"type":"none"}}""", null)
        ResumeAds.onStart(owner)
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
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply { setFacebookClientToken("resume-test-token") })
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
        controller.pause().stop().destroy()
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

    @Test
    fun `native requests once the delay elapses in the background and opens on the return`() {
        ResumeAds.onStop(owner)
        main.idleFor(Duration.ofMillis(1_999))
        assertEquals(emptyList<String>(), calls)
        main.idleFor(Duration.ofMillis(1))
        assertEquals(listOf("prepare"), calls)
        ResumeAds.onStart(owner)
        assertEquals(listOf("prepare", "show"), calls)
    }

    @Test
    fun `a return inside the delay neither requests nor opens`() {
        ResumeAds.onStop(owner)
        main.idleFor(Duration.ofMillis(1_000))
        ResumeAds.onStart(owner)
        main.idleFor(Duration.ofMillis(5_000))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a device asleep through the timer still requests before opening`() {
        ResumeAds.onStop(owner)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        ResumeAds.onStart(owner)
        main.idle()
        assertEquals(listOf("prepare", "show"), calls)
    }

    @Test
    fun `the delay is open_resume's app_resume_load_delay_ms`() {
        AdRemoteConfig.initializeFromJson(
            """{"native_resume":{"ids":[{"id":"native-unit"}],"isEnable":true},""" +
                """"open_resume":{"app_resume_load_delay_ms":5000,"isEnable":false}}""",
        )
        ResumeAds.onStop(owner)
        main.idleFor(Duration.ofMillis(4_000))
        ResumeAds.onStart(owner)
        assertEquals(emptyList<String>(), calls)
        ResumeAds.onStop(owner)
        main.idleFor(Duration.ofMillis(5_000))
        ResumeAds.onStart(owner)
        assertEquals(listOf("prepare", "show"), calls)
    }

    @Test
    fun `a native screen with its own minimum background time uses it instead of open_resume's delay`() {
        ResumeAds.nativeScreen = object : ResumeAds.NativeScreen {
            override fun prepare(context: Context) { calls += "prepare" }
            override fun show(host: Activity) { calls += "show" }
            override fun minBackgroundMs(): Long = 1_000L
        }
        ResumeAds.onStop(owner)
        main.idleFor(Duration.ofMillis(999))
        ResumeAds.onStart(owner)
        assertEquals("A return inside the screen's own second opens nothing", emptyList<String>(), calls)
        ResumeAds.onStop(owner)
        main.idleFor(Duration.ofMillis(1_000))
        ResumeAds.onStart(owner)
        assertEquals(listOf("prepare", "show"), calls)
    }

    @Test
    fun `the selected type's key switched off means no resume, with no fallback`() {
        AdRemoteConfig.initializeFromJson(
            """{"native_resume":{"ids":[{"id":"native-unit"}],"isEnable":false},""" +
                """"open_resume":{"ids":[{"id":"open-unit"}],"isEnable":true}}""",
        )
        ResumeAds.onStop(owner)
        manager.onStop()
        main.idleFor(Duration.ofMillis(3_000))
        ResumeAds.onStart(owner)
        manager.onResume()
        main.idle()
        assertEquals(emptyList<String>(), calls)
        assertEquals(0, ResumeLoadGmaShadow.requests.size)
        assertEquals(0, Int02InterstitialShadow.requests.size)
    }

    @Test
    fun `a type changed while away cancels the return`() {
        ResumeAds.onStop(owner)
        main.idleFor(Duration.ofMillis(2_000))
        AdBehavior.document.install("""{"app_resume":{"type":"none"}}""", null)
        ResumeAds.onStart(owner)
        assertEquals(listOf("prepare"), calls)
    }

    @Test
    fun `excluded screens, excused trips and the process switch keep native resume idle`() {
        manager.disableAppResumeWithActivity(Int02Activity::class.java)
        ResumeAds.onStop(owner)
        main.idleFor(Duration.ofMillis(3_000))
        ResumeAds.onStart(owner)
        manager.enableAppResumeWithActivity(Int02Activity::class.java)

        manager.disableAdResumeByClickAction()
        ResumeAds.onStop(owner)
        main.idleFor(Duration.ofMillis(3_000))
        ResumeAds.onStart(owner)
        manager.setDisableAdResumeByClickAction(false)

        manager.disableAppResume()
        ResumeAds.onStop(owner)
        main.idleFor(Duration.ofMillis(3_000))
        ResumeAds.onStart(owner)
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
            ResumeAds.onStop(owner)
            main.idleFor(Duration.ofMillis(2_000))
            manager.setResumeSkipPolicy(ResumeSkipPolicy { "suppressed_by_flow" })
            manager.onResume()
            ResumeAds.onStart(owner)
            assertEquals(listOf<Any?>("native_resume"), skipped)
            assertEquals(listOf("prepare"), calls)
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
