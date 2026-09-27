package io.onboardkit.ui.splash

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.adnative.NativeAdManager
import com.ads.module.helper.adnative.NativeAdPreload
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.nativead.NativeAdView
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.NativeStatus
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.remote.OnboardingSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Google test ad, provider, cache, native screen and close button on a physical device. */
@RunWith(AndroidJUnit4::class)
class SplashNativeWaitDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun existingPreloadShowsRealNativeAndWaitsForUserToClose() {
        val queued = InstrumentationRegistry.getArguments().getString("queuedPreload") == "true"
        val initialized = CountDownLatch(1)
        val provider = CountingNativeProvider()
        val unit = NativeAdUnit("ca-app-pub-3940256099942544/2247696110")
        var fills = 0
        onMain {
            assertFalse("Use a fresh instrumentation process", OnboardingSdk.isReady())
            ConsentCenter.setHostConsent(true, false)
            Entitlement.install(object : EntitlementSource {
                override fun isPremium(context: Context) = false
            })
            ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
                setFacebookClientToken("123456789")
                setIdAdResume("")
            })
            AppOpenManager.getInstance().disableAppResume()
            MobileAds.initialize(app) { initialized.countDown() }
            OnboardingSdk.install(app) { adProvider = provider; trackkitAutoTracking(false) }
            OnboardingSettings.document.acceptSuccessfulFetch(
                """{"splash":{"native":{"auto_dismiss_ms":5000}}}""")
            OnboardingSdk.configure(onboardKitConfig {
                ads = AdsConfig(splashNative = unit)
            }.getOrThrow()).getOrThrow()
            assertEquals(3_000L, OnboardingSettings.number("splash.native.skip.delay_ms"))
            NativeAdPreload.getInstance().registerAdCallback(AdPlacement.SplashNative.key, object : AdCallback() {
                override fun onNativeAdLoaded(nativeAd: ApNativeAd) { fills++ }
            })
        }
        assertTrue("Google Mobile Ads initializes", initialized.await(45, TimeUnit.SECONDS))
        try {
            ActivityScenario.launch(SplashNativePreloadDeviceActivity::class.java).use { preload ->
                lateinit var host: SplashNativePreloadDeviceActivity
                preload.onActivity { host = it }
                eventually("Preload host is focused") { onMain { host.hasWindowFocus() } }
                if (queued) preload.moveToState(Lifecycle.State.CREATED)
                onMain {
                    provider.preloadNative(host, NativeAdRequest(
                        AdPlacement.SplashNative, unit, R.layout.ob_layout_native_fullscreen))
                    assertEquals(NativeStatus.LOADING, provider.nativeStatus(AdPlacement.SplashNative))
                    assertEquals("Queued mode must defer the vendor request", !queued,
                        NativeAdManager.isLoading(AdPlacement.SplashNative.key))
                }
                // Launch from the existing host, as production does. A second ActivityScenario
                // clears the task and cancels the preload owner's queued lifecycle work.
                val monitor = instrumentation.addMonitor(ObSplashNativeActivity::class.java.name, null, false)
                onMain { host.startActivity(Intent(host, ObSplashNativeActivity::class.java)) }
                val native = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
                    as? ObSplashNativeActivity) { "native_fs did not launch" }
                try {
                    assertFalse("The original splash remains behind native_fs", onMain { host.isDestroyed })
                    eventually("Original Google test ad binds on native_fs", 45_000) {
                        onMain {
                            assertFalse("Waiting screen must stay open", native.isFinishing || native.isDestroyed)
                            native.findViewById<FrameLayout>(R.id.ob_native_container)
                                .getChildAt(0) is NativeAdView
                        }
                    }
                    val skip = onMain { native.findViewById<View>(R.id.ob_skip_button) }
                    assertEquals(View.GONE, onMain { skip.visibility })
                    SystemClock.sleep(2_500)
                    assertEquals("X must not appear before 3 seconds after bind", View.GONE, onMain { skip.visibility })
                    eventually("X appears after 3 seconds", 1_500) { onMain { skip.visibility == View.VISIBLE } }
                    // Exceeds the old 15-second default as well as the legacy remote value above.
                    SystemClock.sleep(14_000)
                    onMain {
                        assertFalse("No automatic next screen", native.isFinishing || native.isDestroyed)
                        assertEquals("Entering native_fs must not preload again", 1, provider.requests)
                        assertEquals("Exactly one real Google fill", 1, fills)
                        assertFalse(NativeAdManager.isLoading(AdPlacement.SplashNative.key))
                        skip.performClick()
                    }
                    eventually("Only clicking X closes the native screen") { onMain { native.isFinishing } }
                } finally {
                    instrumentation.removeMonitor(monitor)
                    onMain { if (!native.isDestroyed) native.finish() }
                }
            }
        } finally {
            onMain {
                provider.releaseAll()
                NativeAdManager.releaseAll()
                ConsentCenter.clearHostConsent()
                OnboardingSettings.document.acceptSuccessfulFetch(null)
            }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    private fun eventually(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(message, condition())
    }

    private class CountingNativeProvider : FakeAdProvider() {
        private val real = ERainAdProvider()
        var requests = 0
        override fun preloadNative(activity: Activity, request: NativeAdRequest) {
            requests++
            real.preloadNative(activity, request)
        }
        override fun nativeStatus(placement: AdPlacement) = real.nativeStatus(placement)
        override fun bindNative(activity: ComponentActivity, request: NativeAdRequest,
            container: FrameLayout, listener: AdEventListener) = real.bindNative(activity, request, container, listener)
        override fun releaseNative(placement: AdPlacement) = real.releaseNative(placement)
        override fun releaseAll() = real.releaseAll()
    }
}

class SplashNativePreloadDeviceActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        setTurnScreenOn(true)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(state)
        setContentView(FrameLayout(this))
    }
}
