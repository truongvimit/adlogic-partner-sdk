package com.ads.module.admob

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AdSurfacesTest {

    /** Namespaces whose Activities are not ads: the SDK's own, test doubles and Google's non-ad flows. */
    private val notAds = listOf(
        "androidx.",
        "com.ads.module.",
        "com.android.billingclient.",
        "com.google.android.gms.common.",
        "com.google.android.play.core.",
        "com.google.android.ump.",
    )

    @Test
    fun `every Activity the merged manifest declares is either an ad surface or a known non-ad screen`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        @Suppress("DEPRECATION")
        val declared = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES)
            .activities.orEmpty().map { it.name }
        assertTrue("The mediation stack declares its Activities", declared.any { it.startsWith("com.bytedance.sdk.openadsdk.") })
        val unclassified = declared.filterNot { name -> AdSurfaces.isAdSurface(name) || notAds.any(name::startsWith) }
        assertEquals(
            "A new SDK brought Activities: add its namespace to AdSurfaces (an ad) or to this list (not an ad)",
            emptyList<String>(), unclassified,
        )
    }

    @Test
    fun `each mediated network's landing and fullscreen Activities are ad surfaces`() {
        listOf(
            "com.google.android.gms.ads.AdActivity",
            "com.bytedance.sdk.openadsdk.activity.single.TTLandingPageActivity",
            "com.bytedance.sdk.openadsdk.activity.single.TTDelegateActivity",
            "com.mbridge.msdk.activity.MBCommonActivity",
            "com.vungle.ads.internal.ui.VungleActivity",
            "com.facebook.ads.AudienceNetworkActivity",
            "com.applovin.adview.AppLovinFullscreenActivity",
            "com.applovin.sdk.AppLovinWebViewActivity",
            "com.unity3d.services.ads.adunit.AdUnitActivity",
            "com.unity3d.ads.adplayer.FullScreenWebViewDisplay",
            "com.unity3d.ironsourceads.internal.services.InlineStoreActivity",
            "com.ironsource.sdk.controller.ControllerActivity",
        ).forEach { assertTrue(it, AdSurfaces.isAdSurface(it)) }
        listOf(
            "com.google.android.gms.common.api.GoogleApiActivity",
            "com.android.billingclient.api.ProxyBillingActivity",
            "io.onboardkit.ui.resume.ObResumeActivity",
            "com.example.MainActivity",
        ).forEach { assertTrue(it, !AdSurfaces.isAdSurface(it)) }
    }
}
