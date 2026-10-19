package io.onboardkit.ads

import android.app.Activity
import android.content.Context
import com.ads.module.admob.ResumeAds
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ui.pool.ObPoolResumeActivity
import io.onboardkit.ui.resume.ObResumeActivity

/** The screen `:ads` opens for native resume. */
internal object ObNativeResume : ResumeAds.NativeScreen {

    override fun prepare(context: Context) {
        // The pooled resume screen requests its native only once it opens, as DashDrama does.
        if (OnboardingPool.routeResume) return
        if (!OnboardingSdk.isReady()) return
        val placement = AdPlacement.ResumeNative
        val unit = OnboardingSdk.configOrNull()?.ads?.nativeUnitFor(placement) ?: return
        if (OnboardingSdk.guard().skipReason(context, placement, unit) != null) return
        OnboardingSdk.provider()?.preloadNativeInBackground(
            context, NativeAdRequest(placement, unit, NativeTemplates.layoutForPlacement(placement)),
        )
    }

    override fun show(host: Activity) {
        // Both screens exclude themselves; this also covers a routing switch while one is open.
        if (host is ObResumeActivity || host is ObPoolResumeActivity) return
        // Without config the screen's guard would restart the whole flow from the launcher.
        if (!OnboardingSdk.isReady()) return
        if (OnboardingPool.routeResume) ObPoolResumeActivity.start(host) else ObResumeActivity.start(host)
    }
}
