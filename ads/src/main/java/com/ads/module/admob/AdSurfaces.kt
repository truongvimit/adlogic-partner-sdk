package com.ads.module.admob

import android.app.Activity
import androidx.annotation.RestrictTo

/**
 * Activities an ad SDK owns, told apart by package namespace: GMA's AdActivity and every
 * mediated network's fullscreen, landing page, store or debugger screen. One in front is never a
 * host return. A network added to the mediation stack needs its namespace here, or its in-app
 * landing page counts as the host coming back.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object AdSurfaces {

    @JvmField
    val NAMESPACES: List<String> = listOf(
        "com.google.android.gms.ads.",
        "com.bytedance.sdk.openadsdk.",
        "com.mbridge.msdk.",
        "com.vungle.ads.",
        "com.facebook.ads.",
        "com.applovin.",
        "com.unity3d.ads.",
        "com.unity3d.services.ads.",
        "com.unity3d.ironsourceads.",
        "com.ironsource.",
    )

    @JvmStatic
    fun contains(activity: Activity?): Boolean = activity != null && isAdSurface(activity.javaClass.name)

    @JvmStatic
    fun isAdSurface(className: String): Boolean = NAMESPACES.any(className::startsWith)
}
