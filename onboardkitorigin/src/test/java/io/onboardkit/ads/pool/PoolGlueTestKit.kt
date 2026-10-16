package io.onboardkit.ads.pool

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.adnative.pool.MainLooperPoolScheduler
import com.ads.module.helper.adnative.pool.NativePoolEngine
import com.ads.module.helper.adnative.pool.PoolAdLoader
import com.ads.module.helper.adnative.pool.PoolExpiry
import com.ads.module.helper.adnative.pool.PoolLoadSink
import com.ads.module.helper.adnative.pool.PoolParity
import com.ads.module.helper.adnative.pool.PoolRequest
import com.ads.module.helper.adnative.pool.PoolRequestPolicy
import com.ads.module.helper.adnative.pool.SharedNativePool
import com.ads.module.helper.adnative.pool.SystemPoolClock
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.ads.NativeStatus
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.PrivacyGoalsScreenConfig
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.AnalyticsHub
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.flow.NativeFlowType
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.remote.RemoteFlags
import kotlinx.coroutines.Dispatchers
import org.mockito.Mockito.mock
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowViewGroup
import org.robolectric.util.ReflectionHelpers
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Gate and selection update inside every publish, so a test reads them right after a call. */
internal val SYNC_PARITY = PoolParity(asyncGate = false, asyncSelector = false)

internal fun poolSnapshot(parity: PoolParity = SYNC_PARITY, adoptSplashLanguage1: Boolean = true) =
    NativeFlowSnapshot(type = NativeFlowType.SHARED_POOL, parity = parity, adoptSplashLanguage1 = adoptSplashLanguage1)

/** Records each id attempt; the test settles it through that attempt's sink. */
internal class ApFakeLoader : PoolAdLoader<ApNativeAd> {
    inner class Attempt(val request: PoolRequest, val id: String, val sink: PoolLoadSink<ApNativeAd>) {
        var ad: ApNativeAd? = null
            private set

        fun fill(): ApNativeAd = ApNativeAd(R.layout.ob_layout_native_lfo, mock(NativeAd::class.java)).also {
            ad = it
            sink.onLoaded(it)
        }
    }

    val attempts = mutableListOf<Attempt>()

    override fun load(request: PoolRequest, id: String, sink: PoolLoadSink<ApNativeAd>) {
        attempts += Attempt(request, id, sink)
    }

    fun callsFor(key: String): List<String> = attempts.filter { it.request.key == key }.map { it.id }
    fun last(key: String): Attempt = attempts.last { it.request.key == key }
    fun fill(key: String): ApNativeAd = last(key).fill()

    /** Fails every id of the running chain until it is exhausted. */
    fun exhaust(key: String) {
        var seen = -1
        while (attempts.size != seen) {
            seen = attempts.size
            last(key).sink.onFailed()
        }
    }

    fun impress(ad: ApNativeAd) = attempts.first { it.ad === ad }.sink.onImpression(ad)
    fun click(ad: ApNativeAd) = attempts.first { it.ad === ad }.sink.onClicked(ad)
}

/** A sync engine on the real main-looper scheduler, installed as the process pool. */
internal fun installTestEngine(loader: ApFakeLoader): NativePoolEngine<ApNativeAd> = NativePoolEngine(
    loader = loader,
    policy = PoolRequestPolicy.ALLOW_ALL,
    parity = SYNC_PARITY,
    clock = SystemPoolClock,
    scheduler = MainLooperPoolScheduler(),
    expiry = PoolExpiry { 0L },
    derivedDispatcher = Dispatchers.Unconfined,
).also(SharedNativePool::installForTest)

/** The provider every pool test installs; only what adoption reads is controllable. */
internal object PoolTestProvider : FakeAdProvider() {
    var language1Status = NativeStatus.IDLE
    val released = mutableListOf<AdPlacement>()

    override fun nativeStatus(placement: AdPlacement) =
        if (placement == AdPlacement.Language1) language1Status else NativeStatus.IDLE

    override fun releaseNative(placement: AdPlacement) {
        released += placement
    }

    fun reset() {
        language1Status = NativeStatus.IDLE
        released.clear()
    }
}

internal val LFO1 = NativeAdUnit(listOf("lfo1-high", "lfo1-all"))
internal val LFO2 = NativeAdUnit(listOf("lfo2-high", "lfo2-all"))

internal val POOL_ADS = AdsConfig(
    languageNative = LFO1,
    languageDupNative = LFO2,
    contentStepNative = NativeAdUnit("content"),
    fullScreenStepNative = NativeAdUnit("fullscreen"),
    resumeNative = NativeAdUnit("resume"),
)

/**
 * A fresh SDK per test: install() keeps the first provider for good, so the field is cleared
 * first, as NativeTemplateIdTest does. Every gate starts open.
 */
internal object PoolTestSdk {
    val analytics = CopyOnWriteArrayList<AnalyticsEvent>()
    private var listening = false

    fun install(app: Application, ads: AdsConfig = POOL_ADS) {
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
        AdRemoteConfig.reset()
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdBehavior.document.acceptSuccessfulFetch(null)
        OnboardingSdk.install(app) {
            adProvider = PoolTestProvider
            trackkitAutoTracking(false)
        }
        if (!listening) {
            AnalyticsHub.addPlugin { analytics += it }
            listening = true
        }
        analytics.clear()
        PoolTestProvider.reset()
        OnboardingSdk.remoteOrNull()?.applySnapshot(RemoteFlags())
        configure(ads)
        allowAds(app)
    }

    fun configure(ads: AdsConfig = POOL_ADS, privacyGoals: PrivacyGoalsScreenConfig = PrivacyGoalsScreenConfig()) {
        OnboardingSdk.configure(onboardKitConfig {
            defaultSteps()
            this.ads = ads
            privacyGoalsScreen = privacyGoals
        }.getOrThrow()).getOrThrow()
    }

    fun tearDown() {
        OnboardingPool.resetForTest()
        SharedNativePool.resetForTest()
        shadowOf(Looper.getMainLooper()).idle()
        ConsentCenter.clearHostConsent()
        setPremium(false)
        AdRemoteConfig.reset()
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        AdBehavior.document.acceptSuccessfulFetch(null)
        PoolTestProvider.reset()
        ReflectionHelpers.setField(OnboardingSdk, "application", null)
    }

    inline fun <reified T : AnalyticsEvent> events(): List<T> = analytics.filterIsInstance<T>()

    fun requested(): List<String> = events<AnalyticsEvent.AdRequested>().map { it.placementName }

    fun skipped(): List<Pair<String, String>> = events<AnalyticsEvent.AdSkipped>().map { it.placementName to it.reason }
}

internal fun allowAds(context: Context) {
    ConsentCenter.setHostConsent(true, false)
    setPremium(false)
    setOnline(context, true)
}

internal fun setPremium(premium: Boolean) {
    Entitlement.install(object : EntitlementSource {
        override fun isPremium(context: Context) = premium
    })
}

@Suppress("DEPRECATION")
internal fun setOnline(context: Context, online: Boolean) {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    if (!online) {
        shadowOf(connectivity).setActiveNetworkInfo(null)
        shadowOf(connectivity).setDefaultNetworkActive(false)
        return
    }
    shadowOf(connectivity).setDefaultNetworkActive(true)
    shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
        android.net.NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
    shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
        NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
}

/** The view each vendor ad was registered to last: GMA reports its impressions and clicks from that one. */
internal val lastRegisteredView = WeakHashMap<NativeAd, NativeAdView>()

/** Keeps GMA out of view registration and counts destroys; inflation and populate stay real. */
@Implements(value = NativeAdView::class, isInAndroidSdk = false)
class PoolGlueViewShadow : ShadowViewGroup() {
    private val assets = mutableMapOf<String, android.view.View?>()
    var registered: NativeAd? = null
    var destroyCount = 0

    @Implementation fun setNativeAd(ad: NativeAd) {
        registered = ad
        lastRegisteredView[ad] = realView as NativeAdView
    }
    @Implementation fun destroy() { destroyCount++ }
    @Implementation fun setHeadlineView(view: android.view.View?) { assets["Headline"] = view }
    @Implementation fun getHeadlineView(): android.view.View? = assets["Headline"]
    @Implementation fun setBodyView(view: android.view.View?) { assets["Body"] = view }
    @Implementation fun getBodyView(): android.view.View? = assets["Body"]
    @Implementation fun setCallToActionView(view: android.view.View?) { assets["CallToAction"] = view }
    @Implementation fun getCallToActionView(): android.view.View? = assets["CallToAction"]
    @Implementation fun setIconView(view: android.view.View?) { assets["Icon"] = view }
    @Implementation fun getIconView(): android.view.View? = assets["Icon"]
    @Implementation fun setPriceView(view: android.view.View?) { assets["Price"] = view }
    @Implementation fun getPriceView(): android.view.View? = assets["Price"]
    @Implementation fun setStarRatingView(view: android.view.View?) { assets["StarRating"] = view }
    @Implementation fun getStarRatingView(): android.view.View? = assets["StarRating"]
    @Implementation fun setAdvertiserView(view: android.view.View?) { assets["Advertiser"] = view }
    @Implementation fun getAdvertiserView(): android.view.View? = assets["Advertiser"]
    @Implementation fun setMediaView(view: com.google.android.gms.ads.nativead.MediaView?) {}
}
