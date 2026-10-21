package io.onboardkit.ui.pool

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.helper.adnative.pool.MainLooperPoolScheduler
import com.ads.module.helper.adnative.pool.NativePoolEngine
import com.ads.module.helper.adnative.pool.PoolExpiry
import com.ads.module.helper.adnative.pool.PoolRequestPolicy
import com.ads.module.helper.adnative.pool.SharedNativePool
import com.ads.module.helper.adnative.pool.SystemPoolClock
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.pool.ApFakeLoader
import io.onboardkit.ads.pool.LFO1
import io.onboardkit.ads.pool.LFO2
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolGlueViewShadow
import io.onboardkit.ads.pool.PoolTestSdk
import io.onboardkit.ads.pool.SYNC_PARITY
import io.onboardkit.ads.pool.allowAds
import io.onboardkit.ads.pool.installTestEngine
import io.onboardkit.ads.pool.poolSnapshot
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.LanguageConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.ObLanguages
import io.onboardkit.config.PrivacyGoalsScreenConfig
import io.onboardkit.config.StepDefinition
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.AnalyticsHub
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.remote.RemoteFlags
import io.onboardkit.ui.language.LanguageAdapter
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import androidx.activity.ComponentActivity

/** Every pooled UI slot of these suites, plus the confirm modal and the leftover splash interstitial. */
internal val POOL_UI_ADS = AdsConfig(
    splashInterstitial = InterstitialAdUnit("splash-inter"),
    languageNative = LFO1,
    languageDupNative = LFO2,
    languageConfirmNative = NativeAdUnit("confirm"),
    contentStepNative = NativeAdUnit("content"),
    fullScreenStepNative = NativeAdUnit("fullscreen"),
    resumeNative = NativeAdUnit("resume"),
)

internal val POOL_UI_LANGUAGES = LanguageConfig(
    languages = listOf("en-US", "es", "fr").map { requireNotNull(ObLanguages.find(it)) },
)

/** The splash interstitial is buffered on demand; its presentation waits for the test to end it. */
internal class PoolUiProvider : FakeAdProvider() {
    var interstitialReady = true
    val presentations = mutableListOf<ObInterstitialCallback>()
    val backgroundPreloads = mutableListOf<AdPlacement>()
    val interstitialLoads = mutableListOf<AdPlacement>()
    val loadAndShows = mutableListOf<AdPlacement>()

    /** The classic screens' native listener, so a test can report a fill, an impression or a no-fill. */
    val nativeListeners = mutableMapOf<AdPlacement, AdEventListener>()

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
        return false
    }

    override fun isInterstitialReady(placement: AdPlacement) =
        interstitialReady && placement == AdPlacement.SplashInterstitial

    override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) {
        presentations += callback
    }

    override fun loadInterstitial(
        activity: Activity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        adConfigKey: String?,
        listener: AdEventListener?,
    ) {
        interstitialLoads += placement
    }

    override fun loadAndShowInterstitial(
        activity: AppCompatActivity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        callback: ObInterstitialCallback,
        timeoutMs: Long,
    ) {
        loadAndShows += placement
        presentations += callback
    }

    /** The process-wide presentation latch must not outlive a test. */
    fun endPresentations() {
        presentations.forEach { it.onAdSkipped(AdSkipReason.NOT_READY) }
        presentations.clear()
    }
}

/**
 * [PoolTestSdk] with a provider that can show the splash interstitial and a language list of
 * three rows. Each install is a fresh SDK, session and engine; [tearDown] resets the pool.
 */
internal object PoolUiSdk {
    val analytics = CopyOnWriteArrayList<AnalyticsEvent>()
    private var listening = false
    lateinit var provider: PoolUiProvider
        private set

    fun install(app: Application, loader: ApFakeLoader): NativePoolEngine<ApNativeAd> {
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
        AdRemoteConfig.reset()
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdBehavior.document.acceptSuccessfulFetch(null)
        provider = PoolUiProvider()
        OnboardingSdk.install(app) {
            adProvider = provider
            trackkitAutoTracking(false)
        }
        if (!listening) {
            AnalyticsHub.addPlugin { analytics += it }
            listening = true
        }
        analytics.clear()
        OnboardingSdk.session.reset()
        OnboardingSdk.remoteOrNull()?.applySnapshot(RemoteFlags())
        configure()
        allowAds(app)
        return installTestEngine(loader)
    }

    fun configure(
        ads: AdsConfig = POOL_UI_ADS,
        steps: List<StepDefinition>? = null,
        privacyGoals: PrivacyGoalsScreenConfig = PrivacyGoalsScreenConfig(),
    ) {
        OnboardingSdk.configure(onboardKitConfig {
            if (steps == null) defaultSteps() else steps.forEach(::step)
            language = POOL_UI_LANGUAGES
            this.ads = ads
            privacyGoalsScreen = privacyGoals
        }.getOrThrow()).getOrThrow()
    }

    fun tearDown() {
        if (::provider.isInitialized) provider.endPresentations()
        shadowOf(Looper.getMainLooper()).idle()
        OnboardingSdk.session.reset()
        PoolTestSdk.tearDown()
    }

    inline fun <reified T : AnalyticsEvent> events(): List<T> = analytics.filterIsInstance<T>()

    /** App-resume holds still taken; every suppress must have been released. */
    fun suppressDepth(): Int = ReflectionHelpers.getField<AtomicInteger>(OnboardingSdk.appResume(), "depth").get()
}

/**
 * Swaps in an engine whose selection is derived in a later main-looper message, as production's
 * `async_selector` derives it on another thread: a slot hears a pool change only after the call
 * that made it has returned.
 */
internal fun installLaggingSelectorEngine(loader: ApFakeLoader): NativePoolEngine<ApNativeAd> {
    SharedNativePool.resetForTest()
    return NativePoolEngine(
        loader = loader,
        policy = PoolRequestPolicy.ALLOW_ALL,
        parity = SYNC_PARITY.copy(asyncSelector = true),
        clock = SystemPoolClock,
        scheduler = MainLooperPoolScheduler(),
        expiry = PoolExpiry { 0L },
        derivedDispatcher = Handler(Looper.getMainLooper()).asCoroutineDispatcher(),
    ).also(SharedNativePool::installForTest)
}

/** The vendor ad the container's current view registered, or null under a skeleton or hidden. */
internal fun FrameLayout.shownAd(): NativeAd? =
    (getChildAt(0) as? NativeAdView)?.takeIf { visibility == View.VISIBLE }
        ?.let { Shadow.extract<PoolGlueViewShadow>(it).registered }

/** The SHARED_POOL host on its LFO stage, every gate open, as [flow] latched it. */
internal fun launchPoolFlow(app: Application, flow: NativeFlowSnapshot = poolSnapshot()): ActivityController<ObPoolFlowActivity> {
    OnboardingSdk.session.nativeFlow = flow
    OnboardingPool.activate(app, flow)
    val main = shadowOf(Looper.getMainLooper())
    main.idle()
    return Robolectric.buildActivity(ObPoolFlowActivity::class.java).also {
        it.get().setTheme(R.style.ob_Theme_OnboardKit)
        it.setup()
        main.idle()
        // Row change animations would move the paused clock on every tap.
        it.get().findViewById<RecyclerView>(R.id.ob_language_list).itemAnimator = null
    }
}

internal fun ObPoolFlowActivity.tapLanguage(index: Int) {
    val list = findViewById<RecyclerView>(R.id.ob_language_list)
    val adapter = list.adapter as LanguageAdapter
    adapter.onCreateViewHolder(list, 0).also { adapter.onBindViewHolder(it, index) }.itemView.performClick()
    shadowOf(Looper.getMainLooper()).idle()
}
