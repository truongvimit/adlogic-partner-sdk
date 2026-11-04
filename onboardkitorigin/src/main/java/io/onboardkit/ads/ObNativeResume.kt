package io.onboardkit.ads

import android.app.Activity
import android.content.Context
import androidx.annotation.MainThread
import com.ads.module.admob.ResumeAds
import com.ads.module.helper.adnative.pool.LoadMode
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ui.pool.ObPoolResumeActivity
import io.onboardkit.ui.resume.ObResumeActivity

/** The screen `:ads` opens for native resume; each screen requests native_resume as it shows. */
internal object ObNativeResume : ResumeAds.NativeScreen {

    /** The classic screen's request; a load already running is joined, never doubled. */
    fun requestClassic(context: Context) {
        if (!OnboardingSdk.isReady()) return
        val placement = AdPlacement.ResumeNative
        val unit = OnboardingSdk.configOrNull()?.ads?.nativeUnitFor(placement) ?: return
        if (OnboardingSdk.guard().skipReason(context, placement, unit) != null) return
        OnboardingSdk.provider()?.preloadNativeInBackground(
            context, NativeAdRequest(placement, unit, NativeTemplates.layoutForPlacement(placement)),
        )
    }

    /**
     * Renews native_resume past the pool's gate: a held ad is refreshed and stays drawn until the new
     * one fills, an empty loader loads FORCED, and one already loading is left alone.
     */
    @MainThread
    fun renewPooled() {
        if (!OnboardingPool.refresh(AdPlacement.ResumeNative)) {
            OnboardingPool.preload(AdPlacement.ResumeNative, LoadMode.FORCED)
        }
    }

    override fun show(host: Activity) = show(host, afterAdClick = false)

    override fun show(host: Activity, afterAdClick: Boolean) {
        // Both screens exclude themselves; this also covers a routing switch while one is open.
        if (host is ObResumeActivity || host is ObPoolResumeActivity) return
        // Without config the screen's guard would restart the whole flow from the launcher.
        if (!OnboardingSdk.isReady()) return
        if (OnboardingPool.routeResume) {
            ObPoolResumeActivity.start(host, afterAdClick)
        } else {
            ObResumeActivity.start(host, afterAdClick)
        }
    }
}
