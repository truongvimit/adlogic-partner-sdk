package io.onboardkit.ads

import android.app.Activity
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import com.ads.module.helper.adnative.NativeClickAction
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.NativeAdUnit

internal interface AdEventListener {
    fun onLoaded() {}
    fun onFailedToLoad() {}
    fun onImpression() {}
    fun onClicked() {}

    // Meta's native adapter reports only this and Pangle's only the click: listen to both.
    fun onAdOpened() {}

    fun onDetached() {}
}

internal data class NativeAdRequest(
    val placement: AdPlacement,
    val unit: NativeAdUnit,
    @LayoutRes val layoutRes: Int,
    val allowWhileVisible: Boolean = false,
)

internal enum class NativeStatus { IDLE, LOADING, READY, FAILED }

/** The flow's ad engine; [io.onboardkit.ads.erain.ERainAdProvider] is the only implementation. */
abstract class OnboardingAdProvider internal constructor() {

    internal abstract fun pendingClickAction(placement: AdPlacement): NativeClickAction?

    internal abstract fun preloadNative(activity: Activity, request: NativeAdRequest)

    internal abstract fun nativeStatus(placement: AdPlacement): NativeStatus

    // True for a bind done inside this call, not echoed as onLoaded; later binds report onLoaded.
    internal abstract fun bindNative(
        activity: ComponentActivity,
        request: NativeAdRequest,
        container: FrameLayout,
        listener: AdEventListener,
    ): Boolean

    internal abstract fun releaseNative(placement: AdPlacement)

    internal abstract fun loadInterstitial(
        activity: Activity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        adConfigKey: String? = null,
        listener: AdEventListener? = null,
    )

    internal abstract fun isInterstitialReady(placement: AdPlacement): Boolean

    internal abstract fun readyInterstitialUnitId(placement: AdPlacement): String?

    internal abstract fun releaseInterstitial(placement: AdPlacement)

    internal abstract fun showInterstitial(
        activity: Activity,
        placement: AdPlacement,
        callback: ObInterstitialCallback,
    )

    // timeoutMs bounds the wait for a fill only; a late fill never auto-shows for this call.
    internal abstract fun loadAndShowInterstitial(
        activity: AppCompatActivity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        callback: ObInterstitialCallback,
        timeoutMs: Long,
    )

    internal abstract fun loadBanner(
        activity: AppCompatActivity,
        unit: BannerAdUnit,
        listener: AdEventListener,
    )

    internal abstract fun releaseAll()
}
