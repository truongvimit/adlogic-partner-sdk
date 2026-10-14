package com.ads.module.helper.adnative.pool

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RestrictTo
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.tracking.AdTracking
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.LoadAdError

/**
 * One `ERainAd` native request per id; the glue, not this loader, reports `ad_request`.
 * An [AdGate] refusal is posted as a failure, never left silent like `Admob.loadNativeAd`'s.
 * A tap reaches the sink as one click, however many of clicked and opened report it.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class AdMobPoolLoader internal constructor(
    context: Context,
    private val requestNative: (Context, String, Int, AdCallback) -> Unit,
) : PoolAdLoader<ApNativeAd> {
    constructor(context: Context) : this(context, { appContext, id, layoutRes, callback ->
        ERainAd.getInstance().loadNativeAdResultCallback(appContext, id, layoutRes, callback)
    })

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override fun load(request: PoolRequest, id: String, sink: PoolLoadSink<ApNativeAd>) {
        val passesUaGate = AdGate.placementPassesUaGate(request.adConfigKey ?: request.key)
        if (AdGate.skipReason(appContext, true, passesUaGate) != null) {
            main.post { sink.onFailed() }
            return
        }
        // Paid events resolve their placement by id: it must name the lender, not a slot.
        AdTracking.registerPlacement(id, request.key)
        val callback = IdCallback(sink)
        try {
            requestNative(appContext, id, request.layoutRes, callback)
        } catch (e: Exception) {
            main.post { callback.fail() }
        }
    }

    private inner class IdCallback(private val sink: PoolLoadSink<ApNativeAd>) : AdCallback() {
        private var ad: ApNativeAd? = null
        private var settled = false
        private val gesture = PoolClickGesture(SystemPoolClock)

        override fun onNativeAdLoaded(nativeAd: ApNativeAd) = onMain {
            if (settled) {
                nativeAd.destroy()
                return@onMain
            }
            settled = true
            ad = nativeAd
            sink.onLoaded(nativeAd)
        }

        override fun onAdFailedToLoad(error: LoadAdError?) = fail()

        override fun onAdFailedToShow(adError: AdError?) = fail()

        override fun onAdImpression() = onMain { ad?.let(sink::onImpression) }

        override fun onAdClicked() = onMain { ad?.takeIf { gesture.clicked() }?.let(sink::onClicked) }

        override fun onAdOpened() = onMain { ad?.takeIf { gesture.opened() }?.let(sink::onClicked) }

        fun fail() = onMain {
            if (settled) return@onMain
            settled = true
            sink.onFailed()
        }

        private fun onMain(block: () -> Unit) {
            if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post(block)
        }
    }
}

/** Posts to the main looper; a 0 ms delay runs on the next loop, never inline. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class MainLooperPoolScheduler : PoolScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun postDelayed(delayMs: Long, block: () -> Unit): PoolCancellable {
        val runnable = Runnable { block() }
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
        return PoolCancellable { handler.removeCallbacks(runnable) }
    }
}

/** The time base `ApNativeAd` ages on, so pool expiry and `isUsable` agree. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object SystemPoolClock : PoolClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}
