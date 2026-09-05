package com.ads.module.helper

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The premium signal the ads pipeline gates on. Implemented by whichever billing engine the app
 * ships; `:billingkit` installs one automatically when both modules are on the classpath.
 * Call [Entitlement.notifyChanged] after changing the answer so existing observers can reread it.
 */
interface EntitlementSource {

    fun isPremium(context: Context): Boolean
}

/**
 * Process-wide holder for the installed [EntitlementSource].
 *
 * With no source installed every premium check answers false. Consent and the other ad gates
 * still apply to an IAA-only app.
 */
object Entitlement {

    @Volatile
    private var source: EntitlementSource? = null
    private val changes = MutableStateFlow(0L)

    @JvmStatic
    fun install(source: EntitlementSource) {
        Entitlement.source = source
        notifyChanged()
    }

    @JvmStatic
    fun isPremium(context: Context): Boolean {
        val current = source ?: return false
        // A throwing source must never take the ad pipeline down with it.
        return runCatching { current.isPremium(context) }.getOrDefault(false)
    }

    /**
     * Reads the current entitlement when collection starts, then after [install] or [notifyChanged].
     * Only changes to the premium answer are emitted. Rapid notifications may be conflated to the
     * latest answer; this is current state, not a purchase event stream.
     *
     * The flow keeps only the application context. Cancel collection when the observer is no
     * longer needed. A source that changes its answer must call [notifyChanged] after the change.
     */
    @JvmStatic
    fun observe(context: Context): Flow<Boolean> {
        val appContext = context.applicationContext
        return changes.map { isPremium(appContext) }.distinctUntilChanged()
    }

    /** Asks active observers to reread the installed source; safe to call from any thread. */
    @JvmStatic
    fun notifyChanged() {
        changes.update { it + 1 }
    }
}
