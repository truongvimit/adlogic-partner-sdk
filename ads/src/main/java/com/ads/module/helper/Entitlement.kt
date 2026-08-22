package com.ads.module.helper

import android.content.Context

/**
 * The premium signal the ads pipeline gates on. Implemented by whichever billing engine the app
 * ships; `:billingkit` installs one automatically when both modules are on the classpath.
 */
interface EntitlementSource {

    fun isPremium(context: Context): Boolean
}

/**
 * Process-wide holder for the installed [EntitlementSource].
 *
 * With no source installed every check answers false — an IAA-only app sells nothing, so nobody
 * is ever premium and ads always run.
 */
object Entitlement {

    @Volatile
    private var source: EntitlementSource? = null

    @JvmStatic
    fun install(source: EntitlementSource) {
        Entitlement.source = source
    }

    @JvmStatic
    fun isPremium(context: Context): Boolean {
        val current = source ?: return false
        // A throwing source must never take the ad pipeline down with it.
        return runCatching { current.isPremium(context) }.getOrDefault(false)
    }
}
