package io.onboardkit.ads

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.ads.module.helper.adnative.NativeAdShimmer
import com.ads.module.helper.adnative.NativeClickAction
import com.facebook.shimmer.ShimmerFrameLayout
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.core.ObLog
import io.onboardkit.remote.OnboardingSettings

// Ends each attempt with onBound or onUnavailable on main; an unavailability waits for RESUMED.
internal fun ComponentActivity.showNativeAd(
    placement: AdPlacement,
    unit: NativeAdUnit?,
    container: FrameLayout,
    onBound: () -> Unit = {},
    onShown: () -> Unit = {},
    onUnavailable: (AdSkipReason) -> Unit = {},
    onAdEngaged: (NativeClickAction) -> Unit = {},
    reuseFailedPreload: Boolean = false,
    preloadedOnly: Boolean = false,
    onLoading: () -> Unit = {},
) {
    val provider = OnboardingSdk.provider()
    if (provider == null || unit == null) {
        placement.reportUnavailable(AdSkipReason.NO_AD_UNIT, onUnavailable)
        return
    }
    OnboardingSdk.guard().skipReason(this, placement)?.let { reason ->
        placement.reportUnavailable(reason, onUnavailable)
        return
    }

    val request = NativeAdRequest(placement, unit, NativeTemplates.layoutForPlacement(placement))
    var skeleton: ShimmerFrameLayout? = null
    var loadFailed = false
    val unavailable = ResumedDelivery(container.findViewTreeLifecycleOwner() ?: this)
    val tracked = placement.tracked(
        object : AdEventListener {
            override fun onLoaded() = onMainThread {
                skeleton?.stopShimmer()
                onBound()
            }

            override fun onClicked() = onMainThread { onAdEngaged(clickAction()) }

            override fun onAdOpened() = onMainThread { onAdEngaged(clickAction()) }

            private fun clickAction() = provider.pendingClickAction(placement)
                ?: OnboardingSettings.nativeClickAction(placement)

            override fun onFailedToLoad() = onMainThread {
                skeleton?.stopShimmer()
                ObLog.w(ObLog.Section.LOAD, "${placement.key} native unavailable — no fill")
                onUnavailable(AdSkipReason.NO_FILL)
            }

            override fun onImpression() = onMainThread { onShown() }
        },
    )
    val listener = object : AdEventListener by tracked {
        override fun onFailedToLoad() = runOnUiThread {
            loadFailed = true
            unavailable.deliver(tracked::onFailedToLoad)
        }

        override fun onDetached() = unavailable.cancel()
    }

    placement.trackRequest()
    if (bindBuffered(provider, request, container, listener)) {
        listener.onLoaded()
        return
    }
    // A provider may reject synchronously while binding; do not resurrect its shimmer.
    if (loadFailed) return
    if (preloadedOnly && provider.nativeStatus(placement) != NativeStatus.LOADING) {
        placement.reportUnavailable(AdSkipReason.NOT_READY, onUnavailable)
        return
    }
    if (reuseFailedPreload && provider.nativeStatus(placement) == NativeStatus.FAILED) {
        provider.releaseNative(placement)
        placement.reportUnavailable(AdSkipReason.NO_FILL, onUnavailable)
        return
    }
    onLoading()
    skeleton = NativeAdShimmer.from(this, request.layoutRes).also {
        container.removeAllViews()
        container.addView(it)
        container.visibility = View.VISIBLE
        it.startShimmer()
    }
    // bindNative already subscribed to the pending preload; never start or retry a request here.
    if (preloadedOnly) return
    if (!OnboardingSdk.preload().requestNativeOnce(this, request)) {
        skeleton.stopShimmer()
        placement.reportUnavailable(AdSkipReason.NO_FILL, onUnavailable)
    }
}

private fun AdPlacement.reportUnavailable(
    reason: AdSkipReason,
    onUnavailable: (AdSkipReason) -> Unit,
) {
    trackSkipped(reason)
    onUnavailable(reason)
}

private fun ComponentActivity.bindBuffered(
    provider: OnboardingAdProvider,
    request: NativeAdRequest,
    container: FrameLayout,
    listener: AdEventListener,
): Boolean {
    if (isFinishing || isDestroyed) return false
    return provider.bindNative(this, request, container, listener)
}

private class ResumedDelivery(private val owner: LifecycleOwner) {
    private var pending = false
    private var stopWaiting: () -> Unit = {}

    fun deliver(block: () -> Unit) {
        if (pending) return
        pending = true
        stopWaiting = owner.whenResumed(onHostLost = { pending = false }) {
            pending = false
            block()
        }
    }

    fun cancel() {
        pending = false
        stopWaiting()
        stopWaiting = {}
    }
}

private fun Activity.onMainThread(block: () -> Unit) {
    if (isFinishing || isDestroyed) return
    runOnUiThread {
        if (!isFinishing && !isDestroyed) block()
    }
}
