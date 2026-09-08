package io.onboardkit.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.startup.AppInitializer
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import com.ads.module.R
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.helper.interstitial.InterstitialAutoBuffer
import com.ads.module.helper.interstitial.InterstitialBufferOptions
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.config.InterstitialAdUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.TimeUnit


/** Runs the production provider, manager and waterfall; only Google ad callbacks and bootstrap are fake. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], application = ProviderWaitApplication::class,
    shadows = [ProviderWaitInterstitialShadow::class, ProviderWaitMobileAdsShadow::class, ProviderWaitFacebookShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class ERainInterstitialWaitTest {
    private lateinit var controller: ActivityController<ProviderWaitActivity>
    private lateinit var activity: ProviderWaitActivity
    private lateinit var provider: OnboardingAdProvider
    private val main get() = shadowOf(Looper.getMainLooper())
    private val requests get() = ProviderWaitInterstitialShadow.requests
    private val ids get() = ProviderWaitInterstitialShadow.ids
    private val placement = AdPlacement.AfterOnboardingInterstitial
    private val unit = InterstitialAdUnit(tiers = listOf("after-high", "after-base"))
    private val shown = mutableListOf<ProviderWaitVendorAd>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        InterstitialAutoBuffer.stop()
        InterstitialAutoBuffer.configure(InterstitialBufferOptions())
        InterstitialAdManager.releaseAll()
        requests.clear()
        ids.clear()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true
            )
        )
        shadowOf(connectivity).setNetworkCapabilities(
            connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
            setFacebookClientToken("provider-wait-test")
        })
        ERainAd.getInstance().setIntervalInterstitialAd(0)
        ERainAd.getInstance().setMaxClickAdsPerDay(0)
        ERainAd.getInstance().setCountClickToShowAds(1, 0)
        AppOpenManager.getInstance().disableAppResume()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        controller = Robolectric.buildActivity(ProviderWaitActivity::class.java).setup()
        activity = controller.get()
        provider = ERainAdProvider()
        main.idle()
    }

    @After
    fun tearDown() {
        shown.filter { it.hosts.isNotEmpty() }
            .forEach { it.callback.onAdDismissedFullScreenContent() }
        provider.releaseAll()
        InterstitialAutoBuffer.stop()
        InterstitialAutoBuffer.configure(InterstitialBufferOptions())
        ShadowDialog.getLatestDialog()?.dismiss()
        ConsentCenter.setHostConsent(false, false)
        controller.pause().stop().destroy()
        main.idleFor(800, TimeUnit.MILLISECONDS)
        AppOpenManager.getInstance().setInterstitialShowing(false)
    }

    @Test
    fun `onboarding exit preload stays independent of an unstarted content buffer`() {
        InterstitialAutoBuffer.configure(
            InterstitialBufferOptions(listOf("inter_all", placement.key))
        )

        provider.loadInterstitial(activity, placement, unit)

        assertEquals("Onboarding must preload before the first content screen starts buffering", listOf("after-high"), ids)
        val ad = vendor("after-high")
        requests.single().onAdLoaded(ad)
        main.idle()
        assertTrue(provider.isInterstitialReady(placement))
        assertFalse(InterstitialAutoBuffer.isRunning())
    }

    @Test
    fun `default timeout waits eight seconds through the real provider`() {
        val result = Outcome()
        val started = SystemClock.uptimeMillis()
        provider.loadAndShowInterstitial(activity, placement, unit, result)
        assertEquals(listOf("after-high"), ids)
        assertTrue(requireNotNull(ShadowDialog.getLatestDialog()).isShowing)
        advanceTo(started + 7_999)
        assertTrue(
            "skips=${result.skips}, elapsed=${SystemClock.uptimeMillis() - started}",
            result.skips.isEmpty()
        )
        assertEquals(0, result.next)
        main.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(listOf(AdSkipReason.NOT_READY), result.skips)
        assertEquals(0, result.next)
        assertFalse(requireNotNull(ShadowDialog.getLatestDialog()).isShowing)
    }

    @Test
    fun `custom timeout joins preload and a late fill never auto shows`() {
        provider.loadInterstitial(activity, placement, unit)
        val result = Outcome()
        val started = SystemClock.uptimeMillis()
        provider.loadAndShowInterstitial(activity, placement, unit, result, timeoutMs = 1_250)
        assertEquals("Join must not replace or duplicate the preload", 1, requests.size)
        advanceTo(started + 1_249)
        assertTrue(
            "skips=${result.skips}, elapsed=${SystemClock.uptimeMillis() - started}",
            result.skips.isEmpty()
        )
        main.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(listOf(AdSkipReason.NOT_READY), result.skips)
        val ad = vendor("after-high")
        requests.single().onAdLoaded(ad)
        main.idleFor(9, TimeUnit.SECONDS)
        assertTrue(provider.isInterstitialReady(placement))
        assertTrue(ad.hosts.isEmpty())
        assertEquals(1, result.skips.size)
        assertEquals(0, result.next)
    }

    @Test
    fun `high failure loads base and shows before deadline then closes once`() {
        val result = Outcome()
        provider.loadAndShowInterstitial(activity, placement, unit, result)
        requests.single().onAdFailedToLoad(
            com.google.android.gms.ads.LoadAdError(
                3,
                "no fill",
                "test",
                null,
                null
            )
        )
        main.idle()
        assertEquals(listOf("after-high", "after-base"), ids)
        val ad = vendor("after-base")
        requests.last().onAdLoaded(ad)
        main.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), ad.hosts)
        assertEquals(1, result.next)
        assertTrue(result.skips.isEmpty())
        main.idleFor(9, TimeUnit.SECONDS)
        assertEquals("Fill timeout cannot end a displayed ad", 0, result.closed)
        ad.callback.onAdDismissedFullScreenContent()
        assertEquals(1, result.closed)
    }

    @Test
    fun `ready buffer shows without another load or waiting for timeout`() {
        provider.loadInterstitial(activity, placement, unit)
        val ad = vendor("after-high")
        requests.single().onAdLoaded(ad)
        main.idle()
        val result = Outcome()
        provider.loadAndShowInterstitial(activity, placement, unit, result, timeoutMs = 50)
        main.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
        assertEquals(listOf(activity), ad.hosts)
        assertTrue(result.skips.isEmpty())
        assertEquals(1, result.next)
    }

    private fun advanceTo(deadline: Long) {
        main.idleFor(
            (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0),
            TimeUnit.MILLISECONDS
        )
    }

    private fun vendor(id: String) = ProviderWaitVendorAd(id).also { shown += it }
    private class Outcome : ObInterstitialCallback() {
        val skips = mutableListOf<AdSkipReason>()
        var next = 0
        var closed = 0
        override fun onNextAction() {
            next++
        }

        override fun onAdClosed() {
            closed++
        }

        override fun onAdSkipped(reason: AdSkipReason) {
            skips += reason
        }
    }
}

class ProviderWaitVendorAd(private val unit: String) : InterstitialAd() {
    val hosts = mutableListOf<Activity>()
    var beforeShow: (() -> Unit)? = null
    private var storedCallback: FullScreenContentCallback? = null
    private var paidListener: OnPaidEventListener? = null
    private var placementValue = 0L
    val callback: FullScreenContentCallback get() = requireNotNull(storedCallback)
    override fun getAdUnitId(): String = unit
    override fun show(activity: Activity) {
        beforeShow?.invoke(); hosts += activity
    }

    override fun setFullScreenContentCallback(callback: FullScreenContentCallback?) {
        storedCallback = callback
    }

    override fun getFullScreenContentCallback(): FullScreenContentCallback = callback
    override fun setImmersiveMode(enabled: Boolean) = Unit
    override fun getResponseInfo(): ResponseInfo =
        throw UnsupportedOperationException("No paid event in lifecycle fixture")

    override fun setOnPaidEventListener(listener: OnPaidEventListener?) {
        paidListener = listener
    }

    override fun getOnPaidEventListener(): OnPaidEventListener = requireNotNull(paidListener)
    override fun getPlacementId(): Long = placementValue
    override fun setPlacementId(value: Long) {
        placementValue = value
    }
}

@Implements(value = InterstitialAd::class, isInAndroidSdk = false)
class ProviderWaitInterstitialShadow {
    companion object {
        val requests = mutableListOf<InterstitialAdLoadCallback>()
        val ids = mutableListOf<String>()

        @JvmStatic
        @Implementation
        fun load(
            context: Context,
            adUnitId: String,
            request: AdRequest,
            callback: InterstitialAdLoadCallback
        ) {
            ids += adUnitId
            requests += callback
        }
    }
}

@Implements(value = MobileAds::class, isInAndroidSdk = false)
class ProviderWaitMobileAdsShadow {
    companion object {
        private var configuration = RequestConfiguration.Builder().build()

        @JvmStatic
        @Implementation
        fun initialize(context: Context, callback: OnInitializationCompleteListener?) = Unit

        @JvmStatic
        @Implementation
        fun setRequestConfiguration(value: RequestConfiguration) {
            configuration = value
        }

        @JvmStatic
        @Implementation
        fun getRequestConfiguration(): RequestConfiguration = configuration
    }
}

@Implements(className = "com.facebook.FacebookSdk", isInAndroidSdk = false)
class ProviderWaitFacebookShadow {
    companion object {
        @JvmStatic
        @Implementation
        fun setClientToken(token: String) = Unit
        @JvmStatic
        @Implementation
        fun sdkInitialize(context: Context) = Unit
    }
}

class ProviderWaitActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
    }
}

/** Bootstrap dependency startup through Android's real provider, including reused AndroidX statics. */
class ProviderWaitApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (AppInitializer.getInstance(this)
                .isEagerlyInitialized(ProcessLifecycleInitializer::class.java)
        ) {
            registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    androidx.lifecycle.ReportFragment.injectIfNeededIn(activity)
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) =
                    Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            })
            ProcessLifecycleInitializer().create(this)
        } else {
            val info = ProviderInfo().apply {
                name = InitializationProvider::class.java.name
                packageName = this@ProviderWaitApplication.packageName
                authority = "$packageName.androidx-startup"
                metaData = Bundle().apply {
                    putString(ProcessLifecycleInitializer::class.java.name, "androidx.startup")
                }
            }
            shadowOf(packageManager).addOrUpdateProvider(info)
            InitializationProvider().attachInfo(this, info)
        }
    }
}
