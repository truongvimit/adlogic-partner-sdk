package io.onboardkit.ads

import android.app.Activity
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.ads.module.helper.adnative.NativeClickAction
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import kotlinx.coroutines.launch

/** Nothing buffered and every call a no-op; a test overrides only what it drives. */
internal open class FakeAdProvider : OnboardingAdProvider() {
    override fun pendingClickAction(placement: AdPlacement): NativeClickAction? = null

    override fun preloadNative(activity: Activity, request: NativeAdRequest) = Unit

    override fun nativeStatus(placement: AdPlacement) = NativeStatus.IDLE

    override fun bindNative(
        activity: ComponentActivity,
        request: NativeAdRequest,
        container: FrameLayout,
        listener: AdEventListener,
    ): Boolean = false

    override fun releaseNative(placement: AdPlacement) = Unit

    override fun loadInterstitial(
        activity: Activity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        adConfigKey: String?,
        listener: AdEventListener?,
    ) = Unit

    override fun isInterstitialReady(placement: AdPlacement) = false

    override fun readyInterstitialUnitId(placement: AdPlacement): String? = null

    override fun releaseInterstitial(placement: AdPlacement) = Unit

    override fun showInterstitial(
        activity: Activity,
        placement: AdPlacement,
        callback: ObInterstitialCallback,
    ) = Unit

    override fun loadAndShowInterstitial(
        activity: AppCompatActivity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        callback: ObInterstitialCallback,
        timeoutMs: Long,
    ) = Unit

    override fun loadBanner(activity: AppCompatActivity, unit: BannerAdUnit, listener: AdEventListener) = Unit

    override fun releaseAll() = Unit

    /** Runs [send] when the SDK's provider would dispatch [request]: now, or once its window opens. */
    protected fun sendInRequestWindow(activity: Activity, request: NativeAdRequest, send: () -> Unit) {
        if (activity.canStartNativeRequest(request.allowWhileVisible)) return send()
        val owner = activity as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
            if (activity.awaitNativeRequestWindow(request.allowWhileVisible)) send()
        }
    }
}
