package io.onboardkit.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ads.module.admob.ResumeAds
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ui.pool.ObPoolResumeActivity
import io.onboardkit.ui.resume.ObResumeActivity

/** The screen `:ads` opens for native resume. */
internal object ObNativeResume : ResumeAds.NativeScreen {

    override fun prepare(context: Context) {
        if (OnboardingPool.routeResume) {
            preparePooled()
            return
        }
        if (!OnboardingSdk.isReady()) return
        val placement = AdPlacement.ResumeNative
        val unit = OnboardingSdk.configOrNull()?.ads?.nativeUnitFor(placement) ?: return
        if (OnboardingSdk.guard().skipReason(context, placement, unit) != null) return
        OnboardingSdk.provider()?.preloadNativeInBackground(
            context, NativeAdRequest(placement, unit, NativeTemplates.layoutForPlacement(placement)),
        )
    }

    override fun show(host: Activity) {
        if (OnboardingPool.routeResume) {
            // The pooled screen renews its own ad in onResume; a return to it opens nothing.
            if (OnboardingSdk.isReady() && host !is ObPoolResumeActivity) ObPoolResumeActivity.start(host)
            return
        }
        // Without config the screen's guard would restart the whole flow from the launcher.
        if (!OnboardingSdk.isReady()) return
        if (host is ObResumeActivity) host.renew() else ObResumeActivity.start(host)
    }

    /** The pooled screen opens after `native_flow.pool.resume.min_background_ms` away; classic keeps open_resume's delay. */
    override fun minBackgroundMs(): Long? =
        if (OnboardingPool.routeResume) ObPoolResumeActivity.runSnapshot().resumeMinBackgroundMs else null

    /** DashDrama loads native resume only when its screen opens; `resume_background_preload` warms it earlier. */
    private fun preparePooled() {
        if (!OnboardingSdk.isReady() || !ObPoolResumeActivity.runSnapshot().parity.resumeBackgroundPreload) return
        val preload = Runnable { if (OnboardingPool.isActive) OnboardingPool.preload(AdPlacement.ResumeNative) }
        if (Looper.myLooper() === Looper.getMainLooper()) preload.run() else Handler(Looper.getMainLooper()).post(preload)
    }
}
