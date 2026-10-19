package io.onboardkit.ads.pool

import androidx.annotation.MainThread
import com.ads.module.helper.adnative.NativeAdManager
import com.ads.module.helper.adnative.pool.SharedNativePool
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeStatus
import io.onboardkit.ads.trackRequest
import io.onboardkit.core.ObLog
import io.onboardkit.flow.NativeFlowSnapshot

/**
 * Hands the splash's LFO1 preload to the pool, so the pooled LFO never runs a second LFO1 chain
 * beside it (deviation D-PRE): a fill is adopted, a running load joined, a load still queued for
 * a request window cancelled so the pool's own claim is the only one.
 */
internal object PoolSplashAdoption {
    enum class Status(val wire: String) {
        READY("ready"),
        PENDING("pending"),
        CANCELLED_QUEUE("cancelled_queue"),

        /** No handoff, or the splash load failed or never started: the LFO's claim requests. */
        NONE("none"),
    }

    /**
     * @param handoff the LFO's own `takeLanguage1Preload()`, which it consumes so no stale flag
     *   reaches a later classic LFO
     */
    @MainThread
    fun run(handoff: Boolean, snapshot: NativeFlowSnapshot): Status {
        val status = if (handoff) adopt(snapshot) else Status.NONE
        ObLog.d(ObLog.Section.LOAD, "pool | adopt language1 status=${status.wire} handoff=$handoff")
        return status
    }

    private fun adopt(snapshot: NativeFlowSnapshot): Status {
        // Without a pool nothing would claim LFO1 in place of the cancelled queue.
        if (OnboardingPool.engineOrNull() == null) return Status.NONE
        val placement = AdPlacement.Language1
        val request = PoolPlacementMapper.requestFor(placement, snapshot) ?: return Status.NONE
        val adopted = when {
            SharedNativePool.adoptReadyFromManager(request) -> Status.READY
            SharedNativePool.adoptPendingFromManager(request) -> Status.PENDING
            else -> return cancelQueued(placement)
        }
        // The splash sent this chain without fo_ad_request, and the LFO's claim now finds it held or
        // in flight; counted here, as the classic LFO counts the splash buffer it binds.
        placement.trackRequest()
        return adopted
    }

    private fun cancelQueued(placement: AdPlacement): Status {
        val provider = OnboardingSdk.provider() ?: return Status.NONE
        // The provider still waits for the splash's request window; dispatched later, it would
        // become a load the pool never joined.
        if (provider.nativeStatus(placement) == NativeStatus.LOADING && !NativeAdManager.isLoading(placement.key)) {
            provider.releaseNative(placement)
            return Status.CANCELLED_QUEUE
        }
        return Status.NONE
    }
}
