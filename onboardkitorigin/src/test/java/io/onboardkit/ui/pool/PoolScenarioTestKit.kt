package io.onboardkit.ui.pool

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.settings.AdBehavior
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.pool.PoolTestSdk
import io.onboardkit.ads.pool.allowAds
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.PrivacyGoalsScreenConfig
import io.onboardkit.config.StepDefinition
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.AnalyticsHub
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.remote.RemoteFlags
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The ad vendor behind both flow types in a whole run. Classic natives bind at once except
 * [heldNatives], which wait for [fillNative]; interstitial presentations wait for the test to end
 * them; a pager-exit load-and-show is recorded and refused.
 */
internal class ScenarioVendor : FakeAdProvider() {
    var interstitialReady = true
    var heldNatives: Set<AdPlacement> = setOf(AdPlacement.Language2)
    val presented = mutableListOf<AdPlacement>()
    val presentations = mutableListOf<ObInterstitialCallback>()
    val loadAndShows = mutableListOf<AdPlacement>()
    val nativeRequests = mutableListOf<NativeAdRequest>()
    val interstitialLoads = mutableListOf<Pair<AdPlacement, AdEventListener?>>()
    val backgroundPreloads = mutableListOf<AdPlacement>()
    private val nativeListeners = mutableMapOf<AdPlacement, AdEventListener>()
    var releaseAllCalls = 0
        private set

    override fun preloadNative(activity: Activity, request: NativeAdRequest) {
        nativeRequests += request
    }

    override fun preloadNativeInBackground(context: Context, request: NativeAdRequest) {
        backgroundPreloads += request.placement
    }

    override fun bindNative(
        activity: ComponentActivity,
        request: NativeAdRequest,
        container: FrameLayout,
        listener: AdEventListener,
    ): Boolean {
        nativeListeners[request.placement] = listener
        return request.placement !in heldNatives
    }

    /** A held classic native fills. */
    fun fillNative(placement: AdPlacement) = nativeListeners.getValue(placement).onLoaded()

    override fun loadInterstitial(
        activity: Activity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        adConfigKey: String?,
        listener: AdEventListener?,
    ) {
        interstitialLoads += placement to listener
    }

    override fun isInterstitialReady(placement: AdPlacement) =
        interstitialReady && placement == AdPlacement.SplashInterstitial

    override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) {
        presented += placement
        presentations += callback
    }

    override fun loadAndShowInterstitial(
        activity: AppCompatActivity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        callback: ObInterstitialCallback,
        timeoutMs: Long,
    ) {
        loadAndShows += placement
        callback.onAdSkipped(AdSkipReason.NOT_READY)
    }

    override fun releaseAll() {
        releaseAllCalls++
    }

    /** The process-wide presentation latch must not outlive a test. */
    fun endPresentations() {
        presentations.forEach { it.onAdSkipped(AdSkipReason.NOT_READY) }
        presentations.clear()
    }
}

/** A fresh SDK on a [ScenarioVendor] per test; the pool engine is installed by the test. */
internal object ScenarioSdk {
    val analytics = CopyOnWriteArrayList<AnalyticsEvent>()
    val outcomes = CopyOnWriteArrayList<OnboardingOutcome>()
    private var listening = false
    lateinit var vendor: ScenarioVendor
        private set

    fun install(app: Application): ScenarioVendor {
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
        AdRemoteConfig.reset()
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        OnboardingSettings.acceptLegacy(RemoteFlags(supplied = emptySet()))
        AdBehavior.document.acceptSuccessfulFetch(null)
        vendor = ScenarioVendor()
        OnboardingSdk.install(app) {
            adProvider = vendor
            trackkitAutoTracking(false)
        }
        OnboardingSdk.setListener { _, outcome -> outcomes += outcome }
        if (!listening) {
            AnalyticsHub.addPlugin { analytics += it }
            listening = true
        }
        analytics.clear()
        outcomes.clear()
        OnboardingSdk.session.reset()
        OnboardingSdk.remoteOrNull()?.applySnapshot(RemoteFlags())
        allowAds(app)
        return vendor
    }

    fun configure(
        steps: List<StepDefinition>,
        ads: AdsConfig = POOL_UI_ADS,
        privacyGoals: PrivacyGoalsScreenConfig = PrivacyGoalsScreenConfig(),
    ) {
        OnboardingSdk.configure(onboardKitConfig {
            steps.forEach(::step)
            language = POOL_UI_LANGUAGES
            this.ads = ads
            privacyGoalsScreen = privacyGoals
        }.getOrThrow()).getOrThrow()
    }

    fun tearDown() {
        if (::vendor.isInitialized) vendor.endPresentations()
        shadowOf(Looper.getMainLooper()).idle()
        OnboardingSdk.session.reset()
        OnboardingSettings.acceptLegacy(RemoteFlags(supplied = emptySet()))
        PoolTestSdk.tearDown()
    }

    inline fun <reified T : AnalyticsEvent> events(): List<T> = analytics.filterIsInstance<T>()
}
