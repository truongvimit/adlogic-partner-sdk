package io.onboardkit.ui.splash

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
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
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.NativeProviderHost
import io.onboardkit.ads.NativeProviderViewShadow
import io.onboardkit.ads.NativeStatus
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.remote.RemoteFlags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo
import java.time.Duration

/** The real native Activity, provider, helper and XML; only GMA loading/registration is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [NativeProviderViewShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SplashNativeBindingTest {
    private lateinit var preloadHost: ActivityController<NativeProviderHost>
    private var screen: ActivityController<ObSplashNativeActivity>? = null
    private lateinit var provider: ERainAdProvider
    private lateinit var builders: MockedConstruction<AdLoader.Builder>
    private val requests = mutableListOf<NativeAd.OnNativeAdLoadedListener>()
    private val loadListeners = mutableListOf<AdListener>()
    private val unit = NativeAdUnit("splash-native-binding")
    private val main get() = shadowOf(Looper.getMainLooper())
    private val activity get() = requireNotNull(screen).get()

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        NativeAdManager.releaseAll()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        builders = mockConstruction(AdLoader.Builder::class.java) { builder, _ ->
            var loaded: NativeAd.OnNativeAdLoadedListener? = null
            doAnswer { loaded = it.getArgument(0); builder }.`when`(builder).forNativeAd(any())
            doAnswer { loadListeners += it.getArgument<AdListener>(0); builder }.`when`(builder).withAdListener(any(AdListener::class.java))
            doReturn(builder).`when`(builder).withNativeAdOptions(any())
            val loader = mock(AdLoader::class.java)
            doReturn(loader).`when`(builder).build()
            doAnswer { requests += checkNotNull(loaded); null }.`when`(loader).loadAd(any(AdRequest::class.java))
        }
        provider = OnboardingSdk.provider() as? ERainAdProvider ?: ERainAdProvider()
        provider.releaseAll()
        OnboardingSdk.install(app) { adProvider = provider; trackkitAutoTracking(false) }
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        com.ads.module.config.settings.AdBehavior.document.acceptSuccessfulFetch(null)
        runBlocking { OnboardingSdk.reset() }
        OnboardingSdk.remoteOrNull()?.applySnapshot(RemoteFlags())
        OnboardingSdk.configure(onboardKitConfig {
            ads = AdsConfig(splashNative = unit)
        }.getOrThrow()).getOrThrow()
        preloadHost = Robolectric.buildActivity(NativeProviderHost::class.java)
            .setup().visible().windowFocusChanged(true)
    }

    @After fun cleanup() {
        screen?.pause()?.stop()?.destroy()
        preloadHost.pause().stop().destroy()
        provider.releaseAll()
        main.idleFor(Duration.ofSeconds(61))
        NativeAdManager.releaseAll()
        builders.close()
        ConsentCenter.clearHostConsent()
        OnboardingSettings.document.acceptSuccessfulFetch(null)
    }

    private fun preload(): NativeAd {
        provider.preloadNative(preloadHost.get(), NativeAdRequest(
            AdPlacement.SplashNative, unit, R.layout.ob_layout_native_fullscreen))
        return mock(NativeAd::class.java).also {
            doReturn("Fullscreen native").`when`(it).headline
            doReturn("Install").`when`(it).callToAction
            requests.single().onNativeAdLoaded(it)
            main.idle()
            assertEquals(NativeStatus.READY, provider.nativeStatus(AdPlacement.SplashNative))
        }
    }

    private fun create() {
        screen = Robolectric.buildActivity(ObSplashNativeActivity::class.java)
        activity.setTheme(R.style.ob_Theme_OnboardKit_FullScreenAd)
        requireNotNull(screen).create().start()
        main.idle()
    }

    private fun resume() {
        requireNotNull(screen).resume().visible()
        main.idle()
    }

    private fun assertBound() {
        assertFalse("A ready native must remain on screen", activity.isFinishing)
        val container = activity.findViewById<FrameLayout>(R.id.ob_native_container)
        assertEquals(View.VISIBLE, container.visibility)
        assertTrue("The real fullscreen NativeAdView must be bound", container.getChildAt(0) is NativeAdView)
        assertEquals("Showing a buffered native never requests another ad", 1, requests.size)
    }

    @Test fun `ready native waits for resume then binds without a second request`() {
        val ad = preload()
        create()
        assertFalse("onCreate must not mistake a deferred bind for an unavailable ad", activity.isFinishing)
        main.idleFor(Duration.ofSeconds(30))
        assertFalse("The display timer starts only after the native can bind", activity.isFinishing)
        resume()
        assertBound()
        verify(ad, never()).destroy()
        main.idleFor(Duration.ofSeconds(3))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.ob_skip_button).visibility)
    }

    @Test fun `loading preload waits with shimmer and binds the original request`() {
        provider.preloadNative(preloadHost.get(), NativeAdRequest(
            AdPlacement.SplashNative, unit, R.layout.ob_layout_native_fullscreen))
        assertEquals(NativeStatus.LOADING, provider.nativeStatus(AdPlacement.SplashNative))
        create()
        resume()
        assertFalse(activity.isFinishing)
        assertEquals(1, requests.size)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.ob_native_container).visibility)
        main.idleFor(Duration.ofSeconds(5))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.ob_skip_button).visibility)
        assertFalse(activity.isFinishing)
        val ad = mock(NativeAd::class.java)
        doReturn("Late native").`when`(ad).headline
        requests.single().onNativeAdLoaded(ad)
        main.idle()
        assertBound()
        main.idleFor(Duration.ofMillis(2_999))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.ob_skip_button).visibility)
        main.idleFor(Duration.ofMillis(1))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.ob_skip_button).visibility)
        main.idleFor(Duration.ofSeconds(60))
        assertFalse("Only the user's close action leaves a filled native", activity.isFinishing)
        assertEquals(1, requests.size)
        activity.findViewById<View>(R.id.ob_skip_button).performClick()
        assertTrue(activity.isFinishing)
    }

    @Test fun `queued preload follows the native screen without waiting for the hidden splash`() {
        preloadHost.pause().stop()
        provider.preloadNative(preloadHost.get(), NativeAdRequest(
            AdPlacement.SplashNative, unit, R.layout.ob_layout_native_fullscreen))
        assertEquals(NativeStatus.LOADING, provider.nativeStatus(AdPlacement.SplashNative))
        assertTrue(requests.isEmpty())
        create()
        resume()
        requireNotNull(screen).windowFocusChanged(true)
        main.idle()
        assertFalse(activity.isFinishing)
        assertEquals("The existing queued preload dispatches once on the new host", 1, requests.size)
        requests.single().onNativeAdLoaded(mock(NativeAd::class.java))
        main.idle()
        assertBound()
    }

    @Test fun `failed preload leaves without retrying the network request`() {
        provider.preloadNative(preloadHost.get(), NativeAdRequest(
            AdPlacement.SplashNative, unit, R.layout.ob_layout_native_fullscreen))
        create()
        resume()
        assertFalse(activity.isFinishing)
        loadListeners.single().onAdFailedToLoad(com.google.android.gms.ads.LoadAdError(3, "No fill", "test", null, null))
        main.idle()
        assertTrue(activity.isFinishing)
        assertEquals(1, requests.size)
    }

    @Test fun `configuration recreation restores the consumed fullscreen native`() {
        val ad = preload()
        create()
        resume()
        assertBound()
        assertNotEquals("The fill is owned by the screen, no longer the unused buffer", NativeStatus.READY,
            provider.nativeStatus(AdPlacement.SplashNative))
        requireNotNull(screen).configurationChange(android.content.res.Configuration(activity.resources.configuration)
            .apply { fontScale += 0.1f })
        requireNotNull(screen).visible()
        main.idle()
        assertBound()
        verify(ad, never()).destroy()
    }
}
