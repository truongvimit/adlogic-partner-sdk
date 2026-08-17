package com.ads.module.helper

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Base of every view-level ad helper: binds to one [LifecycleOwner] and owns the
 * activation flag plus the purchase/online/reload gating that every format shares.
 *
 * Subclasses MUST call [bindLifecycle] as the last statement of their constructor —
 * `addObserver` replays the current lifecycle state synchronously, so binding earlier
 * would dispatch events into a half-initialized subclass.
 */
abstract class AdsHelper<C : IAdsConfig, P : IAdsParam>(
    protected val context: Context,
    protected val lifecycleOwner: LifecycleOwner,
    val config: C,
) {

    protected val flagActive = AtomicBoolean(false)
    protected val mainHandler = Handler(Looper.getMainLooper())

    /** User-level reload kill switch, AND-ed with [IAdsConfig.canReloadAds]. */
    var flagUserEnableReload: Boolean = true

    private val lifecycleObserver = object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            onLifecycleEvent(event)
            if (event == Lifecycle.Event.ON_DESTROY) source.lifecycle.removeObserver(this)
        }
    }

    protected fun bindLifecycle() {
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
    }

    protected open fun onLifecycleEvent(event: Lifecycle.Event) {}

    protected fun isResumed(): Boolean =
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    open fun canShowAds(): Boolean = config.canShowAds && !AdGate.isPurchased(context)

    open fun canRequestAds(): Boolean = canShowAds() && AdGate.isNetworkAvailable(context)

    fun canReloadAd(): Boolean = config.canReloadAds && flagUserEnableReload

    fun isActiveState(): Boolean = flagActive.get()

    abstract fun requestAds(param: P)

    abstract fun cancel()
}
